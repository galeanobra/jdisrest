"""Loading an evaluation function from a folder of user code, and wrapping it as an Evaluator."""
from __future__ import annotations

import importlib
import importlib.machinery
import importlib.util
import logging
import math
import numbers
import sys
import types
import unicodedata
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Callable

from ._types import EvalResult, Evaluator, Variables
from ._vector import ENCODINGS, DecisionVector
from ._worker import _coerce

log = logging.getLogger("jdisrest")

# The code folder of this process, once load_function has imported from one; None before.
_code_dir: Path | None = None


def load_function(code_dir: str | Path | None, module: str, attribute: str) -> Any:
    """
    Imports ``module`` and returns its ``attribute``, a function, a class or any other object.

    With ``code_dir``, the module is imported from that folder, which goes first in ``sys.path``
    for the rest of the process, so that the packages next to the module are imported from it too.
    A process imports from one code folder only: packages of the same name in two folders would
    mix silently, since Python keeps the first one imported. For the same reason a module of that
    name already imported from somewhere else is refused rather than reused. Mind that the folder
    also shadows any library of the same name imported later, such as a ``logging.py`` or a
    ``requests.py`` in it.

    Folders whose names are stored with decomposed accents (NFD), as macOS and some cloud drives do,
    become importable by their composed (NFC) name, the one source code uses: Python compares
    module names code point by code point, so the import would fail although the folder is there.
    Such a folder that has an ``__init__.py`` runs it when ``load_function`` first imports from the
    code folder.

    Args:
        code_dir:  Folder that holds the module, as ``<module>.py``, a compiled module (a sourceless
                   ``.pyc`` or an extension such as ``.pyd`` or ``.so``) or the package ``module``, or
                   None to import from the current ``sys.path``, for instance an installed package.
        module:    Name of the module, possibly dotted (``package.module``).
        attribute: Name of the attribute; dots reach nested ones (``Class.method``).

    Returns:
        The attribute.

    Raises:
        FileNotFoundError: if ``code_dir`` is not a folder or holds no such module.
        ImportError: if the process already imports from another code folder, if a module of that
            name is already imported from elsewhere, or if the import itself fails.
        AttributeError: if the module has no such attribute.
    """
    directory = _use_code_dir(Path(code_dir), module) if code_dir is not None else None
    importlib.invalidate_caches()  # the folder may have changed since Python last listed it
    loaded = importlib.import_module(module)
    if directory is not None:
        _check_location(module.split(".")[0], directory)
    target: Any = loaded
    for name in attribute.split("."):
        try:
            target = getattr(target, name)
        except AttributeError:
            raise AttributeError(f"module {module!r} has no attribute {attribute!r}") from None
    return target


def _use_code_dir(code_dir: Path, module: str) -> Path:
    """Checks the folder and the module, then puts the folder first in ``sys.path`` (once per process)."""
    global _code_dir
    directory = code_dir.resolve()
    if not directory.is_dir():
        raise FileNotFoundError(f"code folder {directory} does not exist")
    top = module.split(".")[0]
    if not (any((directory / f"{top}{suffix}").is_file() for suffix in importlib.machinery.all_suffixes())
            or (directory / top).is_dir() or _decomposed_folder(directory, top)):
        raise FileNotFoundError(f"{top}.py not found in {directory}")
    if _code_dir is not None and _code_dir != directory:
        raise ImportError(f"cannot import from {directory}: this process already imports from {_code_dir}, "
                          f"and packages of the same name in both folders would mix (one code folder per process)")
    _check_location(top, directory)
    if _code_dir is None:
        if str(directory) not in sys.path:
            sys.path.insert(0, str(directory))
        _alias_decomposed_folders(directory)
        _code_dir = directory
    return directory


def _check_location(name: str, directory: Path) -> None:
    """Raises ImportError if the module ``name`` is already imported from outside ``directory``."""
    loaded = sys.modules.get(name)
    if loaded is None:
        return
    file = getattr(loaded, "__file__", None)
    locations = [file] if file else list(getattr(loaded, "__path__", []))
    if not any(Path(location).resolve().is_relative_to(directory) for location in locations):
        where = ", ".join(map(str, locations)) or "a built-in module"
        raise ImportError(f"a module named {name!r} is already imported from {where}, not from {directory}")


def _decomposed_folder(directory: Path, name: str) -> bool:
    """Whether ``directory`` has a folder whose NFC name is ``name``, as stored with decomposed accents."""
    return any(entry.is_dir() and unicodedata.normalize("NFC", entry.name) == unicodedata.normalize("NFC", name)
               for entry in directory.iterdir())


def _alias_decomposed_folders(directory: Path) -> None:
    """Registers each folder whose name is not NFC-normalized under its NFC name, unless that name is taken."""
    for entry in directory.iterdir():
        name = unicodedata.normalize("NFC", entry.name)
        if entry.is_dir() and name != entry.name and name not in sys.modules:
            _register_package(name, entry)


def _register_package(name: str, folder: Path) -> None:
    init = folder / "__init__.py"
    if init.is_file():
        spec = importlib.util.spec_from_file_location(name, init, submodule_search_locations=[str(folder)])
        package = importlib.util.module_from_spec(spec)
        sys.modules[name] = package
        try:
            spec.loader.exec_module(package)
        except BaseException:
            del sys.modules[name]
            raise
    else:
        package = types.ModuleType(name)
        package.__path__ = [str(folder)]
        sys.modules[name] = package


class FunctionEvaluator(Evaluator):
    """
    Evaluator around a plain function, with optional checks of the variable and objective counts
    and of the encoding, and an opt-in penalty for non-finite objectives.

    The function receives the variables as a list: a copy of the :class:`DecisionVector` the
    worker hands over, with its layout, or a plain list of any other sequence, so that changing
    it never changes the caller's. It may return whatever :meth:`Worker.run`
    accepts from a function (an :class:`EvalResult`, a number for a single objective, a dict with
    ``"objectives"`` and optional ``"constraints"`` and ``"variables"``, or an object with those
    attributes) and also a sequence of objectives, such as a list, a tuple or a numpy array. The
    objectives become plain floats; constraints and variables are passed on unchanged.

    Both counts are checked only when given: ``number_of_variables``, the values of the flat
    vector (one per bit of a binary variable), before calling the function, so a malformed task
    wastes no evaluation, and ``number_of_objectives`` after it, which only fails earlier than
    the master, whose 422 answer would reject a wrong count anyway. ``encoding``, when given, is
    checked before calling the function too, against the encoding of a :class:`DecisionVector`
    (a plain sequence carries none, and is not checked).

    A NaN or infinite objective is kept by default: the worker then reports the evaluation as an
    error, and the master requeues the task until its failure limit discards it, each attempt a
    wasted evaluation when the function is deterministic. With ``non_finite_penalty``, such a
    result gets that value in every objective instead, with a warning in the log: one finite
    objective left as it was could still make the solution look non-dominated. Choose a value worse than any valid objective of the problem; there is no default
    because no value is safe for every problem.

    Args:
        function:             The evaluation function, ``(variables) -> result``.
        number_of_variables:  Number of values every task must have, or None not to check.
        number_of_objectives: Number of objectives the function must return, or None not to check.
        non_finite_penalty:   Value of every objective of a result with a non-finite objective, or
                              None to report such a result as an evaluation error.
        encoding:             Encoding every task must have, ``"int"``, ``"double"``, ``"binary"``
                              or ``"mixed"``, or None not to check.

    Raises:
        TypeError: if ``function`` is not callable.
        ValueError: if a count is not a positive integer, the penalty is not a finite number or
            the encoding is not one of the four.
    """

    def __init__(self, function: Callable[[Variables], Any], number_of_variables: int | None = None,
                 number_of_objectives: int | None = None, non_finite_penalty: float | None = None,
                 encoding: str | None = None):
        if not callable(function):
            raise TypeError(f"function must be callable, got {type(function).__name__}")
        _check_count("number_of_variables", number_of_variables)
        _check_count("number_of_objectives", number_of_objectives)
        if non_finite_penalty is not None and (isinstance(non_finite_penalty, bool)
                                               or not isinstance(non_finite_penalty, numbers.Real)
                                               or not math.isfinite(non_finite_penalty)):
            raise ValueError(f"non_finite_penalty must be a finite number, got {non_finite_penalty!r}")
        if encoding is not None and encoding not in ENCODINGS:
            raise ValueError(f"encoding must be one of {', '.join(ENCODINGS)}, got {encoding!r}")
        self.function = function
        self.number_of_variables = number_of_variables
        self.number_of_objectives = number_of_objectives
        self.non_finite_penalty = None if non_finite_penalty is None else float(non_finite_penalty)
        self.encoding = encoding

    def evaluate(self, variables: Variables) -> EvalResult:
        """
        Checks the variables, calls the function and converts its result.

        Raises:
            ValueError: if a count or the encoding is wrong or an objective is not a number; the
                message names the field.
            TypeError: if the function returns something that is not a result.
        """
        if self.number_of_variables is not None and len(variables) != self.number_of_variables:
            raise ValueError(f"variables has {len(variables)} values but the evaluator expects "
                             f"{self.number_of_variables}")
        vector = isinstance(variables, DecisionVector)
        if self.encoding is not None and vector and variables.encoding != self.encoding:
            raise ValueError(f"variables has encoding {variables.encoding} but the evaluator expects {self.encoding}")
        result = _as_result(self.function(variables.copy() if vector else list(variables)))
        objectives = _objective_floats(result.objectives)
        if self.number_of_objectives is not None and len(objectives) != self.number_of_objectives:
            raise ValueError(f"objectives has {len(objectives)} values but the evaluator expects "
                             f"{self.number_of_objectives}")
        if self.non_finite_penalty is not None and not all(math.isfinite(value) for value in objectives):
            log.warning(f"objectives {objectives} are not all finite — using {self.non_finite_penalty} "
                        f"for every objective")
            objectives = [self.non_finite_penalty] * len(objectives)
        return EvalResult(objectives=objectives, constraints=result.constraints, variables=result.variables)


def _check_count(name: str, value: int | None) -> None:
    if value is not None and (isinstance(value, bool) or not isinstance(value, numbers.Integral) or value < 1):
        raise ValueError(f"{name} must be a positive integer, got {value!r}")


def _as_result(value: Any) -> EvalResult:
    """What a function returned as an EvalResult: anything _coerce accepts, or a sequence of objectives."""
    if isinstance(value, (EvalResult, Mapping, numbers.Real)) or hasattr(value, "objectives"):
        return _coerce(value)
    if isinstance(value, (str, bytes)) or not hasattr(value, "__iter__"):
        raise TypeError(f"Cannot convert {type(value).__name__} to EvalResult")
    return EvalResult(objectives=list(value))


def _objective_floats(values: Any) -> list[float]:
    """The objectives as plain floats (NaN and infinity included); raises ValueError naming the offender."""
    out: list[float] = []
    for i, value in enumerate(values):
        if isinstance(value, bool) or not (isinstance(value, numbers.Real) or hasattr(type(value), "__float__")):
            raise ValueError(f"objectives[{i}] is not a number: {value!r}")
        out.append(float(value))
    return out

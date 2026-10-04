"""load_function and FunctionEvaluator against code folders written by the tests."""
import compileall
import json
import logging
import math
import sys
import unicodedata
from pathlib import Path

import pytest

from jdisrest import DecisionVector, EvalResult, FunctionEvaluator, Worker, load_function
from jdisrest import _loader
from jdisrest._worker import _result_body

# Imported by the test code with composed accents (NFC), stored on disk decomposed (NFD).
ACCENTED_PACKAGE = unicodedata.normalize("NFC", "Ångström")


# ── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def isolated_imports(monkeypatch, tmp_path):
    """Each test imports its own code folder, as a new process would."""
    monkeypatch.setattr(sys, "path", list(sys.path))
    monkeypatch.setattr(_loader, "_code_dir", None)
    before = set(sys.modules)
    yield
    for name in set(sys.modules) - before:
        module = sys.modules[name]
        locations = [getattr(module, "__file__", None)] + list(getattr(module, "__path__", []))
        if any(location and Path(location).resolve().is_relative_to(tmp_path.resolve()) for location in locations):
            del sys.modules[name]


def _code_folder(root, files, name="code"):
    """Writes a code folder from {relative path: source} and returns it."""
    folder = root / name
    folder.mkdir()
    for relative, source in files.items():
        path = folder / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
    return folder


def _accented_folder(root, with_init):
    """A code folder whose module imports a package stored under its NFD name."""
    package = unicodedata.normalize("NFD", ACCENTED_PACKAGE)
    files = {f"{package}/helper.py": "def scale(x):\n    return 2.0 * x\n",
             "fit.py": f"from {ACCENTED_PACKAGE} import helper\n"
                       f"import {ACCENTED_PACKAGE} as package\n\n"
                       "def evaluate(variables):\n"
                       "    return [helper.scale(variables[0]), getattr(package, 'OFFSET', 0.0)]\n"}
    if with_init:
        files[f"{package}/__init__.py"] = "OFFSET = 7.0\n"
    return _code_folder(root, files)


class Float32:
    """Stands for a number type that converts to float without registering as numbers.Real."""

    def __init__(self, value):
        self.value = value

    def __float__(self):
        return self.value


# ── load_function ───────────────────────────────────────────────────────────

def test_a_function_is_loaded_from_its_code_folder_with_the_modules_next_to_it(tmp_path):
    folder = _code_folder(tmp_path, {"helpers.py": "def scale(x):\n    return 3.0 * x\n",
                                     "fit.py": "from helpers import scale\n\n"
                                               "def evaluate(variables):\n    return [scale(variables[0])]\n"})

    evaluate = load_function(folder, "fit", "evaluate")

    assert evaluate([2.0]) == [6.0]
    assert sys.path[0] == str(folder.resolve())


@pytest.mark.parametrize("with_init, offset", [(False, 0.0), (True, 7.0)])
def test_a_folder_named_with_decomposed_accents_is_imported_by_its_composed_name(tmp_path, with_init, offset):
    folder = _accented_folder(tmp_path, with_init)

    evaluate = load_function(folder, "fit", "evaluate")

    assert evaluate([1.5]) == [3.0, offset], "the package imports, and runs its __init__.py when it has one"


def test_the_module_itself_may_be_in_a_folder_named_with_decomposed_accents(tmp_path):
    folder = _accented_folder(tmp_path, with_init=False)

    scale = load_function(folder, f"{ACCENTED_PACKAGE}.helper", "scale")

    assert scale(1.5) == 3.0


def test_a_dotted_module_and_attribute_reach_a_method_of_a_class_in_a_package(tmp_path):
    folder = _code_folder(tmp_path, {"models/__init__.py": "",
                                     "models/linear.py": "class Model:\n"
                                                         "    @staticmethod\n"
                                                         "    def evaluate(variables):\n"
                                                         "        return [sum(variables)]\n"})

    evaluate = load_function(folder, "models.linear", "Model.evaluate")

    assert evaluate([1.0, 2.0]) == [3.0]


def test_without_a_code_folder_the_module_comes_from_the_current_path():
    assert load_function(None, "math", "sqrt") is math.sqrt


def test_a_missing_code_folder_is_reported(tmp_path):
    with pytest.raises(FileNotFoundError, match="does not exist"):
        load_function(tmp_path / "missing", "fit", "evaluate")


def test_a_folder_without_the_module_is_reported(tmp_path):
    with pytest.raises(FileNotFoundError, match="fit.py not found in"):
        load_function(tmp_path, "fit", "evaluate")


def test_a_compiled_module_without_its_source_is_loaded(tmp_path):
    folder = _code_folder(tmp_path, {"fit.py": "def evaluate(variables):\n    return [2.0 * variables[0]]\n"})
    assert compileall.compile_dir(folder, legacy=True, quiet=1)
    (folder / "fit.py").unlink()

    evaluate = load_function(folder, "fit", "evaluate")

    assert evaluate([1.5]) == [3.0]


def test_a_missing_attribute_is_reported_with_the_module(tmp_path):
    folder = _code_folder(tmp_path, {"fit.py": "def evaluate(variables):\n    return [0.0]\n"})

    with pytest.raises(AttributeError, match="module 'fit' has no attribute 'evaluat'"):
        load_function(folder, "fit", "evaluat")


def test_an_import_that_fails_inside_the_module_is_reported(tmp_path):
    folder = _code_folder(tmp_path, {"fit.py": "import a_dependency_that_is_not_installed\n"})

    with pytest.raises(ImportError, match="a_dependency_that_is_not_installed"):
        load_function(folder, "fit", "evaluate")


def test_the_same_code_folder_serves_several_loads(tmp_path):
    folder = _code_folder(tmp_path, {"fit.py": "def first(v):\n    return [1.0]\n\ndef second(v):\n    return [2.0]\n"})

    assert load_function(folder, "fit", "first")([]) == [1.0]
    assert load_function(folder, "fit", "second")([]) == [2.0]
    assert sys.path.count(str(folder.resolve())) == 1


@pytest.mark.parametrize("module", ["fit", "other"])
def test_a_second_code_folder_in_the_same_process_is_refused(tmp_path, module):
    first = _code_folder(tmp_path, {"fit.py": "def evaluate(variables):\n    return [1.0]\n"}, "first")
    second = _code_folder(tmp_path, {f"{module}.py": "def evaluate(variables):\n    return [2.0]\n"}, "second")
    load_function(first, "fit", "evaluate")

    # A module of the same name would otherwise come silently from the first folder, already imported.
    with pytest.raises(ImportError, match="one code folder per process"):
        load_function(second, module, "evaluate")


def test_a_module_already_imported_from_elsewhere_is_refused_rather_than_reused(tmp_path):
    folder = _code_folder(tmp_path, {"json.py": "def evaluate(variables):\n    return [1.0]\n"})

    with pytest.raises(ImportError, match="a module named 'json' is already imported from"):
        load_function(folder, "json", "evaluate")
    assert json.loads("[1]") == [1], "the standard library module is untouched"


# ── FunctionEvaluator ───────────────────────────────────────────────────────

@pytest.mark.parametrize("returned, objectives", [
    ((1, 2.5, -0.5), [1.0, 2.5, -0.5]),
    ([Float32(3.0)], [3.0]),
    (4, [4.0]),
    ({"objectives": (1, 2)}, [1.0, 2.0]),
    (EvalResult(objectives=[5]), [5.0]),
])
def test_what_the_function_returns_becomes_float_objectives(returned, objectives):
    result = FunctionEvaluator(lambda variables: returned).evaluate([0.0])

    assert result.objectives == objectives
    assert all(type(value) is float for value in result.objectives)


def test_a_numpy_array_of_objectives_is_accepted():
    np = pytest.importorskip("numpy")

    result = FunctionEvaluator(lambda variables: np.array([1.5, 2.0], dtype=np.float32)).evaluate([0.0])

    assert result.objectives == [1.5, 2.0]
    assert all(type(value) is float for value in result.objectives)


def test_constraints_and_repaired_variables_are_passed_on():
    evaluator = FunctionEvaluator(lambda v: EvalResult(objectives=[1.0], constraints=[-0.5], variables=[3, 4]))

    result = evaluator.evaluate([1, 2])

    assert (result.constraints, result.variables) == ([-0.5], [3, 4])


def test_the_function_receives_the_variables_as_a_list():
    received = []

    FunctionEvaluator(lambda variables: received.append(variables) or [0.0]).evaluate((1, 2))

    assert received == [[1, 2]] and type(received[0]) is list


def test_a_numpy_array_reaches_the_function_as_a_list():
    np = pytest.importorskip("numpy")
    received = []

    FunctionEvaluator(lambda variables: received.append(variables) or [0.0]).evaluate(np.array([1.5, 2.0]))

    assert received == [[1.5, 2.0]] and type(received[0]) is list


def test_the_function_receives_a_copy_of_the_decision_vector_with_its_layout():
    task = DecisionVector([4, 1, 0, 1], encoding="mixed", segment_sizes=[1, 3], segment_encodings=["int", "binary"],
                          bits_per_variable=[3])
    received = []

    def evaluate(variables):
        received.append(variables)
        variables[1] = 0  # a repair in place
        return EvalResult(objectives=[1.0], variables=variables)

    result = FunctionEvaluator(evaluate).evaluate(task)

    assert type(received[0]) is DecisionVector and received[0] is not task
    assert (received[0].segment_sizes, received[0].bits_per_variable) == ((1, 3), (3,))
    assert received[0].binary_variables() == [[0, 0, 1]]
    assert task == [4, 1, 0, 1], "the worker's vector is unchanged"
    assert result.variables == [4, 0, 0, 1]


def test_a_wrong_number_of_variables_is_rejected_before_the_function_is_called():
    def evaluate(variables):
        raise AssertionError("must not be called")

    with pytest.raises(ValueError, match="variables has 2 values but the evaluator expects 3"):
        FunctionEvaluator(evaluate, number_of_variables=3).evaluate([0.0, 0.0])


def test_a_wrong_encoding_is_rejected_before_the_function_is_called():
    def evaluate(variables):
        raise AssertionError("must not be called")

    task = DecisionVector([4, 1], encoding="mixed", segment_sizes=[1, 1], segment_encodings=["int", "binary"],
                          bits_per_variable=[1])
    with pytest.raises(ValueError, match="variables has encoding mixed but the evaluator expects binary"):
        FunctionEvaluator(evaluate, encoding="binary").evaluate(task)


def test_the_encoding_is_checked_on_a_decision_vector_only():
    evaluator = FunctionEvaluator(lambda v: [float(sum(v))], encoding="binary")

    assert evaluator.evaluate(DecisionVector([1, 0, 1], encoding="binary", bits_per_variable=[3])).objectives == [2.0]
    assert evaluator.evaluate([1, 2]).objectives == [3.0], "a plain list carries no encoding"


def test_a_wrong_number_of_objectives_is_rejected():
    with pytest.raises(ValueError, match="objectives has 2 values but the evaluator expects 3"):
        FunctionEvaluator(lambda v: (1.0, 2.0), number_of_objectives=3).evaluate([0.0])


def test_without_a_penalty_a_non_finite_objective_is_left_for_the_worker_to_report():
    result = FunctionEvaluator(lambda v: (1.0, math.nan)).evaluate([0.0])

    assert math.isnan(result.objectives[1])
    with pytest.raises(ValueError, match=r"objectives\[1\] is not finite"):
        _result_body("w", result, 0)


@pytest.mark.parametrize("broken", [math.nan, math.inf, -math.inf])
def test_with_a_penalty_a_non_finite_objective_sets_every_objective_to_it(broken, caplog):
    evaluator = FunctionEvaluator(lambda v: (1.0, broken, 2.0), non_finite_penalty=1e6)

    with caplog.at_level(logging.WARNING, logger="jdisrest"):
        result = evaluator.evaluate([0.0])

    assert result.objectives == [1e6, 1e6, 1e6]
    assert any("not all finite" in record.getMessage() for record in caplog.records)


def test_with_a_penalty_a_finite_result_is_unchanged():
    assert FunctionEvaluator(lambda v: (1.0, 2.0), non_finite_penalty=1e6).evaluate([0.0]).objectives == [1.0, 2.0]


@pytest.mark.parametrize("returned, error", [
    ([1.0, "2"], r"objectives\[1\] is not a number"),
    ([True], r"objectives\[0\] is not a number"),
    ([None], r"objectives\[0\] is not a number"),
])
def test_an_objective_that_is_not_a_number_is_rejected_with_its_index(returned, error):
    with pytest.raises(ValueError, match=error):
        FunctionEvaluator(lambda v: returned).evaluate([0.0])


@pytest.mark.parametrize("returned", ["1.0", b"1", object(), True])
def test_a_return_value_that_is_not_a_result_is_rejected(returned):
    with pytest.raises(TypeError, match="Cannot convert"):
        FunctionEvaluator(lambda v: returned).evaluate([0.0])


@pytest.mark.parametrize("arguments", [
    {"number_of_variables": 0},
    {"number_of_objectives": -1},
    {"number_of_variables": True},
    {"number_of_objectives": 2.0},
    {"non_finite_penalty": math.nan},
    {"non_finite_penalty": math.inf},
    {"non_finite_penalty": "1e6"},
    {"encoding": "bits"},
    {"encoding": "Binary"},
])
def test_bad_checks_are_rejected_when_the_evaluator_is_built(arguments):
    with pytest.raises(ValueError, match=next(iter(arguments))):
        FunctionEvaluator(lambda v: [0.0], **arguments)


def test_the_function_must_be_callable():
    with pytest.raises(TypeError, match="function must be callable"):
        FunctionEvaluator(3)


def test_a_penalized_result_reaches_the_master_as_a_result():
    responses = pytest.importorskip("responses")
    master = "http://master.test:8080"
    with responses.RequestsMock(assert_all_requests_are_fired=False) as mock:
        mock.add(responses.POST, f"{master}/api/v1/workers/heartbeat", status=200)
        mock.add(responses.GET, f"{master}/api/v1/tasks/next", json={"taskId": 3, "variables": [1, 2]}, status=200)
        mock.add(responses.GET, f"{master}/api/v1/tasks/next", status=410)
        mock.add(responses.POST, f"{master}/api/v1/tasks/3/result", status=200)

        Worker(master, worker_id="w").run(FunctionEvaluator(lambda v: (sum(v), math.nan), non_finite_penalty=1e6))

        posted = [json.loads(call.request.body) for call in mock.calls if call.request.url.endswith("/tasks/3/result")]
    assert [body["objectives"] for body in posted] == [[1e6, 1e6]]

"""Command-line worker (``python -m jdisrest``) and the helpers it shares with other worker command lines."""
from __future__ import annotations

import argparse
import logging
import math
import signal
import threading
from typing import Any, Callable, Sequence
from urllib.parse import urlsplit

from ._loader import FunctionEvaluator, load_function
from ._types import EvalResult, Evaluator, Variables
from ._worker import Worker

log = logging.getLogger("jdisrest")

# Log tools parse this layout (time, level, logger, message), so keep it stable.
LOG_FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"
LOG_LEVELS = ("DEBUG", "INFO", "WARNING", "ERROR")

# Exit status of main() when the worker gave the master up as lost, so that a batch script can
# tell a worker that ran to the end (0) from one whose master is gone. A worker that came from an
# endpoint file and was still busy when the master shut down at the end of the run finds the file
# deleted and exits with 0 (see Worker.run); one given --master cannot tell, and exits with 3 then
# too. 1 and 2 are taken.
EXIT_MASTER_LOST = 3
_MASTER_LOST = Worker.MASTER_LOST


def add_worker_arguments(parser: argparse.ArgumentParser) -> None:
    """
    Adds the options that connect a worker to the master, which :func:`run_worker` reads.

    ``--master URL`` and ``--endpoint FILE`` are mutually exclusive; without either, the worker
    waits up to ``--timeout`` seconds for ``.master-endpoint`` in the current folder. The endpoint
    file is accepted as soon as it exists, so a file left by an earlier run makes the worker
    connect to a master that is gone: delete it before starting the master. ``--worker-id`` names
    the worker in the master's logs and status (default: a random ``worker-py-...`` id).

    Args:
        parser: The parser to extend, for instance that of a downstream worker command line that
                builds its own evaluator.
    """
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--master", type=_master_url, metavar="URL",
                        help="master URL, for instance http://10.0.0.1:8080")
    source.add_argument("--endpoint", default=".master-endpoint", metavar="FILE",
                        help="endpoint file written by the master, waited for if missing (default: .master-endpoint)")
    parser.add_argument("--timeout", type=_non_negative_int, default=300, metavar="SECONDS",
                        help="seconds to wait for the endpoint file (default: 300)")
    parser.add_argument("--worker-id", metavar="ID", help="worker identifier (default: a random one)")


def configure_logging(level: int | str = "INFO") -> None:
    """
    Sends the log records of the ``jdisrest`` logger, and every other, to stderr as
    ``<time> <LEVEL> <logger>: <message>``. Like :func:`logging.basicConfig`, it does nothing when
    the root logger already has handlers, so an application's own logging set-up wins.

    Args:
        level: Lowest level shown, a :mod:`logging` level or its name.
    """
    logging.basicConfig(level=level, format=LOG_FORMAT)


def run_worker(args: argparse.Namespace, evaluate: Evaluator | Callable[[Variables], EvalResult]) -> str:
    """
    Connects to the master given by the options of :func:`add_worker_arguments` and evaluates its
    tasks until the run finishes, the master is gone or the worker is interrupted.

    Args:
        args:     Parsed options with ``master``, ``endpoint``, ``timeout`` and ``worker_id``.
        evaluate: An :class:`Evaluator` or a function, as :meth:`Worker.run` takes them.

    Returns:
        Why the worker stopped, as :meth:`Worker.run` returns it: ``"finished"``,
        ``"master-lost"`` or ``"interrupted"``.

    Raises:
        ValueError: if ``args.master`` is an empty string, for instance an unset variable in a
            launcher script, which would otherwise read as "no master given", or is not an
            ``http://`` or ``https://`` URL.
        TimeoutError: if the endpoint file does not appear within ``args.timeout`` seconds.
    """
    if args.master is not None:
        if not args.master.strip():
            raise ValueError("--master is empty")
        problem = _url_problem(args.master)
        if problem:
            raise ValueError(f"--master {problem}")
        worker = Worker(args.master, worker_id=args.worker_id)
    else:
        worker = Worker.wait_for_endpoint(args.endpoint, timeout=args.timeout, worker_id=args.worker_id)
    return worker.run(evaluate)


def _load_evaluator(spec: str, code_dir: str | None = None, number_of_variables: int | None = None,
                    number_of_objectives: int | None = None,
                    non_finite_penalty: float | None = None) -> FunctionEvaluator:
    """
    The evaluator named ``MODULE:ATTR``, wrapped in a :class:`FunctionEvaluator` with the given
    checks. ``ATTR`` may be a function, an :class:`Evaluator` subclass, which is instantiated
    without arguments, or an :class:`Evaluator` instance.

    Raises:
        ValueError: if ``spec`` is not ``MODULE:ATTR``, or for a bad count or penalty.
        TypeError: if ``ATTR`` is none of the three.
        FileNotFoundError, ImportError, AttributeError: as :func:`load_function`.
    """
    module, _, attribute = spec.partition(":")
    if not module or not attribute:
        raise ValueError(f"evaluator {spec!r} is not MODULE:ATTR")
    target: Any = load_function(code_dir, module, attribute)
    if isinstance(target, type):
        if not issubclass(target, Evaluator):
            raise TypeError(f"{spec} is a class that does not extend jdisrest.Evaluator")
        target = target()
    if isinstance(target, Evaluator):
        function = target.evaluate
    elif callable(target):
        function = target
    else:
        raise TypeError(f"{spec} is not a function, an Evaluator subclass or an Evaluator instance")
    return FunctionEvaluator(function, number_of_variables, number_of_objectives, non_finite_penalty)


def main(argv: Sequence[str] | None = None, prog: str | None = None) -> int:
    """
    Runs the command-line worker; returns the exit status: 0 once the worker stops because the
    run finished or was stopped, or because the worker was interrupted; 3
    (:data:`EXIT_MASTER_LOST`) when it gave the master up as lost (with ``--master``, also when
    the master shut down at the end of the run while the worker was busy; see
    :data:`EXIT_MASTER_LOST`); 1 if the evaluator cannot be loaded or the master's endpoint file
    never appears; 2 for bad options.

    SIGTERM, which batch schedulers send to cancel or preempt a job, interrupts the worker as
    Ctrl+C does, so it stops cleanly; the task it was evaluating is requeued by the master's
    watchdog once the worker's heartbeats stop.
    """
    args = _parser(prog).parse_args(argv)
    configure_logging(args.log_level)
    try:
        evaluator = _load_evaluator(args.evaluator, args.code_dir, args.variables, args.objectives,
                                    args.non_finite_penalty)
    except (ImportError, OSError, AttributeError, TypeError, ValueError) as error:
        log.error(f"Cannot load evaluator {args.evaluator}: {error}")
        return 1
    previous = _interrupt_on_sigterm()
    try:
        reason = run_worker(args, evaluator)
    except TimeoutError as error:
        log.error(str(error))
        return 1
    except KeyboardInterrupt:
        log.info("Worker interrupted before connecting to the master")
        return 0
    finally:
        if previous is not None:
            signal.signal(signal.SIGTERM, previous)
    return EXIT_MASTER_LOST if reason == _MASTER_LOST else 0


def console_main() -> int:
    """
    Entry point of the ``jdisrest-worker`` console script: :func:`main` with the script's own
    name in its usage and error messages, instead of the path of the launcher that runs it.
    """
    return main(prog="jdisrest-worker")


def _parser(prog: str | None) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=prog, description="jdisrest worker: evaluates the tasks of a master with a Python function "
                               "until the run finishes.")
    parser.add_argument("--evaluator", required=True, type=_evaluator_spec, metavar="MODULE:ATTR",
                        help="function, Evaluator subclass or Evaluator instance ATTR of the module MODULE")
    parser.add_argument("--code-dir", metavar="DIR",
                        help="folder that holds MODULE and the packages it imports (default: import MODULE from "
                             "sys.path: the installed packages and, with python -m, the current folder)")
    add_worker_arguments(parser)
    parser.add_argument("--variables", type=_positive_int, metavar="N",
                        help="number of variables every task must have (default: not checked)")
    parser.add_argument("--objectives", type=_positive_int, metavar="N",
                        help="number of objectives the evaluator must return (default: not checked here; "
                             "the master rejects a wrong count)")
    parser.add_argument("--non-finite-penalty", type=_finite_float, metavar="X",
                        help="value of every objective of a result with a NaN or infinite objective (default: "
                             "none; such a result is reported to the master as an evaluation error)")
    parser.add_argument("--log-level", type=str.upper, choices=LOG_LEVELS, default="INFO",
                        help="lowest level of the messages logged (default: INFO)")
    return parser


def _interrupt_on_sigterm():
    """Makes SIGTERM raise KeyboardInterrupt; returns the handler to restore, or None if none was installed."""
    if threading.current_thread() is not threading.main_thread():
        return None  # signal handlers can only be installed from the main thread
    return signal.signal(signal.SIGTERM, _interrupt)


def _interrupt(signum, frame):
    raise KeyboardInterrupt


def _evaluator_spec(text: str) -> str:
    module, _, attribute = text.partition(":")
    if not module.strip() or not attribute.strip():
        raise argparse.ArgumentTypeError(f"must be MODULE:ATTR, not {text!r}")
    return text


def _master_url(text: str) -> str:
    if not text.strip():
        raise argparse.ArgumentTypeError("must not be empty")
    problem = _url_problem(text)
    if problem:
        raise argparse.ArgumentTypeError(problem)
    return text


def _url_problem(text: str) -> str | None:
    """Why ``text`` cannot be the URL of a master, or None if it can: the worker only speaks HTTP(S)."""
    try:
        parts = urlsplit(text.strip())
        if parts.scheme in ("http", "https") and parts.netloc:
            return None
    except ValueError:  # for instance an unclosed IPv6 bracket
        pass
    return f"must be an http:// or https:// URL such as http://10.0.0.1:8080, not {text!r}"


def _positive_int(text: str) -> int:
    value = _integer(text)
    if value < 1:
        raise argparse.ArgumentTypeError(f"must be at least 1, not {text}")
    return value


def _non_negative_int(text: str) -> int:
    value = _integer(text)
    if value < 0:
        raise argparse.ArgumentTypeError(f"must not be negative, not {text}")
    return value


def _integer(text: str) -> int:
    try:
        return int(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not an integer: {text!r}") from None


def _finite_float(text: str) -> float:
    try:
        value = float(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not a number: {text!r}") from None
    if not math.isfinite(value):
        raise argparse.ArgumentTypeError(f"must be finite, not {text}")
    return value

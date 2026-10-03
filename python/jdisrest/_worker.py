from __future__ import annotations

import json
import logging
import math
import numbers
import socket
import threading
import time
import uuid
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Callable, Union

import requests

from ._types import EvalResult, Evaluator, Variables

log = logging.getLogger("jdisrest")

# Answers to the report of a task (POST /result or /error) that are not failures of the master:
# 404, the master no longer holds the task (its watchdog requeued it, or the run is over), and
# the rejections 400, 413, 415 and 422, where the master could not apply the report and has
# already counted a failed evaluation of the task (requeued, or discarded after its failure limit),
# or, for a 422, ignored it because another worker holds the task now (a 400, 413 or 415 counts
# whoever holds the task: the master cannot read the workerId of a body it has not decoded). After
# a stop or the end of the run, the master counts nothing and answers 404 instead of 422. The Java
# RestWorker handles the same answers, and resets its count of failed requests on the same ones.
_HANDLED_ANSWERS = (400, 404, 413, 415, 422)


class _NoTaskId(requests.RequestException):
    """A task payload that names no task (no integer ``taskId``): a failed request, as in RestWorker."""


class Worker:
    """
    Python worker for the jdisrest distributed optimization framework.

    Connects to a Java master, requests tasks, evaluates them, and returns results.
    Handles heartbeats, master-dead detection, and evaluation error reporting
    automatically.

    Quick start::

        from jdisrest import Worker, EvalResult

        def evaluate(variables):
            return EvalResult(objectives=[sum(v**2 for v in variables)])

        # Read master URL from the file the master writes on startup:
        Worker.from_endpoint(".master-endpoint").run(evaluate)

        # Or provide the URL directly:
        Worker("http://10.0.0.1:8080").run(evaluate)

    ``worker_id`` (default: ``worker-py-`` and eight random hex digits) must be
    unique among the workers of a run: the master tracks one task in flight per
    id and requeues it when that id asks for a new task (it takes the request as
    a sign that the result was lost), so workers that share an id keep taking
    each other's tasks back and most of their results are refused with 404.
    """

    HEARTBEAT_INTERVAL     = 15   # seconds between heartbeats
    HEARTBEAT_RETRY_DELAY  = 5    # seconds before retrying a failed heartbeat
    HEARTBEAT_TIMEOUT      = 5    # seconds to wait for the answer to a heartbeat
    REQUEST_TIMEOUT        = 40   # seconds to wait for a task (> master's 30s long-poll)
    RETRY_DELAY            = 10   # seconds to wait after a failed request to the master
    NO_TASK_DELAY          = 5    # seconds to wait when master returns 204 (no task yet)
    EVAL_ERROR_DELAY       = 1    # seconds to wait after the 2nd evaluation error in a row, then doubled
    MAX_EVAL_ERROR_DELAY   = 60   # longest wait after evaluation errors in a row
    MAX_CONSECUTIVE_ERRORS = 5    # failed requests in a row before assuming master is dead
    MAX_HEARTBEAT_FAILURES = 3    # heartbeat failures in a row before stopping

    # Why run() returned.
    FINISHED    = "finished"      # the master answered 410: the run finished or was stopped
    MASTER_LOST = "master-lost"   # the master was given up as dead
    INTERRUPTED = "interrupted"   # KeyboardInterrupt (Ctrl+C, or SIGTERM under python -m jdisrest)

    def __init__(self, master_url: str, worker_id: str | None = None):
        self.master_url = master_url.rstrip("/")
        self.worker_id  = worker_id or f"worker-py-{uuid.uuid4().hex[:8]}"
        self._stop      = threading.Event()
        # Held while run() runs: acquired without waiting, so a second run() at once fails.
        self._run_lock  = threading.Lock()
        # Reused by every run (a closed Session opens new connections on its next request).
        self._session   = requests.Session()
        self._session.headers.update({"Content-Type": "application/json"})
        # The endpoint file the worker was created from and its content (see from_endpoint).
        self._endpoint: tuple[Path, bytes] | None = None

        log.info(f"Worker {self.worker_id} → {self.master_url}")

    # ── Factory methods ────────────────────────────────────────────────────

    @classmethod
    def from_endpoint(
        cls,
        path: str | Path = ".master-endpoint",
        worker_id: str | None = None,
    ) -> "Worker":
        """
        Create a Worker by reading the master URL from a .master-endpoint file.

        The master writes this file once its REST server is listening, atomically
        (write then rename), so a file that exists is never half-written. It can be
        stale, though: a file left behind by an earlier master that is no longer
        running (it crashed, was killed or was never shut down) points to a server
        that is gone, and the worker then stops as ``"master-lost"`` once its
        requests have failed :attr:`MAX_CONSECUTIVE_ERRORS` times in a row. Delete
        the file before starting a new master in the same folder.

        The worker remembers the file: when the master stops answering, :meth:`run`
        returns :attr:`FINISHED` instead of :attr:`MASTER_LOST` if the file is gone
        or names another master by then, since a master deletes it when it shuts
        down at the end of a run, while one that died leaves it behind.

        Args:
            path:      Path to the .master-endpoint JSON file.
                       Default: ".master-endpoint" in the current directory.
            worker_id: Optional custom worker ID.

        Example::

            Worker.from_endpoint().run(evaluate)
            Worker.from_endpoint("/shared/fs/.master-endpoint").run(evaluate)
        """
        p    = Path(path).absolute()  # an evaluator that changes the working folder must not lose it
        raw  = p.read_bytes()
        data = json.loads(raw)
        url  = data.get("url") or f"http://{data['host']}:{data['port']}"
        worker = cls(master_url=url, worker_id=worker_id)
        worker._endpoint = (p, raw)
        return worker

    @classmethod
    def wait_for_endpoint(
        cls,
        path: str | Path = ".master-endpoint",
        timeout: int = 300,
        poll_interval: int = 5,
        worker_id: str | None = None,
    ) -> "Worker":
        """
        Wait until .master-endpoint appears, then create a Worker.

        Useful when master and worker are started at the same time and you
        don't know exactly when the master will be ready. A file that already
        exists is used at once, even one left behind by an earlier master (see
        :meth:`from_endpoint`).

        Args:
            path:          Path to the .master-endpoint JSON file.
            timeout:       Maximum seconds to wait. Raises TimeoutError if exceeded.
            poll_interval: Seconds between checks.
            worker_id:     Optional custom worker ID.
        """
        p = Path(path)
        waited = 0
        while not p.exists():
            if waited >= timeout:
                raise TimeoutError(
                    f".master-endpoint not found at '{path}' after {timeout}s. "
                    "Is the master running?"
                )
            log.info(f"Waiting for master endpoint at '{path}' ({waited}/{timeout}s)...")
            time.sleep(poll_interval)
            waited += poll_interval
        return cls.from_endpoint(path, worker_id=worker_id)

    # ── Main entry point ───────────────────────────────────────────────────

    def run(self, evaluate: Union[Evaluator, Callable[[Variables], Any]]) -> str:
        """
        Start the worker. Blocks until the master signals completion, the master
        is detected as dead or the worker is interrupted, and returns which of the
        three happened. The same worker can run again once ``run`` has returned
        (one run at a time).

        The master is given up as dead after :attr:`MAX_CONSECUTIVE_ERRORS` failed
        requests in a row, or after :attr:`MAX_HEARTBEAT_FAILURES` failed heartbeats
        in a row. A request fails on a network error and on an HTTP answer the
        protocol does not expect: a 5xx, a 4xx other than the answers to the
        report of a task listed below, a 2xx other than 200 to a task request, or
        a 200 whose payload names no task (no integer ``taskId``).
        So a proxy that keeps answering 502 for a master that is gone stops the
        worker as a refused connection does. A heartbeat fails on a network error
        and on any answer other than 2xx. The count of failed requests starts
        again whenever the master answers as the protocol expects: 204 to a task
        request, or an answer to the report of a task, whether it took it (2xx)
        or not (404, the task is no longer in flight or the run has been
        stopped or has ended; 400, 413, 415 or 422, the master could not apply
        the report and counted a failed evaluation, unless the run has been
        stopped or has ended). A task received is not enough, since its result
        still has to get through.

        Failures of a task are reported to the master
        (``POST /api/v1/tasks/{id}/error``), which then requeues the task at once,
        or discards it after its failure limit, instead of keeping it in flight:

        - An evaluation that raises, or whose result cannot be sent (no objective,
          a NaN or infinite value, something that is not a number), with the
          exception type and message (``"ZeroDivisionError: division by zero"``).
          The traceback of an exception raised by the evaluator is logged with the
          error. From the second failed evaluation in a row, the worker waits
          :attr:`EVAL_ERROR_DELAY` seconds before asking for the next task, twice
          as long after each further one, up to :attr:`MAX_EVAL_ERROR_DELAY`: an
          evaluator that always fails then no longer spins through the tasks, and
          once the wait has reached its cap takes at most one task in that time.
        - A task payload that names its task but cannot be evaluated (no list of
          finite numbers as variables), and a result that did not get through (a
          network error, or an answer that is not one of the above), best-effort:
          a report that fails too is only logged.

        Args:
            evaluate: An :class:`Evaluator` instance, any other object with an
                      ``evaluate(variables)`` method, or a plain callable
                      ``(variables) -> result``. ``variables`` is a list of
                      ints for integer-encoded problems and of floats for
                      real-encoded ones (a mix for composite problems whose
                      segments differ).
                      The result may be an :class:`EvalResult`, a plain number
                      (single objective), a sequence of objectives (a list, a
                      tuple or a numpy array), a dict with ``"objectives"`` and
                      optional ``"constraints"`` and ``"variables"``, or an object
                      with those attributes.

        Returns:
            Why the worker stopped: :attr:`FINISHED` (``"finished"``) when the
            master answered 410, because the run finished or was stopped, or when
            it stopped answering and the endpoint file this worker was created from
            (:meth:`from_endpoint`, :meth:`wait_for_endpoint`) is gone or names
            another master, because the master shut down (it deletes the file);
            :attr:`MASTER_LOST` (``"master-lost"``) when the master was given up as
            dead otherwise (a worker created from a URL cannot tell a master that
            shut down from one that died); :attr:`INTERRUPTED`
            (``"interrupted"``) on a KeyboardInterrupt.

        Raises:
            TypeError: if ``evaluate`` is not an Evaluator, has no ``evaluate``
                       method and is not callable (checked before connecting).
            RuntimeError: if this worker is already running.

        Example::

            # Function style
            Worker("http://master:8080").run(lambda v: EvalResult([sum(v)]))

            # Class style
            class MyEval(Evaluator):
                def evaluate(self, variables):
                    return EvalResult(objectives=[-simulate(variables)])

            Worker("http://master:8080").run(MyEval())
        """
        evaluator = _wrap(evaluate)
        if not self._run_lock.acquire(blocking=False):
            raise RuntimeError(f"Worker {self.worker_id} is already running")
        try:
            # A fresh event per run: a heartbeat thread of an earlier run only ever sees its own.
            self._stop = threading.Event()
            heartbeat = None
            reason = self.INTERRUPTED
            try:
                heartbeat = self._start_heartbeat()
                reason = self._loop(evaluator)
                if reason == self.MASTER_LOST and self._endpoint_withdrawn():
                    # The master shut down at the end of the run while this worker was busy.
                    log.info(f"Worker {self.worker_id}: master endpoint file {self._endpoint[0]} removed "
                             f"or replaced — the run is over")
                    reason = self.FINISHED
            except KeyboardInterrupt:
                log.info(f"Worker {self.worker_id}: interrupted")
            finally:
                self._stop.set()
                if heartbeat is not None:
                    heartbeat.join(timeout=2 * self.HEARTBEAT_TIMEOUT)
                self._session.close()
                log.info(f"Worker {self.worker_id}: stopped")
            return reason
        finally:
            self._run_lock.release()

    # ── Internal loops ─────────────────────────────────────────────────────

    def _loop(self, evaluator: Evaluator) -> str:
        consecutive_errors = 0       # failed requests to the master in a row
        consecutive_eval_errors = 0  # failed evaluations in a row

        while not self._stop.is_set():
            try:
                resp = self._session.get(
                    f"{self.master_url}/api/v1/tasks/next",
                    params={"workerId": self.worker_id},
                    timeout=self.REQUEST_TIMEOUT,
                )

                if resp.status_code == 204:
                    consecutive_errors = 0
                    self._stop.wait(self.NO_TASK_DELAY)
                    continue
                if resp.status_code == 410:
                    log.info(f"Worker {self.worker_id}: master algorithm finished")
                    return self.FINISHED
                _expect(resp, 200)

                task_id, variables = self._read_task(resp.json())
                log.info(f"[task-{task_id}] received ({len(variables)} variables)")

                body, error_message = self._evaluate(evaluator, task_id, variables)
                if body is None:
                    consecutive_eval_errors += 1
                    self._post_error(task_id, error_message)
                    consecutive_errors = 0
                    delay = _backoff(consecutive_eval_errors, self.EVAL_ERROR_DELAY, self.MAX_EVAL_ERROR_DELAY)
                    if delay > 0:
                        log.warning(
                            f"Worker {self.worker_id}: {consecutive_eval_errors} evaluation errors in a row "
                            f"— waiting {delay}s before the next task"
                        )
                        self._stop.wait(delay)
                    continue
                consecutive_eval_errors = 0

                elapsed_ms = body["evaluationTimeMs"]
                elapsed_str = f"{elapsed_ms}ms" if elapsed_ms < 60_000 else f"{elapsed_ms/60000:.1f}min"
                log.info(f"[task-{task_id}] evaluated in {elapsed_str} → {body['objectives']}")

                try:
                    self._post_result(task_id, body)
                except requests.RequestException as post_err:
                    # The master may still hold the task as in flight, and its watchdog never
                    # requeues it while this worker sends heartbeats: say so.
                    self._report_failure(task_id, f"result not delivered: {_describe(post_err)}")
                    raise
                consecutive_errors = 0

            except requests.RequestException as net_err:
                consecutive_errors += 1
                if consecutive_errors >= self.MAX_CONSECUTIVE_ERRORS:
                    log.error(
                        f"Worker {self.worker_id}: {consecutive_errors} consecutive network errors "
                        f"— assuming master is dead, stopping"
                    )
                    return self.MASTER_LOST
                log.warning(
                    f"Worker {self.worker_id}: network error "
                    f"({consecutive_errors}/{self.MAX_CONSECUTIVE_ERRORS}): {net_err} "
                    f"— retrying in {self.RETRY_DELAY}s"
                )
                self._stop.wait(self.RETRY_DELAY)
            except (KeyError, ValueError, TypeError) as proto_err:
                # A task that names its id but cannot be evaluated: a bug, not a network outage
                # (neither counted nor clearing the count); _read_task has reported it.
                log.error(f"Worker {self.worker_id}: invalid task payload from master: {proto_err}")
                self._stop.wait(self.RETRY_DELAY)
        # Only the heartbeat thread sets _stop while the loop runs.
        return self.MASTER_LOST

    def _read_task(self, task: Any) -> tuple[int, list]:
        """
        The id and the variables of a task payload.

        Raises:
            requests.RequestException: if the payload has no integer ``taskId``,
                        which counts as a failed request, as in RestWorker: an
                        endpoint that answers 200 with JSON to everything (a
                        catch-all gateway) must not keep the worker polling forever.
            ValueError: if its variables are not a list of finite numbers; the
                        failure is first reported to the master (best-effort), so
                        it does not keep the task in flight.
        """
        task_id = task.get("taskId") if isinstance(task, dict) else None
        if isinstance(task_id, bool) or not isinstance(task_id, numbers.Integral):
            raise _NoTaskId(f"invalid task payload from master: no integer taskId in a {type(task).__name__} payload")
        task_id = int(task_id)
        problem = _variables_problem(task.get("variables"))
        if problem is not None:
            self._report_failure(task_id, f"invalid task payload: {problem}")
            raise ValueError(f"task {task_id}: {problem}")
        return task_id, task["variables"]

    def _evaluate(self, evaluator: Evaluator, task_id: int, variables: list) -> tuple[dict | None, str | None]:
        """
        Evaluates one task: returns the result body and None, or None and the
        message for the master once the failure is logged. The traceback is logged
        when the evaluator itself raised, not when only its result was unusable:
        the message then says all, and the traceback would only point here.
        """
        t0 = time.monotonic()
        try:
            returned = evaluator.evaluate(variables)
        except Exception as eval_err:
            failure, with_traceback = eval_err, True
        else:
            try:
                # Validate here so a NaN/inf objective or a non-numeric
                # variable is reported as an evaluation error (the master
                # requeues the task, or discards it after the failure limit)
                # instead of as an unreadable request.
                return _result_body(self.worker_id, _coerce(returned), int((time.monotonic() - t0) * 1000)), None
            except Exception as result_err:
                failure, with_traceback = result_err, False
        elapsed_ms = int((time.monotonic() - t0) * 1000)
        message = _describe(failure)
        log.error(f"[task-{task_id}] evaluation failed after {elapsed_ms}ms: {message}",
                  exc_info=failure if with_traceback else None)
        return None, message

    def _post_result(self, task_id: int, body: dict) -> None:
        """POST /result; raises requests.RequestException unless the master answered as the protocol expects."""
        post_resp = self._session.post(
            f"{self.master_url}/api/v1/tasks/{task_id}/result",
            json=body,
            timeout=15,
        )
        # 404 = master already requeued the task (watchdog kicked in mid-eval),
        # or the run is over (stopping criterion met, or POST /api/v1/stop) and late
        # results are dropped.
        # 400/413/415/422 = master could not apply the result and has requeued the task
        # (or discarded it after the failure limit; neither once the run has been stopped
        # or has ended); its body says why. None of them is a network problem, so none
        # counts towards the dead-master threshold.
        # Anything else (a 5xx, another 4xx) does.
        if post_resp.status_code == 404:
            log.warning(
                f"[task-{task_id}] master no longer expects the result "
                f"(requeued, or the run is over)"
            )
        elif post_resp.status_code in _HANDLED_ANSWERS:
            log.error(f"[task-{task_id}] master rejected result: {post_resp.text}")
        else:
            _expect(post_resp)

    def _post_error(self, task_id: int, message: str) -> None:
        """POST /error; raises requests.RequestException unless the master answered as the protocol expects."""
        resp = self._session.post(
            f"{self.master_url}/api/v1/tasks/{task_id}/error",
            json={"workerId": self.worker_id, "errorMessage": message},
            timeout=10,
        )
        if resp.status_code in _HANDLED_ANSWERS:
            log.warning(f"[task-{task_id}] master answered {resp.status_code} to the error report: {resp.text}")
        else:
            _expect(resp)

    def _report_failure(self, task_id: int, message: str) -> None:
        """Reports a failure of the task, best-effort: a report that fails too is only logged."""
        try:
            self._post_error(task_id, message)
        except requests.RequestException as err:
            log.warning(f"[task-{task_id}] could not report the failure to the master: {err}")

    def _endpoint_withdrawn(self) -> bool:
        """
        Whether the endpoint file this worker was created from is gone or names another master
        now: the master deletes it when it shuts down, while one that died leaves it. Always
        False for a worker created from a URL, and when the file cannot be read for another reason.
        """
        if self._endpoint is None:
            return False
        path, content = self._endpoint
        try:
            return path.read_bytes() != content
        except FileNotFoundError:
            return True
        except OSError:
            return False

    def _start_heartbeat(self) -> threading.Thread:
        address = socket.gethostname()
        t = threading.Thread(
            target=self._heartbeat_loop, args=(address, self._stop), daemon=True,
            name=f"heartbeat-{self.worker_id}",
        )
        t.start()
        return t

    def _heartbeat_loop(self, address: str, stop: threading.Event):
        # Its own Session, since a requests.Session is not meant to be shared between
        # threads, with the connection settings of the main one (proxies, TLS,
        # authentication, headers), so the heartbeats reach the master as the requests do.
        session = requests.Session()
        for setting in ("auth", "cert", "headers", "proxies", "trust_env", "verify"):
            setattr(session, setting, getattr(self._session, setting))
        consecutive_failures = 0
        try:
            while not stop.is_set():
                try:
                    _expect(session.post(
                        f"{self.master_url}/api/v1/workers/heartbeat",
                        params={"workerId": self.worker_id, "address": address},
                        timeout=self.HEARTBEAT_TIMEOUT,
                    ))
                    consecutive_failures = 0
                    delay = self.HEARTBEAT_INTERVAL
                except Exception as e:
                    if stop.is_set():
                        break  # the run is over: a failure now says nothing about the master
                    consecutive_failures += 1
                    if consecutive_failures >= self.MAX_HEARTBEAT_FAILURES:
                        log.error(
                            f"Worker {self.worker_id}: {consecutive_failures} heartbeat failures "
                            f"— assuming master is dead, stopping"
                        )
                        stop.set()
                        break
                    log.warning(
                        f"Heartbeat error ({consecutive_failures}/{self.MAX_HEARTBEAT_FAILURES}): {e} "
                        f"— retrying in {self.HEARTBEAT_RETRY_DELAY}s"
                    )
                    delay = self.HEARTBEAT_RETRY_DELAY
                stop.wait(delay)
        finally:
            session.close()


# ── Helpers ────────────────────────────────────────────────────────────────

def _wrap(evaluate: Any) -> Evaluator:
    """
    The Evaluator that run() calls: an Evaluator as it is, else an adapter around the
    ``evaluate`` method of any other object that has one, or around a plain callable.
    The adapter returns what it calls returns; the loop converts it with _coerce.

    Raises:
        TypeError: if ``evaluate`` is none of these.
    """
    if isinstance(evaluate, Evaluator):
        return evaluate
    # A class is called (instantiated), as before, rather than having an unbound method used.
    method = None if isinstance(evaluate, type) else getattr(evaluate, "evaluate", None)
    if callable(method):
        fn = method
    elif callable(evaluate):
        fn = evaluate
    else:
        raise TypeError(f"evaluate must be an Evaluator, an object with an evaluate method or a callable, "
                        f"got {type(evaluate).__name__}")

    class _FnEvaluator(Evaluator):
        def evaluate(self, variables: Variables) -> Any:
            return fn(variables)

    return _FnEvaluator()


def _coerce(result) -> EvalResult:
    """Convert various return types to EvalResult."""
    if isinstance(result, EvalResult):
        return result
    if isinstance(result, numbers.Real) and not isinstance(result, bool):
        # Plain or numpy scalar: a single objective.
        return EvalResult(objectives=[float(result)])
    if isinstance(result, Mapping):
        return EvalResult(
            objectives=result["objectives"],
            constraints=result.get("constraints"),
            variables=result.get("variables"),
        )
    if hasattr(result, "objectives"):
        # `is not None` rather than truthiness: numpy arrays have no truth value.
        constraints = getattr(result, "constraints", None)
        variables = getattr(result, "variables", None)
        return EvalResult(
            objectives=list(result.objectives),
            constraints=list(constraints) if constraints is not None else None,
            variables=list(variables) if variables is not None else None,
        )
    if result is None or isinstance(result, (bool, str, bytes)) or not hasattr(result, "__iter__"):
        raise TypeError(f"Cannot convert {type(result).__name__} to EvalResult")
    # A sequence of objectives: a list, a tuple, a numpy array...
    return EvalResult(objectives=list(result))


def _result_body(worker_id: str, result: EvalResult, elapsed_ms: int) -> dict:
    """
    Build the JSON body for POST /result, validating and normalizing numbers.

    Objectives and constraints are converted to plain floats and must be
    finite; variables keep their kind (ints stay ints, everything else becomes
    a float) so the master sees JSON integers for integer-encoded problems.
    numpy scalars are accepted (they register as numbers.Integral / numbers.Real).

    Raises:
        ValueError: on a NaN/inf/None value, a non-numeric element or no
                    objective at all; the message names the field and index.
    """
    objectives = _finite_floats("objectives", result.objectives)
    if not objectives:
        raise ValueError("objectives is empty: at least one objective is required")
    body = {
        "workerId":         worker_id,
        "objectives":       objectives,
        "constraints":      _finite_floats("constraints", [] if result.constraints is None else result.constraints),
        "evaluationTimeMs": int(elapsed_ms),
    }
    # Only send variables when the evaluator wants the master to replace the
    # original decision (e.g. Lamarckian repair). Workers that do not modify
    # variables omit the field, keeping the wire format backwards-compatible.
    if result.variables is not None:
        body["variables"] = _wire_numbers("variables", result.variables)
    return body


def _finite_floats(field: str, values) -> list[float]:
    """Convert to a list of finite floats; raise ValueError naming the offender."""
    out: list[float] = []
    for i, v in enumerate(values):
        if v is None or isinstance(v, bool) or not isinstance(v, numbers.Real):
            raise ValueError(f"{field}[{i}] is not a number: {v!r}")
        f = float(v)
        if not math.isfinite(f):
            raise ValueError(f"{field}[{i}] is not finite: {f}")
        out.append(f)
    return out


def _wire_numbers(field: str, values) -> list:
    """Ints stay ints, other reals become finite floats; raise ValueError otherwise."""
    out: list = []
    for i, v in enumerate(values):
        if v is None or isinstance(v, bool) or not isinstance(v, numbers.Real):
            raise ValueError(f"{field}[{i}] is not a number: {v!r}")
        if isinstance(v, numbers.Integral):
            out.append(int(v))
        else:
            f = float(v)
            if not math.isfinite(f):
                raise ValueError(f"{field}[{i}] is not finite: {f}")
            out.append(f)
    return out


def _variables_problem(variables: Any) -> str | None:
    """Why the variables of a task payload cannot be evaluated, or None if they can."""
    if not isinstance(variables, list):
        return "variables is missing" if variables is None else f"variables is a {type(variables).__name__}, not a list"
    for i, v in enumerate(variables):
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return f"variables[{i}] is not a number: {v!r}"
        if isinstance(v, float) and not math.isfinite(v):
            return f"variables[{i}] is not finite: {v}"
    return None


def _expect(resp: requests.Response, *statuses: int) -> None:
    """Raises requests.HTTPError unless the answer has one of ``statuses`` (any 2xx when none is given)."""
    expected = resp.status_code in statuses if statuses else 200 <= resp.status_code < 300
    if expected:
        return
    resp.raise_for_status()  # 4xx and 5xx, with requests' usual message
    raise requests.HTTPError(f"unexpected status {resp.status_code} for url: {resp.url}", response=resp)


def _describe(error: BaseException) -> str:
    """``Type: message``, or the type alone when the message is empty (``str(AssertionError())`` is ``''``)."""
    text = str(error)
    return f"{type(error).__name__}: {text}" if text else type(error).__name__


def _backoff(failures: int, first: float, longest: float) -> float:
    """
    Seconds to wait after ``failures`` failed evaluations in a row: none after
    one, then ``first``, doubled for each further one, at most ``longest``.
    """
    if failures < 2:
        return 0
    return min(first * 2 ** min(failures - 2, 64), longest)

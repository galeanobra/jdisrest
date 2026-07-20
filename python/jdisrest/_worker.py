from __future__ import annotations

import json
import logging
import socket
import threading
import time
import uuid
from pathlib import Path
from typing import Callable, Union

import requests

from ._types import EvalResult, Evaluator

log = logging.getLogger("jdisrest")


class Worker:
    """
    Python worker for the jDisREST distributed optimization framework.

    Connects to a Java master, requests tasks, evaluates them, and returns results.
    Handles heartbeats, master-dead detection, and evaluation error reporting
    automatically.

    Quick start::

        from jdisrest import Worker, EvalResult

        def evaluate(variables):
            return EvalResult(objectives=[-sum(v**2 for v in variables)])

        # Read master URL from the file the master writes on startup:
        Worker.from_endpoint(".master-endpoint").run(evaluate)

        # Or provide the URL directly:
        Worker("http://10.0.0.1:8080").run(evaluate)
    """

    HEARTBEAT_INTERVAL    = 15   # seconds between heartbeats
    REQUEST_TIMEOUT       = 40   # seconds to wait for a task (> master's 30s long-poll)
    RETRY_DELAY           = 10   # seconds to wait after a network error
    NO_TASK_DELAY         = 5    # seconds to wait when master returns 204 (no task yet)
    MAX_CONSECUTIVE_ERRORS = 5   # network errors before assuming master is dead
    MAX_HEARTBEAT_FAILURES = 3   # heartbeat failures before stopping

    def __init__(self, master_url: str, worker_id: str | None = None):
        self.master_url = master_url.rstrip("/")
        self.worker_id  = worker_id or f"worker-py-{uuid.uuid4().hex[:8]}"
        self._stop      = threading.Event()
        self._session   = requests.Session()
        self._session.headers.update({"Content-Type": "application/json"})

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

        The master writes this file atomically on startup, so when the file
        exists the URL is always valid and Spring Boot is already listening.

        Args:
            path:      Path to the .master-endpoint JSON file.
                       Default: ".master-endpoint" in the current directory.
            worker_id: Optional custom worker ID.

        Example::

            Worker.from_endpoint().run(evaluate)
            Worker.from_endpoint("/shared/fs/.master-endpoint").run(evaluate)
        """
        data = json.loads(Path(path).read_text())
        url  = data.get("url") or f"http://{data['host']}:{data['port']}"
        return cls(master_url=url, worker_id=worker_id)

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
        don't know exactly when the master will be ready.

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

    def run(self, evaluate: Union[Evaluator, Callable[[list[int]], EvalResult]]):
        """
        Start the worker. Blocks until the master signals completion or the
        master is detected as dead (too many consecutive errors).

        Args:
            evaluate: Either an :class:`Evaluator` instance or a plain callable
                      ``(variables: list[int]) -> EvalResult``.
                      The callable can also return a plain number (single objective)
                      or a dict with ``"objectives"`` and optional ``"constraints"``.

        Example::

            # Function style
            Worker("http://master:8080").run(lambda v: EvalResult([-sum(v)]))

            # Class style
            class MyEval(Evaluator):
                def evaluate(self, variables):
                    return EvalResult(objectives=[-simulate(variables)])

            Worker("http://master:8080").run(MyEval())
        """
        evaluator = _wrap(evaluate)
        self._start_heartbeat()
        try:
            self._loop(evaluator)
        except KeyboardInterrupt:
            log.info(f"Worker {self.worker_id}: interrupted")
        finally:
            self._stop.set()
            self._session.close()
            log.info(f"Worker {self.worker_id}: stopped")

    # ── Internal loops ─────────────────────────────────────────────────────

    def _loop(self, evaluator: Evaluator):
        consecutive_errors = 0

        while not self._stop.is_set():
            try:
                resp = self._session.get(
                    f"{self.master_url}/api/v1/tasks/next",
                    params={"workerId": self.worker_id},
                    timeout=self.REQUEST_TIMEOUT,
                )
                consecutive_errors = 0

                if resp.status_code == 204:
                    self._stop.wait(self.NO_TASK_DELAY)
                    continue
                if resp.status_code == 410:
                    log.info(f"Worker {self.worker_id}: master algorithm finished")
                    break
                resp.raise_for_status()

                task      = resp.json()
                task_id   = task["taskId"]
                variables = task["variables"]
                log.info(f"[task-{task_id}] received ({len(variables)} variables)")

                t0 = time.time()
                try:
                    result = _coerce(evaluator.evaluate(variables))
                except Exception as eval_err:
                    elapsed_ms = int((time.time() - t0) * 1000)
                    log.error(f"[task-{task_id}] evaluation failed after {elapsed_ms}ms: {eval_err}")
                    try:
                        self._session.post(
                            f"{self.master_url}/api/v1/tasks/{task_id}/error",
                            json={"workerId": self.worker_id, "errorMessage": str(eval_err)},
                            timeout=10,
                        )
                    except requests.RequestException:
                        pass  # best-effort
                    continue

                elapsed_ms = int((time.time() - t0) * 1000)
                elapsed_str = f"{elapsed_ms}ms" if elapsed_ms < 60_000 else f"{elapsed_ms/60000:.1f}min"
                log.info(f"[task-{task_id}] evaluated in {elapsed_str} → {result.objectives}")

                body = {
                    "workerId":        self.worker_id,
                    "objectives":      result.objectives,
                    "constraints":     result.constraints or [],
                    "evaluationTimeMs": elapsed_ms,
                }
                # Only send variables when the evaluator wants the master to
                # replace the original decision (e.g. Lamarckian repair).
                # Workers that do not modify variables omit the field, keeping
                # the wire format backwards-compatible with old masters.
                if result.variables is not None:
                    body["variables"] = list(result.variables)
                post_resp = self._session.post(
                    f"{self.master_url}/api/v1/tasks/{task_id}/result",
                    json=body,
                    timeout=15,
                )
                # 404 = master already requeued the task (watchdog kicked in mid-eval).
                # That's expected; anything else 4xx/5xx is a bug we want to know about.
                if post_resp.status_code == 404:
                    log.warning(f"[task-{task_id}] master rejected result (already requeued)")
                else:
                    post_resp.raise_for_status()

            except requests.RequestException as net_err:
                consecutive_errors += 1
                if consecutive_errors >= self.MAX_CONSECUTIVE_ERRORS:
                    log.error(
                        f"Worker {self.worker_id}: {consecutive_errors} consecutive network errors "
                        f"— assuming master is dead, stopping"
                    )
                    break
                log.warning(
                    f"Worker {self.worker_id}: network error "
                    f"({consecutive_errors}/{self.MAX_CONSECUTIVE_ERRORS}): {net_err} "
                    f"— retrying in {self.RETRY_DELAY}s"
                )
                self._stop.wait(self.RETRY_DELAY)
            except (KeyError, ValueError, TypeError) as proto_err:
                # Malformed task payload from master — treat as a bug, not a network outage.
                log.error(f"Worker {self.worker_id}: invalid task payload from master: {proto_err}")
                self._stop.wait(self.RETRY_DELAY)

    def _start_heartbeat(self):
        address = socket.gethostname()
        t = threading.Thread(
            target=self._heartbeat_loop, args=(address,), daemon=True,
            name=f"heartbeat-{self.worker_id}",
        )
        t.start()

    def _heartbeat_loop(self, address: str):
        consecutive_failures = 0
        while not self._stop.is_set():
            try:
                self._session.post(
                    f"{self.master_url}/api/v1/workers/heartbeat",
                    params={"workerId": self.worker_id, "address": address},
                    timeout=5,
                )
                consecutive_failures = 0
            except Exception as e:
                consecutive_failures += 1
                if consecutive_failures >= self.MAX_HEARTBEAT_FAILURES:
                    log.error(
                        f"Worker {self.worker_id}: {consecutive_failures} heartbeat failures "
                        f"— assuming master is dead, stopping"
                    )
                    self._stop.set()
                    break
                log.debug(f"Heartbeat error ({consecutive_failures}/{self.MAX_HEARTBEAT_FAILURES}): {e}")
            self._stop.wait(self.HEARTBEAT_INTERVAL)


# ── Helpers ────────────────────────────────────────────────────────────────

def _wrap(fn_or_evaluator: Union[Evaluator, Callable]) -> Evaluator:
    """Wrap a plain callable as an Evaluator."""
    if isinstance(fn_or_evaluator, Evaluator):
        return fn_or_evaluator

    fn = fn_or_evaluator

    class _FnEvaluator(Evaluator):
        def evaluate(self, variables: list[int]) -> EvalResult:
            return _coerce(fn(variables))

    return _FnEvaluator()


def _coerce(result) -> EvalResult:
    """Convert various return types to EvalResult."""
    if isinstance(result, EvalResult):
        return result
    if isinstance(result, (int, float)):
        return EvalResult(objectives=[float(result)])
    if isinstance(result, dict):
        return EvalResult(
            objectives=result["objectives"],
            constraints=result.get("constraints"),
            variables=result.get("variables"),
        )
    if hasattr(result, "objectives"):
        return EvalResult(
            objectives=list(result.objectives),
            constraints=list(result.constraints) if getattr(result, "constraints", None) else None,
            variables=list(result.variables) if getattr(result, "variables", None) else None,
        )
    raise TypeError(f"Cannot convert {type(result).__name__} to EvalResult")

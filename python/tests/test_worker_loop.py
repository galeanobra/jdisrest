"""Worker._loop against a mocked master (requests are intercepted by `responses`)."""
import json
import logging
import math
import threading
import time
from urllib.parse import urlparse

import pytest

responses = pytest.importorskip("responses")
import requests

from jdisrest import EvalResult, Worker
from jdisrest._worker import _backoff, _describe

MASTER = "http://master.test:8080"
NEXT = f"{MASTER}/api/v1/tasks/next"
HEARTBEAT = f"{MASTER}/api/v1/workers/heartbeat"


@pytest.fixture(autouse=True)
def no_waiting(monkeypatch):
    """The worker retries at once, so that failure paths take no time."""
    for delay in ("RETRY_DELAY", "NO_TASK_DELAY", "HEARTBEAT_RETRY_DELAY", "EVAL_ERROR_DELAY"):
        monkeypatch.setattr(Worker, delay, 0)


def _mock_master(rsps, task_body, result_status, result_body=None):
    """One task, then 410 so the worker stops. Heartbeats always succeed."""
    rsps.add(responses.POST, HEARTBEAT, status=200)
    rsps.add(responses.GET, NEXT, json=task_body, status=200)
    rsps.add(responses.GET, NEXT, status=410)
    if result_status is not None:
        rsps.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_body['taskId']}/result",
                 status=result_status, json=result_body or {})
    rsps.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_body['taskId']}/error", status=200)


def _calls(rsps, suffix):
    return [c for c in rsps.calls if urlparse(c.request.url).path.endswith(suffix)]


def _errors(rsps, task_id):
    return [json.loads(c.request.body) for c in _calls(rsps, f"/tasks/{task_id}/error")]


def _messages(caplog, level=logging.DEBUG):
    return [r.getMessage() for r in caplog.records if r.levelno >= level]


@responses.activate
def test_real_coded_task_round_trip():
    _mock_master(responses, {"taskId": 5, "variables": [0.5, -2.0], "encoding": "double"}, 200)
    seen = []

    def evaluate(variables):
        seen.append(variables)
        return EvalResult(objectives=[sum(v * v for v in variables)], variables=[v * 2 for v in variables])

    assert Worker(MASTER, worker_id="w").run(evaluate) == "finished"

    assert seen == [[0.5, -2.0]]
    posted = json.loads(_calls(responses, "/tasks/5/result")[0].request.body)
    assert posted["objectives"] == [4.25]
    assert posted["variables"] == [1.0, -4.0]
    assert posted["constraints"] == []
    assert posted["workerId"] == "w"
    assert not _calls(responses, "/tasks/5/error")


@pytest.mark.parametrize("payload, repaired", [
    # Two binary variables of 3 and 5 bits, one 0 or 1 per bit, bit 0 first.
    ({"taskId": 45, "variables": [1, 0, 1, 0, 0, 1, 1, 0], "encoding": "binary", "bitsPerVariable": [3, 5]},
     [1, 0, 1, 1, 0, 1, 1, 0]),
    # The same bits after an integer and a real segment.
    ({"taskId": 46, "variables": [3, -7, 0.25, 1, 0, 1, 0, 0, 1, 1, 0], "segmentSizes": [2, 1, 8],
      "encoding": "mixed", "segmentEncodings": ["int", "double", "binary"], "bitsPerVariable": [3, 5]},
     [3, -7, 0.25, 1, 0, 1, 1, 0, 1, 1, 0]),
])
@responses.activate
def test_bits_reach_the_evaluator_as_ints_and_a_repair_goes_back_as_ints(payload, repaired):
    _mock_master(responses, payload, 200)
    seen = []

    def evaluate(variables):
        seen.append(variables)
        fixed = list(variables)
        fixed[-5] = 1
        return EvalResult(objectives=[float(sum(variables))], variables=fixed)

    assert Worker(MASTER, worker_id="w").run(evaluate) == "finished"

    assert seen == [payload["variables"]]
    assert [type(v) for v in seen[0][-8:]] == [int] * 8, "bits are ints, not bools, as integer variables are"
    body = _calls(responses, f"/tasks/{payload['taskId']}/result")[0].request.body
    posted = json.loads(body)
    assert posted["variables"] == repaired
    assert [type(v) for v in posted["variables"][-8:]] == [int] * 8, "sent as JSON 0 and 1, which the master takes"
    assert b"true" not in (body if isinstance(body, bytes) else body.encode())
    assert not _calls(responses, f"/tasks/{payload['taskId']}/error")


@responses.activate
def test_nan_objective_is_reported_as_evaluation_error_not_posted_as_result(caplog):
    _mock_master(responses, {"taskId": 6, "variables": [1, 2]}, result_status=None)

    Worker(MASTER, worker_id="w").run(lambda v: EvalResult(objectives=[math.nan]))

    assert not _calls(responses, "/tasks/6/result")
    error = _errors(responses, 6)[0]
    assert error["workerId"] == "w"
    assert error["errorMessage"] == "ValueError: objectives[0] is not finite: nan"
    failure = next(r for r in caplog.records if "evaluation failed after" in r.getMessage())
    assert failure.exc_info is None, "an unusable result is explained by its message; no traceback into the worker"


@responses.activate
def test_422_from_master_is_logged_and_the_worker_carries_on(caplog):
    _mock_master(responses, {"taskId": 7, "variables": [1, 2]}, 422,
                 {"taskId": 7, "reason": "objectives has 1 values but the problem defines 2"})

    Worker(MASTER, worker_id="w").run(lambda v: EvalResult(objectives=[1.0]))

    # The worker reached the 410 (second GET) instead of stopping on the 422.
    assert len(_calls(responses, "/tasks/next")) == 2
    assert any("rejected result" in r.getMessage() and "problem defines 2" in r.getMessage()
               for r in caplog.records)


# ── Counting failed requests (master-lost) ─────────────────────────────────

@pytest.mark.parametrize("status", [500, 502, 503, 404, 202])
@responses.activate
def test_unexpected_answers_to_task_requests_stop_the_worker_as_master_lost(status, caplog):
    responses.add(responses.POST, HEARTBEAT, status=200)
    responses.add(responses.GET, NEXT, status=status)

    reason = Worker(MASTER, worker_id="w").run(lambda v: 0.0)

    assert reason == "master-lost"
    assert len(_calls(responses, "/tasks/next")) == Worker.MAX_CONSECUTIVE_ERRORS, \
        "every unexpected answer counts towards the dead-master threshold, none clears it"
    assert any("5 consecutive network errors" in m for m in _messages(caplog))


@responses.activate
def test_a_204_clears_the_count_of_failed_requests():
    responses.add(responses.POST, HEARTBEAT, status=200)
    for _ in range(Worker.MAX_CONSECUTIVE_ERRORS - 1):
        responses.add(responses.GET, NEXT, status=503)
    responses.add(responses.GET, NEXT, status=204)
    for _ in range(Worker.MAX_CONSECUTIVE_ERRORS - 1):
        responses.add(responses.GET, NEXT, status=503)
    responses.add(responses.GET, NEXT, status=410)

    assert Worker(MASTER, worker_id="w").run(lambda v: 0.0) == "finished"
    assert len(_calls(responses, "/tasks/next")) == 2 * Worker.MAX_CONSECUTIVE_ERRORS


@responses.activate
def test_results_that_never_get_through_stop_the_worker_and_each_is_reported_as_an_error():
    responses.add(responses.POST, HEARTBEAT, status=200)
    responses.add(responses.GET, NEXT, json={"taskId": 3, "variables": [1, 2]}, status=200)
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/3/result", status=502)
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/3/error", status=200)

    reason = Worker(MASTER, worker_id="w").run(lambda v: 1.0)

    # A task received does not clear the count: only an answer to its report does.
    assert reason == "master-lost"
    assert len(_calls(responses, "/tasks/3/result")) == Worker.MAX_CONSECUTIVE_ERRORS
    errors = _errors(responses, 3)
    assert len(errors) == Worker.MAX_CONSECUTIVE_ERRORS, "each undelivered result is reported, so the task is requeued"
    assert errors[0]["errorMessage"].startswith("result not delivered: HTTPError: 502")


@pytest.mark.parametrize("status", [400, 404, 413, 415, 422])
@responses.activate
def test_answers_that_reject_a_result_clear_the_count_and_are_not_reported_again(status):
    responses.add(responses.POST, HEARTBEAT, status=200)
    for _ in range(Worker.MAX_CONSECUTIVE_ERRORS - 1):
        responses.add(responses.GET, NEXT, status=503)
    responses.add(responses.GET, NEXT, json={"taskId": 4, "variables": [1]}, status=200)
    for _ in range(Worker.MAX_CONSECUTIVE_ERRORS - 1):
        responses.add(responses.GET, NEXT, status=503)
    responses.add(responses.GET, NEXT, status=410)
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/4/result", status=status, json={"taskId": 4})
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/4/error", status=200)

    assert Worker(MASTER, worker_id="w").run(lambda v: 1.0) == "finished"
    assert not _calls(responses, "/tasks/4/error"), "the master already handled the task; reporting it again would " \
                                                    "count a second failure"


@responses.activate
def test_a_result_lost_on_the_way_is_reported_through_error():
    responses.add(responses.POST, HEARTBEAT, status=200)
    responses.add(responses.GET, NEXT, json={"taskId": 8, "variables": [1, 2]}, status=200)
    responses.add(responses.GET, NEXT, status=410)
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/8/result", body=requests.ConnectionError("reset by peer"))
    responses.add(responses.POST, f"{MASTER}/api/v1/tasks/8/error", status=200)

    assert Worker(MASTER, worker_id="w").run(lambda v: 1.0) == "finished"
    reports = [e["errorMessage"] for e in _errors(responses, 8)]
    assert reports == ["result not delivered: ConnectionError: reset by peer"]


# ── Malformed tasks ────────────────────────────────────────────────────────

@pytest.mark.parametrize("payload, problem", [
    ({"taskId": 9}, "variables is missing"),
    ({"taskId": 9, "variables": "1,2"}, "variables is a str, not a list"),
    ({"taskId": 9, "variables": [1, "NaN"]}, "variables[1] is not a number: 'NaN'"),
    ({"taskId": 9, "variables": [1, None]}, "variables[1] is not a number: None"),
    ({"taskId": 9, "variables": [True]}, "variables[0] is not a number: True"),
])
@responses.activate
def test_a_malformed_task_that_names_its_task_is_reported_through_error(payload, problem, caplog):
    _mock_master(responses, payload, result_status=None)
    evaluated = []

    assert Worker(MASTER, worker_id="w").run(evaluated.append) == "finished"

    assert not evaluated
    assert [e["errorMessage"] for e in _errors(responses, 9)] == [f"invalid task payload: {problem}"]
    assert any("invalid task payload from master" in m for m in _messages(caplog, logging.ERROR))


@pytest.mark.parametrize("payload", [{"variables": [1, 2]}, {"taskId": "9", "variables": [1]}, [9, [1, 2]], {}])
@responses.activate
def test_a_task_without_its_id_counts_as_a_failed_request(payload, caplog):
    # As in RestWorker: an endpoint that answers 200 with JSON to everything stops the worker.
    responses.add(responses.POST, HEARTBEAT, status=200)
    responses.add(responses.GET, NEXT, json=payload, status=200)

    assert Worker(MASTER, worker_id="w").run(lambda v: 1.0) == "master-lost"
    assert len(_calls(responses, "/tasks/next")) == Worker.MAX_CONSECUTIVE_ERRORS
    assert not _calls(responses, "/error"), "there is no task id to report"
    assert any("invalid task payload from master: no integer taskId" in m for m in _messages(caplog, logging.WARNING))


# ── Evaluation errors ──────────────────────────────────────────────────────

@pytest.mark.parametrize("error, message", [
    (KeyError("x"), "KeyError: 'x'"),
    (AssertionError(), "AssertionError"),
    (ZeroDivisionError("division by zero"), "ZeroDivisionError: division by zero"),
])
@responses.activate
def test_an_evaluator_exception_is_reported_with_its_type_and_logged_with_its_traceback(error, message, caplog):
    _mock_master(responses, {"taskId": 2, "variables": [1]}, result_status=None)

    def evaluate(variables):
        raise error

    Worker(MASTER, worker_id="w").run(evaluate)

    assert [e["errorMessage"] for e in _errors(responses, 2)] == [message]
    failure = next(r for r in caplog.records if "evaluation failed after" in r.getMessage())
    assert failure.getMessage().endswith(message)
    assert failure.exc_info is not None and failure.exc_info[1] is error, "the traceback of the evaluator is logged"


@responses.activate
def test_evaluation_errors_in_a_row_make_the_worker_wait_longer_each_time(monkeypatch, caplog):
    monkeypatch.setattr(Worker, "EVAL_ERROR_DELAY", 0.001)
    monkeypatch.setattr(Worker, "MAX_EVAL_ERROR_DELAY", 0.002)
    responses.add(responses.POST, HEARTBEAT, status=200)
    for task_id in range(1, 7):
        responses.add(responses.GET, NEXT, json={"taskId": task_id, "variables": [task_id]}, status=200)
        responses.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_id}/error", status=200)
        responses.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_id}/result", status=200)
    responses.add(responses.GET, NEXT, status=410)

    def evaluate(variables):
        if variables == [5]:
            return 1.0  # a success clears the run of failures
        raise RuntimeError("simulator crashed")

    assert Worker(MASTER, worker_id="w").run(evaluate) == "finished"

    waits = [m for m in _messages(caplog, logging.WARNING) if "evaluation errors in a row" in m]
    assert waits == [
        "Worker w: 2 evaluation errors in a row — waiting 0.001s before the next task",
        "Worker w: 3 evaluation errors in a row — waiting 0.002s before the next task",
        "Worker w: 4 evaluation errors in a row — waiting 0.002s before the next task",
    ], "no wait after a single failure, then doubling up to the cap; task 6 follows a success"


def test_the_back_off_doubles_from_the_second_failure_up_to_its_cap():
    assert [_backoff(n, 1, 60) for n in range(0, 10)] == [0, 0, 1, 2, 4, 8, 16, 32, 60, 60]
    assert _backoff(10_000, 0.5, 60) == 60, "a very long run of failures does not overflow"


def test_an_exception_is_described_by_its_type_and_message():
    assert _describe(ValueError("bad")) == "ValueError: bad"
    assert _describe(KeyError("x")) == "KeyError: 'x'"
    assert _describe(AssertionError()) == "AssertionError"


# ── Why run() returns, running again ───────────────────────────────────────

@responses.activate
def test_a_worker_can_run_again_and_says_why_it_stopped_each_time():
    responses.add(responses.POST, HEARTBEAT, status=200)
    responses.add(responses.GET, NEXT, status=410)
    worker = Worker(MASTER, worker_id="w")

    assert worker.run(lambda v: 1.0) == Worker.FINISHED == "finished"
    assert worker.run(lambda v: 1.0) == "finished"
    assert len(_calls(responses, "/tasks/next")) == 2, "the second run talks to the master again"


@responses.activate
def test_a_second_run_while_the_worker_runs_is_refused_and_the_first_one_carries_on():
    _mock_master(responses, {"taskId": 1, "variables": [1]}, result_status=200)
    worker = Worker(MASTER, worker_id="w")
    evaluating, release = threading.Event(), threading.Event()
    reasons = []

    def evaluate(variables):
        evaluating.set()
        assert release.wait(5), "the test never released the evaluation"
        return 1.0

    first = threading.Thread(target=lambda: reasons.append(worker.run(evaluate)))
    first.start()
    try:
        assert evaluating.wait(5), "the first run never evaluated its task"
        with pytest.raises(RuntimeError, match="Worker w is already running"):
            worker.run(lambda v: 1.0)
    finally:
        release.set()
        first.join(10)

    assert reasons == ["finished"], "the refused run did not disturb the one in progress"
    assert len(_calls(responses, "/tasks/1/result")) == 1
    assert worker.run(lambda v: 1.0) == "finished", "the worker can run again once the first run has returned"


@responses.activate
def test_a_keyboard_interrupt_during_an_evaluation_returns_interrupted():
    _mock_master(responses, {"taskId": 1, "variables": [1]}, result_status=200)

    def evaluate(variables):
        raise KeyboardInterrupt

    assert Worker(MASTER, worker_id="w").run(evaluate) == Worker.INTERRUPTED == "interrupted"
    assert not _calls(responses, "/tasks/1/error") and not _calls(responses, "/tasks/1/result")


@responses.activate
def test_failed_heartbeats_are_warnings_and_stop_the_worker_as_master_lost(monkeypatch, caplog):
    monkeypatch.setattr(Worker, "NO_TASK_DELAY", 0.01)
    responses.add(responses.POST, HEARTBEAT, status=500)  # an answer, but not a healthy one
    responses.add(responses.GET, NEXT, status=204)

    assert Worker(MASTER, worker_id="w").run(lambda v: 1.0) == Worker.MASTER_LOST == "master-lost"

    warnings = [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]
    assert any(m.startswith("Heartbeat error (1/3): 500 Server Error") for m in warnings)
    assert any(m.startswith("Heartbeat error (2/3)") for m in warnings)
    assert "Worker w: 3 heartbeat failures — assuming master is dead, stopping" in _messages(caplog, logging.ERROR)
    assert len(_calls(responses, "/workers/heartbeat")) == Worker.MAX_HEARTBEAT_FAILURES


def _master_gone():
    """A master whose server is closed: every request is refused."""
    responses.add(responses.POST, HEARTBEAT, body=requests.ConnectionError("refused"))
    responses.add(responses.GET, NEXT, body=requests.ConnectionError("refused"))


@pytest.mark.parametrize("change, reason", [
    ("delete", "finished"),   # the master shut down at the end of the run (shutdown() deletes the file)
    ("replace", "finished"),  # a new master has replaced it
    ("keep", "master-lost"),  # the master died and left it behind
])
@responses.activate
def test_a_worker_from_an_endpoint_file_tells_a_master_that_shut_down_from_one_that_died(
        tmp_path, caplog, change, reason):
    caplog.set_level(logging.INFO, logger="jdisrest")
    endpoint = tmp_path / ".master-endpoint"
    endpoint.write_text(json.dumps({"host": "master.test", "port": 8080, "url": MASTER}))
    worker = Worker.from_endpoint(endpoint, worker_id="w")
    if change == "delete":
        endpoint.unlink()
    elif change == "replace":
        endpoint.write_text(json.dumps({"host": "master.test", "port": 8081, "url": "http://master.test:8081"}))
    _master_gone()

    assert worker.run(lambda v: 1.0) == reason

    removed = [m for m in _messages(caplog, logging.INFO) if "removed or replaced — the run is over" in m]
    assert len(removed) == (reason == "finished")


@responses.activate
def test_a_worker_from_a_url_cannot_tell_and_gives_the_master_up_as_lost(tmp_path):
    (tmp_path / ".master-endpoint").write_text("{}")  # not the worker's: it was given a URL
    _master_gone()

    assert Worker(MASTER, worker_id="w").run(lambda v: 1.0) == "master-lost"


@responses.activate
def test_the_endpoint_file_is_checked_where_it_was_read_even_after_a_change_of_folder(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / ".master-endpoint").write_text(json.dumps({"url": MASTER}))
    worker = Worker.from_endpoint(worker_id="w")
    monkeypatch.chdir(tmp_path.parent)  # for instance an evaluator that runs a simulation elsewhere
    _master_gone()

    assert worker.run(lambda v: 1.0) == "master-lost", "the file is still there, so the master died"


@responses.activate
def test_heartbeats_do_not_share_the_session_of_the_main_loop():
    _mock_master(responses, {"taskId": 1, "variables": [1]}, result_status=200)
    worker = Worker(MASTER, worker_id="w")
    used = []
    for method in ("get", "post"):
        original = getattr(worker._session, method)
        setattr(worker._session, method,
                lambda url, *a, _original=original, **k: used.append(url) or _original(url, *a, **k))

    def evaluate(variables):
        deadline = time.monotonic() + 5
        while not _calls(responses, "/workers/heartbeat") and time.monotonic() < deadline:
            time.sleep(0.01)  # until the heartbeat thread has sent one
        return 1.0

    worker.run(evaluate)

    assert _calls(responses, "/workers/heartbeat"), "the heartbeat thread ran"
    assert used and not any("heartbeat" in url for url in used)


# ── What run() accepts ─────────────────────────────────────────────────────

@responses.activate
def test_any_object_with_an_evaluate_method_and_a_list_of_objectives_are_accepted():
    _mock_master(responses, {"taskId": 1, "variables": [1, 2]}, result_status=200)

    class Duck:  # not an Evaluator subclass
        def evaluate(self, variables):
            return [float(sum(variables)), 0.5]

    Worker(MASTER, worker_id="w").run(Duck())

    assert json.loads(_calls(responses, "/tasks/1/result")[0].request.body)["objectives"] == [3.0, 0.5]
    assert not _calls(responses, "/tasks/1/error")


@responses.activate
def test_run_refuses_what_cannot_evaluate_before_contacting_the_master():
    with pytest.raises(TypeError, match="evaluate must be an Evaluator"):
        Worker(MASTER, worker_id="w").run(42)
    assert not responses.calls

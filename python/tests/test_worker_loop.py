"""Worker._loop against a mocked master (requests are intercepted by `responses`)."""
import json
import math
from urllib.parse import urlparse

import pytest

responses = pytest.importorskip("responses")

from jdisrest import EvalResult, Worker

MASTER = "http://master.test:8080"


def _mock_master(rsps, task_body, result_status, result_body=None):
    """One task, then 410 so the worker stops. Heartbeats always succeed."""
    rsps.add(responses.POST, f"{MASTER}/api/v1/workers/heartbeat", status=200)
    rsps.add(responses.GET, f"{MASTER}/api/v1/tasks/next", json=task_body, status=200)
    rsps.add(responses.GET, f"{MASTER}/api/v1/tasks/next", status=410)
    if result_status is not None:
        rsps.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_body['taskId']}/result",
                 status=result_status, json=result_body or {})
    rsps.add(responses.POST, f"{MASTER}/api/v1/tasks/{task_body['taskId']}/error", status=200)


def _calls(rsps, suffix):
    return [c for c in rsps.calls if urlparse(c.request.url).path.endswith(suffix)]


@responses.activate
def test_real_coded_task_round_trip():
    _mock_master(responses, {"taskId": 5, "variables": [0.5, -2.0], "encoding": "double"}, 200)
    seen = []

    def evaluate(variables):
        seen.append(variables)
        return EvalResult(objectives=[sum(v * v for v in variables)], variables=[v * 2 for v in variables])

    Worker(MASTER, worker_id="w").run(evaluate)

    assert seen == [[0.5, -2.0]]
    posted = json.loads(_calls(responses, "/tasks/5/result")[0].request.body)
    assert posted["objectives"] == [4.25]
    assert posted["variables"] == [1.0, -4.0]
    assert posted["constraints"] == []
    assert posted["workerId"] == "w"
    assert not _calls(responses, "/tasks/5/error")


@responses.activate
def test_nan_objective_is_reported_as_evaluation_error_not_posted_as_result():
    _mock_master(responses, {"taskId": 6, "variables": [1, 2]}, result_status=None)

    Worker(MASTER, worker_id="w").run(lambda v: EvalResult(objectives=[math.nan]))

    assert not _calls(responses, "/tasks/6/result")
    error = json.loads(_calls(responses, "/tasks/6/error")[0].request.body)
    assert error["workerId"] == "w"
    assert "objectives[0] is not finite" in error["errorMessage"]


@responses.activate
def test_422_from_master_is_logged_and_the_worker_carries_on(caplog):
    _mock_master(responses, {"taskId": 7, "variables": [1, 2]}, 422,
                 {"taskId": 7, "reason": "objectives has 1 values but the problem defines 2"})

    Worker(MASTER, worker_id="w").run(lambda v: EvalResult(objectives=[1.0]))

    # The worker reached the 410 (second GET) instead of stopping on the 422.
    assert len(_calls(responses, "/tasks/next")) == 2
    assert any("rejected result" in r.getMessage() and "problem defines 2" in r.getMessage()
               for r in caplog.records)

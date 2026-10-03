"""The command-line worker, python -m jdisrest, with a stand-in Worker and code folders written by the tests."""
import argparse
import importlib
import logging
import os
import signal
import subprocess
import sys
import tomllib
from pathlib import Path

import pytest

import jdisrest
from jdisrest import FunctionEvaluator, add_worker_arguments, configure_logging, run_worker
from jdisrest import _cli, _loader

CODE = {"fit.py": "from jdisrest import EvalResult, Evaluator\n\n"
                  "def evaluate(variables):\n"
                  "    return [sum(variables), max(variables)]\n\n"
                  "class Model(Evaluator):\n"
                  "    def __init__(self):\n"
                  "        self.offset = 10.0\n\n"
                  "    def evaluate(self, variables):\n"
                  "        return EvalResult(objectives=[self.offset + sum(variables)], constraints=[-1.0])\n\n"
                  "model = Model()\n\n"
                  "class Plain:\n"
                  "    pass\n\n"
                  "LIMIT = 3\n"}


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


@pytest.fixture
def code(tmp_path):
    folder = tmp_path / "code"
    folder.mkdir()
    for name, source in CODE.items():
        (folder / name).write_text(source, encoding="utf-8")
    return folder


@pytest.fixture
def workers(monkeypatch):
    """
    Replaces Worker in the command line with a stand-in that records how it is used and whose run
    returns ``StandIn.reason``; returns those created.
    """
    created = []

    class StandIn:
        reason = jdisrest.Worker.FINISHED

        def __init__(self, master_url, worker_id=None):
            self.master_url, self.worker_id, self.endpoint, self.evaluator = master_url, worker_id, None, None
            created.append(self)

        @classmethod
        def wait_for_endpoint(cls, path, timeout=300, worker_id=None):
            worker = cls(None, worker_id)
            worker.endpoint = (path, timeout)
            return worker

        def run(self, evaluate):
            self.evaluator = evaluate
            return self.reason

    monkeypatch.setattr(_cli, "Worker", StandIn)
    return created


def _parse(*arguments):
    return _cli._parser(None).parse_args(list(arguments))


def _namespace(master=None, endpoint=".master-endpoint", timeout=300, worker_id=None):
    return argparse.Namespace(master=master, endpoint=endpoint, timeout=timeout, worker_id=worker_id)


# ── Options ─────────────────────────────────────────────────────────────────

def test_the_endpoint_file_is_the_default_master_source():
    args = _parse("--evaluator", "fit:evaluate")

    assert (args.master, args.endpoint, args.timeout, args.worker_id) == (None, ".master-endpoint", 300, None)
    assert (args.code_dir, args.variables, args.objectives, args.non_finite_penalty) == (None, None, None, None)
    assert args.log_level == "INFO"


def test_every_option_is_parsed():
    args = _parse("--evaluator", "fit:evaluate", "--code-dir", "code", "--master", "http://10.0.0.1:8080",
                  "--timeout", "60", "--worker-id", "w-1", "--variables", "3", "--objectives", "2",
                  "--non-finite-penalty", "1e6", "--log-level", "debug")

    assert (args.evaluator, args.code_dir, args.master, args.timeout, args.worker_id) == (
        "fit:evaluate", "code", "http://10.0.0.1:8080", 60, "w-1")
    assert (args.variables, args.objectives, args.non_finite_penalty, args.log_level) == (3, 2, 1e6, "DEBUG")


def test_the_master_url_and_the_endpoint_file_are_mutually_exclusive():
    with pytest.raises(SystemExit):
        _parse("--evaluator", "fit:evaluate", "--master", "http://m:1", "--endpoint", "e")


@pytest.mark.parametrize("arguments", [
    [],
    ["--evaluator", "fit"],
    ["--evaluator", ":evaluate"],
    ["--evaluator", "fit:"],
    ["--evaluator", "fit:evaluate", "--master", ""],
    ["--evaluator", "fit:evaluate", "--master", "  "],
    ["--evaluator", "fit:evaluate", "--master", "127.0.0.1:8080"],
    ["--evaluator", "fit:evaluate", "--master", "localhost:8080"],
    ["--evaluator", "fit:evaluate", "--master", "ftp://10.0.0.1:8080"],
    ["--evaluator", "fit:evaluate", "--master", "http://"],
    ["--evaluator", "fit:evaluate", "--timeout", "-1"],
    ["--evaluator", "fit:evaluate", "--variables", "0"],
    ["--evaluator", "fit:evaluate", "--objectives", "two"],
    ["--evaluator", "fit:evaluate", "--non-finite-penalty", "nan"],
    ["--evaluator", "fit:evaluate", "--non-finite-penalty", "inf"],
    ["--evaluator", "fit:evaluate", "--log-level", "LOUD"],
])
def test_bad_options_are_rejected(arguments):
    with pytest.raises(SystemExit):
        _parse(*arguments)


def test_the_worker_options_serve_another_command_line():
    parser = argparse.ArgumentParser()
    parser.add_argument("--code-dir", required=True)
    add_worker_arguments(parser)

    args = parser.parse_args(["--code-dir", "code", "--worker-id", "w-2"])

    assert (args.code_dir, args.master, args.endpoint, args.timeout, args.worker_id) == (
        "code", None, ".master-endpoint", 300, "w-2")


# ── Evaluators ──────────────────────────────────────────────────────────────

@pytest.mark.parametrize("attribute, objectives", [("evaluate", [3.0, 2.0]), ("Model", [13.0]), ("model", [13.0])])
def test_a_function_an_evaluator_class_or_an_evaluator_instance_is_accepted(code, attribute, objectives):
    evaluator = _cli._load_evaluator(f"fit:{attribute}", str(code))

    assert isinstance(evaluator, FunctionEvaluator)
    assert evaluator.evaluate([1.0, 2.0]).objectives == objectives


def test_an_evaluator_keeps_its_constraints_when_wrapped(code):
    assert _cli._load_evaluator("fit:Model", str(code)).evaluate([1.0]).constraints == [-1.0]


@pytest.mark.parametrize("attribute, error", [("Plain", "does not extend jdisrest.Evaluator"),
                                              ("LIMIT", "is not a function")])
def test_an_attribute_that_cannot_evaluate_is_rejected(code, attribute, error):
    with pytest.raises(TypeError, match=error):
        _cli._load_evaluator(f"fit:{attribute}", str(code))


def test_the_checks_of_the_options_wrap_the_evaluator(code):
    evaluator = _cli._load_evaluator("fit:evaluate", str(code), number_of_variables=3, number_of_objectives=2,
                                     non_finite_penalty=1e6)

    with pytest.raises(ValueError, match="variables has 2 values but the evaluator expects 3"):
        evaluator.evaluate([1.0, 2.0])
    assert evaluator.evaluate([1.0, 2.0, float("nan")]).objectives == [1e6, 1e6]


# ── run_worker ──────────────────────────────────────────────────────────────

def test_run_worker_connects_to_the_given_master(workers):
    def evaluate(variables):
        return 1.0

    run_worker(_namespace(master="http://10.0.0.1:8080", worker_id="w-1"), evaluate)

    assert [(w.master_url, w.worker_id, w.endpoint, w.evaluator) for w in workers] == [
        ("http://10.0.0.1:8080", "w-1", None, evaluate)]


def test_run_worker_waits_for_the_endpoint_file_without_a_master_url(workers):
    run_worker(_namespace(endpoint="shared/.master-endpoint", timeout=60), lambda variables: 1.0)

    assert [(w.endpoint, w.worker_id) for w in workers] == [(("shared/.master-endpoint", 60), None)]


def test_run_worker_rejects_an_empty_master_url_instead_of_waiting_for_an_endpoint_file(workers):
    with pytest.raises(ValueError, match="--master is empty"):
        run_worker(_namespace(master=""), lambda variables: 1.0)
    assert workers == []


def test_run_worker_rejects_a_master_url_without_a_scheme(workers):
    with pytest.raises(ValueError, match="--master must be an http:// or https:// URL"):
        run_worker(_namespace(master="10.0.0.1:8080"), lambda variables: 1.0)
    assert workers == []


@pytest.mark.parametrize("reason", [jdisrest.Worker.FINISHED, jdisrest.Worker.MASTER_LOST,
                                    jdisrest.Worker.INTERRUPTED])
def test_run_worker_returns_why_the_worker_stopped(workers, monkeypatch, reason):
    monkeypatch.setattr(_cli.Worker, "reason", reason)

    assert run_worker(_namespace(master="http://m:1"), lambda variables: 1.0) == reason


@pytest.mark.parametrize("url", ["http://10.0.0.1:8080", "https://master.example:443/", "HTTP://M:1"])
def test_http_and_https_master_urls_are_accepted(url):
    assert _parse("--evaluator", "fit:evaluate", "--master", url).master == url


# ── main ────────────────────────────────────────────────────────────────────

def test_main_loads_the_evaluator_and_runs_the_worker(code, workers):
    status = _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code), "--master", "http://m:1",
                        "--worker-id", "w-1"])

    assert status == 0
    assert [(w.master_url, w.worker_id) for w in workers] == [("http://m:1", "w-1")]
    assert workers[0].evaluator.evaluate([1.0, 2.0]).objectives == [3.0, 2.0]


@pytest.mark.parametrize("reason, status", [(jdisrest.Worker.FINISHED, 0), (jdisrest.Worker.INTERRUPTED, 0),
                                            (jdisrest.Worker.MASTER_LOST, 3)])
def test_main_exits_with_3_only_when_the_master_was_lost(code, workers, monkeypatch, reason, status):
    monkeypatch.setattr(_cli.Worker, "reason", reason)

    assert _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code), "--master", "http://m:1"]) == status
    assert _cli.EXIT_MASTER_LOST == 3


@pytest.mark.parametrize("deleted, status", [(True, 0), (False, 3)])
def test_main_exits_with_0_when_the_master_shut_down_under_a_busy_worker_and_with_3_when_it_died(
        code, tmp_path, monkeypatch, deleted, status):
    responses = pytest.importorskip("responses")
    import requests
    for delay in ("RETRY_DELAY", "NO_TASK_DELAY", "HEARTBEAT_RETRY_DELAY"):
        monkeypatch.setattr(jdisrest.Worker, delay, 0)
    master = "http://master.test:8080"
    endpoint = tmp_path / ".master-endpoint"
    endpoint.write_text(f'{{"url": "{master}"}}')

    def refused(request):
        if deleted:
            endpoint.unlink(missing_ok=True)  # shutdown() deletes the file, then closes the server
        return requests.ConnectionError("refused")

    with responses.RequestsMock(assert_all_requests_are_fired=False) as rsps:
        rsps.add_callback(responses.GET, f"{master}/api/v1/tasks/next", callback=refused)
        rsps.add_callback(responses.POST, f"{master}/api/v1/workers/heartbeat", callback=refused)

        assert _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code),
                          "--endpoint", str(endpoint), "--timeout", "0"]) == status


def test_main_fails_without_connecting_when_the_evaluator_cannot_be_loaded(tmp_path, workers, caplog):
    status = _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(tmp_path), "--master", "http://m:1"])

    assert status == 1
    assert workers == []
    assert any("Cannot load evaluator fit:evaluate" in record.getMessage() for record in caplog.records)


def test_main_fails_when_the_endpoint_file_never_appears(code, tmp_path, caplog):
    status = _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code),
                        "--endpoint", str(tmp_path / ".master-endpoint"), "--timeout", "0"])

    assert status == 1
    assert any("not found" in record.getMessage() for record in caplog.records)


def test_sigterm_interrupts_the_worker_and_the_previous_handler_comes_back(code, workers, monkeypatch):
    previous = signal.getsignal(signal.SIGTERM)
    interrupted = []

    def run(self, evaluate):
        with pytest.raises(KeyboardInterrupt):
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
        interrupted.append(True)

    monkeypatch.setattr(_cli.Worker, "run", run)

    assert _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code), "--master", "http://m:1"]) == 0
    assert interrupted == [True]
    assert signal.getsignal(signal.SIGTERM) is previous


def test_an_interruption_while_waiting_for_the_master_ends_the_worker_cleanly(code, workers, monkeypatch):
    def wait_for_endpoint(cls, path, timeout=300, worker_id=None):
        raise KeyboardInterrupt

    monkeypatch.setattr(_cli.Worker, "wait_for_endpoint", classmethod(wait_for_endpoint))

    assert _cli.main(["--evaluator", "fit:evaluate", "--code-dir", str(code)]) == 0


def test_the_log_layout_stays_the_one_log_tools_parse(monkeypatch):
    calls = []
    monkeypatch.setattr(logging, "basicConfig", lambda **arguments: calls.append(arguments))

    configure_logging("DEBUG")

    assert calls == [{"level": "DEBUG", "format": "%(asctime)s %(levelname)s %(name)s: %(message)s"}]


def test_the_package_exports_the_command_line_helpers():
    for name in ("FunctionEvaluator", "load_function", "add_worker_arguments", "configure_logging", "run_worker"):
        assert name in jdisrest.__all__ and hasattr(jdisrest, name), name


def test_python_dash_m_jdisrest_runs_the_command_line():
    package_root = Path(__file__).resolve().parents[1]
    # Python 3.14 colours the help when FORCE_COLOR or PYTHON_COLORS=1 is set, even through a pipe.
    environment = {**os.environ, "PYTHON_COLORS": "0", "NO_COLOR": "1"}

    shown = subprocess.run([sys.executable, "-m", "jdisrest", "--help"], cwd=package_root, capture_output=True,
                           text=True, timeout=60, env=environment)
    missing = subprocess.run([sys.executable, "-m", "jdisrest"], cwd=package_root, capture_output=True,
                             text=True, timeout=60, env=environment)

    assert shown.returncode == 0 and shown.stdout.startswith("usage: python -m jdisrest")
    assert "--evaluator MODULE:ATTR" in shown.stdout
    assert missing.returncode == 2 and "--evaluator" in missing.stderr


def test_the_console_script_shows_its_own_name_whatever_launcher_runs_it(monkeypatch, capsys):
    # Python 3.14 colours the help when FORCE_COLOR or PYTHON_COLORS=1 is set, even when captured.
    monkeypatch.setenv("PYTHON_COLORS", "0")
    monkeypatch.setenv("NO_COLOR", "1")
    monkeypatch.setattr(sys, "argv", [str(Path("venv", "Scripts", "jdisrest-worker.exe")), "--help"])

    with pytest.raises(SystemExit) as shown:
        _cli.console_main()
    usage = capsys.readouterr().out
    monkeypatch.setattr(sys, "argv", ["jdisrest-worker.exe"])
    with pytest.raises(SystemExit) as missing:
        _cli.console_main()
    error = capsys.readouterr().err

    assert shown.value.code == 0 and usage.startswith("usage: jdisrest-worker [-h] --evaluator MODULE:ATTR")
    assert missing.value.code == 2 and "jdisrest-worker: error:" in error and ".exe" not in error


def test_the_console_script_is_declared_with_its_entry_point():
    pyproject = tomllib.loads((Path(__file__).resolve().parents[1] / "pyproject.toml").read_text(encoding="utf-8"))
    module, _, attribute = pyproject["project"]["scripts"]["jdisrest-worker"].partition(":")

    assert getattr(importlib.import_module(module), attribute) is _cli.console_main


def test_the_package_carries_the_license_of_the_repository():
    package_root = Path(__file__).resolve().parents[1]
    pyproject = tomllib.loads((package_root / "pyproject.toml").read_text(encoding="utf-8"))
    assert pyproject["project"]["license-files"] == ["LICENSE"]
    assert (package_root / "LICENSE").is_file()
    repository_license = package_root.parent / "LICENSE"
    if not repository_license.is_file():
        pytest.skip("not in a repository checkout, e.g. an unpacked sdist")
    assert (package_root / "LICENSE").read_bytes() == repository_license.read_bytes()

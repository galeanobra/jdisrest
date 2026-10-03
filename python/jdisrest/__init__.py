"""
jdisrest: Python client for the jdisrest distributed optimization framework.

Minimal usage
-------------
::

    from jdisrest import Worker, EvalResult

    def evaluate(variables):
        # Run your simulation and return objectives.
        # Negate to maximize: return EvalResult(objectives=[-throughput])
        total = sum(v ** 2 for v in variables)
        return EvalResult(objectives=[total])

    # Option A: read master URL from the file the master writes on startup
    Worker.from_endpoint(".master-endpoint").run(evaluate)

    # Option B: provide URL directly
    Worker("http://10.0.0.1:8080").run(evaluate)

    # Option C: wait until the master is ready (master and worker start simultaneously)
    Worker.wait_for_endpoint(".master-endpoint", timeout=300).run(evaluate)


Stateful evaluator
------------------
::

    from jdisrest import Worker, Evaluator, EvalResult

    class MyEvaluator(Evaluator):
        def __init__(self, config_path):
            self.model = load_model(config_path)   # loaded once

        def evaluate(self, variables):
            result = self.model.run(variables)
            return EvalResult(objectives=[-result.throughput])

    Worker.from_endpoint().run(MyEvaluator("config.json"))


Command-line worker
-------------------
::

    python -m jdisrest --evaluator my_problem:evaluate --code-dir path/to/code --endpoint .master-endpoint

evaluates with the function ``evaluate`` of ``path/to/code/my_problem.py`` until the run finishes
(``python -m jdisrest --help`` lists the options; ``jdisrest-worker`` is the same command).
``load_function`` and ``FunctionEvaluator`` do the same from Python, and ``add_worker_arguments``,
``configure_logging`` and ``run_worker`` serve worker command lines of your own.


Monitor progress (from a separate terminal)
-------------------------------------------
::

    import json, urllib.request
    url = json.load(open(".master-endpoint"))["url"]
    status = json.loads(urllib.request.urlopen(url + "/api/v1/status").read())
    print(f"{status['evaluations']}/{status['maxEvaluations']} "
          f"({status['progress']*100:.1f}%) — ETA {status['estimatedSecondsRemaining']}s")
"""

from ._cli import add_worker_arguments, configure_logging, run_worker
from ._loader import FunctionEvaluator, load_function
from ._types import EvalResult, Evaluator, Variables
from ._worker import Worker

__all__ = ["Worker", "EvalResult", "Evaluator", "Variables", "FunctionEvaluator", "load_function",
           "add_worker_arguments", "configure_logging", "run_worker"]
__version__ = "1.2.0"

# jdisrest: Python client

Python worker client for the **jdisrest** distributed evolutionary optimization
framework. See the top-level [jdisrest repository](https://github.com/galeanobra/jdisrest)
for the Java master and the full framework documentation.

## Install

```bash
pip install /path/to/jdisrest/python
# or, for development:
pip install -e /path/to/jdisrest/python
```

## Quick start

```python
from jdisrest import Worker, EvalResult

def evaluate(variables):
    return EvalResult(objectives=[sum(v ** 2 for v in variables)])

Worker("http://master:8080").run(evaluate)
```

Every objective is minimized; negate one to maximize it.

## Command-line worker

When the evaluation is a function in a module of your own, the package can run the worker for
you, with no worker script to write:

```bash
python -m jdisrest --evaluator MODULE:ATTR [--code-dir DIR] [--master URL | --endpoint FILE]
                   [--timeout SECONDS] [--worker-id ID] [--variables N] [--objectives N]
                   [--non-finite-penalty X] [--log-level LEVEL]
```

`pip install` also installs it as the console script `jdisrest-worker`, which takes the same options.
For instance, with this `sphere.py`:

```python
def evaluate(variables):
    return sum(v ** 2 for v in variables)
```

a worker for a master at `10.0.0.1` is started, from the folder that holds `sphere.py`, with

```bash
python -m jdisrest --evaluator sphere:evaluate --code-dir . --master http://10.0.0.1:8080
```

and one that waits for the `.master-endpoint` file the master writes on a shared file system with

```bash
python -m jdisrest --evaluator sphere:evaluate --code-dir path/to/code \
    --endpoint /shared/run/.master-endpoint --timeout 600
```

The worker evaluates tasks until the run finishes (the master answers `410 Gone`), the master
is gone (5 failed requests or 3 failed heartbeats in a row, see [Failures](#failures)) or the
worker is interrupted, then exits.

| Option | Meaning (default) |
|---|---|
| `--evaluator MODULE:ATTR` | Required. `ATTR` of the module `MODULE`: a function `(variables) -> result`, an `Evaluator` subclass, which is instantiated without arguments, or an `Evaluator` instance. `MODULE` may be dotted (`package.module`), and dots in `ATTR` reach nested attributes (`Class.method`). |
| `--code-dir DIR` | Folder that holds `MODULE` and the packages it imports. It goes first in `sys.path`, so it also shadows any library of the same name. A process imports from one code folder only, and a module of that name already imported from elsewhere is refused. Default: import `MODULE` from `sys.path`, that is the installed packages and, with `python -m` only, the current folder. `jdisrest-worker` does not put the current folder in `sys.path`, so it needs `--code-dir .` for a module there. |
| `--master URL` | URL of the master, for instance `http://10.0.0.1:8080`. It must be an `http://` or `https://` URL. Excludes `--endpoint`. |
| `--endpoint FILE` | Endpoint file written by the master (`.master-endpoint`), waited for if it does not exist yet. The file is accepted as soon as it exists, so a file left by an earlier run makes the worker connect to a master that is gone: delete it before starting the master. |
| `--timeout SECONDS` | How long to wait for the endpoint file, checked every 5 seconds (300). |
| `--worker-id ID` | Name of the worker in the master's logs and in `GET /api/v1/workers/status` (a random `worker-py-xxxxxxxx`). It must be unique among the workers of a run: the master tracks one task in flight per id, and workers that share one keep taking each other's tasks back. |
| `--variables N` | Number of variables every task must have. A task with another number is reported to the master as a failed evaluation without calling the function (not checked). |
| `--objectives N` | Number of objectives the function must return. A result with another number is reported to the master as a failed evaluation (not checked here; the master then rejects a result with a wrong count with `422`, which also counts as a failed evaluation). |
| `--non-finite-penalty X` | Opt-in. Replaces every objective of a result that has a NaN or infinite objective with the finite value `X`, and logs a warning (none). |
| `--log-level LEVEL` | `DEBUG`, `INFO`, `WARNING` or `ERROR`, in any case (`INFO`). |

The function receives the variables as a list (ints for an integer-encoded problem, floats for a
real-encoded one) and may return an `EvalResult`, a number (a single objective), a sequence of
objectives such as a list, a tuple or a numpy array, a dict with `"objectives"` and optional
`"constraints"` and `"variables"`, or an object with those attributes.

**Non-finite objectives.** Without `--non-finite-penalty`, a result with a NaN or infinite
objective is reported to the master as a failed evaluation (`POST /api/v1/tasks/{id}/error`).
The master then retries the task and discards it after its failure limit (three failed
evaluations by default), and every retry is a wasted evaluation when the function is
deterministic. With the option, such a result is sent with `X` in every objective instead (all of
them, since one finite objective left as it was could still make the solution look
non-dominated). Choose `X` worse than any valid objective of your problem; there is no default
because no value suits every problem. Non-finite constraints are always reported as failed
evaluations.

**Logging.** Messages go to the standard error as `<time> <LEVEL> jdisrest: <message>`. On
Windows, and on pools such as HTCondor that redirect it to a file, set `PYTHONUTF8=1` for the
worker: otherwise the non-ASCII characters of the log lines, such as `→` and `—`, are escaped
(`\u2192`) or garbled in a stream that uses the system code page.

**Stopping.** SIGTERM, which batch schedulers such as SLURM and HTCondor send to cancel or
preempt a job, interrupts the worker as Ctrl+C does, so it closes cleanly. It sends nothing to
the master about the task it was evaluating: the master's watchdog, which checks every 30
seconds, requeues that task once the worker has sent no heartbeat for 45 seconds, and such a
requeue does not count as a failed evaluation.

**Exit status.** 0 once the worker stops because the run finished or was stopped, or because
the worker was interrupted; 3 when it gave the master up as lost (so a batch script can tell the
two apart); 1 if the evaluator cannot be loaded or the endpoint file does not appear within
`--timeout` seconds; 2 for invalid options. A worker that is still evaluating when the master
shuts down at the end of a run only finds the master gone; it stops after its failed heartbeats
or requests (typically 10 to 25 seconds later) and, since the master deletes its endpoint file
when it shuts down while one that dies leaves it behind, exits with 0 if the endpoint file it was
started from is gone or names another master by then, and with 3 otherwise. A worker given
`--master URL` cannot tell, and exits with 3 in that case too.

## API

The package exports:

- `Worker(master_url, worker_id=None)`: connects to a master (`worker_id`, by default a random
  `worker-py-xxxxxxxx`, must be unique among the workers of a run); `run(evaluate)` evaluates
  its tasks until the run finishes, the master is gone or the worker is interrupted, and returns
  which: `"finished"` (the master answered `410 Gone`: the run finished or was stopped; or, for
  a worker created from an endpoint file, the master stopped answering and the file is gone or
  names another master, because the master shut down), `"master-lost"` or `"interrupted"` (also
  `Worker.FINISHED`, `Worker.MASTER_LOST` and `Worker.INTERRUPTED`). `evaluate` is an
  `Evaluator`, any other object with an `evaluate(variables)` method, or a function; it may
  return any of the results the command-line worker's function may return (see above).
  Anything else passed as `evaluate` raises `TypeError` before the worker connects. A worker can
  run again once `run` has returned.
  `Worker.from_endpoint(path=".master-endpoint")` reads the URL from the endpoint file, and
  `Worker.wait_for_endpoint(path=".master-endpoint", timeout=300)` waits for the file first
  (`TimeoutError` if it does not appear). Both also take `worker_id`.
- `Evaluator`: base class for stateful evaluators, with the abstract method
  `evaluate(variables) -> EvalResult`.
- `EvalResult(objectives, constraints=None, variables=None)`: the result of one evaluation.
  Every objective is minimized; constraints follow jMetal (`>= 0` satisfied, `< 0` violated);
  `variables`, when given, replaces the decision vector on the master (Lamarckian search). The
  master rejects a vector with a value outside the bounds of its variable (`422`, a failed
  evaluation): the bounds are inclusive and checked without tolerance, so clip exactly to them.
- `Variables`: the type of the decision vector a task carries (a list of ints, floats or both).
- `load_function(code_dir, module, attribute)`: imports `module`, from the folder `code_dir`
  (or from `sys.path` if it is None), and returns its `attribute`, as `--evaluator` and
  `--code-dir` do.
- `FunctionEvaluator(function, number_of_variables=None, number_of_objectives=None, non_finite_penalty=None)`:
  an `Evaluator` around a plain function, with the checks and the opt-in penalty of
  `--variables`, `--objectives` and `--non-finite-penalty`.
- `add_worker_arguments(parser)`, `configure_logging(level="INFO")` and `run_worker(args, evaluate)`:
  the pieces of the command-line worker, for a worker command line of your own. The first adds
  `--master`, `--endpoint`, `--timeout` and `--worker-id` to an `argparse` parser; the second
  sets up the log format above (it does nothing if logging is already configured); the third
  connects as those options say, runs the worker and returns what `Worker.run` returned.

For instance, `load_function` and `FunctionEvaluator` run the sphere above from a script:

```python
from jdisrest import FunctionEvaluator, Worker, load_function

sphere = load_function("path/to/code", "sphere", "evaluate")
Worker("http://10.0.0.1:8080").run(FunctionEvaluator(sphere, number_of_objectives=1))
```

and a worker command line with an option of its own reuses the rest:

```python
"""Worker for a shifted sphere."""
import argparse

from jdisrest import FunctionEvaluator, add_worker_arguments, configure_logging, run_worker


def main() -> None:
    parser = argparse.ArgumentParser(description="Shifted sphere worker")
    add_worker_arguments(parser)  # --master | --endpoint, --timeout, --worker-id
    parser.add_argument("--shift", type=float, default=0.0)
    args = parser.parse_args()
    configure_logging()

    def sphere(variables):
        return sum((v - args.shift) ** 2 for v in variables)

    run_worker(args, FunctionEvaluator(sphere, number_of_objectives=1))


if __name__ == "__main__":
    main()
```

`run_worker` does not install the SIGTERM handling of `python -m jdisrest`; a command line that
runs under a batch scheduler installs its own.

### Failures

An evaluation that raises, or whose result has no objective or a value that is not a finite
number, is reported to the master (`POST /api/v1/tasks/{id}/error`) with the type and message
of the exception, such as `ZeroDivisionError: division by zero`; the traceback of an exception
raised by the evaluator goes to the log with the error. The master requeues the task, or
discards it after its failure limit. From the second failed evaluation in a row, the worker
waits before asking for the next task: 1 second, twice as long after each further failure, at
most 60 seconds (`Worker.EVAL_ERROR_DELAY` and `Worker.MAX_EVAL_ERROR_DELAY`), so that a worker
whose evaluator always fails no longer spins through the tasks: once the wait has reached its
cap, it takes at most one task a minute.

A task whose variables are not a list of finite numbers, and a result that does not get
through (a network error, or an answer such as `500` or `502`), are reported the same way,
best-effort, so that the master requeues the task at once instead of keeping it in flight; the
master counts such a report as a failed evaluation of the task if it still has the task in
flight. The master answers `404` to a result it no longer expects (the task was requeued, or
the run was stopped) and `400`, `413`, `415` or `422` to one it cannot apply, which already
counts as a failed evaluation: for a `422`, unless another worker holds the task by then; a
`400`, `413` or `415` counts whoever holds it, because the master cannot read the `workerId` of
a body it has not decoded. The worker logs the answer and carries on.

The master is taken as gone after 5 failed requests in a row (`Worker.MAX_CONSECUTIVE_ERRORS`):
network errors and answers the protocol does not expect, such as the `502` of a proxy in front
of a master that is gone, or a task without an integer `taskId`. A task received does not clear
the count until its result or error report gets an answer. Heartbeats go every 15 seconds on
their own connection; one that fails (a network error or an answer other than 2xx) is logged as
a warning and retried after 5 seconds, and 3 failures in a row (`Worker.MAX_HEARTBEAT_FAILURES`)
also stop the worker.

## Tools

`tools/` holds two standalone scripts for the traces a run writes, which are not part of the
installed package. When the master has a traces folder, it writes there every `populationSize`
evaluations (`archiveSize` for PAES; `setTraceCadence` spaces the snapshots out), and once more
when the run ends, the archive of non-dominated solutions to `aFUN_<evaluations>.csv`
(objectives) and `aVAR_<evaluations>.csv` (variables), and the population (for PAES, its
archive) to `FUN_<evaluations>.csv` and `VAR_<evaluations>.csv`. The local NSGA-II writes the
same files.

### `watch_front.py`

Follows a traces folder and, for each new archive snapshot, prints the extreme solutions of the
front (the lowest value of each objective), the best compromise solution (the closest to the ideal
point once each objective is normalized) and the mean of each objective, with its change since the
previous snapshot. With a reference, it also computes the exact hypervolume and, given a reference
front, IGD and IGD+, as jMetal does. It only uses the standard library, so it runs with any
Python 3.7 or later, for instance on a login node without the workers' environment.

```bash
python tools/watch_front.py path/to/traces                    # follow the run until Ctrl+C
python tools/watch_front.py path/to/traces --once --labels cost time --variables
python tools/watch_front.py path/to/traces --ideal-point 0 0 --reference-point 1 10
python tools/watch_front.py path/to/traces --reference-front reference.csv
python tools/watch_front.py path/to/traces --delete-traces --keep-every 10000
python tools/watch_front.py path/to/traces --recompute        # every kept snapshot, then exit
```

It writes, to `--output-dir` (by default the parent of the traces folder):

- `front_stats.csv`: one row per snapshot with its evaluations, when its `aFUN` was written, its
  number of non-dominated solutions and the mean of each objective.
- `front_extremes.csv`: the objectives and variables of the extremes of each snapshot, and of its
  best compromise solution (`compromise` as `extreme_of`). Every value is read as a float, so
  integer variables appear as `3.0`.
- `front_indicators.csv`, with a reference: the hypervolume, IGD and IGD+ of each snapshot, and
  `front_indicators_reference.txt`, the reference they were computed with.
- `front_reference.csv`: the aggregated front, the non-dominated solutions of every snapshot saved.
- With `--recompute`, `front_indicators_recomputed.csv` (and its `_reference.txt`): the
  indicators of every snapshot still in the traces folder against the aggregated front (or
  `--reference-front`), as known when the command runs.

The file names and columns are a stable format. Restarted on the same output folder, the script
goes on where it stopped without repeating rows.

| Option | Meaning (default) |
|---|---|
| `traces` | Traces folder of the run (the current folder). |
| `--labels NAME ...` | Names of the objectives (`f1`, `f2`, ...). |
| `--variables` | Also print the variables of each extreme. |
| `--once` | Save and show the latest snapshot, then exit; the exit status is 1 if there is no readable snapshot yet. |
| `--interval SECONDS` | Seconds between checks of the folder, at least 2 (15). |
| `--output-dir DIR` | Folder for the output files (the parent of the traces folder). |
| `--delete-traces` | Delete the trace files (`aVAR`, `aFUN`, `VAR`, `FUN`) of the snapshots already saved, except those kept by `--keep` and `--keep-every`. A snapshot is never deleted before its statistics are on disk. |
| `--keep N` | Latest saved snapshots whose traces are never deleted, at least 1: the latest one is the result if the master dies (1). |
| `--keep-every N` | Never delete the snapshots whose evaluations are a multiple of `N`. |
| `--reference-front FILE` | Objectives of a reference front, one point per line, separated by commas or spaces (for instance the `FUN.csv` of a long run). Its minimum and maximum of each objective bound the normalization; IGD and IGD+ need it. |
| `--ideal-point V ...`, `--reference-point V ...` | Lower and upper bound of each objective, replacing those of the reference front; the upper bound is the reference point of the hypervolume. Without `--reference-front`, both are needed, and only the hypervolume is computed. |
| `--recompute` | Write `front_indicators_recomputed.csv` as above and exit. It never deletes traces, and does not change `front_reference.csv`. |

A snapshot is read once both of its files have stopped changing, so a file still being written is
retried on the next pass instead of being reported. The master never cleans its traces folder, so
a new run in the same folder writes snapshots with the same numbers as the previous one: when the
`aFUN` of a snapshot already saved is newer than the time recorded for it in `front_stats.csv`,
the script stops with an error asking for another `--output-dir`, and never deletes the traces of
such a snapshot. The recorded times are local times, to the second, so restart the script in the
same time zone: a restart in another one (for instance in a container on UTC) reports a false
"another run". A new run whose snapshot numbers all differ from those already saved is not
detected.

Files are read and written as UTF-8. On a console or log file whose encoding cannot show a label,
the script prints it with backslash escapes instead of stopping.

### `plot_front_evolution.py`

Draws the archive every `--step` evaluations, coloured by the number of evaluations, as three
files: `front_evolution.png` (every front in one figure, with a 3D view for three objectives and
the projection on each pair of objectives), `front_evolution_grid.png` (one panel per front, with
the earlier fronts in grey) and `front_evolution.gif` (the same as an animation). It needs numpy,
matplotlib and Pillow, which matplotlib installs (`pip install numpy matplotlib`), and at least two
objectives.

```bash
python tools/plot_front_evolution.py path/to/run --step 1000 --labels cost time weight
```

| Option | Meaning (default) |
|---|---|
| `run` | The traces folder, or a folder with a `traces` subfolder. |
| `--step N` | Evaluations between the fronts drawn: the `aFUN_<n>.csv` with `n` a multiple of `N` (1000). The final snapshot of a run is drawn only if its `n` is a multiple of `N` too. |
| `--labels NAME ...` | Names of the objectives (`f1`, `f2`, ...). |
| `--exclude-above X` | Leave out the solutions with an objective of `X` or more, such as those an evaluator penalizes with a large constant, so that they do not flatten the plot. |
| `--output-dir DIR` | Folder for the figures (the parent of the traces folder). |

It reads only the `aFUN` files, so it works for composite (mixed-encoding) runs too. A file that
cannot be read, such as the latest one while the master is still writing it, is skipped with a
warning, and an empty snapshot is drawn as an empty panel.

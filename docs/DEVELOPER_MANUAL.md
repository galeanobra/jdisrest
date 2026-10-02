# jdisrest — Developer Manual

**jdisrest** is a distributed evolutionary optimization framework. The
core algorithm ("master") runs in Java on top of jMetal and delegates
objective-function evaluation to external processes ("workers") that
communicate over HTTP REST. The goal is to fully separate the algorithm
logic (Java) from the problem evaluation (Python, MATLAB, Julia, or any
language capable of making HTTP requests).

---

## Table of contents

1. [Architecture](#1-architecture)
2. [REST protocol](#2-rest-protocol)
3. [Defining a problem](#3-defining-a-problem)
4. [Workers](#4-workers)
   - 4.1 [Java worker](#41-java-worker)
   - 4.2 [Python worker](#42-python-worker)
   - 4.3 [MATLAB worker](#43-matlab-worker)
5. [Master: wiring the algorithm](#5-master-wiring-the-algorithm)
   - [Available algorithms](#available-algorithms)
   - [PAES](#paes)
   - [MOEA/D](#moead)
   - [Extra operators](#extra-operators)
   - [Configuration files and the launcher](#configuration-files-and-the-launcher)
6. [SLURM deployment patterns](#6-slurm-deployment-patterns)
7. [Internals](#7-internals)
8. [Monitoring and control](#8-monitoring-and-control)
   - [`GET /api/v1/status`](#get-apiv1status)
   - [`GET /api/v1/workers/status`](#get-apiv1workersstatus)
   - [`GET` and `POST /api/v1/config`](#get-and-post-apiv1config)
   - [`POST /api/v1/stop`](#post-apiv1stop)
   - [`status.json` file](#statusjson-file)

---

## 1. Architecture

```
┌───────────────────────────────────────────────────────────────┐
│  MASTER process  (Java + Spring Boot WebFlux)                 │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  Algorithm (NSGA-II, MOEA/D, SMS-EMOA, PAES)            │  │
│  │                                                         │  │
│  │  population ──► selection ──► crossover ──► mutation    │  │
│  │       ▲                                         │       │  │
│  │       └────── results                 tasks ────┘       │  │
│  └─────────────┬───────────────────────┬───────────────────┘  │
│                │  pendingTaskQueue     │  completedTaskQueue  │
│                ▼                       ▼                      │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  REST API  (configurable port)                          │  │
│  │                                                         │  │
│  │  Workers (section 2):                                   │  │
│  │  POST /api/v1/workers/heartbeat   ← still alive         │  │
│  │  GET  /api/v1/tasks/next          ← ask for a task      │  │
│  │  POST /api/v1/tasks/{id}/result   ← submit a result     │  │
│  │  POST /api/v1/tasks/{id}/error    ← report a failure    │  │
│  │                                                         │  │
│  │  Operators and scripts (section 8):                     │  │
│  │  GET  /api/v1/status, /api/v1/workers/status            │  │
│  │  GET  /api/v1/config, POST /api/v1/config               │  │
│  │  POST /api/v1/stop                                      │  │
│  └────────────────────────┬────────────────────────────────┘  │
└───────────────────────────│───────────────────────────────────┘
                            │  HTTP/JSON
        ┌───────────────────┼───────────────────────┐
        ▼                   ▼                       ▼
┌───────────────┐  ┌───────────────┐      ┌───────────────┐
│  Java worker  │  │ Python worker │  …   │ MATLAB worker │
│  (RestWorker) │  │  (jdisrest)   │      │  (raw HTTP)   │
└───────────────┘  └───────────────┘      └───────────────┘
```

### Flow

1. The master starts Spring Boot in a daemon thread and waits for workers.
2. Each worker starts, sends `POST /heartbeat` to register itself, and enters its loop.
3. The worker requests a task: `GET /tasks/next` with long-polling of up to 30 s. A steady-state master creates the task on demand when its queue is empty.
4. The master responds with a `taskId` + the decision vector (integers, reals, or both for composite problems).
5. The worker evaluates the objective function (this can take minutes or hours).
6. The worker returns the result: `POST /tasks/{id}/result` with objectives and constraints. If the evaluation fails, it reports it with `POST /tasks/{id}/error` instead; the master then requeues the task, or discards it once it has failed `AbstractMaster.DEFAULT_MAX_TASK_FAILURES` (3) times (section 2.3).
7. The master integrates the result into the population.
8. When the stopping criterion is met, or someone calls `POST /api/v1/stop` (section 8), the master responds `410 Gone` to the workers.

### Key concepts

| Concept | Description |
|---|---|
| **Task** | A `(taskId, variables[])` pair. Created by the master, evaluated by a worker. |
| **Long-polling** | The worker waits up to 30 s for a task to become available, without saturating the network with active polling. |
| **Heartbeat** | The worker signals every 15 s that it is still alive, on a thread separate from the evaluation one. |
| **Watchdog** | The master checks every 30 s which workers have not sent a recent heartbeat and requeues their tasks. |
| **InFlight** | Tasks assigned but not yet returned. If the worker dies, they are requeued. |
| **Failure limit** | A task whose evaluation fails (an `/error` report or a rejected result) is requeued, and discarded after `AbstractMaster.DEFAULT_MAX_TASK_FAILURES` (3) failed evaluations, so a solution that always fails cannot keep the workers busy forever (section 2.3). |
| **Steady-state** | The master creates a new task as soon as a worker asks for one and none is queued, without waiting for the whole generation. This is the default mode. |

---

## 2. REST protocol

Four calls: heartbeat, next task, result and error. That is all any worker, in any language, needs to implement. The other endpoints of the master (status, configuration and stop) serve operators and scripts; section 8 describes them.

### 2.1 `POST /api/v1/workers/heartbeat`

Tells the master the worker is still active. Also acts as the initial registration.

```
POST http://<master>:<port>/api/v1/workers/heartbeat
     ?workerId=<unique-id>
     &address=<worker-hostname>     // optional; diagnostics only

Response: 200 OK  (no body)
```

Send every 15 s on a separate thread. If the master receives no heartbeat within 45 s, it declares the worker dead and requeues its task (section 7.4).

### 2.2 `GET /api/v1/tasks/next`

Requests the next task. The master long-polls for up to 30 s.

```
GET http://<master>:<port>/api/v1/tasks/next?workerId=<unique-id>

Possible responses:
  200 OK   → a task is available
  204 No Content → no task right now (retry in a few seconds)
  410 Gone → algorithm finished (or stopped with POST /api/v1/stop), the worker must stop
  500 Internal Server Error → the master could not serialize the task (an unsupported
                              solution type); it counts as a failed evaluation (section 2.3).
                              Also sent, with no task involved, before the run has started (below)
```

The `500` has a second, transient cause. The REST server starts, and `.master-endpoint` is written, in the algorithm's constructor, but the bundled algorithms can only evaluate a stopping criterion that reads the evaluation count (such as `TerminationByEvaluations`) once `run()` has started. A worker that asks in between gets `500`, and so does `GET /api/v1/status`. The window is short when `run()` follows the constructor at once, as in `ConfiguredMaster`; both bundled workers treat the `500` as a failed request and retry 10 s later.

Body of the 200 response (JSON). Integer problem:

```json
{
  "taskId": 42,
  "variables": [3, -17, 55, 0, 81, ...]
}
```

Real-coded problem (`DoubleSolution`):

```json
{
  "taskId": 43,
  "variables": [0.25, -1.5, 3.0, ...],
  "encoding": "double"
}
```

Composite problem with an integer segment and a real segment:

```json
{
  "taskId": 44,
  "variables": [3, -17, 55, 0.25, -1.5],
  "segmentSizes": [3, 2],
  "encoding": "mixed",
  "segmentEncodings": ["int", "double"]
}
```

- `variables`: flat vector of numbers. Integer variables travel as JSON integers (`5`), real variables as JSON floats (`5.0`). For `CompositeSolution` it is the concatenation of the segments `[seg0 | seg1 | ...]`.
- `segmentSizes`: **only present for composite problems**: number of variables in each segment, so the worker can reconstruct the boundaries.
- `encoding`: `"double"` when every variable is real, `"mixed"` when a composite mixes integer and real segments. **Absent means every variable is an integer.**
- `segmentEncodings`: only for composites that are not all-integer: `"int"` or `"double"` per segment, aligned with `segmentSizes`.

The three optional fields are omitted for integer problems, so the JSON of an integer problem is byte for byte the one sent by jdisrest 1.0. A worker that hands the whole vector to a simulator can ignore all three; JSON parsers already deliver ints and floats with the right type.

The client timeout must be **greater than 30 s** (40 s is the project standard).

### 2.3 `POST /api/v1/tasks/{taskId}/result`

Delivers the result to the master.

```
POST http://<master>:<port>/api/v1/tasks/42/result
Content-Type: application/json

{
  "workerId":         "worker-python-01",
  "objectives":       [-1234.5],
  "constraints":      [],
  "evaluationTimeMs": 3200,
  "variables":        [ ... ]      // optional: see "Lamarckian repair" below
}

Possible responses:
  200 OK                     → result accepted
  404 Not Found              → the master no longer expects this result: the watchdog requeued the
                               task (worker took too long), or the run was stopped (POST /api/v1/stop)
  422 Unprocessable Content  → the result is invalid (see below); the task has been requeued (or discarded)
  400 Bad Request            → the body is not valid JSON (e.g. a bare NaN); the task has been requeued (or discarded)
```

- `objectives`: list of **doubles**, exactly one per objective of the problem. jMetal minimizes; to maximize, negate.
- `constraints`: list of doubles, exactly one per constraint of the problem. jMetal convention: `>= 0` satisfied, `< 0` violated. Empty list (or omitted) if the problem has no constraints.
- `evaluationTimeMs`: optional, for statistics.
- `variables`: **optional**. If the evaluator "repairs" the solution (Lamarckian search), it can return the repaired vector here and the master will overwrite the original solution before archiving the result. Omit it (or send an empty list, which means the same) if the variables are not modified. Same layout as the vector received. The JSON number type does not matter: the master converts each value to the type of the destination variable, so `2` is accepted for a real variable and `2.0` for an integer one. `2.7` for an integer variable is rejected.

The master validates the whole body before writing anything into the solution. It rejects with `422`: a wrong number of objectives or constraints, any `null`, `NaN`, `Infinity` or `-Infinity`, a `variables` vector whose length differs from the solution's, and a non-integral value for an integer variable. The body of the `422` (and of the `400`) is `{"taskId": 42, "reason": "objectives[1] is not finite: NaN"}`.

A rejected result is treated exactly like `POST /error`: it counts as a failed evaluation, the task goes back to the pending queue and the same solution will be evaluated again by whichever worker claims it. The same holds for the other requests Spring rejects before the handler runs, such as a wrong `Content-Type` (`415`).

**Failure limit.** After `AbstractMaster.DEFAULT_MAX_TASK_FAILURES` (3) failed evaluations, or the number set with `setMaxTaskFailures(int)` on the algorithm, the master discards the task instead of requeueing it: the task is never dispatched again, it is counted in `discardedTasks` of `GET /api/v1/status` (section 8), and the master logs it at ERROR level with its decision vector (the first 50 values of a longer one):

```
[task-42] Failed evaluation 1 of 3 — requeued
[task-42] Failed evaluation 3 of 3 — discarded; its variables were [0.25, -1.5, 3.0]
```

A failed evaluation is a `POST /error`, a `422` or `400` answer to a result, or a task the master cannot serialize for `GET /tasks/next` (its `500`). Tasks the watchdog requeues because their worker went silent do not count (section 7.4), so a task whose evaluation kills its worker (out of memory, a crash, a job memory limit) is still retried without limit. `setMaxTaskFailures` can be called at any time and applies from the next failure on, also to tasks that have already failed; a large value restores the unbounded retries of jdisrest 1.1, which had no limit. A steady-state algorithm simply loses a discarded offspring; a generational one receives a generation with fewer than `populationSize` results (section 7.6). If every result fails for the same reason (for example, workers evaluating another problem, with another number of objectives, get `422` on every result), every task ends up discarded and a run with an evaluation budget never finishes: watch `discardedTasks`.

An evaluator that produces `NaN` for some inputs must still map it to a finite penalty itself — the master has no way to choose one, and otherwise each such solution costs `DEFAULT_MAX_TASK_FAILURES` wasted evaluations and is lost to the search. Note that Python's `json.dumps` emits `NaN` as a bare token (invalid JSON, answered with `400`) and MATLAB's `jsonencode` turns `NaN` into `null` (answered with `422`); the bundled Python client validates finiteness before sending and reports the problem through `/error` instead. Its command-line worker (section 4.2) can apply the penalty: `--non-finite-penalty X`, or `FunctionEvaluator(function, non_finite_penalty=X)` from Python, gives `X` to every objective of a result whose objectives are not all finite, and logs a warning. It is opt-in and has no default, because no value is safe for every problem: choose one worse than any valid objective.

> The 404 is not a critical error — it means the watchdog requeued the task due to a timeout, or the run was stopped. The worker should log a warning and continue. The 400 and 422 are not network errors either: log the reason and move on to the next task.

### 2.4 `POST /api/v1/tasks/{taskId}/error`

Reports an evaluation failure (the worker hit an exception while evaluating). Makes the master requeue the task immediately instead of waiting for the watchdog, or discard it once it has reached the failure limit (section 2.3).

```
POST http://<master>:<port>/api/v1/tasks/42/error
Content-Type: application/json

{ "workerId": "worker-py-01", "errorMessage": "ZeroDivisionError: ..." }

Response: 200 OK  (no body), also when the task is no longer in flight
```

---

## 3. Defining a problem

A problem defines **how many variables** a solution has, their **bounds**, and optionally how to **evaluate** it. There are two strategies:

### Strategy A: evaluation on the master (pure Java)

The master evaluates the function directly. Workers only implement the basic REST protocol. Useful for tests, benchmarks, or cheap problems.

```java
package es.unex.example;

import org.uma.jmetal.problem.integerproblem.impl.AbstractIntegerProblem;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import java.util.Collections;

public class SphereProblem extends AbstractIntegerProblem {

    public SphereProblem(int numberOfVariables) {
        numberOfObjectives(1);
        numberOfConstraints(0);
        name("Sphere");
        variableBounds(
            Collections.nCopies(numberOfVariables, -100),
            Collections.nCopies(numberOfVariables, 100)
        );
    }

    @Override
    public IntegerSolution evaluate(IntegerSolution solution) {
        double f = 0;
        for (int x : solution.variables()) f += x * x;
        solution.objectives()[0] = f;
        return solution;
    }
}
```

> For multi-objective: `numberOfObjectives(2)` and fill in `solution.objectives()[0]` and `[1]`.

jdisrest accepts three encodings: `IntegerSolution` (`AbstractIntegerProblem`), `DoubleSolution` (`AbstractDoubleProblem`) and `CompositeSolution` whose segments are any mix of the two. The real-coded version of the same problem:

```java
package es.unex.example;

import org.uma.jmetal.problem.doubleproblem.impl.AbstractDoubleProblem;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import java.util.Collections;

public class SphereRealProblem extends AbstractDoubleProblem {

    public SphereRealProblem(int numberOfVariables) {
        numberOfObjectives(1);
        numberOfConstraints(0);
        name("SphereReal");
        variableBounds(
            Collections.nCopies(numberOfVariables, -5.12),
            Collections.nCopies(numberOfVariables, 5.12)
        );
    }

    @Override
    public DoubleSolution evaluate(DoubleSolution solution) {
        double f = 0;
        for (double x : solution.variables()) f += x * x;
        solution.objectives()[0] = f;
        return solution;
    }
}
```

The workers receive its variables as JSON floats and the task payload carries `"encoding": "double"` (section 2.2). Operators must match the encoding: `SBXCrossover` + `PolynomialMutation` (or the variants in `es.unex.jdisrest.operator`) for `DoubleSolution`, `IntegerSBXCrossover` + `IntegerPolynomialMutation` (or `IntegerSimpleRandomMutation`) for `IntegerSolution`.

### Strategy B: evaluation on the worker (external problem)

If the objective function is expensive or lives outside Java (a Python simulator, MATLAB code, a trained model, …), the Java problem only defines variables and bounds; evaluation happens on the workers.

```java
package es.unex.example;

import org.uma.jmetal.problem.integerproblem.impl.AbstractIntegerProblem;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import java.util.Collections;

public class MyExternalProblem extends AbstractIntegerProblem {

    public MyExternalProblem(int nVars, int lb, int ub) {
        numberOfObjectives(2);
        numberOfConstraints(0);
        name("MyExternalProblem");
        variableBounds(Collections.nCopies(nVars, lb), Collections.nCopies(nVars, ub));
    }

    @Override
    public IntegerSolution evaluate(IntegerSolution solution) {
        // Never called in distributed mode — the TaskController writes
        // the objectives directly onto the solution when it receives the
        // result from the worker.
        throw new UnsupportedOperationException(
            "Distributed evaluation — call from a worker");
    }
}
```

### Warm-start

The algorithm invokes `createInitialPopulationFromFile(n)` instead of jMetal's random initialization when **two** conditions are met: `iVAR.csv` exists in the working directory **and** the problem implements `es.unex.jdisrest.distributed.WarmStartCapable<S>`. If `iVAR.csv` exists but the problem does not implement the interface, a warning is logged and random initialization is used. Typical file convention: one solution per line, comma-separated variables; the implementation should top up with random solutions until the population is full.

The warm start works in the local NSGA-II and in the steady-state algorithms that build their initial solutions with `createInitialSolutions(count)` of `SteadyStateEvolutionaryAlgorithm`: NSGA-II and SMS-EMOA, which keep its `createInitialTasks()` and ask for `populationSize` solutions, and PAES, which asks for a single one and starts from the first solution it gets (from a random one if the list is empty). **MOEA/D ignores the warm start** and always starts from random solutions. `n` is therefore not always the population size. If the problem returns another number of solutions, the master logs `Initial population from iVAR.csv has size 80 instead of the 100 requested — starting from it as it is` and uses the list as it is (the steady-state algorithms create the missing solutions later as ordinary tasks); if it returns `null`, the run starts from random solutions.

When a run uses the warm start and has a traces folder, `iVAR.csv` is copied into it, replacing an older copy, so that the traces record the file the run was given. The copy is the file the problem was asked to read, not necessarily the population it built: `createInitialPopulationFromFile` takes no path and decides by itself how many rows to use, so it must read `WarmStart.FILE` (`iVAR.csv` in the working directory), the file whose presence enabled the warm start and the one copied. A failed copy is logged as a warning and the run goes on. `es.unex.jdisrest.distributed.WarmStart` holds this logic for both the distributed algorithms and the local NSGA-II.

### Composite problems

For problems with heterogeneous segments (e.g. three independent sets of variables with different bounds, or an integer segment next to a real one), implement `Problem<CompositeSolution>` directly. Segments may be `IntegerSolution`, `DoubleSolution` or any mix; the master flattens them in declaration order and tells the worker where each segment starts (`segmentSizes`) and how it is encoded (`segmentEncodings`, section 2.2). jMetal provides `CompositeCrossover` and `CompositeMutation`, but there is a latent aliasing bug — use the defensive wrapper `es.unex.jdisrest.operator.SafeCompositeCrossover` (drop-in replacement).

---

## 4. Workers

A worker is any process that (1) sends heartbeats, (2) requests tasks, (3) evaluates, (4) returns the result. The examples below use `SphereProblem` (f(x)=Σxᵢ², minimize).

### 4.1 Java worker

For problems with a Java `evaluate()` (Strategy A), instantiating `RestWorker<S>` is enough:

```java
import es.unex.jdisrest.distributed.RestWorker;
import es.unex.example.SphereProblem;

public class SphereWorker {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SphereWorker <master-url>");
            System.exit(1);
        }
        String masterUrl = args[0];

        var problem = new SphereProblem(10);
        try (var worker = new RestWorker<>(masterUrl, problem)) {
            worker.run();
        }
    }
}
```

`RestWorker` automatically handles:
- A heartbeat thread every 15 s in parallel with evaluation.
- Dead-master detection: 5 consecutive errors in the main loop or 3 failed heartbeats in a row → the worker shuts down cleanly.
- Network retries with a fixed 10 s wait between attempts.
- Any supported encoding: the received vector is written into `problem.createSolution()` through `SolutionVariables.apply()`, which splits composite solutions by segment and converts every value to the type of the destination variable.
- Evaluation failures: an exception thrown by `problem.evaluate()` (or a vector that does not fit the solution) is reported through `POST /error`, so the master requeues the task (or discards it after the failure limit, section 2.3), and the worker moves on.
- Rejected results (`400`/`422`) and results the master no longer expects (`404`): logged and skipped; they do not count towards dead-master detection.

Its worker id is always `worker-java-` followed by eight random characters.

### 4.2 Python worker

The `jdisrest` package (installable with `pip install -e <path-to-jdisrest>/python`) provides the `Worker` class with the same management as `RestWorker`.

```python
from jdisrest import Worker, EvalResult

def evaluate(variables) -> EvalResult:
    # ints for an integer-encoded problem, floats for a real-encoded one
    f = sum(x ** 2 for x in variables)
    return EvalResult(objectives=[float(f)])

# Option A: read the URL from .master-endpoint (the file must already exist)
Worker.from_endpoint(".master-endpoint").run(evaluate)

# Option B: direct URL
Worker("http://10.0.0.1:55000").run(evaluate)

# Option C: wait for .master-endpoint to appear (master and worker start at the same time)
Worker.wait_for_endpoint(".master-endpoint", timeout=300).run(evaluate)
```

For evaluators with expensive state (loading a model, initializing a simulator, etc.), use the `Evaluator` base class:

```python
from jdisrest import Worker, Evaluator, EvalResult

class MyEvaluator(Evaluator):
    def __init__(self, config_path):
        self.model = load_model(config_path)   # expensive, runs once

    def evaluate(self, variables):
        result = self.model.run(variables)
        return EvalResult(objectives=[-result.throughput])

Worker.from_endpoint().run(MyEvaluator("config.json"))
```

`evaluate()` may return:
- `EvalResult(objectives=[...], constraints=[...], variables=[...])`
- A `dict` with `objectives` / `constraints` / `variables` keys
- A scalar (treated as a single objective)
- Any object with an `.objectives` attribute

Before posting, the client converts objectives and constraints to plain floats (numpy scalars included) and checks that every value is finite. A `NaN` or `inf` is reported to the master through `/error`, with the field and index in the message, so the task is requeued (or discarded after the failure limit, section 2.3) and the log points at the evaluator. Repaired `variables` keep their kind: Python ints are sent as JSON integers and floats as JSON floats.

**Command-line worker.** When the evaluator is a plain function, no script is needed: `python -m jdisrest` (also installed as the `jdisrest-worker` command) imports it and runs a worker until the master finishes.

```bash
# /path/to/code/sphere.py:  def evaluate(variables): return sum(x ** 2 for x in variables)
python -m jdisrest --evaluator sphere:evaluate --code-dir /path/to/code \
    --endpoint /shared/run/.master-endpoint --timeout 300
```

`--evaluator MODULE:ATTR` names a function, an `Evaluator` subclass (instantiated without arguments) or an `Evaluator` instance, and `--code-dir` the folder `MODULE` is imported from. The function may also return a list or tuple of objectives. The worker connects to `--master URL` or, by default, waits up to `--timeout` seconds (300) for the endpoint file `--endpoint` (`.master-endpoint` in the current folder); a file that already exists is used at once, so delete a stale one before starting the master. `--worker-id`, `--variables N` and `--objectives N` (counts checked on the worker), `--non-finite-penalty X` (section 2.3) and `--log-level` complete the options. SIGTERM stops the worker as Ctrl+C does. The exit status is 0 once the worker stops, 1 if the evaluator cannot be loaded or the endpoint file never appears, and 2 for bad options. The same pieces are available from Python (`load_function`, `FunctionEvaluator`, and `add_worker_arguments`, `configure_logging` and `run_worker` for a worker command line of your own); see [`python/README.md`](../python/README.md).

### 4.3 MATLAB worker

MATLAB R2016b+ can use the protocol directly with `matlab.net.http`. Skeleton:

```matlab
function sphere_worker(masterUrl, workerId)
% MATLAB worker skeleton for jdisrest.
%
% Usage:
%   sphere_worker('http://10.0.0.1:55000', 'worker-matlab-01')

    if nargin < 1, masterUrl = 'http://localhost:8080'; end
    if nargin < 2
        workerId = ['worker-matlab-' datestr(now, 'HHMMSS')];
    end
    masterUrl = strip(masterUrl, 'right', '/');

    % Heartbeat every 15 s on a parallel timer
    hbTimer = timer('Period', 15, 'ExecutionMode', 'fixedRate', ...
                    'ErrorFcn', @(~,~) [], ...
                    'TimerFcn', @(~,~) send_heartbeat(masterUrl, workerId));
    start(hbTimer);

    MAX_ERRORS = 5; consecutiveErrors = 0;
    while true
        try
            [task, status] = request_next_task(masterUrl, workerId);
            switch status
                case 204, pause(5); continue
                case 410, fprintf('[%s] Algorithm finished.\n', workerId); break
                case 200
                    consecutiveErrors = 0;
                    taskId = task.taskId;
                    variables = double(task.variables);
                    t0 = tic;
                    objectives = sum(variables .^ 2);     % f(x) = Σxᵢ², one value per objective
                    elapsedMs = round(toc(t0) * 1000);
                    submit_result(masterUrl, workerId, taskId, objectives, elapsedMs);
                otherwise
                    error('Unexpected HTTP status: %d', status);
            end
        catch e
            consecutiveErrors = consecutiveErrors + 1;
            if consecutiveErrors >= MAX_ERRORS
                fprintf('[%s] %d consecutive errors — aborting.\n', workerId, consecutiveErrors);
                break
            end
            pause(10);
        end
    end
    stop(hbTimer); delete(hbTimer);
end

function [task, statusCode] = request_next_task(masterUrl, workerId)
    import matlab.net.http.*, import matlab.net.*
    uri = URI([masterUrl '/api/v1/tasks/next?workerId=' workerId]);
    resp = RequestMessage('GET').send(uri, HTTPOptions('ResponseTimeout', 40));
    statusCode = double(resp.StatusCode);
    task = []; if statusCode == 200, task = resp.Body.Data; end
end

function submit_result(masterUrl, workerId, taskId, objectives, elapsedMs)
    import matlab.net.http.*, import matlab.net.http.field.*, import matlab.net.*
    % num2cell forces a JSON array even for a single objective: jsonencode
    % would emit a bare scalar for a 1x1 double, and the master rejects it.
    payload = struct('workerId', workerId, 'objectives', {num2cell(objectives(:)')}, ...
                     'constraints', [], 'evaluationTimeMs', elapsedMs);
    uri = URI([masterUrl '/api/v1/tasks/' num2str(taskId) '/result']);
    req = RequestMessage('POST', ContentTypeField('application/json'), MessageBody(payload));
    resp = req.send(uri, HTTPOptions('ResponseTimeout', 15));
    code = double(resp.StatusCode);
    if code == 404
        % Requeued by the watchdog, or the run was stopped: the result is not needed.
        fprintf('[%s] task %d no longer expected by the master\n', workerId, taskId);
    elseif code == 400 || code == 422
        % The master could not apply the result and has requeued (or discarded) the task.
        fprintf('[%s] task %d rejected: %s\n', workerId, taskId, resp.Body.Data.reason);
    elseif code ~= 200
        error('Unexpected HTTP status %d from POST /result', code);
    end
end

function send_heartbeat(masterUrl, workerId)
    import matlab.net.http.*, import matlab.net.*
    try
        address = char(java.net.InetAddress.getLocalHost().getHostAddress());
        uri = URI([masterUrl '/api/v1/workers/heartbeat' ...
                   '?workerId=' workerId '&address=' address]);
        RequestMessage('POST').send(uri, HTTPOptions('ResponseTimeout', 5));
    catch  % transient failures are non-fatal; the timer keeps running
    end
end
```

Run:

```matlab
>> sphere_worker('http://10.0.0.1:55000', 'worker-matlab-01')
```

Or as a non-interactive script:

```bash
matlab -nodisplay -nosplash -r "sphere_worker('http://10.0.0.1:55000','worker-matlab-01'); exit"
```

`double(task.variables)` works for every encoding: `jsondecode` already delivers integers and reals as doubles. Two things to watch: `jsonencode` turns `NaN` into `null`, which the master rejects with `422` (map non-finite objectives to a finite penalty before `submit_result`), and a `422`/`400` response means the task was requeued, or discarded after the failure limit — `submit_result` above logs the reason and returns, so the loop continues with the next task instead of counting it as a connection error.

---

## 5. Master: wiring the algorithm

A minimal master:

```java
import es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.operator.IntegerSimpleRandomMutation;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover;
import es.unex.example.SphereProblem;

public class SphereMaster {
    public static void main(String[] args) {
        var problem     = new SphereProblem(10);
        var crossover   = new IntegerSBXCrossover(0.9, 20.0);
        var mutation    = new IntegerSimpleRandomMutation(1.0 / 10);
        var termination = new TerminationByEvaluations(5000);

        var algo = new NSGAII<>(
            "10.0.0.1", 8080,       // the address the workers use to reach this machine
            problem, /*popSize=*/ 50,
            crossover, mutation, termination,
            /*tracesFolder=*/ null);

        MasterFacade.init(5000, /*statusFileIntervalSec=*/ 30);
        algo.run();

        algo.getResult().forEach(s ->
            System.out.println("f=" + s.objectives()[0] + "  vars=" + s.variables()));
        System.exit(0);             // the REST server keeps the JVM alive after run()
    }
}
```

The constructor starts the REST server, which always listens on every interface of the machine; the host is only the address written to `.master-endpoint` for the workers, so it must be one they can reach (not `0.0.0.0`). `MasterFacade.init(maxEvals, statusFileIntervalSec)` starts the periodic `status.json` writer and records the start time for progress/ETA computation. Call it right before `algo.run()`. To change the failure limit of section 2.3, call `algo.setMaxTaskFailures(n)` before `run()` as well. The REST server keeps running after `run()` returns, so a program must end with `System.exit`.

For a real-coded problem, `es.unex.jdisrest.config.ConfiguredMaster` runs NSGA-II, PAES or MOEA/D from a configuration file without a `main` of your own (see [Configuration files and the launcher](#configuration-files-and-the-launcher)).

### Available algorithms

- `es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII` — tournament + ranking/crowding.
- `es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD` — weight-based decomposition. The weight vectors come from `MOEADWeights` (spread for any population size, or the Das–Dennis lattice), unlike jMetal and Evolver, which read those of three or more objectives from files that exist for a few sizes only. The aggregation, with the ideal and nadir points and the optional normalization, is `MOEADAggregation`. Both are Spring-free and tested (see below).
- `es.unex.jdisrest.distributed.algorithms.steadystate.SMSEMOA` — hypervolume.
- `es.unex.jdisrest.distributed.algorithms.steadystate.PAES` — (1+1) evolution strategy with a bounded density archive; mutation only (see below).
- `es.unex.jdisrest.local.algorithms.NSGAII` — sequential local variant (no REST), useful for debugging small problems or measuring distribution overhead. Uses the `PythonProcessEvaluator` / `PythonSolutionListEvaluator` evaluators from the `es.unex.jdisrest.local` package.

### PAES

PAES (Pareto Archived Evolution Strategy) keeps a single current solution and creates offspring by mutation only, so it takes no crossover. A bounded archive is both the result and a density estimator: an offspring that dominates the current solution replaces it, a dominated one is discarded, and when neither dominates the other, the offspring enters the archive (if the archive accepts it) and replaces the current solution only if the archive is full and the offspring lies in a less crowded region. These are the rules of jMetal 7.5's `PAESSelection` and `PAESReplacement`, on which the configurable PAES of Evolver is built, ported to `PAESState` because jdisrest uses jMetal 7.1. They differ from the original PAES of Knowles and Corne, and from jMetal 7.1's own `PAES`, which compare densities on every mutually non-dominated offspring and use an adaptive grid; that archive is `new GenericBoundedArchive<>(archiveSize, new GridDensityEstimator<>(bisections, problem.numberOfObjectives()))`, which the second constructor below accepts. A real-coded master with the default settings:

```java
var problem = new ZDT1();                                    // org.uma.jmetal.problem.multiobjective.zdt
var mutation = new PolynomialMutation(1.0 / problem.numberOfVariables(), 20.0);

var algo = new PAES<>(
    "10.0.0.1", 8080,
    problem, /*archiveSize=*/ 100,
    mutation, new TerminationByEvaluations(25000),
    /*tracesFolder=*/ null);
```

The archive is a `CrowdingDistanceArchive` of 100 solutions with constraint-aware dominance, the current solution is always the parent, and `getResult()` returns the feasible members of the PAES archive. The second constructor makes every setting explicit:

```java
var algo = new PAES<>(
    "10.0.0.1", 8080, problem,
    new KNNDistanceArchive<>(100, 3),         // any jMetal BoundedArchive: AngleArchive, SpatialSpreadDeviationArchive, …
    mutation,
    /*archiveSelectionProbability=*/ 0.2,     // mutate a random archive member instead of the current solution
    PAES.ResultSource.EXTERNAL_ARCHIVE,       // or PAES_ARCHIVE
    new TerminationByEvaluations(25000),
    /*tracesFolder=*/ null);
```

`EXTERNAL_ARCHIVE` returns the archive of every non-dominated solution evaluated, reduced to `archiveSize` solutions by distance-based subset selection, as the other algorithms do. Both constructors check their arguments (a `null`, a probability outside [0, 1], an empty archive) before the REST server starts.

How PAES runs on the steady-state master:

- Every worker that asks for work gets a mutated copy of the current solution (or, with probability `archiveSelectionProbability`, of an archive member), so several offspring of the same current solution can be under evaluation at once. Each result is compared with the current solution at the moment it arrives.
- The master starts from a single solution. Workers that ask for work before its result comes back receive random solutions. No spare task is queued.
- An offspring identical to its parent (for example when no variable was mutated, which happens about 36% of the time with probability `1/n` and 30 variables) is mutated again instead of wasting an evaluation, up to 1000 times; then the clone is evaluated and a warning logged. The attempts run under the population lock, so a mutation that almost never changes anything slows down task creation for every worker.
- Dominance takes constraints into account (`DominanceWithConstraintsComparator`), as in the other algorithms, and so does the default archive. An archive prunes with its own comparator, though: `GenericBoundedArchive` and `KNNDistanceArchive` always use plain Pareto dominance, and so do `AngleArchive` and `CrowdingDistanceArchive` unless a comparator is passed to them. With such an archive an infeasible member that dominates in objective space keeps feasible solutions out of the archive, the current one included, so the result can end up smaller (`getResult()` still returns feasible solutions only). For constrained problems, use the default archive or pass a constraint-aware comparator.
- Warm start (section 3) asks for a single solution and uses the first one it gets. Every `archiveSize` evaluations the traces write the external archive to `aVAR`/`aFUN` and the PAES archive to `VAR`/`FUN`, so a run that is killed keeps the set `getResult()` returns by default.
- `reconfigure(mutation, archiveSelectionProbability, resultSource)` changes those three settings while the run goes on (`POST /api/v1/config`, section 8); the archive and its size stay.

### MOEA/D

`MOEAD` has two constructors. The 13-argument one of earlier versions uses spread weights and no normalization; the 15-argument one adds the weight method and the normalization before the selection operator:

```java
var problem = new DTLZ2();                                   // org.uma.jmetal.problem.multiobjective.dtlz: 12 variables, 3 objectives
var algo = new MOEAD<>(
    "10.0.0.1", 8080, problem, /*populationSize=*/ 91,
    new SBXCrossover(0.9, 20.0),
    new PolynomialMutation(1.0 / problem.numberOfVariables(), 20.0),
    new TerminationByEvaluations(50000),
    /*T=*/ 20, /*delta=*/ 0.9, MOEAD.AggregationFunction.TCHEBYCHEFF,
    /*maxReplacedSolutions=*/ 2,
    MOEADWeights.Method.LATTICE, /*normalizeObjectives=*/ true,
    /*selectionOperator=*/ null,             // MOEA/D picks its parents itself
    /*tracesFolder=*/ "traces");
```

- **Weights** (`MOEADWeights`, one vector per subproblem). `SPREAD` takes any population size: it starts from the corners of the simplex (one objective each) and adds, one after another, the candidate farthest from the vectors already picked, among 50 candidates per vector (at least 20000) drawn uniformly on the simplex with a fixed seed, so every run gets the same vectors. It costs about 10 ms for 100 vectors, 0.2 s for 1000 and 5 s for 5000. `LATTICE` is the simplex lattice of Das and Dennis, which only exists for C(H+m−1, m−1) vectors with m objectives: any size for two objectives, 66, 78, 91, 105 or 120 for three (H = 10 to 14), 84, 120 or 165 for four (H = 6 to 8). With two objectives both give the evenly spread vectors (i/(N−1), 1−i/(N−1)), a single vector is the centre of the simplex, and with a single objective every vector is [1.0]. `MOEADWeights.check(method, numberOfObjectives, populationSize)` returns why a size does not fit (naming the two closest lattice sizes), or `null`. When there is a traces folder, the vectors are written to `weights.csv` in it, one line per subproblem in population order, as soon as the master is built.
- **Aggregation** (`MOEADAggregation`). The ideal point is the minimum of each objective over every result. The nadir point is the maximum over the non-dominated results, with the feasibility-first dominance of jMetal's `NonDominatedSolutionListArchive`: once a feasible solution has arrived, infeasible ones no longer count, however good their objectives. With `normalizeObjectives`, each objective f becomes (f − z\*) / (nadir − z\* + 10⁻⁶), as jMetal's `MOEADReplacement` does; without, f − z\*. Tchebycheff multiplies an objective whose weight is 0 by 10⁻⁴ instead of 0, as jMetal does, so that it still breaks ties; PBI uses θ = 5. With normalization PBI also divides its perpendicular distance by nadir − z\* + 10⁻⁶, where jMetal 7.1 leaves the 10⁻⁶ out. The set of non-dominated points behind the nadir is unbounded and is tracked even without normalization, so that `reconfigure` can switch it on during a run.
- **Validation.** Every argument is checked before the REST server starts: the weight method against the problem's number of objectives (`IllegalArgumentException`), `T` in [1, `populationSize`], `maxReplacedSolutions` in [1, `T`], `delta` in [0, 1], and no `null` crossover, mutation, termination, aggregation or weight method.
- `reconfigure(crossover, mutation, neighborhoodSelectionProbability, maxReplacedSolutions, aggregation, normalizeObjectives)` changes those settings while the run goes on; the weights, the neighbourhoods and their size stay. MOEA/D does not use the warm start (section 3).

Changes for code written against jdisrest 1.1, which also apply to the 13-argument constructor:

- With three or more objectives the weights are the deterministic `SPREAD` vectors instead of unseeded random ones, so runs are reproducible but not comparable with runs of 1.1. With two objectives they are the same, and with a single subproblem the vector is the centre of the simplex instead of `NaN`.
- Tchebycheff weighs a zero weight by 10⁻⁴, which changes the two corner subproblems of every two-objective run.
- A `delta` outside [0, 1] and a `null` operator, termination or aggregation are rejected at construction, before the server starts.
- `weights.csv` is written, and the traces folder created, at construction.
- The protected fields `agg` and `idealPoint` are gone: the aggregation and both points live in the protected `aggregation` field (a `MOEADAggregation`), next to the new `weightMethod`. `lambda` is no longer allocated before `initWeightVectors()`, so an override must assign a new array.

### Extra operators

Under `es.unex.jdisrest.operator.*`:

- `IntegerSimpleRandomMutation`, `IntegerGaussianMutation`, `IntegerBLXCrossover`: operators for `IntegerSolution`.
- `PolynomialMutationRandomProbability`, `RandomMutationWithRandomProbability`: mutations for `DoubleSolution` with a per-call randomized probability.
- `DoubleNPointCrossover(probability, points[, blockSize])`: n-point crossover for `DoubleSolution` that always makes exactly `points` distinct cuts (jMetal's `NPointCrossover` draws each point independently, so its cuts can coincide or cut nothing) and returns copies of the parents when the crossover is not applied (jMetal returns the parents themselves). With a block size b > 1 the cuts fall only between consecutive blocks of b variables, so tuples such as the (x, y, z) coordinates of each point of a shape pass whole from a parent to a child. The number of variables must be a multiple of b, there must be at least 2 blocks, and `points` must be smaller than the number of blocks. Those rules depend on the problem, so `execute()` checks them at every call, and inside a master the first crossover happens in the task request of a worker: call `DoubleNPointCrossover.check(numberOfVariables, points, blockSize)`, which returns the reason or `null`, before the run.
- `LevyFlightMutationRandomStepSize(probability, beta, maximumStepSize)`: Lévy flight mutation whose step size is drawn for every mutated solution, uniform in [0, `maximumStepSize`), so the mean step size is half the maximum; it then mutates exactly as jMetal's `LevyFlightMutation` with that step. `beta` must lie in (1, 2), and steps shrink strongly as it approaches 2. The three-argument constructor clamps values to their bounds and leaves a variable whose lower and upper bounds are equal at that value, where jMetal's default repair throws. Note that jMetal's `LevyFlightMutation` accepts `beta = 2`, where the steps of Mantegna's algorithm vanish (about 10⁻⁸ of the range) and nothing visibly mutates; this operator and the configuration files reject it.
- `NaryTournamentSelection`: n-ary tournament selection.
- `DifferentialEvolutionSelection`: DE parent selection for `DoubleSolution` populations. **Not wired into any algorithm of this release**: it needs `setIndex(i)` before every `execute()` and a DE crossover fed with the current solution, and neither the steady-state base class nor MOEA/D does that (MOEA/D builds its parents itself and ignores the selection operator). Usable only from a custom algorithm.
- `SafeCompositeCrossover`: wrapper around `CompositeCrossover` that avoids a latent aliasing bug in jMetal — drop-in replacement, same constructor signature.

### Configuration files and the launcher

The package `es.unex.jdisrest.config` reads the settings of a run from a Java properties file (the algorithm, the evaluation budget, the population or archive size, the traces folder, and the operators with their probabilities and parameters), and `es.unex.jdisrest.config.ConfiguredMaster` runs a master from such a file. It covers real-coded problems only (a jMetal `DoubleProblem`, whose solutions are `DoubleSolution`s) and three algorithms: NSGA-II, PAES and MOEA/D. The crossovers take two parents and return two children, which is what the steady-state algorithms use. `examples/nsgaii.properties`, `examples/paes.properties` and `examples/moead.properties` list every key with its default and rules in their comments.

**Running a master.** `ConfiguredMaster` takes the fully qualified name of a `DoubleProblem` class with a public constructor without arguments, then either the address, port and file of a run or `--check` and the file. With jdisrest built from source, a run of jMetal's ZDT1 (30 variables, 2 objectives) looks like this:

```bash
cd /path/to/jdisrest
mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
CP="target/classes:target/dependency/*"     # on Windows: "target\classes;target\dependency\*"

java -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster \
    org.uma.jmetal.problem.multiobjective.zdt.ZDT1 --check examples/nsgaii.properties
# Configuration OK for ZDT1: NSGA-II, 25000 evaluations, population 100, crossover sbx (probability 0.9,
# distributionIndex 20), mutation polynomial (probability 0.03333, distributionIndex 20), traces in traces

java -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster \
    org.uma.jmetal.problem.multiobjective.zdt.ZDT1 10.0.0.1 8080 examples/nsgaii.properties maxEvaluations=2000
```

and its workers, started from the same folder, evaluate ZDT1 in Python with the command-line worker of section 4.2:

```python
# zdt1.py
import math

def evaluate(x):
    f1 = x[0]
    g = 1 + 9 * sum(x[1:]) / (len(x) - 1)
    return [f1, g * (1 - math.sqrt(f1 / g))]
```

```bash
python -m jdisrest --evaluator zdt1:evaluate --code-dir . --endpoint .master-endpoint
```

The class path needs jdisrest, its dependencies and the problem's class. A run goes through these steps:

1. The problem is created once, and the file is read for it, with the `key=value` arguments after it.
2. The algorithm is built with a `TerminationByEvaluations` of `maxEvaluations`; this starts the REST server and writes `.master-endpoint`. NSGA-II is `NSGAII`, PAES is `PAES` with a `CrowdingDistanceArchive` of `archiveSize` solutions and constraint-aware dominance, and MOEA/D is the 15-argument `MOEAD` without a selection operator.
3. `MasterFacade.init` gets the same budget and writes `status.json` every 30 s.
4. The traces folder, if the file names one, receives the configuration and `configuration.log` (see below).
5. An `AlgorithmReconfiguration` is registered, so `GET /api/v1/config` returns the configuration in use and `POST /api/v1/config` changes it (section 8).
6. The algorithm runs until its budget is spent or `POST /api/v1/stop`, and its result is written to `VAR.csv` and `FUN.csv` (comma-separated) in the working directory; a run stopped before its first result writes them empty. The master logs `ZDT1 finished after <evaluations> evaluations: <n> solutions written to VAR.csv and FUN.csv` and the JVM exits.

`--check` reads the file for the problem exactly as a run does (every value, the operators, which are built once, and the MOEA/D lattice size against the problem's objectives), prints `Configuration OK for <class name>: <summary>` on the standard output and exits without starting Spring, so a script can gate the submission of a job on its exit status. The exit status is 0 when a run ends (also after `POST /api/v1/stop`) or a check passes, and 1 otherwise. A usage error prints `Invalid arguments: <reason>` and the usage text (for instance `port must be an integer in [1, 65535], got '0'`); an invalid file prints `Invalid configuration: <reason>`, such as `mutation.beta must be in (1, 2), got '2'`; both go to the standard error without a stack trace. A failure while the run starts or goes on is logged as `<class name> failed: <exception>` with its stack trace, and still ends the JVM with status 1.

A problem that needs constructor arguments, or a program that wants its own command name, calls the same launcher from its own `main` and exits with the status it returns:

```java
public static void main(String[] args) {
    // args: <host> <port> <configFile> [key=value ...]   or   --check <configFile> [key=value ...]
    System.exit(ConfiguredMaster.run(() -> new ZDT1(50), "ZDT1 with 50 variables", List.of(args), "MyMaster"));
}
```

**Keys.** Only `algorithm` and `maxEvaluations` are required; the defaults are in parentheses.

| Key | Algorithm | Values (default) |
|---|---|---|
| `algorithm` | all | `nsgaii`, `paes` or `moead` (required) |
| `maxEvaluations` | all | positive integer such as `25000`; `1e5` is rejected (required) |
| `populationSize` | NSGA-II, MOEA/D | positive integer (100) |
| `archiveSize` | PAES | positive integer: the size of the PAES archive (100) |
| `tracesFolder` | all | folder for the traces, relative to the working directory of the master; missing or empty: no traces (none) |
| `crossover` | NSGA-II, MOEA/D | `sbx`, `blxAlpha`, `laplace`, `arithmetic`, `wholeArithmetic` or `nPoint` (`sbx`) |
| `crossover.probability` | NSGA-II, MOEA/D | [0, 1] or `k/n` (0.9) |
| `crossover.distributionIndex` | `sbx` | ≥ 0; larger values give children closer to their parents (20) |
| `crossover.alpha` | `blxAlpha` | ≥ 0: how much the interval between the parents' values is widened on each side (0.5) |
| `crossover.scale` | `laplace` | > 0 (0.5) |
| `crossover.points` | `nPoint` | integer from 1 to the number of blocks minus 1 (2) |
| `crossover.blockSize` | `nPoint` | integer that divides the number of variables, with at least 2 blocks: the cuts fall only between blocks of that many consecutive variables (1) |
| `mutation` | all | `polynomial`, `linkedPolynomial`, `uniform`, `random`, `levyFlight` or `levyRandom` (`polynomial`) |
| `mutation.probability` | all | [0, 1] or `k/n`: the probability of mutating each variable (`1/n`) |
| `mutation.distributionIndex` | `polynomial`, `linkedPolynomial` | ≥ 0; larger values give smaller perturbations (20). In jMetal 7.1 `linkedPolynomial` behaves as `polynomial` on a `DoubleSolution` |
| `mutation.perturbation` | `uniform` | > 0: adds a uniform value in [−p/2, p/2), an absolute amount in the units of the variable, not a fraction of its range (0.5) |
| `mutation.beta` | `levyFlight`, `levyRandom` | in (1, 2) (1.5) |
| `mutation.stepSize` | `levyFlight`, `levyRandom` | > 0, times the range of the variable (0.01). For `levyRandom` it is the maximum of a step size drawn per mutated solution, uniform in [0, `stepSize`), so the mean step is `stepSize`/2 |
| `archiveSelectionProbability` | PAES | [0, 1] or `k/n`: probability of mutating a random archive member instead of the current solution (0.0, classic PAES) |
| `result` | PAES | `paes` (the PAES archive) or `external` (every non-dominated solution evaluated, reduced to `archiveSize`) (`paes`) |
| `weights` | MOEA/D | `spread` or `lattice` (`spread`); see [MOEA/D](#moead) for the lattice sizes |
| `neighborSize` | MOEA/D | integer from 1 to `populationSize`: the subproblems with the closest weights that form each neighbourhood (20, or `populationSize` if smaller) |
| `neighborhoodSelectionProbability` | MOEA/D | [0, 1] or `k/n`: probability of taking the parents from the neighbourhood instead of the whole population (0.9) |
| `maximumNumberOfReplacedSolutions` | MOEA/D | integer from 1 to `neighborSize`: neighbours that one offspring can replace at most (2, or `neighborSize` if smaller) |
| `aggregation` | MOEA/D | `tchebycheff`, `wsum` (weighted sum) or `pbi` (penalty-based boundary intersection, θ = 5) (`tchebycheff`) |
| `normalizeObjectives` | MOEA/D | `true` or `false`: whether the aggregation normalizes the objectives between the ideal and nadir points (`false`) |

**Rules.**

- `k/n` is k divided by the number of variables of the problem (`3/n`, `0.5 / N`); the result must still lie in [0, 1]. It is allowed for every probability.
- Names of algorithms, operators and the other values (`paes`, `spread`, `pbi`, `true`...) are case-insensitive, and spaces around values are ignored; keys are case-sensitive. Numbers are plain decimals (`0.5`, `1e-3`): Java literals such as `0x1p-2` or `20d`, `NaN` and `Infinity` are rejected, and operator parameters must not be negative.
- A key that the chosen algorithm or operators do not use is an error, so a misspelt key stops the run instead of being ignored: `unknown keys for nsgaii with these operators: mutaton.probability. Valid keys: algorithm, maxEvaluations, ...`. A `crossover` key in a PAES file is such an error. A key written twice is not detected: as in any properties file, the last occurrence wins.
- Every operator parameter is checked against the problem, and every operator built once, while the file is read, so a value the operator would reject is reported with its key, for instance `crossover.blockSize: the number of variables (30) is not a multiple of the block size (4)`; a value that only a jMetal constructor rejects is reported as `crossover: <operator> cannot be built with these values: <reason>`. Every catalogue operator tolerates a variable whose lower and upper bounds are equal and keeps its value.
- With `weights = lattice`, a `populationSize` that is not a lattice size for the problem's objectives is rejected when the file is read for the problem (`--check`, a run, `POST /api/v1/config`): `populationSize: LATTICE weights for 3 objectives need C(H+2, 2) vectors, such as 91 or 105, got 100 (spread weights take any size)`.
- Each `key=value` argument after the file replaces or adds that key, taken literally.
- The file is read as UTF-8, and a leading byte order mark is ignored; a file that is not valid UTF-8 (for instance one saved as Latin-1 with accented characters) is rejected with `it is not UTF-8 text; save it with the UTF-8 encoding`. In a properties file a backslash starts an escape sequence (`\t` is a tab), so write Windows paths with `/` or with doubled backslashes (`C:/runs/traces` or `C:\\runs\\traces`). A `tracesFolder` that can only come from single backslashes (one with a control character, or a drive letter not followed by a separator, such as `C:runs`) is rejected with that advice. A `key=value` argument has no escapes.

**The traces folder.** Besides the trace snapshots (`aVAR_<n>.csv`/`aFUN_<n>.csv` for the archive and `VAR_<n>.csv`/`FUN_<n>.csv` for the population, or the PAES archive for PAES, every `populationSize` evaluations, or every `archiveSize` for PAES) and MOEA/D's `weights.csv`, a run with a traces folder leaves there the configuration it used (`<name>` below is the name of the file without `.properties`):

- A copy of the file under its own name, with exactly one active line per key: the lines that a command-line override replaces are commented out where they are, and the overrides are appended at the end. A key that the file sets twice keeps its last line, and the earlier one is commented out as `# overridden by a later line: ...`. Editing the value where a key appears in the copy, or in the same text served by `GET /api/v1/config`, therefore changes that key:

  ```properties
  # overridden on the command line: maxEvaluations = 25000
  ...
  mutation.distributionIndex = 20

  # Command-line overrides; the lines of the file they replace are commented out above
  maxEvaluations = 2000
  ```

  When the run starts from a file that is already in the traces folder (a relaunch from the copy), that file is never rewritten: if its text differs from the effective configuration, the copy is `<name>_0.properties` (`<name>_0_2.properties` and so on if that exists).
- Every change applied with `POST /api/v1/config`, saved as `<name>_<evaluations>.properties`, named after the evaluations after which it took effect (with `_2`, `_3`... if several changes share a count).
- `configuration.log`, with a header line for every run that uses the folder (a blank line separates them) and one line per configuration: the time, the evaluations, the file and the summary of `--check`:

  ```
  # Run started 2026-10-02T10:00:00 from /shared/runs/run-01/run.properties with overrides maxEvaluations=50000
  2026-10-02T10:00:00  evaluations 0         run.properties                  NSGA-II, 50000 evaluations, population 100, ...
  2026-10-02T13:41:07  evaluations 21400     run_21400.properties            NSGA-II, 80000 evaluations, population 100, ...
  ```

The record is written after the algorithm has been built, so a run that fails to start leaves nothing in a traces folder that later runs reuse.

**Changes during a run.** `POST /api/v1/config` (section 8) takes a complete file. It can change `maxEvaluations` (to a value greater than the evaluations already done), the crossover and mutation with their probabilities and parameters, PAES's `archiveSelectionProbability` and `result`, and MOEA/D's `neighborhoodSelectionProbability`, `maximumNumberOfReplacedSolutions`, `aggregation` and `normalizeObjectives`. It cannot change `algorithm`; `populationSize` or `archiveSize`, which shape the population, the archive and the trace cadence; MOEA/D's `weights` and `neighborSize`, which shape the subproblems and their neighbourhoods; or `tracesFolder` (`traces`, `./traces` and `traces/` count as the same folder).

**From your own code.** `AlgorithmConfig.load(file, overrides, problem)` reads a file and its `key=value` overrides for a problem, and `AlgorithmConfig.parseText(text, problem)` the text of one, both with every check above; they return an `NSGAIIConfig`, `PAESConfig` or `MOEADConfig` record and throw `InvalidConfigurationException` (an `IllegalArgumentException`) naming the key at fault. The overloads that take the number of variables instead of the problem check everything but the lattice size, which the `MOEAD` constructor then checks before its server starts. `describe()` gives the one-line summary, `ConfiguredMaster.createAlgorithm(host, port, problem, config)` builds the algorithm as step 2 above, and `AlgorithmConfig.fixedDuringRun(current, next)` lists the changes a running algorithm cannot take.

---

## 6. SLURM deployment patterns

The master runs as one job and the workers as a job array that starts when the master's job starts. They meet in a *run folder* that every node can read: the master writes `.master-endpoint` and `status.json` there (`-Djdisrest.dataPath`), and the workers read the endpoint file to find the master. The general pattern:

1. Build jdisrest once: `mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime` leaves the classes in `target/classes` and every dependency in `target/dependency`, so no fat jar is needed; add your problem's jar or classes to the class path. Install the Python client (`pip install /path/to/jdisrest/python`) in a virtual environment the compute nodes can use.
2. Create the run folder with the configuration file (`run.properties`, for instance a copy of `examples/nsgaii.properties`) and check it with `ConfiguredMaster <problemClass> --check run.properties` before queueing anything.
3. Submit the master job, then the worker array with `--dependency=after:<master job>`: `after` starts the workers once the master's job has started (`afterok` would wait for it to end).
4. Any `.master-endpoint` left by an earlier run is deleted before the jobs are submitted (and again by the master job). The master job starts the master with a routable address and a port, and the master writes `.master-endpoint` atomically. Each worker waits for that file and works until the master answers `410 Gone`, or, if the master process has already exited, until it finds no master (section 8).
5. When the run ends (its budget is spent, or `POST /api/v1/stop`), the master writes `VAR.csv` and `FUN.csv` to the run folder and exits; the job removes `.master-endpoint` and cancels the worker tasks still waiting in the queue.

```json
// .master-endpoint
{ "host": "10.0.0.1", "port": 55312, "url": "http://10.0.0.1:55312" }
```

`submit.sh`, run on the login node from the run folder:

```bash
#!/bin/bash
module load java                 # site-specific: the module that provides Java 25
set -euo pipefail                # after module load, which some module systems break under set -u
JDISREST=/path/to/jdisrest
CP="$JDISREST/target/classes:$JDISREST/target/dependency/*:/path/to/my-problem.jar"

# Status 1 for a configuration the run would reject: nothing is queued then.
java -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster com.example.MyProblem --check run.properties

# A worker can start as soon as the master's job does: remove a stale endpoint file now.
rm -f .master-endpoint

master=$(sbatch --parsable master.sh | cut -d';' -f1)
if ! workers=$(sbatch --parsable --dependency=after:"$master" --array=0-99 worker.sh | cut -d';' -f1); then
    scancel "$master"            # without workers the master would wait until its time limit
    exit 1
fi
echo "$workers" > worker_job     # master.sh cancels the pending ones when the run ends
echo "master $master, workers $workers"
```

`master.sh`:

```bash
#!/bin/bash
#SBATCH --job-name=jdisrest-master
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=2
#SBATCH --mem=<memory>
#SBATCH --time=<time limit>
#SBATCH --output=master-%j.log
module load java                 # site-specific: the module that provides Java 25
set -euo pipefail
JDISREST=/path/to/jdisrest
CP="$JDISREST/target/classes:$JDISREST/target/dependency/*:/path/to/my-problem.jar"
RUN_DIR="$SLURM_SUBMIT_DIR"      # the run folder, shared by every node

# The address the workers use: the source address of the node's default route (8.8.8.8 is
# only looked up in the routing table; nothing is sent). A fast interface such as ib0 is not
# necessarily reachable from every node.
host=$(ip -4 route get 8.8.8.8 2>/dev/null \
    | awk '{for (i = 1; i < NF; i++) if ($i == "src") {print $(i + 1); exit}}' || true)
[[ -n "$host" ]] || host=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
[[ -n "$host" ]] || host=$(hostname)
port=$((RANDOM % 10000 + 50000))

# A stale endpoint file would send the workers to a master that is gone.
rm -f "$RUN_DIR/.master-endpoint"

status=0
java -Djdisrest.dataPath="$RUN_DIR" -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster \
    com.example.MyProblem "$host" "$port" "$RUN_DIR/run.properties" || status=$?

rm -f "$RUN_DIR/.master-endpoint"
if [[ -s "$RUN_DIR/worker_job" ]]; then
    scancel --state=PENDING $(cat "$RUN_DIR/worker_job") || true
fi
exit "$status"
```

`worker.sh`:

```bash
#!/bin/bash
#SBATCH --job-name=jdisrest-worker
#SBATCH --ntasks=1
#SBATCH --cpus-per-task=1
#SBATCH --mem=<memory of one evaluation>
#SBATCH --time=<time limit>
#SBATCH --output=worker-%A_%a.log
module load python               # site-specific: the module that provides Python 3.11 or later
set -euo pipefail
source /path/to/venv/bin/activate

# One thread per worker, as --cpus-per-task=1 asks, and matplotlib without a display.
export OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 MPLBACKEND=Agg

python -u -m jdisrest --evaluator my_problem:evaluate --code-dir /path/to/code \
    --endpoint "$SLURM_SUBMIT_DIR/.master-endpoint" --timeout 900 \
    --worker-id "worker-${SLURM_ARRAY_JOB_ID}-${SLURM_ARRAY_TASK_ID}"
```

Things to know:

- `-Djdisrest.dataPath=<dir>` controls where the master writes `.master-endpoint` and `status.json`; that directory must exist and be visible to the worker nodes. The master never deletes an old `.master-endpoint` itself, and a worker uses an existing file at once, hence the `rm -f` in `submit.sh` and `master.sh`.
- A program with its own `main` (section 5) can replace `ConfiguredMaster` in `master.sh`: it must advertise the host and port the script computes and end with `System.exit`, because the REST server keeps the JVM alive after `run()`.
- To stop the run early, from any machine that can reach the master: `url=$(python3 -c "import json; print(json.load(open('.master-endpoint'))['url'])")` and `curl -X POST "$url/api/v1/stop"`. The master writes `VAR.csv` and `FUN.csv` as at a normal end and exits, the workers stop (section 8), and `master.sh` cancels the pending ones. To change its settings instead, `curl -s "$url/api/v1/config" > new.properties`, edit the file and `curl --data-binary @new.properties "$url/api/v1/config"` (section 8); every change is saved in the traces folder.
- The master does not handle SIGTERM: at its time limit SLURM kills it before it writes `VAR.csv` and `FUN.csv`, and only the latest trace snapshot remains. Size `maxEvaluations` to the time limit, or stop the run with `POST /api/v1/stop` before it.
- The port is not checked: if it is already taken, the master logs `ERROR starting REST server` and never recovers; cancel and resubmit.
- To add workers to a running master, submit another array with the same `--dependency=after:<master job>` and append its id to `worker_job`. A worker that is cancelled or preempted while evaluating posts no `/error`; the watchdog requeues its task once its heartbeats have stopped for 45 s, without counting it as a failure.
- Follow the run with `cat status.json` (written every 30 s) and, for the front itself, `python /path/to/jdisrest/python/tools/watch_front.py traces` on the login node ([`python/README.md`](../python/README.md)).
- Measure the memory and time of one evaluation (for instance with `/usr/bin/time -v`) to set `--mem` and `--time` of the workers. Each worker logs two lines per task; with thousands of workers, `--log-level WARNING` keeps the logs small.
- Workers on Windows, for instance in an HTCondor pool, need `PYTHONUTF8=1`: the worker's log lines contain non-ASCII characters (such as `→`), which a redirected cp1252 stderr writes as escapes or garbled text.

---

## 7. Internals

### 7.1 Class hierarchy

```
AbstractMaster<T, R>
│  pendingTaskQueue    — pending tasks (BlockingQueue)
│  completedTaskQueue  — evaluated tasks (BlockingQueue)
│  inFlightTasks       — assigned tasks (ConcurrentHashMap<taskId, task>)
│  workerRegistry      — known workers and their last heartbeat
│  failure limit, stop — failInFlightTask, setMaxTaskFailures, requestStop (TaskFailureTracker, StopRequest; no Spring)
│
├── GenerationalMaster<T, R>           — batch algorithms (dispatch a generation, wait for all)
│      implements GenerationalAlgorithm<T, R>   (interface with the generational loop)
│
└── SteadyStateMaster<T, R>            — on-demand algorithms (a new task whenever a worker asks)
       implements SteadyStateAlgorithm<T, R>    (interface with the steady-state loop)
    └── SteadyStateEvolutionaryAlgorithm<S>
        ├── algorithms.steadystate.NSGAII<S>
        ├── algorithms.steadystate.MOEAD<S>    — delegates weights and aggregation to MOEADWeights and MOEADAggregation (no Spring)
        ├── algorithms.steadystate.SMSEMOA<S>
        └── algorithms.steadystate.PAES<S>     — delegates its rules to PAESState (no Spring)
```

The protocol timing constants (heartbeat 15 s, worker timeout 45 s, watchdog cycle 30 s, long-poll 30 s) are centralized in `es.unex.jdisrest.util.Timings`. If any of them changes, the equivalent worker-side constants must be updated too (`Worker` in Python, `RestWorker` in Java).

`MasterFacade` (a static class) is the bridge between the Spring beans (controllers, watchdog) and the active master instance. Beans never import `SteadyStateMaster` or `GenerationalMaster` directly; they call `MasterFacade.claimNextTask()`, `submitResult()`, `failInFlightTask()`, `requestStop()`, etc., which delegate to the active instance. It also holds the `ConfigurationHandler` of `/api/v1/config` (`setConfigurationHandler`) and the evaluation budget of the status report (`init`, `setMaxEvaluations`).

### 7.2 Life cycle of a task

```
Master                                  REST API                  Worker
──────                                  ────────                  ──────
createInitialTasks()                    [algorithm thread]
  → pendingTaskQueue.add(task) for each

                                  GET /tasks/next
                            ←──────────────────────── (long-poll 30s)

TaskController.getNextTask():           [REST thread]
  → 410 if finished or stopped
claimNextTask(workerId)
  ← pendingTaskQueue.poll()
  → if empty and still running: createNewTask()
    (two children: one returned, the spare queued)
  → if still nothing: pendingTaskQueue.poll(30s)
  → inFlightTasks.put(taskId, task)
  → workerRegistry[workerId].currentTaskId = taskId

                                  200 OK {taskId, variables}
                            ──────────────────────────────►

                                                          variables = task.variables
                                                          f = evaluate(variables)

                                  POST /tasks/{id}/result
                            ←────────────────────────────

TaskController.submitResult():          [REST thread]
  ← inFlightTasks.get(taskId)           (404 if no longer in flight)
  → validates the payload; if invalid: 422 and failInFlightTask(taskId):
      requeued, or discarded after the failure limit
  → writes variables (optional), objectives and constraints into solution
  → MasterFacade.submitResult()
     → inFlightTasks.remove(taskId)
     → completedTaskQueue.add(task)     (404 instead once a stop is requested)

run() loop                              [algorithm thread]
  ← waitForComputedTask()               (completedTaskQueue; null after a stop)
  → processComputedTask(task): archive, population, ranking + crowding
  → updateProgress(): attributes for the stopping criterion, traces
  → [repeat until the stopping criterion is met or a stop is requested]
```

Task creation is demand-driven: in a steady-state master the REST thread that serves `GET /tasks/next` creates the task when the queue is empty, and `processComputedTask` never creates one. A `POST /error`, a rejected result and a task that cannot be serialized all go through `AbstractMaster.failInFlightTask(taskId)`, which requeues the task or, at the failure limit, discards it (section 2.3); the deprecated `requeueInFlightTask(taskId)` of earlier versions delegates to it and counts as a failure too. The framework calls only `failInFlightTask`, so a subclass written for 1.1 that overrode `requeueInFlightTask` must move that code to `failInFlightTask` or `onTaskDiscarded`. The watchdog moves the tasks of silent workers back to the queue without counting a failure (section 7.4). A `GenerationalMaster` queues a whole generation at once (`submitTasks`), and its `claimNextTask` only polls the queue.

### 7.3 Server-side long-polling

`TaskController.getNextTask()` uses `Mono.fromCallable()` on the `boundedElastic` scheduler so it does not block the Netty event loop:

```java
return Mono.fromCallable(() -> {
               if (MasterFacade.isFinished()) {               // finished or stopped
                   return ResponseEntity.status(410).build();
               }
               var task = MasterFacade.claimNextTask(workerId, 30);
               return task == null
                   ? ResponseEntity.noContent().build()
                   : ResponseEntity.ok(new TaskPayload(...));
           })
           .subscribeOn(Schedulers.boundedElastic());
```

`SteadyStateMaster.claimNextTask()` first takes a queued task or creates one (section 7.5), so a steady-state worker practically never waits; the long-poll, `pendingTaskQueue.poll(timeout, SECONDS)` for up to 30 s, is only a fallback for when no task can be created. `GenerationalMaster.claimNextTask()` always long-polls, because only `submitTasks()` fills its queue. If no task arrives in that time, the method returns `null` → the controller responds `204`.

### 7.4 Watchdog

`WatchdogScheduler` runs every 30 s (`Timings.WATCHDOG_INTERVAL_MS`) via `@Scheduled`:

```java
@Scheduled(fixedDelayString = "#{T(es.unex.jdisrest.util.Timings).WATCHDOG_INTERVAL_MS}")
public void checkDeadWorkers() {
    MasterFacade.requeueOrphanTasks(Timings.WORKER_TIMEOUT_S);   // 45 s without heartbeat
}
```

`requeueOrphanTasks()` looks for workers whose `lastSeen` exceeds the threshold, moves their tasks from `inFlightTasks` back to `pendingTaskQueue`, and removes the worker from the registry. If the original worker delivers the task before another worker has claimed it, it receives a `404` and should log a warning without retrying; once another worker holds the task (same id), the late result is accepted and the second worker's result gets the `404`. These requeues do not count towards the failure limit of section 2.3, because a worker can die for reasons unrelated to its task (a preempted SLURM job, an evicted HTCondor job). A task whose evaluation itself kills the worker is therefore retried without limit, one watchdog timeout each time. The master does not check which worker a report comes from, though: a `POST /error` that the original worker sends after another worker has claimed the task counts as a failed evaluation of the new dispatch.

### 7.5 Steady-state synchronization

`SteadyStateMaster.claimNextTask()` uses double-checking with `synchronized` to prevent two concurrent requests from generating duplicate tasks:

```java
if (isStopRequested()) return null;                  // the worker gets 410 on its next request
T task = pendingTaskQueue.poll();
if (task == null && stoppingConditionIsNotMet()) {
    synchronized (taskCreationLock) {
        task = pendingTaskQueue.poll();              // another thread may have queued a spare
        if (task == null && stoppingConditionIsNotMet()) {
            task = createNewTask();
        }
    }
}
if (task == null) {
    task = pendingTaskQueue.poll(timeoutSeconds, TimeUnit.SECONDS);   // long-poll fallback
}
if (task != null && isStopRequested()) {
    task = null;                                     // a stop arrived while the worker waited
}
// a task obtained here goes to inFlightTasks and the worker is marked busy
```

Inside `SteadyStateEvolutionaryAlgorithm`, `population` and `populationSignatures` are only read and changed inside `synchronized (population)`. `createNewTask()`, which runs on REST threads, reads `crossover` and `mutation` under that lock, and `setVariation(crossover, mutation)` replaces them under it, so no task mixes an old operator with a new one; tasks already created, including the spare child in the queue, keep their solutions. `setTermination(termination)` replaces the stopping criterion from the next check on. Once the loop has found the old criterion met, or `run()` has finished, it cannot reopen the run: it changes nothing and returns `false`; the two cannot cross, so a criterion installed with `true` is the one the loop checks next. A criterion that is already met stops task creation at once, but the loop only notices it with the next result, so callers that change an evaluation budget should only accept one above `getEvaluations()`, the results processed so far, which any thread can read (only the algorithm thread writes `evaluations`), and call `requestStop()` if the run reaches it while they install it. The budget of the status report is separate: `MasterFacade.setMaxEvaluations(int)` updates it, so that `progress` and the ETA follow. `AlgorithmReconfiguration` (section 5) does all of this for `POST /api/v1/config`.

### 7.6 Adding an algorithm

1. Extend `SteadyStateEvolutionaryAlgorithm<S>`. It is a complete algorithm already: `createInitialTasks()` asks for `populationSize` solutions through `createInitialSolutions(count)`, `createNewTask()` mates two parents of the selection and mutates both children, filtering exact duplicates, and `processComputedTask()` keeps the best `populationSize` solutions by ranking and crowding.
2. Override `processComputedTask(task)` with the logic that integrates the solution into the population. It runs on the algorithm thread only, which is the only writer of `evaluations`. Change `population` inside `synchronized (population)`, because REST threads create tasks from it at the same time, and keep `populationSignatures` in step: `createNewTask()` uses it to reject duplicates, so add the key of every solution that enters and remove that of every solution that leaves (`solutionKey(s)`), or call `rebuildPopulationSignatures()` inside the lock.
3. Optionally override `createNewTask()` to customize the generation strategy. It runs on REST threads: read `crossover` and `mutation` inside `synchronized (population)`, which `setVariation` also takes. It may queue a spare offspring in `pendingTaskQueue`.
4. Optionally override `createInitialTasks()` for an algorithm that does not start from `populationSize` solutions; build them with `createInitialSolutions(count)` to keep the warm start and its copy into the traces (PAES asks for 1). An override that creates its solutions otherwise (MOEA/D does) has no warm start.
5. If the algorithm keeps state about tasks in flight (for example MOEA/D's map from task to subproblem), override `onTaskDiscarded(task)` to release it: a task discarded after the failure limit never reaches `processComputedTask`. It runs on the REST thread that handled the failure, concurrently with the algorithm thread, so it must be thread-safe and fast; it need not call `super`, because the framework's own accounting does not depend on it. `MOEAD` is the in-tree example.
6. The stop needs no code. `stoppingConditionIsNotMet()` already includes the stop and the end of `run()`; an override should combine its own condition with `super.stoppingConditionIsNotMet()`. A subclass that overrides `waitForComputedTask()` must return `null` once `isStopRequested()` is `true`, because no result arrives after a stop and a plain `take()` would hang `run()`; the default `run()` loop ends on a `null`. An override of `run()` must call `super.run()` or end its loop on a stop as well.
7. `setVariation`, `setTermination` and `getEvaluations` let a `ConfigurationHandler` change the operators and the budget of the running algorithm. Settings of your own need a method that checks every argument before it changes anything, under the population lock, as `PAES.reconfigure` and `MOEAD.reconfigure` do.
8. Keep the rules of the algorithm in a class without Spring (package-private unless users need it), as `PAESState`, `MOEADWeights`, `MOEADAggregation`, `TaskFailureTracker` and `StopRequest` do: the constructor of every master starts the REST server, so the master itself cannot be built in a unit test. Check the constructor arguments before calling `super(...)`, which starts the server, so that a wrong argument fails at once instead of leaving a live server behind (Java 25 allows statements before `super`).

```java
public class MyAlgo<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    public MyAlgo(String host, int port, Problem<S> problem,
                  int populationSize, CrossoverOperator<S> crossover,
                  MutationOperator<S> mutation, Termination termination,
                  String tracesFolder) {
        super(host, port, problem, populationSize, crossover, mutation,
              new NaryTournamentSelection<>(),
              new DominanceWithConstraintsComparator<>(),
              termination, tracesFolder);
    }

    /** Fills the population, then replaces the first member the offspring dominates. */
    @Override
    @SuppressWarnings("unchecked")
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        S offspring = (S) task.getContents().copy();
        archive.add((S) offspring.copy());       // its own copy: the archive writes attributes outside the lock
        synchronized (population) {
            if (solutionInThePopulation(offspring)) {
                return;                          // an exact copy of a member
            }
            if (population.size() < populationSize) {
                population.add(offspring);
                populationSignatures.add(solutionKey(offspring));
                return;
            }
            for (int i = 0; i < population.size(); i++) {
                if (dominanceComparator.compare(offspring, population.get(i)) < 0) {
                    populationSignatures.remove(solutionKey(population.get(i)));
                    population.set(i, offspring);
                    populationSignatures.add(solutionKey(offspring));
                    break;
                }
            }
        }
    }
}
```

**Generational algorithms.** jdisrest ships no concrete generational algorithm; `GenerationalMaster` is the skeleton for one. Its default `run()` submits the initial tasks, waits for them, and then calls `evolution(population)` until the run is over, always with the same list, so `evolution` must update that list in place, submit the next generation with `submitTasks` and wait for it with `waitForEvaluatedTasks`. Call `submitTasks` exactly once per generation, with exactly `populationSize` tasks: the call resets the count of tasks discarded in the generation, and the wait ends when `populationSize` tasks have been evaluated or discarded. `waitForEvaluatedTasks()` can therefore return fewer than `populationSize` tasks, in completion order: one less for each task discarded after the failure limit, and only those already received when a stop is requested (it notices the stop within about a second). Do not index the results by position. The stop needs no code here either: `isFinished()` and the default `run()` loop check it.

### 7.7 Variable encodings and the wire

`es.unex.jdisrest.util.SolutionVariables` is the only place where a jMetal solution becomes a flat numeric vector or is rebuilt from one. `TaskController`, `RestWorker`, `PythonSolutionListEvaluator`, the duplicate filter (`solutionKey()`) and the composite trace writer all go through it.

- `flatten(solution)` copies the variables into a new `List<Number>`, concatenating composite segments in declaration order and keeping `Integer`/`Double` element types, so Jackson emits `5` for integer variables and `5.0` for real ones.
- `apply(solution, values)` writes a vector back. Each value is converted to the type of the destination variable (`intValue()` / `doubleValue()`), never trusting the type Jackson chose from the JSON text (`5` → `Integer`, `5.0` → `Double`, big values → `Long`). It validates first and writes afterwards, so a rejected vector leaves the solution untouched: wrong length, `null`, non-finite, non-integral for an integer variable (tolerance `1e-9`) and `int` overflow all raise `IllegalArgumentException` with the offending index.
- `wireEncoding(solution)` / `segmentEncodings(solution)` produce the `encoding` and `segmentEncodings` fields of section 2.2. The controller omits both for all-integer solutions.
- Supported types: `IntegerSolution`, `DoubleSolution`, `CompositeSolution` of those. Any other solution is accepted only if its variables are `Integer` or `Double` at runtime (integer permutations); otherwise `IllegalArgumentException` names the class. `SteadyStateEvolutionaryAlgorithm.run()` performs this check on one `problem.createSolution()` before dispatching anything, so an unsupported encoding fails at start-up rather than once per task.

**Duplicate filter.** `SteadyStateEvolutionaryAlgorithm.solutionKey()` returns `flatten(solution)` and `populationSignatures` compares keys element by element. With integer encodings this rejects every duplicate offspring. With real encodings two independently generated vectors are practically never bit-identical, so the filter only catches exact clones — offspring on which neither crossover nor mutation acted, which are the duplicates real-coded evolution actually produces. The retry loop in `createNewTask()` therefore exits on the first iteration for most real-coded offspring and `MAX_DUPLICATE_RETRIES` is never reached. Nothing else changes; a tolerance-based comparison was deliberately not added because it would need a per-variable scale.

**Traces.** `CompositeSolutionListOutput` writes any number of segments of any supported encoding, one VAR row per solution: `v0 v1 ... vN-1,[obj...],[con...]`. Flat solutions still go through jMetal's `SolutionListOutput`.

---

## 8. Monitoring and control

Once the master is up, two endpoints report progress (`GET /api/v1/status` and `GET /api/v1/workers/status`), one reads and changes the configuration (`GET` and `POST /api/v1/config`) and one stops the run (`POST /api/v1/stop`). Like the rest of the protocol they have no authentication: anyone who can reach the master's port can change or stop the run.

### `GET /api/v1/status`

Lightweight snapshot with global progress. Meant for monitoring scripts and external dashboards.

```json
{
  "running": true,
  "finished": false,
  "evaluations": 12500,
  "maxEvaluations": 25000,
  "progress": 0.5,
  "elapsedSeconds": 3621,
  "estimatedSecondsRemaining": 3621,
  "aliveWorkers": 42,
  "inFlightTasks": 42,
  "pendingTasks": 0,
  "discardedTasks": 0
}
```

`evaluations` counts the results the master has accepted; `finished` is `true` once the stopping criterion is met or a stop has been requested. `discardedTasks` counts the tasks discarded after reaching the failure limit (section 2.3): any value above zero means some solutions could not be evaluated, and the master log names them (`[task-42] Failed evaluation 3 of 3 — discarded; its variables were [...]`). A `discardedTasks` that keeps growing while `evaluations` stays still means that every evaluation fails, for instance because the workers evaluate another problem. Fields added in later versions go last; Java clients that bind this JSON to a class with the ten fields of jdisrest 1.1 must ignore unknown properties (Jackson 2 fails on them by default); in the other direction, `StatusSnapshot` reads the JSON of a 1.1 master, which has no `discardedTasks`, as `0`.

### `GET /api/v1/workers/status`

Per-worker details — useful for debugging workers that get stuck:

```json
{
  "aliveWorkers": 42,
  "totalEvaluations": 12500,
  "totalDispatched": 12750,
  "pendingTasks": 0,
  "inFlightTasks": 42,
  "queuedResults": 8,
  "workers": {
    "worker-py-01": { "address": "node01", "lastSeen": "...", "currentTaskId": 1234 },
    "worker-py-02": { "address": "node02", "lastSeen": "...", "currentTaskId": -1 }
  }
}
```

`currentTaskId = -1` indicates an idle worker (waiting for a task or between evaluations).

### `GET` and `POST /api/v1/config`

Reads and changes the configuration of the running algorithm. Every master maps the endpoints, but they only work once the program has registered a `ConfigurationHandler` with `MasterFacade.setConfigurationHandler`, because only it knows how a configuration maps onto its algorithm. `ConfiguredMaster` registers `config.AlgorithmReconfiguration`, the handler for the configuration files of section 5 (real-coded NSGA-II, PAES and MOEA/D):

```bash
url=http://10.0.0.1:8080
curl -s "$url/api/v1/config" > new.properties    # the configuration in use
# edit new.properties, for instance maxEvaluations or mutation.probability
curl -X POST --data-binary @new.properties "$url/api/v1/config"
```

`GET` returns the configuration in use as the text of a properties file (`text/plain`), with one active line per key (section 5), so editing a value where it appears and sending the text back changes that key. After a change it returns the text applied, comments included, with the earlier lines of a key it sets twice commented out. `POST` takes a complete properties file as the body, read as UTF-8 whatever its content type (a leading byte order mark is fine, bytes that are not UTF-8 are rejected), and replaces the configuration: a key left out takes its default value, as at the start (leaving out `tracesFolder` asks for no traces, which a run cannot change to). The text is read for the run's problem (`k/n` over its variables, the MOEA/D lattice against its objectives). The new operators and settings apply to the tasks created from then on, and MOEA/D's replacement settings to the results that arrive. Section 5 lists what can change and what is fixed during a run.

Every change installs a `TerminationByEvaluations` with the new `maxEvaluations`, which must be greater than the evaluations already done, and passes it to `MasterFacade.setMaxEvaluations`, so the progress and the ETA of `/api/v1/status` follow it. Registering `AlgorithmReconfiguration` therefore implies an evaluation budget, whatever stopping criterion the algorithm was built with. Each change is recorded in the traces folder as `<name>_<evaluations>.properties` with a line in `configuration.log` (section 5), and logged as `Configuration changed after <evaluations> evaluations: <summary>`. The run goes on while the text is read, so its state is checked again just before the change; a new `maxEvaluations` that the run reaches while it is being installed ends the run as the budget would.

Responses of `POST`; every one but `200` has the body `{"error": "<reason>"}`:

| Status | When |
|---|---|
| `200 OK` | applied; the body is `{"applied": "<summary>"}` |
| `409 Conflict` | the run has already finished or been stopped, or has reached its budget, also when that happens while the change is being applied; nothing changes and nothing is recorded |
| `413 Content Too Large` | the body is longer than 1 MiB |
| `422 Unprocessable Content` | the configuration is wrong, changes a setting fixed during the run (`populationSize cannot change during a run (100 to 120)`), sets a `maxEvaluations` not above the evaluations already done, cannot be applied, or the body is not UTF-8; nothing changes |
| `500 Internal Server Error` | the handler failed in another way; the failure is logged |
| `501 Not Implemented` | the master registered no handler (`this master cannot change its configuration`); the body is not read |
| `503 Service Unavailable` | the master has not started its run yet (`the master is not ready yet`); try again shortly |

`GET` answers `501` with the text `This master does not expose its configuration` when no handler is registered. The REST server starts in the algorithm's constructor, so both endpoints answer `501` until the program registers its handler; `ConfiguredMaster` does so just before `run()`.

A program that builds its algorithm from a configuration file by other means wires the same pieces before `run()`:

```java
var problem = new ZDT1();
Path file = Path.of("run.properties");
List<String> overrides = List.of();
AlgorithmConfig config = AlgorithmConfig.load(file, overrides, problem);
ConfigHistory history = ConfigHistory.of(file, overrides, config);         // throws IOException

var algorithm = ConfiguredMaster.createAlgorithm("10.0.0.1", 8080, problem, config);   // starts the REST server
MasterFacade.init(config.maxEvaluations(), 30);
history.recordStart(config);                                               // the copy and configuration.log
MasterFacade.setConfigurationHandler(new AlgorithmReconfiguration(algorithm, config, problem, history));
algorithm.run();
// then write the result and call System.exit, as in section 5
```

A handler of your own implements `ConfigurationHandler`: `current()` returns the text `GET` serves, and `apply(text)` validates and applies a new configuration (it gets `""` for an empty body) and returns a one-line summary. Both run on REST worker threads, off the event loop, concurrently with the algorithm and with each other, so they must be thread-safe. `apply` reports a wrong configuration with an `IllegalArgumentException` (`422`) and a run that cannot take a change any more with an `IllegalStateException` (`409`), and must change nothing then; any other exception is a `500`. `MasterFacade.setConfigurationHandler(null)` disables the endpoints again.

### `POST /api/v1/stop`

Finishes the run now, as if the stopping criterion had been met, so that it ends in order instead of being killed:

```bash
curl -X POST http://10.0.0.1:8080/api/v1/stop
```

- No more tasks are handed out: workers get `410 Gone` on their next request and shut down. A request already being served when the stop lands may still receive one task. A worker still evaluating when the master process has exited (`ConfiguredMaster` exits as soon as it has written the result, at a stop as at a normal end) finds no master instead, and stops after 5 failed requests 10 s apart or 3 failed heartbeats, within about a minute.
- The results of the evaluations still in flight are refused with `404`, which both bundled workers log and move past, and are not counted in `evaluations`. Results accepted before the stop that the algorithm had not processed yet are counted, then dropped.
- `run()` returns with the current result, exactly as at a normal end: a steady-state master stops waiting within 200 ms, a generational one within about a second, with the part of the generation it has. The caller writes the result: `ConfiguredMaster` writes `VAR.csv` and `FUN.csv` and exits with status 0; a program of your own does it as after a normal finish, for instance with `TraceWriter.write(algo.getResult(), "VAR.csv", "FUN.csv", ",")`. A stop before the first result leaves the result archive empty, and `getResult()` can then throw (jMetal's archives reject an empty list), so check `algo.getEvaluations() > 0` first, as `ConfiguredMaster` does. No trace snapshot is taken at the stop: the traces folder ends with the last regular snapshot, which is older than the result.

The response is `202 Accepted` with the `/api/v1/status` snapshot, already with `"finished": true`, or `503 Service Unavailable` if no master is running. Repeated requests are harmless; the master logs the first one:

```
Stop requested — finishing with the current result and discarding the 3 evaluations in flight
```

The stop does not end the process: the REST server and the `status.json` writer keep running until the program exits, which is why a `main` must end with `System.exit`. After a stop, `pendingTasks` can stay above zero, since tasks still queued (or requeued by a late `/error` or the watchdog) are never handed out, and `POST /api/v1/config` answers `409`.

The endpoint calls `AbstractMaster.requestStop()`. `isFinished()` includes the request, so the workers are sent away whatever the algorithm's stopping condition says; `SteadyStateEvolutionaryAlgorithm`, the base of the bundled algorithms, includes it in `stoppingConditionIsNotMet()`, and the default `run()` loops of both `SteadyStateAlgorithm` and `GenerationalAlgorithm` end on it. Custom algorithms need no code of their own unless they override the waits (section 7.6).

### `status.json` file

If `MasterFacade.init(maxEvals, statusFileIntervalSec)` is called with an interval > 0, the master writes the same payload as `/api/v1/status` to `status.json` under `-Djdisrest.dataPath`. Useful when the worker nodes have access to a shared filesystem but not to the compute node's network (the typical case on HPC clusters with login nodes). `ConfiguredMaster` writes it every 30 s. To follow the Pareto front of a run rather than its counters, `python/tools/watch_front.py` reads the traces folder (see [`python/README.md`](../python/README.md)).

The first write happens at once, inside `init`. When `init` comes right before `run()`, as it should, the run has not started yet, so that write usually fails for the same reason as the early `500` of section 2.2, and the master logs `Could not write status.json (further failures will be silent): The parameter 'object' is null`; the writes from the next interval on succeed once the run has started. Only the first failure is logged, so a `dataPath` that cannot be written goes unreported after that warning: check that `status.json` appears.

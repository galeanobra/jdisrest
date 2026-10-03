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
   - [Local mode](#local-mode)
   - [Extra operators](#extra-operators)
   - [Configuration files and the launcher](#configuration-files-and-the-launcher)
6. [SLURM deployment patterns](#6-slurm-deployment-patterns)
7. [Internals](#7-internals)
8. [Monitoring and control](#8-monitoring-and-control)
   - [`GET /api/v1/status`](#get-apiv1status)
   - [`GET /api/v1/workers/status`](#get-apiv1workersstatus)
   - [`GET` and `POST /api/v1/config`](#get-and-post-apiv1config)
   - [`POST /api/v1/stop`](#post-apiv1stop)
   - [Startup and shutdown](#startup-and-shutdown)
   - [`status.json` file](#statusjson-file)
9. [Changes in 1.2](#9-changes-in-12)
10. [Changes in 1.2.1](#10-changes-in-121)

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

1. The algorithm's constructor starts the REST server (Spring Boot), returns once it accepts connections, and writes `.master-endpoint`; `run()` then builds the initial tasks. Until they exist the master hands out no task: a worker that asks gets `204` and asks again (section 2.2).
2. Each worker starts, sends `POST /heartbeat` to register itself, and enters its loop.
3. The worker requests a task: `GET /tasks/next` with long-polling of up to 30 s. A steady-state master creates the task on demand when its queue is empty.
4. The master responds with a `taskId` + the decision vector (integers, reals, or both for composite problems).
5. The worker evaluates the objective function (this can take minutes or hours).
6. The worker returns the result: `POST /tasks/{id}/result` with objectives and constraints. If the evaluation fails, it reports it with `POST /tasks/{id}/error` instead; the master then requeues the task, or discards it once it has failed `AbstractMaster.DEFAULT_MAX_TASK_FAILURES` (3) times (section 2.3).
7. The master integrates the result into the population.
8. When the stopping criterion is met, or someone calls `POST /api/v1/stop` (section 8), the master responds `410 Gone` to the workers and `run()` returns. The program writes the result and calls `shutdown()`, which closes the REST server (section 5).

### Key concepts

| Concept | Description |
|---|---|
| **Task** | A `(taskId, variables[])` pair. Created by the master, evaluated by a worker. |
| **Long-polling** | The worker waits up to 30 s for a task to become available, without saturating the network with active polling. |
| **Heartbeat** | The worker signals every 15 s that it is still alive, on a thread separate from the evaluation one. |
| **Watchdog** | The master checks every 30 s which workers have not sent a recent heartbeat and requeues their tasks. |
| **InFlight** | Tasks assigned but not yet returned. If the worker dies, they are requeued. A worker holds one task at a time, so each one needs its own `workerId` (section 2.1). |
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

Response: 200 OK  (no body); 400 without workerId
```

Send every 15 s on a separate thread. If the master hears nothing from a worker (heartbeat, task request or result) within 45 s, it declares the worker dead and requeues its task (section 7.4). Both bundled workers count a heartbeat that gets a network error or an answer other than `2xx` as failed, retry it after 5 s, and stop after 3 failures in a row (section 4).

The `workerId` names the worker in the master's log and in `GET /api/v1/workers/status`, and it must be **unique among the workers of a run**, one per concurrent evaluation slot. The master tracks one task in flight per id: when an id asks for a new task while its previous one is still in flight, the master takes it as a lost result and requeues that task (section 2.2). Two processes, or two threads of one process, that share an id therefore keep taking each other's tasks back, and most of their results are refused with `404`. The bundled workers generate a random id (`worker-java-` or `worker-py-` and eight hex characters) unless they are given one; an id built from a SLURM array job and task id is unique too.

### 2.2 `GET /api/v1/tasks/next`

Requests the next task. The master long-polls for up to 30 s.

```
GET http://<master>:<port>/api/v1/tasks/next?workerId=<unique-id>

Possible responses:
  200 OK   → a task is available
  204 No Content → no task right now: the master is not ready yet (below), or no task
                   arrived within the long-poll; ask again a few seconds later (also a
                   request being served when the run is stopped or ends: the next one gets 410)
  410 Gone → algorithm finished (or stopped with POST /api/v1/stop), the worker must stop
  500 Internal Server Error → the master could not build the payload of the task it took
                              for this worker (an unsupported solution type, or a variable
                              that is null, NaN or infinite); the task counts as a failed
                              evaluation (section 2.3), and the worker may ask again
```

The REST server starts, and `.master-endpoint` is written, in the algorithm's constructor, but the algorithm can only hand out tasks once `run()` has built its initial ones (`isReady()`, section 7.5). Until then `GET /tasks/next` answers `204` at once, without creating a task, and `GET /api/v1/status` reports the run as running (section 8). Both bundled workers wait 5 s after a `204`. (jdisrest 1.1 answered `500` in that window.)

A single `500` concerns one task, which the master has already failed and requeued (or discarded), not the master: ask again. A JSON number cannot carry `NaN` or an infinity, so a solution with such a variable is failed here instead of travelling as a string that no worker can evaluate.

**One task per worker.** A worker holds one task at a time. If it asks for a task while the one it was last given is still in flight (its result or error never arrived, because the request was lost or timed out, or the worker restarted under the same id), the master requeues that task, without counting a failed evaluation, and logs `[task-42] Requeued — worker w-07 asked for a new task without reporting this one (its result or error was lost)`. A late result for it then gets `404` (section 2.3), unless another worker holds the task by then.

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

The client's read timeout must be **greater than the 30 s long-poll** (both bundled workers wait 40 s). A poll that times out on the client while the master hands it a task leaves that task in flight until the worker's next request requeues it.

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
  404 Not Found              → the master no longer expects this result: the task was requeued (the
                               watchdog found the worker silent, or the worker asked for a new task
                               first, section 2.2), another report for it was handled first, or the
                               run was stopped (POST /api/v1/stop) or, with the bundled algorithms,
                               has ended on its stopping criterion (section 8); the result is not
                               counted, valid or not (below)
  422 Unprocessable Content  → the result is invalid (see below); the task has been requeued (or discarded);
                               after a stop or the end of the run, 404 instead (below)
  400 Bad Request            → the body is not valid JSON (e.g. a bare NaN); the task has been requeued (or discarded),
                               except after a stop or the end of the run (below)
  413 Content Too Large      → the body is larger than the server's limit (16 MiB by default, section 5); same
  415 Unsupported Media Type → the Content-Type is not application/json; same
```

- `workerId`: the reporting worker. A result without it is accepted too.
- `objectives`: list of **doubles**, exactly one per objective of the problem. jMetal minimizes; to maximize, negate.
- `constraints`: list of doubles, exactly one per constraint of the problem. jMetal convention: `>= 0` satisfied, `< 0` violated. Empty list (or omitted) if the problem has no constraints.
- `evaluationTimeMs`: optional, for statistics.
- `variables`: **optional**. If the evaluator "repairs" the solution (Lamarckian search), it can return the repaired vector here and the master will overwrite the original solution before archiving the result. Omit it (or send an empty list, which means the same) if the variables are not modified. Same layout as the vector received. The JSON number type does not matter: the master converts each value to the type of the destination variable, so `2` is accepted for a real variable and `2.0` for an integer one. `2.7` for an integer variable is rejected, and so is a value outside the bounds of its variable.

The master validates the whole body before writing anything into the solution. It rejects with `422`: a wrong number of objectives or constraints, any `null`, `NaN`, `Infinity` or `-Infinity`, a `variables` vector whose length differs from the solution's, a non-integral value for an integer variable, and a value outside the bounds of its variable. Bounds are those of an `IntegerSolution` or `DoubleSolution`, or of such segments of a composite (other solution types declare none); they are inclusive and checked without tolerance, so a repair must clip exactly to them: a value computed as `lb + (ub - lb) * x` can land one ulp outside and be rejected. The body of the `422` (and of the `400`, `413` and `415`) is `{"taskId": 42, "reason": "objectives[1] is not finite: NaN"}`, or for instance `"variables[2] = 3.0 is outside the bounds [-2.5, 2.5] of its variable"`. A valid result is written into the solution only after the master has taken the task out of flight, so two reports of the same task never write into it at once.

A rejected result is treated exactly like `POST /error`: it counts as a failed evaluation, the task goes back to the pending queue and the same solution will be evaluated again by whichever worker claims it. As with `POST /error` (section 2.4), a `422` sent by a worker that no longer holds the task (another worker has it now) is answered but changes nothing. The other requests Spring rejects before the handler runs, such as a body that is not JSON (`400`), a wrong `Content-Type` (`415`) or a body above the size limit (`413`), count as a failed evaluation whoever sent them, because the master cannot read the `workerId` of a body it has not decoded.

**After a stop or the end of the run.** Once a stop has been requested or, with the bundled algorithms, the algorithm has ended its run, the master needs no more results (`AbstractMaster.needsNoMoreResults()`), and no failure counts: the task of a rejected result leaves flight (unless another worker holds it, as above) without being requeued or discarded, as that of a result refused with `404`. A result that fails validation then gets `404` like any late result, not `422`; a `400`, `413` or `415` keeps its status, and counts nothing either.

**Failure limit.** After `AbstractMaster.DEFAULT_MAX_TASK_FAILURES` (3) failed evaluations, or the number set with `setMaxTaskFailures(int)` on the algorithm, the master discards the task instead of requeueing it: the task is never dispatched again, it is counted in `discardedTasks` of `GET /api/v1/status` (section 8), and the master logs it at ERROR level with its decision vector (the first 50 values of a longer one):

```
[task-42] Failed evaluation 1 of 3 — requeued
[task-42] Failed evaluation 3 of 3 — discarded; its variables were [0.25, -1.5, 3.0]
```

A failed evaluation is a `POST /error`, a `422`, `400`, `413` or `415` answer to a result, or a task the master cannot serialize for `GET /tasks/next` (its `500`). Tasks the watchdog requeues because their worker went silent do not count (section 7.4), nor do tasks requeued because their worker asked for another one (section 2.2), so a task whose evaluation kills its worker (out of memory, a crash, a job memory limit) is still retried without limit. `setMaxTaskFailures` can be called at any time and applies from the next failure on, also to tasks that have already failed; a large value restores the unbounded retries of jdisrest 1.1, which had no limit. A steady-state algorithm simply loses a discarded offspring; a generational one receives a generation with fewer than `populationSize` results (section 7.6). If every result fails for the same reason (for example, workers evaluating another problem, with another number of objectives, get `422` on every result), every task ends up discarded and a run with an evaluation budget never finishes: watch `discardedTasks`.

An evaluator that produces `NaN` for some inputs must still map it to a finite penalty itself — the master has no way to choose one, and otherwise each such solution costs `DEFAULT_MAX_TASK_FAILURES` wasted evaluations and is lost to the search. Note that Python's `json.dumps` emits `NaN` as a bare token (invalid JSON, answered with `400`) and MATLAB's `jsonencode` turns `NaN` into `null` (answered with `422`); the bundled Python client validates finiteness before sending and reports the problem through `/error` instead. Its command-line worker (section 4.2) can apply the penalty: `--non-finite-penalty X`, or `FunctionEvaluator(function, non_finite_penalty=X)` from Python, gives `X` to every objective of a result whose objectives are not all finite, and logs a warning. It is opt-in and has no default, because no value is safe for every problem: choose one worse than any valid objective.

> The 404 is not a critical error: the task was requeued, or the run is over. The worker should log a warning and continue. The 400, 413, 415 and 422 are not network errors either: log the reason and move on to the next task. Both bundled workers treat a `2xx`, `404`, `400`, `413`, `415` or `422` answer to `/result` or `/error` as the answer of a live master, and count anything else (a `5xx`, another `4xx`, a network error) towards giving the master up as lost (section 4).

### 2.4 `POST /api/v1/tasks/{taskId}/error`

Reports an evaluation failure (the worker hit an exception while evaluating). Makes the master requeue the task immediately instead of waiting for the watchdog, or discard it once it has reached the failure limit (section 2.3). After a stop or, with the bundled algorithms, once the algorithm has ended its run, the report counts nothing: the task leaves flight, and is neither requeued nor discarded (section 2.3).

```
POST http://<master>:<port>/api/v1/tasks/42/error
Content-Type: application/json

{ "workerId": "worker-py-01", "errorMessage": "ZeroDivisionError: ..." }

Response: 200 OK  (no body), also when the task is no longer in flight, or the report is
                  ignored or counts nothing
```

Only the worker that holds the task can fail it. A report from a worker that no longer holds it (the watchdog took the task away, or the worker asked for another one, and another worker has it now) is logged as `[task-42] Failure report from w-07 ignored — the task is now held by w-12` and changes nothing, so it cannot take the task away from its new holder or count against its evaluation. A report without `workerId`, or for a task that no registered worker holds, counts.

`errorMessage` is free text for the master's log. The bundled workers send the exception type with its message: the Python client as `Type: message` (`ZeroDivisionError: division by zero`), `RestWorker` as Java's `toString()` of the exception (`java.lang.ArithmeticException: / by zero`). For a task or a result they cannot use they send the reason, such as `objectives[0] is not finite: NaN` (with the Python client, the reason of a `ValueError`, so `ValueError: objectives[0] is not finite: nan`).

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

The workers receive its variables as JSON floats and the task payload carries `"encoding": "double"` (section 2.2). Operators must match the encoding: `SBXCrossover` + `PolynomialMutation` (or the variants in `es.unex.jdisrest.operator`) for `DoubleSolution`, `IntegerSBXCrossover` + `IntegerPolynomialMutation` (or `es.unex.jdisrest.operator.IntegerSimpleRandomMutation`) for `IntegerSolution`. jMetal 7.1 has an `IntegerSimpleRandomMutation` too, with the same constructors and a biased draw, so check the import (section 5, [Extra operators](#extra-operators)).

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
        // Never called in distributed mode: the master writes the
        // objectives a worker returns into the solution.
        throw new UnsupportedOperationException(
            "Distributed evaluation — call from a worker");
    }
}
```

### Warm-start

The algorithm invokes `createInitialPopulationFromFile(n)` instead of jMetal's random initialization when **two** conditions are met: `iVAR.csv` exists in the working directory **and** the problem implements `es.unex.jdisrest.distributed.WarmStartCapable<S>`. If `iVAR.csv` exists but the problem does not implement the interface, a warning is logged and random initialization is used. Typical file convention: one solution per line, comma-separated variables; the implementation should top up with random solutions until the population is full.

The warm start works in the local NSGA-II and in every steady-state algorithm, which build their initial solutions with `createInitialSolutions(count)` of `SteadyStateEvolutionaryAlgorithm`: NSGA-II and SMS-EMOA, which keep its `createInitialTasks()` and ask for `populationSize` solutions; MOEA/D, which asks for `populationSize` and gives solution `i` to subproblem `i` (`i` modulo `populationSize` for a longer list), replacing a solution that duplicates an earlier one by a random one; and PAES, which asks for a single one and starts from the first solution it gets (from a random one if the list is empty). `n` is therefore not always the population size. If the problem returns another number of solutions, the master logs `Initial population from iVAR.csv has size 80 instead of the 100 requested — starting from it as it is` and uses the list as it is (the steady-state algorithms create the missing solutions later as ordinary tasks), except the local NSGA-II, which tops a short list up with random solutions and drops the surplus of a long one, so that its evaluation count and trace names stay exact; if it returns `null`, the run starts from random solutions.

When a run uses the warm start and has a traces folder, `iVAR.csv` is copied into it, replacing an older copy, so that the traces record the file the run was given. The copy is the file the problem was asked to read, not necessarily the population it built: `createInitialPopulationFromFile` takes no path and decides by itself how many rows to use, so it must read `WarmStart.FILE` (`iVAR.csv` in the working directory), the file whose presence enabled the warm start and the one copied. A failed copy is logged as a warning and the run goes on. `es.unex.jdisrest.distributed.WarmStart` holds this logic for both the distributed algorithms and the local NSGA-II.

### Composite problems

For problems with heterogeneous segments (e.g. three independent sets of variables with different bounds, or an integer segment next to a real one), implement `Problem<CompositeSolution>` directly. Segments may be `IntegerSolution`, `DoubleSolution` or any mix; the master flattens them in declaration order and tells the worker where each segment starts (`segmentSizes`) and how it is encoded (`segmentEncodings`, section 2.2). jMetal provides `CompositeCrossover` and `CompositeMutation`, but `CompositeCrossover` can return children that share segments with their parents, which a mutation applied in place then corrupts. The defensive wrapper `es.unex.jdisrest.operator.SafeCompositeCrossover` copies the parents first; it takes the same constructor argument and fits wherever a `CrossoverOperator<CompositeSolution>` is expected, but it is not a subclass of `CompositeCrossover` (section 5, [Extra operators](#extra-operators)).

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
            System.err.println("Usage: SphereWorker <master-url> [worker-id]");
            System.exit(1);
        }
        String masterUrl = args[0];
        String workerId = args.length > 1 ? args[1] : null;   // null: a generated id

        var problem = new SphereProblem(10);
        try (var worker = new RestWorker<>(masterUrl, problem, workerId)) {
            worker.run();
        }
    }
}
```

`new RestWorker<>(masterUrl, problem)` generates the worker id (`worker-java-` and eight random hex characters); the three-argument constructor takes it, for instance a SLURM job and array index, and generates one for `null` or a blank id. It must be unique among the workers of the run (section 2.1); `getWorkerId()` returns it. A `null` or blank URL, or a `null` problem, is an `IllegalArgumentException`.

`RestWorker` automatically handles:
- A heartbeat thread every 15 s in parallel with evaluation. A heartbeat that fails (a network error, or an answer other than `2xx`) is retried after 5 s.
- Dead-master detection: 5 failed exchanges in a row in the main loop, or 3 failed heartbeats in a row, and the worker shuts down cleanly. A failed exchange is a network error or a timeout, an answer the protocol does not define for that call (a `5xx`, a `401`, ...) or a task without a usable `taskId`; the worker waits 10 s after one. The count starts again only on a `204` or when the master answers the report of a task (`2xx`, `404`, `400`, `413`, `415` or `422` to its result or error), not when a task is merely handed out, so a master that serves tasks but fails every result stops the worker too.
- Timeouts derived from `es.unex.jdisrest.util.Timings`: `GET /next` waits for the long-poll plus 10 s (40 s), a heartbeat a third of the heartbeat interval (5 s); a result or error report waits 15 s and a connection 10 s.
- Any supported encoding. Before the received vector is written into `problem.createSolution()`, the layout the master announces (`segmentSizes`, `encoding`, `segmentEncodings`, section 2.2) is compared with that solution's: a worker whose problem builds another kind of solution (other segment boundaries, a flat solution for a composite one, real variables for integer ones) reports the task through `POST /error` instead of evaluating misaligned values. The vector is then written through `SolutionVariables.apply()`, which splits composite solutions by segment and converts every value to the type of the destination variable.
- Failed and unusable tasks. Once the task id is known, everything that keeps a task from being evaluated is reported through `POST /error`, so the master requeues the task at once (or discards it after the failure limit, section 2.3), and the worker moves on: an unreadable `variables` list, a layout mismatch, a vector that does not fit the solution, an exception from `createSolution()` or `evaluate()` (logged at ERROR with its stack trace), and a `NaN` or infinite objective, constraint or changed variable. After two or more failed tasks in a row (rejected results included), the worker waits 5 s before asking for the next one.
- Lamarckian repair: when `evaluate()` changes the decision variables, the result carries the whole new vector in `variables` (section 2.3), and the master writes it into its solution if it lies within the bounds. When the variables are unchanged the field is left out.
- Rejected results (`400`, `413`, `415`, `422`) and results the master no longer expects (`404`): logged and skipped.
- `run()` runs once: a second call, a call from another thread while it runs, or a call after `close()` throws `IllegalStateException`. `close()` called from another thread stops a running loop at its next step, after the request in progress (at most the long-poll).

### 4.2 Python worker

The `jdisrest` package (installable with `pip install -e <path-to-jdisrest>/python`) provides the `Worker` class, which speaks the protocol as `RestWorker` does (the differences are listed below).

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

`run()` takes an `Evaluator`, any other object with an `evaluate(variables)` method, or a plain function; anything else raises `TypeError` before the worker connects. `evaluate()` may return:
- `EvalResult(objectives=[...], constraints=[...], variables=[...])`
- A `dict` (any mapping) with `objectives` / `constraints` / `variables` keys
- A scalar (treated as a single objective)
- A sequence of objectives: a list, a tuple or a numpy array
- Any object with an `.objectives` attribute (and optionally `.constraints` and `.variables`)

Before posting, the client converts objectives and constraints to plain floats (numpy scalars included) and checks that there is at least one objective and that every value is finite. A `NaN` or `inf` is reported to the master through `/error`, with the field and index in the message, so the task is requeued (or discarded after the failure limit, section 2.3) and the log points at the evaluator. Repaired `variables` keep their kind: Python ints are sent as JSON integers and floats as JSON floats; they must lie within the bounds of their variables (section 2.3).

`run()` blocks until the run finishes, the master is lost or the worker is interrupted, and returns which: `"finished"` (the master answered `410`, because the run finished or was stopped; or, for a worker created from an endpoint file, the master stopped answering and the file is gone or names another master, because the master shut down), `"master-lost"` or `"interrupted"` (also `Worker.FINISHED`, `Worker.MASTER_LOST` and `Worker.INTERRUPTED`). The same `Worker` can run again once `run()` has returned, one run at a time: a second `run()` while one is in progress raises `RuntimeError`.

Failures are handled as in `RestWorker`. An evaluator exception is reported through `/error` as `Type: message`, and its traceback is logged. The master is given up as lost after 5 failed requests in a row (network errors, and answers the protocol does not define: a `5xx`, another `4xx`, a `2xx` other than `200` to a task request, or a task without an integer `taskId`) or 3 failed heartbeats in a row; the request count starts again only on a `204` or when the master answers the report of a task (`2xx`, `404`, `400`, `413`, `415` or `422`). Heartbeats go every 15 s on their own HTTP session; a failed one (a network error or an answer other than `2xx`) is logged as a warning and retried after 5 s. The differences from `RestWorker`:

- Back-off: from the second failed evaluation in a row, the Python worker waits before asking for the next task, 1 s and then twice as long after each further failure, at most 60 s (`Worker.EVAL_ERROR_DELAY`, `Worker.MAX_EVAL_ERROR_DELAY`); a successful evaluation resets it, and rejected results do not count. `RestWorker` waits a fixed 5 s after two or more failed tasks in a row, rejected results included.
- A result that does not get through (a network error, or an answer outside the list above) is reported by the Python worker through `/error`, best-effort, as `result not delivered: <Type: message>`, which counts as a failed evaluation of the task if the master still has it in flight (a result that did arrive has taken it out). `RestWorker` sends nothing: the master requeues the task, without counting a failure, when the worker asks for its next one (section 2.2).
- A task whose `variables` are not a list of finite numbers is reported by both (`invalid task payload: <reason>` from Python), but the Python worker then waits 10 s without counting a failed request. A payload without an integer `taskId` (or a JSON `null`) counts as a failed request in both, so an endpoint that answers `200` with JSON to everything, such as a catch-all gateway, stops them as a lost master. A master of this version never sends either.
- The Python worker checks no segment layout: it hands the flat vector to the evaluator.

**Command-line worker.** When the evaluator is a plain function, no script is needed: `python -m jdisrest` (also installed as the `jdisrest-worker` command) imports it and runs a worker until the master finishes.

```bash
# /path/to/code/sphere.py:  def evaluate(variables): return sum(x ** 2 for x in variables)
python -m jdisrest --evaluator sphere:evaluate --code-dir /path/to/code \
    --endpoint /shared/run/.master-endpoint --timeout 300
```

`--evaluator MODULE:ATTR` names a function, an `Evaluator` subclass (instantiated without arguments) or an `Evaluator` instance, and `--code-dir` the folder `MODULE` is imported from. The function may also return a list or tuple of objectives. The worker connects to `--master URL` or, by default, waits up to `--timeout` seconds (300) for the endpoint file `--endpoint` (`.master-endpoint` in the current folder); a file that already exists is used at once, so delete a stale one before starting the master. `--worker-id`, `--variables N` and `--objectives N` (counts checked on the worker), `--non-finite-penalty X` (section 2.3) and `--log-level` complete the options. SIGTERM stops the worker as Ctrl+C does. The exit status is 0 once the worker stops because the run finished or was stopped, or because it was interrupted; 3 when it gave the master up as lost, so a batch script can tell the two apart; 1 if the evaluator cannot be loaded or the endpoint file never appears; and 2 for bad options. A worker that is still evaluating when the master shuts down at the end of a run (section 5) only finds the master gone. The master deletes its `.master-endpoint` when it shuts down, while one that dies leaves it behind, so a worker started from the endpoint file exits with 0 if, when it gives the master up, that file is gone or names another master, and with 3 otherwise; `Worker.run()` returns `"finished"` instead of `"master-lost"` in the same case. A worker given `--master URL` cannot tell, and exits with 3 then too. The same pieces are available from Python (`load_function`, `FunctionEvaluator`, and `add_worker_arguments`, `configure_logging` and `run_worker`, which returns what `Worker.run()` returned, for a worker command line of your own); see [`python/README.md`](../python/README.md).

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
        % Unique per worker (section 2.1): a time stamp would be shared by the
        % workers of a job array started in the same second.
        uuid = char(java.util.UUID.randomUUID());
        workerId = ['worker-matlab-' uuid(1:8)];
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
                case 204, consecutiveErrors = 0; pause(5); continue
                case 410, fprintf('[%s] Algorithm finished.\n', workerId); break
                case 200
                    taskId = task.taskId;
                    variables = double(task.variables);
                    t0 = tic;
                    objectives = sum(variables .^ 2);     % f(x) = Σxᵢ², one value per objective
                    elapsedMs = round(toc(t0) * 1000);
                    submit_result(masterUrl, workerId, taskId, objectives, elapsedMs);
                    consecutiveErrors = 0;                % the master answered the result
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
        % Requeued, or the run is over: the result is not needed.
        fprintf('[%s] task %d no longer expected by the master\n', workerId, taskId);
    elseif any(code == [400 413 415 422])
        % The master could not apply the result; it has requeued (or discarded) the
        % task, unless the run has been stopped or has ended.
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

`double(task.variables)` works for every encoding: `jsondecode` already delivers integers and reals as doubles. Two things to watch: `jsonencode` turns `NaN` into `null`, which the master rejects with `422` (map non-finite objectives to a finite penalty before `submit_result`), and a `422`, `400`, `413` or `415` response means the task was requeued, or discarded after the failure limit (neither after a stop or the end of the run, section 2.3): `submit_result` above logs the reason and returns, so the loop continues with the next task instead of counting it as a connection error. The error count starts again after a `204` or an answered result, not when a task arrives, as in the bundled workers (section 4.1). The worker id must be unique among the workers of the run (section 2.1); the default above is a random one.

---

## 5. Master: wiring the algorithm

A minimal master:

```java
import es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.operator.IntegerSimpleRandomMutation;   // not jMetal's class of the same name
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
        try {
            algo.run();
            algo.getResult().forEach(s ->
                System.out.println("f=" + s.objectives()[0] + "  vars=" + s.variables()));
        } finally {
            algo.shutdown();        // closes the REST server, so the JVM can exit
        }
    }
}
```

The constructor starts the REST server and returns once it accepts connections. The server always listens on every interface of the machine; the host is only the address written to `.master-endpoint` for the workers, so it must be one they can reach. A blank host, `0.0.0.0` or `::` cannot be: the master advertises instead the address of this machine's default route (or, without one, the address of its host name, or that of a network interface when the host name resolves to a loopback address such as the `127.0.1.1` of Debian and Ubuntu) and logs a warning, so pass the address or name the workers should use. Port `0` takes a free port, which is the one advertised. If the server cannot start, for instance because the port is in use, the constructor throws `IllegalStateException` (`Could not start the REST server on port 8080 — <cause>`, where the operating system words the cause, such as `Address already in use`) and writes no `.master-endpoint`.

`MasterFacade.init(maxEvals, statusFileIntervalSec)` starts the periodic `status.json` writer and records the start time for progress/ETA computation. Call it right before `algo.run()`. To change the failure limit of section 2.3, call `algo.setMaxTaskFailures(n)` before `run()` as well.

**Shutting down.** `algo.shutdown()` ends the master once its result has been written: it stops the run if it is still going (as `POST /api/v1/stop` does), deletes the `.master-endpoint` it wrote (unless another master has replaced the file since), closes the REST server at once (requests being served are cut off, and the workers get connection errors and stop, section 4), and makes the `status.json` writer write a last snapshot, with `"finished": true`, and stop. It takes about 2 s, a few seconds more while requests such as the long-polls of idle workers are still open (they are cut off without an error in the log), and further calls do nothing. Without it the REST server keeps the JVM alive after `main` returns, which is why programs written for jdisrest 1.1 end with `System.exit`; they still work, but leave `.master-endpoint` behind and `status.json` at its last periodic write. Call it from the program's own thread, not from a REST handler (such as `onTaskDiscarded`). A master cannot be restarted, and the static state of `MasterFacade` is never reset, so run one master per JVM.

**Server settings.** The `.master-endpoint` file (`AbstractMaster.ENDPOINT_FILE_NAME`, `{"host":"10.0.0.1","port":8080,"url":"http://10.0.0.1:8080"}`, with an IPv6 address in brackets in the `url`) and `status.json` go to the folder named by the system property `jdisrest.dataPath` (the working directory by default), which is created if it does not exist. The REST server starts with these Spring properties as defaults:

| Property | Default | Why |
|---|---|---|
| `server.address` | `0.0.0.0` | listen on every interface |
| `spring.main.banner-mode` | `off` | |
| `spring.http.codecs.max-in-memory-size` | `16MB` (16 MiB, `AbstractMaster.DEFAULT_MAX_REQUEST_SIZE`) | the largest request body; a larger result gets `413` (section 2.3). Spring's own 256 KiB refused large Lamarckian results every time |
| `server.shutdown` | `immediate` | `shutdown()` cuts off long-polls nobody needs instead of waiting for them |

A `-D` system property (`-Dspring.http.codecs.max-in-memory-size=64MB`), an environment variable or an `application.properties` file on the class path overrides any of them, and sets any other Spring property. `server.port` always comes from the constructor.

For a real-coded problem, `es.unex.jdisrest.config.ConfiguredMaster` runs NSGA-II, PAES or MOEA/D from a configuration file without a `main` of your own (see [Configuration files and the launcher](#configuration-files-and-the-launcher)).

### Available algorithms

- `es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII` — tournament + ranking/crowding.
- `es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD` — weight-based decomposition. The weight vectors come from `MOEADWeights` (spread for any population size, or the Das–Dennis lattice), unlike jMetal and Evolver, which read those of three or more objectives from files that exist for a few sizes only. The aggregation, with the ideal and nadir points and the optional normalization, is `MOEADAggregation`. Both are Spring-free and tested (see below).
- `es.unex.jdisrest.distributed.algorithms.steadystate.SMSEMOA` — hypervolume. The population plus the newcomer is ranked with constraint-aware dominance, and the member of the last front with the smallest hypervolume contribution leaves. An offspring identical to a population member is discarded on arrival (it still counts as an evaluation and enters the archive). When an objective has the same value in the whole joint population, the contributions are undefined (jMetal's PISA hypervolume throws), so the member of the last front with the smallest crowding distance leaves instead, and `SMS-EMOA: objective <i> has the same value in the whole population — ...` is logged once.
- `es.unex.jdisrest.distributed.algorithms.steadystate.PAES` — (1+1) evolution strategy with a bounded density archive; mutation only (see below).
- `es.unex.jdisrest.local.algorithms.NSGAII` — sequential local variant (no REST), useful for debugging small problems or measuring distribution overhead. Its Python evaluators are `PythonProcessEvaluator` / `PythonSolutionListEvaluator` from the `es.unex.jdisrest.local` package ([Local mode](#local-mode)).

The four steady-state algorithms share `SteadyStateEvolutionaryAlgorithm`. `getResult()` returns the feasible solutions of the result archive, at most `populationSize` (for PAES, see below), and an empty list when there is none, also for a run stopped before its first result. `observable()` is jMetal's `Observable` of the progress attributes (`EVALUATIONS`, `POPULATION`, `COMPUTING_TIME`), notified once the run starts and after every processed result, for jMetal observers such as a progress log or a live chart: register them before `run()`. They run on the algorithm thread, so they may read the population but must not change it, and a slow observer slows the processing of results. A crossover may return one child: a second pair of parents, selected independently, is then mated for the second offspring of a task, which would otherwise be a copy of the first that only the mutation may tell apart; one that returns none makes task creation fail with `IllegalStateException`. The external archive keeps its own copies of the results, never the population's solutions.

The stopping criterion is checked after every processed result and before every task creation, on the progress attributes. jMetal's `TerminationByComputingTime` therefore fires only when a result arrives, and `TerminationByQualityIndicator` cannot be used: it requires a non-empty population, and the first check comes before any result has arrived.

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
- Warm start (section 3) asks for a single solution and uses the first one it gets. Every `archiveSize` evaluations, and once more when the run ends (section 7.7), the traces write the external archive to `aVAR`/`aFUN` and the PAES archive to `VAR`/`FUN`, so a run that is killed keeps the set `getResult()` returns by default.
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
- **Population.** Once the population is full, member `k` is the current solution of subproblem `k`. Every task records the subproblem it was created for: initial task `i` is subproblem `i`, and an offspring belongs to the subproblem whose neighbourhood (or, with probability 1 − `delta`, the whole population) supplied its parents. While the population fills, a solution takes the slot of its subproblem when it is free, otherwise the nearest free one. Workers that ask for work before the first result has arrived, including more workers than subproblems, get random solutions. The initial solutions come from `createInitialSolutions(populationSize)`, so the warm start is used (section 3). Every random draw comes from `JMetalRandom`, so its seed covers MOEA/D too. `getResult()` returns the feasible solutions of the archive, at most `populationSize`, as the other algorithms do. Replacement compares aggregated objectives only, so it ignores constraints, and the selection operator passed to the constructor is not used.
- **Validation.** Every argument is checked before the REST server starts: the weight method against the problem's number of objectives (`IllegalArgumentException`), `T` in [1, `populationSize`], `maxReplacedSolutions` in [1, `T`], `delta` in [0, 1], and no `null` crossover, mutation, termination, aggregation or weight method.
- `reconfigure(crossover, mutation, neighborhoodSelectionProbability, maxReplacedSolutions, aggregation, normalizeObjectives)` changes those settings while the run goes on; the weights, the neighbourhoods and their size stay.

Changes for code written against jdisrest 1.1, which also apply to the 13-argument constructor:

- With three or more objectives the weights are the deterministic `SPREAD` vectors instead of unseeded random ones, so runs are reproducible but not comparable with runs of 1.1. With two objectives they are the same, and with a single subproblem the vector is the centre of the simplex instead of `NaN`.
- Tchebycheff weighs a zero weight by 10⁻⁴, which changes the two corner subproblems of every two-objective run.
- A `delta` outside [0, 1] and a `null` operator, termination or aggregation are rejected at construction, before the server starts.
- `weights.csv` is written, and the traces folder created, at construction.
- The protected fields `agg` and `idealPoint` are gone: the aggregation and both points live in the protected `aggregation` field (a `MOEADAggregation`), next to the new `weightMethod`. `lambda` is no longer allocated before `initWeightVectors()`, so an override must assign a new array.
- The warm start is honoured (1.1 always started from random solutions), and `iVAR.csv` is copied into the traces folder.
- `getResult()` returns only feasible solutions; 1.1 returned the archive as it was, infeasible members included.
- Every draw comes from `JMetalRandom` instead of an unseeded `java.util.Random`, so the stochastic stream of a run changes; the protected `random` field is deprecated and unused.
- Solutions fill the slot of their subproblem instead of arriving in order, the duplicate filter stays exact when one offspring replaces several neighbours, and a worker that asks before the first result gets a random solution instead of a `500`.

### Local mode

`es.unex.jdisrest.local.algorithms.NSGAII` runs NSGA-II in one process, without REST and without workers: jMetal's generational NSGA-II with the traces, the warm start (section 3) and the feasible result of the distributed algorithms. It also differs from jMetal's in three places, to behave like the distributed NSGA-II: the replacement ranks with constraint-aware dominance (`DominanceWithConstraintsComparator`), each child of the crossover is copied before it is mutated (jMetal mutates in place, and crossovers such as `NPointCrossover` or `CompositeCrossover` may return the parents themselves or share their segments), and the constructor rejects a population size below 1 or that is not a multiple of the crossover's parents (`IllegalArgumentException`: NSGA-II mates the whole population in groups of that size). `result()` is empty before `run()` and for an empty archive, and `saveTrace()` is protected.

The evaluations go through any jMetal `SolutionListEvaluator`. For an evaluator written in Python, `PythonSolutionListEvaluator` sends each solution to a `PythonProcessEvaluator`, a long-running Python child process that answers over its standard input and output:

```java
var python = new PythonProcessEvaluator(
        "python3", "evaluator.py",
        /*scriptArgs=*/ List.of("--config", "run.json"),
        /*workingDirectory=*/ Path.of("/path/to/code"),
        /*extraEnv=*/ Map.of("OMP_NUM_THREADS", "1"),
        /*timeout=*/ Duration.ofMinutes(10));         // null: wait as long as it takes
var evaluator = new PythonSolutionListEvaluator<DoubleSolution>(python);
var algo = new NSGAII<DoubleSolution>(problem, /*populationSize=*/ 100, /*maxEvaluations=*/ 25000,
        crossover, mutation, NSGAII.defaultSelection(), evaluator, /*tracesFolder=*/ "traces");
try {
    algo.run();
    TraceWriter.write(algo.result(), "VAR.csv", "FUN.csv", ",");
} finally {
    evaluator.shutdown();                           // nothing else ends the Python child
}
```

The child is started as `<python> -u <script> [scriptArgs...]`, in `workingDirectory` (the JVM's when `null`; a relative script is resolved against it) and with `extraEnv` added to its environment. Shorter constructors take `(python, script)`, `(python, script, extraEnv)` and `(python, script, extraEnv, timeout)`; `null` means the default for each argument. The child's standard error is inherited, so its tracebacks and diagnostics show up on the master's console.

**The child protocol.** One JSON object per line, in strict lock-step: Java writes one request and waits for exactly one answer before it writes the next.

- Handshake: the first line the child prints must be a JSON object with `"ready": true` (other fields are ignored).
- Request: `{"id": 0, "vars": [3, -1.5, ...]}`. The key is `vars`, not `variables` as in the REST payload, for compatibility with existing children, and the key order is unspecified. Integer variables are written as JSON integers and real ones as JSON floats, in the layout of `SolutionVariables.flatten` (composite segments concatenated). A `null`, `NaN` or infinite variable is never sent: the call fails with `IllegalArgumentException` (`Cannot send the decision to the Python evaluator: variables[3] is not finite: NaN`) before anything is written, and the evaluator stays usable.
- Answer: `{"id": 0, "objectives": [...], "constraints": [...], "variables": [...]}`, where `id` echoes the request's, and `constraints` and `variables` are optional. Objectives and constraints must be finite numbers, as many as the problem defines (else `IOException` or `IllegalStateException`). `variables` is a repaired decision vector (Lamarckian repair), in the layout of the request; its numbers are converted to the type of each variable.
- Error: `{"id": 0, "error": "<message>"}`. The call fails with an `IOException` carrying the message, and the protocol stays in step, so the next call works. Only a non-empty string counts as an error: an `error` field that is `null`, `false`, `""` or not a string is ignored, and the answer must then carry `objectives`.
- End: Java closes the child's standard input (EOF) when it has no more requests, and the child must exit then.

**Standard output is reserved for protocol lines.** Anything else the child prints there, a debug `print()`, a library banner or warning, is read in place of the expected answer: the call fails with an `IOException` that quotes the line (cut at 500 characters), and since the real answer is still in the pipe, the evaluator refuses every later call. A line that is not a JSON object (`null`, a number, an array, or a bare `NaN` token, which `json.dumps` writes for a non-finite float) fails the same way. Diagnostics belong on standard error. A minimal child that keeps stray `print()` calls off the protocol stream (output written to file descriptor 1 by native code needs an `os.dup2` redirection instead):

```python
import json, sys

def evaluate(x):                                 # the problem: one list of objectives
    return [sum(v * v for v in x)]

protocol, sys.stdout = sys.stdout, sys.stderr    # print() now goes to stderr

def send(message):
    protocol.write(json.dumps(message, allow_nan=False) + "\n")
    protocol.flush()

send({"ready": True})
for line in sys.stdin:                           # ends at EOF, when Java closes the evaluator
    request = json.loads(line)
    try:
        send({"id": request["id"], "objectives": evaluate(request["vars"])})
    except Exception as e:                       # with allow_nan=False, a NaN objective lands here
        send({"id": request["id"], "error": f"{type(e).__name__}: {e}"})
```

**Time-out.** Without one, every wait for a line from the child blocks as long as it takes, so a hung evaluation blocks the run forever. A `timeout` bounds each wait, for the ready banner and for every answer, so it must also cover the child's start-up (its imports included). When it expires, the child and every process it started are destroyed, the call fails with an `IOException`, and the evaluator is unusable. Interrupting the thread that waits has the same effect (`InterruptedIOException`, with the interrupt flag kept). The time-out does not bound writing a request.

**Lifecycle.** A constructor that fails after starting the child (a wrong banner, a child that exits first, a time-out) destroys the child before it throws. After a time-out, an interrupt, a line out of step, a child that closes its standard output or stops reading its input, or `close()`, every call fails at once with `IOException` `Python evaluator is no longer usable: <reason>`; an error answer, or an invalid answer that is still a JSON object with the right `id`, leaves the evaluator usable. `close()` (or `evaluator.shutdown()`) must be called when the run ends, since nothing else stops the child: neither jMetal's NSGA-II nor the local `NSGAII` calls `shutdown()`. It closes the child's input, waits up to 10 s for it to exit, then kills it and every process it started; it is idempotent. `PythonProcessEvaluator` is not thread-safe, but `close()` may be called from another thread to end a call that waits for a hung child: the call fails once the 10 s have passed.

**Writing results back.** `PythonSolutionListEvaluator` copies the objectives and constraints into each solution, and writes a non-empty `variables` vector back first, with `SolutionVariables.apply` after a bounds check: a value outside the bounds of its variable (inclusive, without tolerance, as in section 2.3) is rejected with `IllegalArgumentException` (`variables[2] = 3.0 is outside the bounds [-2.5, 2.5] of its variable`) and nothing is written. The second constructor takes a custom `decisionExtractor` (a solution to the vector sent); a custom extractor that lays the variables out differently needs the third, which also takes the matching `variablesApplier`, otherwise a returned vector is written in the `flatten` layout. A custom applier should check the bounds too. Local evaluation is fail-fast: the first failure (an error answer, a protocol problem as `UncheckedIOException`, a decision that cannot be sent, a count mismatch as `IllegalStateException`, a rejected repair) ends the `evaluate` call, and with it the run. The solutions before it are already updated; there is no retry or penalty, unlike the distributed mode. `PythonSolutionListEvaluator` holds a live process, so serialising it throws `NotSerializableException`.

### Extra operators

Under `es.unex.jdisrest.operator.*`:

- `IntegerSimpleRandomMutation(probability[, randomGenerator])`: uniform reset mutation for `IntegerSolution`, every value of `[lb, ub]` equally likely, for any `int` range. jMetal 7.1 has a class with the same simple name and constructors whose draw never produces `ub` for a non-negative range, so check that the import is `es.unex.jdisrest.operator`: the other one compiles and silently changes the search.
- `IntegerGaussianMutation(probability[, randomGenerator])`: adds Gaussian noise with σ = max(2, (ub − lb)/2) to the value, rounded and clamped to the bounds. The steps are wide (about a third of the range from mid-range), and at least half of the mutations of a value on a bound leave it there.
- `IntegerBLXCrossover(probability[, alpha[, repair[, randomGenerator]]])`: BLX-α for `IntegerSolution`; the children are rounded to the nearest integer after the repair, and a variable whose lower and upper bounds are equal keeps that value.
- `PolynomialMutationRandomProbability`, `RandomMutationWithRandomProbability`: mutations for `DoubleSolution` with a per-call randomized probability, `p + U[0,1] × jitter`. For `RandomMutationWithRandomProbability(probability, jitter, randomGenerator)` the jitter is counted in variables and divided by their number n (`DEFAULT_JITTER` = 3.5, so 1.75 extra mutated variables per call on average); for `PolynomialMutationRandomProbability(probability, distributionIndex, jitter, repair, randomGenerator)` it is a probability (`DEFAULT_JITTER` = 5/36), so its extra mutated variables grow with n. The shorter constructors use the defaults.
- `DoubleNPointCrossover(probability, points[, blockSize])`: n-point crossover for `DoubleSolution` that always makes exactly `points` distinct cuts (jMetal's `NPointCrossover` draws each point independently, so its cuts can coincide or cut nothing) and returns copies of the parents when the crossover is not applied (jMetal returns the parents themselves). With a block size b > 1 the cuts fall only between consecutive blocks of b variables, so tuples such as the (x, y, z) coordinates of each point of a shape pass whole from a parent to a child. The number of variables must be a multiple of b, there must be at least 2 blocks, and `points` must be smaller than the number of blocks. Those rules depend on the problem, so `execute()` checks them at every call, and inside a master the first crossover happens in the task request of a worker: call `DoubleNPointCrossover.check(numberOfVariables, points, blockSize)`, which returns the reason or `null`, before the run.
- `LevyFlightMutationRandomStepSize(probability, beta, maximumStepSize)`: Lévy flight mutation whose step size is drawn for every mutated solution, uniform in [0, `maximumStepSize`), so the mean step size is half the maximum; it then mutates exactly as jMetal's `LevyFlightMutation` with that step. `beta` must lie in (1, 2), and steps shrink strongly as it approaches 2. The three-argument constructor clamps values to their bounds and leaves a variable whose lower and upper bounds are equal at that value, where jMetal's default repair throws. Note that jMetal's `LevyFlightMutation` accepts `beta = 2`, where the steps of Mantegna's algorithm vanish (about 10⁻⁸ of the range) and nothing visibly mutates; this operator and the configuration files reject it.
- `NaryTournamentSelection([tournamentSize, comparator[, randomGenerator]])`: two independent tournaments that return the parent pair the steady-state algorithms need (the defaults are a size of 2 and `DominanceWithConstraintsComparator`). Each tournament draws `tournamentSize` distinct candidates, so its cost does not depend on the population size; the draws come from the optional `BoundedRandomGenerator<Integer>`, `JMetalRandom` by default. jMetal's class of the same name returns a single solution.
- `DifferentialEvolutionSelection`: DE parent selection for `DoubleSolution` populations. With the target solution excluded it needs `numberOfSolutionsToSelect + 1` solutions (4 for the default of 3) and throws on a smaller population. **Not wired into any algorithm of this release**: it needs `setIndex(i)` before every `execute()` and a DE crossover fed with the current solution, and neither the steady-state base class nor MOEA/D does that (MOEA/D builds its parents itself and ignores the selection operator). jMetal's `DifferentialEvolutionCrossover` does not fit the steady-state algorithms for the same reason. Usable only from a custom algorithm.
- `SafeCompositeCrossover(operators)`: wrapper around `CompositeCrossover` that copies the parents before delegating, because jMetal's `NPointCrossover` returns the parents themselves when it does not fire and `CompositeCrossover` puts those segments into the children, so a mutation applied in place corrupts the parents. It takes the same constructor argument and fits wherever a `CrossoverOperator<CompositeSolution>` is expected, but it is not a subclass (`instanceof CompositeCrossover` is false) and has its own `getOperators()`. It rejects parents whose number of segments differs from the number of operators, naming both. It only matters for code that mutates the crossover's output in place: jMetal's generational loops, a custom `GenerationalAlgorithm.evolution` or a `createNewTask` override that does so; the steady-state algorithms copy their offspring already.

The integer operators, the two mutations with a randomized probability and `NaryTournamentSelection` take every random number from their injected generator, `JMetalRandom` by default, so seeding `JMetalRandom` reproduces their draws; on a master the order in which the workers' requests arrive still varies from run to run.

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
6. The algorithm runs until its budget is spent or `POST /api/v1/stop`, and its result is written to `VAR.csv` and `FUN.csv` (comma-separated) in the working directory; a run stopped before its first result writes them empty. The master logs `ZDT1 finished after <evaluations> evaluations: <n> solutions written to VAR.csv and FUN.csv`.
7. The master is shut down (`shutdown()`, section 5), also when a step after the second fails: `.master-endpoint` is deleted, the REST server closes and `status.json` gets a final snapshot that reports the run as finished. Then the JVM exits. Workers that asked for a task before the close got `410` and stopped (a result they posted after the run had ended got `404` first, and was not counted); those still evaluating find the master gone and stop after their failed heartbeats or requests, typically 10 to 25 s later (section 8, `POST /api/v1/stop`). A Python worker started from the endpoint file then finds the file deleted and exits with 0, not 3 (section 4.2).

`--check` reads the file for the problem exactly as a run does (every value, the operators, which are built once, and the MOEA/D lattice size against the problem's objectives), prints `Configuration OK for <class name>: <summary>` on the standard output and exits without starting Spring, so a script can gate the submission of a job on its exit status. The exit status is 0 when a run ends (also after `POST /api/v1/stop`) or a check passes, and 1 otherwise. A usage error prints `Invalid arguments: <reason>` and the usage text (for instance `port must be an integer in [1, 65535], got '0'`); an invalid file prints `Invalid configuration: <reason>`, such as `mutation.beta must be in (1, 2), got '2'`; both go to the standard error without a stack trace. A failure while the run starts or goes on is logged as `<class name> failed: <exception>` with its stack trace, and still ends the JVM with status 1; a port already in use, for instance, gives `ZDT1 failed: java.lang.IllegalStateException: Could not start the REST server on port 8080 — Address already in use` (with the operating system's wording of the cause).

A problem that needs constructor arguments, or a program that wants its own command name, calls the same launcher from its own `main` and exits with the status it returns (`run` shuts the master down itself, but a failure while the algorithm is being built can leave a server that nothing can shut down, so keep the `System.exit`):

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

**The traces folder.** Besides the trace snapshots (`aVAR_<n>.csv`/`aFUN_<n>.csv` for the archive and `VAR_<n>.csv`/`FUN_<n>.csv` for the population, or the PAES archive for PAES, every `populationSize` evaluations, or every `archiveSize` for PAES, and once more when the run ends, section 7.7) and MOEA/D's `weights.csv`, a run with a traces folder leaves there the configuration it used (`<name>` below is the name of the file without `.properties`):

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
5. When the run ends (its budget is spent, or `POST /api/v1/stop`), the master writes `VAR.csv` and `FUN.csv` to the run folder, shuts down (which deletes `.master-endpoint` and makes the running workers stop) and exits; the job removes `.master-endpoint` again, in case the master was killed, and cancels the worker tasks still waiting in the queue.

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

- `-Djdisrest.dataPath=<dir>` controls where the master writes `.master-endpoint` and `status.json`; the master creates the directory if it is missing, and it must be visible to the worker nodes. The master deletes the `.master-endpoint` it wrote when it shuts down, but one that is killed (by `scancel` or its time limit) leaves it behind, and a worker uses an existing file at once, hence the `rm -f` in `submit.sh` and `master.sh`.
- A program with its own `main` (section 5) can replace `ConfiguredMaster` in `master.sh`: it must advertise the host and port the script computes and call `shutdown()` on the master once the result is written, because otherwise the REST server keeps the JVM alive after `run()` until the job's time limit.
- Each worker needs its own id (section 2.1). `worker.sh` builds one from the array job and task ids; a fixed `--worker-id` shared by the workers of an array would make them requeue each other's tasks.
- To stop the run early, from any machine that can reach the master: `url=$(python3 -c "import json; print(json.load(open('.master-endpoint'))['url'])")` and `curl -X POST "$url/api/v1/stop"`. The master writes `VAR.csv` and `FUN.csv` as at a normal end and exits, the workers stop (section 8), and `master.sh` cancels the pending ones. To change its settings instead, `curl -s "$url/api/v1/config" > new.properties`, edit the file and `curl --data-binary @new.properties "$url/api/v1/config"` (section 8); every change is saved in the traces folder.
- The master does not handle SIGTERM: at its time limit SLURM kills it before it writes `VAR.csv` and `FUN.csv`, and only the latest trace snapshot remains. Size `maxEvaluations` to the time limit, or stop the run with `POST /api/v1/stop` before it.
- If the port is already taken, the master fails at once without writing `.master-endpoint`: `ConfiguredMaster` logs `<problem> failed: java.lang.IllegalStateException: Could not start the REST server on port <port> — ...` and exits with status 1, and `master.sh` cancels the pending workers. Submit the run again.
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
│  workerRegistry      — known workers, when each was last heard from, and the one task it holds
│  failure limit, stop — failInFlightTask, setMaxTaskFailures, requestStop (TaskFailureTracker, StopRequest; no Spring)
│  readiness, shutdown — isReady, shutdown
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

The protocol timing constants (heartbeat 15 s, worker timeout 45 s, watchdog cycle 30 s, long-poll 30 s) are centralized in `es.unex.jdisrest.util.Timings`. `RestWorker` derives its timeouts from them (`GET /next` waits for the long-poll plus 10 s; a heartbeat waits, and is retried after, a third of the heartbeat interval). The Python `Worker` cannot read them: if any of them changes, update its class constants too (`HEARTBEAT_INTERVAL`, `HEARTBEAT_TIMEOUT`, `HEARTBEAT_RETRY_DELAY` and `REQUEST_TIMEOUT`), keeping the read timeout of a task request above the long-poll (section 2.2). They are compile-time constants, so Java code compiled against them keeps the old values until it is recompiled.

`MasterFacade` (a static class) is the bridge between the Spring beans (controllers, watchdog) and the active master instance. Beans never import `SteadyStateMaster` or `GenerationalMaster` directly; they call `MasterFacade.claimNextTask()`, `submitResult()`, `failInFlightTask()`, `requestStop()`, etc., which delegate to the active instance. It also holds the `ConfigurationHandler` of `/api/v1/config` (`setConfigurationHandler`), the evaluation budget of the status report (`init`, `setMaxEvaluations`) and the `status.json` writer, which `shutdown()` stops (`stopStatusFileWriter`). The REST server starts in `AbstractMaster`'s constructor, on the calling thread, before the master has registered itself with the facade and before the subclass constructors have run; requests in that window find no master, or one that is not ready (section 7.5), and are answered accordingly.

### 7.2 Life cycle of a task

```
Master                                  REST API                  Worker
──────                                  ────────                  ──────
createInitialTasks()                    [algorithm thread]
  → pendingTaskQueue.add(task) for each
initProgress()                          (from now on isReady() is true)

                                  GET /tasks/next
                            ←──────────────────────── (long-poll 30s)

TaskController.getNextTask():           [virtual thread]
  → 410 if finished or stopped
claimNextTask(workerId)
  → null (204) until isReady(), and once a stop is requested or the loop has ended
  ← pendingTaskQueue.poll()
  → if empty and still running: createNewTask()
    (two children: one returned, the spare queued)
  → if still nothing: pendingTaskQueue.poll(30s)
  → recordDispatch(workerId, task):
      inFlightTasks.put(taskId, task)
      workerRegistry[workerId].currentTaskId = taskId
      (the worker's previous task, if still in flight, is requeued)

                                  200 OK {taskId, variables}
                            ──────────────────────────────►

                                                          variables = task.variables
                                                          f = evaluate(variables)

                                  POST /tasks/{id}/result
                            ←────────────────────────────

TaskController.submitResult():          [virtual thread]
  ← inFlightTasks.get(taskId)
  → validates the payload; if invalid: 422 and failInFlightTask(taskId, workerId):
      requeued, or discarded after the failure limit (ignored if another worker holds it);
      404 and nothing counted once a stop is requested or the loop has ended
  → MasterFacade.submitResult(taskId, workerId, recorder)
     → inFlightTasks.remove(taskId)     (404 if no longer in flight)
     → currentTaskId = -1 for every worker that pointed at the task
     → if a stop is requested or the loop has ended: 404, nothing recorded
     → recorder: writes variables (optional), objectives and constraints into the solution
     → completedTaskQueue.add(task)

run() loop                              [algorithm thread]
  ← waitForComputedTask()               (completedTaskQueue; null after a stop)
  → processComputedTask(task): archive, population, ranking + crowding
  → updateProgress(): attributes for the stopping criterion, traces
  → [repeat until the stopping criterion is met or a stop is requested]
```

Task creation is demand-driven: in a steady-state master the REST thread that serves `GET /tasks/next` creates the task when the queue is empty, and `processComputedTask` never creates one. `inFlightTasks.remove(taskId)` is the single point that decides what happens to a task in flight: whichever of a result, a failure, the watchdog or a new claim of the same worker removes it first owns it, and the others find nothing to do. A result is written into the solution only after it has won that removal.

A `POST /error`, a rejected result and a task that cannot be serialized all go through `AbstractMaster.failInFlightTask(taskId, workerId)`, which ignores a report from a worker that no longer holds the task (section 2.4) and otherwise calls `failInFlightTask(taskId)`: that one requeues the task or, at the failure limit, discards it (section 2.3). Once the master needs no more results (`needsNoMoreResults()`: a stop has been requested, or `runEnded()`, which `SteadyStateEvolutionaryAlgorithm` reports once its loop has ended), it only takes the task out of flight and forgets its failures, and `failInFlightTask(taskId, workerId)` returns `false`; the same predicate makes `submitResult` refuse results, `claimNextTask` hand out nothing and the watchdog drop the tasks of silent workers. It is not `isFinished()`, which REST threads may see `true` while the loop still waits for the result that lets it notice its stopping criterion (a criterion installed with `setTermination`, section 7.5): refusing that result could hang the run. The deprecated `requeueInFlightTask(taskId)` of earlier versions delegates to `failInFlightTask(taskId)` and counts as a failure too (nothing after a stop or the end). The framework calls only `failInFlightTask`, so a subclass written for 1.1 that overrode `requeueInFlightTask` must move that code to `failInFlightTask(long)` or `onTaskDiscarded`. Likewise the REST layer calls the three-argument `submitResult(taskId, workerId, recorder)`, so an override of the two-argument `submitResult(taskId, workerId)` written for 1.1 no longer sees the workers' results and must override the three-argument one. Whenever a task leaves flight, every worker's `currentTaskId` that points at it returns to `-1`. The watchdog moves the tasks of silent workers back to the queue without counting a failure (section 7.4). A `GenerationalMaster` queues a whole generation at once (`submitTasks`), and its `claimNextTask` only polls the queue.

### 7.3 Server-side long-polling

`TaskController.getNextTask()` uses `Mono.fromCallable()` on a scheduler of virtual threads (the `virtualThreadScheduler` bean of `MasterSpringApp`, one virtual thread per call), so it never blocks the Netty event loop, and any number of long-polls can wait at once without tying up platform threads or making a result wait behind them. The handlers of `/result` and `/error` run there too.

```java
return Mono.fromCallable(() -> {
               if (MasterFacade.isFinished()) {               // finished or stopped
                   return ResponseEntity.status(410).build();
               }
               var task = MasterFacade.claimNextTask(workerId, Timings.TASK_LONGPOLL_S);
               if (task == null) {
                   return ResponseEntity.noContent().build();  // nothing in time, or not ready yet
               }
               try {
                   return ResponseEntity.ok(toPayload(task.getIdentifier(), task.getContents()));
               } catch (RuntimeException e) {                 // e.g. a NaN variable
                   MasterFacade.failInFlightTask(task.getIdentifier(), workerId);
                   return ResponseEntity.internalServerError().build();
               }
           })
           .subscribeOn(scheduler);                           // the virtual-thread scheduler
```

`SteadyStateMaster.claimNextTask()` first takes a queued task or creates one (section 7.5), so a steady-state worker practically never waits; the long-poll, `pendingTaskQueue.poll(timeout, SECONDS)` for up to 30 s, is only a fallback for when no task can be created. `GenerationalMaster.claimNextTask()` always long-polls, because only `submitTasks()` fills its queue. If no task arrives in that time, the method returns `null` → the controller responds `204`. Both return `null` at once, without a long-poll, while the master is not ready (`204`, ask again). Once it needs no more results (a stop or, for `SteadyStateEvolutionaryAlgorithm`, the end of the run) they return `null` at once and drop a task obtained meanwhile: the worker gets `204`, then `410` on its next request.

### 7.4 Watchdog

`WatchdogScheduler` runs every 30 s (`Timings.WATCHDOG_INTERVAL_MS`) via `@Scheduled`:

```java
@Scheduled(fixedDelayString = "#{T(es.unex.jdisrest.util.Timings).WATCHDOG_INTERVAL_MS}")
public void checkDeadWorkers() {
    MasterFacade.requeueOrphanTasks(Timings.WORKER_TIMEOUT_S);   // 45 s without heartbeat
}
```

`requeueOrphanTasks()` looks for workers whose `lastSeen` (their last heartbeat, task request or accepted result) exceeds the threshold, moves their tasks from `inFlightTasks` back to `pendingTaskQueue`, and removes the worker from the registry. After a stop or the end of the run it drops those tasks instead, since nobody would hand them out, and logs `Dropping task 42 from dead worker w-07 — the run needs no more results`. Each removal is one atomic step, so a worker whose heartbeat or request lands at that moment is either kept or registered afresh, never removed together with a task it has just been given. When it removes workers, the master logs `Watchdog: N worker(s) removed. Active workers: ... | Pending tasks: ... | In-flight tasks: ...`. If the original worker delivers the task before another worker has claimed it, it receives a `404` and should log a warning without retrying; once another worker holds the task (same id), the late result is accepted and the second worker's result gets the `404`. A `POST /error` (or a result rejected with `422`) that the original worker sends after another worker has claimed the task is ignored (section 2.4), so it cannot count against the new dispatch; a result rejected with `400`, `413` or `415` counts whoever sent it (section 2.3). These requeues do not count towards the failure limit of section 2.3, because a worker can die for reasons unrelated to its task (a preempted SLURM job, an evicted HTCondor job). A task whose evaluation itself kills the worker is therefore retried without limit, one watchdog timeout each time.

### 7.5 Steady-state synchronization

`SteadyStateMaster.claimNextTask()` uses double-checking with `synchronized` to prevent two concurrent requests from generating duplicate tasks:

```java
if (needsNoMoreResults() || !isReady()) return null; // 410 on the next request, or 204 until ready
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
if (task != null && needsNoMoreResults()) {
    task = null;                                     // a stop or the end arrived meanwhile
}
if (task != null) {
    recordDispatch(workerId, task);                  // in flight; the worker's previous task is requeued
}
```

Tasks are therefore created on the virtual threads of the REST handlers, one at a time under `taskCreationLock`, while the algorithm thread processes results.

**Readiness.** The REST server answers from the moment the constructor starts it, but the algorithm can only serve workers once `run()` has built its initial state. `isReady()` (in `AbstractMaster`, `true` by default) tells the REST layer when that is: while it is `false`, `claimNextTask` hands out nothing (`204`), `isFinished()` is `false` without consulting the stopping condition unless a stop was requested, `GET /api/v1/status` and `status.json` report the run as running, and `POST /api/v1/config` answers `503`. `SteadyStateEvolutionaryAlgorithm` returns `false` from its constructor until `initProgress()` has filled the `attributes` its termination reads (just after the initial tasks are queued), and `true` from then on, and also once `run()` has finished, even after a failure. Until then its `stoppingConditionIsNotMet()` answers `true` on REST threads without consulting the termination, which jMetal's criteria cannot evaluate on missing attributes. An override of `isReady()` must be thread-safe, cheap and safe to call on a half-built object: REST threads call it before the subclass constructors have run. An override of `initProgress()` should call `super.initProgress()`; one that does not still makes the master ready at the algorithm thread's first check of the stopping condition. `attributes` is a `ConcurrentHashMap`, because REST threads read it while the algorithm thread writes it, so it rejects `null` values.

Inside `SteadyStateEvolutionaryAlgorithm`, `population` and `populationSignatures` are only read and changed inside `synchronized (population)`. `createNewTask()`, which runs on REST threads, reads `crossover` and `mutation` under that lock, and `setVariation(crossover, mutation)` replaces them under it, so no task mixes an old operator with a new one; tasks already created, including the spare child in the queue, keep their solutions. `setTermination(termination)` replaces the stopping criterion from the next check on. Once the loop has found the old criterion met, or `run()` has finished, it cannot reopen the run: it changes nothing and returns `false`; the two cannot cross, so a criterion installed with `true` is the one the loop checks next. A criterion that is already met stops task creation at once, but the loop only notices it with the next result, so callers that change an evaluation budget should only accept one above `getEvaluations()`, the results processed so far, which any thread can read (only the algorithm thread writes `evaluations`), and call `requestStop()` if the run reaches it while they install it. The budget of the status report is separate: `MasterFacade.setMaxEvaluations(int)` updates it, so that `progress` and the ETA follow. `AlgorithmReconfiguration` (section 5) does all of this for `POST /api/v1/config`.

### 7.6 Adding an algorithm

1. Extend `SteadyStateEvolutionaryAlgorithm<S>`. It is a complete algorithm already: `createInitialTasks()` asks for `populationSize` solutions through `createInitialSolutions(count)`, `createNewTask()` mates two parents of the selection and mutates copies of both children, filtering exact duplicates, and `processComputedTask()` keeps the best `populationSize` solutions by ranking and crowding. The selection must return two parents: `es.unex.jdisrest.operator.NaryTournamentSelection` does, jMetal's class of the same name does not.
2. Override `processComputedTask(task)` with the logic that integrates the solution into the population. It runs on the algorithm thread only, which is the only writer of `evaluations`. Change `population` inside `synchronized (population)`, because REST threads create tasks from it at the same time, and keep `populationSignatures` in step: `createNewTask()` uses it to reject duplicates, so add the key of every solution that enters and remove that of every solution that leaves (`solutionKey(s)`), or call `rebuildPopulationSignatures()` inside the lock.
3. Optionally override `createNewTask()` to customize the generation strategy. It runs on REST threads: read `crossover` and `mutation` inside `synchronized (population)`, which `setVariation` also takes. It may queue a spare offspring in `pendingTaskQueue`. Take the children with the protected static `child(offspring, i)`, which gives the last child when a crossover returns fewer than `i + 1` and throws `IllegalStateException` when it returns none, and mutate copies, never the crossover's output itself. jMetal's `DifferentialEvolutionCrossover` does not fit here: it needs three parents and a current solution.
4. Optionally override `createInitialTasks()` for an algorithm that does not start from `populationSize` solutions; build them with `createInitialSolutions(count)` to keep the warm start and its copy into the traces (PAES asks for 1, and MOEA/D for `populationSize`, which it maps to its subproblems). An override that creates its solutions otherwise has no warm start.
5. If the algorithm keeps state about tasks in flight (for example MOEA/D's map from task to subproblem), override `onTaskDiscarded(task)` to release it: a task discarded after the failure limit never reaches `processComputedTask`. It runs on the REST thread that handled the failure, concurrently with the algorithm thread, so it must be thread-safe and fast; it need not call `super`, because the framework's own accounting does not depend on it. `MOEAD` is the in-tree example.
6. The stop and the start-up window need no code. `stoppingConditionIsNotMet()` already includes the stop, the end of `run()` and readiness (section 7.5); an override should combine its own condition with `super.stoppingConditionIsNotMet()`. A subclass that overrides `waitForComputedTask()` must return `null` once `isStopRequested()` is `true`, because no result arrives after a stop and a plain `take()` would hang `run()`; the default `run()` loop ends on a `null`. An override of `run()` must call `super.run()` or end its loop on a stop as well.
7. `setVariation`, `setTermination` and `getEvaluations` let a `ConfigurationHandler` change the operators and the budget of the running algorithm. Settings of your own need a method that checks every argument before it changes anything, under the population lock, as `PAES.reconfigure` and `MOEAD.reconfigure` do.
8. Keep the rules of the algorithm in a class without Spring (package-private unless users need it), as `PAESState`, `MOEADWeights`, `MOEADAggregation`, `TaskFailureTracker` and `StopRequest` do: the constructor of every master starts the REST server, so the master itself cannot be built in a unit test. Check the constructor arguments before calling `super(...)`, which starts the server, so that a wrong argument fails at once instead of leaving a live server behind (Java 25 allows statements before `super`): the program has no reference to the half-built master, so nothing can shut that server down.
9. The traces need no code either: `updateProgress()` calls the public `saveTrace()` after every processed result, and `run()` calls it once more when the loop ends, for the final snapshot (section 7.7). An override of `saveTrace()` is called that last time too. Override `populationTraceSnapshot()` instead to change what `VAR_<n>.csv` / `FUN_<n>.csv` hold (PAES writes its archive there).

```java
public class MyAlgo<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    public MyAlgo(String host, int port, Problem<S> problem,
                  int populationSize, CrossoverOperator<S> crossover,
                  MutationOperator<S> mutation, Termination termination,
                  String tracesFolder) {
        super(host, port, problem, populationSize, crossover, mutation,
              new NaryTournamentSelection<>(),       // es.unex.jdisrest.operator's: two parents
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

**Generational algorithms.** jdisrest ships no concrete generational algorithm; `GenerationalMaster` is the skeleton for one. Its default `run()` submits the initial tasks, waits for them, and then calls `evolution(population)` until the run is over, always with the same list, so `evolution` must update that list in place, submit the next generation with `submitTasks` and wait for it with `waitForEvaluatedTasks`. Call `submitTasks` exactly once per generation, with exactly `populationSize` tasks: the call resets the count of tasks discarded in the generation, and the wait ends when `populationSize` tasks have been evaluated or discarded. `waitForEvaluatedTasks()` can therefore return fewer than `populationSize` tasks, in completion order: one less for each task discarded after the failure limit, and only those already received when a stop is requested (it notices the stop within about a second). Do not index the results by position. The stop needs no code here either: `isFinished()` and the default `run()` loop check it. The default `run()` also ends, before the next `evolution()`, when its thread is interrupted, and leaves the interrupt flag set, so the caller can tell an interrupted run from a finished one.

### 7.7 Variable encodings and the wire

`es.unex.jdisrest.util.SolutionVariables` is the only place where a jMetal solution becomes a flat numeric vector or is rebuilt from one. `TaskController`, `RestWorker`, `PythonSolutionListEvaluator`, the duplicate filter (`solutionKey()`) and the composite trace writer all go through it.

- `flatten(solution)` copies the variables into a new `List<Number>`, concatenating composite segments in declaration order and keeping `Integer`/`Double` element types, so Jackson emits `5` for integer variables and `5.0` for real ones. A `null` variable, or one of another type than its segment's encoding, raises `IllegalArgumentException` with its position. Non-finite values are copied as they are (they are valid duplicate keys and trace values); `checkFinite(values)` tells whether a vector can travel as JSON numbers, and the controller fails a task whose vector cannot (section 2.2).
- `apply(solution, values)` writes a vector back. Each value is converted to the type of the destination variable (`intValue()` / `doubleValue()`), never trusting the type Jackson chose from the JSON text (`5` → `Integer`, `5.0` → `Double`, big values → `Long`). It validates first and writes afterwards, so a rejected vector leaves the solution untouched: wrong length, `null`, non-finite, non-integral for an integer variable (tolerance `1e-9`) and `int` overflow all raise `IllegalArgumentException` with the offending index. `convert(solution, values)` does the same conversion without writing.
- `checkBounds(solution, convert(solution, values))` checks a vector that comes back from outside, such as a Lamarckian repair, against the bounds of the variables it would overwrite: inclusive, without tolerance, for `IntegerSolution` and `DoubleSolution` variables and such segments of a composite; other solution types declare no bounds. It returns the reason or `null`; the controller (section 2.3) and the local evaluator ([Local mode](#local-mode)) reject such a vector. `apply` itself does not check bounds.
- `wireEncoding(solution)` / `segmentEncodings(solution)` produce the `encoding` and `segmentEncodings` fields of section 2.2. The controller omits both for all-integer solutions.
- Supported types: `IntegerSolution`, `DoubleSolution`, `CompositeSolution` of those (not nested). Any other solution is accepted only if its variables are all `Integer` or all `Double` at runtime (integer permutations); otherwise `IllegalArgumentException` names the class. `SteadyStateEvolutionaryAlgorithm.run()` performs this check on one `problem.createSolution()` before dispatching anything, so an unsupported encoding fails at start-up rather than once per task.

**Duplicate filter.** `SteadyStateEvolutionaryAlgorithm.solutionKey()` returns `flatten(solution)` and `populationSignatures` compares keys element by element. With integer encodings this rejects every duplicate offspring. With real encodings two independently generated vectors are practically never bit-identical, so the filter only catches exact clones — offspring on which neither crossover nor mutation acted, which are the duplicates real-coded evolution actually produces. The retry loop in `createNewTask()` therefore exits on the first iteration for most real-coded offspring and `MAX_DUPLICATE_RETRIES` is never reached. Nothing else changes; a tolerance-based comparison was deliberately not added because it would need a per-variable scale.

**Traces.** `CompositeSolutionListOutput` writes any number of segments of any supported encoding, one VAR row per solution: `v0 v1 ... vN-1,[obj...],[con...]`. Flat solutions still go through jMetal's `SolutionListOutput`.

With a traces folder, `saveTrace()` writes a snapshot every `populationSize × traceCadence` evaluations (`setTraceCadence(cadence)`, 1 by default; the product is computed in `long`): the archive to `aVAR_<n>.csv` / `aFUN_<n>.csv`, the distance-based subset of at most `populationSize` members the archive returns, infeasible ones included, and the population (`populationTraceSnapshot()`) to `VAR_<n>.csv` / `FUN_<n>.csv`, where `n` is the number of results processed. When the loop of `run()` ends normally (the termination is met, a stop is requested or the thread is interrupted), it writes a final snapshot whatever the count, unless that count was just written or no result was processed, so the traces end with the last state of the run even when the budget is not a multiple of the period. A run that throws writes no final snapshot, and a final snapshot that cannot be written is logged at ERROR (`Could not write the final trace snapshot (...) — the result is not affected`) without failing the run. The local NSGA-II writes the same files every `traceCadence` generations, and a final snapshot too. The final `aFUN_<n>.csv` falls between the regular ones, so a tool that only reads every `N` evaluations skips it.

---

## 8. Monitoring and control

Once the master is up, two endpoints report progress (`GET /api/v1/status` and `GET /api/v1/workers/status`), one reads and changes the configuration (`GET` and `POST /api/v1/config`) and one stops the run (`POST /api/v1/stop`). Like the rest of the protocol they have no authentication: anyone who can reach the master's port can change or stop the run. Spring Boot's `GET /actuator/health` also answers on the master's port (`{"status":"UP", ...}`), as a liveness check.

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

`evaluations` counts the results the master has accepted, those still queued for the algorithm included; `finished` is `true` once the stopping criterion is met or a stop has been requested. Before the master is ready (section 7.5) the run is reported as running and not finished. Once a stop has been requested or, with the bundled algorithms (`SteadyStateEvolutionaryAlgorithm` and its subclasses), the algorithm has ended its run, the master needs no more results: a result that arrives is refused with `404` and not counted, and `evaluations` counts only the results the algorithm used. The results accepted while it was still processing earlier ones and still queued at that point, which it never processes, are left out (they still show as `queuedResults` in `GET /api/v1/workers/status`). There are usually none or a few, but possibly many when the workers together deliver results faster than the algorithm processes them (cheap evaluations, many workers), so `evaluations` can drop at that point. A run that ends on its budget therefore reports `maxEvaluations`: the results the algorithm used, which `getEvaluations()` returns and `ConfiguredMaster` logs. `progress` is `evaluations / maxEvaluations`, clamped to [0, 1], and `estimatedSecondsRemaining` extrapolates it, `elapsedSeconds / progress × (1 − progress)`: it is never negative, `0` once the accepted results have reached the budget while the run has not finished yet, and `-1` until `progress` passes 1 %, once the run has finished, or before any time has elapsed. `elapsedSeconds` counts from `MasterFacade.init`, and is `0` before it. `discardedTasks` counts the tasks discarded after reaching the failure limit (section 2.3): any value above zero means some solutions could not be evaluated, and the master log names them (`[task-42] Failed evaluation 3 of 3 — discarded; its variables were [...]`). A `discardedTasks` that keeps growing while `evaluations` stays still means that every evaluation fails, for instance because the workers evaluate another problem. Fields added in later versions go last; Java clients that bind this JSON to a class with the ten fields of jdisrest 1.1 must ignore unknown properties (Jackson 2 fails on them by default); in the other direction, `StatusSnapshot` reads the JSON of a 1.1 master, which has no `discardedTasks`, as `0`.

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
    "worker-py-01": { "address": "node01", "currentTaskId": 1234, "lastSeen": "2026-10-02T10:41:07.512Z", "workerId": "worker-py-01" },
    "worker-py-02": { "address": "node02", "currentTaskId": -1, "lastSeen": "2026-10-02T10:41:09.033Z", "workerId": "worker-py-02" }
  }
}
```

The keys always come in this order, and the workers are sorted by id; the registry includes workers that may now be considered dead, until the watchdog removes them. `totalEvaluations` is the `evaluations` of `GET /api/v1/status`, and `queuedResults` the results waiting for the algorithm thread: after a stop or the end of the run the algorithm never processes them, and `totalEvaluations` leaves them out. `currentTaskId = -1` indicates an idle worker (waiting for a task or between evaluations): it returns to `-1` as soon as the worker's task leaves flight (its result is accepted, or it fails). `lastSeen` is the worker's last heartbeat, task request or accepted result, and `address` is `unknown` for a worker that asked for a task before its first heartbeat. Before a master exists every count is `0` and `workers` is empty.

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
| `503 Service Unavailable` | the master is not ready yet (`the master is not ready yet`: `run()` has not built its initial state, section 7.5); try again shortly |

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
// then write the result and call algorithm.shutdown(), as in section 5
```

A handler of your own implements `ConfigurationHandler`: `current()` returns the text `GET` serves, and `apply(text)` validates and applies a new configuration (it gets `""` for an empty body) and returns a one-line summary. Both run on REST worker threads, off the event loop, concurrently with the algorithm and with each other, so they must be thread-safe. `apply` reports a wrong configuration with an `IllegalArgumentException` (`422`) and a run that cannot take a change any more with an `IllegalStateException` (`409`), and must change nothing then; any other exception is a `500`. `MasterFacade.setConfigurationHandler(null)` disables the endpoints again.

### `POST /api/v1/stop`

Finishes the run now, as if the stopping criterion had been met, so that it ends in order instead of being killed:

```bash
curl -X POST http://10.0.0.1:8080/api/v1/stop
```

- No more tasks are handed out: workers get `410 Gone` on their next request and shut down. A request already being served when the stop lands may still receive one task. A worker still evaluating when the master has shut down (`ConfiguredMaster` shuts it down and exits as soon as it has written the result, at a stop as at a normal end) finds no master instead: it stops after 3 failed heartbeats 5 s apart, typically 10 to 25 s after the shutdown, or after 5 failed requests 10 s apart. The command-line Python worker started from the endpoint file then exits with 0, because the shutdown deleted the file (section 4.2).
- The results of the evaluations still in flight are refused with `404`, which both bundled workers log and move past, and are not counted in `evaluations`. Results accepted before the stop that the algorithm had not processed yet are dropped, and `evaluations` leaves them out from then on. A failure report counts nothing: the task is neither requeued nor discarded, and a result that fails validation gets `404` rather than `422` (section 2.3).
- `run()` returns with the current result, exactly as at a normal end: a steady-state master stops waiting within 200 ms, a generational one within about a second, with the part of the generation it has. The caller writes the result: `ConfiguredMaster` writes `VAR.csv` and `FUN.csv` and exits with status 0; a program of your own does it as after a normal finish, for instance with `TraceWriter.write(algo.getResult(), "VAR.csv", "FUN.csv", ",")`. A stop before the first result leaves the result archive empty, and `getResult()` then returns an empty list. The steady-state algorithms write a final trace snapshot when `run()` returns (section 7.7), so the traces folder ends with the state the result comes from.

The response is `202 Accepted` with the `/api/v1/status` snapshot, already with `"finished": true`, or `503 Service Unavailable` if no master is running. Repeated requests are harmless; the master logs the first one:

```
Stop requested — finishing with the current result and discarding the 3 evaluations in flight
```

The stop does not end the process: the REST server and the `status.json` writer keep running until the program calls `shutdown()` (section 5) or exits. After a stop, `pendingTasks` can stay above zero, since tasks still queued are never handed out (after a stop nothing is requeued, neither by a late `/error` nor by the watchdog), and `POST /api/v1/config` answers `409`.

The endpoint calls `AbstractMaster.requestStop()`. `isFinished()` includes the request, so the workers are sent away whatever the algorithm's stopping condition says; `SteadyStateEvolutionaryAlgorithm`, the base of the bundled algorithms, includes it in `stoppingConditionIsNotMet()`, and the default `run()` loops of both `SteadyStateAlgorithm` and `GenerationalAlgorithm` end on it. Custom algorithms need no code of their own unless they override the waits (section 7.6).

### Startup and shutdown

The REST server starts in the algorithm's constructor, and every endpoint answers from then on. Until `run()` has built the initial state (`isReady()`, section 7.5):

- `GET /api/v1/tasks/next` answers `204` at once, and the workers ask again 5 s later;
- `GET /api/v1/status` and `status.json` report `"running": true`, `"finished": false`, with zero progress;
- `GET /api/v1/workers/status` works, and heartbeats register workers;
- `POST /api/v1/config` answers `503` (`501` until the program registers a handler);
- `POST /api/v1/stop` is taken: the run then ends as soon as it starts, and the workers get `410`.

`shutdown()` (section 5) ends the master: a run still going is stopped as by `POST /api/v1/stop`, `.master-endpoint` is deleted (if this master wrote it and no other master has replaced it), the server closes at once, cutting off the requests being served, so the workers get connection errors from then on and stop (section 4), and `status.json` gets a last snapshot with `"finished": true`. The master logs `Master endpoint <file> deleted`, `REST server closed`, and `Stop requested` only when the run was still going.

### `status.json` file

If `MasterFacade.init(maxEvals, statusFileIntervalSec)` is called with an interval > 0, the master writes the same payload as `/api/v1/status` to `status.json` under `-Djdisrest.dataPath` (the folder is created if needed), atomically, so a reader never sees a partial file. Useful when the worker nodes have access to a shared filesystem but not to the compute node's network (the typical case on HPC clusters with login nodes). `ConfiguredMaster` writes it every 30 s. To follow the Pareto front of a run rather than its counters, `python/tools/watch_front.py` reads the traces folder (see [`python/README.md`](../python/README.md)).

The first write happens at once, inside `init`, and reports the run as running even though `run()` has not started yet (see [Startup and shutdown](#startup-and-shutdown)). A snapshot that cannot be built (for instance a stopping criterion that throws) and a file that cannot be written are each logged once, as `Could not build the status for status.json (further failures will be silent): ...` and `Could not write status.json (further failures will be silent): ...`, so a `dataPath` that cannot be written goes unreported after that warning: check that `status.json` appears. `shutdown()` writes a last snapshot, with `"finished": true`, and stops the writer, so the file ends with the final state of the run; the snapshot is taken after the REST server has closed and, with the bundled algorithms or after a stop, its `evaluations` are those the algorithm used (see `GET /api/v1/status`).

---

## 9. Changes in 1.2

What behaves differently from jdisrest 1.1. The task payload of an integer problem is still byte for byte the one of 1.0, the result body of a worker that changes no variable is unchanged, and new JSON fields are only added.

**Protocol and master**

- A task is discarded after 3 failed evaluations (`setMaxTaskFailures`) instead of being requeued forever, so a generational master can receive fewer than `populationSize` results per generation (section 7.6). `discardedTasks` is a new, last field of `GET /api/v1/status` and `status.json` (sections 2.3 and 8): Java clients that bind the status JSON to a copy of the 1.1 `StatusSnapshot` record must ignore unknown properties (Jackson 2 fails on them by default), and JSON written by 1.1 reads as `discardedTasks` `0`.
- New endpoints: `POST /api/v1/stop` and `GET`/`POST /api/v1/config` (section 8).
- Until the algorithm is ready, `GET /api/v1/tasks/next` answers `204` instead of `500`, `GET /api/v1/status` and `status.json` report the run as running, and `POST /api/v1/config` answers `503` (sections 2.2 and 7.5).
- A worker that asks for a task while its previous one is still in flight gets that one requeued, without a counted failure: worker ids must be unique per evaluation slot (sections 2.1 and 2.2).
- A failure report (`/error`, or a `422`) from a worker that no longer holds the task is ignored; a result without `workerId` is accepted; a result is written into the solution only after its task has left flight (sections 2.3 and 2.4).
- Lamarckian `variables` outside the bounds get `422` (section 2.3).
- A task whose payload cannot be built (a `null`, `NaN` or infinite variable, or any other exception) answers `500` and counts as a failed evaluation, where 1.1 sent the bad values as JSON strings or stranded the task (section 2.2).
- Request bodies up to 16 MiB (Spring's default was 256 KiB), and every Spring property can be overridden (section 5).
- The constructor throws when the REST server cannot start, instead of logging the failure and going on, and writes no `.master-endpoint`; Spring starts on the constructor's thread. A blank or wildcard host is replaced by a routable address, port `0` takes a free port, the `jdisrest.dataPath` folder is created, and `.master-endpoint` is written by Jackson, with an IPv6 address in brackets in its `url` (section 5).
- New `shutdown()`: it closes the server (at once: `server.shutdown=immediate`), writes a final `status.json` and deletes `.master-endpoint`; `ConfiguredMaster` calls it (section 5).
- Task handlers run on virtual threads instead of `boundedElastic` (section 7.3). `GET /api/v1/workers/status` has a fixed key order, sorted workers and no `500` without a master; the ETA is never negative; `currentTaskId` returns to `-1` whenever a task leaves flight; `status.json` logs snapshot and write failures separately (section 8).
- `failInFlightTask` replaces `requeueInFlightTask` (deprecated), and the REST layer calls `failInFlightTask(taskId, workerId)` and `submitResult(taskId, workerId, recorder)`: overrides of `requeueInFlightTask` and of the two-argument `submitResult(taskId, workerId)` no longer see the workers' reports, while `failInFlightTask(long)` is still called for every accepted failure (section 7.2).
- New extension points for masters and algorithms of your own: `onTaskDiscarded(task)`, to release the state kept about a task discarded after the failure limit (section 7.6); `requestStop()` and `isStopRequested()`, the stop behind `POST /api/v1/stop` ([`POST /api/v1/stop`](#post-apiv1stop) and section 7.6); `isReady()`, which keeps the workers waiting until `run()` has built its initial state (section 7.5 and [Startup and shutdown](#startup-and-shutdown)); `shutdown()` (section 5 and [Startup and shutdown](#startup-and-shutdown)); `populationTraceSnapshot()`, what the population traces hold (sections 7.6 and 7.7); and a `ConfigurationHandler`, registered with `MasterFacade.setConfigurationHandler`, which serves `GET`/`POST /api/v1/config` ([`GET` and `POST /api/v1/config`](#get-and-post-apiv1config)).
- `SolutionVariables`: a custom flat solution must hold only `Integer` or only `Double` variables, and `flatten` rejects `null` or foreign-typed variables (section 7.7).

**Algorithms**

- `getResult()` returns an empty list for an empty archive instead of throwing (every algorithm, and the local NSGA-II's `result()`).
- A final trace snapshot is written when the run ends; the trace period is computed in `long` (section 7.7).
- The external archive keeps copies of the results. A crossover that returns one child is applied to a second, independently selected pair for the second offspring; one that returns none fails with `IllegalStateException`. The duplicate-retry warning appears only when the last attempt was still a duplicate. New: `isReady()`, `observable()`, `child()`, `secondChild()`, `createInitialSolutions`, `setVariation`, `setTermination` and `getEvaluations`; `attributes` is a `ConcurrentHashMap` and rejects `null` values (sections 5 and 7.5).
- SMS-EMOA ranks with constraint-aware dominance, drops duplicates on arrival and survives a constant objective (section 5).
- MOEA/D: deterministic `SPREAD` or `LATTICE` weights in `weights.csv`, Tchebycheff's 10⁻⁴ for zero weights, optional normalization, the warm start, slot filling, `JMetalRandom`, a feasible-only result, random tasks instead of `500` before the first result, and argument checks before the server starts ([MOEA/D](#moead)). For subclasses, the protected fields `agg` and `idealPoint` are gone, replaced by `aggregation` (a `MOEADAggregation`, which holds the aggregation function and the ideal and nadir points), and the protected `random` is deprecated and no longer used: every draw comes from `JMetalRandom`.
- New distributed PAES ([PAES](#paes)).
- Local NSGA-II: constraint-aware replacement, a population size checked against the crossover, children copied before mutation, a final trace snapshot, a protected `saveTrace()` ([Local mode](#local-mode)).
- The default generational `run()` ends when its thread is interrupted (section 7.6).

**Operators** ([Extra operators](#extra-operators))

- Seeded runs draw differently: `IntegerSimpleRandomMutation` and `IntegerGaussianMutation` take every random number from the injected generator (`IntegerGaussianMutation` used an unseeded `java.util.Random`), `IntegerBLXCrossover` rounds instead of truncating, and `NaryTournamentSelection`, which the distributed NSGA-II and SMS-EMOA use, draws `tournamentSize` candidates per tournament instead of a whole permutation.
- `IntegerBLXCrossover` accepts variables with equal bounds and checks its setters; the integer mutations accept any `int` range; `IntegerSimpleRandomMutation`, `IntegerGaussianMutation` and `RandomMutationWithRandomProbability` reject a `NaN` probability and name the rejected value.
- The jitter of `RandomMutationWithRandomProbability` and `PolynomialMutationRandomProbability` is a constructor parameter.
- `NaryTournamentSelection` validates its arguments; `DifferentialEvolutionSelection` no longer loops forever on a population that is too small; `SafeCompositeCrossover` checks the number of segments and offers `getOperators()`.
- New: `DoubleNPointCrossover` and `LevyFlightMutationRandomStepSize`.

**Local evaluation** ([Local mode](#local-mode))

- `PythonProcessEvaluator`: only a non-empty string `error` fails a call; a line that is not a JSON object is an `IOException` that quotes it; after a desynchronisation, a time-out, an interrupt or `close()` every call fails at once; optional time-out, script arguments and working directory; a failed handshake destroys the child; `close()` kills the whole process tree after 10 s; a `null` or non-finite decision is an `IllegalArgumentException` before anything is sent.
- `PythonSolutionListEvaluator`: repaired variables outside the bounds are rejected; a third constructor takes the applier matching a custom extractor; `null` arguments fail at construction.

**Workers** (section 4)

- Both clients: a heartbeat answered with a status other than `2xx` fails; only a `204` or an answered task report resets the count of failed requests, so a master that hands out tasks but fails every result stops the worker; `413` and `415` on `/result` are rejections.
- `RestWorker`: unusable tasks and non-finite results go through `/error`; the segment layout is checked; changed variables are sent back; the status of `/error` is checked; a 5 s pause after two or more failed tasks in a row; timeouts derived from `Timings`; a worker-id constructor and `getWorkerId()`; `run()` runs once; a `null` URL or problem fails at construction.
- Python `Worker`: HTTP errors and tasks without an integer `taskId` count as failed requests (1.1 cleared the count before checking the status, so a master answering `5xx` kept it polling forever); failed heartbeats are retried after 5 s instead of 15 s; the `errorMessage` carries the exception type; `run()` returns why it stopped (a worker created from an endpoint file that the master's shutdown deleted counts as finished) and can run again; any object with an `evaluate` method, sequences and mappings are accepted; an empty objectives list is reported through `/error`; malformed tasks and lost results are reported through `/error`; evaluator tracebacks are logged; back-off after evaluation errors in a row; heartbeats on their own session.
- New command-line worker `python -m jdisrest` (`jdisrest-worker`), which exits with 3 when it gives the master up as lost while the endpoint file it was started from is still there; new trace tools `watch_front.py` and `plot_front_evolution.py` ([`python/README.md`](../python/README.md)).

**Configuration** (section 5)

- New: configuration files for NSGA-II, PAES and MOEA/D (`es.unex.jdisrest.config`), the `ConfiguredMaster` launcher with `--check`, changes during a run through `POST /api/v1/config`, and the record of the configuration in the traces folder; the warm-start file `iVAR.csv` is copied into the traces folder.

---

## 10. Changes in 1.2.1

What behaves differently from jdisrest 1.2.0.

**Protocol and master**

The changes at the end of a run apply to the bundled algorithms (they extend `SteadyStateEvolutionaryAlgorithm`); a master of your own that does not extend it gets them only after a stop.

- A result that arrives after a steady-state run has ended on its stopping criterion gets `404` and is not counted, as after `POST /api/v1/stop`. 1.2.0 accepted and counted it although nobody processed it, so `evaluations` in `GET /api/v1/status` and `status.json` could end above `maxEvaluations` by up to one more result per worker still evaluating at the end (sections 2.3 and 8).
- After a stop or the end of the run, `evaluations` in `GET /api/v1/status` and `status.json`, and `totalEvaluations` in `GET /api/v1/workers/status`, count only the results the algorithm used. 1.2.0 also counted the results still queued at that point, which the algorithm never processes, so with cheap evaluations and many workers a run could end far above `maxEvaluations`; now one that ends on its budget reports `maxEvaluations`, and `evaluations` (and `progress`) can decrease when the run is stopped or ends, by the results left out. Those still show as `queuedResults`, including one the algorithm was taking at the very moment of a stop, which 1.2.0 took out of the queue (section 8).
- After a stop or the end of the run, a failure report counts nothing: after a `POST /error`, or a `400`, `413` or `415` answer to a result, the task leaves flight without being requeued or discarded, and a result that fails validation gets `404` instead of `422`. 1.2.0 counted them as failed evaluations, requeueing the task into a queue nobody served, or discarding it with an ERROR line, a call to `onTaskDiscarded` and one more `discardedTasks`. The watchdog no longer requeues the task of a silent worker then either (sections 2.3, 2.4 and 7.4).
- A steady-state master hands out no task once the algorithm has ended its run, as after a stop: 1.2.0 could still give a worker that asked at that moment one of the tasks left in the queue, whose result was then accepted and counted although nobody processed it (a wasted evaluation). That worker now gets `204`, and `410` on its next request. A generational master returns at once after a stop, without taking a queued task or waiting for the long-poll (sections 7.3 and 7.5).
- New `AbstractMaster.needsNoMoreResults()`, `true` once a stop has been requested or the algorithm has ended its run, which decides all of the above (section 7.2).

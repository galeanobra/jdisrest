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
6. [SLURM deployment patterns](#6-slurm-deployment-patterns)
7. [Internals](#7-internals)
8. [Monitoring](#8-monitoring)

---

## 1. Architecture

```
┌───────────────────────────────────────────────────────────────┐
│  MASTER process  (Java + Spring Boot WebFlux)                 │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  Evolutionary algorithm (NSGA-II, MOEA/D, SMS-EMOA…)    │  │
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
│  │  GET  /api/v1/tasks/next          ← worker requests task│  │
│  │  POST /api/v1/tasks/{id}/result   ← worker submits      │  │
│  │  POST /api/v1/workers/heartbeat   ← worker still alive  │  │
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
3. The worker requests a task: `GET /tasks/next` with long-polling of up to 30 s.
4. The master responds with a `taskId` + vector of integer variables.
5. The worker evaluates the objective function (this can take minutes or hours).
6. The worker returns the result: `POST /tasks/{id}/result` with objectives and constraints.
7. The master integrates the result into the population and generates the next task.
8. When the stopping criterion is met, the master responds `410 Gone` to the workers.

### Key concepts

| Concept | Description |
|---|---|
| **Task** | A `(taskId, variables[])` pair. Created by the master, evaluated by a worker. |
| **Long-polling** | The worker waits up to 30 s for a task to become available, without saturating the network with active polling. |
| **Heartbeat** | The worker signals every 15 s that it is still alive, on a thread separate from the evaluation one. |
| **Watchdog** | The master checks every 30 s which workers have not sent a recent heartbeat and requeues their tasks. |
| **InFlight** | Tasks assigned but not yet returned. If the worker dies, they are requeued. |
| **Steady-state** | The master creates a new task as soon as it receives a result, without waiting for the whole generation. This is the default mode. |

---

## 2. REST protocol

Three calls. That is all any worker, in any language, needs to implement.

### 2.1 `POST /api/v1/workers/heartbeat`

Tells the master the worker is still active. Also acts as the initial registration.

```
POST http://<master>:<port>/api/v1/workers/heartbeat
     ?workerId=<unique-id>
     &address=<worker-hostname>     // optional; diagnostics only

Response: 200 OK  (no body)
```

Send every 15 s on a separate thread. If the master receives no heartbeat within 45 s, it declares the worker dead and requeues its task.

### 2.2 `GET /api/v1/tasks/next`

Requests the next task. The master long-polls for up to 30 s.

```
GET http://<master>:<port>/api/v1/tasks/next?workerId=<unique-id>

Possible responses:
  200 OK   → a task is available
  204 No Content → no task right now (retry in a few seconds)
  410 Gone → algorithm finished, the worker must stop
```

Body of the 200 response (JSON):

```json
{
  "taskId": 42,
  "variables": [3, -17, 55, 0, 81, ...],
  "segmentSizes": [3249, 3249, 3249]
}
```

- `variables`: flat vector of integers. For `CompositeSolution` it is the concatenation of the segments `[seg0 | seg1 | ...]`.
- `segmentSizes`: **only present for composite problems** (`CompositeSolution`): number of variables in each segment, so the worker can reconstruct the boundaries. For `IntegerSolution` the field is omitted from the JSON.

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
  200 OK        → result accepted
  404 Not Found → the watchdog already requeued this task (worker took too long)
```

- `objectives`: list of **doubles** with the value of each objective. jMetal minimizes; to maximize, negate.
- `constraints`: list of doubles. jMetal convention: `<= 0` satisfied, `> 0` violated. Empty list if there are no constraints.
- `evaluationTimeMs`: optional, for statistics.
- `variables`: **optional**. If the evaluator "repairs" the solution (Lamarckian search), it can return the repaired vector here and the master will overwrite the original solution before archiving the result. Omit if the variables are not modified.

> The 404 is not a critical error — it means the watchdog requeued the task due to a timeout. The worker should log a warning and continue.

### 2.4 `POST /api/v1/tasks/{taskId}/error`

Reports an evaluation failure (the worker hit an exception while evaluating). Makes the master requeue the task immediately instead of waiting for the watchdog.

```
POST http://<master>:<port>/api/v1/tasks/42/error
Content-Type: application/json

{ "workerId": "worker-py-01", "errorMessage": "ZeroDivisionError: ..." }
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

> Extend `AbstractIntegerProblem` because jdisrest works with integer variables. For multi-objective: `numberOfObjectives(2)` and fill in `solution.objectives()[0]` and `[1]`.

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

The algorithm invokes `createInitialPopulationFromFile(populationSize)` instead of jMetal's random initialization when **two** conditions are met: `iVAR.csv` exists in the working directory **and** the problem implements `es.unex.jdisrest.distributed.WarmStartCapable<S>`. If `iVAR.csv` exists but the problem does not implement the interface, a warning is logged and random initialization is used. Typical file convention: one solution per line, comma-separated variables; the implementation must top up with random solutions until the population is full.

### Composite problems

For problems with heterogeneous segments (e.g. three independent sets of variables with different bounds), implement `Problem<CompositeSolution>` directly. jMetal provides `CompositeCrossover` and `CompositeMutation`, but there is a latent aliasing bug — use the defensive wrapper `es.unex.jdisrest.operator.SafeCompositeCrossover` (drop-in replacement).

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

### 4.2 Python worker

The `jdisrest` package (installable with `pip install -e <path-to-jdisrest>/python`) provides the `Worker` class with the same management as `RestWorker`.

```python
from jdisrest import Worker, EvalResult

def evaluate(variables: list[int]) -> EvalResult:
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
                    objectives = sum(variables .^ 2);     % f(x) = Σxᵢ²
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
    payload = struct('workerId', workerId, 'objectives', objectives, ...
                     'constraints', [], 'evaluationTimeMs', elapsedMs);
    uri = URI([masterUrl '/api/v1/tasks/' num2str(taskId) '/result']);
    req = RequestMessage('POST', ContentTypeField('application/json'), MessageBody(payload));
    req.send(uri, HTTPOptions('ResponseTimeout', 15));
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
            "0.0.0.0", 8080,
            problem, /*popSize=*/ 50,
            crossover, mutation, termination,
            /*tracesFolder=*/ null);

        MasterFacade.init(5000, /*statusFileIntervalSec=*/ 30);
        algo.run();

        algo.getResult().forEach(s ->
            System.out.println("f=" + s.objectives()[0] + "  vars=" + s.variables()));
    }
}
```

`MasterFacade.init(maxEvals, statusFileIntervalSec)` starts the periodic `status.json` writer and records the start time for progress/ETA computation. Call it right before `algo.run()`.

### Available algorithms

- `es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII` — tournament + ranking/crowding.
- `es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD` — weight-based decomposition.
- `es.unex.jdisrest.distributed.algorithms.steadystate.SMSEMOA` — hypervolume.
- `es.unex.jdisrest.local.algorithms.NSGAII` — sequential local variant (no REST), useful for debugging small problems or measuring distribution overhead. Uses the `PythonProcessEvaluator` / `PythonSolutionListEvaluator` evaluators from the `es.unex.jdisrest.local` package.

### Extra operators

Under `es.unex.jdisrest.operator.*`:

- `IntegerSimpleRandomMutation`, `IntegerGaussianMutation`, `IntegerBLXCrossover`, `PolynomialMutationRandomProbability`, `RandomMutationWithRandomProbability`: variants specific to integer variables.
- `NaryTournamentSelection`: n-ary tournament selection.
- `DifferentialEvolutionSelection`: usable with MOEA/D-DE on `DoubleSolution` problems (not compatible with `IntegerSolution`).
- `SafeCompositeCrossover`: wrapper around `CompositeCrossover` that avoids a latent aliasing bug in jMetal — drop-in replacement, same constructor signature.

---

## 6. SLURM deployment patterns

The general pattern:

1. Build the consumer JAR (fat-jar with jdisrest inside).
2. Submit the master job (`sbatch master.sh`).
3. The master starts and atomically writes `.master-endpoint` with `{host, port, url}` to a directory shared by all nodes.
4. Submit the worker array (`sbatch --array=0-N worker.sh`).
5. Each worker waits for `.master-endpoint` to exist (typically up to 5 min) and reads the URL.
6. When finished, collect the results (`VAR.csv`, `FUN.csv`, `log.tar.gz`).

```json
// .master-endpoint
{ "host": "10.0.0.1", "port": 55312, "url": "http://10.0.0.1:55312" }
```

Outline of a minimal SLURM `master.sh` (copy the yaml to local scratch and patch it there, not in the repo):

```bash
#!/bin/bash
#SBATCH ...
set -euo pipefail
module load java/jdk-25

cp config/master.yaml jar.jar "$SCRATCH/"
cd "$SCRATCH"

DIR_IP=$(ip addr show ib0 | awk '/inet / {sub(/\/.*/, "", $2); print $2}')
PORT=$((RANDOM % 10001 + 50000))
sed -i "s/^  host:.*/  host: \"${DIR_IP}\"/" master.yaml
sed -i "s/^  port:.*/  port: ${PORT}/"        master.yaml

java -Djdisrest.dataPath="$SHARED_DIR" -jar jar.jar master.yaml
```

Outline of a `worker.sh`:

```bash
#!/bin/bash
#SBATCH ... --array=0-99
ENDPOINT_FILE="$SHARED_DIR/.master-endpoint"

# Wait for the master (up to 5 min)
until [[ -f "$ENDPOINT_FILE" ]]; do sleep 5; done
MASTER_URL=$(python3 -c "import json; print(json.load(open('$ENDPOINT_FILE'))['url'])")

python -u worker.py \
    --master "$MASTER_URL" \
    --worker-id "worker-${SLURM_JOB_ID}-${SLURM_ARRAY_TASK_ID}"
```

`-Djdisrest.dataPath=<dir>` controls where the master writes `.master-endpoint` and `status.json`; that directory must be visible to the worker nodes.

---

## 7. Internals

### 7.1 Class hierarchy

```
AbstractMaster<T, R>
│  pendingTaskQueue    — pending tasks (BlockingQueue)
│  completedTaskQueue  — evaluated tasks (BlockingQueue)
│  inFlightTasks       — assigned tasks (ConcurrentHashMap<taskId, task>)
│  workerRegistry      — known workers and their last heartbeat
│
├── GenerationalMaster<T, R>           — batch algorithms (dispatch a generation, wait for all)
│      implements GenerationalAlgorithm<T, R>   (interface with the generational loop)
│
└── SteadyStateMaster<T, R>            — on-demand algorithms (new task per result)
       implements SteadyStateAlgorithm<T, R>    (interface with the steady-state loop)
    └── SteadyStateEvolutionaryAlgorithm<S>
        ├── algorithms.steadystate.NSGAII<S>
        ├── algorithms.steadystate.MOEAD<S>
        └── algorithms.steadystate.SMSEMOA<S>
```

The protocol timing constants (heartbeat 15 s, worker timeout 45 s, watchdog cycle 30 s, long-poll 30 s) are centralized in `es.unex.jdisrest.util.Timings`. If any of them changes, the equivalent worker-side constants must be updated too (`Worker` in Python, `RestWorker` in Java).

`MasterFacade` (a static class) is the bridge between the Spring beans (controllers, watchdog) and the active master instance. Beans never import `SteadyStateMaster` or `GenerationalMaster` directly; they call `MasterFacade.claimNextTask()`, `submitResult()`, etc., which delegate to the active instance.

### 7.2 Life cycle of a task

```
Master                                  REST API                  Worker
──────                                  ────────                  ──────
createNewTask()
  → ParallelTask(id, solution)
  → pendingTaskQueue.add(task)

                                  GET /tasks/next
                            ←──────────────────────── (long-poll 30s)

claimNextTask(workerId)
  ← pendingTaskQueue.poll(30s)
  → inFlightTasks.put(taskId, task)
  → workerRegistry[workerId].currentTaskId = taskId

                                  200 OK {taskId, variables}
                            ──────────────────────────────►

                                                          variables = task.variables
                                                          f = evaluate(variables)

                                  POST /tasks/{id}/result
                            ←────────────────────────────

TaskController.submitResult():
  ← inFlightTasks.get(taskId)
  → writes objectives into solution
  → MasterFacade.submitResult()
     → inFlightTasks.remove(taskId)
     → completedTaskQueue.add(task)

processComputedTask(task)
  ← completedTaskQueue.take()
  → population.add(solution)
  → ranking + crowding
  → createNewTask()  [starts over]
```

### 7.3 Server-side long-polling

`TaskController.getNextTask()` uses `Mono.fromCallable()` on the `boundedElastic` scheduler so it does not block the Netty event loop:

```java
return Mono.fromCallable(() -> MasterFacade.claimNextTask(workerId, 30))
           .subscribeOn(Schedulers.boundedElastic())
           .map(task -> task == null
               ? ResponseEntity.noContent().build()
               : ResponseEntity.ok(new TaskPayload(...)));
```

`SteadyStateMaster.claimNextTask()` blocks for up to 30 s with `pendingTaskQueue.poll(timeout, SECONDS)`. If no task arrives in that time, it returns `null` → the controller responds `204`.

### 7.4 Watchdog

`WatchdogScheduler` runs every 30 s (`Timings.WATCHDOG_INTERVAL_MS`) via `@Scheduled`:

```java
@Scheduled(fixedDelayString = "#{T(es.unex.jdisrest.util.Timings).WATCHDOG_INTERVAL_MS}")
public void checkDeadWorkers() {
    MasterFacade.requeueOrphanTasks(Timings.WORKER_TIMEOUT_S);   // 45 s without heartbeat
}
```

`requeueOrphanTasks()` looks for workers whose `lastSeen` exceeds the threshold, moves their tasks from `inFlightTasks` back to `pendingTaskQueue`, and removes the worker from the registry. If the original worker ends up delivering the task anyway, it will receive a `404` and should log a warning without retrying.

### 7.5 Steady-state synchronization

`SteadyStateMaster.claimNextTask()` uses double-checking with `synchronized` to prevent two concurrent requests from generating duplicate tasks:

```java
synchronized (taskCreationLock) {
    T task = pendingTaskQueue.poll();
    if (task == null && stoppingConditionIsNotMet()) {
        task = createNewTask();
    }
    return task;
}
```

### 7.6 Adding an algorithm

1. Extend `SteadyStateEvolutionaryAlgorithm<S>`.
2. Implement `processComputedTask(task)` with the logic that integrates the solution into the population.
3. Optionally override `createNewTask()` to customize the generation strategy.

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

    @Override
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        @SuppressWarnings("unchecked")
        S sol = (S) task.getContents().copy();
        archive.add(sol);
        synchronized (population) {
            population.add(sol);
            if (population.size() > populationSize) {
                population.remove(worstOf(population));
            }
        }
    }
}
```

---

## 8. Monitoring

Two endpoints, once the master is up:

### `GET /api/v1/status`

Lightweight snapshot with global progress. Meant for `monitor.py` and external dashboards.

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
  "pendingTasks": 0
}
```

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

### `status.json` file

If `MasterFacade.init(maxEvals, statusFileIntervalSec)` is called with an interval > 0, the master writes the same payload as `/api/v1/status` to `status.json` under `-Djdisrest.dataPath`. Useful when the worker nodes have access to a shared filesystem but not to the compute node's network (the typical case on HPC clusters with login nodes).

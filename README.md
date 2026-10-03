# jdisrest

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Python](https://img.shields.io/badge/Python-%E2%89%A53.11-blue)
[![GitHub release](https://img.shields.io/github/v/release/galeanobra/jdisrest)](https://github.com/galeanobra/jdisrest/releases)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21454186.svg)](https://doi.org/10.5281/zenodo.21454186)

Distributed REST-based master/worker framework for jMetal-encoded
optimization problems.

**jdisrest** lets you run evolutionary algorithms (NSGA-II, MOEA/D,
SMS-EMOA, PAES) whose objective-function evaluations are farmed out to
remote worker processes over plain HTTP. The master (Java, Spring Boot
WebFlux) owns the algorithm and the population; workers only need four
REST endpoints (heartbeat, next task, result and error report), so they
can be written in Java, Python, MATLAB, or anything else that can make an
HTTP request. Further endpoints let you follow a run (`/api/v1/status`),
stop it early while keeping its result (`/api/v1/stop`) and, on masters
that support it, change its settings while it runs (`/api/v1/config`).
This makes it straightforward to distribute expensive evaluations, such
as external simulators, trained models, or legacy code, across a cluster
(including SLURM-managed HPC nodes) without coupling the algorithm
implementation to the evaluation language.

Problems may use integer variables (`IntegerSolution`), real variables
(`DoubleSolution`) or a `CompositeSolution` that mixes both; the decision
vector travels to the workers as a flat list of JSON numbers whatever the
encoding.

The algorithmic core (NSGA-II, MOEA/D, SMS-EMOA, PAES, solution
encodings, and several operators) is built on top of
[**jMetal**](https://github.com/jMetal/jMetal). jdisrest runs each
algorithm as a steady-state loop in a distributed, REST-based execution
model and builds it from jMetal's components (encodings, operators,
ranking, archives, hypervolume); the selection and replacement rules of
PAES and the aggregation functions of MOEA/D are re-implemented after
jMetal's for that loop. See [Acknowledgments](#acknowledgments) for how
to cite jMetal itself.

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installing](#installing)
- [Quick start](#quick-start)
- [Repository layout](#repository-layout)
- [Key classes](#key-classes)
- [Documentation](#documentation)
- [Citing this software](#citing-this-software)
- [Publications using jdisrest](#publications-using-jdisrest)
- [Acknowledgments](#acknowledgments)
- [Authors](#authors)
- [License](#license)

## How it works

```
+----------------------------------+
|  MASTER (Java + Spring Boot)     |
|  algorithm + population          |
+----------------+-----------------+
                 |
      REST API over HTTP / JSON
                 |
   +-------------+-------------+
   |             |             |
   v             v             v
   Java worker   Python worker MATLAB worker
```

*(or any other HTTP client)*

1. The master starts and waits for workers to register via heartbeat.
2. Each worker long-polls `GET /api/v1/tasks/next` for a solution to
   evaluate.
3. The worker evaluates it (however long that takes) and posts the
   result back to `POST /api/v1/tasks/{id}/result`, or reports a failed
   evaluation to `POST /api/v1/tasks/{id}/error`. The master requeues a task
   whose evaluation failed (an error report, or a result it rejects), and
   discards it after three failed evaluations
   (`AbstractMaster.setMaxTaskFailures` changes the limit).
4. The master integrates the result into the population and repeats
   until the stopping criterion is met or a `POST /api/v1/stop` ends the
   run early. From then on workers get `410 Gone` and exit.

See [`docs/DEVELOPER_MANUAL.md`](docs/DEVELOPER_MANUAL.md) for the full
REST protocol, how to define problems, worker implementations in
Java/Python/MATLAB, master wiring and configuration files, SLURM
deployment patterns, monitoring and control, and internals.

## Requirements

- **Java 25** and Maven (master / Java workers)
- **Python ≥ 3.11** (Python workers)
- For the trace tools in `python/tools`: Python 3.7 or later
  (`watch_front.py`, standard library only), or numpy and matplotlib
  (`plot_front_evolution.py`)

## Installing

### Java (Maven)

```
git clone --branch v1.2.1 https://github.com/galeanobra/jdisrest.git
cd jdisrest
mvn install        # deposits into ~/.m2/repository
```

`mvn test` runs the unit tests. `mvn verify`, and so `mvn install`, also
runs the integration tests (the `*IT` classes), each class in a JVM of its
own: they start a real master on a free port and talk to it over HTTP.

Consumers reference it via:

```xml
<dependency>
    <groupId>es.unex</groupId>
    <artifactId>jdisrest</artifactId>
    <version>1.2.1</version>
</dependency>
```

### Python (pip)

```
cd jdisrest/python
pip install .
```

To work on the client itself, install it in editable mode instead
(`pip install -e .`), so that changes to its sources take effect without
reinstalling.

The Python `jdisrest` package exposes `Worker`, `Evaluator`, and
`EvalResult` used by worker processes to connect to a running Java master,
and a command-line worker (`python -m jdisrest`, also installed as
`jdisrest-worker`) that evaluates with a function of your own module. See
[`python/README.md`](python/README.md) for both and for the trace tools in
`python/tools`.

## Quick start

A minimal Java master (see the developer manual for the full example,
including problem definition):

```java
var algo = new NSGAII<>(
    "10.0.0.1", 8080,   // address the workers reach the master at (it listens on every interface)
    problem, /*popSize=*/ 50,
    crossover, mutation, termination,
    /*tracesFolder=*/ null);

MasterFacade.init(5000, /*statusFileIntervalSec=*/ 30);
try {
    algo.run();
    // write algo.getResult() here
} finally {
    algo.shutdown();    // closes the REST server, so the JVM can exit
}
```

The constructor starts the REST server and writes the `.master-endpoint`
file; `shutdown()` deletes the file, closes the server (workers still
evaluating find the master gone and stop after a few failed attempts) and
writes a final `status.json`. Until `run()` has built its first
tasks, workers that ask for one are told to come back a few seconds later.

A matching Python worker:

```python
from jdisrest import Worker, EvalResult

def evaluate(variables) -> EvalResult:
    # ints for an integer-encoded problem, floats for a real-encoded one
    return EvalResult(objectives=[float(sum(x ** 2 for x in variables))])

Worker("http://10.0.0.1:8080").run(evaluate)
```

Use `localhost` for both when the master and the workers share one
machine. Each worker needs its own worker id (the bundled workers
generate a random one), because the master tracks one task in flight per
id.

### With a configuration file

For a real-coded problem (a jMetal `DoubleProblem` with a public
no-argument constructor) you need not write the master at all:
`es.unex.jdisrest.config.ConfiguredMaster` reads the algorithm (NSGA-II,
PAES or MOEA/D), the evaluation budget and the operators from a
properties file. The files in [`examples/`](examples) list every key with
its default. For instance, jMetal's ZDT1 (30 variables, 2 objectives)
with `examples/nsgaii.properties`, from a clone of this repository:

```bash
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"    # on Windows, separate with ; instead of :
PROBLEM=org.uma.jmetal.problem.multiobjective.zdt.ZDT1

# Validate the file for the problem, without starting anything
java -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster $PROBLEM \
    --check examples/nsgaii.properties maxEvaluations=5000

# Run the master; key=value arguments override the file
java -cp "$CP" es.unex.jdisrest.config.ConfiguredMaster $PROBLEM \
    10.0.0.1 8080 examples/nsgaii.properties maxEvaluations=5000
```

The workers can be the command-line worker of the Python package, which
calls a function of your own module. Save this as `zdt1.py`:

```python
import math

def evaluate(x):
    g = 1 + 9 * sum(x[1:]) / (len(x) - 1)
    return [x[0], g * (1 - math.sqrt(x[0] / g))]
```

and start as many workers as you like, on any machine that reaches the
master, from the folder that holds it:

```bash
python -m jdisrest --evaluator zdt1:evaluate --code-dir . \
    --master http://10.0.0.1:8080 --variables 30 --objectives 2
```

When the 5000 evaluations are done (or after
`curl -X POST http://10.0.0.1:8080/api/v1/stop`), the master writes its
result, the approximation of the Pareto front, to `VAR.csv` and `FUN.csv`
in its working directory, shuts down and exits, and the workers exit too.
The `traces` folder of the example holds snapshots of the population and
the archive, a copy of the configuration, and a record of every change
made with `POST /api/v1/config` during the run. `--check` and the run exit
with status 0 on success and 1 otherwise, so a script can gate a job on
them.

## Repository layout

```
jdisrest/
├── pom.xml                       # Maven library (es.unex:jdisrest:1.2.1)
├── src/main/java/es/unex/jdisrest/
│   ├── config/                   # Configuration files, launcher, runtime reconfiguration
│   ├── distributed/              # Master, algorithms, REST controllers
│   ├── local/                    # Sequential (non-REST) mode for debugging
│   ├── operator/                 # Custom jMetal operators
│   └── util/                     # Logging, timings, variable encodings, trace output
├── src/test/java/                # JUnit unit tests, and *IT integration tests (mvn verify)
├── examples/                     # Configuration files for NSGA-II, PAES and MOEA/D
├── python/
│   ├── pyproject.toml            # PEP 621 metadata
│   ├── jdisrest/                 # Worker-side Python package and command-line worker
│   ├── tools/                    # Trace tools (watch_front.py, plot_front_evolution.py)
│   └── tests/                    # pytest suite for the client and the tools
└── docs/
    └── DEVELOPER_MANUAL.md       # Developer manual
```

## Key classes

- `es.unex.jdisrest.distributed.AbstractMaster`: shared master infrastructure
  (Spring Boot startup and `shutdown()`, worker registry, watchdog, the
  limit of failed evaluations per task and the early stop).
- `es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm`: generic steady-state
  distributed evolutionary algorithm.
- `es.unex.jdisrest.distributed.algorithms.steadystate.{NSGAII,MOEAD,SMSEMOA,PAES}`:
  algorithm variants. The weight vectors of MOEA/D (spread over the simplex
  for any population size, or a simplex lattice) and its aggregation
  functions live in `MOEADWeights` and `MOEADAggregation`.
- `es.unex.jdisrest.distributed.RestWorker`: Java worker that connects to a
  running master over REST (heartbeats, retries, failure reports, a check
  of the task's layout against the local problem, repaired variables sent
  back, and shutdown handled for you).
- `es.unex.jdisrest.distributed.WarmStartCapable`: optional interface implemented
  by problems that can seed the initial population from disk (`iVAR.csv`,
  which `WarmStart` also copies into the traces folder).
- `es.unex.jdisrest.util.SolutionVariables`: flattens `IntegerSolution`,
  `DoubleSolution` and `CompositeSolution` variables into the wire vector and
  writes results back, converting each value to the type of the destination
  variable.
- `es.unex.jdisrest.distributed.rest.MasterSpringApp`: embedded Spring Boot entry
  point (auto-loaded by the master).
- `es.unex.jdisrest.local.algorithms.NSGAII`: sequential local variant (no REST)
  for debugging small problems or measuring distribution overhead. Its
  `PythonProcessEvaluator` evaluates in a Python child process that speaks
  a line-based JSON protocol over its standard input and output, which the
  developer manual describes.
- `es.unex.jdisrest.config.AlgorithmConfig`: reads NSGA-II, PAES and MOEA/D
  settings (budget, population or archive size, operators with their
  probabilities and parameters, traces folder) from a properties file, and
  checks them before anything starts. Real-coded problems (`DoubleProblem`)
  only.
- `es.unex.jdisrest.config.ConfiguredMaster`: launcher that runs a master
  for a `DoubleProblem` class with such a file, or only validates the file
  (`--check`). A program that builds its problem itself calls
  `ConfiguredMaster.run` from its own `main`.

Every master also answers `POST /api/v1/stop`, which ends the run as if
its stopping criterion had been met so that `run()` returns the current
result, and `GET`/`POST /api/v1/config`, which read and replace the
configuration of a master that registered a `ConfigurationHandler` with
`MasterFacade.setConfigurationHandler` (`ConfiguredMaster` registers
`config.AlgorithmReconfiguration`); other masters answer `501`.

## Documentation

The developer manual at [`docs/DEVELOPER_MANUAL.md`](docs/DEVELOPER_MANUAL.md)
covers the REST protocol (including the limit of failed evaluations per
task), how to define problems and warm-start them, worker
implementations in Java/Python/MATLAB, master wiring and shutdown with
the available algorithms and operators, the local mode and its Python
child protocol, configuration files and the `ConfiguredMaster` launcher,
SLURM deployment patterns, monitoring and control (status, stop and
configuration endpoints), internals, and what changed in 1.2 and 1.2.1.
[`python/README.md`](python/README.md) covers the Python client, its
command-line worker and the trace tools.

## Citing this software

If you use jdisrest in academic work, please cite it. See
[`CITATION.cff`](CITATION.cff) (also exposed through GitHub's "Cite this
repository" button). Each release is archived on Zenodo with its own
versioned DOI; the concept DOI below always resolves to the latest one:

[10.5281/zenodo.21454186](https://doi.org/10.5281/zenodo.21454186)

## Publications using jdisrest

Papers and theses that have used jdisrest (or the codebase it was
extracted from) for their experiments:

- A. Calzadilla, F. Luna, G. Álvarez-Botero, N. Duque-Madrid,
  J. Galeano-Brajones, T. Lopetegi, M. A. G. Laso, I. Arregui. "Compact
  Waveguide Low-Pass Filter With Smooth Profile Designed by Genetic
  Algorithm Optimization." *IEEE Microwave and Wireless Technology
  Letters*, early access, 2026.
  DOI: [10.1109/LMWT.2026.3721955](https://doi.org/10.1109/LMWT.2026.3721955)

- J. Calle-Cancho, J. Galeano-Brajones, D. Cortés-Polo, J. Carmona-Murillo,
  F. Luna-Valero. "Optimizing load-balanced resource allocation in
  next-generation mobile networks: A parallelized multi-objective
  approach." *Ad Hoc Networks*, 177, 103912, 2025.
  DOI: [10.1016/j.adhoc.2025.103912](https://doi.org/10.1016/j.adhoc.2025.103912)

- J. Galeano-Brajones, M. I. Chidean, F. Luna, J. Calle-Cancho,
  J. Carmona-Murillo. "Network traffic classification through high-order
  L-moments and multi-objective optimization." *Computer Communications*,
  242, 108290, 2025.
  DOI: [10.1016/j.comcom.2025.108290](https://doi.org/10.1016/j.comcom.2025.108290)

- J. Galeano-Brajones, C. Pupiales, D. Laselva, J. Carmona-Murillo, F. Luna.
  "Applying Evolutionary Algorithms for Cell Switch-Off to Reduce Network
  Energy Consumption." *2024 IEEE 99th Vehicular Technology Conference
  (VTC2024-Spring)*, pp. 1–7, 2024.
  DOI: [10.1109/VTC2024-Spring62846.2024.10683144](https://doi.org/10.1109/VTC2024-Spring62846.2024.10683144)

- J. Galeano-Brajones. *Advanced Optimization Techniques for Energy
  Efficiency Improvement in Ultra-Dense 5G/6G Networks.* PhD thesis,
  Universidad de Extremadura, 2024.
  [hdl.handle.net/10662/21029](http://hdl.handle.net/10662/21029)

Used jdisrest in a publication? Open a pull request adding it here, or
get in touch.

## Acknowledgments

The NSGA-II, MOEA/D, SMS-EMOA and PAES implementations, solution
encodings, and several operators under `es.unex.jdisrest.operator` are
adapted from or depend directly on
[**jMetal**](https://github.com/jMetal/jMetal). The selection and
replacement rules of PAES follow jMetal 7.5's `PAESSelection` and
`PAESReplacement` components, and the aggregation functions of MOEA/D
(Tchebycheff, weighted sum, PBI) follow jMetal's.
If you use jdisrest, please also cite jMetal itself:

> J.J. Durillo, A.J. Nebro. "jMetal: A Java framework for multi-objective
> optimization." *Advances in Engineering Software*, 42(10), 760–771, 2011.
> DOI: [10.1016/j.advengsoft.2011.05.014](https://doi.org/10.1016/j.advengsoft.2011.05.014)

## Authors

- Jesús Galeano Brajones (Universidad de Extremadura)
  ([ORCID: 0000-0001-8691-8944](https://orcid.org/0000-0001-8691-8944))
- Francisco Luna (Universidad de Málaga)
  ([ORCID: 0000-0002-0455-7223](https://orcid.org/0000-0002-0455-7223))

## License

Distributed under the GNU Affero General Public License v3.0 or later.
See [`LICENSE`](LICENSE) for the full text. For commercial licensing,
contact the authors.

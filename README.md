# jdisrest

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange)
![Python](https://img.shields.io/badge/Python-%E2%89%A53.11-blue)
[![GitHub release](https://img.shields.io/github/v/release/galeanobra/jdisrest)](https://github.com/galeanobra/jdisrest/releases)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21454186.svg)](https://doi.org/10.5281/zenodo.21454186)

Distributed REST-based master/worker framework for jMetal-encoded
optimization problems.

**jdisrest** lets you run evolutionary algorithms (NSGA-II, MOEA/D,
SMS-EMOA) whose objective-function evaluations are farmed out to remote
worker processes over plain HTTP. The master (Java, Spring Boot WebFlux)
owns the algorithm and the population; workers only need to speak a
three-endpoint REST protocol, so they can be written in Java, Python,
MATLAB, or anything else that can make an HTTP request. This makes it
straightforward to distribute expensive evaluations, such as external
simulators, trained models, or legacy code, across a cluster (including
SLURM-managed HPC nodes) without coupling the algorithm implementation
to the evaluation language.

The algorithmic core (NSGA-II, MOEA/D, SMS-EMOA, solution encodings,
and several operators) is built on top of
[**jMetal**](https://github.com/jMetal/jMetal). jdisrest does not
reimplement these algorithms; it wraps jMetal's components in a
distributed, REST-based execution model. See
[Acknowledgments](#acknowledgments) for how to cite jMetal itself.

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
2. Each worker long-polls `GET /tasks/next` for a solution to evaluate.
3. The worker evaluates it (however long that takes) and posts the
   result back to `POST /tasks/{id}/result`.
4. The master integrates the result into the population and repeats
   until the stopping criterion is met.

See [`docs/DEVELOPER_MANUAL.md`](docs/DEVELOPER_MANUAL.md) for the full
REST protocol, how to define problems, worker implementations in
Java/Python/MATLAB, master wiring, SLURM deployment patterns, and
internals.

## Requirements

- **Java 25** and Maven (master / Java workers)
- **Python ≥ 3.11** (Python workers)

## Installing

### Java (Maven)

```
git clone https://github.com/galeanobra/jdisrest.git
cd jdisrest
mvn install        # deposits into ~/.m2/repository
```

Consumers reference it via:

```xml
<dependency>
    <groupId>es.unex</groupId>
    <artifactId>jdisrest</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Python (pip)

```
cd jdisrest/python
pip install -e .
```

The Python `jdisrest` package exposes `Worker`, `Evaluator`, and
`EvalResult` used by worker processes to connect to a running Java master.

## Quick start

A minimal Java master (see the developer manual for the full example,
including problem definition):

```java
var algo = new NSGAII<>(
    "0.0.0.0", 8080,
    problem, /*popSize=*/ 50,
    crossover, mutation, termination,
    /*tracesFolder=*/ null);

MasterFacade.init(5000, /*statusFileIntervalSec=*/ 30);
algo.run();
```

A matching Python worker:

```python
from jdisrest import Worker, EvalResult

def evaluate(variables: list[int]) -> EvalResult:
    return EvalResult(objectives=[float(sum(x ** 2 for x in variables))])

Worker("http://localhost:8080").run(evaluate)
```

## Repository layout

```
jdisrest/
├── pom.xml                       # Maven library (es.unex:jdisrest:1.0.0)
├── src/main/java/es/unex/jdisrest/
│   ├── distributed/              # Master, algorithms, REST controllers
│   ├── local/                    # Sequential (non-REST) mode for debugging
│   ├── operator/                 # Custom jMetal operators
│   └── util/                     # Logging, protocol timings, trace output
├── python/
│   ├── pyproject.toml            # PEP 621 metadata
│   └── jdisrest/                 # Worker-side Python package
└── docs/
    └── DEVELOPER_MANUAL.md       # Developer manual
```

## Key classes

- `es.unex.jdisrest.distributed.AbstractMaster`: shared master infrastructure
  (Spring Boot startup, worker registry, watchdog).
- `es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm`: generic steady-state
  distributed evolutionary algorithm.
- `es.unex.jdisrest.distributed.algorithms.steadystate.{NSGAII,MOEAD,SMSEMOA}`:
  algorithm variants.
- `es.unex.jdisrest.distributed.RestWorker`: Java worker that connects to a
  running master over REST (heartbeats, retries, and shutdown handled for you).
- `es.unex.jdisrest.distributed.WarmStartCapable`: optional interface implemented
  by problems that can seed the initial population from disk.
- `es.unex.jdisrest.distributed.rest.MasterSpringApp`: embedded Spring Boot entry
  point (auto-loaded by the master).
- `es.unex.jdisrest.local.algorithms.NSGAII`: sequential local variant (no REST)
  for debugging small problems or measuring distribution overhead.

## Documentation

The developer manual at [`docs/DEVELOPER_MANUAL.md`](docs/DEVELOPER_MANUAL.md)
covers the REST protocol, how to define problems, worker
implementations in Java/Python/MATLAB, master wiring, SLURM deployment
patterns and internals.

## Citing this software

If you use jdisrest in academic work, please cite it. See
[`CITATION.cff`](CITATION.cff) (also exposed through GitHub's "Cite this
repository" button). Each release is archived on Zenodo with its own
versioned DOI; the concept DOI below always resolves to the latest one:

[10.5281/zenodo.21454186](https://doi.org/10.5281/zenodo.21454186)

## Publications using jdisrest

Papers and theses that have used jdisrest (or the codebase it was
extracted from) for their experiments:

- J. Calle-Cancho, J. Galeano-Brajones, D. Cortés-Polo, J. Carmona-Murillo,
  F. Luna-Valero. "Optimizing load-balanced resource allocation in
  next-generation mobile networks: A parallelized multi-objective
  approach." *Ad Hoc Networks*, 177, 103912, 2025.
  DOI: [10.1016/j.adhoc.2025.103912](https://doi.org/10.1016/j.adhoc.2025.103912)

- J. Galeano-Brajones, C. Pupiales, D. Laselva, J. Carmona-Murillo, F. Luna.
  "Applying Evolutionary Algorithms for Cell Switch-Off to Reduce Network
  Energy Consumption." *2024 IEEE 99th Vehicular Technology Conference
  (VTC2024-Spring)*, pp. 1–7, 2024.
  DOI: [10.1109/VTC2024-Spring62846.2024.10683144](https://doi.org/10.1109/VTC2024-Spring62846.2024.10683144)

- J. Galeano-Brajones, M. I. Chidean, F. Luna, J. Calle-Cancho,
  J. Carmona-Murillo. "Network traffic classification through high-order
  L-moments and multi-objective optimization." *Computer Communications*,
  242, 108290, 2025.
  DOI: [10.1016/j.comcom.2025.108290](https://doi.org/10.1016/j.comcom.2025.108290)

- J. Galeano-Brajones. *Advanced Optimization Techniques for Energy
  Efficiency Improvement in Ultra-Dense 5G/6G Networks.* PhD thesis,
  Universidad de Extremadura, 2024.
  [hdl.handle.net/10662/21029](http://hdl.handle.net/10662/21029)

Used jdisrest in a publication? Open a pull request adding it here, or
get in touch.

## Acknowledgments

The NSGA-II, MOEA/D and SMS-EMOA implementations, solution encodings,
and several operators under `es.unex.jdisrest.operator` are adapted
from or depend directly on [**jMetal**](https://github.com/jMetal/jMetal).
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
contact the author.

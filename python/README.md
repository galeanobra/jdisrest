# jdisrest — Python client

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
    return EvalResult(objectives=[-sum(v ** 2 for v in variables)])

Worker("http://master:8080").run(evaluate)
```

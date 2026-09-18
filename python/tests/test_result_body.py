"""Wire-format rules of the result body built by the worker."""
import math
import numbers

import pytest

from jdisrest import EvalResult
from jdisrest._worker import _coerce, _result_body


class FakeNumpyInt(int):
    """Stands in for numpy.int64: an Integral that is not a plain int."""


class FakeNumpyFloat(float):
    """Stands in for numpy.float64: a Real that is not a plain float."""


def test_objectives_and_constraints_become_plain_floats():
    body = _result_body("w", EvalResult(objectives=[1, FakeNumpyFloat(2.5)], constraints=[FakeNumpyInt(-3)]), 12)

    assert body["objectives"] == [1.0, 2.5]
    assert all(type(v) is float for v in body["objectives"])
    assert body["constraints"] == [-3.0]
    assert body["evaluationTimeMs"] == 12
    assert body["workerId"] == "w"
    assert "variables" not in body


def test_missing_constraints_are_sent_as_empty_list():
    assert _result_body("w", EvalResult(objectives=[1.0]), 0)["constraints"] == []


def test_variables_keep_ints_and_floats_apart():
    body = _result_body("w", EvalResult(objectives=[0.0], variables=[FakeNumpyInt(3), 4, FakeNumpyFloat(0.5), 2.0]), 0)

    assert body["variables"] == [3, 4, 0.5, 2.0]
    assert [type(v) for v in body["variables"]] == [int, int, float, float]


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), -float("inf")])
def test_non_finite_objective_is_rejected_with_index(bad):
    with pytest.raises(ValueError, match=r"objectives\[1\]"):
        _result_body("w", EvalResult(objectives=[1.0, bad]), 0)


def test_non_finite_constraint_and_variable_are_rejected():
    with pytest.raises(ValueError, match=r"constraints\[0\]"):
        _result_body("w", EvalResult(objectives=[1.0], constraints=[math.nan]), 0)
    with pytest.raises(ValueError, match=r"variables\[2\]"):
        _result_body("w", EvalResult(objectives=[1.0], variables=[1, 2, math.inf]), 0)


def test_non_numeric_values_are_rejected():
    with pytest.raises(ValueError, match=r"objectives\[0\] is not a number"):
        _result_body("w", EvalResult(objectives=[None]), 0)
    with pytest.raises(ValueError, match=r"variables\[0\] is not a number"):
        _result_body("w", EvalResult(objectives=[1.0], variables=["3"]), 0)


def test_coerce_accepts_scalar_dict_and_objects():
    assert _coerce(3).objectives == [3.0]
    r = _coerce({"objectives": [1.0], "constraints": [0.0], "variables": [1, 2]})
    assert (r.objectives, r.constraints, r.variables) == ([1.0], [0.0], [1, 2])

    class Obj:
        objectives = (2.0,)
        constraints = None
        variables = (0.5, 1.5)

    r = _coerce(Obj())
    assert r.objectives == [2.0] and r.constraints is None and r.variables == [0.5, 1.5]
    assert isinstance(FakeNumpyInt(1), numbers.Integral)

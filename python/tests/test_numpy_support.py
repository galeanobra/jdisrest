"""numpy results must serialize like plain Python numbers (skipped without numpy)."""
import pytest

np = pytest.importorskip("numpy")

from jdisrest import EvalResult
from jdisrest._worker import _coerce, _result_body


def test_numpy_scalars_and_arrays_are_accepted():
    result = EvalResult(
        objectives=np.array([1.5, 2.0]),
        constraints=np.array([0.0, -1.0]),
        variables=np.array([3, 4], dtype=np.int64),
    )
    body = _result_body("w", result, 5)

    assert body["objectives"] == [1.5, 2.0]
    assert body["constraints"] == [0.0, -1.0]
    assert body["variables"] == [3, 4]
    assert all(type(v) is float for v in body["objectives"] + body["constraints"])
    assert all(type(v) is int for v in body["variables"])


def test_numpy_float_variables_stay_floats():
    body = _result_body("w", EvalResult(objectives=[0.0], variables=np.array([0.5, 1.0])), 0)
    assert body["variables"] == [0.5, 1.0]
    assert all(type(v) is float for v in body["variables"])


def test_numpy_scalar_return_value_is_a_single_objective():
    assert _coerce(np.float32(2.5)).objectives == [2.5]
    assert _coerce(np.int64(7)).objectives == [7.0]


def test_numpy_nan_is_rejected_with_index():
    with pytest.raises(ValueError, match=r"objectives\[0\]"):
        _result_body("w", EvalResult(objectives=np.array([np.nan])), 0)


def test_object_result_with_numpy_arrays_uses_is_not_none():
    class Obj:
        objectives = np.array([1.0])
        constraints = np.array([0.0, 0.0])   # truthiness of this array is ambiguous
        variables = None

    r = _coerce(Obj())
    assert r.objectives == [1.0] and r.constraints == [0.0, 0.0] and r.variables is None

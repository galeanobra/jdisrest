"""numpy results must serialize like plain Python numbers (skipped without numpy)."""
import re

import pytest

np = pytest.importorskip("numpy")

from jdisrest import DecisionVector, EvalResult
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


def test_numpy_array_return_value_is_a_list_of_objectives():
    body = _result_body("w", _coerce(np.array([1.5, 2.0])), 0)
    assert body["objectives"] == [1.5, 2.0]
    assert all(type(v) is float for v in body["objectives"])


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


# An integer variable, then a binary variable of 3 bits.
TASK = DecisionVector([4, 1, 0, 1], encoding="mixed", segment_sizes=[1, 3], segment_encodings=["int", "binary"],
                      bits_per_variable=[3])


@pytest.mark.parametrize("bits", [
    np.array([True, False, True]),
    np.array([1, 0, 1], dtype=np.uint8),
    np.array([1.0, 0.0, 1.0]),
])
def test_numpy_bits_are_sent_as_the_ints_0_and_1(bits):
    repaired = [np.int64(4)] + list(bits)  # numpy scalars, numpy.bool_ included

    body = _result_body("w", _coerce({"objectives": [1.0], "variables": repaired}), 0, TASK)

    assert body["variables"] == [4, 1, 0, 1]
    assert all(type(v) is int for v in body["variables"])


def test_a_numpy_array_of_booleans_is_a_repair_of_bits():
    task = DecisionVector([1, 0, 1], encoding="binary", bits_per_variable=[3])

    body = _result_body("w", EvalResult(objectives=[1.0], variables=np.array([False, False, True])), 0, task)

    assert body["variables"] == [0, 0, 1]


def test_a_numpy_bool_is_still_refused_for_an_integer_variable():
    with pytest.raises(ValueError, match=r"variables\[0\] is not a number: " + re.escape(repr(np.True_))):
        _result_body("w", EvalResult(objectives=[1.0], variables=[np.True_, 1, 0, 1]), 0, TASK)

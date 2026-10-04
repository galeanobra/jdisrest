"""Wire-format rules of the result body built by the worker."""
import math
from types import MappingProxyType

import pytest

from jdisrest import DecisionVector, EvalResult
from jdisrest._worker import _coerce, _result_body

# An integer variable, a real one, and two binary variables of 2 and 3 bits.
TASK = DecisionVector([4, 0.5, 1, 0, 0, 1, 1], encoding="mixed", segment_sizes=[1, 1, 5],
                      segment_encodings=["int", "double", "binary"], bits_per_variable=[2, 3])


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


def test_coerce_accepts_a_sequence_of_objectives():
    assert _coerce([1.0, 2.0]).objectives == [1.0, 2.0]
    assert _coerce((3, 4.5)).objectives == [3, 4.5]
    assert _result_body("w", _coerce((3, 4.5)), 0)["objectives"] == [3.0, 4.5]


def test_coerce_accepts_any_mapping():
    r = _coerce(MappingProxyType({"objectives": [1.0], "constraints": [-1.0]}))
    assert (r.objectives, r.constraints, r.variables) == ([1.0], [-1.0], None)


@pytest.mark.parametrize("bad", [None, "1.0", b"1", True, object()])
def test_coerce_rejects_what_is_not_a_result(bad):
    with pytest.raises(TypeError, match=f"Cannot convert {type(bad).__name__} to EvalResult"):
        _coerce(bad)


@pytest.mark.parametrize("empty", [[], ()])
def test_a_result_without_objectives_is_rejected_before_sending(empty):
    with pytest.raises(ValueError, match="at least one objective"):
        _result_body("w", _coerce(empty), 0)
    with pytest.raises(ValueError, match="at least one objective"):
        _result_body("w", EvalResult(objectives=list(empty)), 0)


# ── Repaired variables and the layout of the task ──────────────────────────

def _repaired(values, task=TASK):
    return _result_body("w", EvalResult(objectives=[1.0], variables=values), 0, task)["variables"]


@pytest.mark.parametrize("bit, sent", [
    (True, 1), (False, 0), (1, 1), (0, 0), (FakeNumpyInt(1), 1), (1.0, 1), (0.0, 0), (-0.0, 0),
    (1 + 1e-12, 1), (1e-10, 0), (FakeNumpyFloat(0.9999999999), 1),
    # The master's tolerance, inclusive as in SolutionVariables.
    (1e-9, 0), (-1e-9, 0), (1 - 1e-9, 1),
])
def test_a_bit_is_sent_as_the_int_0_or_1(bit, sent):
    variables = _repaired([4, 3, bit, 0, 1, 1, 0])

    assert variables[2:] == [sent, 0, 1, 1, 0]
    assert all(type(v) is int for v in variables[2:])


@pytest.mark.parametrize("bit, problem", [
    (2, r"variables\[2\] = 2 is not a bit \(0 or 1\) but its variable is binary"),
    (-1, r"variables\[2\] = -1 is not a bit"),
    (0.5, r"variables\[2\] = 0.5 is not a bit"),
    (1.001, r"variables\[2\] = 1.001 is not a bit"),
    (2e-9, r"variables\[2\] = 2e-09 is not a bit"),
    (1 + 1e-9, r"variables\[2\] = 1.000000001 is not a bit"),  # 1.000000001 - 1 is a little over 1e-9
    (math.nan, r"variables\[2\] is not finite: nan"),
    (None, r"variables\[2\] is not a number: None"),
    ("1", r"variables\[2\] is not a number: '1'"),
])
def test_a_value_that_is_not_a_bit_is_refused_at_a_binary_position(bit, problem):
    with pytest.raises(ValueError, match=problem):
        _repaired([4, 3, bit, 0, 1, 1, 0])


@pytest.mark.parametrize("values, problem", [
    ([True, 0.5, 1, 0, 0, 1, 1], r"variables\[0\] is not a number: True"),
    ([4, False, 1, 0, 0, 1, 1], r"variables\[1\] is not a number: False"),
])
def test_bools_are_still_refused_for_integer_and_real_variables(values, problem):
    with pytest.raises(ValueError, match=problem):
        _repaired(values)


def test_integer_and_real_variables_keep_their_kind_next_to_bits():
    variables = _repaired((FakeNumpyInt(3), 2, True, False, 1, 1.0, 0))

    assert variables == [3, 2, 1, 0, 1, 1, 0]
    assert [type(v) for v in variables] == [int, int, int, int, int, int, int]
    assert _repaired([3, 2.5, 1, 0, 1, 1, 0])[1] == 2.5


@pytest.mark.parametrize("values", [[4, 0.5, 1, 0, 0, 1], [4, 0.5, 1, 0, 0, 1, 1, 0]])
def test_repaired_variables_of_another_length_than_the_task_are_refused(values):
    with pytest.raises(ValueError, match=f"variables has {len(values)} values but the task has 7"):
        _repaired(values)


def test_an_empty_repair_is_sent_as_it_is():
    # The master takes an empty list as "keep the variables", as it does without the field.
    assert _repaired([]) == []


def test_without_the_task_bools_are_refused_and_the_length_is_not_checked():
    with pytest.raises(ValueError, match=r"variables\[0\] is not a number: True"):
        _repaired([True], task=None)
    assert _repaired([1, 0], task=None) == [1, 0]


def test_positions_of_an_unknown_encoding_are_not_bits():
    task = DecisionVector([1, 0], encoding="packed")

    with pytest.raises(ValueError, match=r"variables\[0\] is not a number: True"):
        _repaired([True, 0], task)
    assert _repaired([1, 0], task) == [1, 0]


def test_when_segment_sizes_of_an_unknown_encoding_do_not_fit_no_value_is_a_bit():
    # Where the binary segment lies is unknown, so no value is taken for a bit; a repair keeps the length received.
    task = DecisionVector([5, 6, 7], encoding="mixed", segment_sizes=[8, 1], segment_encodings=["packed", "binary"])

    assert _repaired([5, 6, 1], task) == [5, 6, 1]
    with pytest.raises(ValueError, match=r"variables\[2\] is not a number: True"):
        _repaired([5, 6, True], task)
    with pytest.raises(ValueError, match="variables has 9 values but the task has 3"):
        _repaired([0] * 9, task)

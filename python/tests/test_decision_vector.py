"""DecisionVector: the decision variables of a task with the layout its payload describes."""
import copy
import pickle
from concurrent.futures import ProcessPoolExecutor

import pytest

import jdisrest
from jdisrest import DecisionVector

# The payloads of section 2.2 of the developer manual: integer, real, integer and real, binary,
# integer, real and binary, and two binary segments.
INTEGER = {"taskId": 42, "variables": [3, -17, 55]}
DOUBLE = {"taskId": 43, "variables": [0.25, -1.5, 3.0], "encoding": "double"}
INT_DOUBLE = {"taskId": 44, "variables": [3, -17, 0.25], "segmentSizes": [2, 1], "encoding": "mixed",
              "segmentEncodings": ["int", "double"]}
BINARY = {"taskId": 45, "variables": [1, 0, 1, 0, 0, 1, 1, 0], "encoding": "binary", "bitsPerVariable": [3, 5]}
INT_DOUBLE_BINARY = {"taskId": 46, "variables": [3, -7, 0.25, 1, 0, 1, 0, 0, 1, 1, 0], "segmentSizes": [2, 1, 8],
                     "encoding": "mixed", "segmentEncodings": ["int", "double", "binary"],
                     "bitsPerVariable": [3, 5]}
BINARY_BINARY = {"taskId": 47, "variables": [1, 0, 1, 0, 0, 1, 1, 0], "segmentSizes": [3, 5], "encoding": "binary",
                 "segmentEncodings": ["binary", "binary"], "bitsPerVariable": [3, 5]}


def _layout(vector):
    return (vector.encoding, vector.composite, vector.segment_sizes, vector.segment_encodings,
            vector.bits_per_variable)


# ── What a payload gives ───────────────────────────────────────────────────

@pytest.mark.parametrize("payload, layout, segments, binary_variables", [
    (INTEGER, ("int", False, (3,), ("int",), ()), [[3, -17, 55]], []),
    (DOUBLE, ("double", False, (3,), ("double",), ()), [[0.25, -1.5, 3.0]], []),
    (INT_DOUBLE, ("mixed", True, (2, 1), ("int", "double"), ()), [[3, -17], [0.25]], []),
    (BINARY, ("binary", False, (8,), ("binary",), (3, 5)), [[1, 0, 1, 0, 0, 1, 1, 0]],
     [[1, 0, 1], [0, 0, 1, 1, 0]]),
    (INT_DOUBLE_BINARY, ("mixed", True, (2, 1, 8), ("int", "double", "binary"), (3, 5)),
     [[3, -7], [0.25], [1, 0, 1, 0, 0, 1, 1, 0]], [[1, 0, 1], [0, 0, 1, 1, 0]]),
    (BINARY_BINARY, ("binary", True, (3, 5), ("binary", "binary"), (3, 5)), [[1, 0, 1], [0, 0, 1, 1, 0]],
     [[1, 0, 1], [0, 0, 1, 1, 0]]),
])
def test_a_payload_gives_its_values_and_their_layout(payload, layout, segments, binary_variables):
    vector = DecisionVector.from_payload(payload)

    assert vector == payload["variables"]
    assert [type(v) for v in vector] == [type(v) for v in payload["variables"]], "values keep their JSON type"
    assert _layout(vector) == layout
    assert vector.segments() == segments
    assert vector.binary_variables() == binary_variables
    assert all(type(segment) is list for segment in vector.segments() + vector.binary_variables())


def test_a_composite_of_integer_segments_has_only_segment_sizes_and_gets_int_for_each():
    # The 1.0 payload of a composite: neither encoding nor segmentEncodings.
    vector = DecisionVector.from_payload({"taskId": 1, "variables": [3, 7, -1], "segmentSizes": [1, 2]})

    assert _layout(vector) == ("int", True, (1, 2), ("int", "int"), ())
    assert vector.segments() == [[3], [7, -1]]


def test_a_composite_of_a_single_segment_is_still_a_composite():
    # A CompositeSolution of one segment sends segmentSizes [n].
    vector = DecisionVector.from_payload({"variables": [1, 0, 1], "segmentSizes": [3], "encoding": "binary",
                                          "segmentEncodings": ["binary"], "bitsPerVariable": [3]})

    assert _layout(vector) == ("binary", True, (3,), ("binary",), (3,))
    assert vector.segments() == [[1, 0, 1]] and vector.binary_variables() == [[1, 0, 1]]


def test_a_request_of_the_local_mode_gives_its_vars():
    request = {"id": 0, "vars": [3, 1, 0, 1], "segmentSizes": [1, 3], "encoding": "mixed",
               "segmentEncodings": ["int", "binary"], "bitsPerVariable": [3]}

    vector = DecisionVector.from_payload(request)

    assert vector == [3, 1, 0, 1]
    assert _layout(vector) == ("mixed", True, (1, 3), ("int", "binary"), (3,))
    assert vector.binary_variables() == [[1, 0, 1]]


def test_null_fields_take_their_defaults():
    vector = DecisionVector.from_payload({"variables": [1, 2], "encoding": None, "segmentSizes": None,
                                          "segmentEncodings": None, "bitsPerVariable": None})

    assert _layout(vector) == ("int", False, (2,), ("int",), ())


def test_a_vector_is_built_from_its_layout_as_well():
    vector = DecisionVector([3, 1, 0, 0, 1, 1], encoding="mixed", segment_sizes=[1, 5],
                            segment_encodings=["int", "binary"], bits_per_variable=[2, 3])

    assert _layout(vector) == ("mixed", True, (1, 5), ("int", "binary"), (2, 3))
    assert vector.binary_variables() == [[1, 0], [0, 1, 1]]
    assert _layout(DecisionVector()) == ("int", False, (0,), ("int",), ())


def test_an_empty_binary_segment_holds_no_variable():
    vector = DecisionVector.from_payload({"variables": [4, 1, 1], "segmentSizes": [1, 0, 2], "encoding": "mixed",
                                          "segmentEncodings": ["int", "binary", "binary"], "bitsPerVariable": [2]})

    assert vector.segments() == [[4], [], [1, 1]]
    assert vector.binary_variables() == [[1, 1]]


# ── Inconsistent payloads ──────────────────────────────────────────────────

@pytest.mark.parametrize("payload, problem", [
    ({"taskId": 9}, "variables is missing"),
    ({"variables": "1,2"}, "variables is a str, not a list"),
    ({"variables": [1, "NaN"]}, "variables[1] is not a number: 'NaN'"),
    ({"variables": [True]}, "variables[0] is not a number: True"),
    ({"variables": [1.0, float("inf")]}, "variables[1] is not finite: inf"),
    ({"id": 0, "vars": [1, None]}, "vars[1] is not a number: None"),
    ({"variables": [1, 2], "encoding": 2}, "encoding is not a string: 2"),
    ({"variables": [1, 2], "segmentSizes": "1,1"}, "segmentSizes is a str, not a list"),
    ({"variables": [1, 2, 3, 4], "segmentSizes": [2, 1]},
     "segmentSizes [2, 1] add up to 3, but variables has 4 values"),
    ({"variables": [1, 2], "segmentSizes": [3, -1]}, "segmentSizes[1] is not a non-negative integer: -1"),
    ({"variables": [1, 2], "segmentSizes": [1, True]}, "segmentSizes[1] is not a non-negative integer: True"),
    ({"variables": [1, 2], "segmentSizes": [1, 1.0]}, "segmentSizes[1] is not a non-negative integer: 1.0"),
    ({"variables": [1, 0.5], "encoding": "mixed"}, "encoding is mixed, but segmentSizes is missing"),
    ({"variables": [1, 0.5], "segmentSizes": [1, 1], "encoding": "mixed"},
     "encoding is mixed, but segmentEncodings is missing"),
    ({"variables": [1, 0.5], "segmentSizes": [1, 1], "encoding": "mixed", "segmentEncodings": ["int"]},
     "segmentEncodings has 1 values, but there are 2 segments"),
    ({"variables": [1, 0.5], "segmentSizes": [1, 1], "encoding": "mixed", "segmentEncodings": ["int", 2]},
     "segmentEncodings[1] is not a string: 2"),
    ({"variables": [1, 0, 1], "encoding": "binary"}, "bitsPerVariable is missing, but the vector has 3 bits"),
    ({"variables": [1, 0, 1], "encoding": "binary", "bitsPerVariable": [1, 1]},
     "bitsPerVariable [1, 1] add up to 2, but the vector has 3 bits"),
    ({"variables": [1, 2], "bitsPerVariable": [2]}, "bitsPerVariable [2] add up to 2, but the vector has 0 bits"),
    ({"variables": [1, 0, 1], "encoding": "binary", "bitsPerVariable": [3, 0]},
     "bitsPerVariable[1] is not a positive integer: 0"),
    ({"variables": [1, 0, 1], "encoding": "binary", "bitsPerVariable": {"0": 3}},
     "bitsPerVariable is a dict, not a list"),
    # Eight bits in segments of 4 and 4 cannot hold variables of 3 and 5.
    ({"variables": [1, 0, 1, 0, 0, 1, 1, 0], "segmentSizes": [4, 4], "encoding": "binary",
      "segmentEncodings": ["binary", "binary"], "bitsPerVariable": [3, 5]},
     "bitsPerVariable [3, 5] does not split the binary segments [4, 4] into whole variables"),
])
def test_an_inconsistent_payload_is_refused_naming_the_field(payload, problem):
    with pytest.raises(ValueError) as refused:
        DecisionVector.from_payload(payload)

    assert str(refused.value) == problem


@pytest.mark.parametrize("payload", [None, [1, 2], "variables"])
def test_a_payload_must_be_a_json_object(payload):
    with pytest.raises(ValueError, match="the payload is a .*, not a JSON object"):
        DecisionVector.from_payload(payload)


# ── Encodings this version does not know ───────────────────────────────────

def test_an_unknown_encoding_is_kept_and_its_values_handed_over_as_they_are():
    vector = DecisionVector.from_payload({"variables": [2, 0, 1], "encoding": "permutation"})

    assert vector == [2, 0, 1]
    assert _layout(vector) == ("permutation", False, (3,), ("permutation",), ())


def test_with_an_unknown_encoding_the_lengths_of_binary_variables_are_not_checked():
    # A newer encoding could use bitsPerVariable for values that are not one per bit.
    vector = DecisionVector.from_payload({"variables": [5, 6], "encoding": "packed", "bitsPerVariable": [3, 5]})

    assert vector.bits_per_variable == (3, 5)
    assert vector.binary_variables() == [], "no segment is binary"


def test_with_an_unknown_encoding_segment_sizes_that_do_not_add_up_are_reported_when_asked():
    # A newer encoding could count bits in segmentSizes and send them packed into fewer values.
    vector = DecisionVector.from_payload({"variables": [5, 6, 7], "segmentSizes": [8, 1], "encoding": "mixed",
                                          "segmentEncodings": ["packed", "int"]})

    assert vector == [5, 6, 7] and vector.segment_sizes == (8, 1)
    for cut in (vector.segments, vector.binary_variables):
        with pytest.raises(ValueError) as refused:
            cut()
        assert str(refused.value) == "segmentSizes [8, 1] add up to 9, but variables has 3 values"


def test_with_an_unknown_segment_encoding_binary_variables_that_do_not_fit_are_reported_when_asked():
    vector = DecisionVector.from_payload({"variables": [7, 1, 0, 1], "segmentSizes": [1, 3], "encoding": "mixed",
                                          "segmentEncodings": ["packed", "binary"], "bitsPerVariable": [2]})

    with pytest.raises(ValueError, match=r"bitsPerVariable \[2\] does not split the binary segments"):
        vector.binary_variables()


# ── A list that keeps its layout ───────────────────────────────────────────

def test_it_is_a_list_and_prints_as_one():
    vector = DecisionVector.from_payload(BINARY)

    assert isinstance(vector, list)
    assert vector == [1, 0, 1, 0, 0, 1, 1, 0] and [1, 0, 1, 0, 0, 1, 1, 0] == vector
    assert str(vector) == repr(vector) == "[1, 0, 1, 0, 0, 1, 1, 0]"
    assert sum(vector) == 4 and vector[2] == 1 and len(vector) == 8


def test_copy_keeps_the_layout_and_list_copies_do_not():
    vector = DecisionVector.from_payload(INT_DOUBLE_BINARY)

    clone = vector.copy()
    clone[3] = 0

    assert type(clone) is DecisionVector and _layout(clone) == _layout(vector)
    assert vector[3] == 1, "the copy is independent"
    assert [type(c) for c in (list(vector), vector[:], list.copy(vector))] == [list, list, list]


def test_the_layout_is_read_only_and_describes_the_vector_as_received():
    vector = DecisionVector.from_payload(BINARY)

    with pytest.raises(AttributeError):
        vector.bits_per_variable = (8,)
    vector[0] = 0
    vector.append(1)

    assert vector.segment_sizes == (8,) and vector.bits_per_variable == (3, 5)
    assert vector.binary_variables() == [[0, 0, 1], [0, 0, 1, 1, 0]]


@pytest.mark.parametrize("duplicate", [copy.copy, copy.deepcopy, lambda v: pickle.loads(pickle.dumps(v))])
def test_copies_and_pickles_keep_the_values_and_the_layout(duplicate):
    vector = DecisionVector.from_payload(INT_DOUBLE_BINARY)

    other = duplicate(vector)

    assert type(other) is DecisionVector
    assert other == vector and _layout(other) == _layout(vector)
    assert other.binary_variables() == [[1, 0, 1], [0, 0, 1, 1, 0]]


def test_a_vector_travels_to_another_process_with_its_layout():
    vector = DecisionVector.from_payload(INT_DOUBLE_BINARY)

    with ProcessPoolExecutor(max_workers=1) as pool:
        returned = pool.submit(copy.copy, vector).result(timeout=60)

    assert type(returned) is DecisionVector
    assert returned == vector and _layout(returned) == _layout(vector)


def test_the_package_exports_it():
    assert "DecisionVector" in jdisrest.__all__ and jdisrest.DecisionVector is DecisionVector

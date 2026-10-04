"""
Evaluators of the encoding integration tests (EncodingRunScenario) for the command-line worker.

Each applies the repair of the scenario's Java workers: it sets the first bit of the first binary
variable or, in a problem without one, the first integer variable to 100 and the last real
variable to 1/3000, of those the problem has. It then evaluates the problem as its Java class
does, and records what it receives, one JSON line per evaluation, in the file that
JDISREST_IT_RECORD names: the type and the layout of the decision vector, and the Python types of
the values of each of its segments.
"""
import json
import math
import os

try:
    import numpy
except ImportError:  # the repaired bits then go back as Python bools
    numpy = None


def _record(variables):
    line = {"type": type(variables).__name__, "encoding": variables.encoding,
            "segmentSizes": list(variables.segment_sizes), "segmentEncodings": list(variables.segment_encodings),
            "bitsPerVariable": list(variables.bits_per_variable),
            "valueTypes": [sorted({type(x).__name__ for x in segment}) for segment in variables.segments()]}
    with open(os.environ["JDISREST_IT_RECORD"], "a", encoding="utf-8") as record:
        record.write(json.dumps(line) + "\n")


def zdt1(variables):
    """jMetal's ZDT1; the variables go back as floats."""
    _record(variables)
    repaired = list(variables)
    repaired[-1] = 1 / 3000
    f1 = repaired[0]
    g = 9.0 / (len(repaired) - 1) * sum(repaired[1:]) + 1.0
    return {"objectives": [f1, (1.0 - math.sqrt(f1 / g)) * g], "variables": repaired}


def nmmin(variables):
    """jMetal's NMMin, whose first objective seeks 100 and second -100; the variables go back as ints."""
    _record(variables)
    repaired = list(variables)
    repaired[0] = 100
    return {"objectives": [sum(abs(100 - x) for x in repaired), sum(abs(-100 - x) for x in repaired)],
            "variables": repaired}


def zdt5(variables):
    """jMetal's ZDT5; the repaired bits go back as booleans, numpy.bool_ when numpy is installed."""
    _record(variables)
    bits = variables.binary_variables()
    bits[0][0] = 1
    ones = [sum(variable) for variable in bits]
    f1 = 1.0 + ones[0]
    g = sum(2.0 + u if u < 5 else 1.0 for u in ones[1:])
    repaired = [bit == 1 for variable in bits for bit in variable]
    return {"objectives": [f1, g / f1], "variables": numpy.array(repaired) if numpy else repaired}


def mixed_integer_double(variables):
    """
    jMetal's MixedIntegerDoubleProblem, whose objectives add up the distances of the values to 100
    and to -100, truncated to an int after each one as Java's int += double does; the integers go
    back as ints and the reals as floats.
    """
    _record(variables)
    integers, reals = variables.segments()
    integers[0] = 100
    reals[-1] = 1 / 3000
    to_100 = to_minus_100 = 0
    for x in integers + reals:
        to_100 = int(to_100 + abs(100 - x))
        to_minus_100 = int(to_minus_100 + abs(-100 - x))
    return {"objectives": [to_100, to_minus_100], "variables": integers + reals}


def integer_and_bits(variables):
    """CompositeSmsemoaIT.IntegerAndBits; the integers go back as ints and the bits as Python bools."""
    _record(variables)
    integers, bits = variables.segments()
    bits[0] = 1
    total, ones = sum(integers), sum(bits)
    return {"objectives": [total + ones, (30 - total) * (30 - total) / 30.0 + (10 - ones)],
            "variables": integers + [bit == 1 for bit in bits]}


def integers_reals_and_bits(variables):
    """MixedRunIT.IntegersRealsAndBits; every value goes back as a number, the bits as ints."""
    _record(variables)
    integers, reals, bits = variables.segments()
    bits[0] = 1
    total, ones = sum(integers) + reals[0] + reals[1], sum(bits)
    return {"objectives": [total + ones, (32 - total) * (32 - total) / 32.0 + (8 - ones)],
            "variables": integers + reals + bits}


def bit_segments(variables):
    """BinarySegmentsRunIT.BitSegments; the repaired bits go back as the floats 0.0 and 1.0."""
    _record(variables)
    first, second = variables.segments()
    first[0] = 1
    ones_first, ones_second = sum(first), sum(second)
    return {"objectives": [ones_first + ones_second, (6 - ones_first) * (6 - ones_first) / 6.0 + (12 - ones_second)],
            "variables": [float(bit) for bit in first + second]}

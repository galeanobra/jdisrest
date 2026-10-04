"""
Evaluators of the encoding integration tests (EncodingRunScenario) for the command-line worker.

Each applies the repair of the scenario's Java workers, setting the first bit of the first binary
variable, evaluates the problem as its Java class does, and records the layout of every decision
vector it receives, one JSON line per evaluation, in the file that JDISREST_IT_RECORD names.
"""
import json
import os

try:
    import numpy
except ImportError:  # the repaired bits then go back as Python bools
    numpy = None


def _record(variables):
    line = {"type": type(variables).__name__, "encoding": variables.encoding,
            "segmentSizes": list(variables.segment_sizes), "segmentEncodings": list(variables.segment_encodings),
            "bitsPerVariable": list(variables.bits_per_variable)}
    with open(os.environ["JDISREST_IT_RECORD"], "a", encoding="utf-8") as record:
        record.write(json.dumps(line) + "\n")


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


def integer_and_bits(variables):
    """CompositeSmsemoaIT.IntegerAndBits; the integers go back as ints and the bits as Python bools."""
    _record(variables)
    integers, bits = variables.segments()
    bits[0] = 1
    total, ones = sum(integers), sum(bits)
    return {"objectives": [total + ones, (30 - total) * (30 - total) / 30.0 + (10 - ones)],
            "variables": integers + [bit == 1 for bit in bits]}

"""The decision vector an evaluator receives: the flat list of a task's values, with its layout."""
from __future__ import annotations

import math
import numbers
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

# The encodings of a whole vector, and of one segment, that this version of the protocol defines.
ENCODINGS = ("int", "double", "binary", "mixed")
SEGMENT_ENCODINGS = ("int", "double", "binary")


class DecisionVector(list):
    """
    The decision variables of a task: the flat list of numbers the master sends, which also
    carries the layout the task payload describes.

    It is a ``list``, so an evaluator written for a list works unchanged: ints for integer
    variables, floats for real ones, the ints 0 and 1 for each bit of a binary variable (bit 0
    first, the order of its bit string in the traces), and for a composite solution its segments
    one after the other. Its ``str`` and ``repr`` are those of the list. The attributes, which
    are read-only, say where each segment and each binary variable lies:

    - ``encoding``: ``"int"``, ``"double"``, ``"binary"``, or ``"mixed"`` for a composite whose
      segments differ; ``"int"`` when the payload has none, as the protocol defines it.
    - ``composite``: whether the solution is a composite (the payload has ``segmentSizes``).
    - ``segment_sizes``: the number of values of each segment, the bits of a binary one;
      ``(len(vector),)`` for a flat vector.
    - ``segment_encodings``: the encoding of each segment, aligned with ``segment_sizes``:
      ``(encoding,)`` for a flat vector. A master leaves them out only for a composite of
      integer segments, which then gets ``"int"`` for each.
    - ``bits_per_variable``: the length of each binary variable, in vector order across the
      segments; ``()`` when no variable is binary.

    ``segments()`` and ``binary_variables()`` cut the vector along that layout. The layout
    describes the vector as it was received: changing the list in place, for a repair, does
    not change it. ``copy()`` keeps it, while ``list.copy()``, ``list(vector)`` and a slice give
    a plain list.

    With the encodings of this version, ``segment_sizes`` add up to the length of the vector and
    ``bits_per_variable`` split its binary segments into whole variables. An encoding this
    version does not know, which a newer master may send, is kept as it is, and so is the rest
    of the layout: both fields are then checked for their form only, not against the values,
    since a newer encoding could count something else in them, such as bits packed into fewer
    values, and ``segments()`` and ``binary_variables()`` raise ValueError if they do not fit.

    Args:
        values:            The values, in vector order.
        encoding:          The encoding of the whole vector.
        segment_sizes:     The number of values of each segment of a composite, or None for a
                           flat vector.
        segment_encodings: The encoding of each segment, or None to derive it as above.
        bits_per_variable: The length of each binary variable, or None when there is none.

    Raises:
        ValueError: if the layout does not fit the values (only its form is checked with an
            unknown encoding); the message names the field of the task payload, such as
            ``segmentSizes [2, 1] add up to 3, but variables has 4 values``.
    """

    def __init__(self, values: Iterable[Any] = (), encoding: str = "int",
                 segment_sizes: Sequence[int] | None = None, segment_encodings: Sequence[str] | None = None,
                 bits_per_variable: Sequence[int] | None = None):
        super().__init__(values)
        if not isinstance(encoding, str):
            raise ValueError(f"encoding is not a string: {encoding!r}")
        if segment_sizes is None:
            if encoding == "mixed":
                raise ValueError("encoding is mixed, but segmentSizes is missing")
            sizes = (len(self),)
        else:
            sizes = _counts("segmentSizes", segment_sizes, minimum=0)
        if segment_encodings is None:
            if segment_sizes is not None and encoding == "mixed":
                raise ValueError("encoding is mixed, but segmentEncodings is missing")
            encodings = (encoding,) * len(sizes)
        else:
            encodings = tuple(segment_encodings)
            for i, name in enumerate(encodings):
                if not isinstance(name, str):
                    raise ValueError(f"segmentEncodings[{i}] is not a string: {name!r}")
            if len(encodings) != len(sizes):
                raise ValueError(f"segmentEncodings has {len(encodings)} values, "
                                 f"but there are {len(sizes)} segments")
        bits = _counts("bitsPerVariable", () if bits_per_variable is None else bits_per_variable, minimum=1)
        self._encoding = encoding
        self._composite = segment_sizes is not None
        self._segment_sizes = sizes
        self._segment_encodings = encodings
        self._bits_per_variable = bits
        self._received_length = len(self)  # the layout describes the vector as received
        if not _unknown_encodings(self):
            problem = _sizes_problem(sizes, len(self))
            if problem is not None:
                raise ValueError(problem)
            _check_bits(sizes, encodings, bits, bits_per_variable is None)

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> DecisionVector:
        """
        The decision vector of a task payload (``variables``), or of a request of the local mode
        (``vars``), with the layout its ``encoding``, ``segmentSizes``, ``segmentEncodings`` and
        ``bitsPerVariable`` give. An absent field, or a JSON null, takes its default.

        Raises:
            ValueError: if the values are not a list of finite numbers (a bool is not a number
                here), or the layout does not fit them; the message names the field.
        """
        if not isinstance(payload, Mapping):
            raise ValueError(f"the payload is a {type(payload).__name__}, not a JSON object")
        key = "vars" if "variables" not in payload and "vars" in payload else "variables"
        values = payload.get(key)
        problem = _values_problem(key, values)
        if problem is not None:
            raise ValueError(problem)
        encoding = payload.get("encoding")
        return cls(values, encoding="int" if encoding is None else encoding,
                   segment_sizes=_list_field(payload, "segmentSizes"),
                   segment_encodings=_list_field(payload, "segmentEncodings"),
                   bits_per_variable=_list_field(payload, "bitsPerVariable"))

    @property
    def encoding(self) -> str:
        """``"int"``, ``"double"``, ``"binary"`` or ``"mixed"`` (or a newer encoding, kept as it is)."""
        return self._encoding

    @property
    def composite(self) -> bool:
        """Whether the solution is a composite: the payload has ``segmentSizes``."""
        return self._composite

    @property
    def segment_sizes(self) -> tuple[int, ...]:
        """The number of values of each segment (bits for a binary one); ``(len(vector),)`` when flat."""
        return self._segment_sizes

    @property
    def segment_encodings(self) -> tuple[str, ...]:
        """The encoding of each segment, aligned with :attr:`segment_sizes`."""
        return self._segment_encodings

    @property
    def bits_per_variable(self) -> tuple[int, ...]:
        """The length of each binary variable, in vector order; ``()`` when none is binary."""
        return self._bits_per_variable

    def segments(self) -> list[list]:
        """
        The values of each segment, as plain lists: one list for a flat vector.

        Raises:
            ValueError: if :attr:`segment_sizes` do not add up to the length of the vector as
                received, which only a vector with an encoding this version does not know can have.
        """
        problem = _sizes_problem(self._segment_sizes, self._received_length)
        if problem is not None:
            raise ValueError(problem)
        out, start = [], 0
        for size in self._segment_sizes:
            out.append(self[start:start + size])
            start += size
        return out

    def binary_variables(self) -> list[list]:
        """
        The bits of each binary variable, as plain lists aligned with :attr:`bits_per_variable`:
        ``[[1, 0, 1], [0, 0, 1, 1, 0]]`` for the variables ``101`` and ``00110``.

        Raises:
            ValueError: if the segments do not fit the vector (see :meth:`segments`) or the
                lengths do not split the binary segments, which only a vector with an encoding
                this version does not know can have.
        """
        out: list[list] = []
        lengths = iter(self._bits_per_variable)
        for segment, encoding in zip(self.segments(), self._segment_encodings):
            if encoding != "binary":
                continue
            start = 0
            while start < len(segment):
                length = next(lengths, None)
                if length is None or start + length > len(segment):
                    raise ValueError(f"bitsPerVariable {list(self._bits_per_variable)} does not split the "
                                     f"binary segments into whole variables")
                out.append(segment[start:start + length])
                start += length
        return out

    def copy(self) -> DecisionVector:
        """A copy with the same layout (``list.copy()`` would give a plain list)."""
        clone = type(self).__new__(type(self))
        list.extend(clone, self)
        clone.__dict__.update(self.__dict__)
        return clone


def _counts(field: str, values: Iterable[Any], minimum: int) -> tuple[int, ...]:
    """The values as ints, each at least ``minimum``; raises ValueError naming the first that is not."""
    out: list[int] = []
    for i, value in enumerate(values):
        if isinstance(value, bool) or not isinstance(value, numbers.Integral) or value < minimum:
            kind = "a positive" if minimum > 0 else "a non-negative"
            raise ValueError(f"{field}[{i}] is not {kind} integer: {value!r}")
        out.append(int(value))
    return tuple(out)


def _sizes_problem(sizes: tuple[int, ...], length: int) -> str | None:
    """Why the segment sizes do not cut a vector of ``length`` values, or None if they do."""
    if sum(sizes) != length:
        return f"segmentSizes {list(sizes)} add up to {sum(sizes)}, but variables has {length} values"
    return None


def _check_bits(sizes: tuple[int, ...], encodings: tuple[str, ...], bits: tuple[int, ...], missing: bool) -> None:
    """Raises ValueError unless the lengths of the binary variables split the binary segments exactly."""
    binary = [size for size, encoding in zip(sizes, encodings) if encoding == "binary"]
    if missing and sum(binary) > 0:
        raise ValueError(f"bitsPerVariable is missing, but the vector has {sum(binary)} bits")
    if sum(bits) != sum(binary):
        raise ValueError(f"bitsPerVariable {list(bits)} add up to {sum(bits)}, "
                         f"but the vector has {sum(binary)} bits")
    lengths = iter(bits)
    for size in binary:
        filled = 0
        while filled < size:
            filled += next(lengths)
        if filled != size:
            raise ValueError(f"bitsPerVariable {list(bits)} does not split the binary segments "
                             f"{binary} into whole variables")


def _list_field(payload: Mapping[str, Any], field: str) -> list | None:
    """A field of the payload that must be a list, or None when it is absent or null."""
    value = payload.get(field)
    if value is not None and not isinstance(value, list):
        raise ValueError(f"{field} is a {type(value).__name__}, not a list")
    return value


def _values_problem(field: str, values: Any) -> str | None:
    """Why the values of a payload cannot be evaluated, or None if they can."""
    if not isinstance(values, list):
        return f"{field} is missing" if values is None else f"{field} is a {type(values).__name__}, not a list"
    for i, v in enumerate(values):
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return f"{field}[{i}] is not a number: {v!r}"
        if isinstance(v, float) and not math.isfinite(v):
            return f"{field}[{i}] is not finite: {v}"
    return None


def _unknown_encodings(vector: DecisionVector) -> list[str]:
    """The encodings of the vector and of its segments that this version does not know, each once."""
    names = [vector.encoding] if vector.encoding not in ENCODINGS else []
    names += [name for name in vector.segment_encodings if name not in SEGMENT_ENCODINGS]
    return list(dict.fromkeys(names))


def _binary_positions(vector: DecisionVector) -> list[bool]:
    """
    Whether each value of the vector as received is a bit of a binary variable; none is when
    its segment sizes do not add up to its length, which only an unknown encoding allows.
    """
    if _sizes_problem(vector.segment_sizes, vector._received_length) is not None:
        return [False] * vector._received_length
    return [encoding == "binary"
            for size, encoding in zip(vector.segment_sizes, vector.segment_encodings) for _ in range(size)]

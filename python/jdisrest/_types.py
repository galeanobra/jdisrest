from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field

# Decision vector as delivered by the master: all ints for integer-encoded
# problems, all floats for real-encoded ones, the ints 0 and 1 for each bit of
# a binary variable, and for composite problems the segments one after the
# other. The worker hands it to the evaluator as a DecisionVector, a list that
# also carries the layout the task payload describes (its encoding, the size
# and encoding of each segment and the length of each binary variable).
Variables = list[int] | list[float] | list[int | float]


@dataclass
class EvalResult:
    """
    Result of evaluating one solution.

    objectives:  List of objective values (minimization assumed by jMetal).
                 Negate to maximize: EvalResult(objectives=[-throughput])
                 At least one, and every value must be finite: an empty list,
                 NaN and ±inf are rejected before sending and reported to the
                 master as an evaluation error. Map non-finite values to a
                 finite penalty yourself.
    constraints: Optional list of constraint values.
                 jMetal convention: >= 0 means satisfied, < 0 means violated.
                 Same finiteness rule as objectives.
    variables:   Optional repaired/modified decision vector. When set, the
                 master overwrites the original Solution.variables with this
                 list before archiving the result, making the search Lamarckian
                 (children of the repaired solution inherit its repaired values).
                 Leave as None to keep the master's original variables.
                 For CompositeSolution problems, the list is the flat
                 concatenation [seg0 | seg1 | ...] in declaration order, same
                 as what the worker received in the task payload, with one
                 value per bit of a binary variable; it must have the length
                 of the task's vector, or be empty, which also keeps the
                 original variables. Integers are sent as JSON
                 integers and floats as JSON floats; the master converts each
                 value to the type of the destination variable. A bit may also
                 be a bool or a numpy.bool_, which is sent as 0 or 1; anywhere
                 else a bool is refused. Each value must fit its variable (an
                 integral value for an integer variable, 0 or 1 for a bit,
                 within the variable's bounds): the master rejects a vector
                 that does not (422) and counts a failed evaluation of the task.
    """
    objectives:  list[float]
    constraints: list[float] | None = field(default=None)
    variables:   Variables | None = field(default=None)


class Evaluator(ABC):
    """
    Base class for evaluation functions.

    Subclass this when you need stateful or configurable evaluators.
    For simple cases, pass a plain function to Worker.run() instead.
    Worker.run() also takes any other object with an ``evaluate(variables)``
    method.
    """

    @abstractmethod
    def evaluate(self, variables: Variables) -> EvalResult:
        """
        Evaluate a candidate solution.

        Args:
            variables: Decision variables sent by the master, a
                       :class:`DecisionVector`: ints for an integer-encoded
                       problem, floats for a real-encoded one, the ints 0 and 1
                       for each bit of a binary variable, the segments one
                       after the other for a composite one. Its attributes and
                       ``segments()`` and ``binary_variables()`` give the layout.

        Returns:
            EvalResult with at least one objective value (or anything else
            Worker.run() accepts from a function, such as a single number or a
            list of objectives). A result without objectives is reported to the
            master as an evaluation error.
        """
        ...

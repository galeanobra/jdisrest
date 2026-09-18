from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field

# Decision vector as delivered by the master: all ints for integer-encoded
# problems, all floats for real-encoded ones, a mix for composite problems
# whose segments differ (the task payload's "encoding" field says which).
Variables = list[int] | list[float] | list[int | float]


@dataclass
class EvalResult:
    """
    Result of evaluating one solution.

    objectives:  List of objective values (minimization assumed by jMetal).
                 Negate to maximize: EvalResult(objectives=[-throughput])
                 Every value must be finite: NaN and ±inf are rejected before
                 sending and reported to the master as an evaluation error.
                 Map them to a finite penalty yourself.
    constraints: Optional list of constraint values.
                 jMetal convention: >= 0 means satisfied, < 0 means violated.
                 Same finiteness rule as objectives.
    variables:   Optional repaired/modified decision vector. When set, the
                 master overwrites the original Solution.variables with this
                 list before archiving the result, making the search Lamarckian
                 (children of the repaired solution inherit the relocations).
                 Leave as None to keep the master's original variables.
                 For CompositeSolution problems, the list is the flat
                 concatenation [seg0 | seg1 | ...] in declaration order, same
                 as what the worker received in the task payload. Integers are
                 sent as JSON integers and floats as JSON floats; the master
                 converts each value to the type of the destination variable.
    """
    objectives:  list[float]
    constraints: list[float] | None = field(default=None)
    variables:   Variables | None = field(default=None)


class Evaluator(ABC):
    """
    Base class for evaluation functions.

    Subclass this when you need stateful or configurable evaluators.
    For simple cases, pass a plain function to Worker.run() instead.
    """

    @abstractmethod
    def evaluate(self, variables: Variables) -> EvalResult:
        """
        Evaluate a candidate solution.

        Args:
            variables: Decision variables sent by the master — ints for an
                       integer-encoded problem, floats for a real-encoded one.

        Returns:
            EvalResult with at least one objective value.
        """
        ...

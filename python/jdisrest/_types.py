from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class EvalResult:
    """
    Result of evaluating one solution.

    objectives:  List of objective values (minimization assumed by jMetal).
                 Negate to maximize: EvalResult(objectives=[-throughput])
    constraints: Optional list of constraint values.
                 jMetal convention: <= 0 means satisfied, > 0 means violated.
    variables:   Optional repaired/modified decision vector. When set, the
                 master overwrites the original Solution.variables with this
                 list before archiving the result, making the search Lamarckian
                 (children of the repaired solution inherit the relocations).
                 Leave as None to keep the master's original variables.
                 For CompositeSolution problems, the list is the flat
                 concatenation [seg0 | seg1 | ...] in declaration order, same
                 as what the worker received in the task payload.
    """
    objectives:  list[float]
    constraints: list[float] | None = field(default=None)
    variables:   list[int]   | None = field(default=None)


class Evaluator(ABC):
    """
    Base class for evaluation functions.

    Subclass this when you need stateful or configurable evaluators.
    For simple cases, pass a plain function to Worker.run() instead.
    """

    @abstractmethod
    def evaluate(self, variables: list[int]) -> EvalResult:
        """
        Evaluate a candidate solution.

        Args:
            variables: Integer decision variables sent by the master.

        Returns:
            EvalResult with at least one objective value.
        """
        ...

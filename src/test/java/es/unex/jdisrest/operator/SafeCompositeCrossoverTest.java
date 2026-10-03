package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.CompositeCrossover;
import org.uma.jmetal.operator.crossover.impl.NPointCrossover;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anti-aliasing copy, segment count check and argument rules of {@link SafeCompositeCrossover}.
 *
 * <p>The segment operators are jMetal {@link NPointCrossover}s with probability 0, which
 * return their parents unchanged: the case where jMetal's {@link CompositeCrossover} hands out
 * offspring that share the parents' segments.
 */
class SafeCompositeCrossoverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A composite solution of an integer segment holding {@code integer} and a real segment holding {@code real}. */
    static CompositeSolution composite(int integer, double real) {
        IntegerSolution integers = new DefaultIntegerSolution(List.of(Bounds.create(0, 100)), 2, 0);
        integers.variables().set(0, integer);
        DoubleSolution reals = new DefaultDoubleSolution(List.of(Bounds.create(0.0, 100.0)), 2, 0);
        reals.variables().set(0, real);
        return new CompositeSolution(List.of(integers, reals));
    }

    /** One crossover per segment, each returning its parents unchanged. */
    static List<CrossoverOperator<?>> bypassing() {
        return List.of(new NPointCrossover<IntegerSolution, Integer>(0.0, 1), new NPointCrossover<DoubleSolution, Double>(0.0, 1));
    }

    static List<CompositeSolution> parents() {
        return List.of(composite(1, 1.0), composite(2, 2.0));
    }

    // ── Copies ────────────────────────────────────────────────────────────────

    @Test
    void offspringNeverShareASegmentWithAParent() {
        List<CompositeSolution> parents = parents();
        List<CompositeSolution> aliased = new CompositeCrossover(bypassing()).execute(parents);
        assertSame(parents.get(0).variables().get(0), aliased.get(0).variables().get(0),
            "the fixture must reproduce jMetal's aliasing");

        List<CompositeSolution> children = new SafeCompositeCrossover(bypassing()).execute(parents);

        for (CompositeSolution child : children) {
            for (Solution<?> segment : child.variables()) {
                for (CompositeSolution parent : parents) {
                    for (Solution<?> parentSegment : parent.variables()) {
                        assertNotSame(parentSegment, segment, "an offspring segment must never be a parent's segment");
                    }
                }
            }
        }
        ((IntegerSolution) children.get(0).variables().get(0)).variables().set(0, 99);
        ((DoubleSolution) children.get(1).variables().get(1)).variables().set(0, 99.0);
        assertEquals(1, ((IntegerSolution) parents.get(0).variables().get(0)).variables().get(0),
            "mutating an offspring in place must leave the parents untouched");
        assertEquals(2.0, ((DoubleSolution) parents.get(1).variables().get(1)).variables().get(0));
    }

    // ── Segment count ─────────────────────────────────────────────────────────

    @Test
    void parentsWithMoreSegmentsThanOperatorsAreRejectedWithBothCounts() {
        // CompositeCrossover alone throws IndexOutOfBoundsException here.
        var crossover = new SafeCompositeCrossover(List.of(new NPointCrossover<IntegerSolution, Integer>(0.0, 1)));

        InvalidConditionException error = assertThrows(InvalidConditionException.class, () -> crossover.execute(parents()));

        assertTrue(error.getMessage().contains("2 segments") && error.getMessage().contains("1 operators"),
            error.getMessage());
    }

    @Test
    void parentsWithFewerSegmentsThanOperatorsAreRejected() {
        // CompositeCrossover alone silently ignores the extra operator.
        var crossover = new SafeCompositeCrossover(List.of(new NPointCrossover<IntegerSolution, Integer>(0.0, 1),
            new NPointCrossover<DoubleSolution, Double>(0.0, 1), new NPointCrossover<DoubleSolution, Double>(0.0, 1)));

        assertThrows(InvalidConditionException.class, () -> crossover.execute(parents()));
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @Test
    void rejectsANullParentListOrParent() {
        var crossover = new SafeCompositeCrossover(bypassing());

        assertThrows(NullParameterException.class, () -> crossover.execute(null));
        assertThrows(NullParameterException.class, () -> crossover.execute(Arrays.asList(composite(1, 1.0), null)));
    }

    @Test
    void rejectsAParentCountOtherThanTwo() {
        var crossover = new SafeCompositeCrossover(bypassing());

        assertThrows(InvalidConditionException.class, () -> crossover.execute(List.of(composite(1, 1.0))));
    }

    @Test
    void rejectsANullOperatorList() {
        assertThrows(NullParameterException.class, () -> new SafeCompositeCrossover(null));
    }

    @Test
    void exposesTheSegmentOperatorsInOrder() {
        List<CrossoverOperator<?>> operators = bypassing();
        var crossover = new SafeCompositeCrossover(operators);

        assertEquals(2, crossover.getOperators().size());
        assertSame(operators.get(0), crossover.getOperators().get(0));
        assertSame(operators.get(1), crossover.getOperators().get(1));
        assertEquals(1.0, crossover.crossoverProbability(), "CompositeCrossover's constant");
        assertEquals(2, crossover.numberOfRequiredParents());
        assertEquals(2, crossover.numberOfGeneratedChildren());
    }
}

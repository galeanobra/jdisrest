package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed variables, rounding, copies and setter checks of {@link IntegerBLXCrossover}.
 *
 * <p>Most tests feed a scripted generator, so every child value can be worked out by hand: the
 * first number decides whether the crossover fires, then two per variable give the children's
 * positions in the blend interval. The drift test draws from a seeded {@link Random}.
 */
class IntegerBLXCrossoverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution with the given bounds and values. */
    static IntegerSolution solution(List<Bounds<Integer>> bounds, Integer... values) {
        IntegerSolution solution = new DefaultIntegerSolution(bounds, 2, 0);
        for (int i = 0; i < values.length; i++) solution.variables().set(i, values[i]);
        return solution;
    }

    /** A one-variable solution in [-100, 100]. */
    static IntegerSolution single(int value) {
        return solution(List.of(Bounds.create(-100, 100)), value);
    }

    /** Generator that returns the given numbers in order and fails when asked for one more. */
    static final class Script implements RandomGenerator<Double> {
        final Deque<Double> numbers;

        Script(Double... numbers) {
            this.numbers = new ArrayDeque<>(List.of(numbers));
        }

        @Override
        public Double getRandomValue() {
            assertFalse(numbers.isEmpty(), "the operator drew more random numbers than scripted");
            return numbers.removeFirst();
        }
    }

    static IntegerBLXCrossover crossover(double alpha, RandomGenerator<Double> random) {
        return new IntegerBLXCrossover(0.9, alpha, new RepairDoubleSolutionWithBoundValue(), random);
    }

    // ── Fixed variables ───────────────────────────────────────────────────────

    @Test
    void variableWithEqualBoundsKeepsItsValueWithoutDrawing() {
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 10), Bounds.create(3, 3), Bounds.create(0, 10));
        // Trigger, then two draws for variable 0 and two for variable 2: none for variable 1.
        Script script = new Script(0.0, 0.5, 0.5, 0.5, 0.5);

        List<IntegerSolution> children = crossover(0.5, script)
            .execute(List.of(solution(bounds, 2, 3, 8), solution(bounds, 6, 3, 4)));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        for (IntegerSolution child : children) {
            assertEquals(List.of(4, 3, 6), child.variables(),
                "the fixed variable keeps its value and the others are the centres of their intervals");
        }
    }

    @Test
    void defaultRepairToleratesAVariableWithEqualBounds() {
        // jMetal's bound repair throws on lb == ub; before 1.2.0 this crossover threw whenever it
        // fired. The default constructor draws from JMetalRandom, so a seeded generator is
        // swapped in and put back; the solutions are built first, since they draw from it too.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(-5, 5), Bounds.create(7, 7), Bounds.create(-5, 5));
        IntegerSolution first = solution(bounds, -5, 7, 5);
        IntegerSolution second = solution(bounds, 5, 7, -5);
        var crossover = new IntegerBLXCrossover(1.0);
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(17));
            for (int run = 0; run < 200; run++) {
                for (IntegerSolution child : crossover.execute(List.of(first, second))) {
                    assertEquals(7, child.variables().get(1), "a variable whose bounds are equal keeps that value");
                    for (int i : new int[] {0, 2}) {
                        int value = child.variables().get(i);
                        assertTrue(value >= -5 && value <= 5, "variable " + i + " left its bounds: " + value);
                    }
                }
            }
        } finally {
            global.setRandomGenerator(previous);
        }
    }

    // ── Rounding ──────────────────────────────────────────────────────────────

    @Test
    void largerParentOfANonNegativePairIsReachableWithAlphaZero() {
        // Interval [2, 5): 2 + 0.9 * 3 = 4.7 and 2 + 0.05 * 3 = 2.15.
        List<IntegerSolution> children = crossover(0.0, new Script(0.0, 0.9, 0.05))
            .execute(List.of(single(2), single(5)));

        assertEquals(5, children.get(0).variables().get(0), "4.7 rounds to the larger parent (truncation gave 4)");
        assertEquals(2, children.get(1).variables().get(0));
    }

    @Test
    void smallerParentOfANegativePairIsReachableWithAlphaZero() {
        // Interval [-5, -2): -5 + 0.05 * 3 = -4.85 and -5 + 0.95 * 3 = -2.15.
        List<IntegerSolution> children = crossover(0.0, new Script(0.0, 0.05, 0.95))
            .execute(List.of(single(-5), single(-2)));

        assertEquals(-5, children.get(0).variables().get(0), "-4.85 rounds to the smaller parent (truncation gave -4)");
        assertEquals(-2, children.get(1).variables().get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, -5})
    void childrenAreCentredOnTheBlendInterval(int smallerParent) {
        // Parents x and x + 3 with alpha 0.5: the interval is [x - 1.5, x + 4.5], centred on
        // x + 1.5. Truncation toward zero moved the mean about half a unit toward 0.
        Random random = new Random(42);
        var crossover = new IntegerBLXCrossover(1.0, 0.5, new RepairDoubleSolutionWithBoundValue(), random::nextDouble);
        List<IntegerSolution> parents = List.of(single(smallerParent), single(smallerParent + 3));
        double sum = 0;
        int children = 0;
        for (int run = 0; run < 20_000; run++) {
            for (IntegerSolution child : crossover.execute(parents)) {
                sum += child.variables().get(0);
                children++;
            }
        }

        assertEquals(smallerParent + 1.5, sum / children, 0.03, "the children must not drift toward 0");
    }

    @Test
    void samplesOutsideTheBoundsAreClampedBeforeRounding() {
        // Parents 0 and 3 in [0, 3] with alpha 1: the interval is [-3, 6].
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 3));
        List<IntegerSolution> children = crossover(1.0, new Script(0.0, 0.0, 0.99))
            .execute(List.of(solution(bounds, 0), solution(bounds, 3)));

        assertEquals(0, children.get(0).variables().get(0));
        assertEquals(3, children.get(1).variables().get(0));
    }

    // ── Copies ────────────────────────────────────────────────────────────────

    @Test
    void childrenAreCopiesWhenTheCrossoverDoesNotFire() {
        IntegerSolution first = single(2);
        IntegerSolution second = single(5);

        List<IntegerSolution> children = crossover(0.5, new Script(0.95)).execute(List.of(first, second));

        assertNotSame(first, children.get(0), "a child must never be a parent");
        assertNotSame(second, children.get(1), "a child must never be a parent");
        assertEquals(first.variables(), children.get(0).variables());
        assertEquals(second.variables(), children.get(1).variables());
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN})
    void alphaSetterRejectsWhatTheConstructorRejects(double alpha) {
        var crossover = new IntegerBLXCrossover(0.9, 0.3);

        assertThrows(InvalidConditionException.class, () -> crossover.alpha(alpha));
        assertThrows(InvalidConditionException.class, () -> new IntegerBLXCrossover(0.9, alpha));
        assertEquals(0.3, crossover.alpha(), "a rejected value must leave alpha unchanged");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void probabilitySetterRejectsWhatTheConstructorRejects(double probability) {
        var crossover = new IntegerBLXCrossover(0.9);

        assertThrows(InvalidProbabilityValueException.class, () -> crossover.crossoverProbability(probability));
        assertEquals(0.9, crossover.crossoverProbability(), "a rejected value must leave the probability unchanged");
    }

    @Test
    void settersAcceptValidValues() {
        var crossover = new IntegerBLXCrossover(0.9);

        crossover.alpha(0.0);
        crossover.crossoverProbability(1.0);

        assertEquals(0.0, crossover.alpha());
        assertEquals(1.0, crossover.crossoverProbability());
        assertEquals(Collections.nCopies(2, 2), List.of(crossover.numberOfRequiredParents(),
            crossover.numberOfGeneratedChildren()));
    }
}

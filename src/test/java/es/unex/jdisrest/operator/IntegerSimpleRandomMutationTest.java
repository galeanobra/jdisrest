package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Value draw, range coverage and argument rules of {@link IntegerSimpleRandomMutation}.
 *
 * <p>The scripted generator proves that both the trigger and the new value come from the
 * injected generator: it hands out exactly the numbers the mutation needs and fails on one
 * more.
 */
class IntegerSimpleRandomMutationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution of {@code size} variables in [lower, upper], all at {@code value}. */
    static IntegerSolution solution(int size, int lower, int upper, int value) {
        IntegerSolution solution = new DefaultIntegerSolution(Collections.nCopies(size, Bounds.create(lower, upper)), 2, 0);
        for (int i = 0; i < size; i++) solution.variables().set(i, value);
        return solution;
    }

    /**
     * A one-variable solution whose bounds are set after construction: DefaultIntegerSolution
     * draws its initial value with JMetalRandom.nextInt, which cannot handle every range, and it
     * keeps the bounds list it was given.
     */
    static IntegerSolution solutionWithBounds(int lower, int upper, int value) {
        List<Bounds<Integer>> bounds = new ArrayList<>(List.of(Bounds.create(0, 1)));
        IntegerSolution solution = new DefaultIntegerSolution(bounds, 2, 0);
        bounds.set(0, Bounds.create(lower, upper));
        solution.variables().set(0, value);
        return solution;
    }

    /** Generator that returns the given numbers in order and fails when asked for one more. */
    static RandomGenerator<Double> script(Double... numbers) {
        Deque<Double> queue = new ArrayDeque<>(List.of(numbers));
        return () -> {
            assertFalse(queue.isEmpty(), "the operator drew more random numbers than scripted");
            return queue.removeFirst();
        };
    }

    // ── Value draw ────────────────────────────────────────────────────────────

    @Test
    void newValueComesFromTheInjectedGenerator() {
        // Variable 0: trigger, value 0.55 -> 5. Variable 1: no trigger. Variable 2: trigger, value 0.0 -> 0.
        var mutation = new IntegerSimpleRandomMutation(0.5, script(0.1, 0.55, 0.9, 0.2, 0.0));
        IntegerSolution solution = solution(3, 0, 9, 7);

        IntegerSolution mutated = mutation.execute(solution);

        assertSame(solution, mutated, "the solution is mutated in place");
        assertEquals(List.of(5, 7, 0), mutated.variables(),
            "the new values must be lb + floor(r * (ub - lb + 1)) of the injected draws");
    }

    @Test
    void bothEndsOfTheRangeAreReachable() {
        assertEquals(3, IntegerSimpleRandomMutation.uniformInt(0.0, 3, 7));
        assertEquals(7, IntegerSimpleRandomMutation.uniformInt(Math.nextDown(1.0), 3, 7),
            "the upper bound is reachable (jMetal's class of the same name never draws it)");
        assertEquals(7, IntegerSimpleRandomMutation.uniformInt(1.0, 3, 7), "a draw of 1.0 is capped at the upper bound");
        assertEquals(-5, IntegerSimpleRandomMutation.uniformInt(0.0, -5, -1),
            "the lower bound of a negative range is reachable");
    }

    @Test
    void everyValueOfTheRangeIsEquallyLikely() {
        Random random = new Random(8);
        var mutation = new IntegerSimpleRandomMutation(1.0, random::nextDouble);
        IntegerSolution solution = solution(10, -2, 2, 0);
        int[] counts = new int[5];
        for (int run = 0; run < 10_000; run++) {
            for (int value : mutation.execute(solution).variables()) counts[value + 2]++;
        }

        for (int value = -2; value <= 2; value++) {
            assertEquals(0.2, counts[value + 2] / 100_000.0, 0.01, "frequency of " + value);
        }
    }

    @Test
    void rangeSpanningTheWholeIntTypeDoesNotOverflow() {
        assertEquals(Integer.MIN_VALUE, IntegerSimpleRandomMutation.uniformInt(0.0, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(0, IntegerSimpleRandomMutation.uniformInt(0.5, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE,
            IntegerSimpleRandomMutation.uniformInt(Math.nextDown(1.0), Integer.MIN_VALUE, Integer.MAX_VALUE));

        // Before 1.2.0 JMetalRandom.nextInt threw IllegalArgumentException on such a range.
        IntegerSolution solution = solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        new IntegerSimpleRandomMutation(1.0, script(0.0, 0.75)).execute(solution);
        assertEquals(1 << 30, solution.variables().get(0));
    }

    @Test
    void variableWithEqualBoundsKeepsItsValue() {
        IntegerSolution solution = solution(1, 4, 4, 4);

        new IntegerSimpleRandomMutation(1.0, script(0.0, 0.7)).execute(solution);

        assertEquals(4, solution.variables().get(0));
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsANegativeOrNaNProbabilityNamingIt(double probability) {
        JMetalException error = assertThrows(JMetalException.class, () -> new IntegerSimpleRandomMutation(probability));

        assertTrue(error.getMessage().endsWith(String.valueOf(probability)),
            "the message must print the rejected argument, not the unassigned field: " + error.getMessage());
    }

    @Test
    void rejectsANullSolution() {
        assertThrows(JMetalException.class, () -> new IntegerSimpleRandomMutation(0.1).execute(null));
    }
}

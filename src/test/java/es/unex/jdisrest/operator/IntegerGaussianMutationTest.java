package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Noise source, reproducibility, step distribution and overflow handling of
 * {@link IntegerGaussianMutation}.
 *
 * <p>The scripted draws pick the Box–Muller output on purpose: {@code u1 = 1 - exp(-z²/2)}
 * gives a magnitude of {@code z}, and {@code u2} = 0 or 0.5 gives its sign.
 */
class IntegerGaussianMutationTest {

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

    /** The first Box–Muller draw that gives a normal number of magnitude {@code z}. */
    static double magnitude(double z) {
        return 1.0 - Math.exp(-z * z / 2.0);
    }

    // ── Noise source ──────────────────────────────────────────────────────────

    @Test
    void noiseComesFromTheInjectedGenerator() {
        // sigma = 50. Variable 0: z = +1 -> 20 + 50. Variable 1: z = -0.5 -> 60 - 25.
        // Variable 2: no trigger. Before 1.2.0 the noise came from a static unseeded Random.
        var mutation = new IntegerGaussianMutation(0.5,
            script(0.1, magnitude(1.0), 0.0, 0.2, magnitude(0.5), 0.5, 0.9));
        IntegerSolution solution = solution(3, 0, 100, 20);
        solution.variables().set(1, 60);

        IntegerSolution mutated = mutation.execute(solution);

        assertSame(solution, mutated, "the solution is mutated in place");
        assertEquals(List.of(70, 35, 20), mutated.variables());
    }

    @Test
    void defaultConstructorIsReproducibleWithASeededJMetalRandom() {
        // The solutions are built before the seeded generator is swapped in, because
        // DefaultIntegerSolution draws its initial values from JMetalRandom too.
        List<IntegerSolution> first = new ArrayList<>();
        List<IntegerSolution> second = new ArrayList<>();
        for (int run = 0; run < 50; run++) {
            first.add(solution(10, 0, 50, 25));
            second.add(solution(10, 0, 50, 25));
        }
        var mutation = new IntegerGaussianMutation(0.5);
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(5));
            first.forEach(mutation::execute);
            global.setRandomGenerator(new JavaRandomGenerator(5));
            second.forEach(mutation::execute);
        } finally {
            global.setRandomGenerator(previous);
        }

        assertTrue(first.stream().anyMatch(s -> !s.variables().equals(Collections.nCopies(10, 25))),
            "the fixture must actually mutate");
        for (int run = 0; run < 50; run++) {
            assertEquals(first.get(run).variables(), second.get(run).variables(),
                "the same seed must give the same mutations (run " + run + ")");
        }
    }

    // ── Step distribution ─────────────────────────────────────────────────────

    @Test
    void noiseIsNormalWithSigmaHalfTheRange() {
        // From 0 in [-1000, 1000], sigma = 1000: |z| < 0.6745 half of the time, |z| >= 1 (a
        // clamp to a bound) 31.7% of the time, and either sign half of the time.
        Random random = new Random(3);
        var mutation = new IntegerGaussianMutation(1.0, random::nextDouble);
        int runs = 40_000;
        int nearCentre = 0;
        int clamped = 0;
        int positive = 0;
        for (int run = 0; run < runs; run++) {
            int value = mutation.execute(solution(1, -1000, 1000, 0)).variables().get(0);
            if (Math.abs(value) < 674.5) nearCentre++;
            if (Math.abs(value) == 1000) clamped++;
            if (value > 0) positive++;
        }

        assertEquals(0.5, nearCentre / (double) runs, 0.01, "median of |z| must be 0.6745");
        assertEquals(0.3173, clamped / (double) runs, 0.01, "P(|z| >= 1) must be 0.3173");
        assertEquals(0.5, positive / (double) runs, 0.01, "the noise must be symmetric");
    }

    @Test
    void variableWithEqualBoundsKeepsItsValue() {
        IntegerSolution solution = solution(1, 4, 4, 4);

        new IntegerGaussianMutation(1.0, script(0.0, magnitude(3.0), 0.0)).execute(solution);

        assertEquals(4, solution.variables().get(0));
    }

    // ── Overflow ──────────────────────────────────────────────────────────────

    @Test
    void sigmaIsHalfTheRangeEvenWhenTheRangeOverflowsAnInt() {
        // ub - lb overflowed to -1 in int arithmetic, which gave sigma = 2 and a step of 2.
        IntegerSolution solution = solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);

        new IntegerGaussianMutation(1.0, script(0.0, magnitude(1.0), 0.0)).execute(solution);

        assertEquals(Integer.MAX_VALUE, solution.variables().get(0), "a step of one sigma is half of the int range");
    }

    @Test
    void roundingSaturatesInsteadOfWrappingNearTheTopOfTheIntRange() {
        // sigma = 5: MAX - 1 + 5 used to wrap to a negative int and be clamped to the lower bound.
        IntegerSolution solution = solutionWithBounds(Integer.MAX_VALUE - 10, Integer.MAX_VALUE, Integer.MAX_VALUE - 1);

        new IntegerGaussianMutation(1.0, script(0.0, magnitude(1.0), 0.0)).execute(solution);

        assertEquals(Integer.MAX_VALUE, solution.variables().get(0), "a value above the upper bound is clamped to it");
    }

    @Test
    void firstDrawOfOneGivesABoundedValue() {
        // 1 - u1 = 0 would make the logarithm infinite; it is kept finite and the value clamped.
        IntegerSolution solution = solution(1, 0, 10, 5);

        new IntegerGaussianMutation(1.0, script(0.0, 1.0, 0.5)).execute(solution);

        assertEquals(0, solution.variables().get(0));
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsANegativeOrNaNProbabilityNamingIt(double probability) {
        JMetalException error = assertThrows(JMetalException.class, () -> new IntegerGaussianMutation(probability));

        assertTrue(error.getMessage().endsWith(String.valueOf(probability)),
            "the message must print the rejected argument, not the unassigned field: " + error.getMessage());
    }
}

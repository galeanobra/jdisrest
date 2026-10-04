package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.integerproblem.IntegerProblem;
import org.uma.jmetal.problem.integerproblem.impl.AbstractIntegerProblem;
import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
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
 * Rounding, draws, bounds, repair and argument rules of {@link IntegerPolynomialMutation},
 * against jMetal's class of the same name where they differ.
 *
 * <p>The scripted tests use a distribution index of 0, which makes the step linear in its draw:
 * a draw {@code r} up to 0.5 moves the value to {@code lb + 2r (x - lb)}, and a larger one to
 * {@code x + (2r - 1)(ub - x)}. The statistical tests draw from a seeded {@link Random}.
 */
class IntegerPolynomialMutationTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution with the given bounds and values. */
    static IntegerSolution solution(List<Bounds<Integer>> bounds, Integer... values) {
        IntegerSolution solution = new DefaultIntegerSolution(bounds, 2, 0);
        for (int i = 0; i < values.length; i++) solution.variables().set(i, values[i]);
        return solution;
    }

    /** A one-variable solution in [lower, upper] at {@code value}. */
    static IntegerSolution single(int lower, int upper, int value) {
        return solution(List.of(Bounds.create(lower, upper)), value);
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

    /**
     * Runs {@code action} with JMetalRandom drawing its doubles in [0, 1) from {@code script}, then
     * restores its generator. Any other draw fails, such as the one of jMetal's random repair.
     */
    static void withJMetalRandom(Script script, Runnable action) {
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        try {
            global.setRandomGenerator(new PseudoRandomGenerator() {
                @Override
                public double nextDouble() {
                    return script.getRandomValue();
                }

                @Override
                public double nextDouble(double lowerBound, double upperBound) {
                    return fail("unscripted draw in [" + lowerBound + ", " + upperBound + ")");
                }

                @Override
                public int nextInt(int lowerBound, int upperBound) {
                    return fail("unscripted draw in [" + lowerBound + ", " + upperBound + "]");
                }

                @Override
                public void setSeed(long seed) {
                    fail("unexpected seed");
                }

                @Override
                public long getSeed() {
                    return 0;
                }

                @Override
                public String getName() {
                    return "script";
                }
            });
            action.run();
        } finally {
            global.setRandomGenerator(previous);
        }
    }

    /** A problem of {@code numberOfVariables} variables in [0, 9]. */
    static IntegerProblem problem(int numberOfVariables) {
        return new AbstractIntegerProblem() {
            {
                numberOfObjectives(1);
                variableBounds(Collections.nCopies(numberOfVariables, 0), Collections.nCopies(numberOfVariables, 9));
            }

            @Override
            public IntegerSolution evaluate(IntegerSolution solution) {
                return solution;
            }
        };
    }

    static IntegerPolynomialMutation mutation(double distributionIndex, RandomGenerator<Double> random) {
        return new IntegerPolynomialMutation(1.0, distributionIndex, new RepairDoubleSolutionWithBoundValue(), random);
    }

    static org.uma.jmetal.operator.mutation.impl.IntegerPolynomialMutation jMetal(
            double distributionIndex, RandomGenerator<Double> random) {
        return new org.uma.jmetal.operator.mutation.impl.IntegerPolynomialMutation(
            1.0, distributionIndex, new RepairDoubleSolutionWithBoundValue(), random);
    }

    // ── Rounding ──────────────────────────────────────────────────────────────

    @Test
    void newValueIsRoundedToTheNearestInteger() {
        // From 5 in [0, 10]: 0.37 gives 3.7 and 0.87 gives 8.7. From -5 in [-10, 0]: 0.13 gives -8.7.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 10), Bounds.create(0, 10), Bounds.create(-10, 0));
        Script script = new Script(0.0, 0.37, 0.0, 0.87, 0.0, 0.13);

        IntegerSolution solution = solution(bounds, 5, 5, -5);
        IntegerSolution mutated = mutation(0.0, script).execute(solution);
        IntegerSolution jMetalMutated = jMetal(0.0, new Script(0.0, 0.37, 0.0, 0.87, 0.0, 0.13))
            .execute(solution(bounds, 5, 5, -5));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertSame(solution, mutated, "the solution is mutated in place");
        assertEquals(List.of(4, 9, -9), mutated.variables());
        assertEquals(List.of(3, 8, -8), jMetalMutated.variables(), "jMetal truncates toward 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {5, -5})
    void mutationFromTheMiddleOfTheRangeIsCentredOnTheValue(int value) {
        // From the middle of [value - 5, value + 5] the real steps are symmetric. jMetal's
        // truncation moves the mean half a unit toward 0.
        Random random = new Random(42);
        Random jMetalRandom = new Random(42);
        var mutation = mutation(20.0, random::nextDouble);
        var jMetal = jMetal(20.0, jMetalRandom::nextDouble);
        double sum = 0;
        double jMetalSum = 0;
        int changes = 0;
        for (int run = 0; run < 200_000; run++) {
            int mutated = mutation.execute(single(value - 5, value + 5, value)).variables().get(0);
            sum += mutated;
            if (mutated != value) changes++;
            jMetalSum += jMetal.execute(single(value - 5, value + 5, value)).variables().get(0);
        }

        assertTrue(changes > 50_000, "the fixture must actually mutate: " + changes);
        assertEquals(value, sum / 200_000, 0.01, "the mutations must not drift toward 0");
        assertEquals(value - Math.signum(value) * 0.5, jMetalSum / 200_000, 0.01,
            "jMetal's mutations drift half a unit toward 0");
    }

    @Test
    void zeroAndOneChangeIntoEachOtherEquallyOften() {
        // With a distribution index of 0 a variable in [0, 1] changes in a quarter of its
        // mutations either way. jMetal never turns a 0 into a 1 and turns a 1 into a 0 half of
        // the time.
        Random random = new Random(9);
        Random jMetalRandom = new Random(9);
        var mutation = mutation(0.0, random::nextDouble);
        var jMetal = jMetal(0.0, jMetalRandom::nextDouble);
        int[] changes = new int[2];
        int[] jMetalChanges = new int[2];
        for (int run = 0; run < 100_000; run++) {
            for (int value = 0; value <= 1; value++) {
                if (mutation.execute(single(0, 1, value)).variables().get(0) != value) changes[value]++;
                if (jMetal.execute(single(0, 1, value)).variables().get(0) != value) jMetalChanges[value]++;
            }
        }

        assertEquals(0.25, changes[0] / 100_000.0, 0.01, "frequency of 0 -> 1");
        assertEquals(0.25, changes[1] / 100_000.0, 0.01, "frequency of 1 -> 0");
        assertEquals(0, jMetalChanges[0], "jMetal never turns a 0 into a 1");
        assertEquals(0.5, jMetalChanges[1] / 100_000.0, 0.01, "jMetal turns a 1 into a 0 half of the time");
    }

    @Test
    void drawsAsJMetalAndDiffersOnlyInTheRounding() {
        // Same seed, same draws: on non-negative values a rounded value is the truncated one or
        // one more, and both generators end in step.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 20), Bounds.create(4, 4), Bounds.create(0, 3), Bounds.create(0, 20));
        Random random = new Random(11);
        Random jMetalRandom = new Random(11);
        Random values = new Random(12);
        var mutation = new IntegerPolynomialMutation(0.7, 5.0, new RepairDoubleSolutionWithBoundValue(), random::nextDouble);
        var jMetal = new org.uma.jmetal.operator.mutation.impl.IntegerPolynomialMutation(
            0.7, 5.0, new RepairDoubleSolutionWithBoundValue(), jMetalRandom::nextDouble);
        int differences = 0;
        for (int run = 0; run < 2_000; run++) {
            Integer[] start = {values.nextInt(21), 4, values.nextInt(4), values.nextInt(21)};
            List<Integer> mutated = mutation.execute(solution(bounds, start)).variables();
            List<Integer> jMetalMutated = jMetal.execute(solution(bounds, start)).variables();
            for (int i = 0; i < bounds.size(); i++) {
                int difference = mutated.get(i) - jMetalMutated.get(i);
                assertTrue(difference == 0 || difference == 1, "variable " + i + ": " + difference);
                differences += difference;
            }
        }

        assertTrue(differences > 0, "the fixture must mutate some variables");
        assertEquals(jMetalRandom.nextDouble(), random.nextDouble(), "both must draw the same numbers");
    }

    // ── Bounds and repair ─────────────────────────────────────────────────────

    @Test
    void variableWithEqualBoundsTakesThatValueWithoutDrawingOrRepairing() {
        // Trigger for variable 0, which needs no step draw; trigger and step for variable 1.
        // jMetal's bound repair throws on lb == ub, so it must not be called.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(4, 4), Bounds.create(0, 10));
        Script script = new Script(0.0, 0.0, 0.37);

        IntegerSolution mutated = mutation(0.0, script).execute(solution(bounds, 4, 5));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(4, 4), mutated.variables());
    }

    @Test
    void repairedValueIsRoundedAndClampedToTheBounds() {
        // From 5 in [0, 10], 0.87 gives 8.7; a repair that returns 12.6 is clamped to 10.
        List<double[]> calls = new ArrayList<>();
        RepairDoubleSolution repair = (value, lowerBound, upperBound) -> {
            calls.add(new double[] {value, lowerBound, upperBound});
            return 12.6;
        };

        IntegerSolution mutated = new IntegerPolynomialMutation(1.0, 0.0, repair, new Script(0.0, 0.87))
            .execute(single(0, 10, 5));

        assertEquals(10, mutated.variables().get(0), "the value must stay within the bounds whatever the repair returns");
        assertEquals(1, calls.size(), "the repair must be called once");
        assertArrayEquals(new double[] {8.7, 0.0, 10.0}, calls.get(0), 1e-9);
    }

    @Test
    void repairGivenToTheConstructorIsUsedWithJMetalRandom() {
        // Trigger and step 0.87 from JMetalRandom: from 5 in [0, 10] the repair gets 8.7, and
        // its 2.4 is rounded to 2.
        List<double[]> calls = new ArrayList<>();
        RepairDoubleSolution repair = (value, lowerBound, upperBound) -> {
            calls.add(new double[] {value, lowerBound, upperBound});
            return 2.4;
        };
        var mutation = new IntegerPolynomialMutation(1.0, 0.0, repair);
        IntegerSolution solution = single(0, 10, 5);
        Script script = new Script(0.0, 0.87);

        withJMetalRandom(script, () -> mutation.execute(solution));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(2, solution.variables().get(0), "the value must be the repair's, rounded");
        assertEquals(1, calls.size(), "the repair must be called once");
        assertArrayEquals(new double[] {8.7, 0.0, 10.0}, calls.get(0), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"()", "(double)", "(double, double)", "(IntegerProblem, double)"})
    void defaultRepairClampsToTheBoundsWithoutDrawing(String constructor) {
        // From 15, outside [0, 10], the step 0.45 gives 14.95 with the default distribution
        // index of 20, and 13.5 with 0. jMetal's bound repair returns 10 without drawing; its
        // opposite-bound repair would return 0, and its random repair would draw from
        // JMetalRandom. The solution is built before the script is swapped in, because
        // DefaultIntegerSolution draws its initial values from JMetalRandom too.
        var mutation = switch (constructor) {
            case "()" -> new IntegerPolynomialMutation();
            case "(double)" -> new IntegerPolynomialMutation(0.5);
            case "(double, double)" -> new IntegerPolynomialMutation(0.5, 0.0);
            case "(IntegerProblem, double)" -> new IntegerPolynomialMutation(problem(4), 0.0);
            default -> throw new IllegalArgumentException(constructor);
        };
        IntegerSolution solution = single(0, 10, 15);
        Script script = new Script(0.0, 0.45);

        withJMetalRandom(script, () -> mutation.execute(solution));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(10, solution.variables().get(0), "the default repair must clamp to the upper bound");
    }

    @Test
    void rangeSpanningTheWholeIntTypeDoesNotOverflow() {
        // From 0: 0.25 gives MIN_VALUE / 2 and 0.8 gives 0.6 * MAX_VALUE = 1288490188.2.
        IntegerSolution down = mutation(0.0, new Script(0.0, 0.25))
            .execute(solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
        IntegerSolution up = mutation(0.0, new Script(0.0, 0.8))
            .execute(solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 0));

        assertEquals(-(1 << 30), down.variables().get(0));
        assertEquals(1288490188, up.variables().get(0));
    }

    @Test
    void mutatedValuesStayWithinTheBounds() {
        List<Bounds<Integer>> bounds = List.of(Bounds.create(-3, 4), Bounds.create(0, 1), Bounds.create(10, 12));
        Random random = new Random(3);
        var mutation = mutation(0.0, random::nextDouble);
        for (int run = 0; run < 10_000; run++) {
            IntegerSolution mutated = mutation.execute(solution(bounds, -3, 1, 12));
            for (int i = 0; i < bounds.size(); i++) {
                int value = mutated.variables().get(i);
                assertTrue(value >= bounds.get(i).getLowerBound() && value <= bounds.get(i).getUpperBound(),
                    "variable " + i + " left its bounds: " + value);
            }
        }
    }

    // ── Draws ─────────────────────────────────────────────────────────────────

    @Test
    void onlyTheTriggeredVariablesAreMutated() {
        // Variable 0: no trigger (0.6 > 0.5). Variable 1: trigger and step 0.87.
        Script script = new Script(0.6, 0.4, 0.87);

        IntegerSolution mutated = new IntegerPolynomialMutation(0.5, 0.0, new RepairDoubleSolutionWithBoundValue(), script)
            .execute(solution(Collections.nCopies(2, Bounds.create(0, 10)), 5, 5));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(5, 9), mutated.variables());
    }

    @Test
    void defaultGeneratorIsJMetalRandom() {
        // The solutions are built before the seeded generator is swapped in, because
        // DefaultIntegerSolution draws its initial values from JMetalRandom too.
        List<IntegerSolution> first = new ArrayList<>();
        List<IntegerSolution> second = new ArrayList<>();
        for (int run = 0; run < 50; run++) {
            first.add(solution(Collections.nCopies(10, Bounds.create(0, 50)), 25, 25, 25, 25, 25, 25, 25, 25, 25, 25));
            second.add(solution(Collections.nCopies(10, Bounds.create(0, 50)), 25, 25, 25, 25, 25, 25, 25, 25, 25, 25));
        }
        var mutation = new IntegerPolynomialMutation(0.5, 5.0);
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

    // ── Arguments ─────────────────────────────────────────────────────────────

    @Test
    void hasTheConstructorsAndMethodsOfJMetalsClass() {
        assertEquals(IntegerSBXCrossoverTest.publicSignatures(org.uma.jmetal.operator.mutation.impl.IntegerPolynomialMutation.class),
            IntegerSBXCrossoverTest.publicSignatures(IntegerPolynomialMutation.class), "changing the import must be enough");

        var perVariable = new IntegerPolynomialMutation(problem(4), 10.0);
        assertEquals(0.25, perVariable.mutationProbability(), "1 / numberOfVariables");
        assertEquals(10.0, perVariable.getDistributionIndex());

        var defaults = new IntegerPolynomialMutation();
        assertEquals(0.01, defaults.mutationProbability(), "jMetal's default probability");
        assertEquals(20.0, defaults.getDistributionIndex(), "jMetal's default distribution index");
        assertEquals(20.0, new IntegerPolynomialMutation(0.3).getDistributionIndex());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsANegativeOrNaNProbabilityNamingIt(double probability) {
        var mutation = new IntegerPolynomialMutation(0.1);

        JMetalException error = assertThrows(JMetalException.class, () -> new IntegerPolynomialMutation(probability));
        assertThrows(JMetalException.class, () -> mutation.setMutationProbability(probability));

        assertEquals("Mutation probability is negative: " + probability, error.getMessage());
        assertEquals(0.1, mutation.mutationProbability(), "a rejected value must leave the probability unchanged");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsANegativeOrNaNDistributionIndexNamingIt(double distributionIndex) {
        var mutation = new IntegerPolynomialMutation(0.1, 15.0);

        JMetalException error = assertThrows(JMetalException.class, () -> new IntegerPolynomialMutation(0.1, distributionIndex));
        assertThrows(JMetalException.class, () -> mutation.setDistributionIndex(distributionIndex));

        assertEquals("Distribution index is negative: " + distributionIndex, error.getMessage());
        assertEquals(15.0, mutation.getDistributionIndex(), "a rejected value must leave the index unchanged");
    }

    @Test
    void settersAcceptValidValuesAndAProbabilityAboveOne() {
        var mutation = new IntegerPolynomialMutation(0.1);

        mutation.setMutationProbability(1.5);
        mutation.setDistributionIndex(0.0);

        assertEquals(1.5, mutation.mutationProbability(), "as in jMetal, 1 or more mutates every variable");
        assertEquals(0.0, mutation.getDistributionIndex());
    }

    @Test
    void rejectsANullSolution() {
        assertThrows(JMetalException.class, () -> new IntegerPolynomialMutation(0.1).execute(null));
    }
}

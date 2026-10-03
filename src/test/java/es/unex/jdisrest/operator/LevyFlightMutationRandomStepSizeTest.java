package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.operator.mutation.impl.LevyFlightMutation;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step size draw, equivalence with jMetal's {@link LevyFlightMutation}, bound
 * repair and argument rules of {@link LevyFlightMutationRandomStepSize}.
 *
 * <p>Equivalence is checked by feeding the operator and the jMetal reference
 * the same seeded {@link Random} sequence: the first number of each call is the
 * step size draw, the rest are the Lévy steps.
 */
class LevyFlightMutationRandomStepSizeTest {

    static final int VARIABLES = 12;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** {@value #VARIABLES} variables in [-10, 10], all at 0.5, so most steps stay inside the bounds. */
    static DoubleSolution solution() {
        List<Bounds<Double>> bounds = Collections.nCopies(VARIABLES, Bounds.create(-10.0, 10.0));
        DoubleSolution solution = new DefaultDoubleSolution(bounds, 3, 0);
        for (int i = 0; i < VARIABLES; i++) solution.variables().set(i, 0.5);
        return solution;
    }

    /** Three variables in [-10, 10] at 0.5 around one fixed variable whose bounds are both 1.0. */
    static DoubleSolution solutionWithAFixedVariable() {
        List<Bounds<Double>> bounds = List.of(Bounds.create(-10.0, 10.0), Bounds.create(-10.0, 10.0),
            Bounds.create(1.0, 1.0), Bounds.create(-10.0, 10.0));
        DoubleSolution solution = new DefaultDoubleSolution(bounds, 2, 0);
        for (int i = 0; i < bounds.size(); i++) solution.variables().set(i, 0.5);
        solution.variables().set(2, 1.0);
        return solution;
    }

    /** jMetal's Lévy flight with the given step size, drawing from {@code random}. */
    static LevyFlightMutation levyFlight(double probability, double beta, double stepSize, Random random) {
        return new LevyFlightMutation(probability, beta, stepSize, new RepairDoubleSolutionWithBoundValue(),
            random::nextDouble);
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 2026})
    void mutatesLikeLevyFlightWithTheDrawnStepSize(long seed) {
        Random ours = new Random(seed);
        Random reference = new Random(seed);
        var mutation = new LevyFlightMutationRandomStepSize(1.0, 1.5, 0.5, new RepairDoubleSolutionWithBoundValue(),
            ours::nextDouble);
        double stepSize = reference.nextDouble() * 0.5;
        DoubleSolution solution = solution();

        DoubleSolution mutated = mutation.execute(solution);
        DoubleSolution expected = levyFlight(1.0, 1.5, stepSize, reference).execute(solution());

        assertSame(solution, mutated, "the solution is mutated in place and returned, as jMetal does");
        assertEquals(expected.variables(), mutated.variables(),
            "the mutation must be jMetal's Lévy flight with the first number times the maximum as step size");
    }

    @Test
    void eachSolutionDrawsItsOwnStepSize() {
        Random ours = new Random(11);
        Random reference = new Random(11);
        var mutation = new LevyFlightMutationRandomStepSize(0.5, 1.25, 0.5, new RepairDoubleSolutionWithBoundValue(),
            ours::nextDouble);

        DoubleSolution first = mutation.execute(solution());
        DoubleSolution second = mutation.execute(solution());

        // The reference draws a new step size from the same sequence before each Lévy flight.
        double firstStepSize = reference.nextDouble() * 0.5;
        DoubleSolution expectedFirst = levyFlight(0.5, 1.25, firstStepSize, reference).execute(solution());
        double secondStepSize = reference.nextDouble() * 0.5;
        DoubleSolution expectedSecond = levyFlight(0.5, 1.25, secondStepSize, reference).execute(solution());
        assertNotEquals(firstStepSize, secondStepSize, "the two solutions must draw different step sizes");
        assertEquals(expectedFirst.variables(), first.variables());
        assertEquals(expectedSecond.variables(), second.variables());
    }

    @Test
    void zeroStepSizeLeavesTheSolutionUnchanged() {
        var mutation = new LevyFlightMutationRandomStepSize(1.0, 1.5, 0.5, new RepairDoubleSolutionWithBoundValue(),
            () -> 0.0);

        DoubleSolution mutated = mutation.execute(solution());

        assertEquals(solution().variables(), mutated.variables(), "a step size of 0 moves no variable");
    }

    @Test
    void defaultConstructorDrawsFromJMetalRandomAndClampsLikeJMetal() {
        // A seeded JavaRandomGenerator's nextDouble() is Random.nextDouble(). The global generator
        // is swapped in and put back, so no other test sees a different stream; the solutions are
        // built before, because DefaultDoubleSolution draws its initial values from it.
        List<DoubleSolution> ours = new ArrayList<>();
        List<DoubleSolution> theirs = new ArrayList<>();
        for (int run = 0; run < 50; run++) {
            ours.add(solution());
            theirs.add(solution());
        }
        var reference = new LevyFlightMutationRandomStepSize(1.0, 1.1, 1.0, new RepairDoubleSolutionWithBoundValue(),
            new Random(3)::nextDouble);
        var mutation = new LevyFlightMutationRandomStepSize(1.0, 1.1, 1.0);
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(3));
            for (int run = 0; run < 50; run++) {
                mutation.execute(ours.get(run));
            }
        } finally {
            global.setRandomGenerator(previous);
        }

        // beta 1.1 and step sizes up to the whole range: many values are clamped to a bound.
        long clamped = ours.stream().flatMap(s -> s.variables().stream()).filter(v -> Math.abs(v) == 10.0).count();
        assertTrue(clamped > 0, "the fixture must exercise the clamping");
        for (int run = 0; run < 50; run++) {
            assertEquals(reference.execute(theirs.get(run)).variables(), ours.get(run).variables(),
                "the default repair clamps as RepairDoubleSolutionWithBoundValue (run " + run + ")");
        }
    }

    @Test
    void variableWithEqualBoundsIsLeftAtItsValue() {
        // Every variable mutates with large steps; jMetal's own repair would throw on variable 2.
        // The default repair is only reachable through the constructor that draws from
        // JMetalRandom, so a seeded generator is swapped in and put back for a reproducible run.
        var mutation = new LevyFlightMutationRandomStepSize(1.0, 1.1, 1.0);
        DoubleSolution solution = solutionWithAFixedVariable();
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(13));
            for (int run = 0; run < 200; run++) {
                mutation.execute(solution);

                assertEquals(1.0, solution.variables().get(2), "a variable whose bounds are equal keeps that value");
                for (int i : new int[] {0, 1, 3}) {
                    double value = solution.variables().get(i);
                    assertTrue(value >= -10.0 && value <= 10.0, "variable " + i + " left its bounds: " + value);
                }
            }
        } finally {
            global.setRandomGenerator(previous);
        }
    }

    @Test
    void explicitRepairIsUsedAsGiven() {
        List<double[]> calls = new ArrayList<>();
        var mutation = new LevyFlightMutationRandomStepSize(1.0, 1.5, 0.5,
            (value, lower, upper) -> {
                calls.add(new double[] {lower, upper});
                return -7.0;
            },
            new Random(5)::nextDouble);

        DoubleSolution mutated = mutation.execute(solution());

        assertEquals(VARIABLES, calls.size(), "with probability 1 the repair sees every variable");
        assertEquals(Collections.nCopies(VARIABLES, -7.0), mutated.variables(), "the repair's value is kept");
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Test
    void accessorsReturnTheConstructorArguments() {
        var mutation = new LevyFlightMutationRandomStepSize(0.1, 1.25, 0.5);

        assertEquals(0.1, mutation.mutationProbability());
        assertEquals(1.25, mutation.beta());
        assertEquals(0.5, mutation.maximumStepSize());
    }

    // ── Argument errors ───────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void rejectsAProbabilityOutsideZeroToOne(double probability) {
        assertThrows(InvalidProbabilityValueException.class,
            () -> new LevyFlightMutationRandomStepSize(probability, 1.5, 0.5));
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 2.0, 2.5, 0.5, Double.NaN})
    void rejectsBetaOutsideTheOpenIntervalOneToTwo(double beta) {
        assertThrows(InvalidConditionException.class, () -> new LevyFlightMutationRandomStepSize(0.1, beta, 0.5));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.5, Double.POSITIVE_INFINITY, Double.NaN})
    void rejectsAMaximumStepSizeThatIsNotFiniteAndPositive(double maximum) {
        assertThrows(InvalidConditionException.class, () -> new LevyFlightMutationRandomStepSize(0.1, 1.5, maximum));
    }

    @Test
    void rejectsANullRepairOrGenerator() {
        assertThrows(NullParameterException.class,
            () -> new LevyFlightMutationRandomStepSize(0.1, 1.5, 0.5, null, () -> 0.5));
        assertThrows(NullParameterException.class,
            () -> new LevyFlightMutationRandomStepSize(0.1, 1.5, 0.5, new RepairDoubleSolutionWithBoundValue(), null));
    }

    @Test
    void rejectsANullSolution() {
        var mutation = new LevyFlightMutationRandomStepSize(0.1, 1.5, 0.5);

        assertThrows(NullParameterException.class, () -> mutation.execute(null));
    }
}

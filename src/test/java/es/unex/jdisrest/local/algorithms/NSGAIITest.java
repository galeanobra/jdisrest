package es.unex.jdisrest.local.algorithms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.SBXCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.evaluator.impl.SequentialSolutionListEvaluator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local NSGA-II on top of jMetal's: population sizes checked at construction,
 * constraint-aware replacement, reproduction that never mutates a parent, a result before the
 * run, and the trace snapshots (cadence without overflow, final snapshot once).
 *
 * <p>The runs draw from the shared {@link JMetalRandom}; every test runs with a seeded generator
 * of its own and puts the previous one back afterwards.
 */
class NSGAIITest {

    private static final ZDT1 PROBLEM = new ZDT1(4);
    private static final int POPULATION = 4;
    /** Initial population plus four generations of {@link #POPULATION}. */
    private static final int EVALUATIONS = 20;

    private PseudoRandomGenerator sharedGenerator;

    @BeforeEach
    void seedTheSharedGenerator() {
        sharedGenerator = JMetalRandom.getInstance().getRandomGenerator();
        JMetalRandom.getInstance().setRandomGenerator(new JavaRandomGenerator(1L));
    }

    @AfterEach
    void restoreTheSharedGenerator() {
        JMetalRandom.getInstance().setRandomGenerator(sharedGenerator);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** NSGA-II on ZDT1 with the usual operators; also exposes jMetal's protected replacement. */
    static final class TestNSGAII extends NSGAII<DoubleSolution> {
        TestNSGAII(int populationSize, CrossoverOperator<DoubleSolution> crossover,
                MutationOperator<DoubleSolution> mutation, String tracesFolder) {
            super(PROBLEM, populationSize, EVALUATIONS, crossover, mutation, defaultSelection(),
                    new SequentialSolutionListEvaluator<>(), tracesFolder);
        }

        List<DoubleSolution> replace(List<DoubleSolution> population, List<DoubleSolution> offspring) {
            return replacement(population, offspring);
        }
    }

    static TestNSGAII nsgaii(String tracesFolder) {
        return new TestNSGAII(POPULATION, new SBXCrossover(0.9, 20.0),
                new PolynomialMutation(1.0 / PROBLEM.numberOfVariables(), 20.0), tracesFolder);
    }

    /** A two-objective solution with one constraint ({@code constraint < 0} = violated). */
    static DoubleSolution solution(double constraint, double f1, double f2) {
        DoubleSolution s = new DefaultDoubleSolution(Collections.nCopies(2, Bounds.create(-10.0, 10.0)), 2, 1);
        s.variables().set(0, f1);
        s.variables().set(1, f2);
        s.objectives()[0] = f1;
        s.objectives()[1] = f2;
        s.constraints()[0] = constraint;
        return s;
    }

    /** Names of the {@code VAR_*.csv} population traces in {@code folder}, sorted. */
    static List<String> populationTraces(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("VAR_")).sorted().toList();
        }
    }

    /** Runs {@code algorithm} and returns what it logged. */
    static String runLogged(NSGAII<?> algorithm) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, UTF_8));
        try {
            algorithm.run();
            return buffer.toString(UTF_8);
        } finally {
            System.setErr(original);
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void oddPopulationIsRejectedAtConstruction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new TestNSGAII(5, new SBXCrossover(0.9, 20.0), new PolynomialMutation(0.25, 20.0), null));
        assertTrue(e.getMessage().contains("populationSize = 5 must be a multiple of the 2 parents"), e.getMessage());
    }

    @Test
    void populationSizeProblemNamesTheRule() {
        assertNull(NSGAII.populationSizeProblem(4, 2));
        assertNull(NSGAII.populationSizeProblem(9, 3));
        assertNotNull(NSGAII.populationSizeProblem(10, 3));
        assertEquals("populationSize = 0 must be at least 1", NSGAII.populationSizeProblem(0, 2));
    }

    @Test
    void nullCrossoverIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> new TestNSGAII(POPULATION, null, new PolynomialMutation(0.25, 20.0), null));
    }

    // ── Warm start ────────────────────────────────────────────────────────────

    @Test
    void warmStartOfAnotherSizeIsFittedToThePopulation() {
        int[] created = {0};
        Supplier<String> random = () -> "r" + created[0]++;

        assertEquals(List.of("r0", "r1", "r2", "r3"), NSGAII.fitted(List.of(), POPULATION, random),
                "an empty list must not leave the first selection without parents");
        assertEquals(List.of("a", "b", "r4", "r5"), NSGAII.fitted(List.of("a", "b"), POPULATION, random),
                "a short list is topped up, so jMetal's count of populationSize evaluations is exact");
        assertEquals(List.of("a", "b", "c", "d"), NSGAII.fitted(List.of("a", "b", "c", "d", "e"), POPULATION, random),
                "a long list keeps its first populationSize solutions");
        List<String> exact = new ArrayList<>(List.of("a", "b", "c", "d"));
        List<String> fitted = NSGAII.fitted(exact, POPULATION, random);
        assertEquals(exact, fitted);
        assertNotSame(exact, fitted, "the problem's list is never handed to jMetal itself");
        assertEquals(6, created[0], "random solutions only for the missing slots");
    }

    // ── Replacement and reproduction ──────────────────────────────────────────

    @Test
    void replacementKeepsFeasibleSolutionsOverAnInfeasibleOneWithBetterObjectives() {
        TestNSGAII algorithm = new TestNSGAII(2, new SBXCrossover(0.9, 20.0), new PolynomialMutation(0.5, 20.0), null);
        DoubleSolution a = solution(0.0, 1.0, 1.0);
        DoubleSolution b = solution(0.0, 2.0, 0.5);
        DoubleSolution infeasible = solution(-1.0, 0.0, 0.0);
        DoubleSolution dominated = solution(0.0, 3.0, 3.0);

        List<DoubleSolution> next = algorithm.replace(new ArrayList<>(List.of(a, b)),
                new ArrayList<>(List.of(infeasible, dominated)));

        assertEquals(2, next.size());
        assertTrue(next.contains(a) && next.contains(b),
                "with constraint-aware ranking the two feasible non-dominated solutions survive: " + next);
    }

    @Test
    void reproductionNeverMutatesTheParents() {
        // A crossover that hands back the parents themselves, as NPointCrossover does when it does
        // not act, and a mutation that always changes the first variable.
        CrossoverOperator<DoubleSolution> returnsParents = new CrossoverOperator<>() {
            @Override public List<DoubleSolution> execute(List<DoubleSolution> parents) { return parents; }
            @Override public double crossoverProbability() { return 0.0; }
            @Override public int numberOfRequiredParents() { return 2; }
            @Override public int numberOfGeneratedChildren() { return 2; }
        };
        MutationOperator<DoubleSolution> shift = new MutationOperator<>() {
            @Override public DoubleSolution execute(DoubleSolution s) { s.variables().set(0, s.variables().get(0) + 1.0); return s; }
            @Override public double mutationProbability() { return 1.0; }
        };
        TestNSGAII algorithm = new TestNSGAII(POPULATION, returnsParents, shift, null);
        List<DoubleSolution> matingPool = new ArrayList<>();
        for (int i = 0; i < POPULATION; i++) {
            matingPool.add(solution(0.0, i, i));
        }

        List<DoubleSolution> offspring = algorithm.reproduction(matingPool);

        assertEquals(POPULATION, offspring.size());
        for (int i = 0; i < POPULATION; i++) {
            assertEquals(i, matingPool.get(i).variables().get(0), "a parent must keep its variables");
            assertNotSame(matingPool.get(i), offspring.get(i), "a child must be a copy");
            assertEquals(i + 1.0, offspring.get(i).variables().get(0), "the child is the one mutated");
        }
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Test
    void resultBeforeTheRunIsEmpty() {
        assertEquals(List.of(), nsgaii(null).result(), "an empty archive has no result, and must not throw");
    }

    @Test
    void resultAfterTheRunHoldsFeasibleSolutions() {
        TestNSGAII algorithm = nsgaii(null);

        runLogged(algorithm);

        List<DoubleSolution> result = algorithm.result();
        assertFalse(result.isEmpty());
        assertTrue(result.size() <= POPULATION);
    }

    // ── Traces ────────────────────────────────────────────────────────────────

    @Test
    void finalStateIsTracedWhenTheBudgetIsNotAMultipleOfTheTracePeriod(@TempDir Path dir) throws IOException {
        Path traces = dir.resolve("traces");
        TestNSGAII algorithm = nsgaii(traces.toString());
        algorithm.setTraceCadence(3);  // a snapshot every 12 evaluations

        runLogged(algorithm);

        assertEquals(List.of("VAR_12.csv", "VAR_20.csv"), populationTraces(traces),
                "the periodic snapshot at 12 and the final one at " + EVALUATIONS);
        assertTrue(Files.exists(traces.resolve("aFUN_20.csv")), "the final snapshot includes the archive");
    }

    @Test
    void hugeTraceCadenceDoesNotOverflowIntoTracingEveryGeneration(@TempDir Path dir) throws IOException {
        Path traces = dir.resolve("traces");
        TestNSGAII algorithm = nsgaii(traces.toString());
        algorithm.setTraceCadence(Integer.MAX_VALUE);  // 4 * MAX_VALUE wraps to -4 in int

        runLogged(algorithm);

        assertEquals(List.of("VAR_20.csv"), populationTraces(traces), "only the final state is traced");
    }

    @Test
    void lastPeriodicSnapshotIsNotWrittenTwice(@TempDir Path dir) throws IOException {
        Path traces = dir.resolve("traces");
        TestNSGAII algorithm = nsgaii(traces.toString());

        String log = runLogged(algorithm);

        assertEquals(List.of("VAR_12.csv", "VAR_16.csv", "VAR_20.csv", "VAR_4.csv", "VAR_8.csv"),
                populationTraces(traces), "every generation is traced with the default cadence");
        assertEquals(1, log.split("Population trace saved after 20 evaluations", -1).length - 1,
                "the final snapshot must not repeat the last periodic one: " + log);
    }
}

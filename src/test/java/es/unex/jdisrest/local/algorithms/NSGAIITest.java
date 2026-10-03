package es.unex.jdisrest.local.algorithms;

import es.unex.jdisrest.operator.IntegerPolynomialMutation;
import es.unex.jdisrest.operator.IntegerSBXCrossover;
import es.unex.jdisrest.operator.SafeCompositeCrossover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.SBXCrossover;
import org.uma.jmetal.operator.crossover.impl.SinglePointCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.operator.mutation.impl.CompositeMutation;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local NSGA-II on top of jMetal's: population sizes checked at construction,
 * constraint-aware replacement, reproduction that never mutates a parent, a result before the
 * run, and the trace snapshots (cadence without overflow, final snapshot once, binary variables as
 * bit strings, solutions the traces cannot hold rejected before the first evaluation by a check
 * that draws no random number).
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

    /**
     * A composite problem of two objectives whose solutions are made of {@code segments}; its
     * evaluation sets the objectives from the first variable of the first segment, an integer in
     * [0, 10], and counts the evaluations.
     */
    static final class CompositeProblem implements Problem<CompositeSolution> {
        private final Supplier<List<Solution<?>>> segments;
        int evaluations;

        CompositeProblem(Supplier<List<Solution<?>>> segments) {
            this.segments = segments;
        }

        @Override public int numberOfVariables() { return segments.get().size(); }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "CompositeProblem"; }
        @Override public CompositeSolution createSolution() { return new CompositeSolution(segments.get()); }

        @Override
        public CompositeSolution evaluate(CompositeSolution solution) {
            evaluations++;
            int x = ((IntegerSolution) solution.variables().get(0)).variables().get(0);
            solution.objectives()[0] = x;
            solution.objectives()[1] = 10 - x;
            return solution;
        }
    }

    /** A random integer segment of {@code n} variables in [0, 10], with two objectives. */
    static IntegerSolution integers(int n) {
        return new DefaultIntegerSolution(Collections.nCopies(n, Bounds.create(0, 10)), 2, 0);
    }

    /** NSGA-II on a composite problem, with a crossover that copies the parents and a mutation that changes nothing. */
    static NSGAII<CompositeSolution> unchanging(CompositeProblem problem, String tracesFolder) {
        CrossoverOperator<CompositeSolution> copies = new CrossoverOperator<>() {
            @Override public List<CompositeSolution> execute(List<CompositeSolution> parents) {
                return parents.stream().map(p -> (CompositeSolution) p.copy()).toList();
            }
            @Override public double crossoverProbability() { return 0.0; }
            @Override public int numberOfRequiredParents() { return 2; }
            @Override public int numberOfGeneratedChildren() { return 2; }
        };
        MutationOperator<CompositeSolution> none = new MutationOperator<>() {
            @Override public CompositeSolution execute(CompositeSolution s) { return s; }
            @Override public double mutationProbability() { return 0.0; }
        };
        return new NSGAII<>(problem, POPULATION, EVALUATIONS, copies, none, NSGAII.defaultSelection(),
                new SequentialSolutionListEvaluator<>(), tracesFolder);
    }

    /** The variables of each row of a composite trace: the text before {@code ,[}. */
    static List<String> variableRows(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(row -> row.split(",\\[", 2)[0]).toList();
    }

    /**
     * Runs NSGA-II on ZDT1 from seed 1 and returns the next draw of the shared generator after the
     * run, then the objectives of its result. One random number more or less anywhere in the run
     * shifts every later draw, which changes the result, or the next draw when it comes after the
     * last generation.
     */
    static List<Double> drawsOfASeededRun(String tracesFolder) {
        JMetalRandom.getInstance().setRandomGenerator(new JavaRandomGenerator(1L));
        TestNSGAII algorithm = nsgaii(tracesFolder);

        runLogged(algorithm);

        List<Double> draws = new ArrayList<>(List.of(JMetalRandom.getInstance().nextDouble()));
        algorithm.result().forEach(s -> Arrays.stream(s.objectives()).forEach(draws::add));
        return draws;
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
    void binaryVariablesAreTracedAsBitStrings(@TempDir Path dir) throws IOException {
        Path traces = dir.resolve("traces");
        NSGAII<BinarySolution> algorithm = new NSGAII<>(new ZDT5(), POPULATION, EVALUATIONS,
                new SinglePointCrossover<>(0.9), new BitFlipMutation<>(1.0 / 80), NSGAII.defaultSelection(),
                new SequentialSolutionListEvaluator<>(), traces.toString());

        runLogged(algorithm);

        Pattern row = Pattern.compile("[01]{30}(,[01]{5}){10}");  // ZDT5: a variable of 30 bits, ten of 5
        for (String file : List.of("aVAR_20.csv", "VAR_20.csv")) {
            List<String> rows = Files.readAllLines(traces.resolve(file));
            assertFalse(rows.isEmpty(), file);
            rows.forEach(r -> assertTrue(row.matcher(r).matches(), file + ": one bit string per variable, got " + r));
        }
    }

    @Test
    void compositesWithABinarySegmentAreTracedWithItsBitStrings(@TempDir Path dir) throws IOException {
        Path traces = dir.resolve("traces");
        CompositeProblem problem = new CompositeProblem(
                () -> List.of(integers(2), new DefaultBinarySolution(List.of(6, 3), 2)));
        NSGAII<CompositeSolution> algorithm = new NSGAII<>(problem, POPULATION, EVALUATIONS,
                new SafeCompositeCrossover(List.of(new IntegerSBXCrossover(0.9, 20.0), new SinglePointCrossover<>(0.9))),
                new CompositeMutation(List.of(new IntegerPolynomialMutation(0.5, 20.0), new BitFlipMutation<>(1.0 / 9))),
                NSGAII.defaultSelection(), new SequentialSolutionListEvaluator<>(), traces.toString());

        runLogged(algorithm);

        assertEquals(List.of("VAR_12.csv", "VAR_16.csv", "VAR_20.csv", "VAR_4.csv", "VAR_8.csv"),
                populationTraces(traces), "1.2.1 failed at the first snapshot, once the initial population was evaluated");
        Pattern variables = Pattern.compile("([0-9]|10) ([0-9]|10) [01]{6} [01]{3}");
        for (String file : List.of("aVAR_20.csv", "VAR_20.csv")) {
            List<String> rows = variableRows(traces.resolve(file));
            assertEquals(POPULATION, rows.size(), file);
            rows.forEach(r -> assertTrue(variables.matcher(r).matches(),
                    file + ": the integers, then one bit string per binary variable, got " + r));
        }
        assertEquals(EVALUATIONS, problem.evaluations);
    }

    @Test
    void solutionsTheTracesCannotHoldAreRejectedBeforeTheFirstEvaluation(@TempDir Path dir) {
        Path traces = dir.resolve("traces");
        CompositeProblem problem = new CompositeProblem(
                () -> List.of(integers(1), new CompositeSolution(List.of(integers(1)))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> runLogged(unchanging(problem, traces.toString())));

        assertEquals("The traces cannot hold the solutions of CompositeProblem (segment 1: CompositeSolution found "
                + "where a flat solution was expected: nested CompositeSolution segments are not supported); run "
                + "without a traces folder to evaluate them anyway", e.getMessage());
        assertEquals(0, problem.evaluations, "nothing is evaluated before the check");
        assertFalse(Files.exists(traces), "nor written");
    }

    @Test
    void aTracesFolderDoesNotChangeWhatASeededRunDraws(@TempDir Path dir) {
        // ZDT1 is flat, so TraceWriter.check returns at once: only a draw added around the check,
        // such as checking a new solution of the problem, could tell the two runs apart.
        List<Double> withoutTraces = drawsOfASeededRun(null);
        List<Double> withTraces = drawsOfASeededRun(dir.resolve("traces").toString());

        assertEquals(withoutTraces, withTraces, "the check of the first solution must draw no random number");
    }

    @Test
    void solutionsTheTracesCannotHoldRunWithoutATracesFolder() {
        CompositeProblem problem = new CompositeProblem(
                () -> List.of(integers(1), new CompositeSolution(List.of(integers(1)))));

        runLogged(unchanging(problem, null));

        assertEquals(EVALUATIONS, problem.evaluations, "the check is about the traces only");
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

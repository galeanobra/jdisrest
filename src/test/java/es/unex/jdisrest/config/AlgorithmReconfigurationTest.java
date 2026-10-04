package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.NSGAIIConfig;
import es.unex.jdisrest.config.SolutionLayout.Segment;
import es.unex.jdisrest.config.TestProblems.FromFactory;
import es.unex.jdisrest.config.TestProblems.Named;
import es.unex.jdisrest.distributed.ServerlessAlgorithm;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.operator.IntegerBLXCrossover;
import es.unex.jdisrest.operator.IntegerGaussianMutation;
import es.unex.jdisrest.operator.IntegerSimpleRandomMutation;
import es.unex.jdisrest.operator.SafeCompositeCrossover;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.ArithmeticCrossover;
import org.uma.jmetal.operator.crossover.impl.HUXCrossover;
import org.uma.jmetal.operator.crossover.impl.UniformCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.operator.mutation.impl.CompositeMutation;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static es.unex.jdisrest.config.AlgorithmConfigTest.write;
import static es.unex.jdisrest.config.TestProblems.flatIntegers;
import static es.unex.jdisrest.config.TestProblems.mixed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Changes applied to a running algorithm: the operators of each segment of a composite, installed
 * from the keys with their prefixes and working on its solutions; flat integer and binary changes;
 * a text that the layout of the run rejects, which changes nothing; configurations of another
 * layout or algorithm, refused when the handler is created; and the solutions the constructors
 * create and the random numbers they draw.
 *
 * <p>The algorithms have no REST server ({@link ServerlessAlgorithm}). An NSGA-II configuration
 * drives any algorithm whose variation is a crossover and a mutation, so these tests use NSGA-II
 * configurations; PAES and MOEA/D install the same operators through their own
 * {@code reconfigure}.
 */
class AlgorithmReconfigurationTest {

    private static final String START = "algorithm=nsgaii\nmaxEvaluations=1000\npopulationSize=10\n";
    private static final String NEXT = "algorithm=nsgaii\nmaxEvaluations=2000\npopulationSize=10\n";

    @TempDir
    Path folder;

    private int budget;

    @BeforeEach
    void rememberTheBudgetOfTheStatus() {
        budget = MasterFacade.getMaxEvaluations();
    }

    @AfterEach
    void restoreTheBudgetOfTheStatus() {
        MasterFacade.setMaxEvaluations(budget);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** An algorithm that started with the operators of a configuration read for the problem. */
    static <S extends Solution<?>> ServerlessAlgorithm<S> algorithm(Problem<S> problem, String text) {
        Variation variation = AlgorithmConfig.parseText(text, problem).variation();
        return new ServerlessAlgorithm<>(problem, variation.createCrossover(), variation.createMutation(), 1000);
    }

    /** The history of a run started from a file with the text of {@code initial}. */
    ConfigHistory history(AlgorithmConfig initial, String text) throws IOException {
        return ConfigHistory.of(write(folder, "run.properties", text), List.of(), initial);
    }

    /** The handler of a run of the algorithm that started from {@link #START}, read for the problem. */
    <S extends Solution<?>> AlgorithmReconfiguration handler(ServerlessAlgorithm<S> algorithm, Problem<S> problem)
            throws IOException {
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, problem);
        return new AlgorithmReconfiguration(algorithm, initial, problem, history(initial, START));
    }

    static List<Class<?>> classes(List<?> operators) {
        return operators.stream().<Class<?>>map(Object::getClass).toList();
    }

    // ── Changes ───────────────────────────────────────────────────────────────

    @Test
    void aCompositeChangeInstallsTheOperatorsOfEachSegment() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmReconfiguration handler = handler(algorithm, problem);
        String text = NEXT + "ints.crossover=blxAlpha\nints.mutation=random\nreals.crossover=arithmetic\n"
                + "bits.crossover=hux\nbits.mutation.probability=2/n\n";

        String applied = handler.apply(text);

        var crossover = assertInstanceOf(SafeCompositeCrossover.class, algorithm.crossoverInUse());
        var mutation = assertInstanceOf(CompositeMutation.class, algorithm.mutationInUse());
        assertAll(
                () -> assertEquals("NSGA-II, 2000 evaluations, population 10, ints [crossover blxAlpha (probability 0.9, "
                        + "alpha 0.5), mutation random (probability 0.3333)], reals [crossover arithmetic (probability 0.9), "
                        + "mutation polynomial (probability 0.5, distributionIndex 20)], bits [crossover hux "
                        + "(probability 0.9), mutation bitFlip (probability 0.25)], no traces", applied,
                        "the operators of each segment, k/n over its size"),
                () -> assertEquals(List.of(IntegerBLXCrossover.class, ArithmeticCrossover.class, HUXCrossover.class),
                        classes(crossover.getOperators()), "the crossover of each segment"),
                () -> assertEquals(List.of(IntegerSimpleRandomMutation.class, PolynomialMutation.class, BitFlipMutation.class),
                        classes(mutation.getOperators()), "the mutation of each segment"),
                () -> assertEquals(0.25, mutation.getOperators().getLast().mutationProbability(), "2 over 8 bits"),
                () -> assertEquals(2000, MasterFacade.getMaxEvaluations(), "the new budget of the status"),
                () -> assertEquals(text, handler.current(), "the text in use"));
    }

    @Test
    void theOperatorsInstalledForACompositeWorkOnItsSolutions() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        handler(algorithm, problem).apply(NEXT + "ints.crossover.probability=1\nints.mutation.probability=1\n"
                + "reals.crossover.probability=1\nreals.mutation.probability=1\nbits.crossover=uniform\n"
                + "bits.crossover.probability=1\nbits.mutation.probability=1\n");
        CompositeSolution parent = problem.createSolution();

        List<CompositeSolution> children = algorithm.crossoverInUse().execute(List.of(parent, problem.createSolution()))
                .stream().map(algorithm.mutationInUse()::execute).toList();

        assertAll(
                () -> assertEquals(2, children.size(), "two children"),
                () -> assertTrue(children.stream().allMatch(child ->
                        SolutionVariables.layoutOf(child).equals(SolutionVariables.layoutOf(parent))),
                        "each segment keeps its encoding and size, each binary variable its length"));
    }

    @Test
    void aTextWithoutThePrefixesOfTheSegmentsIsRejectedAndChangesNothing() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmReconfiguration handler = handler(algorithm, problem);
        CrossoverOperator<CompositeSolution> crossover = algorithm.crossoverInUse();
        MutationOperator<CompositeSolution> mutation = algorithm.mutationInUse();
        MasterFacade.setMaxEvaluations(1000);

        var exception = assertThrows(InvalidConfigurationException.class, () -> handler.apply(NEXT + "mutation=random\n"));

        assertAll(
                () -> assertTrue(exception.getMessage().startsWith("unknown keys for nsgaii with these operators: mutation. "
                        + "Valid keys: algorithm, maxEvaluations, populationSize, ints.crossover, "),
                        "the keys of a composite take their prefixes: " + exception.getMessage()),
                () -> assertSame(crossover, algorithm.crossoverInUse(), "the crossover is the same"),
                () -> assertSame(mutation, algorithm.mutationInUse(), "the mutation is the same"),
                () -> assertEquals(1000, MasterFacade.getMaxEvaluations(), "the budget is the same"),
                () -> assertEquals(START, handler.current(), "the text in use is the same"));
    }

    @Test
    void aFlatIntegerChangeInstallsIntegerOperators() throws IOException {
        FromFactory<IntegerSolution> problem = flatIntegers();
        ServerlessAlgorithm<IntegerSolution> algorithm = algorithm(problem, START);

        handler(algorithm, problem).apply(NEXT + "crossover=blxAlpha\ncrossover.alpha=0.25\nmutation=gaussian\n"
                + "mutation.probability=2/n\n");

        var crossover = assertInstanceOf(IntegerBLXCrossover.class, algorithm.crossoverInUse());
        var mutation = assertInstanceOf(IntegerGaussianMutation.class, algorithm.mutationInUse());
        assertAll(
                () -> assertEquals(0.25, crossover.alpha(), "alpha"),
                () -> assertEquals(0.5, mutation.mutationProbability(), "2 over 4 variables"));
    }

    @Test
    void aFlatBinaryChangeInstallsBinaryOperatorsWithKOverTheBits() throws IOException {
        ZDT5 problem = new ZDT5();
        ServerlessAlgorithm<BinarySolution> algorithm = algorithm(problem, START);

        handler(algorithm, problem).apply(NEXT + "crossover=uniform\nmutation.probability=4/n\n");

        var mutation = assertInstanceOf(BitFlipMutation.class, algorithm.mutationInUse());
        assertAll(
                () -> assertInstanceOf(UniformCrossover.class, algorithm.crossoverInUse(), "uniform crossover"),
                () -> assertEquals(0.05, mutation.mutationProbability(), "4 over the 80 bits of ZDT5"));
    }

    @Test
    void aConfigurationOf12DrivesARealCodedRun() throws IOException {
        ZDT1 problem = new ZDT1();
        var parsed = assertInstanceOf(NSGAIIConfig.class, AlgorithmConfig.parseText(START, problem));
        var initial = new NSGAIIConfig(1000, 10, parsed.crossover(), parsed.mutation(), null);
        var algorithm = new ServerlessAlgorithm<>(problem, initial.crossover().create(), initial.mutation().create(), 1000);
        var handler = new AlgorithmReconfiguration(algorithm, initial, problem, history(initial, START));

        String applied = handler.apply(NEXT + "mutation.probability=3/n\n");

        assertEquals("NSGA-II, 2000 evaluations, population 10, crossover sbx (probability 0.9, distributionIndex 20), "
                + "mutation polynomial (probability 0.1, distributionIndex 20), no traces", applied,
                "k/n over the 30 variables of ZDT1, which the record does not know");
    }

    // ── Configurations that cannot drive the run ──────────────────────────────

    @Test
    void aConfigurationReadForAnotherLayoutIsRefused() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, new SolutionLayout(List.of(
                new Segment("ints", Encoding.INT, 3), new Segment("reals", Encoding.DOUBLE, 3))));
        ConfigHistory history = history(initial, START);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmReconfiguration(algorithm, initial, problem, history));

        assertEquals("the configuration was read for a composite of integer (3 variables), real (3 variables), but the "
                + "solutions of Mixed are a composite of integer (3 variables), real (2 variables), binary (8 bits)",
                exception.getMessage(), "both layouts");
    }

    @Test
    void aRealCodedConfigurationCannotDriveAnIntegerRun() throws IOException {
        FromFactory<IntegerSolution> problem = flatIntegers();
        ServerlessAlgorithm<IntegerSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, 30);
        ConfigHistory history = history(initial, START);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmReconfiguration(algorithm, initial, problem, history));

        assertEquals("the configuration was read for real (30 variables), but the solutions of FlatIntegers are "
                + "integer (4 variables)", exception.getMessage(), "both layouts");
    }

    @Test
    void anIntegerConfigurationCannotDriveARealCodedRun() throws IOException {
        ZDT1 problem = new ZDT1();
        ServerlessAlgorithm<DoubleSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, SolutionLayout.of(flatIntegers()));
        ConfigHistory history = history(initial, START);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmReconfiguration(algorithm, initial, problem, history));

        assertEquals("the configuration was read for integer (4 variables), but the solutions of ZDT1 are "
                + "real (30 variables)", exception.getMessage(), "the constructor for a DoubleProblem checks the encoding");
    }

    @Test
    void aPaesConfigurationCannotDriveAnotherAlgorithm() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText("algorithm=paes\nmaxEvaluations=1000\n", problem);
        ConfigHistory history = history(initial, START);

        var exception = assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmReconfiguration(algorithm, initial, problem, history));

        assertEquals("a PAESConfig cannot drive a running " + ServerlessAlgorithm.class.getName(), exception.getMessage(),
                "PAES needs its own algorithm");
    }

    @Test
    void aRealCodedProblemWithoutVariablesIsRefusedByEitherConstructor() throws IOException {
        ZDT1 problem = new ZDT1(0);
        Problem<DoubleSolution> anyProblem = problem;
        ServerlessAlgorithm<DoubleSolution> algorithm = algorithm(new ZDT1(), START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, new ZDT1());
        ConfigHistory history = history(initial, START);
        String reason = "cannot configure the operators of ZDT1: its solutions have no variables";

        assertAll(
                () -> assertEquals(reason, assertThrows(IllegalArgumentException.class,
                        () -> new AlgorithmReconfiguration(algorithm, initial, problem, history)).getMessage(),
                        "the constructor for a DoubleProblem"),
                () -> assertEquals(reason, assertThrows(IllegalArgumentException.class,
                        () -> new AlgorithmReconfiguration(algorithm, initial, anyProblem, history)).getMessage(),
                        "the generic constructor, given a DoubleProblem"));
    }

    // ── Solutions and random numbers ──────────────────────────────────────────

    @Test
    void aRealCodedRunCreatesNoSolutionWithEitherConstructorNorForAChange() throws IOException {
        ZDT1 problem = new ZDT1();
        Problem<DoubleSolution> anyProblem = problem;
        ServerlessAlgorithm<DoubleSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, problem);
        ConfigHistory history = history(initial, START);
        AlgorithmReconfiguration handler = new AlgorithmReconfiguration(algorithm, initial, anyProblem, history);

        assertAll(
                () -> assertTrue(TestProblems.drawsNoRandomNumber(
                        () -> new AlgorithmReconfiguration(algorithm, initial, problem, history)),
                        "the constructor for a DoubleProblem"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(
                        () -> new AlgorithmReconfiguration(algorithm, initial, anyProblem, history)),
                        "the generic constructor, given a DoubleProblem"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(() -> handler.apply(NEXT)), "a change"));
    }

    @Test
    void anotherProblemCreatesOneSolutionForTheHandlerAndNoneForAChange() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, problem);
        ConfigHistory history = history(initial, START);
        int before = problem.created.get();

        AlgorithmReconfiguration handler = new AlgorithmReconfiguration(algorithm, initial, problem, history);
        int created = problem.created.get() - before;
        handler.apply(NEXT);

        assertAll(
                () -> assertEquals(1, created, "one solution checks the layout of the configuration"),
                () -> assertEquals(before + 1, problem.created.get(), "a change is read for that layout"));
    }

    @Test
    void theHandlerOfTheLauncherTakesTheLayoutItWasGivenWithoutASolution() throws IOException {
        Named problem = mixed();
        ServerlessAlgorithm<CompositeSolution> algorithm = algorithm(problem, START);
        AlgorithmConfig initial = AlgorithmConfig.parseText(START, problem);
        ConfigHistory history = history(initial, START);
        int before = problem.created.get();

        AlgorithmReconfiguration handler = new AlgorithmReconfiguration(algorithm, initial, initial.layout(),
                problem.numberOfObjectives(), history);
        String applied = handler.apply(NEXT + "bits.mutation.probability=4/n\n");

        assertAll(
                () -> assertEquals(before, problem.created.get(), "ConfiguredMaster has just read the layout"),
                () -> assertTrue(applied.contains(", bits [crossover singlePoint (probability 0.9), mutation bitFlip "
                        + "(probability 0.5)], "), "a change read for that layout: " + applied),
                () -> assertEquals("a PAESConfig cannot drive a running " + ServerlessAlgorithm.class.getName(),
                        assertThrows(IllegalArgumentException.class, () -> new AlgorithmReconfiguration(algorithm,
                                AlgorithmConfig.parseText("algorithm=paes\nmaxEvaluations=1000\n", problem),
                                initial.layout(), problem.numberOfObjectives(), history)).getMessage(),
                        "the algorithm is still checked"));
    }
}

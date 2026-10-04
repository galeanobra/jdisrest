package es.unex.jdisrest.config;

import es.unex.jdisrest.config.ConfiguredMaster.Launch;
import es.unex.jdisrest.config.TestProblems.FromFactory;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.problem.doubleproblem.impl.AbstractDoubleProblem;
import org.uma.jmetal.problem.multiobjective.MixedIntegerDoubleProblem;
import org.uma.jmetal.problem.multiobjective.NMMin;
import org.uma.jmetal.problem.multiobjective.dtlz.DTLZ2;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.permutationsolution.impl.IntegerPermutationSolution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static es.unex.jdisrest.config.TestProblems.bits;
import static es.unex.jdisrest.config.TestProblems.composite;
import static es.unex.jdisrest.config.TestProblems.flatIntegers;
import static es.unex.jdisrest.config.TestProblems.integers;
import static es.unex.jdisrest.config.TestProblems.mixed;
import static es.unex.jdisrest.config.TestProblems.reals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The generic launcher without starting a master: the command line, {@code --check} with its
 * status and messages, the checks against the problem (variables, objectives, operators), the
 * failures that must happen before the server starts, and the problem class of {@code main}; for
 * problems of every encoding, the examples of each, the second line of {@code --check}, the
 * problems whose solutions cannot be configured, the solutions and random numbers the launcher
 * takes, and the layout {@link ConfiguredMaster#createAlgorithm} checks.
 */
class ConfiguredMasterTest {

    private static final String COMMAND = "TestMaster";
    private static final String TITLE = "Test problem";
    private static final String ZDT1_CLASS = ZDT1.class.getName();

    @TempDir
    Path folder;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** The exit status of a run and what it printed. */
    record Outcome(int status, String out, String err) {
    }

    /** Runs {@link ConfiguredMaster#run} with {@link #COMMAND} and {@link #TITLE}. */
    static Outcome run(Supplier<? extends Problem<?>> problem, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = ConfiguredMaster.run(problem, TITLE, List.of(args), COMMAND,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Outcome(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Runs what {@link ConfiguredMaster#main} runs, without the exit. */
    static Outcome launch(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status = ConfiguredMaster.launch(List.of(args), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Outcome(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private String file(String name, String... lines) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file.toString();
    }

    private String nsgaii(String... extraLines) throws IOException {
        List<String> lines = new ArrayList<>(List.of("algorithm = nsgaii", "maxEvaluations = 25000"));
        lines.addAll(List.of(extraLines));
        return file("nsgaii.properties", lines.toArray(String[]::new));
    }

    /** Creates ZDT1 problems and counts them. */
    static final class CountingFactory implements Supplier<DoubleProblem> {
        final AtomicInteger created = new AtomicInteger();

        @Override
        public DoubleProblem get() {
            created.incrementAndGet();
            return new ZDT1();
        }
    }

    /** A real-coded problem without a constructor without arguments. */
    public static final class SizedProblem extends ZDT1 {
        public SizedProblem(int numberOfVariables) {
            super(numberOfVariables);
        }
    }

    /** A real-coded problem that cannot be instantiated. */
    public abstract static class UnfinishedProblem extends AbstractDoubleProblem {
    }

    /** A problem whose solutions are permutations, which no catalogue operator changes. */
    public static final class PermutationProblem extends FromFactory<IntegerPermutationSolution> {
        public PermutationProblem() {
            super("PermutationProblem", () -> new IntegerPermutationSolution(4, 2, 0));
        }
    }

    /** ZDT1, counting the solutions it creates. */
    static final class CountedZDT1 extends ZDT1 {
        final AtomicInteger created = new AtomicInteger();

        @Override
        public DoubleSolution createSolution() {
            created.incrementAndGet();
            return super.createSolution();
        }
    }

    // ── Command line of a run ─────────────────────────────────────────────────

    @Test
    void aRunTakesAHostAPortAConfigurationFileAndOverrides() {
        Launch launch = Launch.parse(
                List.of("10.0.0.1", "55000", "nsgaii.properties", "maxEvaluations=40", "populationSize=10"));

        assertAll(
                () -> assertEquals("10.0.0.1", launch.host(), "the host"),
                () -> assertEquals(55000, launch.port(), "the port"),
                () -> assertEquals(Path.of("nsgaii.properties"), launch.configFile(), "the configuration file"),
                () -> assertEquals(List.of("maxEvaluations=40", "populationSize=10"), launch.overrides(),
                        "the overrides, in order"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void aRunNeedsAtLeastThreeArguments(int count) {
        List<String> args = List.of("localhost", "8080", "nsgaii.properties").subList(0, count);

        var exception = assertThrows(IllegalArgumentException.class, () -> Launch.parse(args));

        assertTrue(exception.getMessage().startsWith("expected <host> <port> <configFile> [key=value ...], got " + count),
                exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "port", "8080.0", " 8080"})
    void aPortOutsideOneTo65535OrNotAnIntegerIsRejected(String port) {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> Launch.parse(List.of("localhost", port, "nsgaii.properties")));

        assertEquals("port must be an integer in [1, 65535], got '" + port + "'", exception.getMessage(),
                "a port the workers could not reach is a usage error");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    void theLowestAndHighestPortsAreAccepted(int port) {
        Launch launch = Launch.parse(List.of("localhost", Integer.toString(port), "nsgaii.properties"));

        assertEquals(port, launch.port(), "a port at the end of the range");
    }

    @Test
    void anEmptyHostIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Launch.parse(List.of(" ", "8080", "nsgaii.properties")),
                "the workers need an address");
    }

    @Test
    void aUsageErrorPrintsTheUsageOfTheCommandAndCreatesNoProblem() throws IOException {
        var problems = new CountingFactory();

        Outcome outcome = run(problems, "localhost", "port", nsgaii());

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().startsWith("Invalid arguments: port must be an integer in [1, 65535]"),
                        outcome.err()),
                () -> assertTrue(outcome.err().contains("Usage: " + COMMAND + " <host> <port> <configFile> [key=value ...]"),
                        "the usage names the command: " + outcome.err()),
                () -> assertTrue(outcome.err().contains(COMMAND + " --check <configFile> [key=value ...]"),
                        "and the check: " + outcome.err()),
                () -> assertEquals(0, problems.created.get(), "the problem is not created for a usage error"));
    }

    @Test
    void noArgumentsAreAUsageError() {
        Outcome outcome = run(ZDT1::new);

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().contains("Usage: " + COMMAND), outcome.err()));
    }

    // ── Checking a configuration ──────────────────────────────────────────────

    @Test
    void aValidFilePassesTheCheck() throws IOException {
        Outcome outcome = run(ZDT1::new, "--check", nsgaii());

        assertAll(
                () -> assertEquals(0, outcome.status(), "the check passes"),
                () -> assertTrue(outcome.out().startsWith("Configuration OK for " + TITLE + ": NSGA-II, 25000 evaluations, "),
                        "the summary names the problem: " + outcome.out()),
                () -> assertEquals("", outcome.err(), "nothing to complain about"));
    }

    @Test
    void theCheckAppliesTheOverrides() throws IOException {
        Outcome outcome = run(ZDT1::new, "--check", nsgaii(), "maxEvaluations=40", "populationSize = 10");

        assertAll(
                () -> assertEquals(0, outcome.status(), "the check passes"),
                () -> assertTrue(outcome.out().contains("NSGA-II, 40 evaluations, population 10, "), outcome.out()));
    }

    @Test
    void theCheckCreatesTheProblemOnce() throws IOException {
        var problems = new CountingFactory();

        run(problems, "--check", nsgaii());

        assertEquals(1, problems.created.get(), "one problem for the variables and the objectives");
    }

    @Test
    void aCheckWithoutAFileIsAUsageError() {
        Outcome outcome = run(ZDT1::new, "--check");

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().startsWith("Invalid arguments: --check needs a configuration file"),
                        outcome.err()),
                () -> assertTrue(outcome.err().contains("Usage: " + COMMAND), outcome.err()));
    }

    @Test
    void anInvalidFileFailsTheCheckNamingTheKey() throws IOException {
        Outcome outcome = run(ZDT1::new, "--check", nsgaii("populationSize = 0"));

        assertAll(
                () -> assertEquals(1, outcome.status(), "the check fails"),
                () -> assertEquals("Invalid configuration: populationSize must be a positive integer, got '0'",
                        outcome.err().strip(), "the reason, without the usage"),
                () -> assertEquals("", outcome.out(), "no summary"));
    }

    @Test
    void aMissingFileFailsTheCheckNamingIt() {
        String missing = folder.resolve("missing.properties").toString();

        Outcome outcome = run(ZDT1::new, "--check", missing);

        assertAll(
                () -> assertEquals(1, outcome.status(), "the check fails"),
                () -> assertTrue(outcome.err().startsWith("Invalid configuration: cannot read configuration file " + missing),
                        outcome.err()));
    }

    @Test
    void kOverNFollowsTheVariablesOfTheProblem() throws IOException {
        Outcome outcome = run(DTLZ2::new, "--check", nsgaii("mutation.probability = 3/n"));

        assertTrue(outcome.out().contains("mutation polynomial (probability 0.25, "),
                "3/n over the 12 variables of DTLZ2: " + outcome.out());
    }

    @ParameterizedTest
    @CsvSource({"3, 'C(H+2, 2) vectors, such as 91 or 105'", "4, 'C(H+3, 3) vectors, such as 84 or 120'"})
    void latticeWeightsAreCheckedAgainstTheObjectivesOfTheProblem(int objectives, String sizes) throws IOException {
        String file = file("moead.properties", "algorithm = moead", "maxEvaluations = 25000", "weights = lattice");

        Outcome outcome = run(() -> new DTLZ2(objectives + 9, objectives), "--check", file);

        assertAll(
                () -> assertEquals(1, outcome.status(), "100 vectors is no lattice for " + objectives + " objectives"),
                () -> assertEquals("Invalid configuration: populationSize: LATTICE weights for " + objectives
                        + " objectives need " + sizes + ", got 100 (spread weights take any size)",
                        outcome.err().strip(), "the closest lattice sizes"));
    }

    @ParameterizedTest
    @CsvSource({"2, 100", "3, 91", "4, 120"})
    void aLatticeSizeOfTheObjectivesPassesTheCheck(int objectives, int size) throws IOException {
        String file = file("moead.properties", "algorithm = moead", "maxEvaluations = 25000", "weights = lattice",
                "populationSize = " + size);

        Outcome outcome = run(() -> new DTLZ2(objectives + 9, objectives), "--check", file);

        assertEquals(0, outcome.status(), size + " vectors make a lattice for " + objectives + " objectives: "
                + outcome.err());
    }

    @Test
    void aLaplaceScaleOfZeroFailsTheCheck() throws IOException {
        Outcome outcome = run(ZDT1::new, "--check", nsgaii("crossover = laplace", "crossover.scale = 0"));

        assertAll(
                () -> assertEquals(1, outcome.status(), "jMetal's LaplaceCrossover would reject it when the run starts"),
                () -> assertTrue(outcome.err().startsWith("Invalid configuration: crossover.scale"), outcome.err()));
    }

    // ── Failures before the master starts ─────────────────────────────────────

    @Test
    void aRunWithAMissingFileFailsBeforeTheMasterStarts() {
        String missing = folder.resolve("missing.properties").toString();
        var problems = new CountingFactory();

        Outcome outcome = run(problems, "localhost", "8080", missing);

        assertAll(
                () -> assertEquals(1, outcome.status(), "an unreadable file"),
                () -> assertTrue(outcome.err().startsWith("Invalid configuration: cannot read configuration file " + missing),
                        outcome.err()),
                () -> assertFalse(outcome.err().contains("Usage:"), "not a usage error: " + outcome.err()),
                () -> assertEquals(1, problems.created.get(), "the problem is created once"),
                () -> assertNull(MasterFacade.configurationHandler(), "no handler registered"));
    }

    @Test
    void aRunWithAnOverrideThatIsNotKeyValueFailsBeforeTheMasterStarts() throws IOException {
        Outcome outcome = run(ZDT1::new, "localhost", "8080", nsgaii(), "maxEvaluations");

        assertAll(
                () -> assertEquals(1, outcome.status(), "an invalid configuration"),
                () -> assertEquals("Invalid configuration: expected key=value, got 'maxEvaluations'", outcome.err().strip()));
    }

    @Test
    void aRunWithLatticeWeightsThatDoNotFitFailsBeforeTheMasterStarts() throws IOException {
        String file = file("moead.properties", "algorithm = moead", "maxEvaluations = 25000", "weights = lattice");

        Outcome outcome = run(DTLZ2::new, "localhost", "8080", file);

        assertAll(
                () -> assertEquals(1, outcome.status(), "the run rejects what the check rejects"),
                () -> assertTrue(outcome.err().startsWith("Invalid configuration: populationSize: LATTICE weights"),
                        outcome.err()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProblemThatCannotBeCreatedFailsWithoutTheUsageText(boolean check) throws IOException {
        Supplier<DoubleProblem> broken = () -> {
            throw new IllegalArgumentException("the model file is missing");
        };
        String file = nsgaii();

        Outcome outcome = check ? run(broken, "--check", file) : run(broken, "localhost", "8080", file);

        assertAll(
                () -> assertEquals(1, outcome.status(), "a failure"),
                () -> assertFalse(outcome.err().contains("Usage:"), "an IllegalArgumentException of the problem is "
                        + "not a usage error: " + outcome.err()));
    }

    // ── Problem class of main ─────────────────────────────────────────────────

    @Test
    void theLauncherChecksAFileForAProblemClass() throws IOException {
        Outcome outcome = launch(ZDT1_CLASS, "--check", nsgaii());

        assertAll(
                () -> assertEquals(0, outcome.status(), "the check passes"),
                () -> assertTrue(outcome.out().startsWith("Configuration OK for ZDT1: NSGA-II, "),
                        "the simple class name is the title: " + outcome.out()));
    }

    @Test
    void theUsageOfTheLauncherNamesTheProblemClass() {
        Outcome outcome = launch(ZDT1_CLASS, "localhost");

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().contains(
                        "Usage: ConfiguredMaster " + ZDT1_CLASS + " <host> <port> <configFile>"),
                        outcome.err()));
    }

    @Test
    void theLauncherNeedsAProblemClass() {
        Outcome outcome = launch();

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().contains("Usage: ConfiguredMaster <problemClass> <host> <port> <configFile>"),
                        outcome.err()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "com.example.MissingProblem|problem class com.example.MissingProblem is not on the class path",
            "java.lang.String|java.lang.String is not a org.uma.jmetal.problem.Problem",
            "es.unex.jdisrest.config.ConfiguredMasterTest$SizedProblem|"
                    + "es.unex.jdisrest.config.ConfiguredMasterTest$SizedProblem has no public constructor without arguments",
            "es.unex.jdisrest.config.ConfiguredMasterTest$UnfinishedProblem|"
                    + "es.unex.jdisrest.config.ConfiguredMasterTest$UnfinishedProblem must be a public class "
                    + "that is not abstract"})
    void aClassThatIsNoUsableProblemIsAUsageError(String name, String reason) throws IOException {
        Outcome outcome = launch(name, "--check", nsgaii());

        assertAll(
                () -> assertEquals(1, outcome.status(), "a usage error"),
                () -> assertTrue(outcome.err().startsWith("Invalid arguments: " + reason), outcome.err()),
                () -> assertTrue(outcome.err().contains("Usage: ConfiguredMaster <problemClass>"), outcome.err()));
    }

    // ── Problems of every encoding ────────────────────────────────────────────

    static Stream<Arguments> examplesOfEachEncoding() {
        return Stream.of(
                Arguments.of(NMMin.class, "integer.properties", "NMMin: MOEA/D, 25000 evaluations, population 100, "
                        + "weights spread, neighborhood 20 (selection probability 0.9, at most 2 replaced), aggregation "
                        + "tchebycheff, crossover sbx (probability 0.9, distributionIndex 20), mutation polynomial "
                        + "(probability 0.05, distributionIndex 20), traces in traces",
                        "Variables: 20 integer variables; operator keys crossover, mutation"),
                Arguments.of(ZDT5.class, "binary.properties", "ZDT5: NSGA-II, 25000 evaluations, population 100, "
                        + "crossover singlePoint (probability 0.9), mutation bitFlip (probability 0.0125), traces in traces",
                        "Variables: 80 bits; operator keys crossover, mutation"),
                Arguments.of(MixedIntegerDoubleProblem.class, "composite.properties", "MixedIntegerDoubleProblem: PAES, "
                        + "25000 evaluations, archive 100, integer [mutation random (probability 0.2)], real [mutation "
                        + "polynomial (probability 0.1, distributionIndex 20)], archive selection probability 0, result "
                        + "paes, traces in traces",
                        "Variables: integer (10 integer variables), real (10 real variables); operator keys "
                                + "integer.mutation, real.mutation"));
    }

    @ParameterizedTest
    @MethodSource("examplesOfEachEncoding")
    void theExampleOfEachEncodingPassesTheCheckForItsProblem(Class<?> problem, String example, String summary,
            String variables) {
        Outcome outcome = launch(problem.getName(), "--check", Path.of("examples", example).toString());

        assertAll(
                () -> assertEquals(0, outcome.status(), "the check passes: " + outcome.err()),
                () -> assertEquals(List.of("Configuration OK for " + summary, variables), outcome.out().lines().toList(),
                        "the summary, k/n over the values of the problem, then its variables and their keys"),
                () -> assertEquals("", outcome.err(), "nothing to complain about"));
    }

    @Test
    void theCheckOfARealCodedProblemPrintsTheSingleLineOf12() {
        Outcome outcome = launch(ZDT1_CLASS, "--check", Path.of("examples", "nsgaii.properties").toString());

        assertEquals(List.of("Configuration OK for ZDT1: NSGA-II, 25000 evaluations, population 100, crossover sbx "
                        + "(probability 0.9, distributionIndex 20), mutation polynomial (probability 0.03333, "
                        + "distributionIndex 20), traces in traces"), outcome.out().lines().toList(),
                "no line about the variables, which are those of 1.2");
    }

    @Test
    void theCheckOfACompositeNamesItsSegmentsWithTheirSizesAndKeys() throws IOException {
        Outcome outcome = run(TestProblems::mixed, "--check", nsgaii("ints.mutation = random",
                "bits.mutation.probability = 2/n"));

        assertAll(
                () -> assertEquals(0, outcome.status(), "the check passes: " + outcome.err()),
                () -> assertTrue(outcome.out().contains(", ints [crossover sbx (probability 0.9, distributionIndex 20), "
                        + "mutation random (probability 0.3333)], reals [crossover sbx (probability 0.9, distributionIndex "
                        + "20), mutation polynomial (probability 0.5, distributionIndex 20)], bits [crossover singlePoint "
                        + "(probability 0.9), mutation bitFlip (probability 0.25)], "),
                        "the operators of each segment, k/n over its size: " + outcome.out()),
                () -> assertEquals("Variables: ints (3 integer variables), reals (2 real variables), bits (8 bits); "
                        + "operator keys ints.crossover, ints.mutation, reals.crossover, reals.mutation, bits.crossover, "
                        + "bits.mutation", outcome.out().lines().toList().getLast(), "the names the problem gives"));
    }

    @Test
    void aSegmentOfOneValueCountsItInTheSingular() throws IOException {
        Outcome outcome = run(() -> new FromFactory<>("Small", () -> composite(integers(1, 0, 5), bits(1))),
                "--check", nsgaii());

        assertEquals("Variables: integer (1 integer variable), binary (1 bit); operator keys integer.crossover, "
                + "integer.mutation, binary.crossover, binary.mutation", outcome.out().lines().toList().getLast(),
                "the default names of the segments");
    }

    @Test
    void anOverrideTakesThePrefixOfItsSegment() {
        Outcome outcome = launch(MixedIntegerDoubleProblem.class.getName(), "--check",
                Path.of("examples", "composite.properties").toString(), "integer.mutation=gaussian");

        assertTrue(outcome.out().contains(", integer [mutation gaussian (probability 0.2)], "),
                "the override replaces the mutation of the integer segment: " + outcome.out());
    }

    @Test
    void aKeyWithoutThePrefixOfItsSegmentFailsTheCheckOfAComposite() {
        Outcome outcome = launch(MixedIntegerDoubleProblem.class.getName(), "--check",
                Path.of("examples", "composite.properties").toString(), "mutation.probability=0.5");

        assertAll(
                () -> assertEquals(1, outcome.status(), "the check fails"),
                () -> assertTrue(outcome.err().startsWith("Invalid configuration: unknown keys for paes with these "
                        + "operators: mutation.probability. Valid keys: algorithm, maxEvaluations, archiveSize, "
                        + "integer.mutation, integer.mutation.probability, real.mutation, "), outcome.err()),
                () -> assertEquals("", outcome.out(), "no summary"));
    }

    @Test
    void theOperatorsOfAnotherEncodingFailTheCheck() {
        Outcome outcome = launch(ZDT5.class.getName(), "--check", Path.of("examples", "nsgaii.properties").toString());

        assertAll(
                () -> assertEquals(1, outcome.status(), "the check fails"),
                () -> assertEquals("Invalid configuration: crossover must be one of singlePoint, hux, uniform (binary "
                        + "variables), got 'sbx'", outcome.err().strip(), "the catalogue of the binary variables"));
    }

    // ── Problems that cannot be configured ────────────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProblemWhoseSolutionsCannotBeConfiguredIsReportedWithoutUsageNorStackTrace(boolean check) throws IOException {
        String file = nsgaii();

        Outcome outcome = check ? run(PermutationProblem::new, "--check", file)
                : run(PermutationProblem::new, "localhost", "8080", file);

        assertAll(
                () -> assertEquals(1, outcome.status(), "an unsupported problem"),
                () -> assertEquals("Unsupported problem: cannot configure the operators of PermutationProblem: its "
                        + "solutions are " + IntegerPermutationSolution.class.getName() + ", where the configuration "
                        + "files take a DoubleSolution, an IntegerSolution, a BinarySolution or a CompositeSolution of "
                        + "those", outcome.err().strip(), "the reason, on one line"),
                () -> assertEquals("", outcome.out(), "no summary"),
                () -> assertNull(MasterFacade.configurationHandler(), "no handler registered"));
    }

    @Test
    void theLauncherAcceptsTheClassOfAnUnsupportedProblemAndThenReportsIt() throws IOException {
        Outcome outcome = launch(PermutationProblem.class.getName(), "--check", nsgaii());

        assertAll(
                () -> assertEquals(1, outcome.status(), "an unsupported problem"),
                () -> assertTrue(outcome.err().startsWith("Unsupported problem: cannot configure the operators of "
                        + "PermutationProblem: "), "not a usage error: " + outcome.err()),
                () -> assertFalse(outcome.err().contains("Usage:"), "no usage text: " + outcome.err()));
    }

    @Test
    void aFailureOfTheProblemWhileCreatingASolutionIsNoUnsupportedProblem() throws IOException {
        var problem = new FromFactory<IntegerSolution>("Broken", () -> {
            throw new IllegalArgumentException("the model file is missing");
        });

        Outcome outcome = run(() -> problem, "--check", nsgaii());

        assertAll(
                () -> assertEquals(1, outcome.status(), "a failure"),
                () -> assertEquals("", outcome.err(), "logged with its stack trace, not printed as the reason of an "
                        + "unsupported problem"));
    }

    // ── Solutions and random numbers ──────────────────────────────────────────

    @Test
    void aRealCodedProblemIsReadWithoutCreatingASolutionOrDrawingARandomNumber() throws IOException {
        var problem = new CountedZDT1();
        String file = nsgaii();
        String invalid = file("invalid.properties", "algorithm = nsgaii", "maxEvaluations = 25000", "populationSize = 0");

        assertAll(
                () -> assertTrue(TestProblems.drawsNoRandomNumber(() -> run(() -> problem, "--check", file)),
                        "the check"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(() -> run(() -> problem, "localhost", "8080", invalid)),
                        "a run, which reads its file as the check does before the master starts"),
                () -> assertEquals(0, problem.created.get(), "the layout of a DoubleProblem comes from its variables"));
    }

    @Test
    void aProblemOfAnotherEncodingCreatesOneSolutionToReadItsLayout() throws IOException {
        FromFactory<IntegerSolution> problem = flatIntegers();
        String invalid = file("invalid.properties", "algorithm = nsgaii", "maxEvaluations = 25000", "populationSize = 0");

        Outcome check = run(() -> problem, "--check", nsgaii());
        int created = problem.created.get();
        run(() -> problem, "localhost", "8080", invalid);

        assertAll(
                () -> assertEquals(0, check.status(), "the check passes: " + check.err()),
                () -> assertEquals(1, created, "one solution for the check"),
                () -> assertEquals(2, problem.created.get(), "and one for the run, which fails before the master starts"));
    }

    // ── The layout createAlgorithm checks ─────────────────────────────────────

    @Test
    void createAlgorithmRefusesAConfigurationReadForAnotherLayoutBeforeBuildingAnything() {
        AlgorithmConfig binary = AlgorithmConfig.parseText("algorithm = nsgaii\nmaxEvaluations = 100\n", new ZDT5());
        AlgorithmConfig integer = AlgorithmConfig.parseText("algorithm = paes\nmaxEvaluations = 100\n", new NMMin());
        AlgorithmConfig composite = AlgorithmConfig.parseText("algorithm = moead\nmaxEvaluations = 100\n",
                new MixedIntegerDoubleProblem());
        AlgorithmConfig realFirst = AlgorithmConfig.parseText("algorithm = nsgaii\nmaxEvaluations = 100\n",
                new FromFactory<>("RealFirst", () -> composite(reals(30, 0.0, 1.0), integers(2, 0, 10))));

        assertAll(
                () -> assertEquals("the configuration was read for binary (80 bits), but the solutions of NMMin are "
                        + "integer (20 variables)", assertThrows(IllegalArgumentException.class,
                        () -> ConfiguredMaster.createAlgorithm("localhost", 0, new NMMin(), binary)).getMessage(),
                        "a flat problem of another encoding"),
                () -> assertEquals("the configuration was read for integer (20 variables), but the solutions of ZDT1 are "
                        + "real (30 variables)", assertThrows(IllegalArgumentException.class,
                        () -> ConfiguredMaster.createAlgorithm("localhost", 0, new ZDT1(), integer)).getMessage(),
                        "the overload for a DoubleProblem"),
                () -> assertEquals("the configuration was read for a composite of real (30 variables), integer (2 "
                        + "variables), but the solutions of ZDT1 are real (30 variables)", assertThrows(
                        IllegalArgumentException.class,
                        () -> ConfiguredMaster.createAlgorithm("localhost", 0, new ZDT1(), realFirst)).getMessage(),
                        "a composite whose first segment is real, for the overload for a DoubleProblem"),
                () -> assertEquals("the configuration was read for a composite of integer (10 variables), real (10 "
                        + "variables), but the solutions of Mixed are a composite of integer (3 variables), real (2 "
                        + "variables), binary (8 bits)", assertThrows(IllegalArgumentException.class,
                        () -> ConfiguredMaster.createAlgorithm("localhost", 0, mixed(), composite)).getMessage(),
                        "a composite of other segments"));
    }
}

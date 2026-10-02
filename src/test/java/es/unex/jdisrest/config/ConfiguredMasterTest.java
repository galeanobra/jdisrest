package es.unex.jdisrest.config;

import es.unex.jdisrest.config.ConfiguredMaster.Launch;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.problem.doubleproblem.impl.AbstractDoubleProblem;
import org.uma.jmetal.problem.multiobjective.dtlz.DTLZ2;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generic launcher without starting a master: the command line, {@code --check} with its
 * status and messages, the checks against the problem (variables, objectives, operators), the
 * failures that must happen before the server starts, and the problem class of {@code main}.
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
    static Outcome run(Supplier<? extends DoubleProblem> problem, String... args) {
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
            "java.lang.String|java.lang.String is not a org.uma.jmetal.problem.doubleproblem.DoubleProblem",
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
}

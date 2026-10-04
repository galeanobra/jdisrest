package es.unex.jdisrest.distributed;

import es.unex.jdisrest.util.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Warm start: when the problem is asked for its initial solutions, what is copied into the
 * traces folder, and what is logged; and {@link WarmStart#initialPopulation}, which reads the
 * file for the problem, completes it, and rejects it as a whole when a row does not fit.
 *
 * <p>Every test passes its own warm-start file inside a temporary folder, so nothing is read
 * from or written to the working directory. Log lines are captured from stderr, where
 * {@link Log} writes them.
 */
class WarmStartTest {

    private static final String POPULATION = "0.1,0.2\n0.3,0.4\n";

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A problem whose solutions are strings and that has no warm start. */
    static class RandomProblem implements Problem<String> {
        @Override public int numberOfVariables() { return 2; }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "random"; }
        @Override public String evaluate(String solution) { return solution; }
        @Override public String createSolution() { return "random"; }
    }

    /** A warm-start problem that builds its population with the given function and counts the calls. */
    static final class WarmProblem extends RandomProblem implements WarmStartCapable<String> {
        private final IntFunction<List<String>> population;
        int calls;

        WarmProblem(IntFunction<List<String>> population) {
            this.population = population;
        }

        @Override
        public List<String> createInitialPopulationFromFile(int populationSize) {
            calls++;
            return population.apply(populationSize);
        }
    }

    /** A warm-start problem that returns as many solutions as requested, all {@code "loaded"}. */
    static WarmProblem warmProblem() {
        return new WarmProblem(count -> Collections.nCopies(count, "loaded"));
    }

    static Path writePopulation(Path folder) throws IOException {
        return Files.writeString(folder.resolve("iVAR.csv"), POPULATION, UTF_8);
    }

    /**
     * A problem of two integers in [0, 10] that reads its warm start from {@code file} with
     * {@link WarmStart#initialPopulation}, as a problem that reads {@link WarmStart#FILE} does in
     * one line; counts the solutions it creates.
     */
    static final class IntegerProblem implements Problem<IntegerSolution>, WarmStartCapable<IntegerSolution> {
        private final Path file;
        int created;

        IntegerProblem(Path file) {
            this.file = file;
        }

        @Override public int numberOfVariables() { return 2; }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "IntegerProblem"; }
        @Override public IntegerSolution evaluate(IntegerSolution solution) { return solution; }

        @Override
        public IntegerSolution createSolution() {
            created++;
            return new DefaultIntegerSolution(Collections.nCopies(2, Bounds.create(0, 10)), 2, 0);
        }

        @Override
        public List<IntegerSolution> createInitialPopulationFromFile(int populationSize) {
            return WarmStart.initialPopulation(this, populationSize, file);
        }
    }

    static Path writeRows(Path folder, String rows) throws IOException {
        return Files.writeString(folder.resolve("iVAR.csv"), rows, UTF_8);
    }

    static List<List<Integer>> variables(List<IntegerSolution> solutions) {
        return solutions.stream().map(IntegerSolution::variables).toList();
    }

    static List<Path> listing(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.toList();
        }
    }

    /** The value an action returned together with everything it logged. */
    record Logged<T>(T value, String log) {}

    static <T> Logged<T> logged(Supplier<T> action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, UTF_8));
        try {
            T value = action.get();
            return new Logged<>(value, buffer.toString(UTF_8));
        } finally {
            System.setErr(original);
        }
    }

    // ── Warm start used ───────────────────────────────────────────────────────

    @Test
    void warmStartReturnsTheProblemSolutionsAndCopiesTheFile(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = dir.resolve("traces");

        Logged<List<String>> load = logged(() -> WarmStart.load(warmProblem(), 3, file, traces));

        assertEquals(List.of("loaded", "loaded", "loaded"), load.value(),
            "the solutions are those the problem built for the requested count");
        assertEquals(POPULATION, Files.readString(traces.resolve("iVAR.csv"), UTF_8),
            "the traces folder is created and receives a copy of the file");
        assertTrue(load.log().contains("Initial population file copied to " + traces.resolve("iVAR.csv")),
            "a real copy is reported with its target: " + load.log());
        assertFalse(load.log().contains("WARN"), "a complete warm start logs no warning: " + load.log());
    }

    @Test
    void olderCopyInTheTracesIsReplaced(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = Files.createDirectories(dir.resolve("traces"));
        Files.writeString(traces.resolve("iVAR.csv"), "old\n", UTF_8);

        WarmStart.load(warmProblem(), 1, file, traces);

        assertEquals(POPULATION, Files.readString(traces.resolve("iVAR.csv"), UTF_8),
            "a copy left by an earlier run must not survive");
    }

    @Test
    void fileAlreadyInTheTracesFolderIsLeftUntouchedAndNotReportedAsCopied(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);

        Logged<List<String>> load = logged(() -> WarmStart.load(warmProblem(), 1, file, dir));

        assertEquals(List.of("loaded"), load.value());
        assertEquals(POPULATION, Files.readString(file, UTF_8), "copying a file onto itself must not damage it");
        assertFalse(load.log().contains("copied"), "nothing was copied, so nothing is reported: " + load.log());
    }

    @Test
    void noTracesFolderMeansNoCopy(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);

        List<String> population = WarmStart.load(warmProblem(), 1, file, null);

        assertEquals(List.of("loaded"), population);
        assertEquals(List.of(file), listing(dir), "without a traces folder nothing else is written");
    }

    @Test
    void loadedIsLoggedOnceTheProblemHasBuiltItsPopulation(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        WarmProblem problem = new WarmProblem(count -> {
            Log.warn("row 2 is malformed");
            return Collections.nCopies(count, "loaded");
        });

        String log = logged(() -> WarmStart.load(problem, 1, file, null)).log();

        int loaded = log.indexOf("Initial population loaded from " + file + " file");
        assertTrue(loaded >= 0, "the warm start is announced: " + log);
        assertTrue(log.indexOf("row 2 is malformed") < loaded,
            "the line announcing the warm start follows what the problem logged while reading the file: " + log);
    }

    // ── Warm start used with an unexpected population ─────────────────────────

    @Test
    void populationOfAnotherSizeIsKeptWithAWarning(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = dir.resolve("traces");
        WarmProblem problem = new WarmProblem(count -> List.of("first", "second"));

        Logged<List<String>> load = logged(() -> WarmStart.load(problem, 3, file, traces));

        assertEquals(List.of("first", "second"), load.value(), "a short population is used as it is");
        assertTrue(load.log().contains("WARN: Initial population from " + file
            + " has size 2 instead of the 3 requested — starting from it as it is"),
            "the size mismatch is warned with both counts: " + load.log());
        assertTrue(Files.exists(traces.resolve("iVAR.csv")), "the file the run read is still copied");
    }

    @Test
    void problemReturningNoPopulationMeansRandomStartWithoutCopy(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = dir.resolve("traces");

        Logged<List<String>> load = logged(() -> WarmStart.load(new WarmProblem(count -> null), 1, file, traces));

        assertNull(load.value(), "null tells the caller to start from random solutions");
        assertTrue(load.log().contains(
            "WARN: The problem built no initial population from " + file + " — using random initialization"),
            "the fallback to random solutions is warned: " + load.log());
        assertFalse(load.log().contains("Initial population loaded"),
            "a file the run did not start from is not reported as loaded, as 1.2.1 did: " + load.log());
        assertFalse(Files.exists(traces), "a file the run did not start from is not copied");
    }

    @Test
    void tracesPathThatIsARegularFileOnlyWarns(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = Files.writeString(dir.resolve("traces"), "not a folder", UTF_8);

        Logged<List<String>> load = logged(() -> WarmStart.load(warmProblem(), 1, file, traces));

        assertEquals(List.of("loaded"), load.value(), "a failed copy must not cost the run its warm start");
        assertTrue(load.log().contains("WARN: Could not copy " + file + " into " + traces + " ("),
            "the failed copy is warned with its cause: " + load.log());
        assertTrue(load.log().contains(") — the run goes on without the copy"),
            "the warning says what happens next: " + load.log());
        assertEquals("not a folder", Files.readString(traces, UTF_8), "the obstructing file is left as it was");
    }

    // ── Reading the file for the problem ──────────────────────────────────────

    @Test
    void initialPopulationReadsTheFileAndCompletesItWithRandomSolutions(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "1,2\n3,4\n");
        IntegerProblem problem = new IntegerProblem(file);

        Logged<List<IntegerSolution>> read = logged(() -> WarmStart.initialPopulation(problem, 4, file));

        assertEquals(4, read.value().size(), "the list always holds the solutions requested");
        assertEquals(List.of(List.of(1, 2), List.of(3, 4)), variables(read.value().subList(0, 2)),
            "the rows of the file come first, in their order");
        assertEquals(4, problem.created, "one solution of the problem per row, and one per missing slot");
        assertTrue(read.log().contains("INFO: " + file + " holds 2 of the 4 solutions requested — random solutions "
            + "complete them"), "how much of the population comes from the file is logged: " + read.log());
    }

    @Test
    void initialPopulationReadsOnlyTheRowsItNeeds(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "5,6\nnot a row\n");
        IntegerProblem problem = new IntegerProblem(file);

        Logged<List<IntegerSolution>> read = logged(() -> WarmStart.initialPopulation(problem, 1, file));

        assertEquals(List.of(List.of(5, 6)), variables(read.value()), "PAES asks for a single solution");
        assertEquals("", read.log(), "a row after those requested is never read, so it cannot reject the file");
    }

    @Test
    void fileWithARowThatDoesNotFitIsRejectedAsAWholeWithoutBeingLoadedOrCopied(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "1,2\n3,4,5\n6,7\n");
        Path traces = dir.resolve("traces");

        Logged<List<IntegerSolution>> load = logged(() -> WarmStart.load(new IntegerProblem(file), 3, file, traces));

        assertNull(load.value(), "a file that does not fit the problem must not seed part of the run");
        assertTrue(load.log().contains("WARN: Warm-start file rejected as a whole: " + file
            + " line 2: 3 variables, but the solutions of IntegerProblem have 2"),
            "the warning names the file once, the line and the reason: " + load.log());
        assertTrue(load.log().contains(
            "WARN: The problem built no initial population from " + file + " — using random initialization"),
            "the run starts from random solutions: " + load.log());
        assertFalse(load.log().contains("Initial population loaded"),
            "a rejected file is never reported as loaded: " + load.log());
        assertFalse(Files.exists(traces), "nor copied into the traces");
    }

    @Test
    void valueOutsideItsBoundsRejectsTheFile(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "1,2\n3,11\n");
        IntegerProblem problem = new IntegerProblem(file);

        Logged<List<IntegerSolution>> read = logged(() -> WarmStart.initialPopulation(problem, 2, file));

        assertNull(read.value(), "a value no operator expects must not enter the population");
        assertTrue(read.log().contains("WARN: Warm-start file rejected as a whole: " + file
            + " line 2: variables[1] = 11 is outside the bounds [0, 10] of its variable"), read.log());
    }

    @Test
    void fileWithoutRowsGivesNoPopulation(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "\n");
        IntegerProblem problem = new IntegerProblem(file);

        Logged<List<IntegerSolution>> read = logged(() -> WarmStart.initialPopulation(problem, 2, file));

        assertNull(read.value(), "an empty file starts the run from random solutions, as a malformed one does");
        assertTrue(read.log().contains("WARN: " + file + " holds no solution"), read.log());
    }

    @Test
    void fileThatCannotBeReadGivesNoPopulation(@TempDir Path dir) {
        Path file = dir.resolve("iVAR.csv");
        IntegerProblem problem = new IntegerProblem(file);

        Logged<List<IntegerSolution>> read = logged(() -> WarmStart.initialPopulation(problem, 2, file));

        assertNull(read.value(), "the warm start never throws for its file");
        assertTrue(read.log().contains("WARN: " + file + " could not be read: java.nio.file.NoSuchFileException"),
            read.log());
    }

    @Test
    void negativeCountIsRefused(@TempDir Path dir) {
        Path file = dir.resolve("iVAR.csv");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> WarmStart.initialPopulation(new IntegerProblem(file), -1, file));

        assertEquals("count must be at least 0, got -1", e.getMessage());
    }

    @Test
    void problemThatReadsItsFileWithTheHelperStartsFromItAndCopiesIt(@TempDir Path dir) throws IOException {
        Path file = writeRows(dir, "1,2\n3,4\n");
        Path traces = dir.resolve("traces");

        Logged<List<IntegerSolution>> load = logged(() -> WarmStart.load(new IntegerProblem(file), 2, file, traces));

        assertEquals(List.of(List.of(1, 2), List.of(3, 4)), variables(load.value()));
        assertTrue(load.log().contains("Initial population loaded from " + file + " file"), load.log());
        assertTrue(Files.exists(traces.resolve("iVAR.csv")), "the file the run started from is copied");
        assertFalse(load.log().contains("WARN"), "a complete warm start logs no warning: " + load.log());
    }

    // ── Warm start not used ───────────────────────────────────────────────────

    @Test
    void missingFileMeansRandomStartWithoutFolderOrMessages(@TempDir Path dir) {
        Path traces = dir.resolve("traces");
        WarmProblem problem = warmProblem();

        Logged<List<String>> load = logged(() -> WarmStart.load(problem, 1, dir.resolve("iVAR.csv"), traces));

        assertNull(load.value(), "null tells the caller to start from random solutions");
        assertEquals(0, problem.calls, "without the file the problem is never asked for a population");
        assertFalse(Files.exists(traces), "no traces folder is created for a run without warm start");
        assertEquals("", load.log(), "a run without warm start logs nothing about it");
    }

    @Test
    void problemWithoutWarmStartMeansRandomStartAndAWarning(@TempDir Path dir) throws IOException {
        Path file = writePopulation(dir);
        Path traces = dir.resolve("traces");

        Logged<List<String>> load = logged(() -> WarmStart.load(new RandomProblem(), 1, file, traces));

        assertNull(load.value(), "null tells the caller to start from random solutions");
        assertTrue(load.log().contains(
            "WARN: " + file + " found but problem does not implement WarmStartCapable — using random initialization"),
            "an unusable warm-start file is warned: " + load.log());
        assertFalse(Files.exists(traces), "a file the run did not start from is not copied");
    }
}

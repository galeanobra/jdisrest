package es.unex.jdisrest.encodings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.distributed.AbstractMaster;
import es.unex.jdisrest.distributed.RestWorker;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.util.TraceReader;
import es.unex.jdisrest.util.TraceWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One distributed run of a problem of some encoding, end to end, over HTTP: a real master on a
 * free port, two in-process {@link RestWorker}s, each with a problem of its own, and the
 * command-line worker of the Python package ({@code python -m jdisrest}) with an evaluator of
 * {@code encodings/encoding_evaluators.py}. The workers apply a Lamarckian repair before they
 * evaluate ({@link #repair}), so their results carry changed variables back to the master, which
 * must write them into its solutions with the layout it sent them in.
 *
 * <p>Each subclass is one run, started once before its tests: the master is built and its run
 * started; once it is ready the Python worker starts, from the package in {@code python/} of
 * this checkout, and the Java workers start after its first evaluation, so that it takes part
 * however fast they are; the run ends on its budget of {@link #budget()} evaluations. The tests
 * then check that no task was discarded, that every worker evaluated tasks, that every final
 * solution carries the repair, that the result and the last snapshot of the traces hold the rows
 * the encoding should give and read back into solutions of the problem with {@link TraceReader},
 * as a warm start reads them, that the Python evaluator received every vector with the layout of
 * the problem and had none of its results refused, and that {@code python/tools/watch_front.py
 * --once} reads the traces and keeps their integers and bit strings as written in
 * {@code front_extremes.csv}.
 *
 * <p>The Python worker needs Python 3.11 or later with {@code requests}: the interpreter that
 * {@code -Djdisrest.test.python} names, else {@code python3} or {@code python} on the
 * {@code PATH}. Without one the run goes on with the Java workers alone, and the tests that need
 * Python, of its worker and of {@code watch_front.py}, are skipped, saying why.
 *
 * <p>Like {@code EndOfRunScenario}, a subclass starts a master, which registers itself in static
 * singletons that nothing resets, so each needs a JVM of its own: its name ends in {@code IT},
 * Failsafe runs it in a new JVM ({@code mvn verify}), and it runs only when the system property
 * {@code jdisrest.it} is {@code true}. The discovery file, the traces and the result go to a
 * temporary folder.
 *
 * @param <S> the solution type of the problem
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EncodingRunScenario<S extends Solution<?>> {

    /** Longest wait for the master, the run or a worker; a test that reaches it fails. */
    static final long TIMEOUT_S = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A Python 3.11 or later with {@code requests}, or {@code null} when none is found. */
    static final String PYTHON = findPython();

    private static final String NO_PYTHON = "no Python 3.11 or later with requests (python3 or python on the "
            + "PATH, or -Djdisrest.test.python=<interpreter>)";

    /** Where the master writes {@code .master-endpoint}, its traces and the result. */
    @TempDir
    static Path dataPath;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_S))
            .build();
    private final List<WorkerProblem> workerProblems = new ArrayList<>();
    private final List<RestWorker<S>> workers = new ArrayList<>();
    private SteadyStateEvolutionaryAlgorithm<S> algorithm;
    private String url;
    private Process python;
    private Path pythonLog;
    private Path pythonRecord;

    // ── The run ───────────────────────────────────────────────────────────────

    /** A new instance of the problem; the master and each worker get their own. */
    abstract Problem<S> problem();

    /** The algorithm, built on {@code host} and {@code port} for {@code problem}, with its traces in {@code tracesFolder}. */
    abstract SteadyStateEvolutionaryAlgorithm<S> algorithm(String host, int port, Problem<S> problem,
                                                           String tracesFolder);

    /** The run's evaluation budget. */
    abstract int budget();

    /** The Lamarckian repair both workers apply before they evaluate a solution. */
    abstract void repair(S solution);

    /** Whether a solution carries the repair. */
    abstract boolean isRepaired(S solution);

    /** What every {@code VAR} row of the result and of the traces must match. */
    abstract Pattern varRow();

    /** What the variables of every row of {@code front_extremes.csv} must match, joined by commas. */
    abstract Pattern extremeVariables();

    /**
     * The options of the Python worker besides those that connect it: its evaluator, a function
     * of {@code encoding_evaluators.py} that applies {@link #repair} too, and the checks of the
     * tasks it gets.
     */
    abstract List<String> pythonWorkerOptions();

    /** The layout of every vector the Python evaluator must receive, as it records it (a JSON object). */
    abstract String pythonLayout();

    @BeforeAll
    void runUntilTheBudgetIsSpent() throws Exception {
        assertNull(SteadyStateMaster.getInstance(),
                "a master already ran in this JVM: run each *IT class in a JVM of its own (mvn verify)");
        System.setProperty("jdisrest.dataPath", dataPath.toString());
        System.setProperty("server.address", "127.0.0.1");  // only this machine can reach the master
        pythonLog = dataPath.resolve("python-worker.log");
        pythonRecord = dataPath.resolve("python-evaluations.jsonl");
        algorithm = algorithm("127.0.0.1", 0, problem(), dataPath.resolve("traces").toString());  // port 0: a free one
        url = JSON.readTree(Files.readString(dataPath.resolve(AbstractMaster.ENDPOINT_FILE_NAME))).get("url").asText();
        MasterFacade.init(budget(), 0);

        Thread runner = Thread.ofPlatform().daemon().name("algorithm").start(algorithm::run);
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (!algorithm.isReady()) {
            assertTrue(Instant.now().isBefore(deadline), "the algorithm never became ready");
            Thread.sleep(5);
        }
        // Started once the master hands out tasks, so that no worker waits after a 204; the Java
        // workers only after the first evaluation of the Python one, which starts much slower.
        if (PYTHON != null) {
            python = startPythonWorker();
            deadline = Instant.now().plusSeconds(TIMEOUT_S);
            while (python.isAlive() && pythonEvaluations() == 0 && Instant.now().isBefore(deadline)) {
                Thread.sleep(5);
            }
        }
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            WorkerProblem problem = new WorkerProblem(problem());
            RestWorker<S> worker = new RestWorker<>(url, problem, "java-" + i);
            workerProblems.add(problem);
            workers.add(worker);
            threads.add(Thread.ofPlatform().daemon().name("worker-" + i).start(worker::run));
        }
        runner.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        assertFalse(runner.isAlive(), "the run did not end within " + TIMEOUT_S + " s");
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
            assertFalse(thread.isAlive(), "a worker did not stop after the end of the run");
        }
        if (python != null) {
            python.waitFor(TIMEOUT_S, TimeUnit.SECONDS);  // checked by its test
        }
        TraceWriter.write(algorithm.getResult(), dataPath.resolve("VAR.csv").toString(),
                dataPath.resolve("FUN.csv").toString(), ",");
    }

    @AfterAll
    void shutDown() {
        if (python != null && python.isAlive()) {
            python.descendants().forEach(ProcessHandle::destroyForcibly);
            python.destroyForcibly();
        }
        workers.forEach(RestWorker::close);
        if (algorithm != null) {
            algorithm.shutdown();
        }
        http.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void runEndsOnItsBudgetWithoutDiscardingATask() throws Exception {
        JsonNode status = get("/api/v1/status");

        assertEquals(budget(), algorithm.getEvaluations());
        assertTrue(status.get("finished").asBoolean());
        assertEquals(budget(), status.get("evaluations").asInt());
        assertEquals(0, status.get("discardedTasks").asInt(),
                "every task reached a worker and came back in a layout the master could apply");
    }

    @Test
    void everyWorkerEvaluatedTasks() throws IOException {
        for (WorkerProblem problem : workerProblems) {
            assertTrue(problem.evaluations.get() > 0, "both Java workers must take part");
        }
        int javaEvaluations = workerProblems.stream().mapToInt(p -> p.evaluations.get()).sum();
        assertTrue(javaEvaluations + pythonEvaluations() >= budget(),
                "every result the algorithm used was evaluated by a worker");
    }

    @Test
    void thePythonWorkerEvaluatedTasksWithTheLayoutOfTheProblem() throws Exception {
        assumeTrue(PYTHON != null, NO_PYTHON + ": the run went without the Python worker");
        assertFalse(python.isAlive(), "the Python worker did not stop after the end of the run");
        String log = Files.readString(pythonLog);
        assertEquals(0, python.exitValue(), "the Python worker stops with 0 once the run has finished:\n" + log);

        List<String> records = Files.exists(pythonRecord) ? Files.readAllLines(pythonRecord) : List.of();
        assertFalse(records.isEmpty(), "the Python worker must take part:\n" + log);
        JsonNode expected = JSON.readTree(pythonLayout());
        for (String record : records) {
            assertEquals(expected, JSON.readTree(record), "what the Python evaluator received");
        }
        // An evaluation that fails, a repair the worker cannot send and a result the master
        // refuses are all logged at ERROR.
        assertFalse(log.contains(" ERROR "), "no evaluation of the Python worker failed:\n" + log);
    }

    @Test
    void everyFinalSolutionCarriesTheRepairOfTheWorkers() {
        List<S> result = algorithm.getResult();

        assertFalse(result.isEmpty());
        result.forEach(s -> assertTrue(isRepaired(s), "the master wrote the repaired variables back: " + s.variables()));
    }

    @Test
    void resultAndTracesHoldTheRowsOfTheEncoding() throws Exception {
        List<String> rows = Files.readAllLines(dataPath.resolve("VAR.csv"));
        List<String> traced = Files.readAllLines(dataPath.resolve("traces").resolve("aVAR_" + budget() + ".csv"));

        assertEquals(algorithm.getResult().size(), rows.size());
        assertFalse(traced.isEmpty());
        for (String row : rows) assertTrue(varRow().matcher(row).matches(), "VAR.csv: " + row);
        for (String row : traced) assertTrue(varRow().matcher(row).matches(), "aVAR_" + budget() + ".csv: " + row);
    }

    @Test
    void resultAndTracesReadBackIntoSolutionsOfTheProblem() throws IOException {
        Path traced = dataPath.resolve("traces").resolve("aVAR_" + budget() + ".csv");
        for (Path file : List.of(dataPath.resolve("VAR.csv"), traced)) {
            List<S> read = TraceReader.read(problem(), file);
            Path again = dataPath.resolve("again-" + file.getFileName());
            TraceWriter.write(read, again.toString(), dataPath.resolve("again-FUN.csv").toString(), ",");

            assertEquals(variableRows(file), variableRows(again), file.getFileName() + " read back and written again");
            read.forEach(s -> assertTrue(isRepaired(s), file.getFileName() + ": the repaired variable reads back too"));
        }
    }

    @Test
    void watchFrontKeepsTheVariablesOfTheTracesAsWritten() throws Exception {
        assumeTrue(PYTHON != null, NO_PYTHON + ": watch_front.py did not read the traces");
        Path output = dataPath.resolve("watch-front");
        Path log = dataPath.resolve("watch-front.log");
        Path script = checkout().resolve("python").resolve("tools").resolve("watch_front.py");
        Process watch = python(List.of(script.toString(), dataPath.resolve("traces").toString(), "--once",
                "--output-dir", output.toString()), log).start();
        boolean exited = watch.waitFor(TIMEOUT_S, TimeUnit.SECONDS);
        if (!exited) watch.destroyForcibly();

        assertTrue(exited, "watch_front.py --once did not exit within " + TIMEOUT_S + " s");
        assertEquals(0, watch.exitValue(), "watch_front.py --once saves every snapshot and exits with 0:\n"
                + Files.readString(log));
        List<String> rows = Files.readAllLines(output.resolve("front_extremes.csv"));
        int first = List.of(rows.getFirst().split(",")).indexOf("x1");
        assertTrue(first > 0 && rows.size() > 1, "front_extremes.csv holds the extremes with their variables: " + rows);
        for (String row : rows.subList(1, rows.size())) {
            List<String> columns = List.of(row.split(","));
            String variables = String.join(",", columns.subList(first, columns.size()));
            assertTrue(extremeVariables().matcher(variables).matches(), "front_extremes.csv: " + row);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The scenario's problem as a worker runs it: the repair, then the evaluation, counted. */
    final class WorkerProblem implements Problem<S> {
        private final Problem<S> problem;
        final AtomicInteger evaluations = new AtomicInteger();

        WorkerProblem(Problem<S> problem) {
            this.problem = problem;
        }

        @Override public int numberOfVariables() { return problem.numberOfVariables(); }
        @Override public int numberOfObjectives() { return problem.numberOfObjectives(); }
        @Override public int numberOfConstraints() { return problem.numberOfConstraints(); }
        @Override public String name() { return problem.name(); }
        @Override public S createSolution() { return problem.createSolution(); }

        @Override
        public S evaluate(S solution) {
            repair(solution);
            problem.evaluate(solution);
            evaluations.incrementAndGet();
            return solution;
        }
    }

    /**
     * Starts the command-line worker of the Python package in {@code python/} of this checkout on
     * the master of the run, with the evaluators among the test resources.
     */
    private Process startPythonWorker() throws Exception {
        Path evaluators = Path.of(EncodingRunScenario.class.getResource("/encodings/encoding_evaluators.py").toURI());
        List<String> arguments = new ArrayList<>(List.of("-m", "jdisrest", "--code-dir",
                evaluators.getParent().toString(), "--master", url, "--worker-id", "python-0"));
        arguments.addAll(pythonWorkerOptions());
        ProcessBuilder builder = python(arguments, pythonLog);
        builder.environment().put("JDISREST_IT_RECORD", pythonRecord.toString());
        return builder.start();
    }

    /**
     * {@link #PYTHON} with {@code arguments}, its output and errors to {@code log}, and the
     * package in {@code python/} of this checkout on its path.
     */
    private static ProcessBuilder python(List<String> arguments, Path log) throws URISyntaxException {
        List<String> command = new ArrayList<>(List.of(PYTHON));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // No bytecode is written next to the sources, and the log, whose lines have non-ASCII
        // characters, is UTF-8 on Windows too.
        builder.environment().put("PYTHONPATH", checkout().resolve("python").toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PYTHONUTF8", "1");
        return builder;
    }

    /** The checkout the tests run from, two levels above {@code target/test-classes}. */
    private static Path checkout() throws URISyntaxException {
        return Path.of(EncodingRunScenario.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent();
    }

    /** The variables of each row of a VAR file: the text before {@code ,[}, the whole row of a flat solution. */
    private static List<String> variableRows(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(row -> row.split(",\\[", 2)[0]).toList();
    }

    /** The evaluations the Python evaluator has recorded so far. */
    private int pythonEvaluations() throws IOException {
        return pythonRecord != null && Files.exists(pythonRecord) ? Files.readAllLines(pythonRecord).size() : 0;
    }

    /**
     * A Python 3.11 or later that can import {@code requests}: the one {@code jdisrest.test.python}
     * names, else the first of {@code python3} and {@code python} on the {@code PATH} ({@code python}
     * first on Windows, where {@code python3} may only open the Microsoft Store); {@code null} if
     * none can. {@code PythonProcessEvaluatorTest.findPython} tries the same candidates for any
     * Python 3, all that the local mode needs: keep the two in step.
     */
    private static String findPython() {
        String configured = System.getProperty("jdisrest.test.python");
        List<String> candidates = configured != null ? List.of(configured)
                : System.getProperty("os.name", "").startsWith("Windows") ? List.of("python", "python3")
                : List.of("python3", "python");
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-c",
                        "import sys, requests; sys.exit(sys.version_info < (3, 11))")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(TIMEOUT_S, TimeUnit.SECONDS) && p.exitValue() == 0) return candidate;
                p.destroyForcibly();
            } catch (IOException notInstalled) {
                // try the next candidate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** A {@code GET} that must answer {@code 200}, and its JSON body. */
    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url + path))
                .timeout(Duration.ofSeconds(TIMEOUT_S)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "GET " + path);
        return JSON.readTree(response.body());
    }
}

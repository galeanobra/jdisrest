package es.unex.jdisrest.encodings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.distributed.AbstractMaster;
import es.unex.jdisrest.distributed.RestWorker;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.util.TraceWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;

import java.net.URI;
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

/**
 * One distributed run of a problem of some encoding, end to end, over HTTP: a real master on a
 * free port and two in-process {@link RestWorker}s, each with a problem of its own. The workers
 * apply a Lamarckian repair before they evaluate ({@link #repair}), so their results carry
 * changed variables back to the master, which must write them into its solutions with the layout
 * it sent them in.
 *
 * <p>Each subclass is one run, started once before its tests: the master is built and its run
 * started, the workers start once it is ready, and the run ends on its budget of
 * {@link #budget()} evaluations. The tests then check that no task was discarded, that both
 * workers evaluated tasks, that every final solution carries the repair, and that the result
 * and the last snapshot of the traces hold the rows the encoding should give.
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

    @BeforeAll
    void runUntilTheBudgetIsSpent() throws Exception {
        assertNull(SteadyStateMaster.getInstance(),
                "a master already ran in this JVM: run each *IT class in a JVM of its own (mvn verify)");
        System.setProperty("jdisrest.dataPath", dataPath.toString());
        System.setProperty("server.address", "127.0.0.1");  // only this machine can reach the master
        algorithm = algorithm("127.0.0.1", 0, problem(), dataPath.resolve("traces").toString());  // port 0: a free one
        url = JSON.readTree(Files.readString(dataPath.resolve(AbstractMaster.ENDPOINT_FILE_NAME))).get("url").asText();
        MasterFacade.init(budget(), 0);

        Thread runner = Thread.ofPlatform().daemon().name("algorithm").start(algorithm::run);
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (!algorithm.isReady()) {
            assertTrue(Instant.now().isBefore(deadline), "the algorithm never became ready");
            Thread.sleep(5);
        }
        // Started once the master hands out tasks, so that neither waits after a 204.
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
        TraceWriter.write(algorithm.getResult(), dataPath.resolve("VAR.csv").toString(),
                dataPath.resolve("FUN.csv").toString(), ",");
    }

    @AfterAll
    void shutDown() {
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
    void everyWorkerEvaluatedTasks() {
        for (WorkerProblem problem : workerProblems) {
            assertTrue(problem.evaluations.get() > 0, "both workers must take part");
        }
        assertTrue(workerProblems.stream().mapToInt(p -> p.evaluations.get()).sum() >= budget(),
                "every result the algorithm used was evaluated by a worker");
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

    /** A {@code GET} that must answer {@code 200}, and its JSON body. */
    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url + path))
                .timeout(Duration.ofSeconds(TIMEOUT_S)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "GET " + path);
        return JSON.readTree(response.body());
    }
}

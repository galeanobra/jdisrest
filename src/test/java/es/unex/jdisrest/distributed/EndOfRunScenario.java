package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.config.AlgorithmConfig;
import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a steady-state master takes from the workers and what it reports once its run has ended,
 * end to end: a real master, built as {@code ConfiguredMaster} builds it (NSGA-II on ZDT1), serves
 * its REST API on a free port, and the test plays the workers over HTTP. The unit tests cover the
 * rules on masters without a server; this covers the wiring that needs a master registered in the
 * static facade of the REST layer: the evaluations of {@code GET /api/v1/status},
 * {@code GET /api/v1/workers/status} and {@code status.json}, and the answers to invalid and late
 * reports.
 *
 * <p>Each subclass is one run, set up once before its tests. Every task is handed out first, and
 * an invalid result is posted for one of them by a worker that does not hold it. Then the results
 * of {@link #used()} + {@value #BACKLOG} of them are posted while an observer holds the algorithm
 * thread inside {@code updateProgress()} after its {@link #used()}-th result, so the last
 * {@value #BACKLOG} are accepted but never processed. While the thread is held both status
 * endpoints are read, and {@link #endTheRun()} runs; the run ends once the thread is released.
 * Three tasks are still in flight then, one for each late report. The tests run in order, and the
 * last one shuts the master down.
 *
 * <p>A master registers itself in static singletons ({@code SteadyStateMaster.getInstance()},
 * {@code MasterFacade}) that nothing resets, so each subclass starts a single master and needs a
 * JVM of its own: its name ends in {@code IT}, and Failsafe runs every such class in a new JVM
 * ({@code mvn verify}, see {@code pom.xml}). The subclasses run only when the system property
 * {@code jdisrest.it} is {@code true}, which Failsafe sets, so that an IDE that runs every test in
 * one JVM skips them; set it to run one subclass on its own. Nothing is written to the working
 * directory: the discovery file and {@code status.json} go to a temporary folder, and the run
 * writes no traces.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class EndOfRunScenario {

    /** Results accepted while the algorithm thread is held, which it never processes. */
    static final int BACKLOG = 2;

    /** Longest wait for the master, the algorithm thread or an HTTP answer; a test that reaches it fails. */
    static final long TIMEOUT_S = 10;

    /**
     * Seconds between writes of {@code status.json}, longer than the test: the file is written
     * once when the run starts and once more by {@code shutdown()}, and by nothing in between.
     */
    private static final int STATUS_FILE_INTERVAL_S = 3600;

    private static final int POPULATION_SIZE = 4;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where the master writes {@code .master-endpoint} and {@code status.json}. */
    @TempDir
    static Path dataPath;

    private final ZDT1 problem = new ZDT1();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_S))
            .build();
    /** Releases the algorithm thread held after its last result. */
    private final CountDownLatch release = new CountDownLatch(1);

    SteadyStateEvolutionaryAlgorithm<DoubleSolution> algorithm;
    private Thread runner;
    private String url;

    // Tasks still in flight when the run ends, one for each late report.
    private Task lateResult;
    private Task lateInvalidResult;
    private Task lateError;

    // Read while the run goes on: the answer to an invalid result from a worker that does not hold
    // the task, and the holder of the task afterwards.
    private int invalidResultAnswer;
    private String holderAfterTheInvalidResult;

    // Read while the algorithm thread is held after its last result, before the run ends.
    private JsonNode statusWhileHeld;
    private JsonNode clusterWhileHeld;

    // ── The run ───────────────────────────────────────────────────────────────

    /** The run's evaluation budget ({@code maxEvaluations}). */
    abstract int budget();

    /** The results the algorithm processes before its run ends. */
    abstract int used();

    /**
     * Ends the run, called while the algorithm thread is held after its {@link #used()}-th
     * result: the run is over once that thread is released.
     */
    abstract void endTheRun() throws Exception;

    @BeforeAll
    void runUntilTheEndWithResultsStillQueuedAndTasksStillInFlight() throws Exception {
        assertNull(SteadyStateMaster.getInstance(),
                "a master already ran in this JVM: run each *IT class in a JVM of its own (mvn verify)");
        System.setProperty("jdisrest.dataPath", dataPath.toString());
        System.setProperty("server.address", "127.0.0.1");  // only this machine can reach the master
        AlgorithmConfig config = AlgorithmConfig.parseText("algorithm = nsgaii\nmaxEvaluations = " + budget()
                + "\npopulationSize = " + POPULATION_SIZE + "\n", problem);
        algorithm = ConfiguredMaster.createAlgorithm("127.0.0.1", 0, problem, config);  // port 0: a free one
        algorithm.setMaxTaskFailures(1);  // a failure report that counted would discard its task at once
        url = JSON.readTree(Files.readString(dataPath.resolve(AbstractMaster.ENDPOINT_FILE_NAME)))
                .get("url").asText();
        MasterFacade.init(budget(), STATUS_FILE_INTERVAL_S);
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (!Files.exists(dataPath.resolve("status.json"))) {
            assertTrue(Instant.now().isBefore(deadline), "the first status.json is written right away");
            Thread.sleep(5);
        }

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        algorithm.observable().register((observable, attributes) -> {
            ready.countDown();  // initProgress(): the initial tasks are queued
            if (algorithm.getEvaluations() == used()) {  // after its last result, before the loop checks
                held.countDown();
                try {
                    release.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        runner = Thread.ofPlatform().daemon().name("algorithm").start(algorithm::run);
        assertTrue(ready.await(TIMEOUT_S, TimeUnit.SECONDS), "the algorithm never became ready");

        // Every task is handed out before the first result: once the algorithm has processed its
        // last one, REST threads may already see the run finished and hand out nothing.
        List<Task> evaluated = new ArrayList<>();
        for (int i = 0; i < used() + BACKLOG; i++) {
            evaluated.add(claim("w" + i));
        }
        lateResult = claim("late-result");
        lateInvalidResult = claim("late-invalid-result");
        lateError = claim("late-error");
        invalidResultAnswer = postResult(new Task(lateResult.id(), "not-its-holder", lateResult.variables()), false);
        holderAfterTheInvalidResult = algorithm.holderOf(lateResult.id());
        for (Task task : evaluated) {
            assertEquals(200, postResult(task, true), "a result that arrives before the end is accepted");
        }
        assertTrue(held.await(TIMEOUT_S, TimeUnit.SECONDS), "the algorithm never processed its last result");
        statusWhileHeld = get("/api/v1/status");
        clusterWhileHeld = get("/api/v1/workers/status");
        endTheRun();
        release.countDown();
        runner.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        assertFalse(runner.isAlive(), "run() did not return within " + TIMEOUT_S + " s");

        assertEquals(used(), algorithm.getEvaluations(), "the algorithm processed exactly the results before the hold");
        assertEquals(BACKLOG, algorithm.getCompletedTaskQueue().size(), "the results the algorithm never took");
        assertTrue(algorithm.needsNoMoreResults(), "the master needs no more results once the run has ended");
    }

    @AfterAll
    void shutDown() {
        release.countDown();  // in case the setup failed while the algorithm thread was held
        if (algorithm != null) {
            algorithm.shutdown();
        }
        http.close();
    }

    // ── While the run goes on ─────────────────────────────────────────────────

    @Test
    @Order(1)
    void invalidResultThatCountsNothingStillGets422WhileTheRunGoesOn() {
        // The worker does not hold the task, so its report is not counted as a failure; the
        // master still expects results, so the worker is told what is wrong with this one.
        assertEquals(422, invalidResultAnswer, "not 404: the master still needs results");
        assertEquals("late-result", holderAfterTheInvalidResult, "the task stays with its holder");
    }

    @Test
    @Order(2)
    void statusCountsTheQueuedResultsWhileTheMasterStillNeedsResults() {
        // Read while the algorithm thread was held after its last result. The loop had not ended,
        // so the master still needed results, even where REST threads already saw the stopping
        // condition met (a run that ends on its budget): the queued results still count.
        assertEquals(used() + BACKLOG, statusWhileHeld.get("evaluations").asInt(),
                "every result accepted, those still queued included");
        assertEquals(used() + BACKLOG, clusterWhileHeld.get("totalEvaluations").asInt(),
                "the same total as GET /api/v1/status");
    }

    // ── What the master reports ───────────────────────────────────────────────

    @Test
    @Order(3)
    void statusReportsOnlyTheResultsTheAlgorithmUsed() throws Exception {
        JsonNode status = get("/api/v1/status");

        assertTrue(status.get("finished").asBoolean(), "the run is over");
        assertEquals(algorithm.getEvaluations(), status.get("evaluations").asInt(),
                "the results still queued are left out");
    }

    @Test
    @Order(4)
    void workersStatusLeavesTheQueuedResultsOutOfTheTotalButStillShowsThem() throws Exception {
        JsonNode cluster = get("/api/v1/workers/status");

        assertEquals(used(), cluster.get("totalEvaluations").asInt(), "the results the algorithm used");
        assertEquals(BACKLOG, cluster.get("queuedResults").asInt(), "the results it never took");
    }

    // ── What arrives late ─────────────────────────────────────────────────────

    @Test
    @Order(5)
    void lateResultIsRefusedAndNotCounted() throws Exception {
        long accepted = MasterFacade.getTotalEvaluations();

        assertEquals(404, postResult(lateResult, true), "the master no longer expects the result");

        assertFalse(algorithm.inFlightTasks.containsKey(lateResult.id()), "the task leaves flight");
        assertEquals(BACKLOG, algorithm.getCompletedTaskQueue().size(), "not queued for the algorithm");
        assertEquals(accepted, MasterFacade.getTotalEvaluations(), "not counted");
    }

    @Test
    @Order(6)
    void lateInvalidResultGetsTheSame404AsAnyLateResult() throws Exception {
        int pending = algorithm.getPendingTaskQueue().size();

        assertEquals(404, postResult(lateInvalidResult, false),
                "not 422: the master no longer expects the result at all");

        assertFalse(algorithm.inFlightTasks.containsKey(lateInvalidResult.id()), "the task leaves flight");
        assertEquals(pending, algorithm.getPendingTaskQueue().size(), "not requeued");
        assertEquals(0, get("/api/v1/status").get("discardedTasks").asInt(), "not discarded");
    }

    @Test
    @Order(7)
    void lateErrorReportIsAcknowledgedButCountsNothing() throws Exception {
        int pending = algorithm.getPendingTaskQueue().size();

        assertEquals(200, post("/api/v1/tasks/" + lateError.id() + "/error",
                JSON.writeValueAsString(Map.of("workerId", lateError.workerId(),
                        "errorMessage", "ZeroDivisionError: division by zero"))).statusCode());

        assertFalse(algorithm.inFlightTasks.containsKey(lateError.id()), "the task leaves flight");
        assertEquals(pending, algorithm.getPendingTaskQueue().size(), "not requeued");
        assertEquals(0, get("/api/v1/status").get("discardedTasks").asInt(), "not discarded");
    }

    @Test
    @Order(8)
    void workerThatAsksForATaskIsSentAway() throws Exception {
        assertEquals(410, send(HttpRequest.newBuilder(uri("/api/v1/tasks/next?workerId=w0")).GET()).statusCode());
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void shutdownWritesAStatusFileWithOnlyTheResultsTheAlgorithmUsed() throws Exception {
        JsonNode initial = JSON.readTree(Files.readString(dataPath.resolve("status.json")));
        assertFalse(initial.get("finished").asBoolean(), "until shutdown() the file holds the first snapshot");
        assertEquals(0, initial.get("evaluations").asInt(), "written before the run started");

        algorithm.shutdown();
        JsonNode file = JSON.readTree(Files.readString(dataPath.resolve("status.json")));

        assertTrue(file.get("finished").asBoolean(), "shutdown() writes a last snapshot, of the finished run");
        assertEquals(used(), file.get("evaluations").asInt(), "the final snapshot leaves the queued results out");
        assertEquals(budget(), file.get("maxEvaluations").asInt());
        assertFalse(Files.exists(dataPath.resolve(AbstractMaster.ENDPOINT_FILE_NAME)), "the discovery file is deleted");
    }

    // ── A worker over HTTP ────────────────────────────────────────────────────

    /** A task as a worker receives it, and the worker it was handed to. */
    record Task(long id, String workerId, List<Double> variables) {
    }

    /** {@code GET /api/v1/tasks/next}, which must hand out a task. */
    private Task claim(String workerId) throws Exception {
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(uri("/api/v1/tasks/next?workerId=" + workerId)).GET());
        assertEquals(200, response.statusCode(), "every task is handed out while the run goes on");
        JsonNode task = JSON.readTree(response.body());
        List<Double> variables = new ArrayList<>();
        task.get("variables").forEach(value -> variables.add(value.asDouble()));
        return new Task(task.get("taskId").asLong(), workerId, variables);
    }

    /**
     * Evaluates ZDT1 as a worker would and posts the result as the task's worker (normally the one
     * that holds it); an invalid result has one objective instead of two.
     *
     * @return the HTTP status of the answer
     */
    private int postResult(Task task, boolean valid) throws Exception {
        DoubleSolution solution = problem.createSolution();
        for (int i = 0; i < task.variables().size(); i++) {
            solution.variables().set(i, task.variables().get(i));
        }
        problem.evaluate(solution);
        List<Double> objectives = Arrays.stream(solution.objectives()).boxed().toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", task.workerId());
        body.put("objectives", valid ? objectives : objectives.subList(0, 1));
        body.put("constraints", List.of());
        body.put("evaluationTimeMs", 1);
        return post("/api/v1/tasks/" + task.id() + "/result", JSON.writeValueAsString(body)).statusCode();
    }

    /** A {@code GET} that must answer {@code 200}, and its JSON body. */
    JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET());
        assertEquals(200, response.statusCode(), "GET " + path);
        return JSON.readTree(response.body());
    }

    /** A {@code POST} of a JSON body. */
    HttpResponse<String> post(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(TIMEOUT_S)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create(url + path);
    }

    /** Parses a JSON body. */
    static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }
}

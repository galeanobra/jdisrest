package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.unex.jdisrest.distributed.RestWorker.ClientTimings;
import es.unex.jdisrest.distributed.rest.dto.TaskPayload;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.Timings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RestWorker} against a scripted fake master (the JDK's {@code com.sun.net.httpserver} on
 * a free loopback port, no Spring): heartbeat statuses, failures reported through
 * {@code POST /error}, the layout check, Lamarckian variables, the statuses that do and do not
 * count towards the dead-master limit, and the timings derived from {@link Timings}.
 */
class RestWorkerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Short delays so a test runs in milliseconds; one heartbeat at start, then none. */
    private static final ClientTimings FAST = new ClientTimings(
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMillis(20),
            Duration.ofMillis(20), Duration.ofMillis(20));

    /** Same, with heartbeats every 20 ms. */
    private static final ClientTimings FAST_HEARTBEATS = new ClientTimings(
            Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(2), Duration.ofMillis(20), Duration.ofMillis(20),
            Duration.ofMillis(20), Duration.ofMillis(20));

    private FakeMaster master;

    @BeforeEach
    void startMaster() throws IOException {
        master = new FakeMaster();
    }

    @AfterEach
    void stopMaster() {
        master.close();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** One recorded request. */
    record Request(String method, String path, Map<String, String> query, Map<String, Object> body) {

        long taskId() {
            return Long.parseLong(path.split("/")[4]);
        }
    }

    /** One scripted answer; a {@code null} body is sent as an empty body. */
    record Answer(int status, String body) {}

    /** A scriptable stand-in for the master's REST API. */
    static final class FakeMaster implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        /** Answers to {@code GET /next}, in order; then {@link #nextDefault}. */
        final Queue<Answer> next = new ConcurrentLinkedQueue<>();
        volatile Supplier<Answer> nextDefault = () -> new Answer(410, null);
        volatile int heartbeatStatus = 200;
        volatile int resultStatus = 200;
        volatile int errorStatus = 200;
        /** When set, {@code GET /next} waits (up to 2 s) for the first heartbeat. */
        volatile boolean tasksAfterFirstHeartbeat = false;
        private final CountDownLatch firstHeartbeat = new CountDownLatch(1);

        FakeMaster() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                byte[] raw = exchange.getRequestBody().readAllBytes();
                @SuppressWarnings("unchecked")
                Map<String, Object> body = raw.length == 0 ? null : JSON.readValue(raw, Map.class);
                requests.add(new Request(exchange.getRequestMethod(), path, query(exchange), body));

                Answer answer;
                if (path.equals("/api/v1/workers/heartbeat")) {
                    firstHeartbeat.countDown();
                    answer = new Answer(heartbeatStatus, null);
                } else if (path.equals("/api/v1/tasks/next")) {
                    if (tasksAfterFirstHeartbeat) firstHeartbeat.await(2, TimeUnit.SECONDS);
                    Answer scripted = next.poll();
                    answer = scripted != null ? scripted : nextDefault.get();
                } else if (path.endsWith("/result")) {
                    answer = new Answer(resultStatus, resultStatus == 200 ? null : "{\"reason\":\"scripted\"}");
                } else if (path.endsWith("/error")) {
                    answer = new Answer(errorStatus, null);
                } else {
                    answer = new Answer(404, null);
                }
                byte[] out = answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static Map<String, String> query(HttpExchange exchange) {
            Map<String, String> out = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw == null) return out;
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
            return out;
        }

        /** Requests whose path ends with {@code suffix} ({@code "/next"}, {@code "/result"}, ...). */
        List<Request> requests(String suffix) {
            return requests.stream().filter(r -> r.path().endsWith(suffix)).toList();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** A {@code GET /next} answer carrying the given payload fields. */
    static Answer task(Map<String, Object> payload) {
        try {
            return new Answer(200, JSON.writeValueAsString(payload));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Answer task(long taskId, List<?> variables) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("variables", variables);
        return task(payload);
    }

    /**
     * The {@code GET /next} body the master sends for {@code solution}, built like
     * {@code TaskController.toPayload} and decoded the way the worker decodes it.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> payloadFor(long taskId, Solution<?> solution) {
        SolutionVariables.VectorLayout layout = SolutionVariables.layoutOf(solution);
        String encoding = layout.wireName();
        List<Integer> bits = layout.bitsPerVariable();
        TaskPayload payload = SolutionVariables.Encoding.INT.wireName().equals(encoding)
                ? new TaskPayload(taskId, SolutionVariables.flatten(solution), layout.segmentSizes(), null, null)
                : new TaskPayload(taskId, SolutionVariables.flatten(solution), layout.segmentSizes(), encoding,
                        layout.wireSegmentEncodings(), bits.isEmpty() ? null : bits);
        try {
            return JSON.readValue(JSON.writeValueAsString(payload), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static IntegerSolution intSolution(int n) {
        return new DefaultIntegerSolution(Collections.nCopies(n, Bounds.create(-1000, 1000)), 1, 0);
    }

    static DoubleSolution doubleSolution(int n) {
        return new DefaultDoubleSolution(Collections.nCopies(n, Bounds.create(-1000.0, 1000.0)), 1, 0);
    }

    static CompositeSolution composite(Solution<?>... components) {
        return new CompositeSolution(List.of(components));
    }

    /** A binary solution with variables of the given lengths, all bits 0. */
    static BinarySolution binarySolution(Integer... lengths) {
        BinarySolution s = new DefaultBinarySolution(List.of(lengths), 1, 0);
        for (int i = 0; i < lengths.length; i++) s.variables().set(i, new BinarySet(lengths[i]));
        return s;
    }

    /** The number of bits set in a binary solution, as its objective. */
    static void ones(BinarySolution s) {
        s.objectives()[0] = s.variables().stream().mapToInt(BinarySet::cardinality).sum();
    }

    /** f(x) = Σ xᵢ² on whatever numeric variables the solution has. */
    static void sphere(Solution<?> s) {
        s.objectives()[0] = SolutionVariables.flatten(s).stream().mapToDouble(v -> v.doubleValue() * v.doubleValue()).sum();
    }

    /** A problem built from a solution factory and an evaluation; counts its evaluations. */
    static final class TestProblem<S extends Solution<?>> implements Problem<S> {
        private final transient Supplier<S> factory;
        private final transient Consumer<S> evaluation;
        final AtomicInteger evaluations = new AtomicInteger();

        TestProblem(Supplier<S> factory, Consumer<S> evaluation) {
            this.factory = factory;
            this.evaluation = evaluation;
        }

        @Override public int numberOfVariables() { return SolutionVariables.size(factory.get()); }
        @Override public int numberOfObjectives() { return 1; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "TestProblem"; }
        @Override public S createSolution() { return factory.get(); }

        @Override
        public S evaluate(S solution) {
            evaluations.incrementAndGet();
            evaluation.accept(solution);
            return solution;
        }
    }

    static TestProblem<IntegerSolution> intSphere(int n) {
        return new TestProblem<>(() -> intSolution(n), RestWorkerTest::sphere);
    }

    /** Runs the worker on its own thread and fails if it does not stop by itself within 10 s. */
    static void runToCompletion(RestWorker<?> worker) throws InterruptedException {
        Thread thread = new Thread(worker::run, "worker-under-test");
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(10));
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(2));
            fail("the worker must stop on its own");
        }
    }

    <S extends Solution<?>> RestWorker<S> worker(Problem<S> problem) {
        return new RestWorker<>(master.url(), problem, "worker-test", FAST);
    }

    // ── Worker id and construction ────────────────────────────────────────────

    @Test
    void customWorkerIdIsSentInEveryRequest() throws Exception {
        master.tasksAfterFirstHeartbeat = true;
        master.next.add(task(1, List.of(1, 2, 3)));
        RestWorker<IntegerSolution> worker = new RestWorker<>(master.url(), intSphere(3), "slurm-812-3", FAST);

        runToCompletion(worker);

        assertEquals("slurm-812-3", worker.getWorkerId());
        assertEquals("slurm-812-3", master.requests("/heartbeat").get(0).query().get("workerId"),
                "the heartbeat must carry the given id");
        assertTrue(master.requests("/next").stream().allMatch(r -> "slurm-812-3".equals(r.query().get("workerId"))),
                "every claim must carry the given id");
        assertEquals("slurm-812-3", master.requests("/result").get(0).body().get("workerId"),
                "the result must carry the given id");
    }

    @Test
    void nullOrBlankWorkerIdFallsBackToAGeneratedOne() {
        for (String id : new String[] {null, "", "   "}) {
            try (RestWorker<IntegerSolution> worker = new RestWorker<>(master.url(), intSphere(1), id)) {
                assertTrue(worker.getWorkerId().matches("worker-java-[0-9a-f]{8}"),
                        "unexpected generated id " + worker.getWorkerId());
            }
        }
        try (RestWorker<IntegerSolution> worker = new RestWorker<>(master.url(), intSphere(1))) {
            assertTrue(worker.getWorkerId().startsWith("worker-java-"), "the two-argument constructor generates the id");
        }
    }

    @Test
    void missingMasterUrlOrProblemIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RestWorker<>(null, intSphere(1)));
        assertThrows(IllegalArgumentException.class, () -> new RestWorker<>("  ", intSphere(1)));
        assertThrows(IllegalArgumentException.class, () -> new RestWorker<IntegerSolution>(master.url(), null));
    }

    @Test
    void workerRunsOnlyOnce() throws Exception {
        RestWorker<IntegerSolution> worker = worker(intSphere(1));
        runToCompletion(worker);

        assertThrows(IllegalStateException.class, worker::run, "a closed worker must not loop on its closed client");
    }

    @Test
    void workerRunningInAnotherThreadCannotBeStartedAgain() throws Exception {
        master.nextDefault = () -> new Answer(204, null);
        RestWorker<IntegerSolution> worker = worker(intSphere(1));
        Thread thread = new Thread(worker::run, "worker-under-test");
        thread.start();
        try {
            awaitClaim();

            // Preemptive timeout: without the guard the second call would loop on the 204s.
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalStateException.class, worker::run,
                    "a second loop would share the worker id and the heartbeat thread of the first"));
        } finally {
            worker.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(thread.isAlive(), "close() must stop the running loop");
    }

    @Test
    void closeFromAnotherThreadStopsARunningWorker() throws Exception {
        master.nextDefault = () -> new Answer(204, null);
        RestWorker<IntegerSolution> worker = worker(intSphere(1));
        Thread thread = new Thread(worker::run, "worker-under-test");
        thread.start();
        awaitClaim();

        worker.close();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(thread.isAlive(), "the loop must stop at its next step once the worker is closed");
        int claims = master.requests("/next").size();
        Thread.sleep(100);
        assertEquals(claims, master.requests("/next").size(), "a closed worker must not claim tasks");
    }

    /** Waits (up to 5 s) until the worker under test has claimed at least once. */
    private void awaitClaim() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (master.requests("/next").isEmpty()) {
            if (System.nanoTime() > deadline) fail("the worker never claimed a task");
            Thread.sleep(5);
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    @Test
    void heartbeatAnsweredWithAnErrorStatusCountsAsAFailure() throws Exception {
        master.heartbeatStatus = 500;
        master.nextDefault = () -> new Answer(204, null);
        RestWorker<IntegerSolution> worker = new RestWorker<>(master.url(), intSphere(1), "worker-test", FAST_HEARTBEATS);

        runToCompletion(worker);

        assertEquals(3, master.requests("/heartbeat").size(),
                "three heartbeats answered 500 in a row must make the worker give the master up");
    }

    @Test
    void successfulHeartbeatsDoNotStopTheWorker() throws Exception {
        master.nextDefault = () -> new Answer(204, null);
        RestWorker<IntegerSolution> worker = new RestWorker<>(master.url(), intSphere(1), "worker-test", FAST_HEARTBEATS);
        Thread thread = new Thread(worker::run, "worker-under-test");
        thread.start();

        thread.join(500);
        boolean alive = thread.isAlive();
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(alive, "a worker whose heartbeats succeed must keep running");
        assertTrue(master.requests("/heartbeat").size() > 3, "heartbeats must keep coming");
        assertFalse(thread.isAlive(), "an interrupt must stop the worker");
    }

    // ── Failures reported through POST /error ─────────────────────────────────

    @Test
    void solutionThatCannotBeCreatedIsReportedThroughError() throws Exception {
        master.next.add(task(7, List.of(1, 2)));
        TestProblem<IntegerSolution> problem = new TestProblem<>(() -> {
            throw new IllegalStateException("simulator not found");
        }, RestWorkerTest::sphere);

        runToCompletion(worker(problem));

        List<Request> errors = master.requests("/error");
        assertEquals(1, errors.size(), "the claimed task must be handed back, not stranded");
        assertEquals(7, errors.get(0).taskId());
        assertEquals("worker-test", errors.get(0).body().get("workerId"));
        assertTrue(errors.get(0).body().get("errorMessage").toString().contains("simulator not found"),
                "the master must see why: " + errors.get(0).body());
        assertTrue(master.requests("/result").isEmpty());
        assertEquals(2, master.requests("/next").size(), "the worker must go on and stop at the 410");
    }

    @Test
    void unreadableVariablesAreReportedThroughError() throws Exception {
        master.next.add(task(Map.of("taskId", 7, "variables", "oops")));
        master.next.add(task(8, List.of(1, "NaN", 3)));
        TestProblem<IntegerSolution> problem = intSphere(3);

        runToCompletion(worker(problem));

        List<Request> errors = master.requests("/error");
        assertEquals(List.of(7L, 8L), errors.stream().map(Request::taskId).toList());
        assertTrue(errors.get(0).body().get("errorMessage").toString().contains("'variables'"), errors.get(0).body().toString());
        assertTrue(errors.get(1).body().get("errorMessage").toString().contains("variables[1] is not a number"),
                errors.get(1).body().toString());
        assertEquals(0, problem.evaluations.get(), "nothing must be evaluated");
        assertTrue(master.requests("/result").isEmpty());
    }

    @Test
    void evaluationExceptionIsReportedAndTheWorkerMovesOn() throws Exception {
        master.next.add(task(1, List.of(1)));
        master.next.add(task(2, List.of(2)));
        TestProblem<IntegerSolution> problem = new TestProblem<>(() -> intSolution(1), s -> {
            if (s.variables().get(0) == 1) throw new ArithmeticException("division by zero");
            sphere(s);
        });

        runToCompletion(worker(problem));

        assertEquals(List.of(1L), master.requests("/error").stream().map(Request::taskId).toList());
        assertTrue(master.requests("/error").get(0).body().get("errorMessage").toString()
                .contains("ArithmeticException"), "the message must name the exception type");
        assertEquals(List.of(2L), master.requests("/result").stream().map(Request::taskId).toList());
    }

    @Test
    void nonFiniteObjectiveIsReportedAsAnEvaluationError() throws Exception {
        master.next.add(task(4, List.of(1, 2)));
        TestProblem<IntegerSolution> problem = new TestProblem<>(() -> intSolution(2), s -> s.objectives()[0] = Double.NaN);

        runToCompletion(worker(problem));

        assertTrue(master.requests("/result").isEmpty(), "a NaN must not travel as the JSON string \"NaN\"");
        assertEquals(1, master.requests("/error").size());
        assertTrue(master.requests("/error").get(0).body().get("errorMessage").toString()
                .contains("objectives[0] is not finite"), master.requests("/error").get(0).body().toString());
    }

    // ── Layout check ──────────────────────────────────────────────────────────

    @Test
    void layoutsTheMasterBuildsFromTheSameSolutionAgree() {
        List<Supplier<Solution<?>>> shapes = List.of(
                () -> intSolution(3),
                () -> doubleSolution(3),
                () -> composite(intSolution(2), intSolution(3)),
                () -> composite(doubleSolution(2), doubleSolution(1)),
                () -> composite(intSolution(2), doubleSolution(1)),
                () -> binarySolution(3, 5),
                () -> composite(intSolution(2), binarySolution(3, 5)),
                () -> composite(binarySolution(3), binarySolution(5)),
                () -> composite(intSolution(2), doubleSolution(1), binarySolution(3, 5)));
        for (Supplier<Solution<?>> shape : shapes) {
            Solution<?> solution = shape.get();
            assertNull(RestWorker.layoutMismatch(payloadFor(1, solution), shape.get()),
                    "same shape must agree: " + payloadFor(1, solution));
        }
    }

    @Test
    void differentSegmentBoundariesWithTheSameLengthAreAMismatch() {
        String reason = RestWorker.layoutMismatch(payloadFor(1, composite(intSolution(2), intSolution(3))),
                composite(intSolution(3), intSolution(2)));

        assertNotNull(reason, "same length, other boundaries: values would land in the wrong segment");
        assertTrue(reason.contains("segmentSizes [2, 3]") && reason.contains("[3, 2]"), reason);
    }

    @Test
    void compositeAndFlatSolutionsDoNotMatch() {
        assertNotNull(RestWorker.layoutMismatch(payloadFor(1, composite(intSolution(2), intSolution(3))), intSolution(5)));
        assertNotNull(RestWorker.layoutMismatch(payloadFor(1, intSolution(5)), composite(intSolution(2), intSolution(3))));
    }

    @Test
    void differentEncodingsDoNotMatch() {
        String reason = RestWorker.layoutMismatch(payloadFor(1, doubleSolution(3)), intSolution(3));
        assertNotNull(reason, "real variables must not be written into an integer solution");
        assertTrue(reason.contains("encoding \"double\""), reason);

        assertNotNull(RestWorker.layoutMismatch(payloadFor(1, intSolution(3)), doubleSolution(3)),
                "integer variables must not be written into a real solution");
    }

    @Test
    void segmentEncodingsInAnotherOrderDoNotMatch() {
        String reason = RestWorker.layoutMismatch(payloadFor(1, composite(intSolution(2), doubleSolution(2))),
                composite(doubleSolution(2), intSolution(2)));

        assertNotNull(reason, "same sizes and same overall encoding, but the segments are swapped");
        assertTrue(reason.contains("segmentEncodings"), reason);
    }

    @Test
    void malformedLayoutFieldsAreReported() {
        Map<String, Object> payload = new HashMap<>(payloadFor(1, intSolution(2)));
        payload.put("segmentSizes", "two");
        assertTrue(RestWorker.layoutMismatch(payload, intSolution(2)).contains("segmentSizes is not a list"));

        payload = new HashMap<>(payloadFor(1, intSolution(2)));
        payload.put("encoding", 3);
        assertTrue(RestWorker.layoutMismatch(payload, intSolution(2)).contains("encoding is not a string"));

        for (Object bits : List.of("3,5", List.of(3, 0), List.of(3.0, 5.0), List.of(-3, 5))) {
            payload = new HashMap<>(payloadFor(1, binarySolution(3, 5)));
            payload.put("bitsPerVariable", bits);
            assertEquals("bitsPerVariable is not a list of positive integers: " + (bits instanceof String t ? "\"" + t + "\"" : bits),
                    RestWorker.layoutMismatch(payload, binarySolution(3, 5)));
        }
    }

    @Test
    void binaryVariablesSplitIntoOtherLengthsAreAMismatch() {
        assertEquals("bitsPerVariable [3, 5] from the master, but the local solution has [5, 3]",
                RestWorker.layoutMismatch(payloadFor(1, binarySolution(3, 5)), binarySolution(5, 3)),
                "same eight bits, other variables: bits would land in the wrong variable");
        assertEquals("bitsPerVariable [3, 5] from the master, but the local solution has [3, 4, 1]",
                RestWorker.layoutMismatch(payloadFor(1, composite(intSolution(1), binarySolution(3, 5))),
                        composite(intSolution(1), binarySolution(3, 4, 1))));
    }

    @Test
    void binaryTaskWithoutTheLengthsOfItsVariablesDoesNotMatchABinaryProblem() {
        Map<String, Object> payload = new HashMap<>(payloadFor(1, binarySolution(3, 5)));
        payload.remove("bitsPerVariable");

        assertEquals("bitsPerVariable none (no binary variable) from the master, but the local solution has [3, 5]",
                RestWorker.layoutMismatch(payload, binarySolution(3, 5)), "the worker cannot tell where a variable ends");
    }

    @Test
    void binaryTaskDoesNotMatchAnIntegerProblemOfTheSameLength() {
        // The case for always sending the encoding of bits: without it, a worker of an integer
        // problem of eight variables would evaluate the bits as integers.
        assertEquals("encoding \"binary\" from the master, but the local solution is \"int\"",
                RestWorker.layoutMismatch(payloadFor(1, binarySolution(3, 5)), intSolution(8)));
        assertEquals("encoding \"int\" from the master, but the local solution is \"binary\"",
                RestWorker.layoutMismatch(payloadFor(1, intSolution(8)), binarySolution(3, 5)));
    }

    @Test
    void layoutMismatchIsReportedThroughErrorWithoutEvaluating() throws Exception {
        master.next.add(task(payloadFor(9, composite(intSolution(2), intSolution(3)))));
        TestProblem<CompositeSolution> problem = new TestProblem<>(
                () -> composite(intSolution(3), intSolution(2)), RestWorkerTest::sphere);

        runToCompletion(worker(problem));

        assertEquals(0, problem.evaluations.get(), "a misaligned vector must not be evaluated");
        assertEquals(1, master.requests("/error").size());
        assertEquals(9, master.requests("/error").get(0).taskId());
        assertTrue(master.requests("/error").get(0).body().get("errorMessage").toString().contains("does not match"));
        assertTrue(master.requests("/result").isEmpty());
    }

    @Test
    void mixedCompositeWithTheSameLayoutIsEvaluated() throws Exception {
        IntegerSolution ints = intSolution(2);
        ints.variables().set(0, 3);
        ints.variables().set(1, 0);
        DoubleSolution doubles = doubleSolution(2);
        doubles.variables().set(0, 0.0);
        doubles.variables().set(1, 0.5);
        master.next.add(task(payloadFor(5, composite(ints, doubles))));
        TestProblem<CompositeSolution> problem = new TestProblem<>(
                () -> composite(intSolution(2), doubleSolution(2)), RestWorkerTest::sphere);

        runToCompletion(worker(problem));

        assertTrue(master.requests("/error").isEmpty(), "a matching layout must not be reported");
        Map<String, Object> result = master.requests("/result").get(0).body();
        assertEquals(List.of(9.25), result.get("objectives"));
    }

    @Test
    void binaryTaskIsEvaluatedOnSetsOfTheProblemsLengths() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(payloadFor(4, binarySolution(3, 5)));
        payload.put("variables", List.of(1, 0, 1, 0, 0, 1, 1, 0));
        master.next.add(task(payload));
        List<String> received = new CopyOnWriteArrayList<>();
        TestProblem<BinarySolution> problem = new TestProblem<>(() -> binarySolution(3, 5), s -> {
            received.add(s.variables() + " of " + s.variables().stream().map(BinarySet::getBinarySetLength).toList());
            ones(s);
        });

        runToCompletion(worker(problem));

        assertTrue(master.requests("/error").isEmpty(), "a matching layout must not be reported");
        assertEquals(List.of("[101, 00110] of [3, 5]"), received, "bit 0 first, in variables of the problem's lengths");
        Map<String, Object> result = master.requests("/result").get(0).body();
        assertEquals(List.of(4.0), result.get("objectives"));
        assertFalse(result.containsKey("variables"), "bits that did not change are not sent back");
    }

    // ── Lamarckian variables ──────────────────────────────────────────────────

    @Test
    void repairedVariablesAreSentBackWithTheResult() throws Exception {
        master.next.add(task(3, List.of(-5, 2, 3)));
        TestProblem<IntegerSolution> problem = new TestProblem<>(() -> intSolution(3), s -> {
            for (int i = 0; i < s.variables().size(); i++) s.variables().set(i, Math.max(0, s.variables().get(i)));
            sphere(s);
        });

        runToCompletion(worker(problem));

        Map<String, Object> result = master.requests("/result").get(0).body();
        assertEquals(List.of(0, 2, 3), result.get("variables"), "the master must get the repaired vector");
        assertEquals(List.of(13.0), result.get("objectives"));
    }

    @Test
    void unchangedVariablesAreNotSent() throws Exception {
        master.next.add(task(3, List.of(1, 2, 3)));

        runToCompletion(worker(intSphere(3)));

        Map<String, Object> result = master.requests("/result").get(0).body();
        assertEquals(List.of("workerId", "objectives", "constraints", "evaluationTimeMs"), new ArrayList<>(result.keySet()),
                "without a repair the body must be the one earlier versions sent");
        assertEquals(List.of(14.0), result.get("objectives"));
    }

    @Test
    void repairedBitsAreSentBackAsZerosAndOnes() throws Exception {
        IntegerSolution seven = intSolution(1);
        seven.variables().set(0, 7);
        master.next.add(task(payloadFor(6, composite(seven, binarySolution(3, 5)))));
        TestProblem<CompositeSolution> problem = new TestProblem<>(() -> composite(intSolution(1), binarySolution(3, 5)), s -> {
            ((BinarySolution) s.variables().get(1)).variables().get(1).set(4);  // a repair in place
            s.objectives()[0] = 1.0;
        });

        runToCompletion(worker(problem));

        Map<String, Object> result = master.requests("/result").get(0).body();
        assertEquals(List.of(7, 0, 0, 0, 0, 0, 0, 0, 1), result.get("variables"),
                "the whole vector, the repaired bit as the integer 1, not true");
    }

    @Test
    void evaluationThatSplitsTheBitsIntoOtherLengthsIsReportedInsteadOfItsResult() throws Exception {
        master.next.add(task(payloadFor(8, binarySolution(3, 5))));
        TestProblem<BinarySolution> problem = new TestProblem<>(() -> binarySolution(3, 5), s -> {
            s.variables().set(0, new BinarySet(5));
            s.variables().set(1, new BinarySet(3));
            ones(s);
        });

        runToCompletion(worker(problem));

        assertTrue(master.requests("/result").isEmpty(),
                "the master would write the eight bits into its variables of 3 and 5 bits");
        assertEquals("the evaluation changed the layout of the solution: bitsPerVariable [3, 5] before it, [5, 3] after",
                master.requests("/error").get(0).body().get("errorMessage"));
    }

    @Test
    void layoutChangeNamesWhatTheEvaluationChanged() {
        SolutionVariables.VectorLayout three = SolutionVariables.layoutOf(intSolution(3));

        assertNull(RestWorker.layoutChange(three, intSolution(3)));
        assertEquals("the evaluation changed the layout of the solution: 3 values before it, 4 after",
                RestWorker.layoutChange(three, intSolution(4)));
        assertEquals("the evaluation changed the layout of the solution: segmentSizes [2, 1] before it, [1, 2] after",
                RestWorker.layoutChange(SolutionVariables.layoutOf(composite(intSolution(2), binarySolution(1))),
                        composite(intSolution(1), binarySolution(2))));
        assertEquals("the evaluation changed the layout of the solution: bitsPerVariable [2, 1] before it, [1, 2] after",
                RestWorker.layoutChange(SolutionVariables.layoutOf(composite(intSolution(2), binarySolution(2, 1))),
                        composite(intSolution(2), binarySolution(1, 2))));

        BinarySolution broken = binarySolution(3, 5);
        broken.variables().set(1, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RestWorker.layoutChange(SolutionVariables.layoutOf(binarySolution(3, 5)), broken));
        assertEquals("variables[3] is null", e.getMessage());
    }

    @Test
    void resultBodyRejectsNonFiniteChangedVariables() {
        DoubleSolution solution = doubleSolution(2);
        List<Number> sent = SolutionVariables.flatten(solution);
        solution.variables().set(1, Double.POSITIVE_INFINITY);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RestWorker.resultBody("w", solution, sent, 1));
        assertTrue(e.getMessage().contains("variables[1]"), e.getMessage());
    }

    // ── What counts towards the dead-master limit ─────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 413, 415, 422})
    void resultAnswersTheProtocolDefinesDoNotCountAsMasterErrors(int status) throws Exception {
        for (int id = 1; id <= 6; id++) master.next.add(task(id, List.of(id)));
        master.resultStatus = status;

        runToCompletion(worker(intSphere(1)));

        assertEquals(6, master.requests("/result").size(),
                "a " + status + " is an answer about one task, not a lost master: all six tasks must be tried");
        assertEquals(7, master.requests("/next").size(), "the worker must stop at the 410, not at the error limit");
    }

    @Test
    void serverErrorsOnEveryResultStopTheWorker() throws Exception {
        AtomicLong ids = new AtomicLong();
        master.nextDefault = () -> task(ids.incrementAndGet(), List.of(1));
        master.resultStatus = 500;

        runToCompletion(worker(intSphere(1)));

        assertEquals(5, master.requests("/result").size(),
                "tasks handed out must not reset the count while every result fails");
    }

    @Test
    void serverErrorsOnEveryErrorReportStopTheWorker() throws Exception {
        AtomicLong ids = new AtomicLong();
        master.nextDefault = () -> task(ids.incrementAndGet(), List.of(1));
        master.errorStatus = 500;
        TestProblem<IntegerSolution> problem = new TestProblem<>(() -> intSolution(1), s -> {
            throw new IllegalStateException("always fails");
        });

        runToCompletion(worker(problem));

        assertEquals(5, master.requests("/error").size());
    }

    @Test
    void taskWithoutAUsableIdCountsTowardsTheErrorLimit() throws Exception {
        master.nextDefault = () -> task(Map.of("variables", List.of(1)));

        runToCompletion(worker(intSphere(1)));

        assertEquals(5, master.requests("/next").size());
        assertTrue(master.requests("/error").isEmpty(), "without an id there is nothing to report");
    }

    @Test
    void emptyLongPollsDoNotCountTowardsTheErrorLimit() throws Exception {
        for (int i = 0; i < 4; i++) master.next.add(new Answer(500, null));
        for (int i = 0; i < 2; i++) master.next.add(new Answer(204, null));
        for (int i = 0; i < 4; i++) master.next.add(new Answer(500, null));

        runToCompletion(worker(intSphere(1)));

        assertEquals(11, master.requests("/next").size(), "a 204 must reset the count, so the worker reaches the 410");
    }

    // ── Timings ───────────────────────────────────────────────────────────────

    @Test
    void defaultTimeoutsAreDerivedFromTimings() {
        ClientTimings t = ClientTimings.DEFAULTS;

        assertEquals(Duration.ofSeconds(Timings.TASK_LONGPOLL_S + RestWorker.LONGPOLL_MARGIN_S), t.nextTaskTimeout());
        assertTrue(t.nextTaskTimeout().getSeconds() > Timings.TASK_LONGPOLL_S,
                "an empty long-poll must be answered before the client times out");
        assertEquals(Duration.ofSeconds(Timings.HEARTBEAT_INTERVAL_S), t.heartbeatInterval());
        assertTrue(t.heartbeatTimeout().compareTo(t.heartbeatInterval()) < 0, "a slow heartbeat must not delay the next");
        assertTrue(t.heartbeatTimeout().plus(t.heartbeatRetryDelay()).multipliedBy(3)
                        .compareTo(Duration.ofSeconds(Timings.WORKER_TIMEOUT_S)) < 0,
                "three failed heartbeats must be noticed before the master evicts the worker");
    }
}

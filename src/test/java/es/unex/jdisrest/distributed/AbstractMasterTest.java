package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link AbstractMaster} that need no REST server: the default failure limit, the
 * decision vector written to the log when a task is discarded, the server's default properties,
 * the advertised host and endpoint file, the log of worker registrations, and the task pipeline
 * itself (dispatch, results, failures, the watchdog, readiness and shutdown), exercised on a
 * master built without its server (constructing a real one starts Spring).
 */
class AbstractMasterTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static IntegerSolution intSolution(int... values) {
        List<Bounds<Integer>> bounds = Collections.nCopies(values.length, Bounds.create(-1000, 1000));
        IntegerSolution s = new DefaultIntegerSolution(bounds, 1, 0);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static DoubleSolution doubleSolution(double... values) {
        List<Bounds<Double>> bounds = Collections.nCopies(values.length, Bounds.create(-1000.0, 1000.0));
        DoubleSolution s = new DefaultDoubleSolution(bounds, 1, 0);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    /** A solution type {@code SolutionVariables} does not know: variables are strings. */
    static Solution<String> stringSolution() {
        return new Solution<>() {
            private final List<String> vars = new ArrayList<>(List.of("a", "b"));
            @Override public List<String> variables() { return vars; }
            @Override public double[] objectives() { return new double[1]; }
            @Override public double[] constraints() { return new double[0]; }
            @Override public Map<Object, Object> attributes() { return new HashMap<>(); }
            @Override public Solution<String> copy() { return this; }
        };
    }

    static ParallelTask<IntegerSolution> task(long id) {
        return ParallelTask.create(id, intSolution((int) id));
    }

    /**
     * A master without REST server or discovery file, whose readiness, stopping condition and end
     * of run the test sets, and which records the tasks it hands to {@code onTaskDiscarded}.
     */
    static final class TestMaster extends AbstractMaster<ParallelTask<IntegerSolution>, Void> {
        volatile boolean ready = true;
        volatile BooleanSupplier running = () -> true;
        volatile boolean ended = false;
        final List<Long> discardHooks = new CopyOnWriteArrayList<>();

        @Override public boolean isReady() { return ready; }
        @Override public boolean stoppingConditionIsNotMet() { return running.getAsBoolean(); }
        @Override boolean runEnded() { return ended; }

        @Override
        protected void onTaskDiscarded(ParallelTask<IntegerSolution> task) {
            discardHooks.add(task.getIdentifier());
        }

        long pointerOf(String workerId) {
            WorkerEntry entry = workerRegistry.get(workerId);
            return entry == null ? Long.MIN_VALUE : entry.currentTaskId;
        }
    }

    /** Runs an action and returns what it wrote to {@code System.err}, where {@code Log} writes. */
    static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ── Failure limit ─────────────────────────────────────────────────────────

    @Test
    void defaultFailureLimitIsThreeEvaluations() {
        assertEquals(3, AbstractMaster.DEFAULT_MAX_TASK_FAILURES,
            "the documented default: a task is discarded after its third failed evaluation");
    }

    // ── Discard log ───────────────────────────────────────────────────────────

    @Test
    void shortDecisionVectorIsLoggedInFull() {
        assertEquals("[3, -17, 55]", AbstractMaster.variablesOf(intSolution(3, -17, 55)));
        assertEquals("[0.5, -2.25]", AbstractMaster.variablesOf(doubleSolution(0.5, -2.25)));
    }

    @Test
    void binaryDecisionVectorIsLoggedAsTheBitsTheWorkerReceived() {
        CompositeSolution composite = new CompositeSolution(List.of(intSolution(7),
            SteadyStateEvolutionaryAlgorithmTest.binary("101", "01")));

        assertEquals("[1, 0, 1, 0, 1]", AbstractMaster.variablesOf(SteadyStateEvolutionaryAlgorithmTest.binary("101", "01")));
        assertEquals("[7, 1, 0, 1, 0, 1]", AbstractMaster.variablesOf(composite));
    }

    @Test
    void compositeDecisionVectorIsLoggedFlat() {
        CompositeSolution composite = new CompositeSolution(List.of(intSolution(1, 2), doubleSolution(0.5)));

        assertEquals("[1, 2, 0.5]", AbstractMaster.variablesOf(composite),
            "a composite is logged in the same flat layout the worker received");
    }

    @Test
    void longDecisionVectorIsAbbreviatedAfterTheLoggedMaximum() {
        int size = AbstractMaster.MAX_LOGGED_VARIABLES + 70;
        String logged = AbstractMaster.variablesOf(intSolution(IntStream.range(0, size).toArray()));

        String expectedHead = IntStream.range(0, AbstractMaster.MAX_LOGGED_VARIABLES)
            .mapToObj(Integer::toString).reduce((a, b) -> a + ", " + b).orElseThrow();
        assertEquals("[" + expectedHead + ", ... 70 more]", logged,
            "only the first MAX_LOGGED_VARIABLES values are printed, then how many were left out");
    }

    @Test
    void unsupportedSolutionIsLoggedWithItsRawVariables() {
        assertEquals("[a, b]", AbstractMaster.variablesOf(stringSolution()));
    }

    @Test
    void contentsThatAreNotASolutionAreLoggedAsIs() {
        assertEquals("plain text", AbstractMaster.variablesOf("plain text"));
        assertEquals("null", AbstractMaster.variablesOf(null));
    }

    @Test
    void abbreviationKeepsListsUpToTheLimitIntact() {
        assertEquals("[]", AbstractMaster.abbreviate(List.of(), 3));
        assertEquals("[1, 2, 3]", AbstractMaster.abbreviate(List.of(1, 2, 3), 3));
        assertEquals("[1, 2, 3, ... 1 more]", AbstractMaster.abbreviate(List.of(1, 2, 3, 4), 3));
    }

    // ── REST server settings ──────────────────────────────────────────────────

    @Test
    void serverStartsWithOverridableDefaultsAndAMuchLargerBodyLimit() {
        Map<String, Object> defaults = AbstractMaster.defaultServerProperties();

        assertEquals(Map.of(
                "server.address", "0.0.0.0",
                "spring.main.banner-mode", "off",
                "spring.http.codecs.max-in-memory-size", "16MB",
                "server.shutdown", "immediate"), defaults,
            "the fixed settings are Spring default properties, so -D, environment variables and "
                + "application.properties override them; a result of tens of thousands of variables fits");
        assertFalse(defaults.containsKey("server.port"), "the port always comes from the constructor");
    }

    @Test
    void wildcardAndBlankHostsAreRecognisedWithoutResolvingNames() {
        for (String host : new String[] {null, "", "  ", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", " 0.0.0.0 "}) {
            assertTrue(AbstractMaster.isWildcardHost(host), "'" + host + "' cannot be reached by a worker");
        }
        for (String host : new String[] {"10.0.0.1", "node12", "node12.cluster.local", "localhost", "::1", "[fe80::1]"}) {
            assertFalse(AbstractMaster.isWildcardHost(host), "'" + host + "' is a real address and is kept");
        }
    }

    @Test
    void wildcardHostIsAdvertisedAsARoutableAddressWithAWarning() {
        String[] advertised = new String[1];
        String log = stderrOf(() -> advertised[0] = AbstractMaster.advertisedHost("0.0.0.0"));

        assertFalse(AbstractMaster.isWildcardHost(advertised[0]),
            "the discovery file must never send workers to 0.0.0.0, got " + advertised[0]);
        assertTrue(log.contains("WARN") && log.contains("cannot be reached by workers"),
            "the replacement is announced: " + log);
        assertEquals("node12", AbstractMaster.advertisedHost("node12"), "a real host is advertised as given");
    }

    @Test
    void withoutADefaultRouteALoopbackHostNameGivesWayToAnInterfaceAddress() {
        InetAddress debianHostName = InetAddress.ofLiteral("127.0.1.1");
        List<InetAddress> interfaces = List.of(InetAddress.ofLiteral("fe80::1"), InetAddress.ofLiteral("2001:db8::5"),
                InetAddress.ofLiteral("203.0.113.5"), InetAddress.ofLiteral("10.1.2.3"), InetAddress.ofLiteral("10.9.9.9"));

        assertEquals("10.1.2.3", AbstractMaster.reachableAddress(debianHostName, interfaces),
                "the first IPv4 site-local address, not the loopback the host name resolves to");
        assertEquals("203.0.113.5", AbstractMaster.reachableAddress(null, interfaces.subList(0, 3)),
                "another IPv4 address before an IPv6 one");
        assertEquals("2001:db8:0:0:0:0:0:5", AbstractMaster.reachableAddress(debianHostName, interfaces.subList(0, 2)),
                "never a link-local address");
        assertEquals("192.168.1.7", AbstractMaster.reachableAddress(InetAddress.ofLiteral("192.168.1.7"), interfaces),
                "a host name with a reachable address is used as before");
        assertEquals("127.0.1.1", AbstractMaster.reachableAddress(debianHostName, List.of(InetAddress.ofLiteral("fe80::1"))),
                "a loopback address only when there is nothing else");
        assertEquals("127.0.0.1", AbstractMaster.reachableAddress(null, List.of()));
    }

    @Test
    void endpointFileIsJsonWithTheLegacyLayout() throws Exception {
        assertEquals("{\"host\":\"10.0.0.1\",\"port\":8080,\"url\":\"http://10.0.0.1:8080\"}",
            AbstractMaster.endpointJson("10.0.0.1", 8080), "workers of earlier versions read the same fields");

        String odd = "we\"ird\\host";
        Map<?, ?> parsed = JsonMapper.builder().build().readValue(AbstractMaster.endpointJson(odd, 1), Map.class);
        assertEquals(odd, parsed.get("host"), "any host is escaped, so the file always parses");
    }

    @Test
    void urlOfAnIpv6HostPutsTheAddressInBrackets() {
        assertEquals("http://[fe80::1]:8080", AbstractMaster.urlOf("fe80::1", 8080));
        assertEquals("http://[fe80::1]:8080", AbstractMaster.urlOf("[fe80::1]", 8080));
        assertEquals("http://node12:8080", AbstractMaster.urlOf("node12", 8080));
    }

    // ── Readiness ─────────────────────────────────────────────────────────────

    @Test
    void masterThatIsNotReadyIsNotFinishedAndNeverConsultsItsStoppingCondition() {
        TestMaster master = new TestMaster();
        AtomicInteger consulted = new AtomicInteger();
        master.ready = false;
        master.running = () -> {
            consulted.incrementAndGet();
            throw new IllegalStateException("cannot be evaluated before the run starts");
        };

        assertFalse(master.isFinished(), "a run that has not started is not finished");
        assertEquals(0, consulted.get(), "the stopping condition is not evaluated before the master is ready");

        master.requestStop();
        assertTrue(master.isFinished(), "a stop counts even before the master is ready");
    }

    @Test
    void readyMasterConsultsItsStoppingCondition() {
        TestMaster master = new TestMaster();
        master.running = () -> false;

        assertTrue(master.isFinished(), "once ready, a met stopping condition finishes the run");
    }

    // ── Worker registration ───────────────────────────────────────────────────

    @Test
    void firstHeartbeatOfEachWorkerLogsTheTotalInTheLayoutToolsParse() {
        TestMaster master = new TestMaster();

        String log = stderrOf(() -> {
            master.registerHeartbeat("w1", "10.0.0.5");
            master.registerHeartbeat("w2", "10.0.0.6");
            master.registerHeartbeat("w1", "10.0.0.5");
        });

        assertTrue(log.contains("INFO: Worker connected: w1 (10.0.0.5) — total workers: 1 ["), log);
        assertTrue(log.contains("INFO: Worker connected: w2 (10.0.0.6) — total workers: 2 ["), log);
        assertEquals(2, log.split("Worker connected", -1).length - 1, "a later heartbeat logs nothing: " + log);
    }

    @Test
    void workersThatRegisterTogetherReportTheRealTotal() {
        int workers = 8;
        for (int round = 0; round < 25; round++) {
            TestMaster master = new TestMaster();
            CyclicBarrier start = new CyclicBarrier(workers);

            String log = stderrOf(() -> {
                List<Thread> threads = IntStream.range(0, workers).mapToObj(i -> Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    master.registerHeartbeat("w" + i, "10.0.0." + i);
                })).toList();
                for (Thread thread : threads) {
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

            List<Integer> totals = Pattern.compile("— total workers: (\\d+) \\[").matcher(log).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();
            assertEquals(workers, totals.size(), "one line per worker: " + log);
            assertEquals(workers, Collections.max(totals),
                "a size read inside compute missed the workers registering at the same time: " + log);
        }
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    @Test
    void dispatchPutsTheTaskInFlightAndMakesItTheWorkersTask() {
        TestMaster master = new TestMaster();

        master.recordDispatch("w1", task(1));

        assertTrue(master.inFlightTasks.containsKey(1L));
        assertEquals(1L, master.pointerOf("w1"), "the worker is registered as holding the task");
        assertEquals("w1", master.holderOf(1L));
    }

    @Test
    void workerThatClaimsAgainGetsItsLostTaskRequeuedWithoutAFailure() {
        TestMaster master = new TestMaster();
        master.setMaxTaskFailures(1);  // a counted failure would discard the task at once
        ParallelTask<IntegerSolution> lost = task(1);
        master.recordDispatch("w1", lost);

        String log = stderrOf(() -> master.recordDispatch("w1", task(2)));

        assertFalse(master.inFlightTasks.containsKey(1L), "nobody can complete the lost task any more");
        assertEquals(List.of(lost), new ArrayList<>(master.getPendingTaskQueue()),
            "the task whose result or error never arrived goes back to the queue");
        assertEquals(0, master.getDiscardedTaskCount(), "requeued, not counted as a failed evaluation");
        assertEquals(2L, master.pointerOf("w1"), "the worker now holds its new task");
        assertTrue(log.contains("[task-1] Requeued"), "the requeue is logged: " + log);
    }

    @Test
    void workerThatReportedItsTaskTakesTheNextOneWithoutRequeuingAnything() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        stderrOf(() -> master.failInFlightTask(1L, "w1"));
        ParallelTask<IntegerSolution> again = master.getPendingTaskQueue().poll();

        master.recordDispatch("w1", again);

        assertTrue(master.getPendingTaskQueue().isEmpty(), "the reported task is not taken for a lost one");
        assertTrue(master.inFlightTasks.containsKey(1L));
        assertEquals(1L, master.pointerOf("w1"));
    }

    // ── Results ───────────────────────────────────────────────────────────────

    @Test
    void resultIsRecordedOnlyAfterTheTaskHasLeftTheInFlightMap() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        boolean[] inFlightWhileRecording = {true};

        boolean accepted = master.submitResult(1L, "w1",
            t -> inFlightWhileRecording[0] = master.inFlightTasks.containsKey(1L));

        assertTrue(accepted);
        assertFalse(inFlightWhileRecording[0], "the result is written only once this report has won the task");
        assertEquals(1, master.getCompletedTaskQueue().size());
        assertEquals(-1L, master.pointerOf("w1"), "the worker is idle again");
    }

    @Test
    void aSecondResultForTheSameTaskWritesNothing() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        master.submitResult(1L, "w1", t -> { });
        AtomicInteger recorded = new AtomicInteger();

        boolean[] accepted = new boolean[1];
        String log = stderrOf(() -> accepted[0] = master.submitResult(1L, "w2", t -> recorded.incrementAndGet()));

        assertFalse(accepted[0], "the task is no longer in flight: 404");
        assertEquals(0, recorded.get(), "a losing report never touches the solution");
        assertTrue(log.contains("Result for unknown/expired taskId: 1"), log);
    }

    @Test
    void resultWithoutWorkerIdIsRecordedAndTheTaskIsNotLost() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        boolean accepted = master.submitResult(1L, null, t -> { });

        assertTrue(accepted, "a client that omits workerId is still served");
        assertEquals(1, master.getCompletedTaskQueue().size(), "the task reaches the algorithm");
        assertTrue(master.inFlightTasks.isEmpty());
        assertEquals(-1L, master.pointerOf("w1"), "its holder is released");
    }

    @Test
    void legacySubmitResultStillMovesTheTask() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        assertTrue(master.submitResult(1L, "w1"));
        assertEquals(1, master.getCompletedTaskQueue().size());
    }

    @Test
    void lateResultFromAFormerHolderIsAcceptedAndReleasesTheNewHolder() {
        TestMaster master = new TestMaster();
        master.recordDispatch("old", task(1));
        master.workerRegistry.get("old").lastSeen = Instant.now().minusSeconds(3600);
        stderrOf(() -> master.requeueOrphanTasks(45));
        master.recordDispatch("new", master.getPendingTaskQueue().poll());

        assertTrue(master.submitResult(1L, "old", t -> { }), "a late result is still a valid evaluation");
        assertEquals(-1L, master.pointerOf("new"), "the worker still evaluating it no longer holds anything");
        assertFalse(master.submitResult(1L, "new", t -> { }), "its own result then gets 404");
    }

    @Test
    void resultForATaskNoLongerInFlightReleasesTheReporterIfItStillPointsAtIt() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        master.inFlightTasks.remove(1L);  // taken away behind the master's back

        stderrOf(() -> assertFalse(master.submitResult(1L, "w1", t -> fail("nothing to record"))));

        assertEquals(-1L, master.pointerOf("w1"), "a 404 must not leave the worker pointing at the task");
    }

    @Test
    void resultThatCannotBeRecordedCountsAsAFailedEvaluation() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> stderrOf(() -> master.submitResult(1L, "w1", t -> {
                throw new IllegalArgumentException("objectives[0] is not finite: NaN");
            })));

        assertEquals("objectives[0] is not finite: NaN", e.getMessage(), "the reason reaches the caller");
        assertTrue(master.getCompletedTaskQueue().isEmpty(), "an unrecorded result never reaches the algorithm");
        assertEquals(1, master.getPendingTaskQueue().size(), "the task is requeued, not lost");
    }

    @Test
    void resultAfterAStopIsDroppedWithoutRecording() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        stderrOf(master::requestStop);

        assertFalse(master.submitResult(1L, "w1", t -> fail("a result after the stop is not recorded")));
        assertTrue(master.getCompletedTaskQueue().isEmpty());
        assertTrue(master.inFlightTasks.isEmpty());
    }

    @Test
    void resultAfterTheRunHasEndedIsDroppedWithoutRecording() {
        // Another worker's result ended the run while w2 was still evaluating task 2: nobody
        // will process w2's result, so it gets 404 and is not counted.
        TestMaster master = new TestMaster();
        master.recordDispatch("w2", task(2));
        master.ended = true;

        String log = stderrOf(() -> assertFalse(
            master.submitResult(2L, "w2", t -> fail("a result after the end is not recorded"))));

        assertTrue(master.getCompletedTaskQueue().isEmpty(), "never queued: the algorithm has left its loop");
        assertTrue(master.inFlightTasks.isEmpty(), "the task leaves flight");
        assertEquals(-1L, master.pointerOf("w2"), "the worker is idle again");
        assertTrue(master.getPendingTaskQueue().isEmpty(), "not requeued either");
        assertEquals(0, master.getDiscardedTaskCount(), "not a failed evaluation");
        assertFalse(master.isStopRequested(), "a normal end is not turned into a stop");
        assertEquals("", log, "dropped quietly, as after a stop");
    }

    @Test
    void resultIsStillAcceptedWhileOnlyTheStoppingConditionIsMet() {
        // isFinished() can be true on a REST thread before the algorithm thread has noticed it
        // (a criterion installed with setTermination, or one that reads the clock): the loop may
        // be waiting for this very result to notice, so refusing it could hang the run.
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        master.running = () -> false;

        assertTrue(master.isFinished());
        assertTrue(master.submitResult(1L, "w1", t -> { }), "accepted until the algorithm has left its loop");
        assertEquals(1, master.getCompletedTaskQueue().size());
    }

    @Test
    void masterNeedsNoMoreResultsOnlyOnceAStopIsRequestedOrTheRunHasEnded() {
        TestMaster master = new TestMaster();
        master.running = () -> false;  // met as REST threads see it

        assertTrue(master.isFinished());
        assertFalse(master.needsNoMoreResults(), "the loop may still be waiting for the result that lets it notice");

        master.ended = true;
        assertTrue(master.needsNoMoreResults());

        TestMaster stopped = new TestMaster();
        stderrOf(stopped::requestStop);
        assertTrue(stopped.needsNoMoreResults());
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    void failedTaskNoLongerCountsAsHeldByItsWorker() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        stderrOf(() -> master.failInFlightTask(1L));

        assertEquals(-1L, master.pointerOf("w1"),
            "otherwise the watchdog could later pull the requeued task away from another worker");
        assertEquals(1, master.getPendingTaskQueue().size());
    }

    @Test
    void failureReportFromTheHolderIsCounted() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        boolean[] counted = new boolean[1];
        stderrOf(() -> counted[0] = master.failInFlightTask(1L, "w1"));

        assertTrue(counted[0]);
        assertFalse(master.inFlightTasks.containsKey(1L));
        assertEquals(-1L, master.pointerOf("w1"));
    }

    @Test
    void failureReportFromAWorkerThatNoLongerHoldsTheTaskIsIgnored() {
        TestMaster master = new TestMaster();
        master.setMaxTaskFailures(1);
        master.recordDispatch("old", task(1));
        master.workerRegistry.get("old").lastSeen = Instant.now().minusSeconds(3600);
        stderrOf(() -> master.requeueOrphanTasks(45));
        master.recordDispatch("new", master.getPendingTaskQueue().poll());

        boolean[] counted = new boolean[1];
        String log = stderrOf(() -> counted[0] = master.failInFlightTask(1L, "old"));

        assertFalse(counted[0], "the evicted worker's stale error must not touch the new holder's evaluation");
        assertTrue(master.inFlightTasks.containsKey(1L), "the task stays with its holder");
        assertEquals(0, master.getDiscardedTaskCount(), "and it is not counted (it would be discarded here)");
        assertEquals(1L, master.pointerOf("new"));
        assertTrue(log.contains("ignored") && log.contains("held by new"), log);
    }

    @Test
    void failureReportFromAnUnknownReporterIsCounted() {
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        boolean[] counted = new boolean[1];
        stderrOf(() -> counted[0] = master.failInFlightTask(1L, null));

        assertTrue(counted[0], "a body that could not be decoded names no worker: the report counts");
        assertEquals(1, master.getPendingTaskQueue().size());
    }

    @Test
    void failureReportForATaskNotInFlightDoesNothing() {
        TestMaster master = new TestMaster();

        assertFalse(master.failInFlightTask(7L, "w1"));
        assertTrue(master.getPendingTaskQueue().isEmpty());
    }

    @Test
    void failureReportIsStillCountedWhileOnlyTheStoppingConditionIsMet() {
        // As for a result: the loop may still be waiting for one more result to notice its
        // criterion, so the task goes back to the queue for another worker.
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));
        master.running = () -> false;

        boolean[] counted = new boolean[1];
        stderrOf(() -> counted[0] = master.failInFlightTask(1L, "w1"));

        assertTrue(master.isFinished());
        assertTrue(counted[0], "counted until the algorithm has left its loop");
        assertEquals(1, master.getPendingTaskQueue().size(), "requeued for another worker");
    }

    @Test
    void failureReportAfterTheRunHasEndedCountsNothing() {
        // Task 1 failed on w1 after another worker's result had ended the run: nobody would hand
        // it out again, so requeueing or discarding it would only distort the counts.
        TestMaster master = new TestMaster();
        master.setMaxTaskFailures(1);  // a counted failure would discard the task at once
        master.recordDispatch("w1", task(1));
        master.ended = true;

        boolean[] counted = new boolean[1];
        String log = stderrOf(() -> counted[0] = master.failInFlightTask(1L, "w1"));

        assertFalse(counted[0], "not counted");
        assertTrue(master.inFlightTasks.isEmpty(), "the task leaves flight");
        assertEquals(-1L, master.pointerOf("w1"), "the worker is idle again");
        assertTrue(master.getPendingTaskQueue().isEmpty(), "not requeued into a queue nobody serves");
        assertEquals(0, master.getDiscardedTaskCount(), "not discarded either");
        assertEquals(List.of(), master.discardHooks, "so onTaskDiscarded is not called");
        assertEquals("", log, "and no discard is logged");
        assertFalse(master.isStopRequested(), "a normal end is not turned into a stop");
    }

    @Test
    @SuppressWarnings("deprecation")
    void failureAfterAStopCountsNothingWhicheverWayItIsReported() {
        TestMaster master = new TestMaster();
        master.setMaxTaskFailures(1);
        master.recordDispatch("w1", task(1));
        master.recordDispatch("w2", task(2));
        master.recordDispatch("w3", task(3));
        stderrOf(master::requestStop);

        String log = stderrOf(() -> {
            assertFalse(master.failInFlightTask(1L, null), "a body that could not be decoded: not counted");
            master.failInFlightTask(2L);
            master.requeueInFlightTask(3L);
        });

        assertTrue(master.inFlightTasks.isEmpty(), "every task leaves flight");
        assertTrue(master.getPendingTaskQueue().isEmpty(), "none is requeued");
        assertEquals(0, master.getDiscardedTaskCount(), "none is discarded");
        assertEquals(List.of(), master.discardHooks);
        assertEquals("", log);
    }

    @Test
    void resultThatCannotBeRecordedWhenAStopLandsCountsNothing() {
        // The stop lands while the result is written, after submitResult has checked for one:
        // the failure goes through the same check as every other failure.
        TestMaster master = new TestMaster();
        master.recordDispatch("w1", task(1));

        assertThrows(IllegalArgumentException.class, () -> stderrOf(() -> master.submitResult(1L, "w1", t -> {
            master.requestStop();
            throw new IllegalArgumentException("objectives[0] is not finite: NaN");
        })));

        assertTrue(master.getPendingTaskQueue().isEmpty(), "not requeued into a queue nobody serves");
        assertTrue(master.getCompletedTaskQueue().isEmpty());
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    @Test
    void watchdogRemovesOnlySilentWorkersRequeuesTheirTasksAndLogsHowManyItRemoved() {
        TestMaster master = new TestMaster();
        master.recordDispatch("dead1", task(1));
        master.recordDispatch("dead2", task(2));
        master.recordDispatch("alive", task(3));
        master.workerRegistry.get("dead1").lastSeen = Instant.now().minusSeconds(3600);
        master.workerRegistry.get("dead2").lastSeen = Instant.now().minusSeconds(3600);

        String log = stderrOf(() -> master.requeueOrphanTasks(45));

        assertEquals(List.of("alive"), new ArrayList<>(master.getWorkerRegistry().keySet()));
        assertEquals(2, master.getPendingTaskQueue().size(), "both orphans are requeued");
        assertEquals(List.of(3L), new ArrayList<>(master.inFlightTasks.keySet()));
        assertTrue(log.contains("Watchdog: 2 worker(s) removed"),
            "the summary reports the real number of evicted workers: " + log);
        assertEquals("", stderrOf(() -> master.requeueOrphanTasks(45)), "nothing to report when nobody died");
    }

    @Test
    void watchdogStillRequeuesWhileOnlyTheStoppingConditionIsMet() {
        TestMaster master = new TestMaster();
        master.recordDispatch("dead", task(1));
        master.workerRegistry.get("dead").lastSeen = Instant.now().minusSeconds(3600);
        master.running = () -> false;

        String log = stderrOf(() -> master.requeueOrphanTasks(45));

        assertTrue(master.isFinished());
        assertEquals(1, master.getPendingTaskQueue().size(), "the loop may still be waiting for its result");
        assertTrue(log.contains("Requeueing task 1 from dead worker dead"), log);
    }

    @Test
    void watchdogDropsTheTaskOfASilentWorkerOnceTheRunHasEnded() {
        TestMaster master = new TestMaster();
        master.recordDispatch("dead", task(1));
        master.workerRegistry.get("dead").lastSeen = Instant.now().minusSeconds(3600);
        master.ended = true;

        String log = stderrOf(() -> master.requeueOrphanTasks(45));

        assertTrue(master.getWorkerRegistry().isEmpty(), "the silent worker is removed as before");
        assertTrue(master.inFlightTasks.isEmpty());
        assertTrue(master.getPendingTaskQueue().isEmpty(), "but its task is not requeued: nobody would hand it out");
        assertTrue(log.contains("Dropping task 1 from dead worker dead"), log);
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    @Test
    void shutdownStopsARunStillGoingAndIsIdempotent() {
        TestMaster master = new TestMaster();

        String log = stderrOf(() -> {
            master.shutdown();
            master.shutdown();
        });

        assertTrue(master.isStopRequested(), "a run still going is stopped, so its run() returns");
        assertEquals(1, log.split("Stop requested", -1).length - 1, "the second call does nothing: " + log);
    }

    @Test
    void shutdownAfterANormalFinishDoesNotReportAStop() {
        TestMaster master = new TestMaster();
        master.running = () -> false;

        String log = stderrOf(master::shutdown);

        assertFalse(master.isStopRequested(), "a finished run is not stopped again");
        assertFalse(log.contains("Stop requested"), "tools read that line as a user stop: " + log);
    }
}

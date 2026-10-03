package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static es.unex.jdisrest.distributed.AbstractMasterTest.intSolution;
import static es.unex.jdisrest.distributed.AbstractMasterTest.stringSolution;
import static es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithmTest.binary;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The generation barrier of {@link GenerationalMaster#waitForEvaluatedTasks()}: discarded tasks
 * and a stop must end the wait, which would otherwise block on results that never come. Tested
 * through {@link GenerationalMaster#awaitGeneration}, since constructing a master starts Spring.
 * And the check of the first generation submitted, on a master built without its server.
 */
class GenerationalMasterTest {

    /** Short, so that a check the wait misses shows up as a slow test rather than a hang. */
    private static final Duration CHECK_INTERVAL = Duration.ofMillis(10);

    /** Upper bound for anything a test waits for on another thread. */
    private static final long TIMEOUT_S = 5;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static BlockingQueue<String> completed(String... tasks) {
        return new LinkedBlockingQueue<>(List.of(tasks));
    }

    /** A master without REST server whose generations the tests submit themselves. */
    static final class TestMaster extends GenerationalMaster<ParallelTask<Object>, Void> {
        TestMaster() {
            super(null, 2);
        }

        @Override public List<ParallelTask<Object>> createInitialTasks() { return List.of(); }
        @Override public void evolution(List<ParallelTask<Object>> population) { }
        @Override public boolean stoppingConditionIsNotMet() { return false; }
        @Override public Void getResult() { return null; }
    }

    static List<ParallelTask<Object>> generation(Object... contents) {
        List<ParallelTask<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) tasks.add(ParallelTask.create(i, contents[i]));
        return tasks;
    }

    static List<String> await(BlockingQueue<String> completed, int expected, int discarded, boolean stopped) {
        return GenerationalMaster.awaitGeneration(completed, expected, () -> discarded, () -> stopped, CHECK_INTERVAL);
    }

    /**
     * Starts the wait on a daemon thread and returns once that thread is blocked on the queue,
     * so that whatever the test does next happens <em>during</em> the wait.
     */
    static FutureTask<List<String>> awaitInBackground(BlockingQueue<String> completed, int expected,
                                                      AtomicInteger discarded, AtomicBoolean stopped)
            throws InterruptedException {
        FutureTask<List<String>> wait = new FutureTask<>(() -> GenerationalMaster.awaitGeneration(
            completed, expected, discarded::get, stopped::get, CHECK_INTERVAL));
        Thread thread = new Thread(wait, "generation-barrier-test-waiter");
        thread.setDaemon(true);
        thread.start();
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(Instant.now().isBefore(deadline), "the waiter never blocked on the queue");
            Thread.sleep(1);
        }
        return wait;
    }

    // ── Submission ────────────────────────────────────────────────────────────

    @Test
    void solutionThatCannotTravelFailsTheFirstSubmissionBeforeAnythingIsQueued() {
        TestMaster master = new TestMaster();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> master.submitTasks(generation(stringSolution(), intSolution(1))),
            "every task would fail with a 500 and be discarded, and a budget-driven run would never end");
        assertTrue(e.getMessage().startsWith("Unsupported solution type "), e.getMessage());
        assertTrue(master.getPendingTaskQueue().isEmpty(), "nothing is queued");
    }

    @Test
    void binaryVariableOfNoBitsFailsTheFirstSubmission() {
        TestMaster master = new TestMaster();
        BinarySolution s = binary("101", "1");
        s.variables().set(1, new BinarySet(0));

        assertThrows(IllegalArgumentException.class, () -> master.submitTasks(generation(s)));
        assertTrue(master.getPendingTaskQueue().isEmpty(), "nothing is queued");
    }

    @Test
    void solutionsOfEveryEncodingAreQueued() {
        TestMaster master = new TestMaster();

        master.submitTasks(generation(binary("101", "00110"),
            new CompositeSolution(List.of(intSolution(3), binary("01")))));

        assertEquals(2, master.getPendingTaskQueue().size());
    }

    @Test
    void contentsThatAreNotSolutionsAreQueuedUnchecked() {
        TestMaster master = new TestMaster();

        master.submitTasks(generation("a task of another kind"));

        assertEquals(1, master.getPendingTaskQueue().size());
    }

    // ── Full generation ───────────────────────────────────────────────────────

    @Test
    void waitReturnsTheWholeGenerationInCompletionOrder() {
        assertEquals(List.of("b", "a", "c"), await(completed("b", "a", "c"), 3, 0, false));
    }

    @Test
    void waitTakesNothingBeyondTheGeneration() {
        BlockingQueue<String> queue = completed("a", "b", "early");

        assertEquals(List.of("a", "b"), await(queue, 2, 0, false));
        assertEquals(List.of("early"), List.copyOf(queue), "results beyond the generation must stay queued");
    }

    // ── Discarded tasks ───────────────────────────────────────────────────────

    @Test
    void waitEndsWhenEvaluatedAndDiscardedTasksAddUpToTheGeneration() {
        assertEquals(List.of("a", "b"), await(completed("a", "b"), 3, 1, false),
            "a discarded task never arrives, so the wait must not expect it");
    }

    @Test
    void discardDuringTheWaitIsNoticed() throws Exception {
        AtomicInteger discarded = new AtomicInteger();
        BlockingQueue<String> queue = completed();
        FutureTask<List<String>> wait = awaitInBackground(queue, 2, discarded, new AtomicBoolean());

        queue.add("a");
        discarded.incrementAndGet();

        assertEquals(List.of("a"), wait.get(TIMEOUT_S, TimeUnit.SECONDS),
            "a discard adds nothing to the queue, yet it must end the wait");
    }

    @Test
    void generationWhoseTasksWereAllDiscardedReturnsEmpty() {
        assertEquals(List.of(), await(completed(), 2, 2, false));
    }

    // ── Stop and interrupt ────────────────────────────────────────────────────

    @Test
    void stopDuringTheWaitReturnsTheTasksReceivedSoFar() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        FutureTask<List<String>> wait = awaitInBackground(completed("a"), 3, new AtomicInteger(), stopped);

        stopped.set(true);

        assertEquals(List.of("a"), wait.get(TIMEOUT_S, TimeUnit.SECONDS),
            "no result arrives after a stop, so the wait must return what it has");
    }

    @Test
    void stopBeforeTheWaitTakesNothing() {
        BlockingQueue<String> queue = completed("late");

        assertEquals(List.of(), await(queue, 1, 0, true));
        assertEquals(List.of("late"), List.copyOf(queue));
    }

    @Test
    void interruptEndsTheWaitAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertEquals(List.of(), await(completed(), 1, 0, false));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored for the caller");
        } finally {
            Thread.interrupted();  // leave the test thread clean
        }
    }
}

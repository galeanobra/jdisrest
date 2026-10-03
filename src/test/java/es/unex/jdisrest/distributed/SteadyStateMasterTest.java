package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static es.unex.jdisrest.distributed.AbstractMasterTest.intSolution;
import static es.unex.jdisrest.distributed.AbstractMasterTest.stderrOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How the masters hand out tasks before they are ready, once they need no more results and when
 * a worker claims again: tested on masters built without their REST server (constructing a real
 * one starts Spring).
 */
class SteadyStateMasterTest {

    /** The long-poll window passed to {@code claimNextTask}; a test that waits it out fails on time. */
    private static final int LONG_POLL_S = 30;

    /** Far below {@link #LONG_POLL_S}: a claim that answers within it did not wait. */
    private static final Duration AT_ONCE = Duration.ofSeconds(5);

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A steady-state master whose readiness, stopping condition and end of run the test sets, and
     * whose new tasks it counts.
     */
    static final class TestSteadyStateMaster extends SteadyStateMaster<ParallelTask<IntegerSolution>, Void> {
        volatile boolean ready = true;
        volatile boolean running = true;
        volatile boolean ended = false;
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger stoppingConditionChecks = new AtomicInteger();
        private final AtomicLong ids = new AtomicLong(100);

        TestSteadyStateMaster() {
            super(null);
        }

        @Override public boolean isReady() { return ready; }
        @Override boolean runEnded() { return ended; }

        @Override
        public boolean stoppingConditionIsNotMet() {
            stoppingConditionChecks.incrementAndGet();
            return running;
        }

        @Override
        public ParallelTask<IntegerSolution> createNewTask() {
            created.incrementAndGet();
            return ParallelTask.create(ids.getAndIncrement(), intSolution(0));
        }

        @Override public List<ParallelTask<IntegerSolution>> createInitialTasks() { return List.of(); }
        @Override public void processComputedTask(ParallelTask<IntegerSolution> task) { }
        @Override public void initProgress() { }
        @Override public void updateProgress() { }
        @Override public Void getResult() { return null; }
    }

    /** A generational master whose readiness the test sets. */
    static final class TestGenerationalMaster extends GenerationalMaster<ParallelTask<IntegerSolution>, Void> {
        volatile boolean ready = true;

        TestGenerationalMaster() {
            super(null, 2);
        }

        @Override public boolean isReady() { return ready; }
        @Override public boolean stoppingConditionIsNotMet() { return true; }
        @Override public List<ParallelTask<IntegerSolution>> createInitialTasks() { return List.of(); }
        @Override public void evolution(List<ParallelTask<IntegerSolution>> population) { }
        @Override public Void getResult() { return null; }
    }

    static ParallelTask<IntegerSolution> task(long id) {
        return ParallelTask.create(id, intSolution((int) id));
    }

    // ── Not ready ─────────────────────────────────────────────────────────────

    @Test
    void steadyStateMasterThatIsNotReadyHandsOutNothingAtOnce() throws InterruptedException {
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.ready = false;
        master.submitTask(task(1));

        Instant start = Instant.now();
        ParallelTask<IntegerSolution> claimed = master.claimNextTask("w1", LONG_POLL_S);

        assertNull(claimed, "the worker gets 204 and asks again later");
        assertTrue(Duration.between(start, Instant.now()).compareTo(AT_ONCE) < 0, "no long-poll while not ready");
        assertEquals(0, master.stoppingConditionChecks.get(), "the stopping condition cannot be evaluated yet");
        assertEquals(0, master.created.get(), "no offspring is bred from a population that does not exist yet");
        assertEquals(1, master.getPendingTaskQueue().size(), "the queued task waits for the master to be ready");
        assertTrue(master.inFlightTasks.isEmpty());
    }

    @Test
    void steadyStateMasterHandsOutTasksOnceReady() throws InterruptedException {
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.ready = false;
        master.submitTask(task(1));
        master.ready = true;

        assertEquals(1L, master.claimNextTask("w1", LONG_POLL_S).getIdentifier(), "the queued task first");
        assertEquals(100L, master.claimNextTask("w2", LONG_POLL_S).getIdentifier(), "then new offspring");
        assertEquals(1, master.created.get());
    }

    @Test
    void generationalMasterThatIsNotReadyHandsOutNothingAtOnce() throws InterruptedException {
        TestGenerationalMaster master = new TestGenerationalMaster();
        master.ready = false;
        master.submitTasks(List.of(task(1), task(2)));

        Instant start = Instant.now();
        ParallelTask<IntegerSolution> claimed = master.claimNextTask("w1", LONG_POLL_S);

        assertNull(claimed);
        assertTrue(Duration.between(start, Instant.now()).compareTo(AT_ONCE) < 0, "no long-poll while not ready");
        assertEquals(2, master.getPendingTaskQueue().size());
    }

    // ── No more results needed ────────────────────────────────────────────────

    @Test
    void steadyStateMasterStillHandsOutAQueuedTaskWhileOnlyTheStoppingConditionIsMet() throws InterruptedException {
        // isFinished() can be true on a REST thread before the algorithm thread has noticed it (a
        // criterion installed with setTermination): the loop may be waiting for one more result,
        // and the queued task's can be that one.
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.submitTask(task(1));
        master.running = false;

        assertTrue(master.isFinished());
        ParallelTask<IntegerSolution> claimed = master.claimNextTask("w1", LONG_POLL_S);

        assertNotNull(claimed, "handed out until the algorithm has left its loop");
        assertEquals(1L, claimed.getIdentifier());
        assertEquals(0, master.created.get(), "but no new task is created for it");
        assertTrue(master.inFlightTasks.containsKey(1L));
    }

    @Test
    void steadyStateMasterHandsOutNothingOnceTheRunHasEnded() throws InterruptedException {
        // A request that passed the controller's check just before the end: the spare child of
        // the last task created is still queued, and its result would be refused.
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.submitTask(task(1));
        master.ended = true;

        Instant start = Instant.now();
        ParallelTask<IntegerSolution> claimed = master.claimNextTask("w1", LONG_POLL_S);

        assertNull(claimed, "the worker gets 204, then 410 on its next request");
        assertTrue(Duration.between(start, Instant.now()).compareTo(AT_ONCE) < 0, "no long-poll after the end");
        assertEquals(1, master.getPendingTaskQueue().size(), "the queued task is not even taken");
        assertEquals(0, master.created.get());
        assertTrue(master.inFlightTasks.isEmpty());
    }

    @Test
    void taskThatArrivesDuringTheLongPollAfterTheEndIsNotHandedOut() throws Exception {
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.running = false;  // met as REST threads see it: nothing is created, the claim waits
        FutureTask<ParallelTask<IntegerSolution>> claim =
            new FutureTask<>(() -> master.claimNextTask("w1", LONG_POLL_S));
        Thread thread = new Thread(claim, "long-poll-under-test");
        thread.setDaemon(true);
        thread.start();
        Instant deadline = Instant.now().plus(AT_ONCE);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(Instant.now().isBefore(deadline), "the claim never started its long-poll");
            Thread.sleep(1);
        }

        master.ended = true;
        master.submitTask(task(1));  // say, requeued just before the end

        assertNull(claim.get(AT_ONCE.toSeconds(), TimeUnit.SECONDS), "a task whose result would be refused");
        assertTrue(master.inFlightTasks.isEmpty());
        assertNull(master.getWorkerRegistry().get("w1"), "the worker was never given anything");
    }

    @Test
    void generationalMasterHandsOutNothingAfterAStop() throws InterruptedException {
        TestGenerationalMaster master = new TestGenerationalMaster();
        master.submitTasks(List.of(task(1), task(2)));
        stderrOf(master::requestStop);

        assertNull(master.claimNextTask("w1", LONG_POLL_S));
        assertEquals(2, master.getPendingTaskQueue().size(), "at once, without taking a task to drop");
        assertTrue(master.inFlightTasks.isEmpty());
    }

    // ── Claiming again ────────────────────────────────────────────────────────

    @Test
    void steadyStateClaimRequeuesTheTaskTheWorkerNeverReported() throws InterruptedException {
        TestSteadyStateMaster master = new TestSteadyStateMaster();
        master.submitTask(task(1));
        master.claimNextTask("w1", LONG_POLL_S);

        AtomicReference<ParallelTask<IntegerSolution>> second = new AtomicReference<>();
        stderrOf(() -> {
            try {
                second.set(master.claimNextTask("w1", LONG_POLL_S));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(100L, second.get().getIdentifier());
        assertEquals(List.of(1L), master.getPendingTaskQueue().stream().map(ParallelTask::getIdentifier).toList(),
            "the first task, whose result was lost, is handed out again");
        assertEquals(List.of(100L), List.copyOf(master.inFlightTasks.keySet()));
    }

    @Test
    void generationalClaimRequeuesTheTaskTheWorkerNeverReported() throws InterruptedException {
        TestGenerationalMaster master = new TestGenerationalMaster();
        master.submitTasks(List.of(task(1), task(2)));
        master.claimNextTask("w1", LONG_POLL_S);

        stderrOf(() -> {
            try {
                assertEquals(2L, master.claimNextTask("w1", LONG_POLL_S).getIdentifier());
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });

        assertEquals(List.of(1L), master.getPendingTaskQueue().stream().map(ParallelTask::getIdentifier).toList(),
            "otherwise the generation would wait forever for a result that never comes");
    }
}

package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static es.unex.jdisrest.distributed.AbstractMasterTest.intSolution;
import static es.unex.jdisrest.distributed.AbstractMasterTest.stderrOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How the masters hand out tasks before they are ready and when a worker claims again: tested on
 * masters built without their REST server (constructing a real one starts Spring).
 */
class SteadyStateMasterTest {

    /** The long-poll window passed to {@code claimNextTask}; a test that waits it out fails on time. */
    private static final int LONG_POLL_S = 30;

    /** Far below {@link #LONG_POLL_S}: a claim that answers within it did not wait. */
    private static final Duration AT_ONCE = Duration.ofSeconds(5);

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A steady-state master whose readiness the test sets and whose new tasks it counts. */
    static final class TestSteadyStateMaster extends SteadyStateMaster<ParallelTask<IntegerSolution>, Void> {
        volatile boolean ready = true;
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger stoppingConditionChecks = new AtomicInteger();
        private final AtomicLong ids = new AtomicLong(100);

        TestSteadyStateMaster() {
            super(null);
        }

        @Override public boolean isReady() { return ready; }

        @Override
        public boolean stoppingConditionIsNotMet() {
            stoppingConditionChecks.incrementAndGet();
            return true;
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

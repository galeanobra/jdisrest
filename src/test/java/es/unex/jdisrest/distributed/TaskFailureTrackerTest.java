package es.unex.jdisrest.distributed;

import es.unex.jdisrest.distributed.TaskFailureTracker.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-task failure limit: which failed evaluation discards a task, how counts are kept
 * per task and forgotten, and how the limit is validated and changed while running.
 */
class TaskFailureTrackerTest {

    private static final int MAX_FAILURES = 3;
    private static final long TASK = 42L;
    private static final long OTHER_TASK = 7L;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static TaskFailureTracker tracker() {
        return new TaskFailureTracker(MAX_FAILURES);
    }

    /** Records {@code times} failures of a task and returns the decisions in order. */
    static List<Decision> fail(TaskFailureTracker tracker, long taskId, int times) {
        List<Decision> decisions = new ArrayList<>();
        for (int i = 0; i < times; i++) decisions.add(tracker.recordFailure(taskId));
        return decisions;
    }

    // ── recordFailure ─────────────────────────────────────────────────────────

    @Test
    void taskIsRetriedWhileItHasFailedFewerTimesThanTheLimit() {
        TaskFailureTracker tracker = tracker();

        assertEquals(List.of(Decision.RETRY, Decision.RETRY), fail(tracker, TASK, 2));
        assertEquals(2, tracker.failures(TASK), "every retried failure must be counted");
        assertEquals(0, tracker.discardedCount(), "nothing is discarded below the limit");
    }

    @Test
    void taskIsDiscardedOnItsLastAttemptAndItsCountForgotten() {
        TaskFailureTracker tracker = tracker();
        fail(tracker, TASK, MAX_FAILURES - 1);

        assertEquals(Decision.DISCARD, tracker.recordFailure(TASK), "the limit-th failure must discard the task");
        assertEquals(0, tracker.failures(TASK), "a discarded task must not keep a count");
        assertEquals(1, tracker.discardedCount());
    }

    @Test
    void limitOfOneDiscardsAtTheFirstFailure() {
        assertEquals(Decision.DISCARD, new TaskFailureTracker(1).recordFailure(TASK));
    }

    @Test
    void eachTaskKeepsItsOwnCount() {
        TaskFailureTracker tracker = tracker();
        tracker.recordFailure(OTHER_TASK);

        fail(tracker, TASK, 2);

        assertEquals(2, tracker.failures(TASK));
        assertEquals(1, tracker.failures(OTHER_TASK), "failures of one task must not count against another");
    }

    @Test
    void unknownTaskHasNoFailures() {
        assertEquals(0, tracker().failures(TASK));
    }

    @Test
    void countStartsOverAfterTheTaskIsForgotten() {
        TaskFailureTracker tracker = tracker();
        fail(tracker, TASK, 2);
        tracker.forget(TASK);

        assertEquals(Decision.RETRY, tracker.recordFailure(TASK),
            "a task whose result was accepted must start a new count if it fails later");
        assertEquals(1, tracker.failures(TASK));
    }

    @Test
    void discardCounterGrowsOncePerDiscardedTask() {
        TaskFailureTracker tracker = tracker();

        fail(tracker, TASK, MAX_FAILURES);
        fail(tracker, OTHER_TASK, MAX_FAILURES);
        fail(tracker, 99L, MAX_FAILURES - 1);

        assertEquals(2, tracker.discardedCount(), "only tasks that reached the limit are discarded");
    }

    @Test
    void concurrentFailuresOfDistinctTasksAreAllCounted() throws Exception {
        TaskFailureTracker tracker = tracker();
        int tasks = 200;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (long id = 0; id < tasks; id++) {
                long taskId = id;
                done.add(pool.submit(() -> fail(tracker, taskId, MAX_FAILURES)));
            }
            for (Future<?> f : done) f.get();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(tasks, tracker.discardedCount(), "concurrent REST threads must not lose discards");
    }

    // ── Limit ─────────────────────────────────────────────────────────────────

    @Test
    void loweringTheLimitBelowTheRecordedFailuresDiscardsAtTheNextFailure() {
        TaskFailureTracker tracker = tracker();
        fail(tracker, TASK, 2);
        tracker.setMaxFailures(1);

        assertEquals(Decision.DISCARD, tracker.recordFailure(TASK));
    }

    @Test
    void raisingTheLimitGivesAFailingTaskMoreAttempts() {
        TaskFailureTracker tracker = tracker();
        fail(tracker, TASK, MAX_FAILURES - 1);
        tracker.setMaxFailures(MAX_FAILURES + 2);

        assertEquals(List.of(Decision.RETRY, Decision.RETRY, Decision.DISCARD), fail(tracker, TASK, 3),
            "the new limit must count the failures recorded before it changed");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void limitBelowOneIsRejectedAtConstruction(int maxFailures) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new TaskFailureTracker(maxFailures));
        assertTrue(e.getMessage().contains("maxFailures"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void limitBelowOneIsRejectedAndThePreviousLimitKept(int maxFailures) {
        TaskFailureTracker tracker = tracker();

        assertThrows(IllegalArgumentException.class, () -> tracker.setMaxFailures(maxFailures));
        assertEquals(MAX_FAILURES, tracker.maxFailures(), "a rejected limit must not replace the current one");
    }
}

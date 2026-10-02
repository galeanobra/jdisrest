package es.unex.jdisrest.distributed;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts the failed evaluations of each task and decides whether a failed task is retried or
 * discarded, so that a task that always fails (for example, a solution the evaluator cannot
 * handle) does not circulate among the workers forever.
 *
 * <p>A failure is an evaluation error reported by a worker ({@code POST /api/v1/tasks/{id}/error}),
 * a result the master rejects ({@code 422}, or a request body Spring cannot decode) or a task the
 * master cannot serialize for {@code GET /api/v1/tasks/next}. Tasks the watchdog requeues because
 * their worker went silent are <em>not</em> failures: the worker may have been preempted or
 * evicted, which says nothing about the task.
 *
 * <h2>Thread safety</h2>
 * Failures are recorded from REST threads. Counts live in a {@link ConcurrentHashMap} updated
 * with atomic {@code merge}/{@code remove} calls, the discard counter is an {@link AtomicLong}
 * and the limit is {@code volatile}, so no external locking is needed. {@link AbstractMaster}
 * records at most one failure per dispatch of a task (it first wins the task back from
 * {@code inFlightTasks}), so two failures of the same task never race.
 *
 * <p>Kept apart from {@link AbstractMaster}, whose constructor starts the REST server, so that
 * it can be tested on its own.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
final class TaskFailureTracker {

    /** What to do with a task after one more failed evaluation. */
    enum Decision {
        /** The task has failed fewer times than the limit: hand it out again. */
        RETRY,
        /** The task has reached the limit: drop it for good. */
        DISCARD
    }

    /** Failed evaluations of each task still being retried; tasks without failures are absent. */
    private final ConcurrentHashMap<Long, Integer> failures = new ConcurrentHashMap<>();

    /** Tasks discarded since the tracker was created. */
    private final AtomicLong discarded = new AtomicLong();

    /** Failed evaluations after which a task is discarded; at least 1. */
    private volatile int maxFailures;

    /**
     * Creates a tracker with no failures recorded.
     *
     * @param maxFailures failed evaluations after which a task is discarded; at least 1
     * @throws IllegalArgumentException if {@code maxFailures} is less than 1
     */
    TaskFailureTracker(int maxFailures) {
        setMaxFailures(maxFailures);
    }

    /**
     * Changes the limit. It applies from the next recorded failure on, also to tasks that have
     * already failed: a task whose count already reaches a lowered limit is discarded at its next
     * failure.
     *
     * @param maxFailures failed evaluations after which a task is discarded; at least 1
     * @throws IllegalArgumentException if {@code maxFailures} is less than 1; the previous limit
     *                                  is kept
     */
    void setMaxFailures(int maxFailures) {
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be at least 1, got " + maxFailures);
        }
        this.maxFailures = maxFailures;
    }

    /**
     * Returns the current limit.
     *
     * @return failed evaluations after which a task is discarded
     */
    int maxFailures() {
        return maxFailures;
    }

    /**
     * Records one more failed evaluation of a task and decides what to do with it. On
     * {@link Decision#DISCARD} the task's count is forgotten and the discard counter grows.
     *
     * @param taskId the task whose evaluation failed
     * @return {@link Decision#RETRY} while the task has failed fewer than {@link #maxFailures()}
     *         times, {@link Decision#DISCARD} once it reaches the limit
     */
    Decision recordFailure(long taskId) {
        int count = failures.merge(taskId, 1, Integer::sum);
        Decision decision = Decision.RETRY;
        if (count >= maxFailures) {
            failures.remove(taskId);
            discarded.incrementAndGet();
            decision = Decision.DISCARD;
        }
        return decision;
    }

    /**
     * Returns the failed evaluations recorded for a task that is still being retried.
     *
     * @param taskId the task
     * @return its failures so far; {@code 0} if it never failed, was evaluated or was discarded
     */
    int failures(long taskId) {
        return failures.getOrDefault(taskId, 0);
    }

    /**
     * Forgets the failures of a task that has finally been evaluated, so that the map does not
     * grow with every task that once failed.
     *
     * @param taskId the task whose result was accepted
     */
    void forget(long taskId) {
        failures.remove(taskId);
    }

    /**
     * Returns the number of tasks discarded so far.
     *
     * @return discarded tasks since the tracker was created
     */
    long discardedCount() {
        return discarded.get();
    }
}

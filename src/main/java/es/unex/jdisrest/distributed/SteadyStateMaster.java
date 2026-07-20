package es.unex.jdisrest.distributed;

import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * REST-based master for <em>steady-state</em> multi-objective evolutionary algorithms.
 *
 * <p>Extends {@link AbstractMaster} with the steady-state task-dispatch protocol and
 * implements {@link SteadyStateAlgorithm} to drive the algorithm main loop. In the steady-state
 * model, workers operate independently: as soon as one evaluation finishes the worker
 * immediately requests the next task without waiting for a generation barrier.
 *
 * <h2>Singleton pattern</h2>
 * Spring Boot beans ({@code TaskController}, {@code WorkerController},
 * {@code WatchdogScheduler}) are instantiated by the Spring IoC container before the
 * algorithm object is constructed by application code. {@link #INSTANCE} provides a
 * static bridge that controllers can call via {@link #getInstance()} to reach the master.
 * Only one {@code SteadyStateMaster} instance should be active per JVM; constructing a second
 * instance overwrites {@code INSTANCE} with a warning.
 *
 * <h2>Task-creation lock</h2>
 * {@link #claimNextTask(String, int)} may be invoked concurrently from multiple
 * WebFlux {@code boundedElastic} threads when several workers poll for work
 * simultaneously. The {@link #taskCreationLock} guard ensures that
 * {@link #createNewTask()} is called by at most one thread at a time, preventing
 * duplicate offspring from being generated in parallel.
 *
 * @param <T> type of {@link ParallelTask} managed by this master
 * @param <R> type of the final algorithm result
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public abstract class SteadyStateMaster<T extends ParallelTask<?>, R> extends AbstractMaster<T, R> implements SteadyStateAlgorithm<T, R> {

    /**
     * Singleton reference shared with Spring beans. {@code volatile} to ensure safe
     * publication across threads (the Spring thread reads it; the algorithm thread writes
     * it in the constructor).
     */
    private static volatile SteadyStateMaster<?, ?> INSTANCE;

    /**
     * Returns the active {@code SteadyStateMaster} singleton so that Spring beans can delegate to it.
     *
     * @return the singleton instance, or {@code null} if none has been constructed yet
     */
    public static SteadyStateMaster<?, ?> getInstance() {
        return INSTANCE;
    }

    /** The optimization problem whose {@code evaluate()} method workers will invoke. */
    protected Problem problem;

    /**
     * Mutex that serializes on-demand task creation inside {@link #claimNextTask}.
     *
     * <p>When the pending queue is empty and the algorithm is still running, multiple
     * WebFlux threads may attempt to call {@link #createNewTask()} concurrently. This
     * lock ensures only one thread enters the task-creation critical section at a time.
     * After acquiring the lock each thread re-checks the queue ({@code double-checked
     * locking} pattern) to avoid generating a task if another thread has already done so.
     */
    private final Object taskCreationLock = new Object();

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Initializes the master, starts the Spring Boot REST server (via
     * {@link AbstractMaster}), and registers this instance as the singleton.
     *
     * @param host    the hostname or IP address to advertise in the discovery file
     * @param port    the HTTP port the REST server should listen on
     * @param problem the optimization problem to be solved
     */
    public SteadyStateMaster(String host, int port, Problem problem) {
        super(host, port);
        if (INSTANCE != null) {
            Log.warn("Overwriting existing SteadyStateMaster singleton. Only one master instance should be active per JVM.");
        }
        INSTANCE = this;
        this.problem = problem;
    }

    // ── REST API (called by TaskController) ───────────────────────────────────

    /**
     * Handles a worker's request for the next evaluation task.
     *
     * <p>This method is called from a {@code Schedulers.boundedElastic()} thread by
     * {@code TaskController} — never from the WebFlux event-loop thread — so blocking
     * is safe.
     *
     * <p>The dispatch sequence is:
     * <ol>
     *   <li>Try a non-blocking poll on the pending queue.</li>
     *   <li>If the queue is empty and the algorithm is still running, acquire
     *       {@link #taskCreationLock} and re-check (double-checked locking). If still
     *       empty, call {@link #createNewTask()} to generate fresh offspring.</li>
     *   <li>If still no task (e.g., stopping condition was met between steps 1 and 2),
     *       perform a timed poll for up to {@code timeoutSeconds} — this is the
     *       long-polling window.</li>
     *   <li>If a task was obtained, move it to {@link #inFlightTasks} and update the
     *       worker's registry entry.</li>
     * </ol>
     *
     * @param workerId       the unique identifier of the requesting worker
     * @param timeoutSeconds maximum time in seconds to wait for a task if none is
     *                       immediately available (long-poll window)
     * @return the next task for the worker to evaluate, or {@code null} if no task
     *         became available within {@code timeoutSeconds} (the HTTP layer returns
     *         {@code 204 No Content} in that case)
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T claimNextTask(String workerId, int timeoutSeconds) throws InterruptedException {
        T task = pendingTaskQueue.poll();

        if (task == null && stoppingConditionIsNotMet()) {
            synchronized (taskCreationLock) {
                // Re-check after acquiring lock: another thread may have created tasks
                task = pendingTaskQueue.poll();
                if (task == null && stoppingConditionIsNotMet()) {
                    task = createNewTask();
                }
            }
        }

        // Long-poll fallback: wait if the algorithm is still running but produced nothing
        if (task == null) {
            task = pendingTaskQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
        }

        final T finalTask = task;

        if (finalTask != null) {
            // Register the task as in-flight and mark the worker as busy
            inFlightTasks.put(finalTask.getIdentifier(), finalTask);
            workerRegistry.compute(workerId, (id, entry) -> {
                if (entry == null) entry = new WorkerEntry(workerId);
                entry.currentTaskId = finalTask.getIdentifier();
                entry.lastSeen = Instant.now();
                return entry;
            });
        }
        return finalTask;
    }

    // ── SteadyStateAlgorithm implementation ────────────────────────────────────────────

    /**
     * Enqueues all tasks in {@code initialTasks} into the pending queue.
     * Called once at algorithm startup by the default {@link SteadyStateAlgorithm#run()} loop.
     *
     * @param initialTasks the initial population of tasks to dispatch
     */
    @Override
    public void submitInitialTasks(List<T> initialTasks) {
        initialTasks.forEach(this::submitTask);
    }

    /**
     * Blocks until a worker delivers a result by posting to
     * {@code POST /api/v1/tasks/{id}/result}, which moves the task to
     * {@link #completedTaskQueue}.
     *
     * <p>Returns {@code null} (and restores the interrupt flag) if the thread is
     * interrupted while waiting.
     *
     * @return the completed task with objectives and constraints populated, or
     *         {@code null} if interrupted
     */
    @Override
    public T waitForComputedTask() {
        try {
            return completedTaskQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Processes a completed task result by integrating it into the algorithm state
     * (population, archive, etc.). Must be implemented by concrete algorithm subclasses.
     *
     * @param task the completed task returned by {@link #waitForComputedTask()}
     */
    @Override
    public abstract void processComputedTask(T task);

    /**
     * Adds a single task to the pending queue.
     *
     * @param task the task to enqueue
     */
    @Override
    public void submitTask(T task) {
        pendingTaskQueue.add(task);
    }

    /**
     * Creates the next evaluation task by applying crossover and mutation to the current
     * population. Must be implemented by concrete algorithm subclasses.
     *
     * <p>Implementations should be synchronized on the population and may enqueue a spare
     * offspring directly into {@link #pendingTaskQueue} to improve throughput when multiple
     * workers are available.
     *
     * @return a new unevaluated task
     */
    @Override
    public abstract T createNewTask();

    /**
     * Non-blocking poll on the pending task queue.
     *
     * @return the next pending task, or {@code null} if none is available immediately
     */
    @Override
    public T getPendingTask() {
        return pendingTaskQueue.poll();
    }

    /**
     * Returns {@code true} while the algorithm stopping condition has not been reached.
     * Must be implemented by concrete algorithm subclasses.
     *
     * @return {@code true} if the algorithm should continue
     */
    @Override
    public abstract boolean stoppingConditionIsNotMet();

    /**
     * Returns the number of registered workers that are currently idle (not evaluating
     * any task) and have sent a heartbeat within the last 45 seconds.
     *
     * <p>A worker is considered idle when its {@link WorkerEntry#currentTaskId} is
     * {@code -1}. This information can be used by callers to decide whether to pre-fill
     * the pending queue with extra tasks.
     *
     * @return the count of currently idle, recently-active workers
     */
    @Override
    public int numIdleWorkers() {
        Instant threshold = Instant.now().minusSeconds(Timings.WORKER_TIMEOUT_S);
        return (int) workerRegistry.values().stream().filter(e -> e.lastSeen.isAfter(threshold) && e.currentTaskId < 0).count();
    }
}

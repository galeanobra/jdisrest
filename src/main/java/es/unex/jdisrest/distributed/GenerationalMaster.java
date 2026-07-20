package es.unex.jdisrest.distributed;

import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import es.unex.jdisrest.util.Log;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST-based master for <em>generational</em> multi-objective evolutionary algorithms.
 *
 * <p>Extends {@link AbstractMaster} with the generational task-dispatch protocol and
 * implements {@link GenerationalAlgorithm} to drive the algorithm main loop. In the generational
 * model the algorithm proceeds in discrete rounds:
 * <ol>
 *   <li>The master pre-fills the pending queue with a full generation's tasks
 *       ({@link #submitTasks(List)}).</li>
 *   <li>Workers claim and evaluate tasks independently and in parallel.</li>
 *   <li>The master blocks until <em>all</em> {@link #populationSize} results arrive
 *       ({@link #waitForEvaluatedTasks()}).</li>
 *   <li>The algorithm applies evolutionary operators ({@link #evolution(List)}) and the
 *       cycle repeats.</li>
 * </ol>
 *
 * <h2>Singleton pattern</h2>
 * Spring Boot beans ({@code TaskController}, {@code WorkerController},
 * {@code WatchdogScheduler}) are initialized by the Spring IoC container before
 * application code constructs the master object. {@link #INSTANCE} provides a static
 * bridge that allows controllers to reach the active master via {@link #getInstance()}.
 * Only one {@code GenerationalMaster} should exist per JVM; a second construction overwrites
 * {@code INSTANCE} with a warning.
 *
 * @param <T> type of {@link ParallelTask} managed by this master
 * @param <R> type of the final algorithm result
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public abstract class GenerationalMaster<T extends ParallelTask<?>, R> extends AbstractMaster<T, R> implements GenerationalAlgorithm<T, R> {

    /**
     * Singleton reference shared with Spring beans. {@code volatile} to ensure safe
     * publication across threads.
     */
    private static volatile GenerationalMaster<?, ?> INSTANCE;

    /**
     * Returns the active {@code GenerationalMaster} singleton so that Spring beans can delegate to it.
     *
     * @return the singleton instance, or {@code null} if none has been constructed yet
     */
    public static GenerationalMaster<?, ?> getInstance() {
        return INSTANCE;
    }

    /** The optimization problem whose {@code evaluate()} method workers will invoke. */
    protected final Problem problem;

    /**
     * Number of individuals per generation. {@link #waitForEvaluatedTasks()} drains
     * exactly this many results from {@link #completedTaskQueue} each generation.
     */
    protected final int populationSize;

    /** Running total of individual evaluations completed across all generations. */
    protected int evaluations = 0;

    /**
     * Monotonically increasing task identifier counter. Each call to
     * {@link #createTaskIdentifier()} increments and returns this value, guaranteeing
     * that every task has a unique ID within the lifetime of the master process.
     */
    protected final AtomicInteger idCounter = new AtomicInteger(0);

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Initializes the master, starts the Spring Boot REST server (via
     * {@link AbstractMaster}), and registers this instance as the singleton.
     *
     * @param host           the hostname or IP address to advertise in the discovery file
     * @param port           the HTTP port the REST server should listen on
     * @param problem        the optimization problem to be solved
     * @param populationSize the number of individuals per generation
     */
    public GenerationalMaster(String host, int port, Problem problem, int populationSize) {
        super(host, port);
        if (INSTANCE != null) {
            Log.warn("Overwriting existing GenerationalMaster singleton. Only one master instance should be active per JVM.");
        }
        INSTANCE = this;
        this.problem = problem;
        this.populationSize = populationSize;
    }

    // ── REST API (called by TaskController) ───────────────────────────────────

    /**
     * Handles a worker's request for the next evaluation task via a blocking poll.
     *
     * <p>Unlike {@link SteadyStateMaster#claimNextTask}, this method never generates new tasks
     * on demand: generational algorithms pre-fill the pending queue via
     * {@link #submitTasks(List)} at the start of each generation, so the queue is either
     * already populated or the generation has not started yet.
     *
     * <p>This method is called from a {@code Schedulers.boundedElastic()} thread by
     * {@code TaskController} — never from the WebFlux event-loop thread — so blocking
     * is safe.
     *
     * @param workerId       the unique identifier of the requesting worker
     * @param timeoutSeconds maximum time in seconds to wait for a task if none is
     *                       immediately available (long-poll window)
     * @return the next pending task, or {@code null} if none arrived within
     *         {@code timeoutSeconds} (the HTTP layer returns {@code 204 No Content})
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T claimNextTask(String workerId, int timeoutSeconds) throws InterruptedException {
        T task = pendingTaskQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (task != null) {
            // Register the task as in-flight and mark the worker as busy
            inFlightTasks.put(task.getIdentifier(), task);
            workerRegistry.compute(workerId, (id, entry) -> {
                if (entry == null) entry = new WorkerEntry(workerId);
                entry.currentTaskId = task.getIdentifier();
                entry.lastSeen = Instant.now();
                return entry;
            });
        }
        return task;
    }

    // ── GenerationalAlgorithm implementation ─────────────────────────────────────────────

    /**
     * Adds all tasks in {@code tasks} to the pending queue so that workers can claim
     * them concurrently. Typically called once per generation with exactly
     * {@link #populationSize} tasks.
     *
     * @param tasks the batch of unevaluated tasks for the current generation
     */
    @Override
    public void submitTasks(List<T> tasks) {
        pendingTaskQueue.addAll(tasks);
    }

    /**
     * Blocks until all {@link #populationSize} evaluated results for the current
     * generation have been delivered to the master.
     *
     * <p>Results arrive asynchronously as workers post to
     * {@code POST /api/v1/tasks/{id}/result}, which moves completed tasks into
     * {@link #completedTaskQueue}. This method drains exactly {@code populationSize}
     * entries from that queue, incrementing {@link #evaluations} for each one.
     *
     * <p>Liveness is guaranteed by the watchdog ({@link #requeueOrphanTasks}): if a
     * worker dies while holding a task, the watchdog re-enqueues it within 30 seconds
     * so another worker can complete it. Without the watchdog, a single worker crash
     * could cause this method to block indefinitely.
     *
     * @return the list of fully evaluated tasks in completion order (not necessarily
     *         the original submission order); has exactly {@code populationSize} elements
     *         under normal operation
     */
    @Override
    public List<T> waitForEvaluatedTasks() {
        List<T> evaluated = new ArrayList<>(populationSize);
        try {
            for (int i = 0; i < populationSize; i++) {
                evaluated.add(completedTaskQueue.take());
                evaluations++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return evaluated;
    }

    /**
     * Allocates and returns a unique identifier for a new task.
     * Thread-safe; uses an {@link AtomicInteger} internally.
     *
     * @return a strictly increasing integer identifier
     */
    public int createTaskIdentifier() {
        return idCounter.getAndIncrement();
    }

    /**
     * Blocking take on the pending task queue. Waits indefinitely until a task is
     * available.
     *
     * @return the next pending task
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T getPendingTask() throws InterruptedException {
        return pendingTaskQueue.take();
    }

    /**
     * Returns {@code true} while the algorithm stopping condition has not been reached.
     * Must be implemented by concrete algorithm subclasses.
     *
     * @return {@code true} if the algorithm should continue
     */
    @Override
    public abstract boolean stoppingConditionIsNotMet();
}

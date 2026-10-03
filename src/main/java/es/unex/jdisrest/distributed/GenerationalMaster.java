package es.unex.jdisrest.distributed;

import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import es.unex.jdisrest.util.Log;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

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
 *       ({@link #waitForEvaluatedTasks()}), or fewer when tasks are discarded after failing
 *       too many evaluations or a stop is requested.</li>
 *   <li>The algorithm applies evolutionary operators ({@link #evolution(List)}) and the
 *       cycle repeats.</li>
 * </ol>
 *
 * <h2>Singleton pattern</h2>
 * Spring Boot beans ({@code TaskController}, {@code WorkerController},
 * {@code WatchdogScheduler}) are initialized by the Spring IoC container while the
 * {@link AbstractMaster} constructor starts the REST server, before this constructor registers
 * the master. {@link #INSTANCE} provides a static
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
     * Number of individuals per generation. {@link #waitForEvaluatedTasks()} waits until
     * this many tasks of the generation have been evaluated or discarded.
     */
    protected final int populationSize;

    /**
     * Running total of individual evaluations completed across all generations. Updated by
     * {@link #waitForEvaluatedTasks()} when the wait for a generation returns, not result by
     * result, so a stopping condition based on it cannot report the run finished (and send
     * the workers away with {@code 410 Gone}) while tasks of the generation are still pending.
     */
    protected int evaluations = 0;

    /**
     * Monotonically increasing task identifier counter. Each call to
     * {@link #createTaskIdentifier()} increments and returns this value, guaranteeing
     * that every task has a unique ID within the lifetime of the master process.
     */
    protected final AtomicInteger idCounter = new AtomicInteger(0);

    /**
     * How often {@link #waitForEvaluatedTasks()} re-checks the discards and the stop while it
     * waits. Neither adds anything to {@link #completedTaskQueue}, so a plain blocking
     * {@code take()} would never notice them.
     */
    private static final Duration WAIT_CHECK_INTERVAL = Duration.ofSeconds(1);

    /**
     * Tasks of the current generation discarded after failing too many times. They never
     * reach {@link #completedTaskQueue}, so {@link #waitForEvaluatedTasks()} stops waiting
     * for them. Incremented from REST threads by {@link #taskDiscarded}, reset by
     * {@link #submitTasks(List)}.
     */
    private final AtomicInteger discardedInGeneration = new AtomicInteger(0);

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

    /**
     * Creates a master without a REST server, not registered as the singleton, for the tests of
     * this package (see {@link AbstractMaster#AbstractMaster()}).
     *
     * @param problem        the optimization problem to be solved
     * @param populationSize the number of individuals per generation
     */
    GenerationalMaster(Problem problem, int populationSize) {
        super();
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
     * <p>This method is called from a virtual thread by {@code TaskController} (see
     * {@code MasterSpringApp.virtualThreadScheduler()}) — never from the WebFlux event-loop
     * thread — so blocking is safe, and many workers can wait in the long-poll at once.
     *
     * <p>A task obtained during the long-poll after a stop has been requested
     * ({@link #requestStop()}) is dropped instead of being put in flight, as in
     * {@link SteadyStateMaster#claimNextTask}: a task requeued after the stop, by a failed
     * evaluation or the watchdog, must not keep a waiting worker busy for nothing.
     *
     * <p>Until the algorithm is ready ({@link #isReady()}) it returns {@code null} at once. A task
     * handed out is registered with {@link #recordDispatch}, which also requeues the worker's
     * previous task if it is still in flight.
     *
     * @param workerId       the unique identifier of the requesting worker
     * @param timeoutSeconds maximum time in seconds to wait for a task if none is
     *                       immediately available (long-poll window)
     * @return the next pending task, or {@code null} if none arrived within
     *         {@code timeoutSeconds}, the algorithm is not ready yet or a stop has been
     *         requested (the HTTP layer returns {@code 204 No Content})
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public T claimNextTask(String workerId, int timeoutSeconds) throws InterruptedException {
        if (!isReady()) {
            return null;  // 204: the worker asks again a few seconds later
        }
        T task = pendingTaskQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (task != null && isStopRequested()) {
            return null;  // a stop arrived while the worker was waiting: hand nothing out
        }
        if (task != null) {
            // Register the task as in-flight and mark the worker as busy
            recordDispatch(workerId, task);
        }
        return task;
    }

    // ── GenerationalAlgorithm implementation ─────────────────────────────────────────────

    /**
     * Adds all tasks in {@code tasks} to the pending queue so that workers can claim
     * them concurrently.
     *
     * <p><strong>Must be called exactly once per generation, with exactly
     * {@link #populationSize} tasks</strong>, before {@link #waitForEvaluatedTasks()}. The call
     * resets the count of tasks discarded in the generation, and the wait ends when
     * {@code populationSize} tasks have been evaluated or discarded. Submitting a generation in
     * several batches would forget the discards of the earlier batches, and submitting fewer
     * tasks would leave the wait expecting results that never come; either way
     * {@code waitForEvaluatedTasks()} could block until a stop is requested.
     *
     * @param tasks the batch of unevaluated tasks for the current generation
     */
    @Override
    public void submitTasks(List<T> tasks) {
        discardedInGeneration.set(0);
        pendingTaskQueue.addAll(tasks);
    }

    /**
     * Blocks until all {@link #populationSize} evaluated results for the current
     * generation have been delivered to the master.
     *
     * <p>Results arrive asynchronously as workers post to
     * {@code POST /api/v1/tasks/{id}/result}, which moves completed tasks into
     * {@link #completedTaskQueue}. This method drains up to {@code populationSize}
     * entries from that queue and adds how many it took to {@link #evaluations} when it
     * returns.
     *
     * <p>Liveness is guaranteed by the watchdog ({@link #requeueOrphanTasks}): if a
     * worker dies while holding a task, the watchdog re-enqueues it once the worker has been
     * silent for {@link es.unex.jdisrest.util.Timings#WORKER_TIMEOUT_S} seconds (noticed within
     * another {@link es.unex.jdisrest.util.Timings#WATCHDOG_INTERVAL_S} seconds) so another
     * worker can complete it. Without the watchdog, a single worker crash
     * could cause this method to block indefinitely.
     *
     * <p>The wait ends early in three cases, each noticed within about a second:
     * <ul>
     *   <li>Tasks discarded after failing too many evaluations never arrive; the wait ends
     *       when the evaluated and the discarded tasks add up to {@code populationSize}.</li>
     *   <li>Once a stop has been requested ({@link #requestStop()}) no more results arrive;
     *       the wait returns the tasks it has already taken, leaving any result still
     *       queued unprocessed (as {@link SteadyStateMaster#waitForComputedTask()} does), and
     *       the default {@link GenerationalAlgorithm#run()} loop then ends.</li>
     *   <li>If the thread is interrupted, the tasks taken so far are returned and the
     *       interrupt flag is restored.</li>
     * </ul>
     *
     * @return the list of fully evaluated tasks in completion order (not necessarily
     *         the original submission order); has {@code populationSize} elements minus
     *         the tasks discarded in this generation, or fewer after a stop or an interrupt
     */
    @Override
    public List<T> waitForEvaluatedTasks() {
        List<T> evaluated = awaitGeneration(completedTaskQueue, populationSize,
                discardedInGeneration::get, this::isStopRequested, WAIT_CHECK_INTERVAL);
        evaluations += evaluated.size();
        return evaluated;
    }

    /**
     * The wait of {@link #waitForEvaluatedTasks()}, static so that it can be tested without
     * constructing a master (whose constructor starts the REST server).
     *
     * <p>Takes tasks from {@code completed} until {@code expected} tasks have been taken or
     * discarded, or {@code stopRequested} becomes {@code true}. Both suppliers are re-read
     * after every element and at least every {@code checkInterval}. An interrupt ends the
     * wait with the tasks taken so far and restores the interrupt flag.
     *
     * @param completed     queue of evaluated tasks
     * @param expected      tasks in the generation; at least 0
     * @param discarded     tasks of the generation discarded so far
     * @param stopRequested whether the run has been asked to stop
     * @param checkInterval longest wait on the queue between two checks; must be positive
     * @param <T>           type of the tasks
     * @return the tasks taken, in completion order
     */
    static <T> List<T> awaitGeneration(BlockingQueue<T> completed, int expected, IntSupplier discarded,
                                       BooleanSupplier stopRequested, Duration checkInterval) {
        List<T> evaluated = new ArrayList<>(expected);
        try {
            while (!stopRequested.getAsBoolean() && evaluated.size() + discarded.getAsInt() < expected) {
                T task = completed.poll(checkInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (task != null) {
                    evaluated.add(task);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return evaluated;
    }

    /**
     * Counts a discarded task against the current generation, so that
     * {@link #waitForEvaluatedTasks()} stops waiting for it. Runs before, and independently
     * of, {@link #onTaskDiscarded}, which subclasses remain free to override without calling
     * {@code super}.
     *
     * @param task the discarded task
     */
    @Override
    void taskDiscarded(T task) {
        discardedInGeneration.incrementAndGet();
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

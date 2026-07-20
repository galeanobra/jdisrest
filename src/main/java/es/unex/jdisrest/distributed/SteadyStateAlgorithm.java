package es.unex.jdisrest.distributed;

import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;

import java.util.List;

/**
 * Contract for <em>steady-state</em> distributed multi-objective evolutionary algorithms.
 *
 * <p>In the steady-state model, workers operate continuously: each worker fetches one task,
 * evaluates it, and immediately requests the next one. There is no synchronization barrier
 * between generations — new offspring can be evaluated while the previous results are still
 * being processed. This contrasts with the batch/generational model defined by
 * {@link GenerationalAlgorithm}, where the master waits for all individuals in a generation before
 * advancing.
 *
 * <p>The default {@link #run()} method encodes the high-level steady-state loop:
 * <ol>
 *   <li>Generate and submit an initial set of tasks.</li>
 *   <li>Initialize progress tracking.</li>
 *   <li>While the stopping condition is not met: block until any worker returns a result,
 *       process it, and update progress.</li>
 * </ol>
 * Concrete implementations provide the domain-specific logic (crossover, mutation, archive
 * management, etc.) through the abstract methods below.
 *
 * @param <T> type of {@link ParallelTask} being computed by the workers
 * @param <R> type of the final result returned by {@link #getResult()}
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public interface SteadyStateAlgorithm<T extends ParallelTask<?>, R> {

    /**
     * Adds all tasks in {@code tasks} to the pending queue so workers can claim them.
     * Called once at startup with the list produced by {@link #createInitialTasks()}.
     *
     * @param tasks the initial batch of tasks to enqueue
     */
    void submitInitialTasks(List<T> tasks);

    /**
     * Creates the first generation of tasks before any evaluation has taken place.
     *
     * <p>Implementations may load a warm-start population from a file (e.g., {@code iVAR.csv})
     * or generate random solutions using the problem's {@code createSolution()} factory method.
     *
     * @return a non-empty list of tasks ready to be dispatched to workers
     */
    List<T> createInitialTasks();

    /**
     * Blocks until a worker has delivered a computed task result.
     *
     * <p>This call dequeues one entry from the {@code completedTaskQueue}. It will block
     * indefinitely if no result arrives, so the watchdog mechanism must ensure that tasks
     * assigned to dead workers are eventually re-evaluated and re-delivered.
     *
     * @return the completed task, whose solution already has objectives and constraints set
     */
    T waitForComputedTask();

    /**
     * Integrates a completed task result into the algorithm state (population, archive, etc.).
     *
     * <p>Called in the main algorithm thread immediately after {@link #waitForComputedTask()}
     * returns. Implementations typically add the solution to an archive, perform environmental
     * selection to maintain population size, and generate the next offspring task via
     * {@link #createNewTask()}.
     *
     * @param task the completed task returned by {@link #waitForComputedTask()}
     */
    void processComputedTask(T task);

    /**
     * Enqueues a single task so that an idle worker can claim it via
     * {@code GET /api/v1/tasks/next}.
     *
     * @param task the task to enqueue
     */
    void submitTask(T task);

    /**
     * Creates the next task by applying crossover and mutation to the current population.
     *
     * <p>Implementations should be thread-safe with respect to the population because
     * {@link SteadyStateMaster#claimNextTask} may call this method concurrently from multiple
     * WebFlux worker threads. Generating two offspring at once and enqueuing the spare
     * in {@code pendingTaskQueue} is a common pattern to amortise the cost of selection.
     *
     * @return a new, unevaluated task ready to be dispatched to a worker
     */
    T createNewTask();

    /**
     * Returns the next task from the pending queue without blocking (non-destructive poll).
     * Returns {@code null} immediately if the queue is empty.
     *
     * @return the next pending task, or {@code null} if none is available
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    T getPendingTask() throws InterruptedException;

    /**
     * Returns {@code true} while the algorithm should continue iterating.
     * The loop in {@link #run()} exits as soon as this method returns {@code false}.
     *
     * @return {@code true} if the stopping condition has not yet been reached
     */
    boolean stoppingConditionIsNotMet();

    /**
     * Initializes algorithm progress counters and notifies observers before the main loop
     * starts. Called once, immediately after {@link #submitInitialTasks(List)}.
     */
    void initProgress();

    /**
     * Increments algorithm progress counters and notifies observers after each task result
     * has been processed. May also trigger periodic trace saves.
     */
    void updateProgress();

    /**
     * Returns the number of workers that are currently registered and idle (not evaluating
     * any task). Used to decide how eagerly to pre-fill the pending queue.
     *
     * @return the count of idle, recently-seen workers
     */
    int numIdleWorkers();

    /**
     * Returns the algorithm's final result once the stopping condition is met.
     *
     * @return the best solutions found, typically a subset of the archive
     */
    R getResult();

    /**
     * Default steady-state main loop.
     *
     * <p>The sequence is:
     * <ol>
     *   <li>Create and enqueue initial tasks.</li>
     *   <li>Initialize progress tracking.</li>
     *   <li>Repeat until the stopping condition is met:
     *     <ol>
     *       <li>Block until a worker returns a result ({@link #waitForComputedTask()}).</li>
     *       <li>Process the result ({@link #processComputedTask(ParallelTask)}).</li>
     *       <li>Update progress counters ({@link #updateProgress()}).</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * <p>Concrete classes may override this method to inject additional logic (e.g., recording
     * the wall-clock start time), but must call {@code super.run()} or replicate this loop.
     */
    default void run() {
        List<T> initialTasks = createInitialTasks();
        submitInitialTasks(initialTasks);

        initProgress();
        while (stoppingConditionIsNotMet()) {
            T computedTask = waitForComputedTask();
            processComputedTask(computedTask);
            updateProgress();
        }
    }
}

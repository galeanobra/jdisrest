package es.unex.jdisrest.distributed;

import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;

import java.util.List;

/**
 * Contract for <em>generational</em> distributed multi-objective evolutionary algorithms.
 *
 * <p>In the generational model the algorithm proceeds in discrete rounds, or
 * <em>generations</em>. During each generation the master submits a fixed batch of tasks
 * (one per individual), waits for <strong>all</strong> of them to be evaluated, and only
 * then applies selection and variation operators to produce the next generation. This
 * synchronization barrier distinguishes the generational model from the steady-state model
 * defined by {@link SteadyStateAlgorithm}, where new offspring are dispatched immediately as results
 * arrive.
 *
 * <p>The default {@link #run()} method encodes the high-level generational loop:
 * <ol>
 *   <li>Create and submit the initial population tasks.</li>
 *   <li>Wait for all initial evaluations to complete.</li>
 *   <li>While no stop has been requested and the stopping condition is not met: call
 *       {@link #evolution(List)}, which applies selection and variation, submits the
 *       offspring ({@link #submitTasks(List)}) and waits for them
 *       ({@link #waitForEvaluatedTasks()}) itself.</li>
 * </ol>
 *
 * <p>A generation may come back with fewer results than tasks submitted: tasks discarded
 * after failing too many evaluations never return, and a stop ends the wait early (see
 * {@link #waitForEvaluatedTasks()}). Implementations of {@link #evolution(List)} must
 * therefore not assume one result per submitted task or index results by position.
 *
 * @param <T> type of {@link ParallelTask} being computed by the workers
 * @param <R> type of the final result returned by {@link #getResult()}
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public interface GenerationalAlgorithm<T extends ParallelTask<?>, R> {

    /**
     * Creates the initial population as a list of unevaluated tasks.
     *
     * <p>Each task wraps one randomly generated (or warm-started) solution. The list size
     * is typically equal to {@code populationSize}.
     *
     * @return a list of tasks representing the initial population, ready for submission
     */
    List<T> createInitialTasks();

    /**
     * Adds all tasks in {@code tasks} to the pending queue so that workers can claim them
     * concurrently via {@code GET /api/v1/tasks/next}.
     *
     * <p>Unlike the steady-state counterpart, this method submits a full generation's
     * worth of tasks at once. The ordering within the list is not significant.
     *
     * @param tasks the batch of tasks to enqueue; must not be {@code null}
     */
    void submitTasks(List<T> tasks);

    /**
     * Blocks until every task submitted for the current generation has been evaluated and
     * its result delivered to the master.
     *
     * <p>This call drains up to {@code populationSize} entries from the
     * {@code completedTaskQueue}: fewer when tasks of the generation were discarded after
     * failing too many evaluations, which never arrive, or when a stop is requested
     * ({@link #isStopRequested()}), after which it returns the tasks it has already taken.
     * Liveness is guaranteed by the watchdog: if a worker dies while holding a task, the
     * watchdog re-enqueues that task so another worker can complete it, preventing this
     * method from blocking forever.
     *
     * @return the list of fully evaluated tasks in the order they were completed
     *         (not necessarily the original submission order); possibly shorter than the
     *         generation, as explained above
     */
    List<T> waitForEvaluatedTasks();

    /**
     * Applies one full generation of evolutionary operators (selection, crossover, mutation)
     * to the evaluated population, submits the resulting offspring tasks and waits for them.
     *
     * <p>This method is responsible for transforming the population, calling
     * {@link #submitTasks(List)} with the new offspring <em>and</em> waiting for them with
     * {@link #waitForEvaluatedTasks()} before returning: the default {@link #run()} does not
     * wait between two calls, and passes the same list every time, so update it in place.
     *
     * @param population the fully evaluated population from the previous generation
     */
    void evolution(List<T> population);

    /**
     * Returns {@code true} while the algorithm should continue iterating.
     * The loop in {@link #run()} exits as soon as this method returns {@code false}.
     *
     * @return {@code true} if the stopping condition has not yet been reached
     */
    boolean stoppingConditionIsNotMet();

    /**
     * Returns whether the run has been asked to finish before its stopping criterion
     * ({@code POST /api/v1/stop}). The loop in {@link #run()} exits as soon as this method
     * returns {@code true}, whatever {@link #stoppingConditionIsNotMet()} says, so that a stop
     * cannot leave {@code run()} submitting generations that nobody evaluates.
     *
     * <p>{@code false} by default, for implementations that cannot be stopped;
     * {@link AbstractMaster#isStopRequested()} overrides it for every master.
     *
     * @return {@code true} once a stop has been requested
     */
    default boolean isStopRequested() {
        return false;
    }

    /**
     * Returns the algorithm's final result once the stopping condition is met.
     *
     * @return the best solutions found (e.g., the non-dominated archive or the final
     *         population)
     */
    R getResult();

    /**
     * Default generational main loop.
     *
     * <p>The sequence is:
     * <ol>
     *   <li>Create and submit the initial population as tasks.</li>
     *   <li>Block until all initial evaluations complete ({@link #waitForEvaluatedTasks()}).</li>
     *   <li>Repeat until a stop is requested ({@link #isStopRequested()}) or the stopping
     *       condition is met:
     *     <ol>
     *       <li>Call {@link #evolution(List)} with the population list; it submits the next
     *           generation and waits for it itself.</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * <p>Note that {@code evolution()} receives the evaluated population and is expected to
     * update it in place as well as submit and wait for the offspring tasks; the same list is
     * passed on every call.
     */
    default void run() {
        List<T> initialTasks = createInitialTasks();
        submitTasks(initialTasks);
        List<T> population = waitForEvaluatedTasks();
        while (!isStopRequested() && stoppingConditionIsNotMet()) {
            evolution(population);
        }
    }
}

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
 *   <li>While the stopping condition is not met: apply evolution (selection + variation +
 *       task submission), then wait for the full generation to be evaluated.</li>
 * </ol>
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
     * <p>This call drains exactly {@code populationSize} entries from the
     * {@code completedTaskQueue}. Liveness is guaranteed by the watchdog: if a worker dies
     * while holding a task, the watchdog re-enqueues that task so another worker can
     * complete it, preventing this method from blocking forever.
     *
     * @return the list of fully evaluated tasks in the order they were completed
     *         (not necessarily the original submission order)
     */
    List<T> waitForEvaluatedTasks();

    /**
     * Applies one full generation of evolutionary operators (selection, crossover, mutation)
     * to the evaluated population and submits the resulting offspring tasks for the next
     * generation.
     *
     * <p>This method is responsible for both transforming the population <em>and</em> calling
     * {@link #submitTasks(List)} with the new offspring so the loop can continue. After this
     * method returns, the caller blocks again in {@link #waitForEvaluatedTasks()}.
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
     *   <li>Repeat until the stopping condition is met:
     *     <ol>
     *       <li>Apply evolutionary operators and submit next-generation tasks
     *           ({@link #evolution(List)}).</li>
     *       <li>Block until the full generation is evaluated
     *           ({@link #waitForEvaluatedTasks()}).</li>
     *     </ol>
     *   </li>
     * </ol>
     *
     * <p>Note that {@code evolution()} receives the evaluated population and is expected to
     * update it in place as well as submit the offspring tasks; the updated reference is
     * then passed back into the next {@code evolution()} call.
     */
    default void run() {
        List<T> initialTasks = createInitialTasks();
        submitTasks(initialTasks);
        List<T> population = waitForEvaluatedTasks();
        while (stoppingConditionIsNotMet()) {
            evolution(population);
        }
    }
}

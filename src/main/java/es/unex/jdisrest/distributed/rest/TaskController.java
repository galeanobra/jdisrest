package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.rest.dto.TaskErrorPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskResultPayload;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller that manages the task lifecycle between the master algorithm
 * and the remote worker processes.
 *
 * <p>All three endpoints ({@code /next}, {@code /result}, {@code /error}) share
 * the same design principle: the reactive pipeline is created on the Netty
 * event-loop thread but the actual work is dispatched to
 * {@link Schedulers#boundedElastic()} (or to the injected virtual-thread
 * scheduler), so blocking calls never stall the event loop.
 *
 * <p>Task lifecycle:
 * <ol>
 *   <li>Worker calls {@code GET /next} — master moves a task from
 *       {@code pendingTaskQueue} to {@code inFlightTasks} and returns it.</li>
 *   <li>Worker evaluates the solution and calls {@code POST /{id}/result} —
 *       master writes objectives/constraints directly into the solution object
 *       still held in {@code inFlightTasks}, then moves the task to
 *       {@code completedTaskQueue}.</li>
 *   <li>If evaluation fails, worker calls {@code POST /{id}/error} — master
 *       requeues the task to {@code pendingTaskQueue} immediately, without
 *       waiting for the watchdog timeout.</li>
 * </ol>
 *
 * @see MasterFacade
 * @see WatchdogScheduler
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    /** Reactor scheduler backed by Java 21 virtual threads; used for blocking calls. */
    private final Scheduler scheduler;

    /**
     * Constructs the controller with the virtual-thread scheduler bean.
     *
     * @param virtualThreadScheduler the {@link Scheduler} bean declared in
     *                               {@link MasterSpringApp#virtualThreadScheduler()}
     */
    public TaskController(Scheduler virtualThreadScheduler) {
        this.scheduler = virtualThreadScheduler;
    }

    /**
     * Long-poll endpoint: a worker requests the next task to evaluate.
     *
     * <p>The call blocks on {@link MasterFacade#claimNextTask} for up to
     * {@code timeoutSeconds} (currently 30 s) waiting for a task to become
     * available in {@code pendingTaskQueue}. It always runs on
     * {@link Schedulers#boundedElastic()} — never on the Netty event-loop thread.
     *
     * <p>Response codes:
     * <ul>
     *   <li>{@code 200 OK} — task payload returned; worker should evaluate and call
     *       {@code POST /{id}/result}.</li>
     *   <li>{@code 204 No Content} — no task arrived within the timeout; worker
     *       should immediately retry the long-poll.</li>
     *   <li>{@code 410 Gone} — the algorithm has finished; worker should shut
     *       down.</li>
     * </ul>
     *
     * @param workerId opaque identifier for the requesting worker (e.g. {@code "worker-01"})
     * @return a {@link Mono} emitting a {@link ResponseEntity} containing a
     *         {@link TaskPayload}, or an empty/410 response as described above
     */
    @GetMapping("/next")
    public Mono<ResponseEntity<TaskPayload>> getNextTask(@RequestParam("workerId") String workerId) {
        return Mono.<ResponseEntity<TaskPayload>>fromCallable(() -> {
            // Return 410 immediately if the algorithm has already finished.
            if (MasterFacade.isFinished()) {
                return ResponseEntity.<TaskPayload>status(410).build();
            }
            // Block up to TASK_LONGPOLL_S seconds waiting for a task; returns null on timeout.
            ParallelTask<Solution<?>> task = MasterFacade.claimNextTask(workerId, Timings.TASK_LONGPOLL_S);
            if (task == null) {
                // No task within the timeout window; worker should retry.
                return ResponseEntity.<TaskPayload>noContent().build();
            }
            // Flatten the solution's variables and (for CompositeSolution) compute
            // per-segment sizes so the worker can reconstruct segment boundaries.
            List<Integer> variables  = flattenVariables(task.getContents());
            List<Integer> segSizes   = segmentSizes(task.getContents());
            return ResponseEntity.ok(new TaskPayload(task.getIdentifier(), variables, segSizes));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Endpoint for a worker to submit evaluation results (objectives and constraints).
     *
     * <p>The solution object lives on the master throughout the entire evaluation
     * cycle — workers only send back numeric results, never variables. This method
     * writes the returned objectives and constraints directly into the
     * {@link Solution} instance that is still stored in {@code inFlightTasks},
     * then calls {@link MasterFacade#submitResult} to move the task to
     * {@code completedTaskQueue}, unblocking the algorithm thread that is waiting
     * in {@code waitForComputedTask()} or {@code waitForEvaluatedTasks()}.
     *
     * <p>Response codes:
     * <ul>
     *   <li>{@code 200 OK} — result accepted and recorded.</li>
     *   <li>{@code 404 Not Found} — {@code taskId} is no longer in
     *       {@code inFlightTasks}; the watchdog already requeued it because the
     *       worker took too long. The result is discarded.</li>
     * </ul>
     *
     * @param taskId the identifier of the task being completed (path variable)
     * @param result the evaluation result payload containing objectives, optional
     *               constraints, the reporting worker id, and evaluation wall-time
     * @return a {@link Mono} emitting a {@link ResponseEntity} with no body
     */
    @PostMapping("/{taskId}/result")
    public Mono<ResponseEntity<Void>> submitResult(
            @PathVariable("taskId") long taskId,
            @RequestBody TaskResultPayload result) {
        return Mono.<ResponseEntity<Void>>fromCallable(() -> {
            // Look up the task; it may have been requeued by the watchdog if the
            // worker was silent for more than Timings.WORKER_TIMEOUT_S.
            ParallelTask<Solution<?>> task = MasterFacade.inFlightTasks().get(taskId);
            if (task == null) {
                Log.warn("Result for unknown/expired taskId: " + taskId + " from " + result.workerId());
                return ResponseEntity.<Void>notFound().build();
            }

            // Write objectives directly into the solution object held by the master.
            // The worker may also send back a modified decision vector (Lamarckian
            // repair/local-search). When present, overwrite the solution's variables
            // first so objectives/constraints stay consistent with the genes.
            Solution<?> solution = task.getContents();

            if (result.variables() != null && !result.variables().isEmpty()) {
                applyVariables(solution, result.variables());
            }

            double[] objectives = solution.objectives();
            for (int i = 0; i < result.objectives().size() && i < objectives.length; i++) {
                objectives[i] = result.objectives().get(i);
            }

            // Constraints are optional; skip the write if the problem has none.
            if (result.constraints() != null && !result.constraints().isEmpty()) {
                double[] constraints = solution.constraints();
                for (int i = 0; i < result.constraints().size() && i < constraints.length; i++) {
                    constraints[i] = result.constraints().get(i);
                }
            }

            // Move the task from inFlightTasks → completedTaskQueue.
            // Returns false if the watchdog already removed it since our null-check above.
            boolean accepted = MasterFacade.submitResult(taskId, result.workerId());
            return accepted ? ResponseEntity.<Void>ok().build() : ResponseEntity.<Void>notFound().build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Endpoint for a worker to report that evaluation of a task has failed.
     *
     * <p>On receiving this call, the master immediately requeues the task back into
     * {@code pendingTaskQueue} via {@link MasterFacade#requeueInFlightTask}, so
     * another available worker can retry it. This is faster than waiting for the
     * {@link WatchdogScheduler} to detect a silent worker after its 45-second
     * timeout.
     *
     * <p>Always returns {@code 200 OK} — even if the {@code taskId} has already
     * been requeued, the outcome is the same and the error has been logged.
     *
     * @param taskId the identifier of the failed task (path variable)
     * @param error  payload containing the reporting worker id and a human-readable
     *               error message for logging
     * @return a {@link Mono} emitting a {@code 200 OK} {@link ResponseEntity}
     */
    @PostMapping("/{taskId}/error")
    public Mono<ResponseEntity<Void>> reportError(
            @PathVariable("taskId") long taskId,
            @RequestBody TaskErrorPayload error) {
        return Mono.<ResponseEntity<Void>>fromCallable(() -> {
            Log.warn("[task-" + taskId + "] Evaluation error from " + error.workerId()
                + ": " + error.errorMessage());
            // Requeue immediately so the task is not lost until the next watchdog cycle.
            MasterFacade.requeueInFlightTask(taskId);
            return ResponseEntity.<Void>ok().build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Variable serialization helpers ────────────────────────────────────────

    /**
     * Extracts the decision variables from a solution as a flat list of integers.
     *
     * <p>Two cases are handled:
     * <ul>
     *   <li><strong>{@link org.uma.jmetal.solution.integersolution.IntegerSolution}</strong>
     *       (i.e. anything that is <em>not</em> a {@link CompositeSolution}): the
     *       solution's variable list is returned directly — no copying.</li>
     *   <li><strong>{@link CompositeSolution}</strong>: each component solution's
     *       variables are appended in order, producing the concatenated vector
     *       {@code [seg0 | seg1 | seg2 | ...]}. Workers that do not need to
     *       distinguish segments can treat this as a single flat array.</li>
     * </ul>
     *
     * @param solution the jMetal solution whose variables are to be serialized
     * @return a flat {@link List} of integer decision-variable values
     */
    @SuppressWarnings("unchecked")
    static List<Integer> flattenVariables(Solution<?> solution) {
        if (solution instanceof CompositeSolution composite) {
            List<Integer> flat = new ArrayList<>();
            // Append each component's variable list in declaration order.
            for (Object component : composite.variables()) {
                flat.addAll(((Solution<Integer>) component).variables());
            }
            return flat;
        }
        // IntegerSolution: return the variable list as-is (no copy needed).
        return (List<Integer>) solution.variables();
    }

    /**
     * Inverse of {@link #flattenVariables(Solution)}: writes a flat list of
     * integer values back into the variables of a {@link Solution}, splitting
     * by component when the target is a {@link CompositeSolution}.
     *
     * <p>The expected layout matches what was originally sent in
     * {@link TaskPayload#variables()}: a single concatenated vector
     * {@code [seg0 | seg1 | ...]} for composites, or the variable list as-is
     * for plain integer solutions. Components/values past either side's
     * length are silently ignored, mirroring the lenient policy used for
     * objectives and constraints.
     *
     * @param solution  the master-held solution whose variables are to be overwritten
     * @param variables the flat decision vector returned by the worker
     */
    @SuppressWarnings("unchecked")
    static void applyVariables(Solution<?> solution, List<Integer> variables) {
        if (solution instanceof CompositeSolution composite) {
            int idx = 0;
            for (Object component : composite.variables()) {
                List<Integer> segVars = ((Solution<Integer>) component).variables();
                int n = segVars.size();
                for (int i = 0; i < n && idx < variables.size(); i++, idx++) {
                    segVars.set(i, variables.get(idx));
                }
            }
            return;
        }
        List<Integer> solVars = ((Solution<Integer>) solution).variables();
        for (int i = 0; i < solVars.size() && i < variables.size(); i++) {
            solVars.set(i, variables.get(i));
        }
    }

    /**
     * Returns per-segment variable counts for a {@link CompositeSolution}, or
     * {@code null} for a plain {@link org.uma.jmetal.solution.integersolution.IntegerSolution}.
     *
     * <p>When the value is {@code null}, Jackson omits the {@code segmentSizes}
     * field from the JSON response entirely (via {@code @JsonInclude(NON_NULL)}
     * on {@link es.unex.jdisrest.distributed.rest.dto.TaskPayload}), keeping the payload
     * backwards-compatible with workers that were built before composite
     * encoding was introduced.
     *
     * <p>Example: a {@link CompositeSolution} with three components of 3 249
     * variables each produces {@code [3249, 3249, 3249]}.
     *
     * @param solution the jMetal solution to inspect
     * @return a list whose {@code i}-th element is the number of variables in
     *         component {@code i}, or {@code null} for non-composite solutions
     */
    static List<Integer> segmentSizes(Solution<?> solution) {
        if (solution instanceof CompositeSolution composite) {
            return composite.variables().stream()
                .map(c -> ((Solution<?>) c).variables().size())
                .collect(Collectors.toList());
        }
        // Returning null causes Jackson to omit the field from the JSON output.
        return null;
    }
}

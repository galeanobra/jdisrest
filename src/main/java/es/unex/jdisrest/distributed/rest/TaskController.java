package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.rest.dto.TaskErrorPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskRejectionPayload;
import es.unex.jdisrest.distributed.rest.dto.TaskResultPayload;
import es.unex.jdisrest.util.SolutionVariables;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

import java.util.List;
import java.util.Map;
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
 *       master validates the payload, writes objectives/constraints (and the
 *       optional repaired variables) directly into the solution object still
 *       held in {@code inFlightTasks}, then moves the task to
 *       {@code completedTaskQueue}.</li>
 *   <li>If evaluation fails, worker calls {@code POST /{id}/error} — master
 *       requeues the task to {@code pendingTaskQueue} immediately, without
 *       waiting for the watchdog timeout. A result that fails validation (or
 *       cannot be decoded) follows the same path.</li>
 * </ol>
 *
 * <p>Variable encodings: the decision vector travels as a flat list of JSON
 * numbers whatever the jMetal solution type ({@code IntegerSolution},
 * {@code DoubleSolution} or a {@code CompositeSolution} mixing both). The
 * conversion in both directions is delegated to {@link SolutionVariables},
 * which always converts by the type of the destination variable rather than
 * trusting the numeric type found in the JSON.
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
            try {
                return ResponseEntity.ok(toPayload(task.getIdentifier(), task.getContents()));
            } catch (IllegalArgumentException e) {
                // Unsupported solution type: the task is already in flight, so put it
                // back instead of stranding it, and make the misconfiguration visible.
                // SteadyStateEvolutionaryAlgorithm.run() rejects such problems up front;
                // this only guards other masters.
                Log.error("[task-" + task.getIdentifier() + "] Cannot serialize solution: " + e.getMessage());
                MasterFacade.requeueInFlightTask(task.getIdentifier());
                return ResponseEntity.<TaskPayload>internalServerError().build();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Endpoint for a worker to submit evaluation results (objectives and constraints).
     *
     * <p>The solution object lives on the master throughout the entire evaluation
     * cycle. This method validates the payload (see {@link #rejectionReason}),
     * writes the returned objectives, constraints and — when present — repaired
     * variables directly into the {@link Solution} instance that is still stored
     * in {@code inFlightTasks}, then calls {@link MasterFacade#submitResult} to
     * move the task to {@code completedTaskQueue}, unblocking the algorithm thread
     * that is waiting in {@code waitForComputedTask()} or {@code waitForEvaluatedTasks()}.
     *
     * <p>Response codes:
     * <ul>
     *   <li>{@code 200 OK} — result accepted and recorded.</li>
     *   <li>{@code 404 Not Found} — {@code taskId} is no longer in
     *       {@code inFlightTasks}; the watchdog already requeued it because the
     *       worker took too long. The result is discarded.</li>
     *   <li>{@code 422 Unprocessable Content} — the payload is well-formed JSON
     *       but cannot be applied: wrong number of objectives or constraints, a
     *       {@code null}, {@code NaN} or infinite value, or a decision vector that
     *       does not fit the solution. The task is requeued as if the worker had
     *       reported an evaluation error, and the body is a
     *       {@link TaskRejectionPayload} explaining the rejection.</li>
     * </ul>
     *
     * @param taskId the identifier of the task being completed (path variable)
     * @param result the evaluation result payload containing objectives, optional
     *               constraints, the reporting worker id, and evaluation wall-time
     * @return a {@link Mono} emitting a {@link ResponseEntity} with no body, or a
     *         {@link TaskRejectionPayload} body on {@code 422}
     */
    @PostMapping("/{taskId}/result")
    public Mono<ResponseEntity<TaskRejectionPayload>> submitResult(
            @PathVariable("taskId") long taskId,
            @RequestBody TaskResultPayload result) {
        return Mono.<ResponseEntity<TaskRejectionPayload>>fromCallable(() -> {
            // Look up the task; it may have been requeued by the watchdog if the
            // worker was silent for more than Timings.WORKER_TIMEOUT_S.
            ParallelTask<Solution<?>> task = MasterFacade.inFlightTasks().get(taskId);
            if (task == null) {
                Log.warn("Result for unknown/expired taskId: " + taskId + " from " + result.workerId());
                return ResponseEntity.<TaskRejectionPayload>notFound().build();
            }

            Solution<?> solution = task.getContents();

            // Validate everything before writing anything, so a rejected result
            // leaves the master-held solution untouched.
            String reason = rejectionReason(result, solution);
            if (reason != null) {
                Log.warn("[task-" + taskId + "] Invalid result from " + result.workerId()
                    + ": " + reason + " — requeueing");
                MasterFacade.requeueInFlightTask(taskId);
                return ResponseEntity.status(422).body(new TaskRejectionPayload(taskId, reason));
            }

            // The worker may send back a modified decision vector (Lamarckian
            // repair/local-search). Overwrite the variables first so objectives
            // and constraints stay consistent with the genes.
            if (result.variables() != null && !result.variables().isEmpty()) {
                SolutionVariables.apply(solution, result.variables());
            }

            double[] objectives = solution.objectives();
            for (int i = 0; i < objectives.length; i++) {
                objectives[i] = result.objectives().get(i);
            }

            double[] constraints = solution.constraints();
            for (int i = 0; i < constraints.length; i++) {
                constraints[i] = result.constraints().get(i);
            }

            // Move the task from inFlightTasks → completedTaskQueue.
            // Returns false if the watchdog already removed it since our null-check above.
            boolean accepted = MasterFacade.submitResult(taskId, result.workerId());
            return accepted
                ? ResponseEntity.<TaskRejectionPayload>ok().build()
                : ResponseEntity.<TaskRejectionPayload>notFound().build();
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

    /**
     * Handles requests Spring rejects before the handler method runs: a body that
     * cannot be decoded (e.g. a bare {@code NaN} or {@code Infinity} token emitted
     * by Python's {@code json.dumps}, which is not valid JSON — {@code 400}), a
     * wrong {@code Content-Type} ({@code 415}) and similar client errors.
     *
     * <p>Without this handler Spring would answer with the error status and the
     * task would stay in {@code inFlightTasks} forever: the worker is alive and
     * keeps sending heartbeats, so the watchdog never requeues it. When the
     * request targets {@code /{taskId}/result} or {@code /{taskId}/error}, the
     * task is requeued as if the worker had reported an evaluation error. The
     * response keeps Spring's status code and carries a
     * {@link TaskRejectionPayload} explaining what was wrong.
     *
     * @param ex       the failure raised by Spring while resolving the request
     * @param exchange the current exchange, used to recover the task id from the
     *                 matched URI template
     * @return the same status Spring would have sent, with a
     *         {@link TaskRejectionPayload} body
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<TaskRejectionPayload>> onRejectedRequest(
            ResponseStatusException ex, ServerWebExchange exchange) {
        String reason = "Rejected request: " + rootMessage(ex);
        Long taskId = taskIdOf(exchange);
        if (taskId == null) {
            return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(new TaskRejectionPayload(-1, reason)));
        }
        return Mono.<ResponseEntity<TaskRejectionPayload>>fromCallable(() -> {
            Log.warn("[task-" + taskId + "] " + reason + " — requeueing");
            MasterFacade.requeueInFlightTask(taskId);
            return ResponseEntity.status(ex.getStatusCode()).body(new TaskRejectionPayload(taskId, reason));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Task id of the matched {@code /{taskId}/...} route, taken from the URI
     * template variables Spring stores on the exchange during handler mapping.
     *
     * @param exchange the current exchange
     * @return the task id, or {@code null} if the route has none or it is not a number
     */
    private static Long taskIdOf(ServerWebExchange exchange) {
        Map<String, String> vars = exchange.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return null;
        String raw = vars.get("taskId");
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Payload helpers ───────────────────────────────────────────────────────

    /**
     * Builds the {@link TaskPayload} for a solution.
     *
     * <p>{@code encoding} and {@code segmentEncodings} are set only when at least
     * one variable is real-encoded, so integer problems (flat or composite) keep
     * producing exactly the JSON emitted by earlier versions.
     *
     * @param taskId   the task identifier
     * @param solution the solution to serialize
     * @return the payload to send to the worker
     * @throws IllegalArgumentException if the solution type is unsupported
     */
    static TaskPayload toPayload(long taskId, Solution<?> solution) {
        List<Number> variables = SolutionVariables.flatten(solution);
        List<Integer> segSizes = segmentSizes(solution);
        String encoding = SolutionVariables.wireEncoding(solution);
        if (SolutionVariables.Encoding.INT.wireName().equals(encoding)) {
            // Legacy format: omit both encoding fields for integer-only problems.
            return new TaskPayload(taskId, variables, segSizes, null, null);
        }
        List<String> segEncodings = SolutionVariables.wireNames(SolutionVariables.segmentEncodings(solution));
        return new TaskPayload(taskId, variables, segSizes, encoding, segEncodings);
    }

    /**
     * Checks whether a result can be applied to a solution.
     *
     * <p>Rules: {@code objectives} must hold exactly {@code solution.objectives().length}
     * values and {@code constraints} exactly {@code solution.constraints().length}
     * ({@code null} counts as empty); every value must be non-null and finite;
     * {@code variables}, when present and non-empty, must be convertible to the
     * solution's variables (see {@link SolutionVariables#convert}).
     *
     * @param result   the payload posted by the worker
     * @param solution the master-held solution the result targets
     * @return {@code null} if the result is valid, otherwise the reason it is not
     */
    static String rejectionReason(TaskResultPayload result, Solution<?> solution) {
        String reason = checkValues("objectives", result.objectives(), solution.objectives().length);
        if (reason != null) return reason;
        reason = checkValues("constraints", result.constraints(), solution.constraints().length);
        if (reason != null) return reason;
        if (result.variables() != null && !result.variables().isEmpty()) {
            try {
                SolutionVariables.convert(solution, result.variables());
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    /**
     * Validates one numeric list of the result payload.
     *
     * @param field    field name, for the message
     * @param values   the list ({@code null} counts as empty)
     * @param expected the number of values the problem defines
     * @return {@code null} if valid, otherwise the reason
     */
    private static String checkValues(String field, List<Double> values, int expected) {
        int n = (values == null) ? 0 : values.size();
        if (n != expected) {
            return field + " has " + n + " values but the problem defines " + expected;
        }
        for (int i = 0; i < n; i++) {
            Double v = values.get(i);
            if (v == null) return field + "[" + i + "] is null";
            if (!Double.isFinite(v)) return field + "[" + i + "] is not finite: " + v;
        }
        return null;
    }

    /**
     * Message of the innermost cause of an exception, on a single line.
     *
     * @param t the exception
     * @return the root cause message, or the exception class name if it has none
     */
    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return msg.replaceAll("\\s+", " ").trim();
    }

    /**
     * Returns per-segment variable counts for a {@link CompositeSolution}, or
     * {@code null} for a flat (non-composite) solution.
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

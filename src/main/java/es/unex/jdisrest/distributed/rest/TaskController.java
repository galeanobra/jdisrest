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
 * event-loop thread but the actual work is dispatched to the injected
 * virtual-thread scheduler ({@link MasterSpringApp#virtualThreadScheduler()}),
 * so blocking calls never stall the event loop, and any number of workers can
 * wait in a long-poll or on the master's locks at once without one request
 * queueing behind another.
 *
 * <p>Task lifecycle:
 * <ol>
 *   <li>Worker calls {@code GET /next} — master moves a task from
 *       {@code pendingTaskQueue} to {@code inFlightTasks} and returns it.</li>
 *   <li>Worker evaluates the solution and calls {@code POST /{id}/result} —
 *       master validates the payload, takes the task out of
 *       {@code inFlightTasks}, writes objectives/constraints (and the optional
 *       repaired variables) into its solution, then moves the task to
 *       {@code completedTaskQueue}.</li>
 *   <li>If evaluation fails, worker calls {@code POST /{id}/error} — master
 *       requeues the task to {@code pendingTaskQueue} immediately, without
 *       waiting for the watchdog timeout, or discards it once it has failed
 *       too many times. A result that fails validation (or cannot be decoded)
 *       follows the same path. A failure reported by a worker that no longer
 *       holds the task (another worker has it now) is ignored.</li>
 * </ol>
 *
 * <p>Once the master needs no more results (a stop has been requested, or the
 * algorithm has ended its run; see {@link MasterFacade#needsNoMoreResults()}),
 * {@code GET /next} hands out nothing, a decoded result gets {@code 404}
 * whether it passes validation or not (a body Spring rejects keeps its
 * {@code 400}, {@code 413} or {@code 415}), and a failure report counts
 * nothing: the task only leaves {@code inFlightTasks}.
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

    /** Reactor scheduler backed by virtual threads; runs every blocking call of the handlers. */
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
     * {@link Timings#TASK_LONGPOLL_S} seconds waiting for a task to become available in
     * {@code pendingTaskQueue} (a steady-state master creates one on demand instead, so
     * it only waits once the run is over). It always runs on the virtual-thread
     * scheduler — never on the Netty event-loop thread.
     *
     * <p>A worker holds one task at a time: if its previous task is still in flight
     * when it asks again (the result or error never arrived), that task goes back to
     * the queue, without counting as a failed evaluation. Each concurrent evaluation
     * slot therefore needs its own {@code workerId}.
     *
     * <p>Response codes:
     * <ul>
     *   <li>{@code 200 OK} — task payload returned; worker should evaluate and call
     *       {@code POST /{id}/result}.</li>
     *   <li>{@code 204 No Content} — no task arrived within the long-poll window, or the
     *       master is not ready to hand out tasks yet (it is still building its initial
     *       state); the worker should wait a few seconds and ask again (the bundled
     *       workers wait 5 s). A request that was already being served when the run was
     *       stopped or ended gets it too, and the next one {@code 410}.</li>
     *   <li>{@code 410 Gone} — the algorithm has finished; worker should shut
     *       down.</li>
     *   <li>{@code 500 Internal Server Error} — the claimed task could not be serialized
     *       (an unsupported solution type, or a variable that is {@code null}, {@code NaN}
     *       or infinite); the task has been handled as a failed evaluation (requeued, or
     *       discarded after too many failures), and the worker may ask again.</li>
     *   <li>{@code 503 Service Unavailable} — the master shut down while the request waited in
     *       the long-poll ({@code 410} if the run had finished by then). The server is closing,
     *       so the worker usually sees a closed connection instead; nothing is logged.</li>
     * </ul>
     *
     * @param workerId opaque identifier for the requesting worker (e.g. {@code "worker-01"})
     * @return a {@link Mono} emitting a {@link ResponseEntity} containing a
     *         {@link TaskPayload}, or an empty response as described above
     */
    @GetMapping("/next")
    public Mono<ResponseEntity<TaskPayload>> getNextTask(@RequestParam("workerId") String workerId) {
        return Mono.<ResponseEntity<TaskPayload>>fromCallable(() -> {
            // Return 410 immediately if the algorithm has already finished.
            if (MasterFacade.isFinished()) {
                return ResponseEntity.<TaskPayload>status(410).build();
            }
            // Block up to TASK_LONGPOLL_S seconds waiting for a task; returns null on timeout.
            ParallelTask<Solution<?>> task;
            try {
                task = MasterFacade.claimNextTask(workerId, Timings.TASK_LONGPOLL_S);
            } catch (InterruptedException e) {
                // shutdown() disposes the handler threads while workers wait in the long-poll: the
                // server is closing, so answer quietly instead of logging a 500 per waiting worker.
                Thread.currentThread().interrupt();
                return ResponseEntity.<TaskPayload>status(MasterFacade.isFinished() ? 410 : 503).build();
            }
            if (task == null) {
                // No task within the timeout window, or the master is not ready: retry later.
                return ResponseEntity.<TaskPayload>noContent().build();
            }
            try {
                return ResponseEntity.ok(toPayload(task.getIdentifier(), task.getContents()));
            } catch (RuntimeException e) {
                // The task is already in flight, so hand it back (it counts as a failed
                // evaluation) instead of stranding it, and make the problem visible. Any
                // exception counts: an unsupported or corrupted solution may fail with a
                // ClassCastException as well as with an IllegalArgumentException.
                // SteadyStateEvolutionaryAlgorithm.run() rejects unsupported problems up
                // front; this guards other masters and individual bad solutions.
                Log.error("[task-" + task.getIdentifier() + "] Cannot serialize solution: " + e.getMessage());
                MasterFacade.failInFlightTask(task.getIdentifier(), workerId);
                return ResponseEntity.<TaskPayload>internalServerError().build();
            }
        }).subscribeOn(scheduler);
    }

    /**
     * Endpoint for a worker to submit evaluation results (objectives and constraints).
     *
     * <p>The solution object lives on the master throughout the entire evaluation
     * cycle. This method validates the payload (see {@link #rejectionReason}) and
     * hands it to {@link MasterFacade#submitResult(long, String, java.util.function.Consumer)},
     * which takes the task out of {@code inFlightTasks}, writes the returned
     * objectives, constraints and — when present — repaired variables into the
     * {@link Solution} instance (see {@link #record}) and moves the task to
     * {@code completedTaskQueue}, unblocking the algorithm thread that is waiting
     * in {@code waitForComputedTask()} or {@code waitForEvaluatedTasks()}. Nothing is
     * written into a solution whose task this request does not win, so two reports
     * of the same task never write into it at the same time.
     *
     * <p>A result is accepted from any worker, also from one that no longer holds the
     * task (a late result is still a valid evaluation); {@code workerId} may be
     * missing.
     *
     * <p>Response codes:
     * <ul>
     *   <li>{@code 200 OK} — result accepted and recorded.</li>
     *   <li>{@code 404 Not Found} — the master no longer expects the result:
     *       {@code taskId} is no longer in {@code inFlightTasks} (the watchdog
     *       already requeued it because the worker took too long, or another report
     *       for it was accepted first), or no more results are needed (the run was
     *       stopped with {@code POST /api/v1/stop} or, for
     *       {@code SteadyStateEvolutionaryAlgorithm} and its subclasses, has ended on
     *       its stopping criterion). The result is discarded and not counted. Once no
     *       more results are needed this holds for an invalid result too: it does not
     *       count as a failed evaluation, and the worker gets this answer instead of
     *       {@code 422}.</li>
     *   <li>{@code 422 Unprocessable Content} — the payload is well-formed JSON
     *       but cannot be applied: wrong number of objectives or constraints, a
     *       {@code null}, {@code NaN} or infinite value, or a decision vector that
     *       does not fit the solution or lies outside its variables' bounds. The
     *       task is handled as if the worker had reported an evaluation error
     *       (requeued, or discarded after too many failures; ignored if another
     *       worker holds the task now), and the body is a
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
            // Validate everything before taking the task, so a rejected result leaves the
            // master-held solution untouched and only its holder's report counts as a failure.
            // The solution is only read here; its shape never changes.
            ParallelTask<Solution<?>> task = MasterFacade.inFlightTasks().get(taskId);
            if (task != null) {
                String reason = rejectionReason(result, task.getContents());
                if (reason != null) {
                    boolean counted = MasterFacade.failInFlightTask(taskId, result.workerId());
                    Log.warn("[task-" + taskId + "] Invalid result from " + result.workerId() + ": " + reason
                        + (counted ? " — counted as a failed evaluation" : ""));
                    // Read after the report, which counts nothing once no more results are needed.
                    return invalidResultAnswer(taskId, reason, counted, MasterFacade.needsNoMoreResults());
                }
            }
            try {
                // Takes the task out of flight, records the result, moves it to the completed
                // queue. Returns false, dropping the result, if the task was not in flight (the
                // watchdog or another report took it first), a stop has been requested or a
                // SteadyStateEvolutionaryAlgorithm has ended its run.
                boolean accepted = MasterFacade.submitResult(taskId, result.workerId(),
                    claimed -> record(claimed.getContents(), result));
                return accepted
                    ? ResponseEntity.<TaskRejectionPayload>ok().build()
                    : ResponseEntity.<TaskRejectionPayload>notFound().build();
            } catch (IllegalArgumentException e) {
                // Only when the task entered flight after the check above (it is checked again
                // by record): the master has already handled it as a failed evaluation, which
                // counts nothing if a stop or the end of the run landed during the recording.
                Log.warn("[task-" + taskId + "] Invalid result from " + result.workerId() + ": "
                    + e.getMessage() + " — counted as a failed evaluation");
                return ResponseEntity.status(422).body(new TaskRejectionPayload(taskId, e.getMessage()));
            }
        }).subscribeOn(scheduler);
    }

    /**
     * Endpoint for a worker to report that evaluation of a task has failed.
     *
     * <p>On receiving this call, the master immediately requeues the task back into
     * {@code pendingTaskQueue} via {@link MasterFacade#failInFlightTask(long, String)}, so
     * another available worker can retry it, unless the task has now failed too
     * many times and is discarded. This is faster than waiting for the
     * {@link WatchdogScheduler} to detect a silent worker after its
     * {@link Timings#WORKER_TIMEOUT_S}-second timeout. A report from a worker that no
     * longer holds the task (another worker has it now) is ignored, so it cannot take
     * the task away from that worker. Once the master needs no more results (a stop, or
     * the end of the run) the report counts nothing: the task leaves
     * {@code inFlightTasks} and is neither requeued nor discarded.
     *
     * <p>Returns {@code 200 OK} whatever happened to the task — even if the {@code taskId}
     * has already been requeued, or the report was ignored or counted nothing, the error has
     * been logged and there is nothing for the worker to do. Like every endpoint it answers
     * {@code 400} with a {@link TaskRejectionPayload} when the body cannot be decoded (see
     * {@link #onRejectedRequest}), and {@code 500} when no master is running.
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
            // Requeue (or discard) at once so the task is not stranded until the next
            // watchdog cycle.
            MasterFacade.failInFlightTask(taskId, error.workerId());
            return ResponseEntity.<Void>ok().build();
        }).subscribeOn(scheduler);
    }

    /**
     * Handles requests Spring rejects before the handler method runs: a body that
     * cannot be decoded (e.g. a bare {@code NaN} or {@code Infinity} token emitted
     * by Python's {@code json.dumps}, which is not valid JSON — {@code 400}), a
     * wrong {@code Content-Type} ({@code 415}), a body larger than the server's
     * buffer limit ({@code 413}, see {@code AbstractMaster.DEFAULT_MAX_REQUEST_SIZE})
     * and similar client errors on a matched route.
     *
     * <p>Without this handler Spring would answer with the error status and the
     * task would stay in {@code inFlightTasks} forever: the worker is alive and
     * keeps sending heartbeats, so the watchdog never requeues it. When the
     * request targets {@code /{taskId}/result} or {@code /{taskId}/error}, the
     * task is handled as if the worker had reported an evaluation error (requeued,
     * or discarded after too many failures; once the master needs no more results,
     * taken out of {@code inFlightTasks} without counting anything). The reporting
     * worker is unknown here (its id is in the body that could not be read), so the
     * report is not checked against the task's holder. The response keeps Spring's
     * status code, also once no more results are needed, and carries a
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
            boolean counted = MasterFacade.failInFlightTask(taskId, null);
            Log.warn("[task-" + taskId + "] " + reason + (counted ? " — counted as a failed evaluation" : ""));
            return ResponseEntity.status(ex.getStatusCode()).body(new TaskRejectionPayload(taskId, reason));
        }).subscribeOn(scheduler);
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
     * <p>Every variable must be finite: a JSON number cannot carry {@code NaN} or an
     * infinity (the JSON encoder would write it as a string, which no worker can apply),
     * so such a solution is rejected here instead of failing on every worker.
     *
     * @param taskId   the task identifier
     * @param solution the solution to serialize
     * @return the payload to send to the worker
     * @throws IllegalArgumentException if the solution type is unsupported, or a variable is
     *                                  {@code null}, not a number, {@code NaN} or infinite
     */
    static TaskPayload toPayload(long taskId, Solution<?> solution) {
        List<Number> variables = SolutionVariables.flatten(solution);
        String notFinite = SolutionVariables.checkFinite(variables);
        if (notFinite != null) {
            throw new IllegalArgumentException(notFinite + " — a JSON number cannot carry it");
        }
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
     * solution's variables (see {@link SolutionVariables#convert}) and lie within their
     * bounds (see {@link SolutionVariables#checkBounds}).
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
            List<Number> converted;
            try {
                converted = SolutionVariables.convert(solution, result.variables());
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            return SolutionVariables.checkBounds(solution, converted);
        }
        return null;
    }

    /**
     * Answers a result that failed validation, once its report has gone to
     * {@link MasterFacade#failInFlightTask(long, String)}: {@code 422} with the reason while more
     * results are needed (whether the report counted as a failed evaluation or was ignored
     * because another worker holds the task), or if the report counted just before no more were
     * needed; the bodiless {@code 404} of a late result once the master needs no more results and
     * the report did not count (whatever the reason), since the master no longer expects this
     * result at all.
     *
     * @param taskId             the task the result is for
     * @param reason             why the result cannot be applied
     * @param counted            whether the report counted, as
     *                           {@link MasterFacade#failInFlightTask(long, String)} returned
     * @param needsNoMoreResults whether the master needs no more results, read after the report
     * @return {@code 422} with a {@link TaskRejectionPayload}, or {@code 404}
     */
    static ResponseEntity<TaskRejectionPayload> invalidResultAnswer(
            long taskId, String reason, boolean counted, boolean needsNoMoreResults) {
        if (!counted && needsNoMoreResults) {
            return ResponseEntity.<TaskRejectionPayload>notFound().build();
        }
        return ResponseEntity.status(422).body(new TaskRejectionPayload(taskId, reason));
    }

    /**
     * Writes a result into the solution it targets: the repaired variables first, when
     * present (Lamarckian repair or local search), so that objectives and constraints stay
     * consistent with the genes, then the objectives and the constraints. The result is
     * checked again first ({@link #rejectionReason}), so nothing is written if it is invalid.
     *
     * @param solution the master-held solution
     * @param result   the payload posted by the worker
     * @throws IllegalArgumentException with the reason, if the result cannot be applied
     */
    static void record(Solution<?> solution, TaskResultPayload result) {
        String reason = rejectionReason(result, solution);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
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
     * <p>Example: a {@link CompositeSolution} with an integer component of 4
     * variables and a real one of 2 produces {@code [4, 2]}.
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

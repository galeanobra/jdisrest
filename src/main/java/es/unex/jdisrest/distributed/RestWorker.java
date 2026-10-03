package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.Timings;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java worker that talks to the master over REST.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Main thread (the one that calls {@link #run()}): {@code GET /api/v1/tasks/next} →
 *       receives the variables, evaluates them with the local {@link Problem}, and posts the
 *       result ({@code POST /api/v1/tasks/{id}/result}) or the failure
 *       ({@code POST /api/v1/tasks/{id}/error}).</li>
 *   <li>Heartbeat thread: {@code POST /api/v1/workers/heartbeat} every
 *       {@link Timings#HEARTBEAT_INTERVAL_S} seconds, in parallel with evaluation.</li>
 * </ol>
 *
 * <p>The independent heartbeat ensures the master does not declare a worker dead
 * while it is busy evaluating, however long one evaluation takes.
 *
 * <h2>Tasks</h2>
 * Each task is evaluated on a fresh {@code problem.createSolution()}. Before the variables are
 * written into it, the layout the master announces ({@code segmentSizes}, {@code encoding},
 * {@code segmentEncodings}) is compared with that solution: a worker whose problem builds a
 * different solution (other segment boundaries, a flat solution where the master has a
 * composite, real variables where the master has integers) would otherwise be filled with
 * misaligned values whenever the total length happens to agree, and evaluate them silently.
 *
 * <p>Once the task id is known, everything that keeps one task from being evaluated is
 * reported through {@code POST /error}, so the master gets the task back at once (it requeues
 * it, or discards it after the failure limit) instead of leaving it in flight until the worker
 * dies: an unreadable {@code variables} list, a layout mismatch, a vector that does not fit the
 * solution, an exception from {@code createSolution()} or {@code evaluate()}, and a non-finite
 * objective, constraint or variable in the result. The worker then moves on to the next task;
 * after two or more failed tasks in a row it first pauses briefly (as after a {@code 204}), so a
 * worker that cannot evaluate anything does not burn through the master's tasks at network
 * speed.
 *
 * <p>Lamarckian results: when {@code evaluate()} changes the decision variables (a repair, a
 * local search), the result carries the changed vector in its {@code variables} field and the
 * master overwrites the variables of its own solution, as a local jMetal run would keep them.
 * When the variables are unchanged the field is omitted, so the body is the one earlier
 * versions sent.
 *
 * <h2>Detecting a lost master</h2>
 * The worker stops after five failed exchanges with the master in a row in the main loop, or
 * after three failed heartbeats in a row (the heartbeat thread then interrupts the main
 * thread). A failed exchange is a transport failure or timeout, an HTTP status the protocol
 * does not define for that call (a {@code 5xx}, a {@code 401}, ...), or a task without a
 * usable {@code taskId}; a failed heartbeat is one that cannot be delivered or is answered with
 * a status other than {@code 2xx}. The answers the protocol defines are not failures:
 * {@code 204} and {@code 410} on {@code GET /next}, and {@code 404} (the master no longer
 * expects the result) and the rejections {@code 400}, {@code 413}, {@code 415} and {@code 422}
 * (the master has already counted the task as failed) on {@code POST /result} and
 * {@code POST /error}. The count is reset only when an exchange completes as the protocol
 * defines — a {@code 204}, or a task whose outcome the master acknowledged — not merely because
 * a task was handed out, so a master that serves tasks but fails every result stops the worker
 * like one that fails every request.
 *
 * <h2>Timeouts</h2>
 * Derived from {@link Timings}: {@code GET /next} waits {@link Timings#TASK_LONGPOLL_S} plus
 * 10 s, so an empty long-poll is always answered before the client gives up; one heartbeat
 * waits a third of {@link Timings#HEARTBEAT_INTERVAL_S}, and a failed one is retried after the
 * same time, so the three failures that stop the worker fit well inside
 * {@link Timings#WORKER_TIMEOUT_S}. {@code POST /result} and {@code POST /error} wait 15 s.
 *
 * <p>A worker runs once: {@link #run()} closes it when it returns.
 *
 * <h2>Changes in 1.2.0</h2>
 * Earlier versions counted a heartbeat answered with an error status as delivered; did not
 * report a task whose payload could not be read or whose {@code createSolution()} threw (the
 * task stayed in flight); ignored the layout fields; never sent repaired variables; posted a
 * non-finite objective, which the master refused with {@code 422}, where it is now reported
 * through {@code POST /error}; treated {@code 413} and {@code 415} on {@code POST /result} as
 * failed exchanges; ignored the status of {@code POST /error}; reset the error count on every
 * task handed out; used fixed timeouts; and,
 * when {@link #run()} was called again, looped on the closed client until the error limit
 * instead of throwing.
 *
 * @param <S> type of the solutions the local problem builds
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class RestWorker<S extends Solution<?>> implements Closeable {

    // If the master dies, workers must detect it and stop.
    // MAX_CONSECUTIVE_ERRORS: consecutive failed exchanges in the main loop before giving up.
    // MAX_HEARTBEAT_FAILURES: consecutive heartbeat failures before interrupting the main thread.
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final int MAX_HEARTBEAT_FAILURES = 3;

    /** Seconds added to {@link Timings#TASK_LONGPOLL_S} for the read timeout of {@code GET /next}. */
    static final int LONGPOLL_MARGIN_S = 10;

    /**
     * Answers to {@code POST /result} and {@code POST /error} meaning "the master refused this
     * request and has already handled the task as a failed evaluation, or, for a {@code 422},
     * ignored the report because another worker holds the task now" (a {@code 400}, {@code 413}
     * or {@code 415} counts whoever holds the task, since the master cannot read the
     * {@code workerId} of a body it has not decoded; see {@code TaskRejectionPayload}): an
     * evaluation problem, not a lost master. The Python worker handles the same answers.
     */
    private static final Set<Integer> REJECTION_STATUSES = Set.of(400, 413, 415, 422);

    /**
     * Client-side timeouts and delays. Package-private so tests can shorten them; the public
     * constructors always use {@link #DEFAULTS}.
     *
     * @param connectTimeout      TCP connect timeout of every request
     * @param nextTaskTimeout     read timeout of {@code GET /next}; must exceed the long-poll window
     * @param postTimeout         read timeout of {@code POST /result} and {@code POST /error},
     *                            which the master answers without waiting
     * @param heartbeatTimeout    read timeout of one heartbeat
     * @param heartbeatInterval   pause between successful heartbeats
     * @param heartbeatRetryDelay pause after a failed heartbeat
     * @param noTaskDelay         pause after a {@code 204}, and before the next claim after two or
     *                            more failed tasks in a row
     * @param retryDelay          pause after a failed exchange with the master
     */
    record ClientTimings(Duration connectTimeout, Duration nextTaskTimeout, Duration postTimeout,
                         Duration heartbeatTimeout, Duration heartbeatInterval, Duration heartbeatRetryDelay,
                         Duration noTaskDelay, Duration retryDelay) {

        /** The timings of the public constructors, derived from {@link Timings}. */
        static final ClientTimings DEFAULTS = new ClientTimings(
                Duration.ofSeconds(10),
                Duration.ofSeconds(Timings.TASK_LONGPOLL_S + LONGPOLL_MARGIN_S),
                Duration.ofSeconds(15),
                Duration.ofSeconds(Math.max(1, Timings.HEARTBEAT_INTERVAL_S / 3)),
                Duration.ofSeconds(Timings.HEARTBEAT_INTERVAL_S),
                Duration.ofSeconds(Math.max(1, Timings.HEARTBEAT_INTERVAL_S / 3)),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10));
    }

    /** How one claimed task ended, as far as the loop's counters are concerned. */
    private enum Outcome {
        /** The master accepted the result. */
        ACCEPTED,
        /** The task failed: reported through {@code POST /error}, or the result was rejected. */
        FAILED,
        /** The master no longer expected the result ({@code 404}): nothing to retry or count. */
        DROPPED,
        /** The master was lost during the evaluation: the result is discarded, the worker stops. */
        MASTER_LOST
    }

    private final String masterUrl;   // e.g. "http://10.0.0.1:8080"
    private final String workerId;
    private final Problem<S> problem;
    private final ClientTimings timings;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Thread heartbeatThread;
    /** Flag raised by the heartbeat thread when it concludes the master is gone. */
    private volatile boolean masterDead = false;
    /** Set by {@link #close()}: the loop stops and the worker cannot run again. */
    private volatile boolean closed = false;
    /** Set by the first {@link #run()}, atomically, so two threads cannot both start the loop. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Creates a worker with a generated id ({@code worker-java-} followed by eight random hex
     * characters).
     *
     * @param masterUrl base URL of the master, e.g. {@code http://10.0.0.1:8080} (trailing
     *                  slashes are ignored)
     * @param problem   the problem whose {@code createSolution()} and {@code evaluate()} are used;
     *                  it must build the same kind of solution as the master's problem
     * @throws IllegalArgumentException if {@code masterUrl} is {@code null} or blank, or
     *                                  {@code problem} is {@code null}
     */
    public RestWorker(String masterUrl, Problem<S> problem) {
        this(masterUrl, problem, null);
    }

    /**
     * Creates a worker that reports itself to the master as {@code workerId}, e.g. a SLURM job
     * and array index, so the master's log and {@code GET /api/v1/workers/status} name it.
     *
     * <p>The id must be unique among the workers of a run: the master tracks one in-flight
     * task per id and requeues it when that id asks for a new task (it takes the request as a
     * sign that the result was lost), so two workers sharing an id keep taking each other's
     * tasks back and most of their results are refused with {@code 404}.
     *
     * @param masterUrl base URL of the master, e.g. {@code http://10.0.0.1:8080} (trailing
     *                  slashes are ignored)
     * @param problem   the problem whose {@code createSolution()} and {@code evaluate()} are used;
     *                  it must build the same kind of solution as the master's problem
     * @param workerId  the worker id; {@code null} or blank for a generated one
     *                  ({@code worker-java-} followed by eight random hex characters)
     * @throws IllegalArgumentException if {@code masterUrl} is {@code null} or blank, or
     *                                  {@code problem} is {@code null}
     */
    public RestWorker(String masterUrl, Problem<S> problem, String workerId) {
        this(masterUrl, problem, workerId, ClientTimings.DEFAULTS);
    }

    /**
     * Creates a worker with explicit client timings (tests).
     *
     * @param masterUrl base URL of the master
     * @param problem   the local problem
     * @param workerId  the worker id; {@code null} or blank for a generated one
     * @param timings   client-side timeouts and delays
     */
    RestWorker(String masterUrl, Problem<S> problem, String workerId, ClientTimings timings) {
        if (masterUrl == null || masterUrl.isBlank()) {
            throw new IllegalArgumentException("masterUrl is null or blank");
        }
        if (problem == null) throw new IllegalArgumentException("problem is null");
        this.masterUrl = masterUrl.strip().replaceAll("/+$", ""); // strip trailing slashes
        this.problem = problem;
        this.workerId = (workerId == null || workerId.isBlank())
                ? "worker-java-" + UUID.randomUUID().toString().substring(0, 8)
                : workerId;
        this.timings = Objects.requireNonNull(timings, "timings");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timings.connectTimeout())
                .build();

        Log.info("RestWorker " + this.workerId + " -> " + this.masterUrl);
    }

    /**
     * Returns the id this worker reports to the master.
     *
     * @return the worker id given to the constructor, or the generated one
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * Heartbeats and evaluates tasks until the master's algorithm finishes ({@code 410}), the
     * master is considered lost (see the class description), or the calling thread is
     * interrupted; then closes the worker.
     *
     * @throws IllegalStateException if the worker has already run (or is running in another
     *                               thread) or has been closed
     */
    public void run() {
        if (closed || !started.compareAndSet(false, true)) {
            throw new IllegalStateException("RestWorker " + workerId + " has already run or been closed");
        }
        startHeartbeatThread();
        try {
            evaluationLoop();
        } finally {
            close();
        }
    }

    /**
     * Stops the heartbeat thread and releases the HTTP client. Idempotent. A loop still running
     * in another thread stops at its next step; the HTTP client first lets a request in
     * progress complete (at most the {@code GET /next} long-poll), so this call may wait for it.
     */
    @Override
    public void close() {
        closed = true;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        httpClient.close();
    }

    // ── Main evaluation loop ──────────────────────────────────────────────────

    private void evaluationLoop() {
        int consecutiveErrors = 0;       // failed exchanges with the master in a row
        int consecutiveFailedTasks = 0;  // tasks in a row that failed or whose result was rejected
        while (!masterDead && !closed) {
            try {
                Map<String, Object> task = requestNextTask();

                if (task == null) {
                    // No work available yet — back off briefly before retrying.
                    consecutiveErrors = 0;  // the master answered as the protocol defines
                    Thread.sleep(timings.noTaskDelay());
                    continue;
                }

                Outcome outcome = process(task);
                if (outcome == Outcome.MASTER_LOST) break;
                // The master acknowledged the outcome of the task: it is alive. A task handed
                // out is not enough on its own, or a master that fails every result would
                // never reach the limit.
                consecutiveErrors = 0;
                if (outcome == Outcome.ACCEPTED) {
                    consecutiveFailedTasks = 0;
                } else if (outcome == Outcome.FAILED && ++consecutiveFailedTasks > 1) {
                    Log.warn("Worker " + workerId + ": " + consecutiveFailedTasks + " failed tasks in a row "
                            + "— waiting " + timings.noTaskDelay().toMillis() + "ms before the next one");
                    Thread.sleep(timings.noTaskDelay());
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (masterDead) {
                    Log.info("Worker " + workerId + " interrupted by heartbeat: master is gone, stopping");
                } else {
                    Log.info("Worker " + workerId + " interrupted, stopping");
                }
                break;
            } catch (AlgorithmFinishedException e) {
                Log.info("Worker " + workerId + " stopping: master algorithm finished");
                break;
            } catch (Exception e) {
                if (closed) break;  // the failure comes from the closed HTTP client
                consecutiveErrors++;
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.error("Worker " + workerId + ": " + consecutiveErrors
                            + " consecutive errors talking to the master "
                            + "— assuming the master is gone, stopping");
                    break;
                }
                Log.warn("Worker " + workerId + " error contacting master (attempt "
                        + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): "
                        + e.getMessage() + " — retrying in " + timings.retryDelay().toMillis() + "ms");
                try {
                    Thread.sleep(timings.retryDelay());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Evaluates one claimed task and reports its outcome to the master.
     *
     * @param task the decoded body of {@code GET /next}
     * @return how the task ended
     * @throws IOException          if the task has no usable id, or an exchange with the master
     *                              failed (counted by the loop)
     * @throws InterruptedException if the thread is interrupted while talking to the master
     */
    private Outcome process(Map<String, Object> task) throws IOException, InterruptedException {
        long taskId = taskIdOf(task);

        // ── Build the solution ── failures are reported, the master gets the task back.
        List<Number> variables;
        try {
            variables = variablesOf(task);
        } catch (IllegalArgumentException e) {
            return unusable(taskId, e.getMessage());
        }
        S solution;
        try {
            solution = problem.createSolution();
        } catch (Exception e) {
            return failed(taskId, "problem.createSolution()", e);
        }
        if (solution == null) return unusable(taskId, "problem.createSolution() returned null");
        String mismatch = layoutMismatch(task, solution);
        if (mismatch != null) {
            return unusable(taskId, "the task does not match the worker's problem: " + mismatch);
        }
        List<Number> sent;
        try {
            // Splits by component for a CompositeSolution and converts every value to the type
            // of its destination variable, so the numeric type Jackson chose is irrelevant.
            SolutionVariables.apply(solution, variables);
            sent = SolutionVariables.flatten(solution);
        } catch (IllegalArgumentException e) {
            return unusable(taskId, e.getMessage());
        } catch (RuntimeException e) {
            return failed(taskId, "writing the variables into the solution", e);
        }

        // ── Evaluate ── may take minutes or hours; the heartbeat runs independently.
        Log.info("Worker " + workerId + " evaluating task " + taskId);
        long start = System.currentTimeMillis();
        try {
            problem.evaluate(solution);
        } catch (Exception e) {
            if (masterDead) return masterLost();
            return failed(taskId, "problem.evaluate()", e);
        }
        long elapsed = System.currentTimeMillis() - start;
        Log.info("Worker " + workerId + " task " + taskId + " evaluated in " + elapsed + "ms");

        if (masterDead) return masterLost();

        Map<String, Object> body;
        try {
            body = resultBody(workerId, solution, sent, elapsed);
        } catch (IllegalArgumentException e) {
            return unusable(taskId, e.getMessage());
        } catch (RuntimeException e) {
            return failed(taskId, "reading the evaluated solution", e);
        }
        return submitResult(taskId, body);
    }

    /** Logs that a task cannot be evaluated (no stack trace: the reason says it all) and reports it. */
    private Outcome unusable(long taskId, String reason) throws IOException, InterruptedException {
        Log.error("Worker " + workerId + " task " + taskId + " cannot be evaluated: " + reason
                + " — reporting it to the master");
        return reportError(taskId, reason);
    }

    /** Logs an exception thrown by the problem, with its stack trace, and reports it. */
    private Outcome failed(long taskId, String step, Exception e) throws IOException, InterruptedException {
        Log.error("Worker " + workerId + " task " + taskId + " failed in " + step + ": " + e
                + " — reporting it to the master", e);
        return reportError(taskId, e.toString());
    }

    private Outcome masterLost() {
        Log.warn("Worker " + workerId + ": master loss detected after evaluation — discarding result and stopping");
        return Outcome.MASTER_LOST;
    }

    // ── Task payload ──────────────────────────────────────────────────────────

    /**
     * Reads the task id of a {@code GET /next} body.
     *
     * @param task the decoded body
     * @return the task id
     * @throws IOException if it is missing or not an integer: without an id the task cannot be
     *                     reported, so this counts as a failed exchange with the master
     */
    static long taskIdOf(Map<?, ?> task) throws IOException {
        Object raw = task.get("taskId");
        if (raw instanceof Integer || raw instanceof Long) return ((Number) raw).longValue();
        throw new IOException("the master sent a task without a usable taskId: " + describe(raw));
    }

    /**
     * Reads the decision vector of a {@code GET /next} body.
     *
     * @param task the decoded body
     * @return the values, each a {@link Number} of whatever type Jackson chose
     * @throws IllegalArgumentException if {@code variables} is not a list, or an element is not
     *                                  a JSON number (e.g. {@code null}, or the string
     *                                  {@code "NaN"} a non-finite gene becomes)
     */
    static List<Number> variablesOf(Map<?, ?> task) {
        Object raw = task.get("variables");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("the task has no 'variables' list: " + describe(raw));
        }
        List<Number> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Number n)) {
                throw new IllegalArgumentException("variables[" + i + "] is not a number: " + describe(list.get(i)));
            }
            out.add(n);
        }
        return out;
    }

    /**
     * Compares the layout a {@code GET /next} body announces with a locally built solution.
     *
     * <p>The fields are the ones the master derives from its own solution
     * ({@code TaskController.toPayload}): {@code segmentSizes} only for a
     * {@link CompositeSolution}; {@code encoding} {@code "double"} or {@code "mixed"}, absent when
     * every variable is an integer; {@code segmentEncodings} for composites that are not
     * all-integer. The local solution must produce the same three values; a field the payload
     * leaves out is checked through its default (flat, all-integer), except
     * {@code segmentEncodings}, which is compared only when sent.
     *
     * @param task  the decoded body
     * @param local the solution the worker's problem built
     * @return {@code null} if the layouts agree, otherwise the reason they do not
     */
    static String layoutMismatch(Map<?, ?> task, Solution<?> local) {
        Object rawSizes = task.get("segmentSizes");
        Object rawEncoding = task.get("encoding");
        Object rawSegmentEncodings = task.get("segmentEncodings");

        List<Integer> sizes = null;
        if (rawSizes != null) {
            if (!(rawSizes instanceof List<?> list) || !list.stream().allMatch(Integer.class::isInstance)) {
                return "segmentSizes is not a list of integers: " + describe(rawSizes);
            }
            sizes = list.stream().map(Integer.class::cast).toList();
        }
        if (rawEncoding != null && !(rawEncoding instanceof String)) {
            return "encoding is not a string: " + describe(rawEncoding);
        }
        String encoding = rawEncoding == null ? SolutionVariables.Encoding.INT.wireName() : (String) rawEncoding;
        if (rawSegmentEncodings != null && (!(rawSegmentEncodings instanceof List<?> list)
                || !list.stream().allMatch(String.class::isInstance))) {
            return "segmentEncodings is not a list of strings: " + describe(rawSegmentEncodings);
        }

        List<Integer> localSizes = null;
        String localEncoding;
        List<String> localSegmentEncodings;
        try {
            if (local instanceof CompositeSolution composite) {
                localSizes = composite.variables().stream().map(c -> c.variables().size()).toList();
            }
            localEncoding = SolutionVariables.wireEncoding(local);
            localSegmentEncodings = SolutionVariables.wireNames(SolutionVariables.segmentEncodings(local));
        } catch (IllegalArgumentException e) {
            // A solution type the wire format does not support. Never return a null message:
            // null means "the layouts agree".
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } catch (RuntimeException e) {
            return "cannot inspect the local solution: " + e;
        }

        if (!Objects.equals(sizes, localSizes)) {
            return "segmentSizes " + describeSizes(sizes) + " from the master, but the local solution has "
                    + describeSizes(localSizes);
        }
        if (!encoding.equals(localEncoding)) {
            return "encoding \"" + encoding + "\" from the master, but the local solution is \""
                    + localEncoding + "\"";
        }
        if (rawSegmentEncodings != null && !rawSegmentEncodings.equals(localSegmentEncodings)) {
            return "segmentEncodings " + rawSegmentEncodings + " from the master, but the local solution has "
                    + localSegmentEncodings;
        }
        return null;
    }

    // ── Result payload ────────────────────────────────────────────────────────

    /**
     * Builds the body of {@code POST /result}: {@code workerId}, {@code objectives},
     * {@code constraints}, {@code evaluationTimeMs} and, only when the evaluation changed the
     * decision variables, {@code variables} (the whole new vector, in the same flat layout the
     * task used).
     *
     * <p>Values are validated here, as the Python worker does: Jackson 2 would write a
     * {@code NaN} as the string {@code "NaN"}, which the master can only refuse, so a
     * non-finite value is reported as an evaluation error instead, with a message naming it.
     *
     * @param workerId  the reporting worker
     * @param solution  the evaluated solution
     * @param sent      the variables as they were before the evaluation
     *                  ({@link SolutionVariables#flatten})
     * @param elapsedMs evaluation wall time in milliseconds
     * @return the body, with its keys in a fixed order
     * @throws IllegalArgumentException if an objective, a constraint or a changed variable is
     *                                  {@code null} or not finite
     */
    static Map<String, Object> resultBody(String workerId, Solution<?> solution, List<Number> sent, long elapsedMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", workerId);
        body.put("objectives", finiteValues("objectives", solution.objectives()));
        body.put("constraints", finiteValues("constraints", solution.constraints()));
        body.put("evaluationTimeMs", elapsedMs);
        List<Number> evaluated = SolutionVariables.flatten(solution);
        if (!evaluated.equals(sent)) {
            for (int i = 0; i < evaluated.size(); i++) {
                Number v = evaluated.get(i);
                if (v == null) throw new IllegalArgumentException("variables[" + i + "] is null after evaluation");
                if (!Double.isFinite(v.doubleValue())) {
                    throw new IllegalArgumentException("variables[" + i + "] is not finite after evaluation: " + v);
                }
            }
            body.put("variables", evaluated);
        }
        return body;
    }

    private static List<Double> finiteValues(String field, double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) {
                throw new IllegalArgumentException(field + "[" + i + "] is not finite: " + values[i]);
            }
            out.add(values[i]);
        }
        return out;
    }

    private static String describe(Object value) {
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }

    private static String describeSizes(List<Integer> sizes) {
        return sizes == null ? "none (a flat solution)" : sizes.toString();
    }

    // ── HTTP calls ────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/tasks/next
     * Returns {@code null} when the server replied 204 (no task available).
     * The server long-polls for up to {@link Timings#TASK_LONGPOLL_S} seconds internally.
     */
    private Map<String, Object> requestNextTask() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/tasks/next?workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8)))
                .timeout(timings.nextTaskTimeout())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 204) return null;
        if (response.statusCode() == 410) throw new AlgorithmFinishedException();
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected status " + response.statusCode() + " from GET /tasks/next");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> task = mapper.readValue(response.body(), Map.class);
        if (task == null) throw new IOException("Empty task payload from GET /tasks/next");
        return task;
    }

    /**
     * POST /api/v1/tasks/{taskId}/result
     */
    private Outcome submitResult(long taskId, Map<String, Object> body) throws IOException, InterruptedException {
        HttpResponse<String> response = post(taskId, "result", body);
        int status = response.statusCode();

        if (status / 100 == 2) return Outcome.ACCEPTED;
        if (status == 404) {
            // The master no longer expects this result (the watchdog already requeued it,
            // or the run was stopped and the master drops late results).
            Log.warn("Master no longer holds task " + taskId
                    + " (requeued by the watchdog, or the run was stopped)");
            return Outcome.DROPPED;
        }
        if (REJECTION_STATUSES.contains(status)) {
            // The master could not apply the result (e.g. a vector outside the bounds, or a body
            // over its size limit) and has requeued the task (or discarded it after the failure
            // limit). This is an evaluation problem, not a dead master: log the reason and carry
            // on with the next task.
            Log.warn("Master rejected result for taskId " + taskId + " (" + status + "): " + response.body());
            return Outcome.FAILED;
        }
        throw new IOException("Unexpected status " + status + " from POST /tasks/" + taskId + "/result");
    }

    /**
     * POST /api/v1/tasks/{taskId}/error
     * Tells the master the evaluation failed so it requeues the task at once (or discards
     * it after the failure limit).
     */
    private Outcome reportError(long taskId, String message) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", workerId);
        body.put("errorMessage", message);
        HttpResponse<String> response = post(taskId, "error", body);
        int status = response.statusCode();
        if (status / 100 != 2) {
            if (status != 404 && !REJECTION_STATUSES.contains(status)) {
                throw new IOException("Unexpected status " + status + " from POST /tasks/" + taskId + "/error");
            }
            // 404: the master no longer holds the task; a rejection: it has counted the failure.
            Log.warn("Master answered " + status + " to the error report for task " + taskId + ": " + response.body());
        }
        return Outcome.FAILED;
    }

    private HttpResponse<String> post(long taskId, String endpoint, Map<String, Object> body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/tasks/" + taskId + "/" + endpoint))
                .timeout(timings.postTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── Heartbeat (separate thread) ───────────────────────────────────────────

    /**
     * Sends heartbeats every {@link Timings#HEARTBEAT_INTERVAL_S} seconds independently of the
     * main thread, so the master does not declare a worker dead in the middle of a long
     * evaluation. A heartbeat fails when it cannot be delivered or the master answers with a
     * status other than {@code 2xx} (e.g. {@code 500} when no master is registered).
     */
    private void startHeartbeatThread() {
        Thread mainThread = Thread.currentThread();
        heartbeatThread = new Thread(() -> {
            String localAddress = getLocalAddress();
            int consecutiveFailures = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    sendHeartbeat(localAddress);
                    consecutiveFailures = 0;  // Heartbeat OK → reset counter
                    Thread.sleep(timings.heartbeatInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (closed) break;  // the failure comes from the closed HTTP client
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                        Log.error("Worker " + workerId + ": " + consecutiveFailures
                                + " consecutive heartbeat failures "
                                + "— assuming master is gone, signaling shutdown");
                        masterDead = true;
                        mainThread.interrupt();  // unblock the main thread if it's in sleep/HTTP
                        break;
                    }
                    Log.warn("Heartbeat error (" + consecutiveFailures + "/" + MAX_HEARTBEAT_FAILURES
                            + "): " + e.getMessage());
                    try {
                        Thread.sleep(timings.heartbeatRetryDelay());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        heartbeatThread.setName("heartbeat-" + workerId);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void sendHeartbeat(String address) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/workers/heartbeat"
                        + "?workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8)
                        + "&address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)))
                .timeout(timings.heartbeatTimeout())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("heartbeat answered with status " + response.statusCode());
        }
    }

    private String getLocalAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

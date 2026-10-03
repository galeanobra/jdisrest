package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.GenerationalMaster;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

/**
 * Static bridge between the Spring REST beans and the active master algorithm
 * instance ({@link SteadyStateMaster} or {@link GenerationalMaster}).
 *
 * <h2>Why a static bridge?</h2>
 * Spring creates its beans (controllers, watchdog, etc.) eagerly at application
 * context startup, which happens inside the master's own constructor, <em>before</em> the
 * master has finished constructing itself. A static singleton pattern avoids a
 * circular dependency: the algorithm registers itself (via its constructor) and the Spring
 * beans query {@link #ss()} / {@link #g()} on every request, always obtaining
 * the latest reference without needing constructor injection.
 *
 * <h2>Unified API</h2>
 * Controllers and the {@link WatchdogScheduler} never import {@link SteadyStateMaster}
 * or {@link GenerationalMaster} directly; they only call methods on this facade. The facade
 * delegates to whichever master is currently active, checking {@link SteadyStateMaster}
 * first and falling back to {@link GenerationalMaster}. Exactly one of the two must be
 * non-null during normal operation; both being null is valid only in the brief
 * window between Spring startup and the end of the master's constructor. A master that
 * exists but is not ready yet ({@link #isReady()}) is not finished and hands out no task,
 * so the endpoints answer normally from the moment the server starts.
 *
 * <h2>Counters</h2>
 * {@link #totalEvaluations} and {@link #totalTasksDispatched} are maintained
 * here (not inside the master classes) so that monitoring code has a single,
 * algorithm-agnostic source of truth. Once the master needs no more results, the status
 * endpoints report the results the algorithm used rather than those accepted (see
 * {@link #evaluations(long, boolean, int)}).
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class MasterFacade {

    /** Utility class — not instantiable. */
    private MasterFacade() {
    }

    // ── Cumulative counters (not queue sizes) ─────────────────────────────────

    /**
     * Total number of evaluations that have been successfully completed and
     * accepted since the master started. Incremented by
     * {@link #submitResult(long, String, Consumer)} only when the underlying master
     * confirms the result was accepted (i.e. the
     * task had not already been requeued by the watchdog, no stop had been requested and,
     * for a {@code SteadyStateEvolutionaryAlgorithm}, the run had not ended), so it stops
     * growing once a stop is requested or such an algorithm has ended its run. From then on
     * the status endpoints subtract the results still queued, which the algorithm will never
     * process (see {@link #evaluations(long, boolean, int)}).
     */
    private static final AtomicLong totalEvaluations    = new AtomicLong(0);

    /**
     * Total number of tasks that have been dispatched to workers since startup,
     * including tasks that were later requeued after a worker failure. Incremented
     * by {@link #claimNextTask} every time a non-null task is returned.
     */
    private static final AtomicLong totalTasksDispatched = new AtomicLong(0);

    // ── Experiment metadata (set by the program before running the algorithm) ─

    /**
     * The maximum number of evaluations configured for this experiment run.
     * Initialized to {@code -1} (unknown) and set to the actual value by
     * {@link #init}. Used by {@link #currentStatus()} (for {@link StatusController} and
     * {@code status.json}) to compute {@code progress} and ETA.
     */
    private static final AtomicInteger maxEvaluations  = new AtomicInteger(-1);

    /**
     * Reads and changes the configuration of the running algorithm for
     * {@link ConfigController}. {@code null} until the program registers one with
     * {@link #setConfigurationHandler}; while it is {@code null}, {@code /api/v1/config}
     * answers {@code 501 Not Implemented}. {@code volatile} because it is set by the
     * program's thread and read by REST threads.
     */
    private static volatile ConfigurationHandler configurationHandler;

    /**
     * The {@link Instant} at which {@link #init} was called, i.e. the moment
     * the algorithm started running. {@code null} until {@link #init} is called.
     * Used to compute {@code elapsedSeconds} and ETA in the status endpoints.
     */
    private static final AtomicReference<Instant> startTime = new AtomicReference<>(null);

    /**
     * Guards {@link #init} against duplicate invocations. Only the first call sets up
     * experiment metadata and (optionally) the status-file writer thread; subsequent
     * calls are ignored to avoid spawning redundant writer threads.
     */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * The periodic {@code status.json} writer started by {@link #init}, or {@code null} if
     * none was started; stopped by {@link #stopStatusFileWriter()}.
     */
    private static volatile StatusFileWriter statusFileWriter;

    /**
     * Initializes experiment metadata and optionally starts the periodic
     * {@code status.json} writer.
     *
     * <p>Must be called by the program that runs the algorithm exactly once, after
     * constructing the master and immediately before starting the algorithm (i.e. before
     * {@code algorithm.run()} or equivalent). Calling this method sets the experiment clock to
     * {@link Instant#now()}.
     *
     * <p>The writer writes {@code status.json} to the {@code jdisrest.dataPath} folder (the
     * working directory by default), creating the folder if needed, once right away and then
     * every {@code statusFileIntervalSec} seconds, atomically (write-then-rename) so that
     * readers never see a partial file. The JSON is the one of {@code GET /api/v1/status}. A
     * write is best-effort: a failure never stops the algorithm, and only the first failure is
     * logged. {@code AbstractMaster.shutdown()} writes a last snapshot and stops the writer.
     *
     * @param maxEvals              the total number of evaluations the algorithm
     *                              will perform; used as the denominator when
     *                              computing {@code progress} in the status
     *                              endpoints. Must be positive.
     * @param statusFileIntervalSec interval in seconds between writes of
     *                              {@code status.json} to the shared filesystem
     *                              directory ({@code jdisrest.dataPath}). Pass
     *                              {@code 0} to disable file-based status
     *                              reporting entirely (the HTTP endpoint is
     *                              always available regardless).
     */
    public static void init(int maxEvals, int statusFileIntervalSec) {
        if (!initialized.compareAndSet(false, true)) {
            Log.warn("MasterFacade.init() called more than once — ignoring duplicate call");
            return;
        }
        maxEvaluations.set(maxEvals);
        startTime.set(Instant.now());
        if (statusFileIntervalSec > 0) {
            StatusFileWriter writer = new StatusFileWriter(MasterFacade::currentStatus,
                    Path.of(System.getProperty("jdisrest.dataPath", ".")), statusFileIntervalSec * 1000L);
            statusFileWriter = writer;
            writer.start();
        }
    }

    // ── Periodic status.json writer ───────────────────────────────────────────

    /**
     * Writes a last {@code status.json} snapshot and stops the periodic writer started by
     * {@link #init}, waiting a few seconds at most for that last write. Called by
     * {@code AbstractMaster.shutdown()}, after the run has finished, so that the file ends up
     * reporting the final state instead of whatever the last periodic write saw. Does nothing
     * if no writer was started; repeated calls are harmless.
     */
    public static void stopStatusFileWriter() {
        StatusFileWriter writer = statusFileWriter;
        if (writer != null) {
            writer.stop();
        }
    }

    /**
     * Builds the live progress snapshot consumed by both {@link StatusController}
     * and {@code status.json}. Centralised here so the two surfaces share
     * the exact same field values.
     *
     * @return a fresh {@link StatusSnapshot} for the current master state
     */
    public static StatusSnapshot currentStatus() {
        BlockingQueue<?> pending = getPendingTaskQueue();
        BlockingQueue<?> completed = getCompletedTaskQueue();
        long accepted = totalEvaluations.get();  // before the queue: see evaluations()
        int queued = completed != null ? completed.size() : 0;
        return snapshot(isFinished(), evaluations(accepted, needsNoMoreResults(), queued),
                maxEvaluations.get(), startTime.get(), Instant.now(),
                aliveWorkerCount(Timings.WORKER_TIMEOUT_S), inFlightCount(),
                pending != null ? pending.size() : 0, discardedTaskCount());
    }

    /**
     * The evaluations the status endpoints report: {@code evaluations} in
     * {@code GET /api/v1/status} and {@code status.json}, {@code totalEvaluations} in
     * {@code GET /api/v1/workers/status}. While the run goes on, the results the master has
     * accepted, including those still queued for the algorithm, which it will process. Once the
     * master needs no more results (a stop has been requested or the algorithm has ended its
     * run, see {@link es.unex.jdisrest.distributed.AbstractMaster#needsNoMoreResults()}), only
     * the results the algorithm used: the accepted ones minus those still queued, which it will
     * never process. A run of the bundled algorithms that ends on its evaluation budget therefore
     * reports that budget, not the results that were still queued on top of it.
     *
     * <p>The callers read the queue whenever they build a snapshot, rather than draining it or
     * adjusting the counter when the run ends, so that a result queued at the very moment the
     * run ends is left out too. They read the accepted count first: a result is queued before
     * it is counted, so in that order a result being accepted at that moment is never taken
     * for a used one.
     *
     * @param accepted            results accepted so far ({@link #getTotalEvaluations()})
     * @param needsNoMoreResults  whether the master needs no more results
     * @param queuedResults       results waiting in the master's completed queue
     * @return the evaluations to report; never negative
     */
    static long evaluations(long accepted, boolean needsNoMoreResults, int queuedResults) {
        return needsNoMoreResults ? Math.max(0L, accepted - queuedResults) : accepted;
    }

    /**
     * Builds a progress snapshot from its raw values: the arithmetic of {@link #currentStatus()}.
     *
     * <p>{@code progress} is {@code evaluations / maxEvals} clamped to {@code [0, 1]}, and the
     * ETA is extrapolated from that same clamped value: {@code -1} until 1 % of the budget is
     * done, once the run has finished, or before any time has elapsed; otherwise never
     * negative, and {@code 0} once the budget has been reached while the run has not finished
     * yet (results accepted but not processed by the algorithm yet, or a stopping criterion
     * other than the budget).
     *
     * @param finished       whether the run is over
     * @param evaluations    the evaluations to report ({@link #evaluations(long, boolean, int)})
     * @param maxEvals       the budget; {@code 0} or less when unknown
     * @param start          when the run started; {@code null} before {@link #init}
     * @param now            the current instant
     * @param aliveWorkers   workers heard from recently
     * @param inFlightTasks  tasks held by workers
     * @param pendingTasks   tasks waiting to be handed out
     * @param discardedTasks tasks discarded after failing too often
     * @return the snapshot
     */
    static StatusSnapshot snapshot(boolean finished, long evaluations, int maxEvals, Instant start, Instant now,
                                   int aliveWorkers, int inFlightTasks, int pendingTasks, long discardedTasks) {
        long elapsedSeconds = start != null ? Math.max(0L, Duration.between(start, now).getSeconds()) : 0L;
        double progress = (maxEvals > 0) ? Math.min(1.0, (double) evaluations / maxEvals) : 0.0;
        // Only compute ETA once enough progress has been made to avoid wild extrapolations.
        long etaSeconds = (progress > 0.01 && !finished && elapsedSeconds > 0)
                ? (long) (elapsedSeconds / progress * (1.0 - progress))
                : -1L;
        return new StatusSnapshot(!finished, finished, evaluations, maxEvals, progress, elapsedSeconds,
                etaSeconds, aliveWorkers, inFlightTasks, pendingTasks, discardedTasks);
    }

    /**
     * Builds the body of {@code GET /api/v1/workers/status}: counters, queue sizes and the
     * worker registry, with the keys always in the same order ({@code aliveWorkers},
     * {@code totalEvaluations}, {@code totalDispatched}, {@code pendingTasks},
     * {@code inFlightTasks}, {@code queuedResults}, {@code workers}) and the workers sorted by
     * id. {@code totalEvaluations} is the {@code evaluations} of {@code GET /api/v1/status}
     * ({@link #evaluations(long, boolean, int)}), so once the master needs no more results it
     * leaves out the {@code queuedResults}, which stay visible. Without a master the counts are
     * {@code 0} and {@code workers} is empty.
     *
     * @return a new ordered map, ready to be serialized
     */
    static Map<String, Object> clusterStatus() {
        BlockingQueue<?> pending = getPendingTaskQueue();
        BlockingQueue<?> completed = getCompletedTaskQueue();
        long accepted = getTotalEvaluations();  // before the queue: see evaluations()
        int queued = completed != null ? completed.size() : 0;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("aliveWorkers", aliveWorkerCount(Timings.WORKER_TIMEOUT_S));
        status.put("totalEvaluations", evaluations(accepted, needsNoMoreResults(), queued));
        status.put("totalDispatched", getTotalTasksDispatched());
        status.put("pendingTasks", pending != null ? pending.size() : 0);
        status.put("inFlightTasks", inFlightCount());
        status.put("queuedResults", queued);
        status.put("workers", new TreeMap<>(getWorkerRegistry()));
        return status;
    }

    /**
     * Returns the configured maximum number of evaluations for this run.
     *
     * @return the value passed to {@link #init}, or {@code -1} if {@link #init}
     *         has not yet been called
     */
    public static int     getMaxEvaluations() { return maxEvaluations.get(); }

    /**
     * Returns the {@link Instant} at which the algorithm was started.
     *
     * @return the start time set by {@link #init}, or {@code null} if
     *         {@link #init} has not yet been called
     */
    public static Instant getStartTime()      { return startTime.get(); }

    // ── Active master instance resolution ────────────────────────────────────

    /**
     * Returns the active {@link SteadyStateMaster} instance, or {@code null} if a
     * steady-state master is not in use for this run.
     */
    @SuppressWarnings("unchecked")
    private static SteadyStateMaster<ParallelTask<Solution<?>>, ?> ss() {
        return (SteadyStateMaster<ParallelTask<Solution<?>>, ?>) SteadyStateMaster.getInstance();
    }

    /**
     * Returns the active {@link GenerationalMaster} instance, or {@code null} if a
     * generational master is not in use for this run.
     */
    @SuppressWarnings("unchecked")
    private static GenerationalMaster<ParallelTask<Solution<?>>, ?> g() {
        return (GenerationalMaster<ParallelTask<Solution<?>>, ?>) GenerationalMaster.getInstance();
    }

    // ── Unified public API ────────────────────────────────────────────────────

    /**
     * Claims the next pending task on behalf of a worker, blocking until a task
     * is available or the timeout expires (long-polling).
     *
     * <p>Delegates to the active master instance ({@link SteadyStateMaster} or
     * {@link GenerationalMaster}). If a task is returned it is moved from
     * {@code pendingTaskQueue} to {@code inFlightTasks} inside the master and
     * the dispatch counter is incremented here.
     *
     * @param workerId       the identifier of the requesting worker
     * @param timeoutSeconds maximum seconds to wait for a task before returning
     *                       {@code null}
     * @return the next {@link ParallelTask}, or {@code null} if none became
     *         available within the timeout
     * @throws InterruptedException     if the waiting thread is interrupted
     * @throws IllegalStateException    if neither master instance is available
     */
    public static ParallelTask<Solution<?>> claimNextTask(String workerId, int timeoutSeconds)
            throws InterruptedException {
        ParallelTask<Solution<?>> task = null;
        if (ss() != null) task = ss().claimNextTask(workerId, timeoutSeconds);
        else if (g() != null) task = g().claimNextTask(workerId, timeoutSeconds);
        else throw new IllegalStateException("No master instance available");
        if (task != null) totalTasksDispatched.incrementAndGet();
        return task;
    }

    /**
     * Records a completed evaluation result submitted by a worker.
     *
     * <p>Moves the task from {@code inFlightTasks} to {@code completedTaskQueue}
     * inside the active master, unblocking the algorithm thread that is waiting
     * for computed results. The evaluation counter is incremented only if the
     * master confirms the result was accepted (i.e. the task had not already been
     * requeued by the watchdog while the worker was evaluating, no stop had been requested
     * and, for a {@code SteadyStateEvolutionaryAlgorithm}, the run had not ended).
     *
     * <p>The caller must have written the objectives and constraints into the
     * {@link Solution} object retrieved from {@link #inFlightTasks()} <em>before</em>
     * calling this method; {@link TaskController} uses
     * {@link #submitResult(long, String, Consumer)} instead, which writes them only once the
     * master has accepted the result.
     *
     * @param taskId   the numeric identifier of the completed task
     * @param workerId the identifier of the submitting worker; may be {@code null}
     * @return {@code true} if the result was accepted; {@code false} if the task
     *         was no longer in {@code inFlightTasks} (watchdog had already
     *         requeued it), a stop has been requested or, for a
     *         {@code SteadyStateEvolutionaryAlgorithm}, the run has ended, in which case the
     *         result is dropped and not counted
     * @throws IllegalStateException if neither master instance is available
     */
    public static boolean submitResult(long taskId, String workerId) {
        return submitResult(taskId, workerId, task -> { });
    }

    /**
     * Records a completed evaluation result submitted by a worker, writing it into the task's
     * solution only once the master has accepted it (see
     * {@link es.unex.jdisrest.distributed.AbstractMaster#submitResult(long, String, Consumer)}):
     * the task is taken out of {@code inFlightTasks} first, then {@code recorder} writes the
     * result, then the task is moved to {@code completedTaskQueue}. The evaluation counter is
     * incremented only if the result was accepted.
     *
     * @param taskId   the numeric identifier of the completed task
     * @param workerId the identifier of the submitting worker; may be {@code null}
     * @param recorder writes the result into the task's solution; called at most once
     * @return {@code true} if the result was accepted and recorded; {@code false} if the task
     *         was not in flight, a stop has been requested or, for a
     *         {@code SteadyStateEvolutionaryAlgorithm}, the run has ended
     * @throws IllegalStateException if neither master instance is available
     * @throws RuntimeException      whatever {@code recorder} throws; the task has then been
     *                               handled as a failed evaluation
     */
    public static boolean submitResult(long taskId, String workerId,
                                       Consumer<? super ParallelTask<Solution<?>>> recorder) {
        boolean accepted;
        if (ss() != null) accepted = ss().submitResult(taskId, workerId, recorder);
        else if (g() != null) accepted = g().submitResult(taskId, workerId, recorder);
        else throw new IllegalStateException("No master instance available");
        if (accepted) totalEvaluations.incrementAndGet();
        return accepted;
    }

    /**
     * Provides direct access to the master's {@code inFlightTasks} map so that
     * {@link TaskController} can validate a result against the solution object it
     * targets before calling {@link #submitResult(long, String, Consumer)}, which
     * writes it.
     *
     * <p>The solution object stays on the master throughout the entire evaluation
     * cycle: workers receive a copy of its variables and send back objectives,
     * constraints and, optionally, a repaired decision vector, which
     * {@link TaskController} copies into it. Writing directly into the solution avoids
     * an extra copy and keeps the solution reference stable across the three-stage
     * pipeline.
     *
     * @return the live {@link ConcurrentHashMap} mapping task id to in-flight task
     * @throws IllegalStateException if neither master instance is available
     */
    @SuppressWarnings("unchecked")
    public static ConcurrentHashMap<Long, ParallelTask<Solution<?>>> inFlightTasks() {
        if (ss() != null) return (ConcurrentHashMap<Long, ParallelTask<Solution<?>>>) (Object) ss().inFlightTasks;
        if (g() != null) return (ConcurrentHashMap<Long, ParallelTask<Solution<?>>>) (Object) g().inFlightTasks;
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Registers or refreshes a worker heartbeat.
     *
     * <p>On the first call for a given {@code workerId} this creates a new entry
     * in the worker registry. On subsequent calls it updates the last-seen
     * timestamp. Used by {@link WorkerController#heartbeat}.
     *
     * @param workerId the worker's unique identifier
     * @param address  the worker's network address (may be empty)
     * @throws IllegalStateException if neither master instance is available
     */
    public static void registerHeartbeat(String workerId, String address) {
        if (ss() != null) {
            ss().registerHeartbeat(workerId, address);
            return;
        }
        if (g() != null) {
            g().registerHeartbeat(workerId, address);
            return;
        }
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Returns the number of workers whose most recent heartbeat was received
     * within {@code timeoutSeconds} seconds of now.
     *
     * @param timeoutSeconds staleness threshold in seconds
     * @return count of workers considered alive; {@code 0} if no master is set
     */
    public static int aliveWorkerCount(long timeoutSeconds) {
        if (ss() != null) return ss().aliveWorkerCount(timeoutSeconds);
        if (g() != null) return g().aliveWorkerCount(timeoutSeconds);
        return 0;
    }

    /**
     * Returns the full worker registry from the active master.
     *
     * <p>The map contains all workers that have ever sent a heartbeat, including
     * those that are now considered dead. Callers can filter by last-seen time
     * using {@link #aliveWorkerCount} for the count and this method for details.
     *
     * @return an unmodifiable view of the worker registry, or an empty map if no
     *         master is set
     */
    public static Map<String, ?> getWorkerRegistry() {
        if (ss() != null) return ss().getWorkerRegistry();
        if (g() != null) return g().getWorkerRegistry();
        return Map.of();
    }

    /**
     * Returns the pending task queue from the active master.
     *
     * <p>The pending queue holds tasks that have been created by the algorithm
     * but not yet claimed by any worker. Its size is exposed in the status
     * endpoints as {@code pendingTasks}.
     *
     * @return the {@link BlockingQueue} of pending tasks, or {@code null} if no
     *         master is set
     */
    public static BlockingQueue<?> getPendingTaskQueue() {
        if (ss() != null) return ss().getPendingTaskQueue();
        if (g() != null) return g().getPendingTaskQueue();
        return null;
    }

    /**
     * Returns the completed task queue from the active master.
     *
     * <p>The completed queue holds tasks whose evaluations have been submitted by
     * workers but not yet consumed by the algorithm thread. Its size is exposed
     * in the cluster status endpoint ({@link WorkerController#status()}) as
     * {@code queuedResults}.
     *
     * @return the {@link BlockingQueue} of completed tasks, or {@code null} if no
     *         master is set
     */
    public static BlockingQueue<?> getCompletedTaskQueue() {
        if (ss() != null) return ss().getCompletedTaskQueue();
        if (g() != null) return g().getCompletedTaskQueue();
        return null;
    }

    /**
     * Returns the number of tasks currently in-flight (claimed by workers but
     * not yet returned with a result or error).
     *
     * <p>Returns {@code 0} when no master instance is set, as in the brief window
     * between the start of the REST server and the end of the master's constructor, or
     * when nothing ever constructed one. Symmetric with {@link #aliveWorkerCount}
     * and {@link #getPendingTaskQueue}, both of which already tolerate that
     * window.
     *
     * @return current size of the {@code inFlightTasks} map; {@code 0} if no
     *         master is set
     */
    public static int inFlightCount() {
        if (ss() == null && g() == null) return 0;
        return inFlightTasks().size();
    }

    /**
     * Returns how many tasks the active master has discarded after too many failed
     * evaluations (see {@link es.unex.jdisrest.distributed.AbstractMaster#failInFlightTask(long)}).
     *
     * @return discarded tasks; {@code 0} if no master is set
     */
    public static long discardedTaskCount() {
        if (ss() != null) return ss().getDiscardedTaskCount();
        if (g() != null) return g().getDiscardedTaskCount();
        return 0L;
    }

    /**
     * Records a failed evaluation of an in-flight task, which is requeued at once or, after
     * too many failures, discarded (see
     * {@link es.unex.jdisrest.distributed.AbstractMaster#failInFlightTask(long)}).
     *
     * <p>This is faster than waiting for the {@link WatchdogScheduler} to detect the problem
     * after its {@link Timings#WORKER_TIMEOUT_S}-second timeout. If the {@code taskId} is not
     * present in {@code inFlightTasks} (e.g. already requeued by a concurrent watchdog cycle)
     * the call is a no-op. Once the master needs no more results (a stop, or the end of the
     * run) the task only leaves flight, and nothing is counted. It does not check which worker
     * reports the failure; {@link TaskController} uses {@link #failInFlightTask(long, String)},
     * which does.
     *
     * @param taskId the identifier of the task whose evaluation failed
     * @throws IllegalStateException if neither master instance is available
     */
    public static void failInFlightTask(long taskId) {
        if (ss() != null) { ss().failInFlightTask(taskId); return; }
        if (g() != null)  {  g().failInFlightTask(taskId); return; }
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Records a failed evaluation reported by a worker, unless that worker no longer holds the
     * task (see
     * {@link es.unex.jdisrest.distributed.AbstractMaster#failInFlightTask(long, String)}).
     * Called by {@link TaskController} when a worker reports an evaluation error, sends a
     * result the master rejects, or the task cannot be serialized.
     *
     * @param taskId   the identifier of the task whose evaluation failed
     * @param workerId the worker that reports it; {@code null} when unknown
     * @return {@code true} if the report was accepted while the master still needed results
     *         (it then counts, unless a stop or the end of the run lands meanwhile);
     *         {@code false} if the task was not in flight or is held by another worker, or if
     *         the master needs no more results (a stop, or the end of the run: the task has then
     *         left flight, counting nothing)
     * @throws IllegalStateException if neither master instance is available
     */
    public static boolean failInFlightTask(long taskId, String workerId) {
        if (ss() != null) return ss().failInFlightTask(taskId, workerId);
        if (g() != null)  return  g().failInFlightTask(taskId, workerId);
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Records a failed evaluation of an in-flight task.
     *
     * @param taskId the identifier of the task whose evaluation failed
     * @throws IllegalStateException if neither master instance is available
     * @deprecated since 1.2.0, renamed to {@link #failInFlightTask(long)}, to which it
     *             delegates. The task is no longer requeued unconditionally: the call
     *             counts as a failed evaluation and discards the task once it reaches the
     *             master's failure limit
     *             ({@link es.unex.jdisrest.distributed.AbstractMaster#setMaxTaskFailures}),
     *             and counts nothing once the master needs no more results.
     */
    @Deprecated(since = "1.2.0")
    public static void requeueInFlightTask(long taskId) {
        failInFlightTask(taskId);
    }

    /**
     * Requeues all in-flight tasks whose owning workers have not sent a heartbeat
     * within {@code timeoutSeconds} seconds (drops them instead once the master needs no
     * more results: a stop, or the end of the run).
     *
     * <p>Called periodically by the {@link WatchdogScheduler}. Tasks that belong
     * to still-alive workers are left untouched. Workers are removed from the
     * registry at the same time.
     *
     * @param timeoutSeconds staleness threshold used to decide whether a worker
     *                       is considered dead
     */
    public static void requeueOrphanTasks(long timeoutSeconds) {
        if (ss() != null) {
            ss().requeueOrphanTasks(timeoutSeconds);
            return;
        }
        if (g() != null) {
            g().requeueOrphanTasks(timeoutSeconds);
            return;
        }
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Returns {@code true} if the algorithm has completed all its evaluations,
     * otherwise reached its termination criterion, or been asked to stop
     * ({@link #requestStop()}).
     *
     * <p>Used by {@link TaskController#getNextTask} to return {@code 410 Gone}
     * and signal workers to shut down.
     *
     * @return {@code true} if finished; {@code false} if still running or if no
     *         master instance is set
     */
    public static boolean isFinished() {
        if (ss() != null) return ss().isFinished();
        if (g() != null) return g().isFinished();
        return false;
    }

    /**
     * Returns whether the active master needs no more results: a stop has been requested or
     * its algorithm has ended its run (see
     * {@link es.unex.jdisrest.distributed.AbstractMaster#needsNoMoreResults()}). Unlike
     * {@link #isFinished()}, it is never {@code true} while the algorithm may still wait for a
     * result. Used by {@link TaskController}, which answers a result it cannot apply then with
     * the {@code 404} of a late result, and by the status endpoints.
     *
     * @return {@code true} once no more results are needed; {@code false} while the run may
     *         still use one, or if no master instance is set
     */
    static boolean needsNoMoreResults() {
        if (ss() != null) return ss().needsNoMoreResults();
        if (g() != null) return g().needsNoMoreResults();
        return false;
    }

    /**
     * Returns {@code true} once a master is registered and ready to serve workers (see
     * {@link es.unex.jdisrest.distributed.AbstractMaster#isReady()}). Before that the run has
     * not started: no task is handed out and {@link #isFinished()} is {@code false}.
     *
     * @return {@code true} if a master is registered and ready; {@code false} otherwise
     */
    public static boolean isReady() {
        if (ss() != null) return ss().isReady();
        if (g() != null) return g().isReady();
        return false;
    }

    /**
     * Returns whether a master is registered.
     *
     * @return {@code true} once a master has finished constructing itself
     */
    static boolean hasMaster() {
        return ss() != null || g() != null;
    }

    /**
     * Asks the active master to finish now, as if it had met its stopping criterion
     * (see {@link es.unex.jdisrest.distributed.AbstractMaster#requestStop()}): from then
     * on {@link #isFinished()} is {@code true} and results still in flight are refused.
     * Used by {@link StopController}; repeated calls are harmless.
     *
     * @return {@code true} if a master is running; {@code false} if no master instance is set
     */
    public static boolean requestStop() {
        boolean active = true;
        if (ss() != null) ss().requestStop();
        else if (g() != null) g().requestStop();
        else active = false;
        return active;
    }

    /**
     * Updates the evaluation budget reported by {@code GET /api/v1/status} and
     * {@code status.json} when it changes while the run goes on (for example, after a new
     * configuration is applied through {@link ConfigController}), so that
     * {@code progress} and the ETA follow the new budget. Unlike {@link #init} it may be
     * called any number of times and does not touch the start time.
     *
     * @param maxEvals the new maximum number of evaluations; a value of {@code 0} or less
     *                 marks the budget as unknown ({@code progress} is then {@code 0})
     */
    public static void setMaxEvaluations(int maxEvals) {
        maxEvaluations.set(maxEvals);
    }

    /**
     * Registers the object that reads and changes the configuration of the running algorithm
     * for {@link ConfigController}. The program that builds the algorithm registers it,
     * because only it knows how the configuration maps to the algorithm. A later call
     * replaces the handler.
     *
     * @param handler the handler, or {@code null} to disable {@code /api/v1/config} (it then
     *                answers {@code 501 Not Implemented})
     */
    public static void setConfigurationHandler(ConfigurationHandler handler) {
        configurationHandler = handler;
    }

    /**
     * Returns the handler registered with {@link #setConfigurationHandler}.
     *
     * @return the handler, or {@code null} if none is registered
     */
    public static ConfigurationHandler configurationHandler() {
        return configurationHandler;
    }

    /**
     * Returns the cumulative number of evaluations successfully completed and
     * accepted by the master since startup.
     *
     * <p>This counter is only incremented when
     * {@link #submitResult(long, String, Consumer)} returns
     * {@code true}, so tasks that were requeued by the watchdog and re-evaluated
     * are not double-counted. Once the master needs no more results, the status endpoints
     * report fewer: only the results the algorithm used, without those still queued (see
     * {@link #evaluations(long, boolean, int)}).
     *
     * @return total accepted evaluations
     */
    public static long getTotalEvaluations() {
        return totalEvaluations.get();
    }

    /**
     * Returns the cumulative number of tasks dispatched to workers since startup.
     *
     * <p>This includes tasks that were later requeued due to worker failure and
     * re-dispatched to another worker, so {@code totalDispatched} may exceed
     * {@code totalEvaluations} when failures occur.
     *
     * @return total tasks dispatched
     */
    public static long getTotalTasksDispatched() {
        return totalTasksDispatched.get();
    }
}

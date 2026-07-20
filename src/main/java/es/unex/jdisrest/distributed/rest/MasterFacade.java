package es.unex.jdisrest.distributed.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.GenerationalMaster;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;

import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 * context startup, <em>before</em> the algorithm object has been constructed by
 * the calling code in {@code Main.java}. A static singleton pattern avoids a
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
 * window between Spring startup and algorithm initialization.
 *
 * <h2>Counters</h2>
 * {@link #totalEvaluations} and {@link #totalTasksDispatched} are maintained
 * here (not inside the master classes) so that monitoring code has a single,
 * algorithm-agnostic source of truth.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class MasterFacade {

    /** Utility class — not instantiable. */
    private MasterFacade() {
    }

    // ── Cumulative counters (not queue sizes) ─────────────────────────────────

    /**
     * Total number of evaluations that have been successfully completed and
     * accepted since the master started. Incremented by {@link #submitResult}
     * only when the underlying master confirms the result was accepted (i.e. the
     * task had not already been requeued by the watchdog).
     */
    private static final AtomicLong totalEvaluations    = new AtomicLong(0);

    /**
     * Total number of tasks that have been dispatched to workers since startup,
     * including tasks that were later requeued after a worker failure. Incremented
     * by {@link #claimNextTask} every time a non-null task is returned.
     */
    private static final AtomicLong totalTasksDispatched = new AtomicLong(0);

    // ── Experiment metadata (set by Main before running the algorithm) ────────

    /**
     * The maximum number of evaluations configured for this experiment run.
     * Initialized to {@code -1} (unknown) and set to the actual value by
     * {@link #init}. Used by {@link StatusController} and
     * {@link #writeStatusFile()} to compute {@code progress} and ETA.
     */
    private static final AtomicInteger maxEvaluations  = new AtomicInteger(-1);

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
     * Latches once the first {@code status.json} write failure has been logged, so the
     * algorithm log isn't spammed with one warning per writer tick when {@code dataPath}
     * is misconfigured.
     */
    private static final AtomicBoolean statusWriteErrorLogged = new AtomicBoolean(false);

    /** Shared Jackson mapper for serializing {@link StatusSnapshot} to {@code status.json}. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Initializes experiment metadata and optionally starts the periodic
     * {@code status.json} writer.
     *
     * <p>Must be called by {@code Main.java} exactly once, immediately before
     * starting the algorithm (i.e. before {@code algorithm.run()} or equivalent).
     * Calling this method sets the experiment clock to {@link Instant#now()}.
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
            startStatusFileWriter(statusFileIntervalSec);
        }
    }

    // ── Periodic status.json writer ───────────────────────────────────────────

    /**
     * Starts a daemon thread that calls {@link #writeStatusFile()} every
     * {@code intervalSeconds} seconds until interrupted, then writes a final
     * status snapshot when the algorithm finishes.
     *
     * @param intervalSeconds seconds between successive writes
     */
    private static void startStatusFileWriter(int intervalSeconds) {
        long intervalMs = intervalSeconds * 1000L;
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                writeStatusFile();
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Write a final snapshot so monitoring tools see the completed state.
            writeStatusFile();
        });
        t.setDaemon(true);
        t.setName("status-file-writer");
        t.start();
    }

    /**
     * Writes a JSON progress snapshot to {@code status.json} in the shared data
     * directory, using an atomic write-then-rename strategy to prevent readers
     * from seeing a partially written file.
     *
     * <p>The file is first written to a temporary path ({@code status.json.tmp})
     * in the same directory, then atomically renamed to {@code status.json} via
     * {@link StandardCopyOption#ATOMIC_MOVE}. On POSIX filesystems this rename
     * is guaranteed to be atomic from the reader's perspective.
     *
     * <p>The output directory is controlled by the system property
     * {@code jdisrest.dataPath} (defaults to {@code "."} if unset). This should
     * be set to a shared filesystem path visible to monitoring tools and to
     * worker nodes.
     *
     * <p>The JSON format is identical to that of
     * {@code GET /api/v1/status} (see {@link StatusController#status()}) so that
     * {@code monitor.py} can parse both sources with the same logic.
     *
     * <p>This method is best-effort: any {@link Exception} during the write is
     * silently swallowed so that a transient I/O error never interrupts the
     * algorithm.
     */
    /**
     * Builds the live progress snapshot consumed by both {@link StatusController}
     * and {@link #writeStatusFile()}. Centralised here so the two surfaces share
     * the exact same field values.
     *
     * @return a fresh {@link StatusSnapshot} for the current master state
     */
    public static StatusSnapshot currentStatus() {
        long    evaluations    = totalEvaluations.get();
        int     maxEvals       = maxEvaluations.get();
        Instant start          = startTime.get();
        boolean finished       = isFinished();
        long    elapsedSeconds = start != null ? Duration.between(start, Instant.now()).getSeconds() : 0L;
        double  progress       = (maxEvals > 0) ? (double) evaluations / maxEvals : 0.0;
        // Only compute ETA once enough progress has been made to avoid wild extrapolations.
        long etaSeconds = (progress > 0.01 && !finished && elapsedSeconds > 0)
                ? (long) (elapsedSeconds / progress * (1.0 - progress))
                : -1L;
        BlockingQueue<?> pending = getPendingTaskQueue();
        int pendingTasks = pending != null ? pending.size() : 0;

        return new StatusSnapshot(
                !finished,
                finished,
                evaluations,
                maxEvals,
                Math.min(1.0, progress),
                elapsedSeconds,
                etaSeconds,
                aliveWorkerCount(Timings.WORKER_TIMEOUT_S),
                inFlightCount(),
                pendingTasks
        );
    }

    private static void writeStatusFile() {
        try {
            String json = JSON.writeValueAsString(currentStatus());
            // Write to the shared data directory, not to the local scratch area.
            String dataPath = System.getProperty("jdisrest.dataPath", ".");
            Path tmp  = Path.of(dataPath, "status.json.tmp");
            Path dest = Path.of(dataPath, "status.json");
            Files.writeString(tmp, json);
            // ATOMIC_MOVE guarantees readers never see a partial file.
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // Best-effort: a failed status write must never crash the algorithm.
            // Log only the first occurrence to surface misconfigured dataPath without spamming.
            if (statusWriteErrorLogged.compareAndSet(false, true)) {
                Log.warn("Could not write status.json (further failures will be silent): " + e.getMessage());
            }
        }
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
     * requeued by the watchdog while the worker was evaluating).
     *
     * <p><strong>Important:</strong> the caller ({@link TaskController#submitResult})
     * must write the objectives and constraints directly into the
     * {@link Solution} object retrieved from {@link #inFlightTasks()}
     * <em>before</em> calling this method, because the master moves the same
     * solution reference into the completed queue.
     *
     * @param taskId   the numeric identifier of the completed task
     * @param workerId the identifier of the submitting worker (for logging)
     * @return {@code true} if the result was accepted; {@code false} if the task
     *         was no longer in {@code inFlightTasks} (watchdog had already
     *         requeued it)
     * @throws IllegalStateException if neither master instance is available
     */
    public static boolean submitResult(long taskId, String workerId) {
        boolean accepted;
        if (ss() != null) accepted = ss().submitResult(taskId, workerId);
        else if (g() != null) accepted = g().submitResult(taskId, workerId);
        else throw new IllegalStateException("No master instance available");
        if (accepted) totalEvaluations.incrementAndGet();
        return accepted;
    }

    /**
     * Provides direct access to the master's {@code inFlightTasks} map so that
     * {@link TaskController} can write evaluation results into the solution object
     * before calling {@link #submitResult}.
     *
     * <p>The solution object stays on the master throughout the entire evaluation
     * cycle — workers never send variables back, only objectives and constraints.
     * Writing directly into the map entry avoids an extra copy and keeps the
     * solution reference stable across the three-stage pipeline.
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
     * <p>Returns {@code 0} when no master instance is set yet — this can happen
     * during the brief startup window between {@link #init} and the algorithm
     * actually constructing its master, which is when the periodic status
     * writer fires its first snapshot. Symmetric with {@link #aliveWorkerCount}
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
     * Immediately requeues an in-flight task back to {@code pendingTaskQueue}.
     *
     * <p>Called by {@link TaskController#reportError} when a worker explicitly
     * reports an evaluation failure. This is faster than waiting for the
     * {@link WatchdogScheduler} to detect the problem after its 45-second
     * timeout. If the {@code taskId} is not present in {@code inFlightTasks}
     * (e.g. already requeued by a concurrent watchdog cycle) the call is a
     * no-op.
     *
     * @param taskId the identifier of the task to requeue
     */
    public static void requeueInFlightTask(long taskId) {
        if (ss() != null) { ss().requeueInFlightTask(taskId); return; }
        if (g() != null)  {  g().requeueInFlightTask(taskId); return; }
        throw new IllegalStateException("No master instance available");
    }

    /**
     * Requeues all in-flight tasks whose owning workers have not sent a heartbeat
     * within {@code timeoutSeconds} seconds.
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
     * Returns {@code true} if the algorithm has completed all its evaluations or
     * otherwise reached its termination criterion.
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
     * Returns the cumulative number of evaluations successfully completed and
     * accepted by the master since startup.
     *
     * <p>This counter is only incremented when {@link #submitResult} returns
     * {@code true}, so tasks that were requeued by the watchdog and re-evaluated
     * are not double-counted.
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

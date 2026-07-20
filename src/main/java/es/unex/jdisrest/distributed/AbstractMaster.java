package es.unex.jdisrest.distributed;

import es.unex.jdisrest.distributed.rest.MasterSpringApp;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.springframework.boot.SpringApplication;
import es.unex.jdisrest.util.Log;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shared REST infrastructure for {@link GenerationalMaster} and {@link SteadyStateMaster}.
 *
 * <p>This class centralises everything that is common to both the generational and
 * steady-state master variants:
 * <ul>
 *   <li>Starting the embedded Spring Boot / WebFlux server that exposes the REST API
 *       consumed by workers.</li>
 *   <li>Writing the {@code .master-endpoint} discovery file so workers and monitoring
 *       scripts can find the master's URL without hard-coding it.</li>
 *   <li>Maintaining the three-stage task pipeline:
 *       <ol>
 *         <li>{@link #pendingTaskQueue} — tasks created but not yet dispatched.</li>
 *         <li>{@link #inFlightTasks} — tasks dispatched to a worker, awaiting result.</li>
 *         <li>{@link #completedTaskQueue} — results received, waiting for the algorithm
 *             thread to process them.</li>
 *       </ol>
 *   </li>
 *   <li>Worker registration and heartbeat tracking via {@link #workerRegistry}.</li>
 *   <li>The watchdog method {@link #requeueOrphanTasks(long)}, invoked every 30 seconds by
 *       {@code WatchdogScheduler}, which detects dead workers and re-enqueues their tasks
 *       to prevent the algorithm from stalling.</li>
 * </ul>
 *
 * @param <T> type of {@link ParallelTask} managed by this master
 * @param <R> type of the final algorithm result
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public abstract class AbstractMaster<T extends ParallelTask<?>, R> {

    /**
     * Tasks that have been created but not yet claimed by any worker.
     * Workers dequeue from here via {@code GET /api/v1/tasks/next}.
     */
    protected BlockingQueue<T> pendingTaskQueue = new LinkedBlockingQueue<>();

    /**
     * Tasks whose results have been received from workers and are waiting for the main
     * algorithm thread to call {@code waitForComputedTask()} or {@code waitForEvaluatedTasks()}.
     */
    protected BlockingQueue<T> completedTaskQueue = new LinkedBlockingQueue<>();

    /**
     * Tasks that have been claimed by a worker but whose result has not yet arrived.
     * Keyed by task identifier. The watchdog scans this map to detect orphan tasks whose
     * owning worker has stopped sending heartbeats.
     *
     * <p>Declared {@code public} so that {@code TaskController} can read solution variables
     * from the in-flight task when a worker posts its result (the result payload only
     * carries objectives/constraints, not the full solution).
     */
    public final ConcurrentHashMap<Long, T> inFlightTasks = new ConcurrentHashMap<>();

    /**
     * Registry of workers that have sent at least one heartbeat. Keys are worker IDs
     * (unique strings assigned by the worker process). Values are {@link WorkerEntry}
     * instances that track the worker's last-seen time and current task.
     */
    protected final ConcurrentHashMap<String, WorkerEntry> workerRegistry = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Starts the embedded Spring Boot REST server and writes the discovery endpoint file.
     * Blocks until the server is ready to accept connections before returning.
     *
     * @param host the hostname or IP address to advertise in the discovery file
     *             (the server itself always binds to {@code 0.0.0.0})
     * @param port the HTTP port the server should listen on
     */
    protected AbstractMaster(String host, int port) {
        startRestServer(host, port);
    }

    // ── Spring Boot startup ───────────────────────────────────────────────────

    /**
     * Launches the Spring Boot / WebFlux application in a dedicated daemon thread and
     * blocks the calling thread until the server signals it is ready.
     *
     * <p>A {@link CountDownLatch} is used as the readiness signal: the Spring thread
     * counts it down after the application context is started (or after any startup error,
     * so the caller is not left blocked forever).
     *
     * @param host the advertised hostname, used only for logging and the endpoint file
     * @param port the HTTP port passed to Spring via {@code --server.port}
     */
    private void startRestServer(String host, int port) {
        CountDownLatch ready = new CountDownLatch(1);

        Thread springThread = new Thread(() -> {
            try {
                SpringApplication app = new SpringApplication(MasterSpringApp.class);
                app.setWebApplicationType(org.springframework.boot.WebApplicationType.REACTIVE);
                var context = app.run("--server.port=" + port, "--server.address=0.0.0.0", "--spring.main.banner-mode=off");
                Log.info("Spring Boot started (" + context.getClass().getSimpleName() + ")");
                ready.countDown();
                // Keep the thread alive so the Spring context is not shut down
                Thread.currentThread().join();
            } catch (Throwable e) {
                Log.error("ERROR starting REST server: " + e);
                e.printStackTrace();
                ready.countDown();
            }
        });
        springThread.setDaemon(true);
        springThread.setName("spring-rest-server");
        springThread.start();

        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Log.info("REST server ready at http://" + host + ":" + port + " — waiting for workers...");
        writeMasterEndpointFile(host, port);
    }

    /**
     * Writes the {@code .master-endpoint} JSON file atomically (write-then-rename) so that
     * workers can discover the master's URL without racing against a partial write.
     *
     * <p>The file is written to the directory indicated by the system property
     * {@code jdisrest.dataPath} (default: current working directory). In SLURM deployments,
     * {@code master.sh} sets this property to the shared scratch directory ({@code $HERE})
     * so that worker nodes and the login node can all read the file even though the master
     * process may be running on a different compute node.
     *
     * <p>Example file content:
     * <pre>{@code {"host":"10.0.0.1","port":8080,"url":"http://10.0.0.1:8080"}}</pre>
     *
     * @param host the advertised hostname or IP address
     * @param port the HTTP port the server is listening on
     */
    private void writeMasterEndpointFile(String host, int port) {
        try {
            String dataPath = System.getProperty("jdisrest.dataPath", ".");
            String url  = "http://" + host + ":" + port;
            String json = "{\"host\":\"" + host + "\",\"port\":" + port + ",\"url\":\"" + url + "\"}";
            Path tmp    = Path.of(dataPath, ".master-endpoint.tmp");
            Path dest   = Path.of(dataPath, ".master-endpoint");
            Files.writeString(tmp, json);
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Master endpoint written to " + dest + " (" + url + ")");
        } catch (IOException e) {
            Log.warn("Could not write .master-endpoint: " + e.getMessage());
        }
    }

    // ── Worker registration API (called by WorkerController) ─────────────────

    /**
     * Records or refreshes a worker's heartbeat in the registry.
     *
     * <p>If the worker is seen for the first time its entry is created and a log message
     * is emitted. For subsequent heartbeats only {@link WorkerEntry#lastSeen} is updated.
     * This method is thread-safe; the underlying {@link ConcurrentHashMap#compute} call
     * is atomic.
     *
     * @param workerId a unique identifier string for the worker (assigned by the worker
     *                 process at startup)
     * @param address  the worker's IP address, used for logging and diagnostics
     */
    public void registerHeartbeat(String workerId, String address) {
        workerRegistry.compute(workerId, (id, entry) -> {
            if (entry == null) {
                Log.info("Worker connected: " + workerId + " (" + address + ")" + " — total workers: " + (workerRegistry.size() + 1));
                return new WorkerEntry(workerId, address);
            }
            // Upgrade a placeholder address if the worker registered via claimNextTask first.
            if (address != null && !address.isEmpty() && "unknown".equals(entry.address)) {
                entry.address = address;
            }
            entry.lastSeen = Instant.now();
            return entry;
        });
    }

    /**
     * Returns a read-only view of the worker registry for monitoring and status endpoints.
     *
     * @return an unmodifiable map from worker ID to {@link WorkerEntry}
     */
    public Map<String, WorkerEntry> getWorkerRegistry() {
        return Collections.unmodifiableMap(workerRegistry);
    }

    /**
     * Counts how many registered workers have sent a heartbeat within the last
     * {@code timeoutSeconds} seconds.
     *
     * @param timeoutSeconds the heartbeat window; workers silent for longer than this
     *                       are considered dead and excluded from the count
     * @return the number of recently-active workers
     */
    public int aliveWorkerCount(long timeoutSeconds) {
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);
        return (int) workerRegistry.values().stream().filter(e -> e.lastSeen.isAfter(threshold)).count();
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    /**
     * Detects dead workers and re-enqueues their in-flight tasks so the algorithm does
     * not stall. Called periodically (every 30 s) by {@code WatchdogScheduler}.
     *
     * <p>A worker is considered dead if its {@link WorkerEntry#lastSeen} timestamp is older
     * than {@code timeoutSeconds}. For each dead worker:
     * <ol>
     *   <li>If the worker held an in-flight task ({@link WorkerEntry#currentTaskId} ≥ 0),
     *       that task is removed from {@link #inFlightTasks} and added back to
     *       {@link #pendingTaskQueue}.</li>
     *   <li>The worker's entry is removed from the registry.</li>
     * </ol>
     *
     * <p>Re-enqueued tasks will be claimed and re-evaluated by another worker, ensuring
     * that {@link GenerationalMaster#waitForEvaluatedTasks()} and {@link SteadyStateMaster#waitForComputedTask()}
     * eventually unblock even if a worker crashes mid-evaluation.
     *
     * @param timeoutSeconds the heartbeat expiry window; workers silent for longer than
     *                       this are treated as dead
     */
    public void requeueOrphanTasks(long timeoutSeconds) {
        Instant threshold = Instant.now().minusSeconds(timeoutSeconds);

        workerRegistry.values().stream().filter(e -> e.lastSeen.isBefore(threshold)).forEach(deadWorker -> {
            Log.warn("Worker timeout: " + deadWorker.workerId + " (last seen: " + deadWorker.lastSeen + ")");

            if (deadWorker.currentTaskId >= 0) {
                T orphan = inFlightTasks.remove(deadWorker.currentTaskId);
                if (orphan != null) {
                    Log.info("Requeueing task " + deadWorker.currentTaskId + " from dead worker " + deadWorker.workerId);
                    pendingTaskQueue.add(orphan);
                }
            }
            workerRegistry.remove(deadWorker.workerId);
        });
    }

    // ── Task result submission (called by TaskController) ─────────────────────

    /**
     * Moves a completed task from {@link #inFlightTasks} to {@link #completedTaskQueue}
     * so the main algorithm thread can process it.
     *
     * <p>Returns {@code false} without enqueuing if the task ID is not found in
     * {@link #inFlightTasks}. This happens when the watchdog has already re-enqueued the
     * task because the reporting worker was considered dead; the result arriving late
     * should be silently discarded to avoid double-processing. The {@code TaskController}
     * responds with HTTP {@code 404} in this case.
     *
     * <p>On success, the worker's {@link WorkerEntry} is updated: {@code currentTaskId}
     * is reset to {@code -1} (idle) and {@code lastSeen} is refreshed.
     *
     * @param taskId   the identifier of the completed task
     * @param workerId the ID of the worker submitting the result
     * @return {@code true} if the task was found and moved to the completed queue;
     *         {@code false} if the task was already removed by the watchdog
     */
    public boolean submitResult(long taskId, String workerId) {
        T task = inFlightTasks.remove(taskId);
        if (task == null) return false;

        workerRegistry.computeIfPresent(workerId, (id, entry) -> {
            entry.currentTaskId = -1L;
            entry.lastSeen = Instant.now();
            return entry;
        });

        completedTaskQueue.add(task);
        return true;
    }

    /**
     * Moves a task from {@link #inFlightTasks} back to {@link #pendingTaskQueue} after a
     * worker reports that its evaluation failed (e.g., simulator crash or timeout).
     *
     * <p>If the task ID is not found (because the watchdog already re-enqueued it), this
     * method is a no-op. Workers trigger this path by posting to
     * {@code POST /api/v1/tasks/{id}/error}.
     *
     * @param taskId the identifier of the task that failed evaluation
     */
    public void requeueInFlightTask(long taskId) {
        T task = inFlightTasks.remove(taskId);
        if (task != null) {
            Log.info("Task " + taskId + " requeued after evaluation error");
            pendingTaskQueue.add(task);
        }
    }

    // ── Queue accessors ───────────────────────────────────────────────────────

    /**
     * Returns the {@link #completedTaskQueue} for use by subclass algorithm loops.
     *
     * @return the completed-task blocking queue
     */
    public BlockingQueue<T> getCompletedTaskQueue() {
        return completedTaskQueue;
    }

    /**
     * Returns the {@link #pendingTaskQueue} for use by subclass algorithm loops or tests.
     *
     * @return the pending-task blocking queue
     */
    public BlockingQueue<T> getPendingTaskQueue() {
        return pendingTaskQueue;
    }

    /**
     * Convenience method that delegates to {@link #stoppingConditionIsNotMet()}.
     * Used by REST status endpoints to report algorithm completion.
     *
     * @return {@code true} if the algorithm has finished
     */
    public boolean isFinished() {
        return !stoppingConditionIsNotMet();
    }

    /**
     * Returns {@code true} while the algorithm should continue iterating.
     * Implemented by concrete subclasses based on their termination criteria.
     *
     * @return {@code true} if the stopping condition has not yet been reached
     */
    public abstract boolean stoppingConditionIsNotMet();

    // ── WorkerEntry inner class ───────────────────────────────────────────────

    /**
     * Mutable record of a worker's connection state, stored in {@link #workerRegistry}.
     *
     * <p>Fields are {@code volatile} because they are written by HTTP request threads
     * (heartbeat, task-claim, result-submission) and read by the watchdog scheduler
     * thread without additional synchronization.
     */
    public static class WorkerEntry {

        /** Unique identifier assigned by the worker process at startup. */
        public final String workerId;

        /**
         * IP address of the worker, as reported in heartbeat requests.
         * Volatile because it may be upgraded from {@code "unknown"} to the real
         * address if the worker first appeared via {@code claimNextTask} (which has
         * no address) and only later sent its first heartbeat.
         */
        public volatile String address;

        /**
         * Wall-clock time of the most recent heartbeat from this worker. Updated on
         * every call to {@link AbstractMaster#registerHeartbeat(String, String)} and on
         * every successful result submission.
         */
        public volatile Instant lastSeen;

        /**
         * Identifier of the task currently held by this worker, or {@code -1} if the
         * worker is idle (not evaluating anything). Set to the task ID when a task is
         * dispatched ({@link AbstractMaster#submitResult(long, String)}) and reset to
         * {@code -1} when the result arrives or the watchdog re-enqueues the task.
         */
        public volatile long currentTaskId = -1L;

        /**
         * Creates a worker entry with an unknown address. Equivalent to calling
         * {@link #WorkerEntry(String, String)} with {@code "unknown"} as the address.
         *
         * @param workerId the unique worker identifier
         */
        public WorkerEntry(String workerId) {
            this(workerId, "unknown");
        }

        /**
         * Creates a worker entry with the given identifier and address.
         * {@link #lastSeen} is initialized to the current instant.
         *
         * @param workerId the unique worker identifier
         * @param address  the worker's IP address for logging
         */
        public WorkerEntry(String workerId, String address) {
            this.workerId = workerId;
            this.address = address;
            this.lastSeen = Instant.now();
        }
    }
}

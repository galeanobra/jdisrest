package es.unex.jdisrest.distributed;

import es.unex.jdisrest.distributed.rest.MasterSpringApp;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;
import org.springframework.boot.SpringApplication;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;

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
 *   <li>Bounded retries: a task whose evaluation fails is requeued at once and, after
 *       {@link #DEFAULT_MAX_TASK_FAILURES} failed evaluations (see
 *       {@link #setMaxTaskFailures(int)}), discarded, so that a task that always fails cannot
 *       keep the workers busy forever (see {@link #failInFlightTask(long)}).</li>
 *   <li>Finishing early on request ({@code POST /api/v1/stop}, see {@link #requestStop()}):
 *       {@link #isFinished()} reports the run as finished and the default {@code run()} loops
 *       return with the current result.</li>
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
     * <p>Declared {@code public} so that {@code TaskController} can write the result into the
     * in-flight task's solution when a worker posts it (the solution object itself stays on
     * the master: the result payload carries objectives, constraints and, optionally, a
     * repaired decision vector to copy into it).
     */
    public final ConcurrentHashMap<Long, T> inFlightTasks = new ConcurrentHashMap<>();

    /**
     * Set by {@link #requestStop()} ({@code POST /api/v1/stop}). Package-private so that
     * {@link SteadyStateMaster#waitForComputedTask()} can wait on it without exposing it to
     * subclasses in other packages, which use {@link #isStopRequested()}.
     */
    final StopRequest stopRequest = new StopRequest();

    /**
     * Registry of workers that have sent at least one heartbeat. Keys are worker IDs
     * (unique strings assigned by the worker process). Values are {@link WorkerEntry}
     * instances that track the worker's last-seen time and current task.
     */
    protected final ConcurrentHashMap<String, WorkerEntry> workerRegistry = new ConcurrentHashMap<>();

    /**
     * Default number of failed evaluations after which a task is discarded instead of retried
     * (see {@link #failInFlightTask(long)}). Three attempts ride out a transient failure on one
     * worker, while a task that fails on every worker leaves the pipeline after a bounded
     * cost. Change it per master with {@link #setMaxTaskFailures(int)}.
     */
    public static final int DEFAULT_MAX_TASK_FAILURES = 3;

    /**
     * At most this many decision variables of a discarded task are written to the log; longer
     * vectors are abbreviated so that one discard cannot flood the master's log.
     */
    static final int MAX_LOGGED_VARIABLES = 50;

    /** Failed evaluations per task; decides between retrying and discarding. */
    private final TaskFailureTracker taskFailures = new TaskFailureTracker(DEFAULT_MAX_TASK_FAILURES);

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
     * {@code jdisrest.dataPath} (default: current working directory). In cluster deployments,
     * point this property at a shared directory so that workers on other nodes can read the
     * file even though the master process may be running on a different compute node.
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
     * eventually unblock even if a worker crashes mid-evaluation. These requeues do not count
     * as failed evaluations (see {@link #failInFlightTask(long)}): a worker that went silent
     * says nothing about its task.
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
     * <p>Returns {@code false} without enqueuing in two cases, and {@code TaskController}
     * answers HTTP {@code 404} to the worker in both:
     * <ul>
     *   <li>The task ID is not found in {@link #inFlightTasks}. This happens when the
     *       watchdog has already re-enqueued the task because the reporting worker was
     *       considered dead; the result arriving late is discarded to avoid
     *       double-processing.</li>
     *   <li>A stop has been requested ({@link #requestStop()}). The task leaves
     *       {@link #inFlightTasks} but its result is dropped: the algorithm finishes with
     *       the state it had when the stop was requested, and refusing the result here stops
     *       the {@code evaluations} counter of {@code GET /api/v1/status} from growing after
     *       the stop. Results accepted before the stop that the algorithm thread has not
     *       taken yet (and one being recorded at the very moment the stop lands) are still
     *       counted, then dropped.</li>
     * </ul>
     *
     * <p>Whenever the task was in flight, the worker's {@link WorkerEntry} is updated:
     * {@code currentTaskId} is reset to {@code -1} (idle) and {@code lastSeen} is refreshed,
     * and the failed evaluations recorded for the task are forgotten.
     *
     * @param taskId   the identifier of the completed task
     * @param workerId the ID of the worker submitting the result
     * @return {@code true} if the task was found and moved to the completed queue;
     *         {@code false} if the task was already removed by the watchdog or a stop has
     *         been requested
     */
    public boolean submitResult(long taskId, String workerId) {
        T task = inFlightTasks.remove(taskId);
        if (task == null) return false;
        taskFailures.forget(taskId);

        workerRegistry.computeIfPresent(workerId, (id, entry) -> {
            entry.currentTaskId = -1L;
            entry.lastSeen = Instant.now();
            return entry;
        });

        if (isStopRequested()) {
            return false;  // the run is over: nobody will process this result
        }
        completedTaskQueue.add(task);
        return true;
    }

    /**
     * Handles a failed evaluation of an in-flight task: the worker reported an error
     * ({@code POST /api/v1/tasks/{id}/error}), the master rejected its result ({@code 422},
     * or a body Spring could not decode) or the master could not serialize the task for
     * {@code GET /api/v1/tasks/next}.
     *
     * <p>The task goes back to {@link #pendingTaskQueue} so that another worker retries it,
     * unless it has now failed {@code maxTaskFailures} times (see {@link #setMaxTaskFailures}).
     * Then it is discarded: it is logged at ERROR level with its decision vector (only the
     * first values of a very long one), counted in
     * {@link #getDiscardedTaskCount()} ({@code discardedTasks} in {@code GET /api/v1/status}),
     * handed to {@link #onTaskDiscarded}, and never evaluated again, so a task that always
     * fails does not keep the workers busy forever.
     *
     * <p>A discarded task never reaches {@link #completedTaskQueue}, so the algorithm never
     * sees it: a steady-state algorithm simply loses that offspring, and a generational
     * algorithm receives a generation with fewer than {@code populationSize} results.
     *
     * <p>No-op if the task is no longer in flight (the watchdog already requeued it, or another
     * report for the same dispatch won the race), so each dispatch counts at most once.
     *
     * @param taskId the identifier of the task whose evaluation failed
     */
    public void failInFlightTask(long taskId) {
        T task = inFlightTasks.remove(taskId);
        if (task == null) return;
        // Winning the remove above makes this the only failure of the dispatch, so the count
        // read here cannot change before recordFailure.
        int attempt = taskFailures.failures(taskId) + 1;
        TaskFailureTracker.Decision decision = taskFailures.recordFailure(taskId);
        String failure = "[task-" + taskId + "] Failed evaluation " + attempt
                + " of " + taskFailures.maxFailures();
        if (decision == TaskFailureTracker.Decision.RETRY) {
            Log.info(failure + " — requeued");
            pendingTaskQueue.add(task);
        } else {
            Log.error(failure + " — discarded; its variables were " + variablesOf(task.getContents()));
            taskDiscarded(task);
            onTaskDiscarded(task);
        }
    }

    /**
     * Records a failed evaluation of an in-flight task, which is requeued or, after too many
     * failures, discarded.
     *
     * @param taskId the identifier of the task whose evaluation failed
     * @deprecated since 1.2.0, renamed to {@link #failInFlightTask(long)}, to which it
     *             delegates. Unlike in earlier versions the task is no longer requeued
     *             unconditionally: the call counts as a failed evaluation and discards the task
     *             once it reaches the failure limit ({@link #setMaxTaskFailures(int)}). The
     *             framework no longer calls this method: a subclass that overrode it (to release
     *             per-task state, count retries or log) must override
     *             {@link #failInFlightTask(long)} or {@link #onTaskDiscarded} instead, or its
     *             override silently stops running.
     */
    @Deprecated(since = "1.2.0")
    public void requeueInFlightTask(long taskId) {
        failInFlightTask(taskId);
    }

    /**
     * Framework bookkeeping for a discarded task, run by {@link #failInFlightTask(long)} just
     * before {@link #onTaskDiscarded}. Package-private, and therefore out of reach of
     * subclasses in other packages, so that accounting the framework depends on (such as
     * {@link GenerationalMaster}'s per-generation discard count) cannot be lost by an
     * {@code onTaskDiscarded} override that forgets to call {@code super}. Does nothing here.
     *
     * @param task the discarded task
     */
    void taskDiscarded(T task) {
    }

    /**
     * Hook called once for every task discarded by {@link #failInFlightTask}. The task will
     * never reach {@link #completedTaskQueue}; subclasses that keep state about in-flight tasks
     * (for example, MOEA/D's map from task to subproblem) override it to release that state.
     * Does nothing by default. The framework's own accounting does not depend on it, so an
     * override need not call {@code super}.
     *
     * <p><strong>Threading:</strong> it runs on the REST thread that handled the failure
     * report, concurrently with the algorithm thread and with other REST threads. Overrides
     * must therefore be thread-safe (use concurrent collections or the same locks as the
     * algorithm), fast and non-blocking, since the worker waits for the response. An exception
     * thrown here reaches the worker as an HTTP {@code 500}; the task is discarded and counted
     * regardless.
     *
     * @param task the discarded task
     */
    protected void onTaskDiscarded(T task) {
    }

    /**
     * Sets how many failed evaluations a task gets before it is discarded instead of retried
     * (default {@link #DEFAULT_MAX_TASK_FAILURES}). Tasks requeued by the watchdog because
     * their worker went silent do not count as failures.
     *
     * <p>May be called at any time; the new limit applies from the next failure on, also to
     * tasks that have already failed. Use a large value to restore the unbounded retries of
     * earlier versions.
     *
     * @param maxTaskFailures at least 1
     * @throws IllegalArgumentException if {@code maxTaskFailures} is less than 1; the
     *                                  previous limit is kept
     */
    public void setMaxTaskFailures(int maxTaskFailures) {
        taskFailures.setMaxFailures(maxTaskFailures);
    }

    /**
     * Returns the number of tasks discarded so far after failing too many times.
     *
     * @return discarded tasks since the master started
     */
    public long getDiscardedTaskCount() {
        return taskFailures.discardedCount();
    }

    /**
     * Describes the decision vector of a task for the discard log, whatever its encoding: the
     * flat vector of {@link SolutionVariables#flatten} when the solution type is supported,
     * otherwise the raw {@code variables()} list, abbreviated beyond
     * {@value #MAX_LOGGED_VARIABLES} values; anything that is not a solution is printed as is.
     *
     * @param contents the task's contents
     * @return a one-line description of its variables
     */
    static String variablesOf(Object contents) {
        if (!(contents instanceof Solution<?> solution)) {
            return String.valueOf(contents);
        }
        List<?> variables;
        try {
            variables = SolutionVariables.flatten(solution);
        } catch (IllegalArgumentException e) {
            variables = solution.variables();
        }
        return abbreviate(variables, MAX_LOGGED_VARIABLES);
    }

    /**
     * Prints a list as {@code List.toString()} does, keeping only its first {@code limit} elements.
     *
     * @param values the list to print
     * @param limit  how many elements to print at most; at least 1
     * @return e.g. {@code [1, 2, 3]}, or {@code [1, 2, ... 8 more]} when abbreviated
     */
    static String abbreviate(List<?> values, int limit) {
        if (values.size() <= limit) {
            return values.toString();
        }
        String head = values.subList(0, limit).toString();
        return head.substring(0, head.length() - 1) + ", ... " + (values.size() - limit) + " more]";
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Whether the run is over: a stop has been requested or the stopping criterion is met.
     * Used by the REST layer: {@code GET /api/v1/tasks/next} answers {@code 410 Gone} once it
     * is {@code true}, so that workers shut down, and {@code GET /api/v1/status} reports
     * {@code "finished": true}.
     *
     * <p>The stop is checked here, not only in {@link #stoppingConditionIsNotMet()}, so that a
     * subclass whose stopping condition ignores {@link #isStopRequested()} still sends its
     * workers away after {@code POST /api/v1/stop}.
     *
     * @return {@code true} if the algorithm has finished or has been asked to stop
     */
    public boolean isFinished() {
        return isStopRequested() || !stoppingConditionIsNotMet();
    }

    /**
     * Returns {@code true} while the algorithm should continue iterating.
     * Implemented by concrete subclasses based on their termination criteria.
     *
     * <p>Subclasses need not check {@link #isStopRequested()}: {@link #isFinished()} and the
     * default {@code run()} loops of {@link SteadyStateAlgorithm} and
     * {@link GenerationalAlgorithm} already do. They may still fold it in to short-circuit an
     * expensive criterion, as {@code SteadyStateEvolutionaryAlgorithm} does.
     *
     * @return {@code true} if the stopping condition has not yet been reached
     */
    public abstract boolean stoppingConditionIsNotMet();

    /**
     * Asks the algorithm to finish now, as if it had met its stopping criterion
     * ({@code POST /api/v1/stop}). Repeated calls are harmless; only the first is logged.
     * From then on:
     * <ul>
     *   <li>{@link #isFinished()} is {@code true}, so workers get {@code 410 Gone} on their
     *       next {@code GET /api/v1/tasks/next} and shut down. Requests already being served
     *       may still receive one task each.</li>
     *   <li>Results of the evaluations still in flight are refused ({@link #submitResult}
     *       returns {@code false}, the worker gets {@code 404}) and dropped.</li>
     *   <li>The default {@code run()} loops return with the current result, which the caller
     *       of {@code run()} writes as at a normal finish:
     *       {@link SteadyStateMaster#waitForComputedTask()} returns {@code null} within a
     *       fraction of a second, and {@link GenerationalMaster#waitForEvaluatedTasks()}
     *       returns the part of the generation it has already taken within about a second.
     *       Results still waiting in {@link #completedTaskQueue} are not processed.</li>
     * </ul>
     *
     * <p>It does not stop the REST server or the {@code status.json} writer: the process ends
     * when the caller of {@code run()} exits, as at a normal finish.
     *
     * <p><strong>Subclasses</strong> that override {@code waitForComputedTask()} or
     * {@code waitForEvaluatedTasks()} must return once {@link #isStopRequested()} is
     * {@code true} ({@code null}, or the tasks received so far): no more results arrive after a
     * stop, so a plain blocking {@code take()} would hang {@code run()} forever. Overrides of
     * {@code run()} must likewise end their loop on a stop.
     */
    public void requestStop() {
        if (stopRequest.request()) {
            Log.info("Stop requested — finishing with the current result and discarding the "
                    + inFlightTasks.size() + " evaluations in flight");
        }
    }

    /**
     * Returns whether {@link #requestStop()} has been called. Once {@code true} it stays
     * {@code true}. In {@link SteadyStateMaster} and {@link GenerationalMaster} it replaces
     * the {@code false} default of {@link SteadyStateAlgorithm#isStopRequested()} and
     * {@link GenerationalAlgorithm#isStopRequested()}, so that their {@code run()} loops
     * honour the stop.
     *
     * @return {@code true} once a stop has been requested
     */
    public boolean isStopRequested() {
        return stopRequest.isRequested();
    }

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
         * dispatched ({@link SteadyStateMaster#claimNextTask} or
         * {@link GenerationalMaster#claimNextTask}) and reset to {@code -1} when the result
         * arrives ({@link AbstractMaster#submitResult(long, String)}) or the watchdog
         * re-enqueues the task.
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

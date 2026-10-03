package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.distributed.rest.MasterSpringApp;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.Solution;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.Timings;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared REST infrastructure for {@link GenerationalMaster} and {@link SteadyStateMaster}.
 *
 * <p>This class centralises everything that is common to both the generational and
 * steady-state master variants:
 * <ul>
 *   <li>Starting the embedded Spring Boot / WebFlux server that exposes the REST API
 *       consumed by workers, and closing it again ({@link #shutdown()}).</li>
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
 *   <li>Worker registration and heartbeat tracking via {@link #workerRegistry}. Each worker
 *       holds at most one task at a time: the one it was last given ({@link #recordDispatch}).</li>
 *   <li>The watchdog method {@link #requeueOrphanTasks(long)}, invoked every
 *       {@link Timings#WATCHDOG_INTERVAL_S} seconds by {@code WatchdogScheduler}, which detects
 *       dead workers and re-enqueues their tasks to prevent the algorithm from stalling.</li>
 *   <li>Bounded retries: a task whose evaluation fails is requeued at once and, after
 *       {@link #DEFAULT_MAX_TASK_FAILURES} failed evaluations (see
 *       {@link #setMaxTaskFailures(int)}), discarded, so that a task that always fails cannot
 *       keep the workers busy forever (see {@link #failInFlightTask(long)}).</li>
 *   <li>Finishing early on request ({@code POST /api/v1/stop}, see {@link #requestStop()}):
 *       {@link #isFinished()} reports the run as finished and the default {@code run()} loops
 *       return with the current result.</li>
 * </ul>
 *
 * <h2>Arbitration</h2>
 * {@code inFlightTasks.remove(id)} is the single point that decides what happens to a task in
 * flight: whichever of a result ({@link #submitResult(long, String, Consumer)}), a failure
 * ({@link #failInFlightTask(long)}), the watchdog or a new claim of the same worker
 * ({@link #recordDispatch}) removes it first owns it, and the others find nothing to do. A result
 * is written into the task's solution only after its report has won that removal, so a losing
 * report never touches a solution another thread is reading or writing.
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
     * <p>Declared {@code public} so that {@code TaskController} can validate a posted result
     * against the in-flight task's solution before submitting it (the solution object itself
     * stays on the master: the result payload carries objectives, constraints and, optionally,
     * a repaired decision vector, which {@link #submitResult(long, String, Consumer)} copies
     * into it once the task has left this map).
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

    /**
     * Largest request body the REST server buffers by default, as a Spring data size. Spring's
     * own default (256 KiB) answered a large Lamarckian result (tens of thousands of variables)
     * with {@code 413} every time, so the task could never be completed; this one fits about a
     * million variables. Override it with the Spring property
     * {@code spring.http.codecs.max-in-memory-size} (see {@link #defaultServerProperties()}).
     */
    public static final String DEFAULT_MAX_REQUEST_SIZE = "16MB";

    /**
     * Name of the discovery file written to the {@code jdisrest.dataPath} folder (see
     * {@link #shutdown()} for when it is deleted).
     */
    public static final String ENDPOINT_FILE_NAME = ".master-endpoint";

    /**
     * Destination used only to ask the operating system which local address it would route
     * traffic through (see {@link #routableLocalAddress()}): {@code 192.0.2.1} belongs to
     * TEST-NET-1 (RFC 5737), which no real host uses. No packet is ever sent to it.
     */
    private static final String ROUTE_PROBE_ADDRESS = "192.0.2.1";

    /** The running REST server, closed by {@link #shutdown()}; {@code null} for a master built without one. */
    private final ConfigurableApplicationContext restServer;

    /** The discovery file this master wrote, or {@code null} if it wrote none. */
    private final Path endpointFile;

    /** Exact content written to {@link #endpointFile}, so that {@link #shutdown()} deletes only its own file. */
    private final String endpointJson;

    /** Set by the first call to {@link #shutdown()}, which makes later calls no-ops. */
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Starts the embedded Spring Boot REST server and writes the discovery endpoint file.
     * Blocks until the server is ready to accept connections before returning.
     *
     * <p>If the server cannot start (typically because the port is already in use) the
     * constructor throws, and no endpoint file is written: a stale file would send the workers to
     * whatever else owns that port.
     *
     * <p>The server keeps the JVM alive until {@link #shutdown()} is called (or the program ends
     * with {@link System#exit}).
     *
     * @param host the hostname or IP address to advertise in the discovery file. It must be an
     *             address the workers can reach: a blank or wildcard host ({@code 0.0.0.0},
     *             {@code ::}) is replaced by a routable address of this machine, with a warning
     *             (see {@link #advertisedHost(String)}). The server itself listens on every
     *             interface ({@code server.address}, see {@link #defaultServerProperties()}).
     * @param port the HTTP port the server should listen on; {@code 0} picks a free port, which
     *             is the one advertised
     * @throws IllegalStateException if the REST server cannot be started
     */
    protected AbstractMaster(String host, int port) {
        restServer = startRestServer(port);
        int boundPort = restServer.getEnvironment().getProperty("local.server.port", Integer.class, port);
        String advertised = advertisedHost(host);
        Log.info("REST server ready at " + urlOf(advertised, boundPort) + " — waiting for workers...");
        endpointJson = endpointJson(advertised, boundPort);
        endpointFile = writeMasterEndpointFile(endpointJson, urlOf(advertised, boundPort));
    }

    /**
     * Creates a master without a REST server and without a discovery file, for the tests of this
     * package: its task pipeline, worker registry and failure accounting work as usual, but no
     * worker can reach it.
     */
    AbstractMaster() {
        restServer = null;
        endpointFile = null;
        endpointJson = null;
    }

    // ── Spring Boot startup ───────────────────────────────────────────────────

    /**
     * Spring properties the REST server starts with, passed as Spring <em>default</em>
     * properties: the source with the lowest precedence, so that a {@code -D} system property, an
     * environment variable or an {@code application.properties} file overrides any of them.
     * <ul>
     *   <li>{@code server.address=0.0.0.0}: listen on every interface.</li>
     *   <li>{@code spring.main.banner-mode=off}.</li>
     *   <li>{@code spring.http.codecs.max-in-memory-size=}{@value #DEFAULT_MAX_REQUEST_SIZE}: the
     *       largest request body (see {@link #DEFAULT_MAX_REQUEST_SIZE}).</li>
     *   <li>{@code server.shutdown=immediate}: {@link #shutdown()} (and the end of the JVM) close
     *       the server at once instead of waiting for the requests being served, such as a
     *       {@value Timings#TASK_LONGPOLL_S}-second long-poll, whose results nobody would use.</li>
     * </ul>
     * The port is not among them: it always comes from the constructor, which advertises it.
     *
     * @return a new mutable map of property names to values, in the order above
     */
    static Map<String, Object> defaultServerProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", "0.0.0.0");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.http.codecs.max-in-memory-size", DEFAULT_MAX_REQUEST_SIZE);
        properties.put("server.shutdown", "immediate");
        return properties;
    }

    /**
     * Starts the Spring Boot / WebFlux application on the calling thread and returns once the
     * server accepts connections. The server runs on Netty's own threads, which keep the JVM
     * alive until {@link #shutdown()} closes the returned context.
     *
     * @param port the HTTP port passed to Spring via {@code --server.port}
     * @return the started application context
     * @throws IllegalStateException if Spring fails to start (for example, the port is in use)
     */
    private static ConfigurableApplicationContext startRestServer(int port) {
        SpringApplication app = new SpringApplication(MasterSpringApp.class);
        app.setWebApplicationType(WebApplicationType.REACTIVE);
        app.setDefaultProperties(defaultServerProperties());
        try {
            ConfigurableApplicationContext context = app.run("--server.port=" + port);
            Log.info("Spring Boot started (" + context.getClass().getSimpleName() + ")");
            return context;
        } catch (RuntimeException e) {
            String reason = "Could not start the REST server on port " + port + " — " + rootMessage(e);
            Log.error(reason);
            throw new IllegalStateException(reason, e);
        }
    }

    /**
     * Message of the innermost cause of an exception, which for a failed start names the real
     * problem (for example, {@code Address already in use}).
     *
     * @param e the exception
     * @return the root cause's message, or its class name if it has none
     */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() != null ? root.getMessage() : root.getClass().getName();
    }

    /**
     * Returns the host to advertise to the workers. A blank host or a wildcard address
     * ({@code 0.0.0.0}, {@code ::}) only means "every interface" to a server; a worker cannot
     * connect to it, and on another node it would reach itself. Such a host is replaced by a
     * routable address of this machine ({@link #routableLocalAddress()}), with a warning; any
     * other host is returned as given.
     *
     * @param host the host passed to the constructor; may be {@code null}
     * @return the host to write to the discovery file
     */
    static String advertisedHost(String host) {
        if (!isWildcardHost(host)) {
            return host;
        }
        String routable = routableLocalAddress();
        Log.warn("Advertised host '" + host + "' cannot be reached by workers — advertising " + routable
                + " instead; pass the address or name the workers should use");
        return routable;
    }

    /**
     * Whether a host is blank or a literal wildcard address (IPv4 {@code 0.0.0.0} or IPv6
     * {@code ::}, with or without brackets). Host names are not resolved.
     *
     * @param host the host; may be {@code null}
     * @return {@code true} if workers could not use it to reach this machine
     */
    static boolean isWildcardHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String literal = host.strip();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        try {
            return InetAddress.ofLiteral(literal).isAnyLocalAddress();
        } catch (IllegalArgumentException notALiteral) {
            return false;  // a host name
        }
    }

    /**
     * Finds an address of this machine that other machines can reach: the source address the
     * operating system would use for its default route, read from a connected UDP socket (a
     * connect sends nothing), or, when there is no such route (an isolated node), the first
     * reachable address of {@link #reachableAddress}: that of the host name, then those of the
     * network interfaces.
     *
     * @return an IP address in text form; a loopback address only when the machine has no other
     */
    static String routableLocalAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.ofLiteral(ROUTE_PROBE_ADDRESS), 9);
            InetAddress local = socket.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (IOException | RuntimeException noRoute) {
            // no default route: fall back to the host name's address, or an interface's
        }
        InetAddress hostAddress;
        try {
            hostAddress = InetAddress.getLocalHost();
        } catch (IOException e) {
            hostAddress = null;
        }
        return reachableAddress(hostAddress, interfaceAddresses());
    }

    /**
     * Picks the address to advertise on a node without a default route: the host name's address
     * unless it is a loopback, link-local or wildcard one (Debian and Ubuntu map the host name to
     * {@code 127.0.1.1}), otherwise the first interface address that is none of these, an IPv4
     * site-local one first, then another IPv4 one, then an IPv6 one; failing all of them, the host
     * name's address, or {@code 127.0.0.1}.
     *
     * @param hostAddress        the address of the host name, or {@code null} if it does not
     *                           resolve
     * @param interfaceAddresses the addresses of the network interfaces, in their order
     * @return the address in text form
     */
    static String reachableAddress(InetAddress hostAddress, List<InetAddress> interfaceAddresses) {
        if (hostAddress != null && isReachable(hostAddress)) {
            return hostAddress.getHostAddress();
        }
        return interfaceAddresses.stream()
                .filter(AbstractMaster::isReachable)
                .min(Comparator.comparingInt((InetAddress a) -> a instanceof Inet4Address
                        ? (a.isSiteLocalAddress() ? 0 : 1) : 2))
                .or(() -> Optional.ofNullable(hostAddress))
                .map(InetAddress::getHostAddress)
                .orElse("127.0.0.1");
    }

    private static boolean isReachable(InetAddress address) {
        return !address.isLoopbackAddress() && !address.isLinkLocalAddress() && !address.isAnyLocalAddress();
    }

    /** The addresses of the network interfaces that are up and neither loopback nor virtual. */
    private static List<InetAddress> interfaceAddresses() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(nic -> {
                        try {
                            return nic.isUp() && !nic.isLoopback() && !nic.isVirtual();
                        } catch (SocketException e) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .toList();
        } catch (SocketException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The URL of a server, with an IPv6 literal in brackets.
     *
     * @param host the advertised host
     * @param port the port
     * @return for example {@code http://10.0.0.1:8080} or {@code http://[fe80::1]:8080}
     */
    static String urlOf(String host, int port) {
        boolean ipv6Literal = host.indexOf(':') >= 0 && !host.startsWith("[");
        return "http://" + (ipv6Literal ? "[" + host + "]" : host) + ":" + port;
    }

    /**
     * Content of the discovery file: a JSON object with the fields {@code host}, {@code port}
     * and {@code url}, in that order, written by Jackson so that any host is escaped properly.
     *
     * @param host the advertised host
     * @param port the port the server listens on
     * @return for example {@code {"host":"10.0.0.1","port":8080,"url":"http://10.0.0.1:8080"}}
     */
    static String endpointJson(String host, int port) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("host", host);
        endpoint.put("port", port);
        endpoint.put("url", urlOf(host, port));
        try {
            return new ObjectMapper().writeValueAsString(endpoint);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the endpoint " + endpoint, e);
        }
    }

    /**
     * The folder of the discovery file and of {@code status.json}: the system property
     * {@code jdisrest.dataPath}, or the working directory when it is unset.
     *
     * @return the data folder
     */
    static Path dataPath() {
        return Path.of(System.getProperty("jdisrest.dataPath", "."));
    }

    /**
     * Writes the {@code .master-endpoint} JSON file atomically (write-then-rename) so that
     * workers can discover the master's URL without racing against a partial write.
     *
     * <p>The file is written to the directory indicated by the system property
     * {@code jdisrest.dataPath} (default: current working directory), which is created if it
     * does not exist. In cluster deployments, point this property at a shared directory so that
     * workers on other nodes can read the file even though the master process may be running on
     * a different compute node. {@link #shutdown()} deletes the file again.
     *
     * <p>Example file content:
     * <pre>{@code {"host":"10.0.0.1","port":8080,"url":"http://10.0.0.1:8080"}}</pre>
     *
     * <p>A failure is only logged: the workers can still be given the URL explicitly.
     *
     * @param json the file content ({@link #endpointJson})
     * @param url  the advertised URL, for the log
     * @return the file written, or {@code null} if it could not be written
     */
    private static Path writeMasterEndpointFile(String json, String url) {
        try {
            Path folder = dataPath();
            Files.createDirectories(folder);
            Path tmp  = folder.resolve(ENDPOINT_FILE_NAME + ".tmp");
            Path dest = folder.resolve(ENDPOINT_FILE_NAME);
            Files.writeString(tmp, json);
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Master endpoint written to " + dest + " (" + url + ")");
            return dest;
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not write .master-endpoint: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes the discovery file this master wrote, unless another master has replaced it since
     * (its content is then different), so that workers started later do not connect to a
     * server that is gone.
     */
    private void deleteMasterEndpointFile() {
        if (endpointFile == null) {
            return;
        }
        try {
            if (Files.exists(endpointFile) && endpointJson.equals(Files.readString(endpointFile))) {
                Files.deleteIfExists(endpointFile);
                Log.info("Master endpoint " + endpointFile + " deleted");
            }
        } catch (IOException e) {
            Log.warn("Could not delete " + endpointFile + ": " + e.getMessage());
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

    // ── Task dispatch (called by the masters' claimNextTask) ──────────────────

    /**
     * Records that a task has been handed to a worker: puts it in {@link #inFlightTasks} and
     * makes it the worker's {@link WorkerEntry#currentTaskId} (registering the worker, with an
     * {@code "unknown"} address, if it has not sent a heartbeat yet).
     *
     * <p>A worker holds one task at a time. If the worker's previous task is still in flight, the
     * worker asked for a new one without its result or error ever arriving (the request was lost
     * on the way, timed out, or the worker restarted under the same id), so nothing will ever
     * complete that task: it is requeued at once, without counting as a failed evaluation. Each
     * concurrent evaluation slot therefore needs its own {@code workerId}; two slots sharing one
     * keep taking each other's tasks back, which costs evaluations (never results).
     *
     * @param workerId the identifier of the worker that receives the task
     * @param task     the task handed out
     */
    protected final void recordDispatch(String workerId, T task) {
        long taskId = task.getIdentifier();
        inFlightTasks.put(taskId, task);
        long[] previous = {-1L};
        workerRegistry.compute(workerId, (id, entry) -> {
            if (entry == null) entry = new WorkerEntry(workerId);
            previous[0] = entry.currentTaskId;
            entry.currentTaskId = taskId;
            entry.lastSeen = Instant.now();
            return entry;
        });
        if (previous[0] >= 0 && previous[0] != taskId) {
            T lost = inFlightTasks.remove(previous[0]);
            if (lost != null) {
                Log.warn("[task-" + previous[0] + "] Requeued — worker " + workerId
                        + " asked for a new task without reporting this one (its result or error was lost)");
                pendingTaskQueue.add(lost);
            }
        }
    }

    /**
     * Returns the worker that currently holds a task: the one whose
     * {@link WorkerEntry#currentTaskId} points at it.
     *
     * @param taskId the task
     * @return the worker's identifier, or {@code null} if no registered worker holds the task
     */
    String holderOf(long taskId) {
        for (WorkerEntry entry : workerRegistry.values()) {
            if (entry.currentTaskId == taskId) {
                return entry.workerId;
            }
        }
        return null;
    }

    /**
     * Clears every worker's pointer to a task that has just left {@link #inFlightTasks}, so that
     * the watchdog never pulls the task (requeued, perhaps already handed to another worker)
     * away on behalf of a worker that no longer holds it.
     *
     * @param taskId the task that is no longer in flight
     */
    private void releaseHolders(long taskId) {
        for (WorkerEntry entry : workerRegistry.values()) {
            if (entry.currentTaskId == taskId) {
                workerRegistry.computeIfPresent(entry.workerId, (id, current) -> {
                    if (current.currentTaskId == taskId) current.currentTaskId = -1L;
                    return current;
                });
            }
        }
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    /**
     * Detects dead workers and re-enqueues their in-flight tasks so the algorithm does
     * not stall. Called periodically (every {@link Timings#WATCHDOG_INTERVAL_S} s) by
     * {@code WatchdogScheduler}.
     *
     * <p>A worker is considered dead if its {@link WorkerEntry#lastSeen} timestamp is older
     * than {@code timeoutSeconds}. For each dead worker:
     * <ol>
     *   <li>The worker's entry is removed from the registry. The check and the removal are one
     *       atomic step per entry, so a worker whose heartbeat or claim lands at that moment is
     *       either kept (it is no longer stale) or registered afresh, never removed together
     *       with a task it has just been given.</li>
     *   <li>If the worker held an in-flight task ({@link WorkerEntry#currentTaskId} ≥ 0),
     *       that task is removed from {@link #inFlightTasks} and added back to
     *       {@link #pendingTaskQueue}.</li>
     * </ol>
     * When at least one worker was removed, a summary with their number and the queue sizes is
     * logged.
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
        int evicted = 0;
        for (String workerId : workerRegistry.keySet()) {
            WorkerEntry[] removed = {null};
            workerRegistry.computeIfPresent(workerId, (id, entry) -> {
                if (!entry.lastSeen.isBefore(threshold)) return entry;
                removed[0] = entry;
                return null;
            });
            WorkerEntry deadWorker = removed[0];
            if (deadWorker == null) continue;
            evicted++;
            Log.warn("Worker timeout: " + deadWorker.workerId + " (last seen: " + deadWorker.lastSeen + ")");
            long taskId = deadWorker.currentTaskId;
            if (taskId >= 0) {
                T orphan = inFlightTasks.remove(taskId);
                if (orphan != null) {
                    Log.info("Requeueing task " + taskId + " from dead worker " + deadWorker.workerId);
                    pendingTaskQueue.add(orphan);
                }
            }
        }
        if (evicted > 0) {
            Log.info("Watchdog: " + evicted + " worker(s) removed. "
                    + "Active workers: " + aliveWorkerCount(timeoutSeconds)
                    + " | Pending tasks: " + pendingTaskQueue.size()
                    + " | In-flight tasks: " + inFlightTasks.size());
        }
    }

    // ── Task result submission (called by TaskController) ─────────────────────

    /**
     * Moves a completed task from {@link #inFlightTasks} to {@link #completedTaskQueue}
     * so the main algorithm thread can process it. The caller must have written the result into
     * the task's solution already.
     *
     * <p>Since 1.2.0 the REST layer calls {@link #submitResult(long, String, Consumer)}
     * instead, which writes the result only after this master has decided to accept it; an
     * override of this method no longer sees the results posted by workers and must override
     * the three-argument method instead.
     *
     * @param taskId   the identifier of the completed task
     * @param workerId the ID of the worker submitting the result; may be {@code null}
     * @return {@code true} if the task was found and moved to the completed queue;
     *         {@code false} if the task was not in flight or a stop has been requested
     */
    public boolean submitResult(long taskId, String workerId) {
        return submitResult(taskId, workerId, task -> { });
    }

    /**
     * Records the result of an in-flight task and moves the task from {@link #inFlightTasks}
     * to {@link #completedTaskQueue} so the main algorithm thread can process it.
     *
     * <p>The task is first taken out of {@link #inFlightTasks}; only if that removal succeeds
     * (see "Arbitration" in the class description) is {@code recorder} called to write the
     * result into the task's solution, and only then is the task enqueued. Two reports of the
     * same task can therefore never write into its solution at the same time, and a report that
     * loses writes nothing.
     *
     * <p>Any worker's result is accepted, also one from a worker that no longer holds the task
     * (its late result is still a valid evaluation of the same solution); the worker that holds
     * it then gets {@code 404} for its own. Returns {@code false} without recording anything in
     * two cases, and {@code TaskController} answers HTTP {@code 404} to the worker in both:
     * <ul>
     *   <li>The task ID is not found in {@link #inFlightTasks}, typically because the watchdog
     *       re-enqueued it after the reporting worker was considered dead. The late result is
     *       discarded to avoid double-processing, and a warning is logged.</li>
     *   <li>A stop has been requested ({@link #requestStop()}). The task leaves
     *       {@link #inFlightTasks} but its result is dropped: the algorithm finishes with
     *       the state it had when the stop was requested, and refusing the result here stops
     *       the {@code evaluations} counter of {@code GET /api/v1/status} from growing after
     *       the stop. Results accepted before the stop that the algorithm thread has not
     *       taken yet (and one being recorded at the very moment the stop lands) are still
     *       counted, then dropped.</li>
     * </ul>
     *
     * <p>Whenever the task was in flight, every worker's {@link WorkerEntry#currentTaskId} that
     * points at it is reset to {@code -1}, the reporting worker's {@code lastSeen} is refreshed,
     * and the failed evaluations recorded for the task are forgotten. When it was not in flight,
     * only the reporting worker's pointer is reset, if it still points at the task.
     * {@code workerId} may be {@code null} (a client that does not send it): the result is
     * recorded all the same.
     *
     * <p>If {@code recorder} throws, the result could not be recorded: the task is handled as a
     * failed evaluation (requeued, or discarded after too many failures, as in
     * {@link #failInFlightTask(long)}) and the exception is rethrown.
     *
     * @param taskId   the identifier of the completed task
     * @param workerId the ID of the worker submitting the result; may be {@code null}
     * @param recorder writes the result into the task's solution; called at most once, on the
     *                 calling thread, before the task is enqueued
     * @return {@code true} if the result was recorded and the task moved to the completed queue;
     *         {@code false} if the task was not in flight or a stop has been requested
     */
    public boolean submitResult(long taskId, String workerId, Consumer<? super T> recorder) {
        T task = inFlightTasks.remove(taskId);
        if (task == null) {
            Log.warn("Result for unknown/expired taskId: " + taskId + " from " + workerId);
            if (workerId != null) {
                workerRegistry.computeIfPresent(workerId, (id, entry) -> {
                    if (entry.currentTaskId == taskId) entry.currentTaskId = -1L;
                    return entry;
                });
            }
            return false;
        }
        releaseHolders(taskId);
        if (workerId != null) {
            workerRegistry.computeIfPresent(workerId, (id, entry) -> {
                entry.lastSeen = Instant.now();
                return entry;
            });
        }

        if (isStopRequested()) {
            taskFailures.forget(taskId);
            return false;  // the run is over: nobody will process this result
        }
        try {
            recorder.accept(task);
        } catch (RuntimeException e) {
            Log.warn("[task-" + taskId + "] Could not record the result from " + workerId + ": " + e.getMessage());
            failedEvaluation(task);
            throw e;
        }
        taskFailures.forget(taskId);
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
     * fails does not keep the workers busy forever. Either way, every worker's
     * {@link WorkerEntry#currentTaskId} that still points at the task is reset to {@code -1}.
     *
     * <p>A discarded task never reaches {@link #completedTaskQueue}, so the algorithm never
     * sees it: a steady-state algorithm simply loses that offspring, and a generational
     * algorithm receives a generation with fewer than {@code populationSize} results.
     *
     * <p>No-op if the task is no longer in flight (the watchdog already requeued it, or another
     * report for the same dispatch won the race), so each dispatch counts at most once.
     *
     * <p>This method does not check which worker reports the failure; the REST layer calls
     * {@link #failInFlightTask(long, String)}, which does and then calls this method.
     *
     * @param taskId the identifier of the task whose evaluation failed
     */
    public void failInFlightTask(long taskId) {
        T task = inFlightTasks.remove(taskId);
        if (task == null) return;
        releaseHolders(taskId);
        failedEvaluation(task);
    }

    /**
     * Handles a failure report from a worker, as {@link #failInFlightTask(long)} does, but only
     * if the reporting worker holds the task. A report from a worker that no longer holds it
     * (for example, one evicted by the watchdog whose task has been handed to another worker
     * since) is ignored with a warning, so it cannot requeue, count against or discard the
     * evaluation another live worker is doing.
     *
     * <p>The report is accepted when {@code workerId} is the task's holder (the worker whose
     * {@link WorkerEntry#currentTaskId} points at it), when no registered worker holds the task,
     * or when {@code workerId} is {@code null} (the reporter is unknown, for instance because the
     * request body could not be decoded).
     *
     * @param taskId   the identifier of the task whose evaluation failed
     * @param workerId the worker that reports the failure; may be {@code null}
     * @return {@code true} if the report was accepted and handed to
     *         {@link #failInFlightTask(long)}; {@code false} if the task was not in flight or
     *         is held by another worker
     */
    public boolean failInFlightTask(long taskId, String workerId) {
        if (!inFlightTasks.containsKey(taskId)) {
            if (workerId != null) {
                workerRegistry.computeIfPresent(workerId, (id, entry) -> {
                    if (entry.currentTaskId == taskId) entry.currentTaskId = -1L;
                    return entry;
                });
            }
            return false;
        }
        String holder = holderOf(taskId);
        if (workerId != null && holder != null && !holder.equals(workerId)) {
            Log.warn("[task-" + taskId + "] Failure report from " + workerId
                    + " ignored — the task is now held by " + holder);
            return false;
        }
        failInFlightTask(taskId);
        return true;
    }

    /**
     * Counts one failed evaluation of a task that has just been taken out of
     * {@link #inFlightTasks}, then requeues or discards it.
     *
     * @param task the task whose evaluation failed
     */
    private void failedEvaluation(T task) {
        long taskId = task.getIdentifier();
        // Winning the remove that took the task out of flight makes this the only failure of the
        // dispatch, so the count read here cannot change before recordFailure.
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
     * Hook called once for every task discarded after failing too many times (see
     * {@link #failInFlightTask(long)}). The task will
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
     * <p>Until {@link #isReady()} is {@code true} the run has not started, so it is not
     * finished either: the stopping condition is not consulted then (unless a stop has been
     * requested, this returns {@code false}), because many criteria cannot be evaluated before
     * the algorithm has built its initial state.
     *
     * @return {@code true} if the algorithm has finished or has been asked to stop
     */
    public boolean isFinished() {
        return isStopRequested() || (isReady() && !stoppingConditionIsNotMet());
    }

    /**
     * Returns {@code true} once the algorithm can hand out tasks and answer whether it has
     * finished. The REST server starts in the constructor, before the algorithm has built its
     * initial state; subclasses whose stopping condition cannot be evaluated until then override
     * this method. The default is {@code true}.
     *
     * <p>While it is {@code false}, workers are told to come back later: {@code claimNextTask}
     * hands out nothing ({@code GET /api/v1/tasks/next} answers {@code 204}), and
     * {@link #isFinished()} is {@code false} without consulting the stopping condition, so that
     * {@code GET /api/v1/status} and {@code status.json} work from the moment the server starts
     * (reporting the run as running), and {@code POST /api/v1/config} answers {@code 503} (try
     * again shortly). It is called from REST threads, so it must be thread-safe and cheap.
     *
     * @return {@code true} if the algorithm is ready to serve workers
     */
    public boolean isReady() {
        return true;
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
     *   <li>Results of the evaluations still in flight are refused
     *       ({@link #submitResult(long, String, Consumer)} returns {@code false}, the worker
     *       gets {@code 404}) and dropped.</li>
     *   <li>The default {@code run()} loops return with the current result, which the caller
     *       of {@code run()} writes as at a normal finish:
     *       {@link SteadyStateMaster#waitForComputedTask()} returns {@code null} within a
     *       fraction of a second, and {@link GenerationalMaster#waitForEvaluatedTasks()}
     *       returns the part of the generation it has already taken within about a second.
     *       Results still waiting in {@link #completedTaskQueue} are not processed.</li>
     * </ul>
     *
     * <p>It does not stop the REST server or the {@code status.json} writer; {@link #shutdown()}
     * does, as at a normal finish.
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

    /**
     * Shuts the master down once its result is no longer needed from the workers, typically right
     * after {@code run()} returns and the result has been written:
     * <ol>
     *   <li>If the run is still going, it is stopped as by {@link #requestStop()} (so that a
     *       {@code run()} on another thread returns instead of waiting for results that can no
     *       longer arrive).</li>
     *   <li>The {@code .master-endpoint} file this master wrote is deleted, unless another master
     *       has replaced it since, so that workers started later do not look for a server that
     *       is gone.</li>
     *   <li>The REST server is closed at once: requests still being served are cut off, and
     *       workers see connection errors from then on (the bundled ones give up after a few
     *       attempts).</li>
     *   <li>The {@code status.json} writer (see {@code MasterFacade.init}) writes a last snapshot,
     *       which reports the run as finished, and stops.</li>
     * </ol>
     *
     * <p>Without this call the REST server's threads keep the JVM alive after {@code main}
     * returns, so a program had to end with {@link System#exit}; with it, the JVM exits on its
     * own once the program's threads finish. Repeated calls do nothing. The master cannot be
     * restarted, and the static facade of the REST layer is not reset, so start a new run in a
     * new JVM.
     */
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        boolean finished;
        try {
            finished = isFinished();
        } catch (RuntimeException e) {
            finished = false;  // a stopping condition that cannot be evaluated: stop to be safe
        }
        if (!finished) {
            requestStop();
        }
        deleteMasterEndpointFile();
        if (restServer != null) {
            restServer.close();
            Log.info("REST server closed");
        }
        MasterFacade.stopStatusFileWriter();
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
         * Wall-clock time this worker was last heard from. Updated on every call to
         * {@link AbstractMaster#registerHeartbeat(String, String)}, on every task it claims
         * and on every result it submits for a task in flight.
         */
        public volatile Instant lastSeen;

        /**
         * Identifier of the task currently held by this worker, or {@code -1} if the
         * worker is idle (not evaluating anything). Set to the task ID when a task is
         * dispatched ({@link AbstractMaster#recordDispatch}, called by
         * {@link SteadyStateMaster#claimNextTask} and {@link GenerationalMaster#claimNextTask})
         * and reset to {@code -1} when the task leaves the in-flight map: its result is accepted
         * ({@link AbstractMaster#submitResult(long, String, Consumer)}) or its evaluation fails
         * ({@link AbstractMaster#failInFlightTask(long)}). When the watchdog finds the worker
         * dead it removes the whole entry and requeues this task.
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

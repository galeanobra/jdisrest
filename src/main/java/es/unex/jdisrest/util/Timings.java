package es.unex.jdisrest.util;

/**
 * Centralised timing constants for the master/worker protocol.
 *
 * <p>All values are in seconds unless suffixed with {@code _MS}. The relationships
 * encoded here are deliberate:
 * <ul>
 *   <li>{@link #WORKER_TIMEOUT_S} is exactly three times {@link #HEARTBEAT_INTERVAL_S},
 *       so a worker must miss at least three consecutive heartbeats before it is
 *       considered dead.</li>
 *   <li>{@link #WATCHDOG_INTERVAL_S} is shorter than {@link #WORKER_TIMEOUT_S} so a
 *       dead worker is detected within roughly one timeout window.</li>
 *   <li>{@link #TASK_LONGPOLL_S} is the long-poll window opened by
 *       {@code GET /api/v1/tasks/next}; workers should set their HTTP read timeout a
 *       few seconds above this value to absorb network latency.</li>
 * </ul>
 *
 * <p>If you change any of these, also update the matching constants on the worker
 * side ({@code Worker.HEARTBEAT_INTERVAL} in Python and {@code RestWorker} in Java).
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class Timings {

    private Timings() {}

    /** How often workers send a heartbeat to the master. */
    public static final int HEARTBEAT_INTERVAL_S = 15;

    /** Maximum time without a heartbeat before a worker is considered dead. */
    public static final long WORKER_TIMEOUT_S = 45L;

    /** Period at which the watchdog scans for dead workers. */
    public static final long WATCHDOG_INTERVAL_S = 30L;

    /** Same as {@link #WATCHDOG_INTERVAL_S} expressed in milliseconds (for {@code @Scheduled}). */
    public static final long WATCHDOG_INTERVAL_MS = WATCHDOG_INTERVAL_S * 1000L;

    /** Long-poll window served by {@code GET /api/v1/tasks/next}. */
    public static final int TASK_LONGPOLL_S = 30;
}

package es.unex.jdisrest.util;

/**
 * Centralised timing constants for the master/worker protocol.
 *
 * <p>All values are in seconds unless suffixed with {@code _MS}. The relationships
 * encoded here are deliberate:
 * <ul>
 *   <li>{@link #WORKER_TIMEOUT_S} is three times {@link #HEARTBEAT_INTERVAL_S}: a worker
 *       is considered dead after that long without any contact (heartbeat, task claim or
 *       result), which is about three missed heartbeats. Workers wait the interval
 *       <em>after</em> each heartbeat request completes, so on a slow network the real
 *       period is a little longer and slightly fewer beats fit in the timeout.</li>
 *   <li>{@link #WATCHDOG_INTERVAL_S} is shorter than {@link #WORKER_TIMEOUT_S}, so a dead
 *       worker is detected between {@link #WORKER_TIMEOUT_S} and
 *       {@link #WORKER_TIMEOUT_S} + {@link #WATCHDOG_INTERVAL_S} seconds after it was last
 *       heard from.</li>
 *   <li>{@link #TASK_LONGPOLL_S} is the long-poll window opened by
 *       {@code GET /api/v1/tasks/next}; workers must set their HTTP read timeout a
 *       few seconds above this value to absorb network latency. A worker whose poll
 *       times out first asks again while the master may still hand the first poll a
 *       task, which then sits in flight until that worker's next claim requeues it.</li>
 * </ul>
 *
 * <p>The Java worker derives its timings from these constants ({@code RestWorker}: the
 * read timeout of {@code GET /next} is {@link #TASK_LONGPOLL_S} plus 10 s, and a heartbeat
 * waits, and is retried after, a third of {@link #HEARTBEAT_INTERVAL_S}). The Python
 * worker cannot read them: if you change any of these, also update the matching class
 * constants of {@code jdisrest.Worker} ({@code HEARTBEAT_INTERVAL}, {@code HEARTBEAT_TIMEOUT},
 * {@code HEARTBEAT_RETRY_DELAY} and {@code REQUEST_TIMEOUT}). They are compile-time constants,
 * so Java code compiled against them (including a downstream project's) keeps the old values
 * until it is recompiled.
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

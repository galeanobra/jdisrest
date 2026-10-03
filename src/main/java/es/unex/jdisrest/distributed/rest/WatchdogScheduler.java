package es.unex.jdisrest.distributed.rest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import es.unex.jdisrest.util.Timings;

/**
 * Spring-managed scheduler that periodically detects and recovers from worker failures.
 *
 * <p>Workers send a heartbeat to {@code POST /api/v1/workers/heartbeat?workerId=...&address=...}
 * every {@link Timings#HEARTBEAT_INTERVAL_S} seconds from a dedicated background thread,
 * independently of how long the current evaluation takes. If a worker has not been heard from
 * (heartbeat, task claim or result) for {@link Timings#WORKER_TIMEOUT_S} seconds, the watchdog
 * considers it dead, re-queues any in-flight task it held (drops it instead once the master needs
 * no more results), and removes it from the registry so new requests from the same worker ID are
 * treated as fresh connections. A dead worker is therefore noticed between
 * {@link Timings#WORKER_TIMEOUT_S} and {@link Timings#WORKER_TIMEOUT_S} +
 * {@link Timings#WATCHDOG_INTERVAL_S} seconds after it was last heard from.
 *
 * <p>This component is required for liveness: without it, a single worker crash would leave
 * its task permanently in-flight and cause {@code GenerationalMaster#waitForEvaluatedTasks()} or the
 * steady-state loop to block indefinitely.
 *
 * <p>The watchdog only acts when a master is registered.
 * During the Spring Boot startup window — before the master object is constructed — it returns
 * immediately to avoid spurious log noise.
 *
 * <h2>Timing parameters</h2>
 * All timings live in {@link Timings}: heartbeat interval, watchdog interval, and
 * worker timeout. The {@code fixedDelay} semantics mean the countdown starts
 * <em>after</em> each invocation completes, not at a fixed wall-clock cadence.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@Component
public class WatchdogScheduler {

    /**
     * Detects dead workers and recovers their in-flight tasks.
     *
     * <p>Runs every {@link Timings#WATCHDOG_INTERVAL_MS} ms (after the previous execution
     * completes) and calls {@link MasterFacade#requeueOrphanTasks}, which re-enqueues the
     * in-flight tasks whose owner has not been heard from within
     * {@link Timings#WORKER_TIMEOUT_S} (drops them instead once the master needs no more
     * results), removes the corresponding entries from the worker registry, and, if any worker
     * was removed, logs how many together with the queue depths so operators can monitor
     * recovery.
     */
    @Scheduled(fixedDelayString = "#{T(es.unex.jdisrest.util.Timings).WATCHDOG_INTERVAL_MS}")
    public void checkDeadWorkers() {
        if (!MasterFacade.hasMaster()) return; // master not yet constructed
        MasterFacade.requeueOrphanTasks(Timings.WORKER_TIMEOUT_S);
    }
}

package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.GenerationalMaster;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

/**
 * Spring-managed scheduler that periodically detects and recovers from worker failures.
 *
 * <p>Workers send a heartbeat to {@code PUT /api/v1/workers/{id}/heartbeat} every 15 seconds
 * from a dedicated background thread, independently of how long the current evaluation takes.
 * If no heartbeat arrives within {@link Timings#WORKER_TIMEOUT_S} (45 s = 3 missed beats),
 * the watchdog considers the worker dead, re-queues any in-flight task it held, and removes
 * it from the registry so new requests from the same worker ID are treated as fresh connections.
 *
 * <p>This component is required for liveness: without it, a single worker crash would leave
 * its task permanently in-flight and cause {@code GenerationalMaster#waitForEvaluatedTasks()} or the
 * steady-state loop to block indefinitely.
 *
 * <p>The watchdog only acts when at least one master instance ({@link SteadyStateMaster} or
 * {@link GenerationalMaster}) is active. During the Spring Boot startup window — before the master
 * object is constructed — it returns immediately to avoid spurious log noise.
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
     * completes). On each invocation:
     * <ol>
     *   <li>Counts alive workers before cleanup (for logging purposes).</li>
     *   <li>Calls {@link MasterFacade#requeueOrphanTasks} to re-enqueue in-flight tasks
     *       whose owner has not sent a heartbeat within {@link Timings#WORKER_TIMEOUT_S},
     *       and removes the corresponding entries from the worker registry.</li>
     *   <li>Counts alive workers after cleanup.</li>
     *   <li>If any workers were evicted, logs the number removed and the current queue
     *       depths so operators can monitor recovery.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "#{T(es.unex.jdisrest.util.Timings).WATCHDOG_INTERVAL_MS}")
    public void checkDeadWorkers() {
        if (SteadyStateMaster.getInstance() == null
                && GenerationalMaster.getInstance() == null) return; // master not yet constructed

        int before = MasterFacade.aliveWorkerCount(Timings.WORKER_TIMEOUT_S);
        MasterFacade.requeueOrphanTasks(Timings.WORKER_TIMEOUT_S);
        int after = MasterFacade.aliveWorkerCount(Timings.WORKER_TIMEOUT_S);

        if (before != after) {
            Log.info("Watchdog: " + (before - after) + " worker(s) removed. "
                + "Active workers: " + after
                + " | Pending tasks: "  + MasterFacade.getPendingTaskQueue().size()
                + " | In-flight tasks: " + MasterFacade.inFlightCount());
        }
    }
}

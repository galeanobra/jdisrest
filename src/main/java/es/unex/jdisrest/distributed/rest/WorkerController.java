package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.util.Timings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller that handles worker registration, keep-alive heartbeats, and
 * cluster-state diagnostics.
 *
 * <p>Workers are not pre-registered; they announce themselves to the master on
 * their first {@code POST /heartbeat} call and continue sending heartbeats every
 * ~15 seconds to prove they are still alive. The {@link WatchdogScheduler} uses
 * the absence of a heartbeat for more than 45 seconds as the signal that a
 * worker has crashed or lost network connectivity.
 *
 * <p>All methods return non-blocking {@link Mono} pipelines. The heartbeat
 * endpoint does not dispatch to a separate scheduler because
 * {@link MasterFacade#registerHeartbeat} performs only a
 * {@link java.util.concurrent.ConcurrentHashMap} write and is non-blocking.
 *
 * @see MasterFacade#registerHeartbeat(String, String)
 * @see WatchdogScheduler
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    /**
     * Keep-alive heartbeat — doubles as the initial worker registration call.
     *
     * <p>On the <em>first</em> call from a given {@code workerId}, the master
     * creates a new entry in the worker registry with the supplied address and
     * the current timestamp. On every subsequent call the timestamp is refreshed.
     * Because both registration and renewal go through the same code path, workers
     * do not need a separate "register" step — they simply start heartbeating.
     *
     * <p>The {@link WatchdogScheduler} considers a worker dead when no heartbeat
     * has been seen for {@link Timings#WORKER_TIMEOUT_S} (45 s), which allows for
     * three consecutive missed intervals at the default 15-second cadence.
     *
     * <p>Always returns {@code 200 OK}.
     *
     * @param workerId opaque worker identifier (e.g. {@code "worker-01"}); must be
     *                 unique across the cluster for correct per-worker tracking
     * @param address  optional network address of the worker (e.g. {@code "10.0.0.5"});
     *                 used for logging and diagnostics; defaults to an empty string
     *                 if not supplied
     * @return a {@link Mono} emitting a {@code 200 OK} {@link ResponseEntity} with
     *         no body
     */
    @PostMapping("/heartbeat")
    public Mono<ResponseEntity<Void>> heartbeat(
            @RequestParam("workerId") String workerId,
            @RequestParam(value = "address", required = false, defaultValue = "") String address) {

        return Mono.fromRunnable(() ->
                MasterFacade.registerHeartbeat(workerId, address)
        ).thenReturn(ResponseEntity.<Void>ok().build());
    }

    /**
     * Cluster-state snapshot for diagnostics and monitoring.
     *
     * <p>Returns a JSON object with the following fields:
     * <ul>
     *   <li>{@code aliveWorkers} — number of workers that have sent a heartbeat
     *       within the last 45 seconds.</li>
     *   <li>{@code totalEvaluations} — cumulative number of evaluations successfully
     *       submitted since the master started.</li>
     *   <li>{@code totalDispatched} — cumulative number of tasks sent out to workers
     *       (includes tasks that were later requeued due to worker failure).</li>
     *   <li>{@code pendingTasks} — current size of {@code pendingTaskQueue}
     *       (tasks waiting to be claimed by a worker).</li>
     *   <li>{@code inFlightTasks} — current size of {@code inFlightTasks}
     *       (tasks claimed by workers but not yet returned).</li>
     *   <li>{@code queuedResults} — current size of {@code completedTaskQueue}
     *       (evaluations completed but not yet consumed by the algorithm thread).</li>
     *   <li>{@code workers} — the full worker registry map (worker id → metadata),
     *       including workers that may now be considered dead.</li>
     * </ul>
     *
     * <p>This endpoint runs on the Netty event-loop thread because all
     * {@link MasterFacade} reads involved are non-blocking atomic reads.
     *
     * @return a {@link Mono} emitting a {@code 200 OK} {@link ResponseEntity}
     *         whose body is a {@link Map} of diagnostic fields
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> status() {
        return Mono.fromCallable(() ->
                ResponseEntity.ok(Map.of(
                        "aliveWorkers",      MasterFacade.aliveWorkerCount(Timings.WORKER_TIMEOUT_S),
                        "totalEvaluations",  MasterFacade.getTotalEvaluations(),
                        "totalDispatched",   MasterFacade.getTotalTasksDispatched(),
                        "pendingTasks",      MasterFacade.getPendingTaskQueue().size(),
                        "inFlightTasks",     MasterFacade.inFlightCount(),
                        "queuedResults",     MasterFacade.getCompletedTaskQueue().size(),
                        "workers",           MasterFacade.getWorkerRegistry()
                ))
        );
    }
}

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
 * their first {@code POST /heartbeat} call (or their first task claim) and continue
 * sending heartbeats every {@link Timings#HEARTBEAT_INTERVAL_S} seconds to prove they
 * are still alive. The {@link WatchdogScheduler} uses the absence of any contact for
 * more than {@link Timings#WORKER_TIMEOUT_S} seconds as the signal that a
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
     * <p>The {@link WatchdogScheduler} considers a worker dead when it has not been
     * heard from for {@link Timings#WORKER_TIMEOUT_S} seconds, three times
     * {@link Timings#HEARTBEAT_INTERVAL_S}.
     *
     * <p>Returns {@code 200 OK}; {@code 400} (Spring's default error body) when
     * {@code workerId} is missing, and {@code 500} when no master is registered.
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
     *   <li>{@code aliveWorkers} — number of workers heard from within the last
     *       {@link Timings#WORKER_TIMEOUT_S} seconds.</li>
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
     *       sorted by worker id, including workers that may now be considered dead.</li>
     * </ul>
     * The keys always come in this order. Before a master is registered every count is
     * {@code 0} and {@code workers} is empty.
     *
     * <p>This endpoint runs on the Netty event-loop thread because all
     * {@link MasterFacade} reads involved are non-blocking atomic reads.
     *
     * @return a {@link Mono} emitting a {@code 200 OK} {@link ResponseEntity}
     *         whose body is a {@link Map} of diagnostic fields
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> status() {
        return Mono.fromCallable(() -> ResponseEntity.ok(MasterFacade.clusterStatus()));
    }
}

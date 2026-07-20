package es.unex.jdisrest.distributed.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller that exposes a high-level progress summary for the running
 * experiment.
 *
 * <p>Mapped to {@code GET /api/v1/status}. The response is designed to be
 * consumed by monitoring tools (e.g. {@code monitor.py}) without requiring
 * access to the master node's log files. The file-based counterpart written
 * periodically by {@link MasterFacade#writeStatusFile()} uses the exact same
 * JSON format so that both sources can be parsed by the same code.
 *
 * <p>Example response:
 * <pre>{@code
 * {
 *   "running":                  true,
 *   "finished":                 false,
 *   "evaluations":              45320,
 *   "maxEvaluations":           100000,
 *   "progress":                 0.4532,
 *   "elapsedSeconds":           3600,
 *   "estimatedSecondsRemaining": 4322,
 *   "aliveWorkers":             23,
 *   "inFlightTasks":            15,
 *   "pendingTasks":             8
 * }
 * }</pre>
 *
 * @see MasterFacade#init(int, int)
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    /**
     * Returns a progress snapshot of the current experiment run.
     *
     * <p>Response fields:
     * <ul>
     *   <li><strong>{@code running}</strong> — {@code true} while the algorithm is
     *       still executing; {@code false} once it has finished. Inverse of
     *       {@code finished}.</li>
     *   <li><strong>{@code finished}</strong> — {@code true} once the algorithm has
     *       completed all evaluations or met its stopping criterion.</li>
     *   <li><strong>{@code evaluations}</strong> — cumulative number of evaluations
     *       successfully completed and recorded by the master since startup.</li>
     *   <li><strong>{@code maxEvaluations}</strong> — the total number of evaluations
     *       configured for this run (set via {@link MasterFacade#init}).</li>
     *   <li><strong>{@code progress}</strong> — {@code evaluations / maxEvaluations},
     *       clamped to {@code [0.0, 1.0]}. Zero if {@code maxEvaluations} has not
     *       been set yet.</li>
     *   <li><strong>{@code elapsedSeconds}</strong> — wall-clock seconds since the
     *       algorithm started (since {@link MasterFacade#init} was called). Zero if
     *       the algorithm has not yet started.</li>
     *   <li><strong>{@code estimatedSecondsRemaining}</strong> — ETA in seconds,
     *       computed as {@code elapsedSeconds / progress * (1 - progress)}.
     *       Returns {@code -1} when progress is below 1 % (not yet computable),
     *       when the algorithm has already finished, or when elapsed time is zero.</li>
     *   <li><strong>{@code aliveWorkers}</strong> — number of workers that have sent
     *       a heartbeat within the last 45 seconds.</li>
     *   <li><strong>{@code inFlightTasks}</strong> — number of tasks currently
     *       claimed by workers and awaiting a result or error response.</li>
     *   <li><strong>{@code pendingTasks}</strong> — number of tasks sitting in
     *       {@code pendingTaskQueue} waiting to be dispatched to a worker.</li>
     * </ul>
     *
     * <p>This endpoint runs on the Netty event-loop thread; all reads from
     * {@link MasterFacade} are non-blocking atomic reads or lock-free queue
     * size queries.
     *
     * @return a {@link Mono} emitting a {@code 200 OK} {@link ResponseEntity}
     *         whose body is an ordered map of progress fields
     */
    @GetMapping
    public Mono<ResponseEntity<StatusSnapshot>> status() {
        return Mono.fromCallable(() -> ResponseEntity.ok(MasterFacade.currentStatus()));
    }
}

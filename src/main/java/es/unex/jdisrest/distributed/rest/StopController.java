package es.unex.jdisrest.distributed.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller that finishes the run early: {@code POST /api/v1/stop}.
 *
 * <p>The master stops as if it had met its stopping criterion (see
 * {@link es.unex.jdisrest.distributed.AbstractMaster#requestStop()}):
 * <ul>
 *   <li>it hands out no more tasks, so the workers get {@code 410 Gone} on their next
 *       {@code GET /api/v1/tasks/next} and shut down;</li>
 *   <li>it refuses the results of the evaluations still in flight ({@code 404}, which both
 *       bundled workers log and move past), so {@code evaluations} in
 *       {@code GET /api/v1/status} stops growing;</li>
 *   <li>the algorithm's {@code run()} returns its current result, and the caller of
 *       {@code run()} writes it, as at a normal finish.</li>
 * </ul>
 * Use it to end a long run cleanly instead of killing the process, which would lose the
 * final result. The REST server keeps answering until the process exits.
 *
 * <p>Response codes:
 * <ul>
 *   <li>{@code 202 Accepted} — the stop is under way; the body is the status snapshot of
 *       {@code GET /api/v1/status}, already with {@code "finished": true}. Repeated requests
 *       are harmless and answer the same.</li>
 *   <li>{@code 503 Service Unavailable} — no master is running.</li>
 * </ul>
 *
 * <p>The handler only sets a flag and reads lock-free counters, so it runs on the Netty
 * event loop without offloading.
 *
 * <p>Like the rest of the protocol, the endpoint has no authentication: anyone who can reach
 * the master's port can stop the run.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
@RestController
@RequestMapping("/api/v1/stop")
public class StopController {

    /**
     * Requests the stop and reports the resulting status.
     *
     * @return a {@link Mono} emitting {@code 202} with the current {@link StatusSnapshot},
     *         or {@code 503} with no body when no master is running
     */
    @PostMapping
    public Mono<ResponseEntity<StatusSnapshot>> stop() {
        return Mono.fromCallable(() -> MasterFacade.requestStop()
                ? ResponseEntity.accepted().body(MasterFacade.currentStatus())
                : ResponseEntity.<StatusSnapshot>status(503).build());
    }
}

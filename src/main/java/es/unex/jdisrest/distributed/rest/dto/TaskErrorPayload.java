package es.unex.jdisrest.distributed.rest.dto;

/**
 * Request body sent by a worker to report a failed evaluation.
 *
 * <p>When a worker encounters an unrecoverable error while evaluating a solution
 * (e.g., the simulator crashes, a timeout occurs, or an unexpected exception is
 * thrown), it posts this payload to
 * {@code POST /api/v1/tasks/{taskId}/error} instead of the normal result endpoint.
 *
 * <p>On receipt the master removes the task from the in-flight map and
 * re-queues it in the pending queue so that another worker can retry the
 * evaluation. The {@code errorMessage} is logged at WARNING level for
 * post-hoc debugging.
 *
 * @param workerId     identifier of the worker that encountered the error
 * @param errorMessage human-readable description of what went wrong; used for
 *                     logging only — the master does not act on its content
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskErrorPayload(String workerId, String errorMessage) {}

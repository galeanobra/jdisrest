package es.unex.jdisrest.distributed.rest.dto;

/**
 * Response body returned by the master when it refuses a worker's result.
 *
 * <p>Sent with status {@code 422 Unprocessable Content} when a
 * {@link TaskResultPayload} fails validation (wrong number of objectives or
 * constraints, non-finite or {@code null} values, a decision vector that does
 * not fit the solution), and with {@code 400 Bad Request} when the body could
 * not be decoded at all (for example a bare {@code NaN} token, which is not
 * valid JSON). In both cases the master has already requeued the task, exactly
 * as if the worker had called {@code POST /api/v1/tasks/{taskId}/error}: the
 * worker should log {@code reason} and move on to the next task.
 *
 * @param taskId the task whose result was rejected; {@code -1} if it could not
 *               be determined from the request path
 * @param reason human-readable explanation of the rejection
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskRejectionPayload(long taskId, String reason) {}

package es.unex.jdisrest.distributed.rest.dto;

/**
 * Response body returned by the master when it refuses a worker's result.
 *
 * <p>Sent with status {@code 422 Unprocessable Content} when a
 * {@link TaskResultPayload} fails validation (wrong number of objectives or
 * constraints, non-finite or {@code null} values, a decision vector that does
 * not fit the solution or lies outside its bounds), and with the status Spring
 * chose when the request was refused before it could be read: {@code 400 Bad
 * Request} when the body could not be decoded at all (for example a bare
 * {@code NaN} token, which is not valid JSON), {@code 413 Content Too Large}
 * when it exceeds the server's buffer limit (16 MB by default, see
 * {@code AbstractMaster.DEFAULT_MAX_REQUEST_SIZE}) and {@code 415 Unsupported
 * Media Type} when it is not sent as {@code application/json}. In every case the
 * master has already handled the task exactly as if the worker had called
 * {@code POST /api/v1/tasks/{taskId}/error} (requeued, or discarded once it has
 * failed too many times; once a stop has been requested or the algorithm has
 * ended its run, taken out of flight without counting anything, and an invalid
 * result gets {@code 404} instead of {@code 422}): the worker should log
 * {@code reason} and move on to the next task.
 *
 * @param taskId the task whose result was rejected; {@code -1} if it could not
 *               be determined from the request path
 * @param reason human-readable explanation of the rejection
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskRejectionPayload(long taskId, String reason) {}

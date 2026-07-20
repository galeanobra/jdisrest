package es.unex.jdisrest.distributed;

/**
 * Signals that the master's algorithm has finished and no further tasks will be issued.
 *
 * <p>The master returns HTTP {@code 410 Gone} on {@code GET /api/v1/tasks/next} once its
 * stopping condition is met. {@link RestWorker} translates that status code into this
 * exception, causing the evaluation loop to exit cleanly. Python workers follow the same
 * convention and raise an equivalent error when they receive a 410 response.
 *
 * <p>This is an unchecked exception because algorithm termination is an expected
 * control-flow event, not a recoverable error.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class AlgorithmFinishedException extends RuntimeException {

    /**
     * Constructs the exception with a fixed detail message.
     */
    public AlgorithmFinishedException() {
        super("Algorithm finished");
    }
}

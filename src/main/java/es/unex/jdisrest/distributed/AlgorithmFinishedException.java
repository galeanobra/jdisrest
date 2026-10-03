package es.unex.jdisrest.distributed;

/**
 * Signals that the master's algorithm has finished and no further tasks will be issued.
 *
 * <p>The master returns HTTP {@code 410 Gone} on {@code GET /api/v1/tasks/next} once its
 * stopping condition is met or a stop has been requested ({@code POST /api/v1/stop}).
 * {@link RestWorker} translates that status code into this exception, causing the evaluation
 * loop to exit cleanly; it never escapes {@link RestWorker#run()}. The Python worker reacts to
 * the same status by leaving its loop, without raising anything.
 *
 * <p>This is an unchecked exception because algorithm termination is an expected
 * control-flow event, not a recoverable error.
 *
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

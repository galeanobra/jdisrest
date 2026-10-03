package es.unex.jdisrest.distributed;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A request to finish a run before its stopping criterion is met
 * ({@code POST /api/v1/stop}, see {@link AbstractMaster#requestStop()}).
 *
 * <p>The request is a one-way latch: once made it cannot be withdrawn. It is written from a REST
 * thread and read, without locking, by the algorithm thread and by the threads that hand out
 * tasks, so the flag is {@code volatile}; {@link #request()} is {@code synchronized} only so
 * that exactly one caller learns it was the first (and logs the stop once).
 *
 * <p>Kept apart from the masters, whose constructors start Spring, so that it can be unit
 * tested on its own.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
final class StopRequest {

    /** {@code true} once {@link #request()} has been called; never reset. */
    private volatile boolean requested;

    /**
     * Records the request. Repeated calls are harmless.
     *
     * @return {@code true} the first time, {@code false} if a stop had already been requested
     */
    synchronized boolean request() {
        boolean first = !requested;
        requested = true;
        return first;
    }

    /**
     * Returns whether {@link #request()} has been called.
     *
     * @return {@code true} once a stop has been requested
     */
    boolean isRequested() {
        return requested;
    }

    /**
     * Waits for the next element of a queue, checking every {@code checkInterval} whether a stop
     * has been requested.
     *
     * <p>Once a stop has been requested it returns {@code null}, <em>even if an element is
     * already waiting</em> in the queue: the results that arrive after a stop are discarded, so
     * the algorithm finishes with the state it had when the stop was requested. The element, if
     * any, is left in the queue; one taken at the very moment the stop lands is put back, so
     * that the queue holds every result the algorithm never processed (the status endpoints
     * leave them out of {@code evaluations}).
     *
     * <p>The stop is therefore noticed at most {@code checkInterval} after it is requested; the
     * interval trades that latency against wake-ups of an idle algorithm thread.
     *
     * @param queue         the queue to take from
     * @param checkInterval how long each poll waits before checking the stop again; must be
     *                      positive
     * @param <T>           type of the queue elements
     * @return the next element, or {@code null} if a stop has been requested
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    <T> T takeUnlessStopped(BlockingQueue<T> queue, Duration checkInterval) throws InterruptedException {
        T element = null;
        while (element == null && !requested) {
            element = queue.poll(checkInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (requested) {
            if (element != null) {
                queue.offer(element);  // taken as the stop landed: unprocessed, like the rest
            }
            return null;
        }
        return element;
    }
}

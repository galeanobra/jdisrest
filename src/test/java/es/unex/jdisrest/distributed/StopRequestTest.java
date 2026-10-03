package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stop latch behind {@code POST /api/v1/stop}: a one-shot request, and the wait the
 * steady-state algorithm thread uses so that a stop ends it even when no result is coming.
 */
class StopRequestTest {

    /** Short, so that a missed stop shows up as a slow test rather than a hang. */
    private static final Duration CHECK_INTERVAL = Duration.ofMillis(20);

    /** Upper bound for anything a test waits for on another thread. */
    private static final long TIMEOUT_S = 5;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static BlockingQueue<String> queueOf(String... elements) {
        return new LinkedBlockingQueue<>(List.of(elements));
    }

    /**
     * Starts {@link StopRequest#takeUnlessStopped} on a daemon thread and returns once that
     * thread is blocked in the queue poll, so that whatever the test does next happens
     * <em>during</em> the wait.
     */
    static FutureTask<String> waitInBackground(StopRequest stop, BlockingQueue<String> queue)
            throws InterruptedException {
        FutureTask<String> wait = new FutureTask<>(() -> stop.takeUnlessStopped(queue, CHECK_INTERVAL));
        Thread thread = new Thread(wait, "stop-request-test-waiter");
        thread.setDaemon(true);
        thread.start();
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(Instant.now().isBefore(deadline), "the waiter never blocked on the queue");
            Thread.sleep(1);
        }
        return wait;
    }

    // ── request ───────────────────────────────────────────────────────────────

    @Test
    void newRequestIsNotStopped() {
        assertFalse(new StopRequest().isRequested(), "a stop must not be requested before request()");
    }

    @Test
    void onlyTheFirstRequestReportsANewStop() {
        StopRequest stop = new StopRequest();

        assertTrue(stop.request(), "the first request must report a new stop");
        assertFalse(stop.request(), "a repeated request must not report a new stop");
        assertTrue(stop.isRequested(), "the request must stay set");
    }

    @Test
    void concurrentRequestsReportExactlyOneNewStop() throws InterruptedException {
        StopRequest stop = new StopRequest();
        AtomicInteger firsts = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = IntStream.range(0, 8)
            .mapToObj(i -> new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stop.request()) firsts.incrementAndGet();
            }))
            .toList();
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) t.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));

        assertEquals(1, firsts.get(), "exactly one caller must learn it made the stop, so it is logged once");
    }

    // ── takeUnlessStopped ─────────────────────────────────────────────────────

    @Test
    void waitingElementIsReturnedWhileNoStopIsRequested() throws InterruptedException {
        BlockingQueue<String> queue = queueOf("result");

        assertEquals("result", new StopRequest().takeUnlessStopped(queue, CHECK_INTERVAL));
        assertTrue(queue.isEmpty(), "the returned element must be taken from the queue");
    }

    @Test
    void waitingElementIsDiscardedOnceAStopIsRequested() throws InterruptedException {
        StopRequest stop = new StopRequest();
        BlockingQueue<String> queue = queueOf("late result");
        stop.request();

        assertNull(stop.takeUnlessStopped(queue, CHECK_INTERVAL),
            "a result waiting after the stop must not reach the algorithm");
        assertEquals(List.of("late result"), List.copyOf(queue), "a discarded element is left in the queue");
    }

    @Test
    void stopFromAnotherThreadEndsAWaitOnAnEmptyQueue() throws Exception {
        StopRequest stop = new StopRequest();
        FutureTask<String> wait = waitInBackground(stop, queueOf());

        stop.request();

        assertNull(wait.get(TIMEOUT_S, TimeUnit.SECONDS), "a stop must end the wait with no element");
    }

    @Test
    void elementArrivingDuringTheWaitIsReturned() throws Exception {
        StopRequest stop = new StopRequest();
        BlockingQueue<String> queue = queueOf();
        FutureTask<String> wait = waitInBackground(stop, queue);

        queue.add("result");

        assertEquals("result", wait.get(TIMEOUT_S, TimeUnit.SECONDS));
        assertFalse(stop.isRequested());
    }

    @Test
    void interruptedThreadGetsInterruptedException() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                () -> new StopRequest().takeUnlessStopped(queueOf(), CHECK_INTERVAL),
                "an interrupt must reach the caller, which decides how to end its loop");
        } finally {
            Thread.interrupted();  // leave the test thread clean
        }
    }
}

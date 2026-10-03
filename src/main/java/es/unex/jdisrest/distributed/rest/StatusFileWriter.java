package es.unex.jdisrest.distributed.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Writes the progress snapshot to {@code status.json} periodically, on a daemon thread, for
 * monitoring tools that read files rather than {@code GET /api/v1/status} (started by
 * {@link MasterFacade#init}).
 *
 * <p>Every write goes to {@code status.json.tmp} first and is then renamed to
 * {@code status.json} with {@link StandardCopyOption#ATOMIC_MOVE}, so a reader never sees a
 * partial file. The folder is created if it does not exist. The JSON is the one of
 * {@code GET /api/v1/status} (same record), written with Jackson 2.
 *
 * <p>Writing is best-effort, so a failure never reaches the algorithm, and each kind of failure
 * is logged once:
 * <ul>
 *   <li>the snapshot cannot be built (the master cannot report its state, for instance a
 *       stopping condition that throws), or</li>
 *   <li>the file cannot be written (a wrong or read-only {@code jdisrest.dataPath}).</li>
 * </ul>
 * The two are logged separately, so a snapshot that cannot be built while the run starts does
 * not use up the warning about a misconfigured folder.
 *
 * <p>{@link #stop()} writes a last snapshot, so the file ends with the final state of the run
 * rather than whatever the last periodic write saw. The writer waits on a latch rather than
 * sleeping, so stopping it needs no interrupt (an interrupt would make the last write fail on
 * an interruptible file channel).
 *
 * <p>Kept apart from {@link MasterFacade}, whose state is static and global, so that it can be
 * tested on its own.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
final class StatusFileWriter {

    /** Name of the status file in the data folder. */
    static final String FILE_NAME = "status.json";

    /** Longest {@link #stop()} waits for the last write. */
    private static final long STOP_TIMEOUT_MS = 5_000L;

    /** Shared Jackson mapper for serializing {@link StatusSnapshot}. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<StatusSnapshot> snapshots;
    private final Path folder;
    private final long intervalMs;
    private final Thread thread;

    /** Counted down by {@link #stop()}; the writer thread waits on it between writes. */
    private final CountDownLatch stopRequested = new CountDownLatch(1);

    /** Latches once a failure to build the snapshot has been logged. */
    private final AtomicBoolean snapshotErrorLogged = new AtomicBoolean(false);

    /** Latches once a failure to write the file has been logged. */
    private final AtomicBoolean writeErrorLogged = new AtomicBoolean(false);

    /**
     * Creates a writer; {@link #start()} starts it.
     *
     * @param snapshots  supplies the snapshot to write; called on the writer thread
     * @param folder     the folder of {@code status.json}
     * @param intervalMs milliseconds between writes; must be positive
     */
    StatusFileWriter(Supplier<StatusSnapshot> snapshots, Path folder, long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs must be positive, got " + intervalMs);
        }
        this.snapshots = snapshots;
        this.folder = folder;
        this.intervalMs = intervalMs;
        this.thread = new Thread(this::loop, "status-file-writer");
        this.thread.setDaemon(true);
    }

    /** Starts writing: once right away, then every {@code intervalMs}. */
    void start() {
        thread.start();
    }

    /**
     * Stops the periodic writes after one last write, and waits up to a few seconds for it.
     * Repeated calls are harmless: the last write happens once.
     */
    void stop() {
        stopRequested.countDown();
        if (Thread.currentThread() == thread || !thread.isAlive()) {
            return;
        }
        try {
            thread.join(STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The writer thread: writes, waits for the interval or a stop, and writes once more at the end. */
    private void loop() {
        boolean running = true;
        while (running) {
            write();
            try {
                running = !stopRequested.await(intervalMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                running = false;  // nothing interrupts this thread but the JVM: treat it as a stop
            }
        }
        write();  // the final snapshot, so monitoring tools see the completed state
    }

    /**
     * Writes one snapshot. Never throws.
     *
     * @return {@code true} if {@code status.json} was written
     */
    boolean write() {
        StatusSnapshot snapshot;
        try {
            snapshot = snapshots.get();
        } catch (RuntimeException e) {
            if (snapshotErrorLogged.compareAndSet(false, true)) {
                Log.warn("Could not build the status for " + FILE_NAME + " (further failures will be silent): " + e);
            }
            return false;
        }
        try {
            Files.createDirectories(folder);
            Path tmp = folder.resolve(FILE_NAME + ".tmp");
            Path dest = folder.resolve(FILE_NAME);
            Files.writeString(tmp, JSON.writeValueAsString(snapshot));
            // ATOMIC_MOVE guarantees readers never see a partial file.
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            // Best-effort: a failed status write must never crash the algorithm. Log only the
            // first occurrence to surface a misconfigured dataPath without spamming.
            if (writeErrorLogged.compareAndSet(false, true)) {
                Log.warn("Could not write " + FILE_NAME + " (further failures will be silent): " + e.getMessage());
            }
            return false;
        }
    }
}

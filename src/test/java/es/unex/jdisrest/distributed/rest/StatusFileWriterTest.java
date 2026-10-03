package es.unex.jdisrest.distributed.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The periodic {@code status.json} writer: where and what it writes, the last snapshot written
 * when it stops, and failures that are logged once each without one kind hiding the other.
 */
class StatusFileWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Longer than any test: only the first write and the last one happen. */
    private static final long NEVER_MS = 3_600_000L;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static StatusSnapshot snapshot(long evaluations, boolean finished) {
        return new StatusSnapshot(!finished, finished, evaluations, 100, evaluations / 100.0, 1, -1, 1, 0, 0, 0);
    }

    static StatusSnapshot read(Path folder) throws Exception {
        return JSON.readValue(Files.readString(folder.resolve(StatusFileWriter.FILE_NAME)), StatusSnapshot.class);
    }

    /** Runs an action and returns what it wrote to {@code System.err}, where {@code Log} writes. */
    static String stderrOf(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ── Writing ───────────────────────────────────────────────────────────────

    @Test
    void writeCreatesTheFolderAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
        Path folder = dir.resolve("shared").resolve("run1");
        StatusFileWriter writer = new StatusFileWriter(() -> snapshot(42, false), folder, NEVER_MS);

        assertTrue(writer.write(), "a missing jdisrest.dataPath folder is created");
        assertEquals(snapshot(42, false), read(folder), "the JSON of GET /api/v1/status");
        assertFalse(Files.exists(folder.resolve(StatusFileWriter.FILE_NAME + ".tmp")), "renamed into place");
    }

    @Test
    void stopWritesALastSnapshotWithTheFinalState(@TempDir Path dir) throws Exception {
        AtomicLong evaluations = new AtomicLong(10);
        Supplier<StatusSnapshot> live = () -> snapshot(evaluations.get(), evaluations.get() >= 100);
        StatusFileWriter writer = new StatusFileWriter(live, dir, NEVER_MS);
        writer.start();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!Files.exists(dir.resolve(StatusFileWriter.FILE_NAME))) {
            assertTrue(Instant.now().isBefore(deadline), "the first write happens right away");
            Thread.sleep(5);
        }
        assertEquals(10, read(dir).evaluations());

        evaluations.set(100);
        writer.stop();

        assertEquals(snapshot(100, true), read(dir),
            "the file ends with the finished run, not with the last periodic write");
        writer.stop();  // harmless
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    void snapshotFailureDoesNotUseUpTheWarningAboutTheFolder(@TempDir Path dir) throws Exception {
        Path notAFolder = Files.writeString(dir.resolve("plain-file"), "x");
        boolean[] failing = {true};
        StatusFileWriter writer = new StatusFileWriter(() -> {
            if (failing[0]) throw new IllegalStateException("termination evaluated before the run started");
            return snapshot(1, false);
        }, notAFolder.resolve("data"), NEVER_MS);

        String log = stderrOf(() -> {
            assertFalse(writer.write());
            assertFalse(writer.write());
            failing[0] = false;
            assertFalse(writer.write());
            assertFalse(writer.write());
        });

        assertEquals(1, count(log, "Could not build the status"), "logged once: " + log);
        assertEquals(1, count(log, "Could not write status.json"),
            "a misconfigured folder is still reported after an early snapshot failure: " + log);
    }

    @Test
    void intervalMustBePositive(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new StatusFileWriter(() -> snapshot(0, false), dir, 0));
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}

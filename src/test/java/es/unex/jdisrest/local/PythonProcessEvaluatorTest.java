package es.unex.jdisrest.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PythonProcessEvaluator} against a fake child written into a temporary
 * directory: protocol round trip, error replies, lines that are not protocol
 * objects, time-outs, crashes and the clean-up of a failed handshake. Skipped
 * when no Python 3 interpreter is found ({@code python3}/{@code python} on the
 * PATH, or {@code -Djdisrest.test.python=<interpreter>}).
 */
class PythonProcessEvaluatorTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A Python 3 interpreter, or {@code null} when none is available. */
    static final String PYTHON = findPython();

    /** Opcodes of the fake child: the first variable of a request selects the answer. */
    static final int SUM = 0, FAIL = 1, EMPTY_ERROR = 2, NULL_LINE = 3, ARRAY_LINE = 4,
            STRAY_PRINT = 5, HANG = 6, CRASH = 7, REPAIR = 8, FALSE_ERROR = 9, NO_ANSWER = 10;

    /**
     * The fake child. argv[1] selects the start-up (a proper banner, a wrong one,
     * none at all, an early exit); {@code --pid-file F} records its pid,
     * {@code --touch F} creates F relative to its working directory and
     * {@code --linger-on-eof} leaves a grandchild holding its stdout open for a
     * second after it exits at the end of its input. {@code NO_ANSWER} creates
     * {@code <pid-file>.received} and sends nothing back.
     */
    static final String CHILD = """
            import json, os, sys, time

            args = sys.argv[1:]
            protocol = sys.stdout

            def send(text):
                protocol.write(text + "\\n")
                protocol.flush()

            if "--pid-file" in args:
                with open(args[args.index("--pid-file") + 1], "w") as f:
                    f.write(str(os.getpid()))
            if "--touch" in args:
                open(args[args.index("--touch") + 1], "w").close()

            start = args[0] if args else "ready"
            if start == "exit":
                sys.exit(3)
            if start == "ready":
                send(json.dumps({"ready": True}))
            elif start == "library-banner":
                send("hello from a library")
            elif start == "null-banner":
                send("null")
            elif start == "not-ready":
                send(json.dumps({"ready": False}))
            if start != "ready":
                time.sleep(60)
                sys.exit(0)

            for line in sys.stdin:
                request = json.loads(line)
                rid, x = request["id"], request["vars"]
                op = int(x[0]) if x else 0
                answer = {"id": rid, "objectives": [sum(x)], "constraints": [-1.0]}
                if op == 1:
                    answer = {"id": rid, "error": "ValueError: boom"}
                elif op == 2:
                    answer = {"id": rid, "error": "", "objectives": [7.0]}
                elif op == 3:
                    send("null")
                    continue
                elif op == 4:
                    send("[1, 2]")
                    continue
                elif op == 5:
                    send("debug print")
                elif op == 6:
                    time.sleep(60)
                elif op == 7:
                    sys.exit(5)
                elif op == 8:
                    answer["variables"] = [v + 1 for v in x]
                elif op == 9:
                    answer = {"id": rid, "error": False, "objectives": [9.0]}
                elif op == 10:
                    open(args[args.index("--pid-file") + 1] + ".received", "w").close()
                    continue
                send(json.dumps(answer))

            if "--linger-on-eof" in args:
                import subprocess
                subprocess.Popen([sys.executable, "-c", "import time; time.sleep(1)"], stdout=protocol)
            """;

    static String findPython() {
        String configured = System.getProperty("jdisrest.test.python");
        List<String> candidates = configured != null ? List.of(configured)
                : System.getProperty("os.name", "").startsWith("Windows") ? List.of("python", "python3")
                : List.of("python3", "python");
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-c", "import sys; print(sys.version_info[0])")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && out.equals("3")) return candidate;
                p.destroyForcibly();
            } catch (IOException notInstalled) {
                // try the next candidate
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Writes the fake child into {@code dir} and returns its path. */
    static Path child(Path dir) throws IOException {
        return Files.writeString(dir.resolve("fake_child.py"), CHILD);
    }

    /** Starts the fake child in {@code dir}, recording its pid in {@code dir/child.pid}. */
    static PythonProcessEvaluator start(Path dir, String startup, Duration timeout, String... extraArgs)
            throws IOException {
        assumeTrue(PYTHON != null, "no Python 3 interpreter available");
        List<String> args = new ArrayList<>(List.of(startup, "--pid-file", dir.resolve("child.pid").toString()));
        args.addAll(List.of(extraArgs));
        return new PythonProcessEvaluator(PYTHON, child(dir).toString(), args, null, null, timeout);
    }

    static PythonProcessEvaluator start(Path dir) throws IOException {
        return start(dir, "ready", null);
    }

    /** Fails unless the child whose pid is in {@code dir/child.pid} is gone within 10 s. */
    static void assertChildIsGone(Path dir) throws Exception {
        long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")).trim());
        Optional<ProcessHandle> child = ProcessHandle.of(pid);
        if (child.isPresent()) {
            try {
                child.get().onExit().get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                child.get().destroyForcibly();
                fail("child " + pid + " must not outlive a failed or closed evaluator");
            }
        }
    }

    static IOException assertUnusable(PythonProcessEvaluator python) {
        IOException e = assertThrows(IOException.class, () -> python.evaluate(List.of(SUM)));
        assertTrue(e.getMessage().startsWith("Python evaluator is no longer usable"),
                "later calls must fail fast with the reason: " + e.getMessage());
        return e;
    }

    // ── Protocol ──────────────────────────────────────────────────────────────

    @Test
    void evaluationReturnsObjectivesConstraintsAndRepairedVariables(@TempDir Path dir) throws IOException {
        try (PythonProcessEvaluator python = start(dir)) {
            PythonProcessEvaluator.Result sum = python.evaluate(List.of(SUM, 2.5, 3));
            assertArrayEquals(new double[] {5.5}, sum.objectives);
            assertArrayEquals(new double[] {-1.0}, sum.constraints);
            assertNull(sum.variables, "no 'variables' field means the decision was not modified");

            PythonProcessEvaluator.Result repaired = python.evaluate(new int[] {REPAIR, 1});
            assertArrayEquals(new double[] {9.0}, repaired.objectives);
            assertEquals(List.of(9, 2), repaired.variables);
        }
    }

    @Test
    void errorReplyFailsTheCallButKeepsTheProtocolInStep(@TempDir Path dir) throws IOException {
        try (PythonProcessEvaluator python = start(dir)) {
            IOException e = assertThrows(IOException.class, () -> python.evaluate(List.of(FAIL)));
            assertEquals("Python evaluator error: ValueError: boom", e.getMessage());

            assertArrayEquals(new double[] {4.0}, python.evaluate(List.of(SUM, 4)).objectives,
                    "an error reply must leave the evaluator usable");
        }
    }

    @Test
    void errorFieldOnlyCountsWhenItIsANonEmptyString(@TempDir Path dir) throws IOException {
        try (PythonProcessEvaluator python = start(dir)) {
            assertArrayEquals(new double[] {7.0}, python.evaluate(List.of(EMPTY_ERROR)).objectives,
                    "\"error\": \"\" next to objectives is a success");
            assertArrayEquals(new double[] {9.0}, python.evaluate(List.of(FALSE_ERROR)).objectives,
                    "\"error\": false next to objectives is a success");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {NULL_LINE, ARRAY_LINE, STRAY_PRINT})
    void lineThatIsNotAJsonObjectIsAnIOExceptionQuotingTheLine(int op, @TempDir Path dir) throws IOException {
        String expectedLine = switch (op) {
            case NULL_LINE -> "null";
            case ARRAY_LINE -> "[1, 2]";
            default -> "debug print";
        };
        try (PythonProcessEvaluator python = start(dir)) {
            IOException e = assertThrows(IOException.class, () -> python.evaluate(List.of(op)));
            assertTrue(e.getMessage().contains("(line: " + expectedLine + ")"),
                    "the message must quote the offending line: " + e.getMessage());
            assertTrue(e.getMessage().contains("stdout is reserved for protocol lines"), e.getMessage());

            assertUnusable(python);
        }
    }

    @Test
    void decisionThatIsNoListOfJsonNumbersIsRejectedBeforeSendingAndTheEvaluatorStaysUsable(@TempDir Path dir)
            throws IOException {
        try (PythonProcessEvaluator python = start(dir)) {
            IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                    () -> python.evaluate(List.of(SUM, Double.NaN)));
            assertEquals("Cannot send the decision to the Python evaluator: variables[1] is not finite: NaN",
                    nan.getMessage());
            assertThrows(IllegalArgumentException.class, () -> python.evaluate(Arrays.asList(SUM, null)));

            // Had either request reached the child, its sum() would have failed or crashed it.
            assertArrayEquals(new double[] {4.0}, python.evaluate(List.of(SUM, 4)).objectives,
                    "a rejected decision must not be sent, so the evaluator stays in step");
        }
    }

    @Test
    void unsendableNamesANullOrNonFiniteVariable() {
        assertNull(PythonProcessEvaluator.unsendable(List.of(1, 2.5, -0.0, Double.MAX_VALUE)),
                "finite numbers of any type are sent as they are");
        assertEquals("variables[2] is null", PythonProcessEvaluator.unsendable(Arrays.asList(1.0, 2.0, null)));
        assertEquals("variables[1] is not finite: Infinity",
                PythonProcessEvaluator.unsendable(List.of(1, Double.POSITIVE_INFINITY, Double.NaN)));
        assertEquals("variables[0] is not finite: NaN", PythonProcessEvaluator.unsendable(List.of(Float.NaN)));
    }

    // ── Failures of the child ─────────────────────────────────────────────────

    @Test
    void timeoutDestroysTheChildAndDisablesTheEvaluator(@TempDir Path dir) throws Exception {
        try (PythonProcessEvaluator python = start(dir, "ready", Duration.ofSeconds(2))) {
            IOException e = assertThrows(IOException.class, () -> python.evaluate(List.of(HANG)));
            assertTrue(e.getMessage().contains("within 2000 ms"), e.getMessage());

            assertChildIsGone(dir);
            assertTrue(assertUnusable(python).getMessage().contains("timed out"));
        }
    }

    @Test
    void interruptedCallDestroysTheChildAndKeepsTheInterruptFlag(@TempDir Path dir) throws Exception {
        try (PythonProcessEvaluator python = start(dir)) {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> python.evaluate(List.of(HANG)));
            assertTrue(Thread.interrupted(), "the interrupt flag must survive the call");

            assertChildIsGone(dir);
            assertUnusable(python);
        }
    }

    @Test
    void crashedChildIsReportedWithItsExitCode(@TempDir Path dir) throws IOException {
        try (PythonProcessEvaluator python = start(dir)) {
            IOException e = assertThrows(IOException.class, () -> python.evaluate(List.of(CRASH)));
            assertTrue(e.getMessage().contains("closed stdout"), e.getMessage());
            assertTrue(e.getMessage().contains("exit code 5"), e.getMessage());

            assertUnusable(python);
        }
    }

    @Test
    void closeEndsTheChildIsIdempotentAndDisablesTheEvaluator(@TempDir Path dir) throws Exception {
        PythonProcessEvaluator python = start(dir);
        python.close();
        python.close();

        assertChildIsGone(dir);
        assertUnusable(python);
    }

    @Test
    void closeFromAnotherThreadReleasesACallWaitingForTheChild(@TempDir Path dir) throws Exception {
        // The child exits at the end of its input without answering, but a grandchild keeps its
        // stdout open: close() returns and stops the stdout reader before the stream ends.
        PythonProcessEvaluator python = start(dir, "ready", null, "--linger-on-eof");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().daemon().start(() -> {
            try {
                python.evaluate(List.of(NO_ANSWER));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(dir.resolve("child.pid.received"))) {
            assertTrue(System.nanoTime() < deadline, "the request never reached the child");
            Thread.sleep(10);
        }

        python.close();

        caller.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(caller.isAlive(), "close() from another thread must release a call waiting for the child");
        assertInstanceOf(IOException.class, failure.get(), "the released call must fail with an IOException");
        assertUnusable(python);
    }

    // ── Start-up ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"library-banner", "null-banner", "not-ready"})
    void failedHandshakeDestroysTheChild(String startup, @TempDir Path dir) throws Exception {
        IOException e = assertThrows(IOException.class, () -> start(dir, startup, null).close());
        String expectedLine = switch (startup) {
            case "library-banner" -> "hello from a library";
            case "null-banner" -> "null";
            default -> "{\"ready\": false}";
        };
        assertTrue(e.getMessage().contains(expectedLine), "the message must quote the banner: " + e.getMessage());

        assertChildIsGone(dir);
    }

    @Test
    void silentChildTimesOutDuringTheHandshakeAndIsDestroyed(@TempDir Path dir) throws Exception {
        IOException e = assertThrows(IOException.class, () -> start(dir, "silent", Duration.ofSeconds(2)).close());
        assertTrue(e.getMessage().contains("the ready banner within 2000 ms"), e.getMessage());

        assertChildIsGone(dir);
    }

    @Test
    void childExitingBeforeTheBannerIsReportedWithItsExitCode(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> start(dir, "exit", null).close());
        assertTrue(e.getMessage().contains("exited before sending the ready banner, exit code 3"), e.getMessage());
    }

    @Test
    void scriptArgumentsAndWorkingDirectoryReachTheChild(@TempDir Path dir) throws IOException {
        assumeTrue(PYTHON != null, "no Python 3 interpreter available");
        Path work = Files.createDirectory(dir.resolve("work"));
        List<String> args = List.of("ready", "--touch", "started.txt");
        try (PythonProcessEvaluator python = new PythonProcessEvaluator(
                PYTHON, child(dir).toString(), args, work, null, null)) {
            assertTrue(Files.exists(work.resolve("started.txt")),
                    "the child must run in the given working directory with the given arguments");
            assertArrayEquals(new double[] {1.0}, python.evaluate(List.of(SUM, 1)).objectives);
        }
    }

    @Test
    void nonPositiveTimeoutIsRejectedBeforeStartingTheChild() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PythonProcessEvaluator("no-such-interpreter", "x.py", List.of(), null, null, timeout),
                    "timeout " + timeout + " must be rejected before any process is started");
        }
    }
}

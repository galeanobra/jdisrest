package es.unex.jdisrest.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a long-running Python child process used as a synchronous evaluator
 * for the local/sequential mode. Communicates over the child's stdin/stdout
 * with a line-delimited JSON protocol; the child's stderr is inherited.
 *
 * <p>The child is started as {@code <pythonExecutable> -u <scriptPath> [scriptArgs...]},
 * optionally in another working directory and with extra environment variables
 * (a relative {@code scriptPath} is resolved by the interpreter against the
 * child's working directory).
 *
 * <h2>Child protocol</h2>
 * <p>One JSON object per line, in strict lock-step: Java writes one request and
 * waits for exactly one response before writing the next.
 * <ul>
 *   <li>handshake: the first line the child prints must be a JSON object with
 *       {@code "ready": true} (other fields are ignored)</li>
 *   <li>request:  {@code {"id": <long>, "vars": [<number>, ...]}} — integer
 *       variables are written as JSON integers, real ones as JSON floats, and a
 *       binary variable as one JSON integer 0 or 1 per bit; the key order is
 *       unspecified, and the key is {@code vars} (not {@code variables} as in the
 *       REST payload) for compatibility with existing children. With a layout
 *       ({@link #evaluate(List, SolutionVariables.VectorLayout)}) the request also
 *       carries the layout keys of the REST payload, with its names and omission
 *       rules: {@code segmentSizes} for a composite, {@code encoding} and
 *       {@code segmentEncodings} unless every variable is an integer one, and
 *       {@code bitsPerVariable} when a variable is binary. A {@code null},
 *       {@code NaN} or infinite variable is never sent (JSON has no such number):
 *       the call fails with an {@link IllegalArgumentException} before writing
 *       anything, as the REST master fails a task whose payload it cannot build,
 *       and the evaluator stays usable</li>
 *   <li>response: {@code {"id": <long>, "objectives": [...], "constraints": [...],
 *       "variables": [...]}} ({@code constraints} and {@code variables} optional);
 *       {@code id} must echo the request's. At the positions of the binary
 *       variables of a request that carried its layout, {@code variables} may hold
 *       JSON {@code true} and {@code false}, which are taken as 1 and 0, so that a
 *       child that repairs bits as booleans can send them as {@code json.dumps}
 *       writes them; anywhere else they are not numbers</li>
 *   <li>error:    {@code {"id": <long>, "error": "<msg>"}} — the call fails with an
 *       {@link IOException} carrying {@code msg}, and the protocol stays in step, so
 *       the next call works. Only a non-empty string counts as an error: an
 *       {@code error} field that is {@code null}, {@code false}, {@code ""} or not a
 *       string is ignored, so a child that always sends {@code "error": ""} (or
 *       {@code null}) on success works, and the response must then carry
 *       {@code objectives}</li>
 *   <li>EOF on stdin ({@link #close()}) means no more requests: the child must exit.</li>
 * </ul>
 *
 * <p><b>stdout is reserved for protocol lines.</b> Anything else the child
 * prints there — a debug {@code print()}, a library banner or warning — is read
 * in place of the expected line: the call fails with an {@link IOException}
 * quoting the offending line, and since the real answer is still in the pipe
 * the stream is out of step for good, so the evaluator refuses every later call
 * and must be closed. Diagnostics belong on stderr, which is inherited by the
 * JVM's stderr (Python tracebacks therefore show up on the console, not in the
 * exception message). A minimal child that keeps stray {@code print()} calls off
 * the protocol stream (output written to file descriptor 1 by native code needs
 * an {@code os.dup2} redirection instead):
 * <pre>{@code
 * import json, sys
 *
 * def evaluate(x):                                 # the problem: one list of objectives
 *     return [sum(v * v for v in x)]
 *
 * protocol, sys.stdout = sys.stdout, sys.stderr    # print() now goes to stderr
 *
 * def send(message):
 *     protocol.write(json.dumps(message, allow_nan=False) + "\n")
 *     protocol.flush()
 *
 * send({"ready": True})
 * for line in sys.stdin:                           # ends at EOF, i.e. on close()
 *     request = json.loads(line)
 *     try:
 *         send({"id": request["id"], "objectives": evaluate(request["vars"])})
 *     except Exception as e:
 *         send({"id": request["id"], "error": f"{type(e).__name__}: {e}"})
 * }</pre>
 *
 * <p>Objectives and constraints must be finite numbers; a {@code null},
 * {@code NaN} or infinite value is reported as an {@link IOException}, the
 * same way the REST master rejects such results ({@code json.dumps} writes a
 * non-finite float as a bare {@code NaN}/{@code Infinity} token, which is not
 * JSON and fails the parse; {@code allow_nan=False} turns it into a Python
 * exception the child can report as an error). The numeric type of the
 * returned {@code variables} is irrelevant: the caller converts each value to
 * the type of the destination variable.
 *
 * <h2>Time-out</h2>
 * <p>By default every wait for a line from the child blocks for as long as it
 * takes: a hung evaluation blocks the calling thread forever. A
 * {@code timeout} given to the constructor bounds each wait — for the ready
 * banner and for every response — so it must also cover the child's start-up
 * (imports included). When it expires the child and every process it started
 * are destroyed and the call fails with an {@link IOException}; the evaluator is
 * then unusable. Interrupting a thread that waits for the child has the same
 * effect ({@link InterruptedIOException}, interrupt flag kept).
 *
 * <h2>Lifecycle</h2>
 * <p>A constructor that fails after starting the child (bad banner, child exit,
 * time-out) destroys the child before throwing. After a time-out, an interrupt,
 * a protocol desynchronisation, the child closing its stdout or no longer
 * reading its stdin, or {@link #close()}, every call fails fast with an
 * {@link IOException} saying why; an {@code error} reply or an invalid response
 * that was still a JSON object with the right {@code id} leaves the evaluator
 * usable. {@link #close()} must be called once the run ends — nothing else stops
 * the child (jMetal's NSGA-II never calls {@code SolutionListEvaluator.shutdown()}).
 * Each evaluator runs one daemon thread that reads the child's stdout.
 *
 * <p>Not thread-safe: {@link #evaluate(List)} must be called from a single
 * thread. The stock jMetal NSGA-II is single-threaded, so this is sufficient.
 * {@link #close()} may be called from another thread to end a call that waits
 * for a hung child: the child is destroyed once the grace period has passed,
 * and the waiting call then fails with an {@link IOException}.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class PythonProcessEvaluator implements AutoCloseable {

    /** Grace period {@link #close()} gives the child to exit after the end of its input. */
    private static final long EXIT_GRACE_SECONDS = 10;

    /** Longest protocol line quoted in an exception message. */
    private static final int MAX_QUOTED_LINE = 500;

    /** Handed over by the stdout reader when the child closes its stdout. */
    private static final Object END_OF_STREAM = new Object();

    /** How often a wait for a child line checks that the stdout reader is still running. */
    private static final long READER_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    private final Process process;
    private final BufferedWriter stdin;
    /** Lines of the child's stdout, handed over one at a time (see {@link #startStdoutReader}). */
    private final SynchronousQueue<Object> stdoutLines = new SynchronousQueue<>();
    private final Thread stdoutReader;
    /** Bound of each wait for a child line, or {@code null} for none. */
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(0);
    /** Why the evaluator can no longer be used, or {@code null} while it can. */
    private volatile String unusableReason;

    /** One evaluation result as reported by the Python child. */
    public static final class Result {
        public final double[] objectives;
        public final double[] constraints;
        /**
         * Optional repaired/modified decision vector, mirroring the
         * {@code variables} field of the REST {@code TaskResultPayload}.
         * {@code null} (the common case) means the evaluator did not modify
         * the decision and the caller should keep the original variables.
         * Elements keep the numeric type produced by the JSON parser; use
         * {@link es.unex.jdisrest.util.SolutionVariables#apply} to write them
         * back with the conversion the destination requires, after
         * {@link es.unex.jdisrest.util.SolutionVariables#checkBounds} (as
         * {@link PythonSolutionListEvaluator} does by default).
         */
        public final List<Number> variables;
        public Result(double[] objectives, double[] constraints) {
            this(objectives, constraints, null);
        }
        public Result(double[] objectives, double[] constraints, List<Number> variables) {
            this.objectives = objectives;
            this.constraints = constraints;
            this.variables = variables;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Starts {@code <pythonExecutable> -u <scriptPath>} and waits, without a
     * time-out, for its ready banner.
     *
     * @param pythonExecutable the interpreter, e.g. {@code "python3"} or a path to it
     * @param scriptPath       the child script implementing the protocol
     * @throws IOException if the child cannot be started or the handshake fails
     */
    public PythonProcessEvaluator(String pythonExecutable, String scriptPath) throws IOException {
        this(pythonExecutable, scriptPath, Map.of());
    }

    /**
     * Same as {@link #PythonProcessEvaluator(String, String)} with extra
     * environment variables merged into the inherited environment.
     *
     * @param pythonExecutable the interpreter
     * @param scriptPath       the child script implementing the protocol
     * @param extraEnv         variables added to the child's environment ({@code null} for none)
     * @throws IOException if the child cannot be started or the handshake fails
     */
    public PythonProcessEvaluator(String pythonExecutable, String scriptPath, Map<String, String> extraEnv)
            throws IOException {
        this(pythonExecutable, scriptPath, List.of(), null, extraEnv, null);
    }

    /**
     * Same as {@link #PythonProcessEvaluator(String, String, Map)} with a bound on
     * every wait for the child (see "Time-out" in the class description).
     *
     * @param pythonExecutable the interpreter
     * @param scriptPath       the child script implementing the protocol
     * @param extraEnv         variables added to the child's environment ({@code null} for none)
     * @param timeout          longest wait for the ready banner and for each response,
     *                         or {@code null} for no limit
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     * @throws IOException              if the child cannot be started or the handshake fails
     */
    public PythonProcessEvaluator(String pythonExecutable, String scriptPath, Map<String, String> extraEnv,
                                  Duration timeout) throws IOException {
        this(pythonExecutable, scriptPath, List.of(), null, extraEnv, timeout);
    }

    /**
     * Starts {@code <pythonExecutable> -u <scriptPath> [scriptArgs...]} and waits
     * for its ready banner. Every other constructor delegates here.
     *
     * @param pythonExecutable the interpreter
     * @param scriptPath       the child script implementing the protocol, resolved by the
     *                         interpreter against {@code workingDirectory} when relative
     * @param scriptArgs       arguments passed to the script ({@code null} for none)
     * @param workingDirectory the child's working directory, or {@code null} for the JVM's
     * @param extraEnv         variables added to the child's environment ({@code null} for none)
     * @param timeout          longest wait for the ready banner and for each response,
     *                         or {@code null} for no limit
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     * @throws IOException              if the child cannot be started or the handshake fails;
     *                                  in the latter case the child has been destroyed
     */
    public PythonProcessEvaluator(String pythonExecutable, String scriptPath, List<String> scriptArgs,
                                  Path workingDirectory, Map<String, String> extraEnv, Duration timeout)
            throws IOException {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive, or null for no limit: " + timeout);
        }
        List<String> command = new ArrayList<>(List.of(pythonExecutable, "-u", scriptPath));
        if (scriptArgs != null) command.addAll(scriptArgs);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (workingDirectory != null) pb.directory(workingDirectory.toFile());
        if (extraEnv != null && !extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        this.timeout = timeout;
        this.process = pb.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdoutReader = startStdoutReader(process.getInputStream(), stdoutLines, process.pid());

        // A child that fails the handshake is still running (a library banner on stdout, a wrong
        // banner): destroy it here, since the caller never gets an object to close.
        try {
            String banner = readLine("the ready banner");
            if (banner == null) {
                throw new IOException("Python evaluator exited before sending the ready banner" + exitStatus());
            }
            Map<?, ?> b = parseObject(banner, "ready banner");
            if (!Boolean.TRUE.equals(b.get("ready"))) {
                throw new IOException("Unexpected banner from Python evaluator: " + quote(banner));
            }
        } catch (IOException | RuntimeException e) {
            destroy("its handshake failed (" + e.getMessage() + ")");
            throw e;
        }
        Log.info("PythonProcessEvaluator ready (pid=" + process.pid() + ", script=" + scriptPath + ")");
    }

    /**
     * Copies the child's stdout into {@code lines} one line at a time, then
     * {@link #END_OF_STREAM} (or the {@link IOException} that ended the read).
     * A {@link SynchronousQueue} hands a line over only when a call is waiting
     * for one, so the child is held back by the pipe exactly as with a direct
     * read; the separate thread is what lets a call stop waiting (time-out,
     * interrupt) while the read itself cannot be interrupted. The thread ends at
     * EOF once its last item is taken, or when interrupted by
     * {@link #destroy}/{@link #close()}.
     */
    private static Thread startStdoutReader(InputStream stdout, SynchronousQueue<Object> lines, long pid) {
        return Thread.ofPlatform().daemon().name("python-evaluator-stdout-" + pid).start(() -> {
            Object last = END_OF_STREAM;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    lines.put(line);
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                return;     // destroyed or closed: nobody reads any more
            }
            try {
                lines.put(last);
            } catch (InterruptedException ignored) {
                // closed before anyone asked for the end of the stream
            }
        });
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Convenience overload for integer decision vectors.
     *
     * @param decision the decision vector
     * @return the evaluation result
     * @throws IOException on protocol or process failure
     */
    public Result evaluate(int[] decision) throws IOException {
        return evaluate(Arrays.stream(decision).boxed().toList());
    }

    /**
     * Convenience overload for real decision vectors, written as JSON floats.
     *
     * @param decision the decision vector
     * @return the evaluation result
     * @throws IllegalArgumentException if an element is {@code NaN} or infinite
     * @throws IOException              on protocol or process failure
     */
    public Result evaluate(double[] decision) throws IOException {
        return evaluate(Arrays.stream(decision).boxed().toList());
    }

    /**
     * Sends one decision vector to the child, without its layout, and waits for its result.
     *
     * @param decision flat decision vector; {@link Integer} elements are written as
     *                 JSON integers and {@link Double} elements as JSON floats
     * @return the evaluation result
     * @throws IllegalArgumentException if an element is {@code null}, {@code NaN} or
     *                                  infinite; nothing is sent and the evaluator stays
     *                                  usable
     * @throws InterruptedIOException if the thread was interrupted while waiting (the
     *                                child is destroyed)
     * @throws IOException            if the evaluator is no longer usable, the child
     *                                crashed, did not answer within the time-out (it is
     *                                destroyed), printed a line that is not a JSON object,
     *                                answered out of order, reported an error, or returned
     *                                a non-finite objective or constraint
     */
    public Result evaluate(List<? extends Number> decision) throws IOException {
        return evaluate(decision, null);
    }

    /**
     * Sends one decision vector to the child with the keys of its layout, as the REST payload
     * carries them (see "Child protocol" in the class description), and waits for its result. The
     * child can then tell the segments and the binary variables of the vector apart, and may
     * return the bits of a repair as JSON booleans.
     *
     * @param decision flat decision vector, for instance {@code SolutionVariables.flatten(solution)}
     * @param layout   its layout, for instance {@code SolutionVariables.layoutOf(solution)}, or
     *                 {@code null} to send none
     * @return the evaluation result
     * @throws IllegalArgumentException if an element is {@code null}, {@code NaN} or infinite,
     *                                  or the vector does not have the width of its layout;
     *                                  nothing is sent and the evaluator stays usable
     * @throws InterruptedIOException   if the thread was interrupted while waiting (the child is
     *                                  destroyed)
     * @throws IOException              under the conditions of {@link #evaluate(List)}
     */
    public Result evaluate(List<? extends Number> decision, SolutionVariables.VectorLayout layout)
            throws IOException {
        String reason = unusableReason;
        if (reason != null) {
            throw new IOException("Python evaluator is no longer usable: " + reason);
        }
        String unsendable = unsendable(decision);
        if (unsendable == null && layout != null && decision.size() != layout.width()) {
            unsendable = "it has " + decision.size() + " values but its layout has " + layout.width();
        }
        if (unsendable != null) {
            throw new IllegalArgumentException("Cannot send the decision to the Python evaluator: " + unsendable);
        }
        long id = nextId.getAndIncrement();
        String request = json.writeValueAsString(request(id, decision, layout));
        try {
            stdin.write(request);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            String crashed = "likely crashed" + exitStatus();
            markUnusable("writing to its stdin failed (" + crashed + ")");
            throw new IOException("Python evaluator stopped reading its stdin (" + crashed + ")", e);
        }

        String line = readLine("the response to request " + id);
        if (line == null) {
            throw new IOException("Python evaluator closed stdout (likely crashed" + exitStatus() + ")");
        }
        Map<?, ?> resp = parseObject(line, "response");

        Object respId = resp.get("id");
        if (!(respId instanceof Number) || ((Number) respId).longValue() != id) {
            markUnusable("its stdout is out of step with the requests");
            throw new IOException("Protocol desync: expected id=" + id + ", got " + respId
                    + " (line: " + quote(line) + ")");
        }
        if (resp.get("error") instanceof String error && !error.isEmpty()) {
            throw new IOException("Python evaluator error: " + error);
        }

        Object objRaw = resp.get("objectives");
        if (!(objRaw instanceof List<?>)) {
            throw new IOException("Python evaluator response has no 'objectives' list (line: " + quote(line) + ")");
        }
        Object consRaw = resp.get("constraints");
        List<?> consList = (consRaw instanceof List<?>) ? (List<?>) consRaw : List.of();
        double[] o = toFiniteDoubleArray("objectives", (List<?>) objRaw);
        double[] c = toFiniteDoubleArray("constraints", consList);

        // Optional repaired decision; absent for evaluators that don't modify variables.
        Object varsRaw = resp.get("variables");
        List<Number> v = (varsRaw instanceof List<?>) ? toNumberList((List<?>) varsRaw, binaryPositions(layout)) : null;
        return new Result(o, c, v);
    }

    /**
     * The request for one decision vector: {@code id}, {@code vars} and, with a layout, its keys
     * with the omission rules of the REST payload ({@code TaskPayload}), in the order of that
     * payload.
     */
    static Map<String, Object> request(long id, List<? extends Number> decision, SolutionVariables.VectorLayout layout) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("vars", decision);
        if (layout == null) return request;
        if (layout.composite()) request.put("segmentSizes", layout.segmentSizes());
        String encoding = layout.wireName();
        if (!SolutionVariables.Encoding.INT.wireName().equals(encoding)) {
            request.put("encoding", encoding);
            if (layout.composite()) request.put("segmentEncodings", layout.wireSegmentEncodings());
        }
        if (!layout.bitsPerVariable().isEmpty()) request.put("bitsPerVariable", layout.bitsPerVariable());
        return request;
    }

    /** Which positions of the vector hold a bit, or {@code null} without a layout. */
    private static boolean[] binaryPositions(SolutionVariables.VectorLayout layout) {
        if (layout == null) return null;
        boolean[] binary = new boolean[layout.width()];
        int offset = 0;
        for (SolutionVariables.VectorLayout.Segment segment : layout.segments()) {
            if (segment.encoding() == SolutionVariables.Encoding.BINARY) {
                Arrays.fill(binary, offset, offset + segment.width(), true);
            }
            offset += segment.width();
        }
        return binary;
    }

    /**
     * Why a decision vector cannot be written as a list of JSON numbers, or {@code null} if it
     * can: Jackson would write a {@code null} element as {@code null} and a non-finite one as the
     * string {@code "NaN"} (or {@code "Infinity"}), which no child can evaluate as a number.
     *
     * @param decision the decision vector
     * @return the reason, naming the first offending position, or {@code null}
     */
    static String unsendable(List<? extends Number> decision) {
        for (int i = 0; i < decision.size(); i++) {
            if (decision.get(i) == null) return "variables[" + i + "] is null";
        }
        return SolutionVariables.checkFinite(decision);
    }

    // ── Reading the child ─────────────────────────────────────────────────────

    /**
     * Waits for the next stdout line of the child, within the time-out if one
     * was given. The wait gives up once the stdout reader has stopped without
     * handing anything over — {@link #close()} from another thread stops it, and
     * from then on no line can arrive — instead of blocking forever.
     *
     * @param awaited what the line should be, for the time-out message
     * @return the line, or {@code null} once the child has closed its stdout
     * @throws IOException on time-out, interrupt, read failure or a stopped
     *                     reader (the evaluator becomes unusable; the child is
     *                     destroyed on a time-out or an interrupt)
     */
    private String readLine(String awaited) throws IOException {
        long deadline = (timeout == null) ? 0 : System.nanoTime() + nanos(timeout);
        Object item = null;
        try {
            while (item == null) {
                long wait = READER_CHECK_NANOS;
                if (timeout != null) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) break;
                    wait = Math.min(wait, left);
                }
                item = stdoutLines.poll(wait, TimeUnit.NANOSECONDS);
                // A SynchronousQueue hands over only to a waiting taker: once the reader is gone, nothing comes.
                if (item == null && !stdoutReader.isAlive()) {
                    markUnusable("its stdout reader stopped");
                    throw new IOException("Python evaluator is no longer usable: " + unusableReason
                            + " (while waiting for " + awaited + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy("it was interrupted while waiting for " + awaited);
            throw new InterruptedIOException("Interrupted while waiting for " + awaited
                    + " from the Python evaluator — child destroyed");
        }
        if (item == null) {
            String message = "Python evaluator did not send " + awaited + " within " + timeout.toMillis()
                    + " ms — child destroyed";
            Log.warn(message + " (pid=" + process.pid() + ")");
            destroy("it timed out waiting for " + awaited);
            throw new IOException(message);
        }
        if (item == END_OF_STREAM) {
            markUnusable("the child closed its stdout (likely crashed" + exitStatus() + ")");
            return null;
        }
        if (item instanceof IOException e) {
            markUnusable("reading its stdout failed (" + e.getMessage() + ")");
            throw new IOException("Reading the Python evaluator's stdout failed: " + e.getMessage(), e);
        }
        return (String) item;
    }

    /**
     * Parses one protocol line. Anything but a JSON object — a stray print, the
     * JSON literal {@code null}, a bare {@code NaN} token — makes the evaluator
     * unusable, because the line the protocol expected is still in the pipe.
     *
     * @param line the line read from the child
     * @param what which line this is, for the message
     * @return the parsed object
     * @throws IOException if the line is not a JSON object; the message quotes it
     */
    private Map<?, ?> parseObject(String line, String what) throws IOException {
        Object parsed;
        try {
            parsed = json.readValue(line, Object.class);
        } catch (JsonProcessingException e) {
            markUnusable("its stdout is out of step with the requests");
            throw new IOException("Python evaluator printed a " + what + " line that is not valid JSON"
                    + " — stdout is reserved for protocol lines (line: " + quote(line) + ")", e);
        }
        if (!(parsed instanceof Map<?, ?> object)) {
            markUnusable("its stdout is out of step with the requests");
            throw new IOException("Python evaluator printed a " + what + " line that is not a JSON object"
                    + " — stdout is reserved for protocol lines (line: " + quote(line) + ")");
        }
        return object;
    }

    private static double[] toFiniteDoubleArray(String field, List<?> list) throws IOException {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object e = list.get(i);
            if (!(e instanceof Number n)) {
                throw new IOException("Python evaluator returned " + field + "[" + i + "] = " + e + " (not a number)");
            }
            out[i] = n.doubleValue();
            if (!Double.isFinite(out[i])) {
                throw new IOException("Python evaluator returned non-finite " + field + "[" + i + "] = " + e);
            }
        }
        return out;
    }

    /**
     * The repaired variables of a response, as numbers: a JSON {@code true} or {@code false} at a
     * binary position becomes the {@link Integer} 1 or 0.
     *
     * @param list   the {@code variables} array of the response
     * @param binary which positions hold a bit, or {@code null} when the request had no layout
     */
    private static List<Number> toNumberList(List<?> list, boolean[] binary) throws IOException {
        List<Number> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (e instanceof Boolean bit && binary != null && i < binary.length && binary[i]) {
                out.add(bit ? 1 : 0);
                continue;
            }
            if (!(e instanceof Number n)) {
                throw new IOException("Python evaluator returned variables[" + i + "] = " + e + " (not a number)");
            }
            out.add(n);
        }
        return Collections.unmodifiableList(out);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Ends the child: closes its stdin (EOF, the protocol's "no more requests"),
     * waits up to 10 s for it to exit, then destroys it and every process it
     * started. Idempotent; never throws. Every later {@link #evaluate} call fails.
     */
    @Override
    public void close() {
        markUnusable("it has been closed");
        try {
            stdin.close();
        } catch (IOException ignored) {}
        try {
            if (!process.waitFor(EXIT_GRACE_SECONDS, TimeUnit.SECONDS)) {
                Log.warn("Python evaluator did not exit within " + EXIT_GRACE_SECONDS + " s of the end of its input"
                        + " — destroying it (pid=" + process.pid() + ")");
                destroyProcessTree();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessTree();
        }
        stdoutReader.interrupt();
    }

    /** Destroys the child and makes every later call fail with {@code reason}. */
    private void destroy(String reason) {
        markUnusable(reason);
        destroyProcessTree();
        stdoutReader.interrupt();
        try {
            stdin.close();
        } catch (IOException ignored) {}
    }

    /**
     * Kills the child and its descendants. The descendants matter: a pipe held
     * open by a grandchild (e.g. the real interpreter behind a virtual
     * environment's launcher on Windows) would otherwise outlive the child.
     * They are listed before the child dies, because orphans are re-parented.
     */
    private void destroyProcessTree() {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    /** Records the first reason the evaluator became unusable. */
    private void markUnusable(String reason) {
        if (unusableReason == null) unusableReason = reason;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** {@code ", exit code N"} once the child has exited (waiting up to 1 s), else {@code ""}. */
    private String exitStatus() {
        try {
            if (process.waitFor(1, TimeUnit.SECONDS)) return ", exit code " + process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "";
    }

    /** A protocol line for an exception message, shortened when very long. */
    private static String quote(String line) {
        return line.length() <= MAX_QUOTED_LINE
                ? line
                : line.substring(0, MAX_QUOTED_LINE) + "… (" + line.length() + " chars)";
    }

    /** The time-out in nanoseconds, saturated for absurdly long durations. */
    private static long nanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}

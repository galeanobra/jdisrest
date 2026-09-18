package es.unex.jdisrest.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a long-running Python child process used as a synchronous evaluator
 * for the local/sequential mode. Communicates over the child's stdin/stdout
 * with a line-delimited JSON protocol; the child's stderr is inherited.
 *
 * <p>Protocol:
 * <ul>
 *   <li>request:  {@code {"id": <long>, "vars": [<number>, ...]}} — integer
 *       variables are written as JSON integers, real ones as JSON floats</li>
 *   <li>response: {@code {"id": <long>, "objectives": [...], "constraints": [...],
 *       "variables": [...]}} ({@code constraints} and {@code variables} optional)</li>
 *   <li>error:    {@code {"id": <long>, "error": "<msg>"}}</li>
 *   <li>EOF on stdin terminates the loop.</li>
 * </ul>
 *
 * <p>Objectives and constraints must be finite numbers; a {@code null},
 * {@code NaN} or infinite value is reported as an {@link IOException}, the
 * same way the REST master rejects such results. The numeric type of the
 * returned {@code variables} is irrelevant: the caller converts each value to
 * the type of the destination variable.
 *
 * <p>Not thread-safe: {@link #evaluate(List)} must be called from a single
 * thread. The stock jMetal NSGA-II is single-threaded, so this is sufficient.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class PythonProcessEvaluator implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(0);

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
         * back with the conversion the destination requires.
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

    public PythonProcessEvaluator(String pythonExecutable, String scriptPath) throws IOException {
        this(pythonExecutable, scriptPath, Map.of());
    }

    public PythonProcessEvaluator(String pythonExecutable, String scriptPath, Map<String, String> extraEnv) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(pythonExecutable, "-u", scriptPath);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!extraEnv.isEmpty()) pb.environment().putAll(extraEnv);
        this.process = pb.start();
        this.stdin  = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        String banner = stdout.readLine();
        if (banner == null) {
            throw new IOException("Python evaluator exited before sending the ready banner");
        }
        Map<?, ?> b = json.readValue(banner, Map.class);
        if (!Boolean.TRUE.equals(b.get("ready"))) {
            throw new IOException("Unexpected banner from Python evaluator: " + banner);
        }
        Log.info("PythonProcessEvaluator ready (pid=" + process.pid() + ", script=" + scriptPath + ")");
    }

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
     * Sends one decision vector to the child and waits for its result.
     *
     * @param decision flat decision vector; {@link Integer} elements are written as
     *                 JSON integers and {@link Double} elements as JSON floats
     * @return the evaluation result
     * @throws IOException if the child crashed, answered out of order, reported an
     *                     error, or returned a non-finite objective or constraint
     */
    public Result evaluate(List<? extends Number> decision) throws IOException {
        long id = nextId.getAndIncrement();
        Map<String, Object> req = Map.of("id", id, "vars", decision);
        stdin.write(json.writeValueAsString(req));
        stdin.write('\n');
        stdin.flush();

        String line = stdout.readLine();
        if (line == null) {
            throw new IOException("Python evaluator closed stdout (likely crashed)");
        }
        Map<?, ?> resp = json.readValue(line, Map.class);

        Object respId = resp.get("id");
        if (!(respId instanceof Number) || ((Number) respId).longValue() != id) {
            throw new IOException("Protocol desync: expected id=" + id + ", got " + respId
                    + " (line: " + line + ")");
        }
        Object error = resp.get("error");
        if (error != null) {
            throw new IOException("Python evaluator error: " + error);
        }

        Object objRaw = resp.get("objectives");
        if (!(objRaw instanceof List<?>)) {
            throw new IOException("Python evaluator response has no 'objectives' list (line: " + line + ")");
        }
        Object consRaw = resp.get("constraints");
        List<?> consList = (consRaw instanceof List<?>) ? (List<?>) consRaw : List.of();
        double[] o = toFiniteDoubleArray("objectives", (List<?>) objRaw);
        double[] c = toFiniteDoubleArray("constraints", consList);

        // Optional repaired decision; absent for evaluators that don't modify variables.
        Object varsRaw = resp.get("variables");
        List<Number> v = (varsRaw instanceof List<?>) ? toNumberList((List<?>) varsRaw) : null;
        return new Result(o, c, v);
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

    private static List<Number> toNumberList(List<?> list) throws IOException {
        List<Number> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (!(e instanceof Number n)) {
                throw new IOException("Python evaluator returned variables[" + i + "] = " + e + " (not a number)");
            }
            out.add(n);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (IOException ignored) {}
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.warn("Python evaluator did not exit within 10s; destroying");
                process.destroy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
        }
        try {
            stdout.close();
        } catch (IOException ignored) {}
    }
}

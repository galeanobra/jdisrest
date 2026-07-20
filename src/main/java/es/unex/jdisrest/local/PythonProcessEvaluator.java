package es.unex.jdisrest.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives a long-running Python child process used as a synchronous evaluator
 * for the local/sequential mode. Communicates over the child's stdin/stdout
 * with a line-delimited JSON protocol; the child's stderr is inherited.
 *
 * <p>Protocol (matches {@code worker/local_eval.py}):
 * <ul>
 *   <li>request:  {@code {"id": <long>, "vars": [<int>, ...]}}</li>
 *   <li>response: {@code {"id": <long>, "objectives": [...], "constraints": [...]}}</li>
 *   <li>error:    {@code {"id": <long>, "error": "<msg>"}}</li>
 *   <li>EOF on stdin terminates the loop.</li>
 * </ul>
 *
 * <p>Not thread-safe: {@link #evaluate(int[])} must be called from a single
 * thread. The stock jMetal NSGA-II is single-threaded, so this is sufficient.
 */
public final class PythonProcessEvaluator implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final BufferedReader stdout;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(0);

    public static final class Result {
        public final double[] objectives;
        public final double[] constraints;
        /**
         * Optional repaired/modified decision vector, mirroring the
         * {@code variables} field of the REST {@code TaskResultPayload}.
         * {@code null} (the common case) means the evaluator did not modify
         * the decision and the caller should keep the original variables.
         */
        public final int[] variables;
        public Result(double[] objectives, double[] constraints) {
            this(objectives, constraints, null);
        }
        public Result(double[] objectives, double[] constraints, int[] variables) {
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

    public Result evaluate(int[] decision) throws IOException {
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

        Object consRaw = resp.get("constraints");
        List<?> consList = (consRaw instanceof List<?>) ? (List<?>) consRaw : List.of();
        double[] o = toDoubleArray((List<?>) resp.get("objectives"));
        double[] c = toDoubleArray(consList);

        // Optional repaired decision; absent for evaluators that don't modify variables.
        Object varsRaw = resp.get("variables");
        int[] v = (varsRaw instanceof List<?>) ? toIntArray((List<?>) varsRaw) : null;
        return new Result(o, c, v);
    }

    private static double[] toDoubleArray(List<?> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).doubleValue();
        }
        return out;
    }

    private static int[] toIntArray(List<?> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).intValue();
        }
        return out;
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

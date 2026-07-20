package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.Timings;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Java worker that talks to the master over REST.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Main thread: GET /api/v1/tasks/next → receives variables, evaluates, POSTs the result.</li>
 *   <li>Heartbeat thread: POST /api/v1/workers/heartbeat every 15 s (in parallel with evaluation).</li>
 * </ol>
 *
 * <p>The independent heartbeat ensures the master does not declare a worker dead
 * while it is busy evaluating a 30-minutes-or-longer fitness function.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class RestWorker<S extends Solution<?>> implements Closeable {

    // If the master dies, workers must detect it and stop.
    // MAX_CONSECUTIVE_ERRORS: consecutive failures in the main loop before giving up.
    // MAX_HEARTBEAT_FAILURES: consecutive heartbeat failures before interrupting the main thread.
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final int MAX_HEARTBEAT_FAILURES = 3;

    private final String masterUrl;   // e.g. "http://10.0.0.1:8080"
    private final String workerId;
    private final Problem<S> problem;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Thread heartbeatThread;
    /** Flag raised by the heartbeat thread when it concludes the master is gone. */
    private volatile boolean masterDead = false;

    public RestWorker(String masterUrl, Problem<S> problem) {
        this.masterUrl = masterUrl.replaceAll("/$", ""); // strip trailing slash
        this.problem = problem;
        this.workerId = "worker-java-" + UUID.randomUUID().toString().substring(0, 8);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        Log.info("RestWorker " + workerId + " -> " + masterUrl);
    }

    public void run() {
        startHeartbeatThread();
        try {
            evaluationLoop();
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
        httpClient.close();
    }

    // ── Main evaluation loop ──────────────────────────────────────────────────

    private void evaluationLoop() {
        int consecutiveErrors = 0;
        while (!masterDead) {
            try {
                Map<String, Object> task = requestNextTask();
                consecutiveErrors = 0;  // Successful round-trip → reset counter

                if (task == null) {
                    // No work available yet — back off briefly before retrying.
                    Thread.sleep(5_000);
                    continue;
                }

                long taskId = ((Number) task.get("taskId")).longValue();
                List<Integer> variables = (List<Integer>) task.get("variables");

                // Build the solution and copy in the variables sent by the master.
                S solution = problem.createSolution();
                List<Object> solutionVars = (List<Object>) (List<?>) solution.variables();
                for (int i = 0; i < variables.size(); i++) {
                    solutionVars.set(i, variables.get(i));
                }

                // Evaluate (may take minutes or hours — the heartbeat runs independently).
                long start = System.currentTimeMillis();
                Log.info("Worker " + workerId + " evaluating task " + taskId);
                problem.evaluate(solution);
                long elapsed = System.currentTimeMillis() - start;
                Log.info("Worker " + workerId + " task " + taskId + " evaluated in " + elapsed + "ms");

                if (masterDead) {
                    Log.warn("Worker " + workerId + ": master loss detected after evaluation — discarding result and stopping");
                    break;
                }

                submitResult(taskId, solution, elapsed);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (masterDead) {
                    Log.info("Worker " + workerId + " interrupted by heartbeat: master is gone, stopping");
                } else {
                    Log.info("Worker " + workerId + " interrupted, stopping");
                }
                break;
            } catch (AlgorithmFinishedException e) {
                Log.info("Worker " + workerId + " stopping: master algorithm finished");
                break;
            } catch (Exception e) {
                consecutiveErrors++;
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.error("Worker " + workerId + ": " + consecutiveErrors
                            + " consecutive errors talking to the master "
                            + "— assuming the master is gone, stopping");
                    break;
                }
                Log.warn("Worker " + workerId + " error contacting master (attempt "
                        + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): "
                        + e.getMessage() + " — retrying in 10s");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // ── HTTP calls ────────────────────────────────────────────────────────────

    /**
     * GET /api/v1/tasks/next
     * Returns {@code null} when the server replied 204 (no task available).
     * The server long-polls for up to 30 s internally.
     */
    private Map<String, Object> requestNextTask() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/tasks/next?workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 204) return null;
        if (response.statusCode() == 410) throw new AlgorithmFinishedException();
        if (response.statusCode() != 200) {
            throw new RuntimeException("Unexpected status " + response.statusCode());
        }
        return mapper.readValue(response.body(), Map.class);
    }

    /**
     * POST /api/v1/tasks/{taskId}/result
     */
    private void submitResult(long taskId, S solution, long elapsedMs) throws Exception {
        // Extract objectives.
        double[] objArray = solution.objectives();
        Double[] objectives = new Double[objArray.length];
        for (int i = 0; i < objArray.length; i++) objectives[i] = objArray[i];

        // Extract constraints.
        double[] constrArray = solution.constraints();
        Double[] constraints = new Double[constrArray.length];
        for (int i = 0; i < constrArray.length; i++) constraints[i] = constrArray[i];

        String body = mapper.writeValueAsString(Map.of(
                "workerId", workerId,
                "objectives", objectives,
                "constraints", constraints,
                "evaluationTimeMs", elapsedMs
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/tasks/" + taskId + "/result"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 404) {
            // The master no longer expects this result (the watchdog already requeued it).
            Log.warn("Master rejected result for taskId " + taskId
                    + " (already requeued by watchdog)");
        } else if (response.statusCode() != 200) {
            throw new RuntimeException("Unexpected status " + response.statusCode()
                    + " from POST /tasks/result");
        }
    }

    // ── Heartbeat (separate thread) ───────────────────────────────────────────

    /**
     * Sends heartbeats every 15 s independently of the main thread, so the master
     * does not declare a worker dead while it is in the middle of a 50-minute evaluation.
     */
    private void startHeartbeatThread() {
        Thread mainThread = Thread.currentThread();
        heartbeatThread = new Thread(() -> {
            String localAddress = getLocalAddress();
            int consecutiveFailures = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    sendHeartbeat(localAddress);
                    consecutiveFailures = 0;  // Heartbeat OK → reset counter
                    Thread.sleep(Timings.HEARTBEAT_INTERVAL_S * 1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                        Log.error("Worker " + workerId + ": " + consecutiveFailures
                                + " consecutive heartbeat failures "
                                + "— assuming master is gone, signaling shutdown");
                        masterDead = true;
                        mainThread.interrupt();  // unblock the main thread if it's in sleep/HTTP
                        break;
                    }
                    Log.warn("Heartbeat error (" + consecutiveFailures + "/" + MAX_HEARTBEAT_FAILURES
                            + "): " + e.getMessage());
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        heartbeatThread.setName("heartbeat-" + workerId);
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void sendHeartbeat(String address) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(masterUrl + "/api/v1/workers/heartbeat"
                        + "?workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8)
                        + "&address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private String getLocalAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

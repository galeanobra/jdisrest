package es.unex.jdisrest.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.config.TestProblems.FromFactory;
import es.unex.jdisrest.distributed.RestWorker;
import es.unex.jdisrest.distributed.SteadyStateMaster;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static es.unex.jdisrest.config.TestProblems.bits;
import static es.unex.jdisrest.config.TestProblems.composite;
import static es.unex.jdisrest.config.TestProblems.integers;
import static es.unex.jdisrest.config.TestProblems.reals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A run of the launcher itself for a problem whose solutions are not real-coded, end to end:
 * {@link ConfiguredMaster#run} starts NSGA-II from a file on a composite of three integers, two
 * reals and binary variables of 5 and 3 bits, in segments it names {@code ints}, {@code reals}
 * and {@code bits} ({@link Mixed}), on a free port of this machine, and an in-process
 * {@link RestWorker} evaluates its tasks. Before the worker starts, the test sends the
 * configuration back through {@code POST /api/v1/config} with the budget raised from 20 to 40
 * evaluations and the bit-flip probability of the {@code bits} segment changed, which the handler
 * of the launcher reads for the layout of the composite. The tests then check that the launcher
 * ended with status 0, that it created one solution of the problem for the layout and no other
 * besides those the run creates itself, that the run ended on the new budget, and that
 * {@code VAR.csv} and {@code FUN.csv} hold the rows of composite traces.
 *
 * <p>Like the other {@code *IT} classes, it starts a master, which registers itself in static
 * singletons that nothing resets, so it needs a JVM of its own: Failsafe runs it in a new JVM
 * ({@code mvn verify}), and it runs only when the system property {@code jdisrest.it} is
 * {@code true}. The discovery file and {@code status.json} go to a temporary folder;
 * {@code VAR.csv} and {@code FUN.csv} go to the working directory, where the launcher writes them,
 * which is {@code target/it-workdir} under {@code mvn verify}.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfiguredMasterRunIT {

    /** Longest wait for the master, the run or the worker; a test that reaches it fails. */
    static final long TIMEOUT_S = 60;

    private static final String HOST = "127.0.0.1";
    private static final int POPULATION_SIZE = 4;
    private static final int BUDGET = 40;
    private static final String CONFIG = "/api/v1/config";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The configuration file of the run, and where the master writes {@code .master-endpoint} and {@code status.json}. */
    @TempDir
    static Path dataPath;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_S))
            .build();
    private final Mixed problem = new Mixed();
    private final AtomicInteger status = new AtomicInteger(-1);
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private Thread launcher;
    private RestWorker<CompositeSolution> worker;

    // What the configuration endpoint answered before the worker started.
    private HttpResponse<String> change;

    /**
     * Three integers in [0, 10], two reals in [-1, 1] and binary variables of 5 and 3 bits, as
     * {@link TestProblems#mixed()}. The first objective is the sum of the values and of the bits
     * set, the second grows with what that sum leaves to 40, so the front spans the whole range.
     */
    static final class Mixed extends FromFactory<CompositeSolution> implements NamedSegments {
        Mixed() {
            super("Mixed", () -> composite(integers(3, 0, 10), reals(2, -1.0, 1.0), bits(5, 3)));
        }

        @Override
        public List<String> segmentNames() {
            return List.of("ints", "reals", "bits");
        }

        @Override
        public CompositeSolution evaluate(CompositeSolution solution) {
            List<Integer> x = ((IntegerSolution) solution.variables().get(0)).variables();
            List<Double> r = ((DoubleSolution) solution.variables().get(1)).variables();
            List<BinarySet> b = ((BinarySolution) solution.variables().get(2)).variables();
            double sum = x.stream().mapToInt(Integer::intValue).sum() + r.get(0) + r.get(1)
                    + b.stream().mapToInt(BinarySet::cardinality).sum();
            solution.objectives()[0] = sum;
            solution.objectives()[1] = (40 - sum) * (40 - sum) / 40;
            return solution;
        }
    }

    @BeforeAll
    void runTheLauncherUntilTheBudgetIsSpent() throws Exception {
        assertNull(SteadyStateMaster.getInstance(),
                "a master already ran in this JVM: run each *IT class in a JVM of its own (mvn verify)");
        System.setProperty("jdisrest.dataPath", dataPath.toString());
        System.setProperty("server.address", HOST);  // only this machine can reach the master
        Path file = dataPath.resolve("mixed.properties");
        Files.writeString(file, String.join("\n", "algorithm = nsgaii", "maxEvaluations = 20",
                "populationSize = " + POPULATION_SIZE, "bits.mutation.probability = 1/n") + "\n", StandardCharsets.UTF_8);
        int port = freePort();
        List<String> args = List.of(HOST, Integer.toString(port), file.toString());
        PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
        launcher = Thread.ofPlatform().daemon().name("launcher").start(() -> status.set(
                ConfiguredMaster.run(() -> problem, "Mixed", args, "ConfiguredMasterRunIT", System.out, err)));

        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (!MasterFacade.isReady()) {
            assertTrue(launcher.isAlive(), "the launcher ended before its master was ready: " + errors());
            assertTrue(Instant.now().isBefore(deadline), "the master never became ready");
            Thread.sleep(5);
        }
        String url = "http://" + HOST + ":" + port;
        URI endpoint = URI.create(url + CONFIG);
        String current = send(HttpRequest.newBuilder(endpoint).GET()).body();
        assertTrue(current.contains("maxEvaluations = 20\n") && current.contains("bits.mutation.probability = 1/n\n"),
                "GET serves the file:\n" + current);
        change = send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(current
                .replace("maxEvaluations = 20\n", "maxEvaluations = " + BUDGET + "\n")
                .replace("bits.mutation.probability = 1/n\n", "bits.mutation.probability = 2/n\n"),
                StandardCharsets.UTF_8)));

        worker = new RestWorker<>(url, new Mixed(), "java-0");
        Thread evaluator = Thread.ofPlatform().daemon().name("worker").start(worker::run);
        launcher.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        assertFalse(launcher.isAlive(), "the run did not end within " + TIMEOUT_S + " s");
        worker.close();
        evaluator.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        assertFalse(evaluator.isAlive(), "the worker did not stop after the end of the run");
    }

    @AfterAll
    void shutDown() {
        if (worker != null) {
            worker.close();
        }
        if (launcher != null && launcher.isAlive()) {
            MasterFacade.requestStop();  // the launcher then shuts its master down
        }
        http.close();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void theLauncherEndsTheRunWithStatus0() {
        assertAll(
                () -> assertEquals(0, status.get(), "the run ended: " + errors()),
                () -> assertEquals("", errors(), "no usage nor configuration error"));
    }

    @Test
    void theLauncherCreatesOneSolutionForTheLayoutAndNoOther() {
        // What the run creates by itself: the solution whose encoding run() checks before the
        // first task, and the initial population.
        int run = 1 + POPULATION_SIZE;

        assertEquals(1 + run, problem.created.get(),
                "one solution gives the layout, which the algorithm and the handler take without creating another");
    }

    @Test
    void theHandlerOfTheLauncherReadsAChangeForTheLayoutOfTheComposite() throws IOException {
        JsonNode body = JSON.readTree(change.body());

        assertAll(
                () -> assertEquals(200, change.statusCode(), change.body()),
                () -> assertTrue(body.get("applied").asText().startsWith("NSGA-II, " + BUDGET + " evaluations, population "
                        + POPULATION_SIZE + ", ints [crossover sbx (probability 0.9, distributionIndex 20), mutation "
                        + "polynomial (probability 0.3333, distributionIndex 20)], reals [crossover sbx (probability 0.9, "
                        + "distributionIndex 20), mutation polynomial (probability 0.5, distributionIndex 20)], bits "
                        + "[crossover singlePoint (probability 0.9), mutation bitFlip (probability 0.25)]"),
                        "the keys of each segment, k/n over the 8 bits of the bits segment: " + body));
    }

    @Test
    void theRunEndsOnTheBudgetOfTheChange() throws IOException {
        JsonNode snapshot = JSON.readTree(Files.readString(dataPath.resolve("status.json")));

        assertAll(
                () -> assertTrue(snapshot.get("finished").asBoolean(), "the last snapshot: " + snapshot),
                () -> assertEquals(BUDGET, snapshot.get("evaluations").asInt(), "the new budget: " + snapshot));
    }

    @Test
    void theResultIsWrittenInTheRowsOfCompositeTraces() throws IOException {
        List<String> variables = Files.readAllLines(Path.of(ConfiguredMaster.VARIABLES_FILE));
        List<String> objectives = Files.readAllLines(Path.of(ConfiguredMaster.OBJECTIVES_FILE));

        assertFalse(variables.isEmpty(), "the result of the run");
        assertEquals(variables.size(), objectives.size(), "a row of objectives for each solution");
        // One token per jMetal variable, joined by spaces: three integers, two reals and two bit
        // strings; then the objectives and the constraints.
        variables.forEach(row -> assertTrue(row.matches("(\\d+ ){3}\\S+ \\S+ [01]{5} [01]{3},\\[\\S+  \\S+],\\[]"),
                ConfiguredMaster.VARIABLES_FILE + ": " + row));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A port that no server listens on now: the launcher takes no port 0. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return socket.getLocalPort();
        }
    }

    /** What the launcher printed to its error stream: usage and configuration errors only. */
    private String errors() {
        return errors.toString(StandardCharsets.UTF_8);
    }

    /** A request to the master that must be answered, its status and its body as text. */
    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(TIMEOUT_S)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

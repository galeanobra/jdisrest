package es.unex.jdisrest.encodings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.unex.jdisrest.config.AlgorithmConfig;
import es.unex.jdisrest.config.AlgorithmReconfiguration;
import es.unex.jdisrest.config.ConfigHistory;
import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PAES on a composite of an integer, a real and a binary segment ({@link IntegersRealsAndBits}),
 * built by {@code ConfiguredMaster.createAlgorithm} from a file with the operators of each segment
 * under its name ({@code encodings/mixed.properties}), with an {@link AlgorithmReconfiguration}
 * registered as {@code ConfiguredMaster} registers its own, but built with the public constructor,
 * which creates a solution to check the layout. Before the workers start, the test reads the
 * configuration with {@code GET /api/v1/config} and sends it back with {@code POST}: once with an
 * operator key without the prefix of a segment, which gets {@code 422}, and once with the budget
 * raised from the 100 evaluations of the file to 200 and the bit-flip probability of the binary
 * segment changed, which applies, so the run ends on the new budget. The tasks travel as three
 * integers, two reals and eight bits, with {@code segmentSizes} [3, 2, 8], and the workers set the
 * first bit of the binary segment before they evaluate; the Python worker sends every value back
 * as a number, the bits as ints.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class MixedRunIT extends EncodingRunScenario<CompositeSolution> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "mixed.properties";
    private static final String CONFIG = "/api/v1/config";

    private Problem<CompositeSolution> problem;
    private SteadyStateEvolutionaryAlgorithm<CompositeSolution> algorithm;
    private AlgorithmConfig config;
    private ConfigHistory history;
    private Path traces;

    // What the configuration endpoints answered before the workers started.
    private HttpResponse<String> withoutPrefix;
    private HttpResponse<String> change;
    private String changed;
    private String inUse;

    /**
     * Three integers in [0, 10], two reals in [0, 1] and two binary variables of 5 and 3 bits. The
     * first objective grows with the values and the bits set, the second with what the first
     * leaves out, so the front spans the whole range.
     */
    static final class IntegersRealsAndBits implements Problem<CompositeSolution> {
        @Override public int numberOfVariables() { return 3; }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "IntegersRealsAndBits"; }

        @Override
        public CompositeSolution createSolution() {
            IntegerSolution integers = new DefaultIntegerSolution(Collections.nCopies(3, Bounds.create(0, 10)), 2, 0);
            DoubleSolution reals = new DefaultDoubleSolution(Collections.nCopies(2, Bounds.create(0.0, 1.0)), 2, 0);
            BinarySolution bits = new DefaultBinarySolution(List.of(5, 3), 2, 0);
            return new CompositeSolution(List.of(integers, reals, bits));
        }

        @Override
        public CompositeSolution evaluate(CompositeSolution solution) {
            List<Integer> x = ((IntegerSolution) solution.variables().get(0)).variables();
            List<Double> r = ((DoubleSolution) solution.variables().get(1)).variables();
            List<BinarySet> b = ((BinarySolution) solution.variables().get(2)).variables();
            double total = x.stream().mapToInt(Integer::intValue).sum() + r.get(0) + r.get(1);
            int ones = b.stream().mapToInt(BinarySet::cardinality).sum();
            solution.objectives()[0] = total + ones;
            solution.objectives()[1] = (32 - total) * (32 - total) / 32.0 + (8 - ones);
            return solution;
        }
    }

    @Override
    Problem<CompositeSolution> problem() {
        return new IntegersRealsAndBits();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<CompositeSolution> algorithm(String host, int port,
                                                                  Problem<CompositeSolution> problem, String tracesFolder) {
        try {
            Path file = Path.of(MixedRunIT.class.getResource("/encodings/" + FILE).toURI());
            List<String> overrides = List.of("tracesFolder=" + tracesFolder);
            this.problem = problem;
            traces = Path.of(tracesFolder);
            config = AlgorithmConfig.load(file, overrides, problem);
            algorithm = ConfiguredMaster.createAlgorithm(host, port, problem, config);
            history = ConfigHistory.of(file, overrides, config);
            return algorithm;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Records the start and registers the handler, as {@code ConfiguredMaster} does once the
     * budget is in {@code MasterFacade}, then edits the configuration in use and sends it back.
     */
    @Override
    void beforeTheWorkersStart(String url) throws Exception {
        history.recordStart(config);
        MasterFacade.setConfigurationHandler(new AlgorithmReconfiguration(algorithm, config, problem, history));
        URI endpoint = URI.create(url + CONFIG);
        String current = send(HttpRequest.newBuilder(endpoint).GET()).body();
        assertTrue(current.contains("maxEvaluations = 100\n") && current.contains("binary.mutation.probability = 1/n\n"),
                "GET serves the file with its override:\n" + current);

        withoutPrefix = send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(
                current + "\nmutation.probability = 2/n\n", StandardCharsets.UTF_8)));
        changed = current.replace("maxEvaluations = 100\n", "maxEvaluations = " + budget() + "\n")
                .replace("binary.mutation.probability = 1/n\n", "binary.mutation.probability = 2/n\n");
        change = send(HttpRequest.newBuilder(endpoint).POST(HttpRequest.BodyPublishers.ofString(changed,
                StandardCharsets.UTF_8)));
        inUse = send(HttpRequest.newBuilder(endpoint).GET()).body();
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(CompositeSolution solution) {
        ((BinarySolution) solution.variables().get(2)).variables().get(0).set(0);
    }

    @Override
    boolean isRepaired(CompositeSolution solution) {
        return ((BinarySolution) solution.variables().get(2)).variables().get(0).get(0);
    }

    @Override
    Pattern varRow() {
        // One token per jMetal variable, joined by spaces: three integers, two reals, then one bit
        // string per binary variable, the repaired bit first; then the objectives and the constraints.
        return Pattern.compile("(\\d+ ){3}(" + JAVA_REAL + " ){2}1[01]{4} [01]{3},\\[\\S+  \\S+],\\[]");
    }

    @Override
    Pattern extremeVariables() {
        // The same tokens, each in a column of its own: the integers and the bit strings as written,
        // and the reals in shape only, since Java and Python write those from 0.001 to 1 alike.
        return Pattern.compile("(\\d+,){3}(" + PYTHON_REAL + ",){2}1[01]{4},[01]{3}");
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:integers_reals_and_bits", "--encoding", "mixed",
                "--variables", "13", "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "mixed", "segmentSizes": [3, 2, 8],
                 "segmentEncodings": ["int", "double", "binary"], "bitsPerVariable": [5, 3],
                 "valueTypes": [["int"], ["float"], ["int"]]}""";
    }

    // ── Changes during the run ────────────────────────────────────────────────

    @Test
    void aChangeWithoutThePrefixesOfTheSegmentsIsRefusedWith422() throws IOException {
        JsonNode body = JSON.readTree(withoutPrefix.body());

        assertAll(
                () -> assertEquals(422, withoutPrefix.statusCode(), withoutPrefix.body()),
                () -> assertTrue(body.get("error").asText().startsWith("unknown keys for paes with these operators: "
                        + "mutation.probability. Valid keys: algorithm, maxEvaluations, archiveSize, integer.mutation, "),
                        "the keys of a composite take the names of its segments: " + body));
    }

    @Test
    void aChangeWithThePrefixesOfTheSegmentsAppliesBeforeTheFirstResult() throws IOException {
        JsonNode body = JSON.readTree(change.body());
        List<String> log = Files.readAllLines(traces.resolve("configuration.log"));

        assertAll(
                () -> assertEquals(200, change.statusCode(), change.body()),
                () -> assertTrue(body.get("applied").asText().startsWith("PAES, 200 evaluations, archive 20, integer "
                        + "[mutation random (probability 0.3333)], real [mutation polynomial (probability 0.5, "
                        + "distributionIndex 20)], binary [mutation bitFlip (probability 0.25)], "),
                        "k/n over the 8 bits of the binary segment: " + body),
                () -> assertEquals(changed, inUse, "GET serves the text applied"),
                () -> assertEquals(changed, Files.readString(traces.resolve("mixed_0.properties")),
                        "the change is saved in the traces, after 0 evaluations"),
                () -> assertTrue(log.getLast().matches("\\S+  evaluations 0 +mixed_0\\.properties +PAES, 200 evaluations, .*"),
                        "and logged after the start: " + log));
    }
}

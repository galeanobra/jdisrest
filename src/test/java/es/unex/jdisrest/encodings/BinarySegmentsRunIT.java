package es.unex.jdisrest.encodings;

import es.unex.jdisrest.config.AlgorithmConfig;
import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * MOEA/D on a composite of two binary segments ({@link BitSegments}), built by
 * {@code ConfiguredMaster.createAlgorithm} from a file with the operators of each segment under
 * the names the segments take by default, {@code binary1} and {@code binary2}
 * ({@code encodings/binary-segments.properties}): jMetal's half-uniform crossover for the first and
 * its uniform crossover for the second, each with a bit-flip mutation. A composite whose segments
 * are all binary travels with {@code encoding} "binary", not "mixed": the tasks carry 18 bits with
 * {@code segmentSizes} [6, 12], {@code segmentEncodings} and {@code bitsPerVariable}. The workers
 * set the first bit of the first segment before they evaluate; the Python evaluator returns the
 * repaired bits as the floats 0.0 and 1.0, which the worker sends as 0 and 1.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class BinarySegmentsRunIT extends EncodingRunScenario<CompositeSolution> {

    private static final String FILE = "binary-segments.properties";

    /**
     * A binary variable of 6 bits in a segment, and two of 4 and 8 bits in another. The first
     * objective counts the bits set, and the second grows with the bits left unset, quadratically
     * in the first segment and linearly in the second, so the front spans the whole range.
     */
    static final class BitSegments implements Problem<CompositeSolution> {
        @Override public int numberOfVariables() { return 2; }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "BitSegments"; }

        @Override
        public CompositeSolution createSolution() {
            BinarySolution first = new DefaultBinarySolution(List.of(6), 2, 0);
            BinarySolution second = new DefaultBinarySolution(List.of(4, 8), 2, 0);
            return new CompositeSolution(List.of(first, second));
        }

        @Override
        public CompositeSolution evaluate(CompositeSolution solution) {
            int first = ones((BinarySolution) solution.variables().get(0));
            int second = ones((BinarySolution) solution.variables().get(1));
            solution.objectives()[0] = first + second;
            solution.objectives()[1] = (6 - first) * (6 - first) / 6.0 + (12 - second);
            return solution;
        }

        private static int ones(BinarySolution segment) {
            return segment.variables().stream().mapToInt(BinarySet::cardinality).sum();
        }
    }

    @Override
    Problem<CompositeSolution> problem() {
        return new BitSegments();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<CompositeSolution> algorithm(String host, int port,
                                                                  Problem<CompositeSolution> problem, String tracesFolder) {
        try {
            Path file = Path.of(BinarySegmentsRunIT.class.getResource("/encodings/" + FILE).toURI());
            return ConfiguredMaster.createAlgorithm(host, port, problem, AlgorithmConfig.load(file,
                    List.of("maxEvaluations=" + budget(), "tracesFolder=" + tracesFolder), problem));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(CompositeSolution solution) {
        ((BinarySolution) solution.variables().get(0)).variables().get(0).set(0);
    }

    @Override
    boolean isRepaired(CompositeSolution solution) {
        return ((BinarySolution) solution.variables().get(0)).variables().get(0).get(0);
    }

    @Override
    Pattern varRow() {
        // One bit string per binary variable, joined by spaces, the repaired bit first; then the
        // objectives and the constraints.
        return Pattern.compile("1[01]{5} [01]{4} [01]{8},\\[\\S+  \\S+],\\[]");
    }

    @Override
    Pattern extremeVariables() {
        // The same bit strings, each in a column of its own.
        return Pattern.compile("1[01]{5},[01]{4},[01]{8}");
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:bit_segments", "--encoding", "binary",
                "--variables", "18", "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "binary", "segmentSizes": [6, 12],
                 "segmentEncodings": ["binary", "binary"], "bitsPerVariable": [6, 4, 8],
                 "valueTypes": [["int"], ["int"]]}""";
    }
}

package es.unex.jdisrest.encodings;

import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.NMMin;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MOEA/D on jMetal's NMMin, a flat integer problem of 20 variables in [-1000, 1000], built by
 * {@code ConfiguredMaster.createAlgorithm} from {@code examples/integer.properties}: jdisrest's
 * rounding SBX crossover and polynomial mutation. The tasks travel as 20 integers without any
 * layout field, as in 1.0, and the workers set the first variable to 100, the value the first
 * objective seeks, before they evaluate; the Python worker sends its repaired variables back as
 * ints.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class IntegerRunIT extends EncodingRunScenario<IntegerSolution> {

    private static final int POPULATION_SIZE = 20;
    private static final int REPAIRED_VALUE = 100;

    @Override
    Problem<IntegerSolution> problem() {
        return new NMMin();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm(String host, int port, Problem<IntegerSolution> problem,
                                                                String tracesFolder) {
        return ConfiguredMaster.createAlgorithm(host, port, problem, example("integer.properties", problem,
                "maxEvaluations=" + budget(), "populationSize=" + POPULATION_SIZE, "tracesFolder=" + tracesFolder));
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(IntegerSolution solution) {
        solution.variables().set(0, REPAIRED_VALUE);
    }

    @Override
    boolean isRepaired(IntegerSolution solution) {
        return solution.variables().getFirst() == REPAIRED_VALUE;
    }

    @Override
    Pattern varRow() {
        // One integer per variable, joined by commas; the repaired one first.
        return Pattern.compile(REPAIRED_VALUE + "(,-?\\d+){19}");
    }

    @Override
    Pattern extremeVariables() {
        return varRow();  // a flat row is already the variables joined by commas, as integers
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:nmmin", "--encoding", "int", "--variables", "20",
                "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "int", "segmentSizes": [20], "segmentEncodings": ["int"],
                 "bitsPerVariable": [], "valueTypes": [["int"]]}""";
    }
}

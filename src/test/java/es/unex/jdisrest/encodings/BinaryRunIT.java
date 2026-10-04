package es.unex.jdisrest.encodings;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.operator.crossover.impl.SinglePointCrossover;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.binarysolution.BinarySolution;

import java.util.List;
import java.util.regex.Pattern;

/**
 * NSGA-II on jMetal's ZDT5, a flat binary problem whose variables have different lengths (one of
 * 30 bits, ten of 5), with jMetal's single-point crossover and bit-flip mutation. The tasks travel
 * as 80 bits with {@code bitsPerVariable}, and the workers set the first bit of the first variable
 * before they evaluate; the Python worker sends its repaired bits back as booleans,
 * {@code numpy.bool_} when numpy is installed.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class BinaryRunIT extends EncodingRunScenario<BinarySolution> {

    private static final int POPULATION_SIZE = 20;

    @Override
    Problem<BinarySolution> problem() {
        return new ZDT5();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<BinarySolution> algorithm(String host, int port, Problem<BinarySolution> problem,
                                                               String tracesFolder) {
        return new NSGAII<>(host, port, problem, POPULATION_SIZE, new SinglePointCrossover<>(0.9),
                new BitFlipMutation<>(1.0 / 80), new TerminationByEvaluations(budget()), tracesFolder);
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(BinarySolution solution) {
        solution.variables().get(0).set(0);
    }

    @Override
    boolean isRepaired(BinarySolution solution) {
        return solution.variables().get(0).get(0);
    }

    @Override
    Pattern varRow() {
        // One bit string per variable, joined by commas; the repaired bit first.
        return Pattern.compile("1[01]{29}(,[01]{5}){10}");
    }

    @Override
    Pattern extremeVariables() {
        return varRow();  // a flat row is already the variables joined by commas
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:zdt5", "--encoding", "binary", "--variables", "80",
                "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "binary", "segmentSizes": [80], "segmentEncodings": ["binary"],
                 "bitsPerVariable": [30, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5]}""";
    }
}

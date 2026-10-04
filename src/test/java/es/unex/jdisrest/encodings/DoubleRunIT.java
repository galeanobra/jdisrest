package es.unex.jdisrest.encodings;

import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.util.List;
import java.util.regex.Pattern;

/**
 * NSGA-II on jMetal's ZDT1, a flat problem of 30 real variables in [0, 1], built by
 * {@code ConfiguredMaster.createAlgorithm} from {@code examples/nsgaii.properties}: SBX crossover
 * and polynomial mutation. The tasks travel as 30 reals with {@code encoding} "double", the
 * payload of 1.1 and 1.2, and the workers set the last variable to 1/3000 before they evaluate;
 * the Python worker sends its repaired variables back as floats. That real has 16 significant
 * digits, so the master's solutions, its result and its traces hold it only if it crossed the
 * JSON of both workers without losing a bit, and Java writes it as {@code 3.333333333333333E-4},
 * Python as {@code 0.0003333333333333333}: the traces must hold the first form, and
 * {@code front_extremes.csv} the second, since {@code watch_front.py} saves the reals of the
 * traces as Python writes them, as it did in 1.2.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class DoubleRunIT extends EncodingRunScenario<DoubleSolution> {

    private static final int POPULATION_SIZE = 20;
    private static final double REPAIRED_VALUE = 1.0 / 3000;

    @Override
    Problem<DoubleSolution> problem() {
        return new ZDT1();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<DoubleSolution> algorithm(String host, int port, Problem<DoubleSolution> problem,
                                                               String tracesFolder) {
        return ConfiguredMaster.createAlgorithm(host, port, problem, example("nsgaii.properties", problem,
                "maxEvaluations=" + budget(), "populationSize=" + POPULATION_SIZE, "tracesFolder=" + tracesFolder));
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(DoubleSolution solution) {
        solution.variables().set(solution.variables().size() - 1, REPAIRED_VALUE);
    }

    @Override
    boolean isRepaired(DoubleSolution solution) {
        return solution.variables().getLast() == REPAIRED_VALUE;  // bit for bit
    }

    @Override
    Pattern varRow() {
        // One real per variable as Java writes it, joined by commas; the repaired one last.
        return Pattern.compile("(" + JAVA_REAL + ",){29}3\\.333333333333333E-4");
    }

    @Override
    Pattern extremeVariables() {
        // The same reals, each in a column of its own, as Python writes them.
        return Pattern.compile("(" + PYTHON_REAL + ",){29}0\\.0003333333333333333");
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:zdt1", "--encoding", "double", "--variables", "30",
                "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "double", "segmentSizes": [30], "segmentEncodings": ["double"],
                 "bitsPerVariable": [], "valueTypes": [["float"]]}""";
    }
}

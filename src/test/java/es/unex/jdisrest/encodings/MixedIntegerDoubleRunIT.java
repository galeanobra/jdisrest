package es.unex.jdisrest.encodings;

import es.unex.jdisrest.config.ConfiguredMaster;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.MixedIntegerDoubleProblem;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.List;
import java.util.regex.Pattern;

/**
 * PAES on jMetal's MixedIntegerDoubleProblem, a composite of an integer and a real segment of 10
 * variables each, in [-1000, 1000], built by {@code ConfiguredMaster.createAlgorithm} from
 * {@code examples/composite.properties}: jdisrest's random mutation for the integers and the
 * polynomial mutation for the reals, with the probability the file gives each segment. The tasks
 * travel as 10 integers and 10 reals with {@code segmentSizes} [10, 10], the mixed payload of 1.1
 * and 1.2, and the workers set the first integer to 100, the value the first objective seeks, and
 * the last real to 1/3000 before they evaluate; the Python worker sends the integers back as ints
 * and the reals as floats. As in {@link DoubleRunIT}, that real has 16 significant digits, so the
 * master's solutions, its result and its traces hold it only if it crossed the JSON of both workers
 * without losing a bit, and Java writes it as {@code 3.333333333333333E-4}, Python as
 * {@code 0.0003333333333333333}: the traces of the composite must hold the first form, and
 * {@code front_extremes.csv} the second.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class MixedIntegerDoubleRunIT extends EncodingRunScenario<CompositeSolution> {

    private static final int ARCHIVE_SIZE = 20;
    private static final int REPAIRED_INTEGER = 100;
    private static final double REPAIRED_REAL = 1.0 / 3000;

    @Override
    Problem<CompositeSolution> problem() {
        return new MixedIntegerDoubleProblem();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<CompositeSolution> algorithm(String host, int port,
                                                                  Problem<CompositeSolution> problem, String tracesFolder) {
        return ConfiguredMaster.createAlgorithm(host, port, problem, example("composite.properties", problem,
                "maxEvaluations=" + budget(), "archiveSize=" + ARCHIVE_SIZE, "tracesFolder=" + tracesFolder));
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(CompositeSolution solution) {
        List<Double> reals = ((DoubleSolution) solution.variables().get(1)).variables();
        ((IntegerSolution) solution.variables().get(0)).variables().set(0, REPAIRED_INTEGER);
        reals.set(reals.size() - 1, REPAIRED_REAL);
    }

    @Override
    boolean isRepaired(CompositeSolution solution) {
        List<Double> reals = ((DoubleSolution) solution.variables().get(1)).variables();
        return ((IntegerSolution) solution.variables().get(0)).variables().getFirst() == REPAIRED_INTEGER
                && reals.getLast() == REPAIRED_REAL;  // bit for bit
    }

    @Override
    Pattern varRow() {
        // One token per jMetal variable, joined by spaces: ten integers, the repaired one first, and
        // ten reals as Java writes them, the repaired one last; then the objectives and the constraints.
        return Pattern.compile(REPAIRED_INTEGER + "( -?\\d+){9}( " + JAVA_REAL + "){9} 3\\.333333333333333E-4,"
                + "\\[\\S+  \\S+],\\[]");
    }

    @Override
    Pattern extremeVariables() {
        // The same tokens, each in a column of its own: the integers as written, not as floats, and
        // the reals as Python writes them.
        return Pattern.compile(REPAIRED_INTEGER + "(,-?\\d+){9}(," + PYTHON_REAL + "){9},0\\.0003333333333333333");
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:mixed_integer_double", "--encoding", "mixed",
                "--variables", "20", "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "mixed", "segmentSizes": [10, 10],
                 "segmentEncodings": ["int", "double"], "bitsPerVariable": [], "valueTypes": [["int"], ["float"]]}""";
    }
}

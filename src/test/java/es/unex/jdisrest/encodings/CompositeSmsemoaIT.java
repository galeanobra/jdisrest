package es.unex.jdisrest.encodings;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.algorithms.steadystate.SMSEMOA;
import es.unex.jdisrest.operator.IntegerPolynomialMutation;
import es.unex.jdisrest.operator.IntegerSBXCrossover;
import es.unex.jdisrest.operator.SafeCompositeCrossover;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.operator.crossover.impl.SinglePointCrossover;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.operator.mutation.impl.CompositeMutation;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SMS-EMOA on a composite of an integer segment and a binary one ({@link IntegerAndBits}), with
 * per-segment operators: jdisrest's rounding integer SBX and polynomial mutation, and jMetal's
 * single-point crossover and bit-flip mutation. The tasks travel as three integers and ten bits,
 * with {@code segmentSizes} [3, 10], and the workers set the first bit of the binary segment
 * before they evaluate; the Python worker sends the integers back as ints and its repaired bits
 * as Python booleans.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class CompositeSmsemoaIT extends EncodingRunScenario<CompositeSolution> {

    private static final int POPULATION_SIZE = 20;

    /**
     * Three integers in [0, 10] and two binary variables of 4 and 6 bits. The first objective
     * grows with the integers and the bits set, the second with what the first leaves out, so
     * the front spans the whole range.
     */
    static final class IntegerAndBits implements Problem<CompositeSolution> {
        @Override public int numberOfVariables() { return 2; }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "IntegerAndBits"; }

        @Override
        public CompositeSolution createSolution() {
            IntegerSolution integers = new DefaultIntegerSolution(Collections.nCopies(3, Bounds.create(0, 10)), 2, 0);
            BinarySolution bits = new DefaultBinarySolution(List.of(4, 6), 2, 0);
            return new CompositeSolution(List.of(integers, bits));
        }

        @Override
        public CompositeSolution evaluate(CompositeSolution solution) {
            List<Integer> x = ((IntegerSolution) solution.variables().get(0)).variables();
            List<BinarySet> b = ((BinarySolution) solution.variables().get(1)).variables();
            int sum = x.stream().mapToInt(Integer::intValue).sum();
            int ones = b.stream().mapToInt(BinarySet::cardinality).sum();
            solution.objectives()[0] = sum + ones;
            solution.objectives()[1] = (30 - sum) * (30 - sum) / 30.0 + (10 - ones);
            return solution;
        }
    }

    @Override
    Problem<CompositeSolution> problem() {
        return new IntegerAndBits();
    }

    @Override
    SteadyStateEvolutionaryAlgorithm<CompositeSolution> algorithm(String host, int port,
                                                                  Problem<CompositeSolution> problem, String tracesFolder) {
        return new SMSEMOA<>(host, port, problem, POPULATION_SIZE,
                new SafeCompositeCrossover(List.of(new IntegerSBXCrossover(0.9, 20.0), new SinglePointCrossover<>(0.9))),
                new CompositeMutation(List.of(new IntegerPolynomialMutation(1.0 / 3, 20.0), new BitFlipMutation<>(1.0 / 10))),
                new TerminationByEvaluations(budget()), tracesFolder);
    }

    @Override
    int budget() {
        return 200;
    }

    @Override
    void repair(CompositeSolution solution) {
        ((BinarySolution) solution.variables().get(1)).variables().get(0).set(0);
    }

    @Override
    boolean isRepaired(CompositeSolution solution) {
        return ((BinarySolution) solution.variables().get(1)).variables().get(0).get(0);
    }

    @Override
    Pattern varRow() {
        // One token per jMetal variable, joined by spaces: three integers, then one bit string per
        // binary variable, the repaired bit first; then the objectives and the constraints.
        return Pattern.compile("(\\d+ ){3}1[01]{3} [01]{6},\\[\\S+  \\S+],\\[]");
    }

    @Override
    Pattern extremeVariables() {
        // The same tokens, each in a column of its own: the integers as written, not as floats.
        return Pattern.compile("(\\d+,){3}1[01]{3},[01]{6}");
    }

    @Override
    List<String> pythonWorkerOptions() {
        return List.of("--evaluator", "encoding_evaluators:integer_and_bits", "--encoding", "mixed",
                "--variables", "13", "--objectives", "2");
    }

    @Override
    String pythonLayout() {
        return """
                {"type": "DecisionVector", "encoding": "mixed", "segmentSizes": [3, 10],
                 "segmentEncodings": ["int", "binary"], "bitsPerVariable": [4, 6], "valueTypes": [["int"], ["int"]]}""";
    }
}

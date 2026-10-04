package es.unex.jdisrest.config;

import es.unex.jdisrest.config.SolutionLayout.Segment;
import es.unex.jdisrest.config.Variation.SegmentOperators;
import es.unex.jdisrest.operator.IntegerPolynomialMutation;
import es.unex.jdisrest.operator.IntegerSBXCrossover;
import es.unex.jdisrest.operator.SafeCompositeCrossover;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.junit.jupiter.api.Test;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.SBXCrossover;
import org.uma.jmetal.operator.crossover.impl.SinglePointCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.operator.mutation.impl.CompositeMutation;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.util.List;
import java.util.Map;

import static es.unex.jdisrest.config.AlgorithmConfigTest.properties;
import static es.unex.jdisrest.config.TestProblems.mixed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The operators of a configuration: those of the single segment of a flat problem of each
 * encoding, used as they are, and the composite operators of a composite problem, which keep every
 * segment within its bounds and its bit lengths and never change the parents; PAES without a
 * crossover; and the rules of the record.
 */
class VariationTest {

    private static final int RUNS = 200;

    static Variation variation(SolutionLayout layout, String... entries) {
        return AlgorithmConfigParser.parse(properties(entries), layout).variation();
    }

    /** The layout of {@link TestProblems#mixed()}: ints (3), reals (2) and bits (5 + 3). */
    static SolutionLayout mixedLayout() {
        return SolutionLayout.of(mixed());
    }

    static boolean withinBounds(CompositeSolution solution) {
        IntegerSolution ints = (IntegerSolution) solution.variables().get(0);
        DoubleSolution reals = (DoubleSolution) solution.variables().get(1);
        BinarySolution bits = (BinarySolution) solution.variables().get(2);
        return ints.variables().size() == 3 && ints.variables().stream().allMatch(v -> v >= 0 && v <= 10)
                && reals.variables().size() == 2 && reals.variables().stream().allMatch(v -> v >= -1.0 && v <= 1.0)
                && bits.variables().stream().map(BinarySet::getBinarySetLength).toList().equals(List.of(5, 3));
    }

    // ── Problems that are not composite ───────────────────────────────────────

    @Test
    void aRealCodedProblemGetsTheOperatorsOfItsSegmentAsIn12() {
        Variation variation = variation(SolutionLayout.real(12), "algorithm=nsgaii", "maxEvaluations=100");

        CrossoverOperator<DoubleSolution> crossover = variation.createCrossover();
        MutationOperator<DoubleSolution> mutation = variation.createMutation();

        assertAll(
                () -> assertInstanceOf(SBXCrossover.class, crossover, "SBX"),
                () -> assertInstanceOf(PolynomialMutation.class, mutation, "polynomial mutation"),
                () -> assertFalse(variation.composite(), "not composite"),
                () -> assertEquals("crossover sbx (probability 0.9, distributionIndex 20), mutation polynomial "
                        + "(probability 0.08333, distributionIndex 20)", variation.describe(), "as 1.2 described them"));
    }

    @Test
    void anIntegerProblemGetsTheRoundingOperatorsOfJdisrest() {
        Variation variation = variation(SolutionLayout.of(TestProblems.flatIntegers()), "algorithm=nsgaii",
                "maxEvaluations=100");

        CrossoverOperator<IntegerSolution> crossover = variation.createCrossover();
        MutationOperator<IntegerSolution> mutation = variation.createMutation();

        assertAll(
                () -> assertInstanceOf(IntegerSBXCrossover.class, crossover, "jdisrest's SBX, which rounds"),
                () -> assertInstanceOf(IntegerPolynomialMutation.class, mutation, "jdisrest's polynomial, which rounds"),
                () -> assertEquals(0.25, mutation.mutationProbability(), "1/n over 4 variables"));
    }

    @Test
    void aBinaryProblemGetsJMetalsBinaryOperators() {
        Variation variation = variation(new SolutionLayout(List.of(new Segment(null, Encoding.BINARY, 40))),
                "algorithm=nsgaii", "maxEvaluations=100");

        CrossoverOperator<BinarySolution> crossover = variation.createCrossover();
        MutationOperator<BinarySolution> mutation = variation.createMutation();

        assertAll(
                () -> assertInstanceOf(SinglePointCrossover.class, crossover, "single-point crossover"),
                () -> assertInstanceOf(BitFlipMutation.class, mutation, "bit-flip mutation"),
                () -> assertEquals(0.025, mutation.mutationProbability(), "1/n over 40 bits"));
    }

    // ── Composite problems ────────────────────────────────────────────────────

    @Test
    void aCompositeGetsCompositeOperatorsOfTheOperatorsOfEachSegment() {
        Variation variation = variation(mixedLayout(), "algorithm=nsgaii", "maxEvaluations=100");

        var crossover = assertInstanceOf(SafeCompositeCrossover.class, variation.<CompositeSolution>createCrossover(),
                "jdisrest's safe composite crossover");
        var mutation = assertInstanceOf(CompositeMutation.class, variation.<CompositeSolution>createMutation(),
                "jMetal's composite mutation");

        assertAll(
                () -> assertEquals(List.of(IntegerSBXCrossover.class, SBXCrossover.class, SinglePointCrossover.class),
                        crossover.getOperators().stream().map(Object::getClass).toList(),
                        "the default crossover of each encoding, in the order of the segments"),
                () -> assertEquals(List.of(IntegerPolynomialMutation.class, PolynomialMutation.class, BitFlipMutation.class),
                        mutation.getOperators().stream().map(Object::getClass).toList(),
                        "the default mutation of each encoding, in the order of the segments"),
                () -> assertEquals(List.of(1.0 / 3, 0.5, 0.125),
                        mutation.getOperators().stream().map(MutationOperator::mutationProbability).toList(),
                        "1/n over the size of each segment: 3 integers, 2 reals, 8 bits"),
                () -> assertTrue(variation.composite(), "composite"));
    }

    @Test
    void compositeOperatorsKeepEverySegmentWithinItsBoundsAndNeverChangeTheParents() {
        Variation variation = variation(mixedLayout(), "algorithm=nsgaii", "maxEvaluations=100",
                "ints.crossover=blxAlpha", "ints.crossover.probability=1", "ints.mutation=random",
                "ints.mutation.probability=1", "reals.crossover.probability=1", "reals.mutation.probability=1",
                "bits.crossover=uniform", "bits.crossover.probability=1", "bits.mutation.probability=1");
        CrossoverOperator<CompositeSolution> crossover = variation.createCrossover();
        MutationOperator<CompositeSolution> mutation = variation.createMutation();
        List<CompositeSolution> parents = List.of(mixed().createSolution(), mixed().createSolution());

        for (int run = 0; run < RUNS; run++) {
            List<List<Number>> before = parents.stream().map(SolutionVariables::flatten).toList();
            List<CompositeSolution> children = crossover.execute(parents).stream().map(mutation::execute).toList();

            List<CompositeSolution> crossed = parents;
            assertAll(
                    () -> assertEquals(2, children.size(), "two children"),
                    () -> assertTrue(children.stream().allMatch(VariationTest::withinBounds),
                            () -> "a child left its bounds or its bit lengths: "
                                    + children.stream().map(SolutionVariables::flatten).toList()),
                    () -> assertEquals(before, crossed.stream().map(SolutionVariables::flatten).toList(),
                            "the parents do not change"));
            parents = children;
        }
    }

    @Test
    void paesHasNoCrossover() {
        Variation variation = variation(mixedLayout(), "algorithm=paes", "maxEvaluations=100");

        var exception = assertThrows(IllegalStateException.class, variation::createCrossover);

        assertAll(
                () -> assertEquals("the configuration has no crossover", exception.getMessage(), "PAES does not cross"),
                () -> assertInstanceOf(CompositeMutation.class, variation.createMutation(), "but it mutates"),
                () -> assertTrue(variation.segments().stream().allMatch(operators -> operators.crossover() == null),
                        "no segment has a crossover"));
    }

    @Test
    void aCompositeHasNoSingleSegment() {
        Variation variation = variation(mixedLayout(), "algorithm=nsgaii", "maxEvaluations=100");

        var exception = assertThrows(IllegalStateException.class, variation::single);

        assertTrue(exception.getMessage().startsWith("a composite problem has the operators of each segment: ints ["),
                "the message lists the segments: " + exception.getMessage());
    }

    @Test
    void everyCallBuildsNewOperators() {
        Variation variation = variation(mixedLayout(), "algorithm=nsgaii", "maxEvaluations=100");

        var first = (SafeCompositeCrossover) variation.<CompositeSolution>createCrossover();
        var second = (SafeCompositeCrossover) variation.<CompositeSolution>createCrossover();
        var firstMutation = (CompositeMutation) variation.<CompositeSolution>createMutation();
        var secondMutation = (CompositeMutation) variation.<CompositeSolution>createMutation();

        assertAll(
                () -> assertNotSame(first.getOperators().getFirst(), second.getOperators().getFirst(),
                        "a run and a later change never share a crossover"),
                () -> assertNotSame(firstMutation.getOperators().getLast(), secondMutation.getOperators().getLast(),
                        "nor a mutation"));
    }

    // ── The record ────────────────────────────────────────────────────────────

    @Test
    void theLayoutOfAVariationIsTheOneItWasReadFor() {
        SolutionLayout layout = mixedLayout();

        assertEquals(layout, variation(layout, "algorithm=paes", "maxEvaluations=100").layout(), "same segments");
    }

    @Test
    void aVariationNeedsSegmentsThatFormALayout() {
        var mutation = new OperatorConfig<>(IntegerMutationType.RANDOM, 0.1, Map.of());
        Segment ints = new Segment("ints", Encoding.INT, 3);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Variation(List.of()), "no segment"),
                () -> assertThrows(IllegalArgumentException.class, () -> new Variation(List.of(
                        new SegmentOperators(ints, null, mutation), new SegmentOperators(ints, null, mutation))),
                        "a repeated name"),
                () -> assertThrows(NullPointerException.class, () -> new SegmentOperators(ints, null, null),
                        "a segment without mutation"));
    }
}

package es.unex.jdisrest.config;

import es.unex.jdisrest.operator.DoubleNPointCrossover;
import es.unex.jdisrest.operator.LevyFlightMutationRandomStepSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.ArithmeticCrossover;
import org.uma.jmetal.operator.crossover.impl.BLXAlphaCrossover;
import org.uma.jmetal.operator.crossover.impl.LaplaceCrossover;
import org.uma.jmetal.operator.crossover.impl.SBXCrossover;
import org.uma.jmetal.operator.crossover.impl.WholeArithmeticCrossover;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.LevyFlightMutation;
import org.uma.jmetal.operator.mutation.impl.LinkedPolynomialMutation;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.operator.mutation.impl.SimpleRandomMutation;
import org.uma.jmetal.operator.mutation.impl.UniformMutation;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operator catalogues of the configuration files: every type passes its values to the jMetal or
 * jdisrest operator, the checks enforce the rules of each operator and accept only values its
 * constructor accepts, every crossover takes two parents and gives two children, and every operator
 * keeps the variables within their bounds, a variable with equal bounds included.
 */
class OperatorTypeTest {

    private static final int VARIABLES = 30;
    private static final double LOWER = -5.0;
    private static final double UPPER = 5.0;
    private static final double FIXED = 1.0;
    private static final int RUNS = 200;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static Map<String, Double> defaults(OperatorType<?> type) {
        return type.parameters().stream()
                .collect(Collectors.toMap(OperatorType.Parameter::name, OperatorType.Parameter::defaultValue));
    }

    /** {@link #VARIABLES} variables in [LOWER, UPPER], except the first, whose bounds are both FIXED. */
    static DoubleSolution solutionWithAFixedVariable() {
        List<Bounds<Double>> bounds = new ArrayList<>(Collections.nCopies(VARIABLES, Bounds.create(LOWER, UPPER)));
        bounds.set(0, Bounds.create(FIXED, FIXED));
        return new DefaultDoubleSolution(bounds, 2, 0);
    }

    static boolean withinBounds(DoubleSolution solution) {
        return solution.variables().getFirst() == FIXED
                && solution.variables().stream().allMatch(value -> value >= LOWER && value <= UPPER);
    }

    static Stream<OperatorType<?>> everyType() {
        return Stream.concat(Stream.of(CrossoverType.values()), Stream.of(MutationType.values()));
    }

    /** Every assignment of {@code candidates} to the parameters of {@code type}. */
    static List<Map<String, Double>> assignments(OperatorType<?> type, double[] candidates) {
        List<Map<String, Double>> assignments = new ArrayList<>(List.of(Map.of()));
        for (OperatorType.Parameter parameter : type.parameters()) {
            List<Map<String, Double>> extended = new ArrayList<>();
            for (Map<String, Double> assignment : assignments) {
                for (double candidate : candidates) {
                    Map<String, Double> next = new LinkedHashMap<>(assignment);
                    next.put(parameter.name(), candidate);
                    extended.add(next);
                }
            }
            assignments = extended;
        }
        return assignments;
    }

    // ── Crossovers ────────────────────────────────────────────────────────────

    @Test
    void sbxReceivesTheProbabilityAndTheDistributionIndex() {
        var crossover = assertInstanceOf(SBXCrossover.class,
                CrossoverType.SBX.create(0.7, Map.of("distributionIndex", 15.0)));

        assertAll(
                () -> assertEquals(0.7, crossover.crossoverProbability(), "probability"),
                () -> assertEquals(15.0, crossover.distributionIndex(), "distributionIndex"));
    }

    @Test
    void blxAlphaReceivesTheProbabilityAndAlpha() {
        var crossover = assertInstanceOf(BLXAlphaCrossover.class,
                CrossoverType.BLX_ALPHA.create(0.8, Map.of("alpha", 0.3)));

        assertAll(
                () -> assertEquals(0.8, crossover.crossoverProbability(), "probability"),
                () -> assertEquals(0.3, crossover.alpha(), "alpha"));
    }

    @Test
    void laplaceReceivesTheProbabilityAndTheScale() {
        var crossover = assertInstanceOf(LaplaceCrossover.class,
                CrossoverType.LAPLACE.create(0.6, Map.of("scale", 0.4)));

        assertAll(
                () -> assertEquals(0.6, crossover.crossoverProbability(), "probability"),
                () -> assertEquals(0.4, crossover.getScale(), "scale"));
    }

    @Test
    void arithmeticReceivesTheProbability() {
        var crossover = assertInstanceOf(ArithmeticCrossover.class, CrossoverType.ARITHMETIC.create(0.5, Map.of()));

        assertEquals(0.5, crossover.crossoverProbability(), "probability");
    }

    @Test
    void wholeArithmeticReceivesTheProbability() {
        var crossover = assertInstanceOf(WholeArithmeticCrossover.class,
                CrossoverType.WHOLE_ARITHMETIC.create(0.4, Map.of()));

        assertEquals(0.4, crossover.crossoverProbability(), "probability");
    }

    @Test
    void nPointReceivesTheProbabilityThePointsAndTheBlockSize() {
        var crossover = assertInstanceOf(DoubleNPointCrossover.class,
                CrossoverType.N_POINT.create(0.85, Map.of("points", 3.0, "blockSize", 3.0)));

        assertAll(
                () -> assertEquals(0.85, crossover.crossoverProbability(), "probability"),
                () -> assertEquals(3, crossover.numberOfPoints(), "points"),
                () -> assertEquals(3, crossover.blockSize(), "blockSize"));
    }

    @ParameterizedTest
    @EnumSource(CrossoverType.class)
    void everyCrossoverTakesTwoParentsAndGivesAtLeastTwoChildren(CrossoverType type) {
        CrossoverOperator<DoubleSolution> crossover = type.create(0.9, defaults(type));

        // The steady-state algorithms select two parents and use two children.
        assertAll(
                () -> assertEquals(2, crossover.numberOfRequiredParents(), type + " takes two parents"),
                () -> assertTrue(crossover.numberOfGeneratedChildren() >= 2, type + " gives at least two children"));
    }

    @ParameterizedTest
    @EnumSource(CrossoverType.class)
    void everyCrossoverKeepsTheChildrenWithinTheBoundsAndAFixedVariableAtItsValue(CrossoverType type) {
        CrossoverOperator<DoubleSolution> crossover = type.create(1.0, defaults(type));
        List<DoubleSolution> parents = List.of(solutionWithAFixedVariable(), solutionWithAFixedVariable());

        for (int run = 0; run < RUNS; run++) {
            List<DoubleSolution> children = crossover.execute(parents);

            assertTrue(children.stream().allMatch(OperatorTypeTest::withinBounds),
                    () -> type + " left the bounds: " + children.stream().map(DoubleSolution::variables).toList());
            parents = children.subList(0, 2);
        }
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Test
    void polynomialReceivesTheProbabilityAndTheDistributionIndex() {
        var mutation = assertInstanceOf(PolynomialMutation.class,
                MutationType.POLYNOMIAL.create(0.1, Map.of("distributionIndex", 30.0)));

        assertAll(
                () -> assertEquals(0.1, mutation.mutationProbability(), "probability"),
                () -> assertEquals(30.0, mutation.getDistributionIndex(), "distributionIndex"));
    }

    @Test
    void linkedPolynomialReceivesTheProbabilityAndTheDistributionIndex() {
        var mutation = assertInstanceOf(LinkedPolynomialMutation.class,
                MutationType.LINKED_POLYNOMIAL.create(0.2, Map.of("distributionIndex", 10.0)));

        assertAll(
                () -> assertEquals(0.2, mutation.mutationProbability(), "probability"),
                () -> assertEquals(10.0, mutation.getDistributionIndex(), "distributionIndex"));
    }

    @Test
    void uniformReceivesTheProbabilityAndThePerturbation() {
        var mutation = assertInstanceOf(UniformMutation.class,
                MutationType.UNIFORM.create(0.3, Map.of("perturbation", 0.25)));

        assertAll(
                () -> assertEquals(0.3, mutation.mutationProbability(), "probability"),
                () -> assertEquals(0.25, mutation.getPerturbation(), "perturbation"));
    }

    @Test
    void randomReceivesTheProbability() {
        var mutation = assertInstanceOf(SimpleRandomMutation.class, MutationType.RANDOM.create(0.05, Map.of()));

        assertEquals(0.05, mutation.mutationProbability(), "probability");
    }

    @Test
    void levyFlightReceivesTheProbabilityBetaAndTheStepSize() {
        var mutation = assertInstanceOf(LevyFlightMutation.class,
                MutationType.LEVY_FLIGHT.create(0.1, Map.of("beta", 1.9, "stepSize", 0.26)));

        assertAll(
                () -> assertEquals(0.1, mutation.mutationProbability(), "probability"),
                () -> assertEquals(1.9, mutation.beta(), "beta"),
                () -> assertEquals(0.26, mutation.stepSize(), "stepSize"));
    }

    @Test
    void levyRandomReceivesTheProbabilityBetaAndTheMaximumStepSize() {
        var mutation = assertInstanceOf(LevyFlightMutationRandomStepSize.class,
                MutationType.LEVY_RANDOM.create(0.1, Map.of("beta", 1.25, "stepSize", 0.5)));

        assertAll(
                () -> assertEquals(0.1, mutation.mutationProbability(), "probability"),
                () -> assertEquals(1.25, mutation.beta(), "beta"),
                () -> assertEquals(0.5, mutation.maximumStepSize(), "stepSize is the maximum step size"));
    }

    @ParameterizedTest
    @EnumSource(MutationType.class)
    void everyMutationKeepsTheVariablesWithinTheBoundsAndAFixedVariableAtItsValue(MutationType type) {
        MutationOperator<DoubleSolution> mutation = type.create(1.0, defaults(type));
        DoubleSolution solution = solutionWithAFixedVariable();

        for (int run = 0; run < RUNS; run++) {
            mutation.execute(solution);

            assertTrue(withinBounds(solution), () -> type + " left the bounds: " + solution.variables());
        }
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("everyType")
    void everyValueTheCheckAcceptsCanBeBuilt(OperatorType<?> type) {
        double[] candidates = {0.0, Double.MIN_VALUE, 0.5, 1.0, 1.5, 1.99999, 2.0, 3.0, 1e6};

        for (Map<String, Double> parameters : assignments(type, candidates)) {
            if (type.check(parameters, VARIABLES) == null) {
                for (double probability : new double[] {0.0, 1.0}) {
                    assertDoesNotThrow(() -> type.create(probability, parameters),
                            type.key() + " accepts " + parameters + " in its check, so its constructor must too");
                }
            }
        }
    }

    @Test
    void laplaceRequiresAPositiveScale() {
        assertAll(
                () -> assertEquals("scale must be greater than 0, got '0'",
                        CrossoverType.LAPLACE.check(Map.of("scale", 0.0), VARIABLES), "jMetal rejects a scale of 0"),
                () -> assertNull(CrossoverType.LAPLACE.check(Map.of("scale", 0.01), VARIABLES), "a small scale is fine"));
    }

    @Test
    void uniformRequiresAPositivePerturbation() {
        assertAll(
                () -> assertEquals("perturbation must be greater than 0, got '0'",
                        MutationType.UNIFORM.check(Map.of("perturbation", 0.0), VARIABLES), "0 never changes a variable"),
                () -> assertNull(MutationType.UNIFORM.check(Map.of("perturbation", 0.01), VARIABLES),
                        "a small perturbation is fine"));
    }

    @ParameterizedTest
    @EnumSource(names = {"LEVY_FLIGHT", "LEVY_RANDOM"})
    void bothLevyMutationsRequireBetaBetweenOneAndTwoAndAPositiveStepSize(MutationType type) {
        assertAll(
                () -> assertEquals("beta must be in (1, 2), got '1'",
                        type.check(Map.of("beta", 1.0, "stepSize", 0.01), VARIABLES), "beta 1 is excluded"),
                () -> assertEquals("beta must be in (1, 2), got '2'",
                        type.check(Map.of("beta", 2.0, "stepSize", 0.01), VARIABLES), "beta 2 vanishes the steps"),
                () -> assertEquals("stepSize must be greater than 0, got '0'",
                        type.check(Map.of("beta", 1.5, "stepSize", 0.0), VARIABLES), "a step size of 0 never moves"),
                () -> assertNull(type.check(Map.of("beta", 1.99999, "stepSize", 1e-6), VARIABLES),
                        "values inside the ranges are fine"));
    }

    @Test
    void nPointRequiresIntegerPointsAndBlockSize() {
        assertAll(
                () -> assertEquals("points must be an integer, got '2.5'",
                        CrossoverType.N_POINT.check(Map.of("points", 2.5, "blockSize", 1.0), VARIABLES), "points"),
                () -> assertEquals("blockSize must be an integer, got '3.00001'",
                        CrossoverType.N_POINT.check(Map.of("points", 2.0, "blockSize", 3.00001), VARIABLES),
                        "blockSize, shown as written"),
                () -> assertEquals("points must be an integer, got '10000000000'",
                        CrossoverType.N_POINT.check(Map.of("points", 1e10, "blockSize", 1.0), VARIABLES),
                        "an integer beyond the int range"));
    }

    @Test
    void nPointReportsTheSizeRulesOfTheCrossoverUnderTheParameterAtFault() {
        assertAll(
                () -> assertEquals("blockSize: " + DoubleNPointCrossover.check(VARIABLES, 1, 7),
                        CrossoverType.N_POINT.check(Map.of("points", 2.0, "blockSize", 7.0), VARIABLES),
                        "a block size that does not divide the variables"),
                () -> assertEquals("points: " + DoubleNPointCrossover.check(VARIABLES, 10, 3),
                        CrossoverType.N_POINT.check(Map.of("points", 10.0, "blockSize", 3.0), VARIABLES),
                        "as many points as blocks"),
                () -> assertEquals("points: " + DoubleNPointCrossover.check(VARIABLES, 0, 3),
                        CrossoverType.N_POINT.check(Map.of("points", 0.0, "blockSize", 3.0), VARIABLES),
                        "no points, with a valid block size"),
                () -> assertNull(CrossoverType.N_POINT.check(Map.of("points", 9.0, "blockSize", 3.0), VARIABLES),
                        "9 points between 10 blocks"));
    }

    @Test
    void operatorsWithoutRulesAcceptAnyNonNegativeValue() {
        assertAll(
                () -> assertNull(CrossoverType.SBX.check(Map.of("distributionIndex", 0.0), VARIABLES), "sbx"),
                () -> assertNull(CrossoverType.BLX_ALPHA.check(Map.of("alpha", 0.0), VARIABLES), "blxAlpha"),
                () -> assertNull(MutationType.POLYNOMIAL.check(Map.of("distributionIndex", 0.0), VARIABLES), "polynomial"),
                () -> assertNull(MutationType.RANDOM.check(Map.of(), VARIABLES), "random"));
    }

    // ── Repair ────────────────────────────────────────────────────────────────

    @Test
    void theRepairClampsAsJMetalDoesAndKeepsAFixedVariable() {
        BoundRepair repair = BoundRepair.INSTANCE;

        assertAll(
                () -> assertEquals(UPPER, repair.repairSolutionVariableValue(7.0, LOWER, UPPER), "above: the upper bound"),
                () -> assertEquals(LOWER, repair.repairSolutionVariableValue(-7.0, LOWER, UPPER), "below: the lower bound"),
                () -> assertEquals(3.0, repair.repairSolutionVariableValue(3.0, LOWER, UPPER), "inside: unchanged"),
                () -> assertEquals(FIXED, repair.repairSolutionVariableValue(9.0, FIXED, FIXED), "equal bounds: the bound"),
                () -> assertThrows(RuntimeException.class, () -> repair.repairSolutionVariableValue(0.0, UPPER, LOWER),
                        "a lower bound above the upper one is still rejected"));
    }
}

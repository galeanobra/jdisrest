package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;
import org.uma.jmetal.util.pseudorandom.BoundedRandomGenerator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cuts, copies and argument rules of {@link DoubleNPointCrossover}.
 *
 * <p>Variable {@code i} of the first parent holds {@code i} and of the second
 * {@code 100 + i}, so the parent every child variable came from, and therefore
 * every cut, can be read back from the child. Random cuts come from injected
 * seeded generators; the one test that crosses with the default generators
 * swaps a seeded generator into {@code JMetalRandom} and puts the previous one
 * back.
 */
class DoubleNPointCrossoverTest {

    /** Offset of the second parent's values: variable {@code i} holds {@code 100 + i}. */
    static final double SECOND_PARENT_OFFSET = 100.0;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution of {@code size} variables in [0, 200] whose variable {@code i} is {@code offset + i}. */
    static DoubleSolution solution(int size, double offset) {
        List<Bounds<Double>> bounds = Collections.nCopies(size, Bounds.create(0.0, 200.0));
        DoubleSolution solution = new DefaultDoubleSolution(bounds, 2, 0);
        for (int i = 0; i < size; i++) solution.variables().set(i, offset + i);
        return solution;
    }

    /** The two parents of {@code size} variables: values {@code i} and {@code 100 + i}. */
    static List<DoubleSolution> parents(int size) {
        return List.of(solution(size, 0.0), solution(size, SECOND_PARENT_OFFSET));
    }

    /** Point generator that returns the given indices, in order, as the choices of the partial shuffle. */
    static BoundedRandomGenerator<Integer> choices(Integer... indices) {
        Deque<Integer> queue = new ArrayDeque<>(List.of(indices));
        return (lower, upper) -> queue.removeFirst();
    }

    /** A crossover whose two generators draw from one {@link Random} with the given seed. */
    static DoubleNPointCrossover seeded(double probability, int points, int blockSize, long seed) {
        Random random = new Random(seed);
        return new DoubleNPointCrossover(probability, points, blockSize, random::nextDouble,
            (lower, upper) -> lower + random.nextInt(upper - lower + 1));
    }

    static List<Double> values(DoubleSolution solution) {
        return List.copyOf(solution.variables());
    }

    /** Positions {@code p} where the child takes variable {@code p} from another parent than {@code p - 1}. */
    static List<Integer> cutsOf(DoubleSolution child) {
        List<Integer> cuts = new ArrayList<>();
        for (int i = 1; i < child.variables().size(); i++) {
            boolean fromSecond = child.variables().get(i) >= SECOND_PARENT_OFFSET;
            boolean previousFromSecond = child.variables().get(i - 1) >= SECOND_PARENT_OFFSET;
            if (fromSecond != previousFromSecond) cuts.add(i);
        }
        return cuts;
    }

    // ── Crossing ──────────────────────────────────────────────────────────────

    @Test
    void cutsAtTwoAndFiveSwapTheMiddleSegment() {
        // The candidate cuts are [1..7]: index 1 is cut 2, then index 4 (after the swap) is cut 5.
        var crossover = new DoubleNPointCrossover(1.0, 2, () -> 0.0, choices(1, 4));

        List<DoubleSolution> children = crossover.execute(parents(8));

        assertEquals(List.of(0.0, 1.0, 102.0, 103.0, 104.0, 5.0, 6.0, 7.0), values(children.get(0)));
        assertEquals(List.of(100.0, 101.0, 2.0, 3.0, 4.0, 105.0, 106.0, 107.0), values(children.get(1)));
    }

    @Test
    void oneCutAtSixSwapsTheTail() {
        var crossover = new DoubleNPointCrossover(1.0, 1, () -> 0.0, choices(5));

        List<DoubleSolution> children = crossover.execute(parents(8));

        assertEquals(List.of(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 106.0, 107.0), values(children.get(0)));
        assertEquals(List.of(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 6.0, 7.0), values(children.get(1)));
    }

    @Test
    void bypassReturnsCopiesNotTheParents() {
        // 0.5 is not below the probability 0.3, so the crossover is not applied.
        var crossover = new DoubleNPointCrossover(0.3, 2, () -> 0.5, choices());
        List<DoubleSolution> parents = parents(8);

        List<DoubleSolution> children = crossover.execute(parents);

        assertEquals(values(parents.get(0)), values(children.get(0)), "the first child copies the first parent");
        assertEquals(values(parents.get(1)), values(children.get(1)), "the second child copies the second parent");
        assertNotSame(parents.get(0), children.get(0), "a bypassed child must not be the parent itself");
        assertNotSame(parents.get(1), children.get(1), "a bypassed child must not be the parent itself");
        assertNotSame(parents, children, "the children list must not be the parents list");
    }

    @Test
    void mutatingABypassedChildLeavesItsParentUntouched() {
        var crossover = new DoubleNPointCrossover(0.0, 2, () -> 0.5, choices());
        List<DoubleSolution> parents = parents(8);

        List<DoubleSolution> children = crossover.execute(parents);
        children.get(0).variables().set(3, 199.0);
        children.get(1).variables().set(3, 199.0);

        assertEquals(values(solution(8, 0.0)), values(parents.get(0)), "the child must not alias the parent");
        assertEquals(values(solution(8, SECOND_PARENT_OFFSET)), values(parents.get(1)),
            "the child must not alias the parent");
    }

    @Test
    void appliedCrossoverLeavesTheParentsUntouched() {
        var crossover = new DoubleNPointCrossover(1.0, 2, () -> 0.0, choices(1, 4));
        List<DoubleSolution> parents = parents(8);

        crossover.execute(parents);

        assertEquals(values(solution(8, 0.0)), values(parents.get(0)));
        assertEquals(values(solution(8, SECOND_PARENT_OFFSET)), values(parents.get(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 29})
    void everyCrossoverMakesExactlyTheRequestedNumberOfCuts(int points) {
        var crossover = seeded(1.0, points, 1, 42);
        List<DoubleSolution> parents = parents(30);

        for (int run = 0; run < 500; run++) {
            List<DoubleSolution> children = crossover.execute(parents);
            DoubleSolution first = children.get(0);
            DoubleSolution second = children.get(1);

            assertEquals(points, cutsOf(first).size(), "every crossover makes exactly " + points + " cuts");
            assertEquals(0.0, first.variables().get(0), "the first child starts with the first parent");
            for (int i = 0; i < 30; i++) {
                assertEquals(SECOND_PARENT_OFFSET, Math.abs(second.variables().get(i) - first.variables().get(i)),
                    "the second child is the complement of the first at variable " + i);
            }
        }
    }

    @Test
    void everySetOfCutsIsEquallyLikely() {
        // 6 variables, 2 points: the 10 pairs of cuts among [1..5] are expected 2000 times each.
        var crossover = seeded(1.0, 2, 1, 7);
        List<DoubleSolution> parents = parents(6);
        Map<List<Integer>, Integer> counts = new HashMap<>();

        for (int run = 0; run < 20_000; run++) {
            counts.merge(cutsOf(crossover.execute(parents).get(0)), 1, Integer::sum);
        }

        assertEquals(10, counts.size(), "every pair of distinct cuts occurs: " + counts);
        counts.forEach((cuts, count) -> assertTrue(Math.abs(count - 2000) < 200,
            "cuts " + cuts + " occurred " + count + " times out of 20000, expected about 2000"));
    }

    @Test
    void blockSizeOneGivesTheSameChildrenAsTheConstructorWithoutBlockSize() {
        Random withoutBlocks = new Random(2024);
        Random withBlockSizeOne = new Random(2024);
        var plain = new DoubleNPointCrossover(0.7, 3, withoutBlocks::nextDouble,
            (lower, upper) -> lower + withoutBlocks.nextInt(upper - lower + 1));
        var blocks = new DoubleNPointCrossover(0.7, 3, 1, withBlockSizeOne::nextDouble,
            (lower, upper) -> lower + withBlockSizeOne.nextInt(upper - lower + 1));
        List<DoubleSolution> parents = parents(12);

        for (int run = 0; run < 200; run++) {
            List<DoubleSolution> expected = plain.execute(parents);
            List<DoubleSolution> actual = blocks.execute(parents);

            assertEquals(values(expected.get(0)), values(actual.get(0)),
                "block size 1 must consume the random stream as the plain crossover (run " + run + ")");
            assertEquals(values(expected.get(1)), values(actual.get(1)),
                "block size 1 must consume the random stream as the plain crossover (run " + run + ")");
        }
    }

    @Test
    void defaultConstructorsDrawFromJMetalRandom() {
        // A seeded JavaRandomGenerator draws as seeded(..): nextDouble() and lower + nextInt(upper - lower + 1).
        // The global generator is swapped in and put back, so no other test sees a different stream.
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        List<DoubleSolution> parents = parents(12);
        try {
            var reference = seeded(0.8, 2, 1, 5);
            var twoArguments = new DoubleNPointCrossover(0.8, 2);
            global.setRandomGenerator(new JavaRandomGenerator(5));
            List<List<Double>> expected = new ArrayList<>();
            List<List<Double>> fromTwoArguments = new ArrayList<>();
            for (int run = 0; run < 100; run++) {
                expected.add(values(reference.execute(parents).get(0)));
                fromTwoArguments.add(values(twoArguments.execute(parents).get(0)));
            }

            var threeArguments = new DoubleNPointCrossover(0.8, 2, 1);
            global.setRandomGenerator(new JavaRandomGenerator(5));
            List<List<Double>> fromThreeArguments = new ArrayList<>();
            for (int run = 0; run < 100; run++) {
                fromThreeArguments.add(values(threeArguments.execute(parents).get(0)));
            }

            assertEquals(expected, fromTwoArguments, "the 2-argument constructor draws from JMetalRandom");
            assertEquals(fromTwoArguments, fromThreeArguments,
                "block size 1 gives the same cuts as the 2-argument constructor for the same sequence");
        } finally {
            global.setRandomGenerator(previous);
        }
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    @Test
    void lastBlockIsSwappedWhole() {
        // 9 variables in blocks of 3: the candidate cuts are [3, 6]; index 1 is cut 6.
        var crossover = new DoubleNPointCrossover(1.0, 1, 3, () -> 0.0, choices(1));

        List<DoubleSolution> children = crossover.execute(parents(9));

        assertEquals(List.of(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 106.0, 107.0, 108.0), values(children.get(0)));
        assertEquals(List.of(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 6.0, 7.0, 8.0), values(children.get(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 9})
    void cutsFallOnlyBetweenBlocks(int points) {
        // 30 variables in 10 blocks of 3.
        var crossover = seeded(1.0, points, 3, 42);
        List<DoubleSolution> parents = parents(30);

        for (int run = 0; run < 500; run++) {
            List<Integer> cuts = cutsOf(crossover.execute(parents).get(0));

            assertEquals(points, cuts.size(), "every crossover makes exactly " + points + " cuts");
            for (int cut : cuts) {
                assertEquals(0, cut % 3, "a cut separated variables " + (cut - 1) + " and " + cut + " of a block");
            }
        }
    }

    @Test
    void asManyPointsAsBoundariesAlternatesEveryBlock() {
        // 9 variables in 3 blocks, 2 points: both boundaries are cut whatever the generator draws.
        var crossover = seeded(1.0, 2, 3, 3);

        List<DoubleSolution> children = crossover.execute(parents(9));

        assertEquals(List.of(0.0, 1.0, 2.0, 103.0, 104.0, 105.0, 6.0, 7.0, 8.0), values(children.get(0)));
        assertEquals(List.of(100.0, 101.0, 102.0, 3.0, 4.0, 5.0, 106.0, 107.0, 108.0), values(children.get(1)));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Test
    void accessorsReturnTheConstructorArguments() {
        var blocks = new DoubleNPointCrossover(0.85, 3, 4);
        var plain = new DoubleNPointCrossover(0.6, 2);

        assertEquals(0.85, blocks.crossoverProbability());
        assertEquals(3, blocks.numberOfPoints());
        assertEquals(4, blocks.blockSize());
        assertEquals(1, plain.blockSize(), "the constructors without a block size use 1");
        assertEquals(2, plain.numberOfRequiredParents());
        assertEquals(2, plain.numberOfGeneratedChildren());
    }

    // ── check ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"8, 7, 1", "2, 1, 1", "9, 2, 3", "30, 1, 3", "6, 1, 3"})
    void checkAcceptsEveryValidCombination(int variables, int points, int blockSize) {
        assertNull(DoubleNPointCrossover.check(variables, points, blockSize));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "8 | 0 | 1 | the number of points must be at least 1, got 0",
        "8 | 1 | 0 | the block size must be at least 1, got 0",
        "8 | 1 | 3 | the number of variables (8) is not a multiple of the block size (3)",
        "1 | 1 | 1 | an n-point crossover needs at least 2 variables to cut between, got 1",
        "3 | 1 | 3 | an n-point crossover needs at least 2 blocks of 3 variables to cut between, got 3 variables",
        "8 | 8 | 1 | the number of points (8) must be smaller than the number of variables (8)",
        "9 | 3 | 3 | the number of points (3) must be smaller than the number of blocks (3 blocks of 3 variables)"
    })
    void checkExplainsEveryInvalidCombination(int variables, int points, int blockSize, String reason) {
        assertEquals(reason, DoubleNPointCrossover.check(variables, points, blockSize));
    }

    @Test
    void executeRejectsWithTheReasonCheckGives() {
        var crossover = new DoubleNPointCrossover(1.0, 1, 3);

        InvalidConditionException e = assertThrows(InvalidConditionException.class,
            () -> crossover.execute(parents(8)));

        assertEquals(DoubleNPointCrossover.check(8, 1, 3), e.getMessage());
    }

    @Test
    void sizeRulesAreEnforcedEvenWhenTheCrossoverIsBypassed() {
        // Probability 0: the crossover is never applied, yet the wrong size fails on the first call.
        var crossover = new DoubleNPointCrossover(0.0, 1, 3, () -> 0.5, choices());

        assertThrows(InvalidConditionException.class, () -> crossover.execute(parents(8)));
    }

    // ── Argument errors ───────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void rejectsAProbabilityOutsideZeroToOne(double probability) {
        assertThrows(InvalidProbabilityValueException.class, () -> new DoubleNPointCrossover(probability, 2));
    }

    @Test
    void rejectsZeroPoints() {
        assertThrows(InvalidConditionException.class, () -> new DoubleNPointCrossover(0.9, 0));
    }

    @Test
    void rejectsZeroBlockSize() {
        assertThrows(InvalidConditionException.class, () -> new DoubleNPointCrossover(0.9, 1, 0));
    }

    @Test
    void rejectsNullGenerators() {
        assertThrows(NullParameterException.class,
            () -> new DoubleNPointCrossover(0.9, 2, 1, null, choices()),
            "a null random generator must fail at construction, not at the first crossover");
        assertThrows(NullParameterException.class,
            () -> new DoubleNPointCrossover(0.9, 2, () -> 0.0, null),
            "a null point generator must fail at construction, not at the first crossover");
    }

    @Test
    void rejectsVariablesThatAreNotAMultipleOfTheBlockSize() {
        var crossover = new DoubleNPointCrossover(1.0, 1, 3);

        assertThrows(InvalidConditionException.class, () -> crossover.execute(parents(8)));
    }

    @Test
    void rejectsAsManyPointsAsBlocks() {
        var crossover = new DoubleNPointCrossover(1.0, 3, 3);

        assertThrows(InvalidConditionException.class, () -> crossover.execute(parents(9)));
    }

    @Test
    void rejectsAsManyPointsAsVariables() {
        var crossover = new DoubleNPointCrossover(1.0, 8);

        assertThrows(InvalidConditionException.class, () -> crossover.execute(parents(8)));
    }

    @Test
    void rejectsASingleParent() {
        var crossover = new DoubleNPointCrossover(1.0, 2);

        assertThrows(InvalidConditionException.class, () -> crossover.execute(List.of(solution(8, 0.0))));
    }

    @Test
    void rejectsParentsWithDifferentNumbersOfVariables() {
        var crossover = new DoubleNPointCrossover(1.0, 2);

        assertThrows(InvalidConditionException.class,
            () -> crossover.execute(List.of(solution(8, 0.0), solution(9, SECOND_PARENT_OFFSET))));
    }

    @Test
    void rejectsNullParents() {
        var crossover = new DoubleNPointCrossover(1.0, 2);

        assertThrows(NullParameterException.class, () -> crossover.execute(null));
    }
}

package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException;
import org.uma.jmetal.util.errorchecking.exception.NegativeValueException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rounding, draws, copies and argument rules of {@link IntegerSBXCrossover}, against jMetal's
 * class of the same name where they differ.
 *
 * <p>The scripted tests use a distribution index of 0, which keeps the spread factor simple: for
 * parents 2 and 5 in [0, 10] and a draw of 0.9 the real children are 0.94 and 7.18, and for -5
 * and -2 in [-10, 0] they are -7.18 and -0.94. The statistical tests draw from a seeded
 * {@link Random}.
 */
class IntegerSBXCrossoverTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution with the given bounds and values. */
    static IntegerSolution solution(List<Bounds<Integer>> bounds, Integer... values) {
        IntegerSolution solution = new DefaultIntegerSolution(bounds, 2, 0);
        for (int i = 0; i < values.length; i++) solution.variables().set(i, values[i]);
        return solution;
    }

    /**
     * A one-variable solution whose bounds are set after construction: DefaultIntegerSolution
     * draws its initial value with JMetalRandom.nextInt, which cannot handle every range, and it
     * keeps the bounds list it was given.
     */
    static IntegerSolution solutionWithBounds(int lower, int upper, int value) {
        List<Bounds<Integer>> bounds = new ArrayList<>(List.of(Bounds.create(0, 1)));
        IntegerSolution solution = new DefaultIntegerSolution(bounds, 2, 0);
        bounds.set(0, Bounds.create(lower, upper));
        solution.variables().set(0, value);
        return solution;
    }

    /** Generator that returns the given numbers in order and fails when asked for one more. */
    static final class Script implements RandomGenerator<Double> {
        final Deque<Double> numbers;

        Script(Double... numbers) {
            this.numbers = new ArrayDeque<>(List.of(numbers));
        }

        @Override
        public Double getRandomValue() {
            assertFalse(numbers.isEmpty(), "the operator drew more random numbers than scripted");
            return numbers.removeFirst();
        }
    }

    /** The values of variable {@code index} in each child. */
    static List<Integer> values(List<IntegerSolution> children, int index) {
        return children.stream().map(child -> child.variables().get(index)).toList();
    }

    /** Public constructors and declared public methods, as comparable text. */
    static Set<String> publicSignatures(Class<?> type) {
        Stream<String> constructors = Arrays.stream(type.getConstructors())
            .map(Constructor::getParameterTypes)
            .map(parameters -> "<init>" + Arrays.toString(parameters));
        Stream<String> methods = Arrays.stream(type.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getReturnType().getName() + " " + method.getName()
                + Arrays.toString(method.getParameterTypes()));
        return Stream.concat(constructors, methods).collect(Collectors.toSet());
    }

    // ── Rounding ──────────────────────────────────────────────────────────────

    @Test
    void crossedValuesAreRoundedToTheNearestInteger() {
        // Trigger, then per variable: cross (0.0), spread (0.9) and order (0.9: c1 to the first child).
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 10), Bounds.create(-10, 0));
        List<IntegerSolution> parents = List.of(solution(bounds, 2, -5), solution(bounds, 5, -2));
        Script script = new Script(0.0, 0.0, 0.9, 0.9, 0.0, 0.9, 0.9);

        List<IntegerSolution> children = new IntegerSBXCrossover(1.0, 0.0, script).execute(parents);
        List<IntegerSolution> jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(
            1.0, 0.0, new Script(0.0, 0.0, 0.9, 0.9, 0.0, 0.9, 0.9)).execute(parents);

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(1, 7), values(children, 0), "0.94 and 7.18 round to 1 and 7");
        assertEquals(List.of(-7, -1), values(children, 1), "-7.18 and -0.94 round to -7 and -1");
        assertEquals(List.of(0, 7), values(jMetal, 0), "jMetal truncates 0.94 to 0");
        assertEquals(List.of(-7, 0), values(jMetal, 1), "jMetal truncates -0.94 to 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {3, -7})
    void childrenAreCentredOnTheParents(int smallerParent) {
        // Parents x and x + 4 in [x - 3, x + 7]: the bounds are as far from both parents, so the
        // real children of a crossed variable add up to 2x + 4, and so do swapped ones. jMetal
        // crosses half of the variables and truncates their children toward 0 by half a unit.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(smallerParent - 3, smallerParent + 7));
        List<IntegerSolution> parents = List.of(solution(bounds, smallerParent), solution(bounds, smallerParent + 4));
        Random random = new Random(42);
        Random jMetalRandom = new Random(42);
        var crossover = new IntegerSBXCrossover(1.0, 20.0, random::nextDouble);
        var jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(1.0, 20.0, jMetalRandom::nextDouble);
        double sum = 0;
        double jMetalSum = 0;
        int children = 0;
        for (int run = 0; run < 100_000; run++) {
            for (IntegerSolution child : crossover.execute(parents)) sum += child.variables().get(0);
            for (IntegerSolution child : jMetal.execute(parents)) jMetalSum += child.variables().get(0);
            children += 2;
        }

        double centre = smallerParent + 2;
        assertEquals(centre, sum / children, 0.01, "the children must not drift toward 0");
        assertEquals(centre - Math.signum(centre) * 0.25, jMetalSum / children, 0.01,
            "jMetal's children of a crossed variable drift half a unit toward 0");
    }

    @Test
    void parentsZeroAndOneGiveOneChildOfEach() {
        // jMetal crosses them into 0.5 - d and 0.5 + d, both truncated to 0, so a variable in
        // [0, 1] drifts to 0.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 1));
        List<IntegerSolution> parents = List.of(solution(bounds, 0), solution(bounds, 1));
        Random random = new Random(7);
        Random jMetalRandom = new Random(7);
        var crossover = new IntegerSBXCrossover(1.0, 20.0, random::nextDouble);
        var jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(1.0, 20.0, jMetalRandom::nextDouble);
        int jMetalZeros = 0;
        for (int run = 0; run < 10_000; run++) {
            List<IntegerSolution> children = crossover.execute(parents);
            assertEquals(1, children.get(0).variables().get(0) + children.get(1).variables().get(0),
                "the children must be one 0 and one 1");
            List<IntegerSolution> jMetalChildren = jMetal.execute(parents);
            if (jMetalChildren.get(0).variables().get(0) + jMetalChildren.get(1).variables().get(0) == 0) jMetalZeros++;
        }

        assertEquals(0.5, jMetalZeros / 10_000.0, 0.02, "jMetal gives two children of 0 whenever it crosses");
    }

    @Test
    void drawsAsJMetalAndDiffersOnlyInTheRounding() {
        // Same seed, same draws: on non-negative values a rounded child is the truncated one or
        // one more, and both generators end in step.
        List<Bounds<Integer>> bounds = Collections.nCopies(5, Bounds.create(0, 20));
        Random random = new Random(11);
        Random jMetalRandom = new Random(11);
        Random values = new Random(12);
        var crossover = new IntegerSBXCrossover(0.9, 5.0, random::nextDouble);
        var jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(0.9, 5.0, jMetalRandom::nextDouble);
        int differences = 0;
        for (int run = 0; run < 2_000; run++) {
            List<IntegerSolution> parents = new ArrayList<>();
            for (int p = 0; p < 2; p++) {
                parents.add(solution(bounds, values.ints(5, 0, 21).boxed().toArray(Integer[]::new)));
            }
            List<IntegerSolution> children = crossover.execute(parents);
            List<IntegerSolution> jMetalChildren = jMetal.execute(parents);
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < 5; i++) {
                    int difference = children.get(c).variables().get(i) - jMetalChildren.get(c).variables().get(i);
                    assertTrue(difference == 0 || difference == 1, "child " + c + ", variable " + i + ": " + difference);
                    differences += difference;
                }
            }
        }

        assertTrue(differences > 0, "the fixture must cross some variables");
        assertEquals(jMetalRandom.nextDouble(), random.nextDouble(), "both must draw the same numbers");
    }

    // ── Bounds ────────────────────────────────────────────────────────────────

    @Test
    void childrenStayWithinTheBounds() {
        List<Bounds<Integer>> bounds = List.of(Bounds.create(-3, 4), Bounds.create(0, 1), Bounds.create(10, 12));
        Random random = new Random(3);
        var crossover = new IntegerSBXCrossover(1.0, 0.0, random::nextDouble);
        List<IntegerSolution> parents = List.of(solution(bounds, -3, 0, 12), solution(bounds, 4, 1, 10));
        for (int run = 0; run < 10_000; run++) {
            for (IntegerSolution child : crossover.execute(parents)) {
                for (int i = 0; i < bounds.size(); i++) {
                    int value = child.variables().get(i);
                    assertTrue(value >= bounds.get(i).getLowerBound() && value <= bounds.get(i).getUpperBound(),
                        "variable " + i + " left its bounds: " + value);
                }
            }
        }
    }

    @Test
    void parentOutsideItsBoundsGivesChildrenWithinThem() {
        // A warm start can hand in a parent outside the bounds. Parent 1 lies 4 below [5, 10],
        // more than half its gap of 7 to parent 8, so for the draw 0.5 the spread factor of the
        // first child is NaN, which is rounded to 0 and then clamped to 5; the second child is
        // 7.99999. Trigger, cross, spread and order (0.9: c1 to the first child).
        List<Bounds<Integer>> bounds = List.of(Bounds.create(5, 10));
        List<IntegerSolution> parents = List.of(solution(bounds, 1), solution(bounds, 8));
        Script script = new Script(0.0, 0.0, 0.5, 0.9);

        List<IntegerSolution> children = new IntegerSBXCrossover(1.0, 20.0, script).execute(parents);
        List<IntegerSolution> jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(
            1.0, 20.0, new Script(0.0, 0.0, 0.5, 0.9)).execute(parents);

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(5, 8), values(children, 0), "the children must stay within [5, 10]");
        assertEquals(List.of(0, 7), values(jMetal, 0), "jMetal's cast turns the NaN child into 0 and truncates 7.99999");
    }

    @Test
    void variableWithEqualBoundsKeepsItsValue() {
        // Trigger, then swap (0.9) variable 0, cross (0.0) the equal values of variable 1 without
        // more draws, and swap variable 2.
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 10), Bounds.create(3, 3), Bounds.create(0, 10));
        Script script = new Script(0.0, 0.9, 0.0, 0.9);

        List<IntegerSolution> children = new IntegerSBXCrossover(1.0, 20.0, script)
            .execute(List.of(solution(bounds, 2, 3, 8), solution(bounds, 6, 3, 4)));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(6, 3, 4), children.get(0).variables());
        assertEquals(List.of(2, 3, 8), children.get(1).variables());
    }

    @Test
    void parentsTwoToTheThirtyOneApartAreCrossed() {
        // jMetal subtracts them as ints: Integer.MIN_VALUE - 0 overflows, its absolute value is
        // negative, and the parents count as equal, so they are copied.
        IntegerSolution first = solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE);
        IntegerSolution second = solutionWithBounds(Integer.MIN_VALUE, Integer.MAX_VALUE, 0);
        Script script = new Script(0.0, 0.0, 0.5, 0.9);

        List<IntegerSolution> children = new IntegerSBXCrossover(1.0, 20.0, script).execute(List.of(first, second));
        List<IntegerSolution> jMetal = new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(
            1.0, 20.0, new Script(0.0, 0.0)).execute(List.of(first, second));

        assertTrue(script.numbers.isEmpty(), "every scripted number must be used");
        assertEquals(List.of(-2112621161, 0), values(children, 0), "the smaller parent moves toward the larger");
        assertEquals(List.of(Integer.MIN_VALUE, 0), values(jMetal, 0), "jMetal copies them");
    }

    // ── Copies and draws ──────────────────────────────────────────────────────

    @Test
    void childrenAreCopiesWhenTheCrossoverDoesNotFire() {
        List<Bounds<Integer>> bounds = List.of(Bounds.create(0, 10));
        IntegerSolution first = solution(bounds, 2);
        IntegerSolution second = solution(bounds, 5);

        List<IntegerSolution> children = new IntegerSBXCrossover(0.5, 20.0, new Script(0.95))
            .execute(List.of(first, second));

        assertNotSame(first, children.get(0), "a child must never be a parent");
        assertNotSame(second, children.get(1), "a child must never be a parent");
        assertEquals(first.variables(), children.get(0).variables());
        assertEquals(second.variables(), children.get(1).variables());
    }

    @Test
    void defaultGeneratorIsJMetalRandom() {
        // The parents are built before the seeded generator is swapped in, because
        // DefaultIntegerSolution draws its initial values from JMetalRandom too.
        List<Bounds<Integer>> bounds = Collections.nCopies(10, Bounds.create(-50, 50));
        List<IntegerSolution> parents = List.of(
            solution(bounds, 0, 10, 20, 30, 40, 0, -10, -20, -30, -40),
            solution(bounds, 40, 30, 20, 10, 0, -40, -30, -20, -10, 0));
        var crossover = new IntegerSBXCrossover(1.0);
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        List<List<Integer>> first = new ArrayList<>();
        List<List<Integer>> second = new ArrayList<>();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(5));
            for (int run = 0; run < 50; run++) crossover.execute(parents).forEach(c -> first.add(c.variables()));
            global.setRandomGenerator(new JavaRandomGenerator(5));
            for (int run = 0; run < 50; run++) crossover.execute(parents).forEach(c -> second.add(c.variables()));
        } finally {
            global.setRandomGenerator(previous);
        }

        assertTrue(first.stream().anyMatch(values -> !parents.get(0).variables().equals(values)
            && !parents.get(1).variables().equals(values)), "the fixture must actually cross");
        assertEquals(first, second, "the same seed must give the same children");
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @Test
    void hasTheConstructorsAndMethodsOfJMetalsClass() {
        assertEquals(publicSignatures(org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover.class),
            publicSignatures(IntegerSBXCrossover.class), "changing the import must be enough");
        var crossover = new IntegerSBXCrossover(0.8);
        assertEquals(20.0, crossover.getDistributionIndex(), "jMetal's default distribution index");
        assertEquals(0.8, crossover.crossoverProbability());
        assertEquals(Collections.nCopies(2, 2), List.of(crossover.numberOfRequiredParents(),
            crossover.numberOfGeneratedChildren()));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN})
    void distributionIndexSetterRejectsWhatTheConstructorRejects(double distributionIndex) {
        var crossover = new IntegerSBXCrossover(0.9, 15.0);
        Class<? extends RuntimeException> expected =
            Double.isNaN(distributionIndex) ? InvalidConditionException.class : NegativeValueException.class;

        assertThrows(expected, () -> crossover.setDistributionIndex(distributionIndex));
        assertThrows(expected, () -> new IntegerSBXCrossover(0.9, distributionIndex));
        assertEquals(15.0, crossover.getDistributionIndex(), "a rejected value must leave the index unchanged");
    }

    @Test
    void negativeDistributionIndexGetsTheExceptionOfJMetal() {
        assertThrows(NegativeValueException.class,
            () -> new org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover(0.9, -0.1), "jMetal's exception");
        assertThrows(NegativeValueException.class, () -> new IntegerSBXCrossover(0.9, -0.1),
            "code that catches jMetal's exception must still catch it");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void probabilitySetterRejectsWhatTheConstructorRejects(double probability) {
        var crossover = new IntegerSBXCrossover(0.9);

        assertThrows(InvalidProbabilityValueException.class, () -> crossover.setCrossoverProbability(probability));
        assertThrows(InvalidProbabilityValueException.class, () -> new IntegerSBXCrossover(probability));
        assertEquals(0.9, crossover.crossoverProbability(), "a rejected value must leave the probability unchanged");
    }

    @Test
    void settersAcceptValidValues() {
        var crossover = new IntegerSBXCrossover(0.9);

        crossover.setDistributionIndex(0.0);
        crossover.setCrossoverProbability(1.0);

        assertEquals(0.0, crossover.getDistributionIndex());
        assertEquals(1.0, crossover.crossoverProbability());
    }

    @Test
    void rejectsAnythingButTwoParents() {
        var crossover = new IntegerSBXCrossover(0.9);
        IntegerSolution parent = solution(List.of(Bounds.create(0, 1)), 0);

        assertThrows(JMetalException.class, () -> crossover.execute(null));
        JMetalException error = assertThrows(JMetalException.class, () -> crossover.execute(List.of(parent)));
        assertEquals("There must be two parents instead of 1", error.getMessage());
    }
}

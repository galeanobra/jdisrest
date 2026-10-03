package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;
import org.uma.jmetal.util.pseudorandom.BoundedRandomGenerator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate sampling, winner rule and argument rules of {@link NaryTournamentSelection}.
 *
 * <p>Solution {@code i} of a population has objective {@code i}, and the comparator prefers
 * the lower objective, so solution {@code i} is the {@code i}-th best and the winner of a
 * tournament can be read back from its objective.
 */
class NaryTournamentSelectionTest {

    /** Lower objective wins. */
    static final Comparator<DoubleSolution> BY_OBJECTIVE = Comparator.comparingDouble(s -> s.objectives()[0]);

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** {@code size} solutions; solution {@code i} has objective {@code i}. */
    static List<DoubleSolution> population(int size) {
        List<DoubleSolution> population = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            DoubleSolution solution = new DefaultDoubleSolution(List.of(Bounds.create(0.0, 1.0)), 1, 0);
            solution.objectives()[0] = i;
            population.add(solution);
        }
        return population;
    }

    /** Integer generator over a seeded {@link Random}, counting its draws. */
    static final class Counting implements BoundedRandomGenerator<Integer> {
        final Random random;
        int draws;

        Counting(long seed) {
            random = new Random(seed);
        }

        @Override
        public Integer getRandomValue(Integer lower, Integer upper) {
            draws++;
            return lower + random.nextInt(upper - lower + 1);
        }
    }

    /** Integer generator that returns the given values in order and records each requested range. */
    static final class Script implements BoundedRandomGenerator<Integer> {
        final Deque<Integer> values;
        final List<List<Integer>> ranges = new ArrayList<>();

        Script(Integer... values) {
            this.values = new ArrayDeque<>(List.of(values));
        }

        @Override
        public Integer getRandomValue(Integer lower, Integer upper) {
            ranges.add(List.of(lower, upper));
            assertFalse(values.isEmpty(), "the operator drew more numbers than scripted");
            return values.removeFirst();
        }
    }

    static int objective(DoubleSolution solution) {
        return (int) solution.objectives()[0];
    }

    // ── Sampling ──────────────────────────────────────────────────────────────

    @Test
    void eachTournamentDrawsExactlyTournamentSizeNumbers() {
        // Before 1.2.0 a tournament built a whole permutation: 1000 draws here.
        Counting random = new Counting(1);
        var selection = new NaryTournamentSelection<>(3, BY_OBJECTIVE, random);

        selection.execute(population(1000));

        assertEquals(6, random.draws, "two tournaments of 3 candidates must draw 6 numbers, whatever the population size");
    }

    @Test
    void candidatesFollowAPartialFisherYatesShuffle() {
        // Virtual array [0 1 2 3 4]: draw 4 in [0,4] takes 4 (array [4 1 2 3 0]), draw 4 in [1,4]
        // takes 0 (array [4 0 2 3 1]), draw 2 in [2,4] takes 2. A comparator that ties everything
        // shows the order: the first candidate drawn wins.
        Script script = new Script(4, 4, 2, 1, 1, 2);
        List<Integer> compared = new ArrayList<>();
        Comparator<DoubleSolution> ties = (winner, candidate) -> {
            compared.add(objective(candidate));
            return 0;
        };
        var selection = new NaryTournamentSelection<>(3, ties, script);

        List<DoubleSolution> parents = selection.execute(population(5));

        assertEquals(List.of(List.of(0, 4), List.of(1, 4), List.of(2, 4), List.of(0, 4), List.of(1, 4), List.of(2, 4)),
            script.ranges, "draw k of a tournament must be in [k, N - 1]");
        assertEquals(List.of(0, 2, 0, 2), compared, "the later candidates of each tournament, in draw order");
        assertEquals(List.of(4, 1), parents.stream().map(NaryTournamentSelectionTest::objective).toList(),
            "ties keep the candidate drawn first");
    }

    @Test
    void tournamentOverTheWholePopulationAlwaysPicksTheBest() {
        // Only distinct candidates cover all 6 solutions in 6 draws.
        var selection = new NaryTournamentSelection<>(6, BY_OBJECTIVE, new Counting(2));
        List<DoubleSolution> population = population(6);

        for (int run = 0; run < 200; run++) {
            for (DoubleSolution parent : selection.execute(population)) {
                assertSame(population.get(0), parent, "every solution competes, so the best one must win");
            }
        }
    }

    @Test
    void winnersFollowTheDistributionOfABinaryTournamentWithoutReplacement() {
        // Two distinct candidates out of 4: the best wins with probability 1/2, the second
        // with 1/3, the third with 1/6 and the worst never.
        var selection = new NaryTournamentSelection<>(2, BY_OBJECTIVE, new Counting(3));
        List<DoubleSolution> population = population(4);
        int[] wins = new int[4];
        int runs = 30_000;
        for (int run = 0; run < runs; run++) {
            for (DoubleSolution parent : selection.execute(population)) wins[objective(parent)]++;
        }

        double[] expected = {1 / 2.0, 1 / 3.0, 1 / 6.0, 0.0};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], wins[i] / (2.0 * runs), 0.01, "share of wins of solution " + i);
        }
    }

    @Test
    void comparatorReturningAnyPositiveValueMakesTheCandidateWin() {
        // Each tournament draws solution 1, then solution 0, which this comparator ranks better
        // with a 7. jMetal's findBestSolution, used before 1.2.0, switched only on exactly 1.
        Comparator<DoubleSolution> scaled = (a, b) -> 7 * Integer.signum(objective(a) - objective(b));
        var selection = new NaryTournamentSelection<>(2, scaled, new Script(1, 1, 1, 1));

        List<DoubleSolution> parents = selection.execute(population(2));

        assertEquals(List.of(0, 0), parents.stream().map(NaryTournamentSelectionTest::objective).toList());
    }

    @Test
    void defaultConstructorDrawsFromJMetalRandom() {
        List<DoubleSolution> population = population(50);
        var selection = new NaryTournamentSelection<DoubleSolution>();
        JMetalRandom global = JMetalRandom.getInstance();
        PseudoRandomGenerator previous = global.getRandomGenerator();
        List<DoubleSolution> first = new ArrayList<>();
        List<DoubleSolution> second = new ArrayList<>();
        try {
            global.setRandomGenerator(new JavaRandomGenerator(9));
            for (int run = 0; run < 20; run++) first.addAll(selection.execute(population));
            global.setRandomGenerator(new JavaRandomGenerator(9));
            for (int run = 0; run < 20; run++) second.addAll(selection.execute(population));
        } finally {
            global.setRandomGenerator(previous);
        }

        assertEquals(first, second, "a seeded JMetalRandom must reproduce the selections");
        assertEquals(2, selection.getTournamentSize());
    }

    // ── Result ────────────────────────────────────────────────────────────────

    @Test
    void resultIsTwoPopulationMembers() {
        List<DoubleSolution> population = population(5);

        List<DoubleSolution> parents = new NaryTournamentSelection<>(2, BY_OBJECTIVE, new Counting(4)).execute(population);

        assertEquals(2, parents.size());
        for (DoubleSolution parent : parents) {
            assertTrue(population.stream().anyMatch(member -> member == parent), "parents are references, not copies");
        }
    }

    @Test
    void singleSolutionIsReturnedTwiceWithTournamentSizeOne() {
        List<DoubleSolution> population = population(1);

        List<DoubleSolution> parents = new NaryTournamentSelection<>(1, BY_OBJECTIVE, new Counting(5)).execute(population);

        assertSame(population.get(0), parents.get(0));
        assertSame(population.get(0), parents.get(1));
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsATournamentSizeBelowOne(int size) {
        assertThrows(InvalidConditionException.class, () -> new NaryTournamentSelection<>(size, BY_OBJECTIVE));
    }

    @Test
    void rejectsANullComparatorOrGenerator() {
        assertThrows(NullParameterException.class, () -> new NaryTournamentSelection<DoubleSolution>(2, null));
        assertThrows(NullParameterException.class, () -> new NaryTournamentSelection<>(2, BY_OBJECTIVE, null));
    }

    @Test
    void rejectsAPopulationSmallerThanTheTournament() {
        var selection = new NaryTournamentSelection<>(3, BY_OBJECTIVE);

        assertThrows(InvalidConditionException.class, () -> selection.execute(population(2)));
    }
}

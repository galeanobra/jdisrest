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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Population size and index checks, requested counts and distinctness of
 * {@link DifferentialEvolutionSelection}.
 *
 * <p>The size checks run under a timeout: before 1.2.0 a population one solution too small
 * made {@code execute} loop forever.
 */
class DifferentialEvolutionSelectionTest {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** {@code size} distinct solutions. */
    static List<DoubleSolution> population(int size) {
        List<DoubleSolution> population = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            DoubleSolution solution = new DefaultDoubleSolution(List.of(Bounds.create(0.0, 100.0)), 1, 0);
            solution.variables().set(0, (double) i);
            population.add(solution);
        }
        return population;
    }

    /** Integer generator over a seeded {@link Random}. */
    static BoundedRandomGenerator<Integer> seeded(long seed) {
        Random random = new Random(seed);
        return (lower, upper) -> lower + random.nextInt(upper - lower + 1);
    }

    /** Integer generator that must not be called. */
    static final BoundedRandomGenerator<Integer> UNUSED = (lower, upper) -> fail("no random index must be drawn");

    static DifferentialEvolutionSelection<DoubleSolution> selection(int count, boolean includeCurrent, int index) {
        var selection = new DifferentialEvolutionSelection<>(seeded(index), count, includeCurrent);
        selection.setIndex(index);
        return selection;
    }

    // ── Population size ───────────────────────────────────────────────────────

    @Test
    void populationOneSolutionTooSmallIsRejectedInsteadOfLoopingForever() {
        // DE/rand/1 needs 3 individuals besides the target: 4 in all.
        var selection = new DifferentialEvolutionSelection<DoubleSolution>();
        selection.setIndex(0);

        InvalidConditionException error = assertTimeoutPreemptively(TIMEOUT,
            () -> assertThrows(InvalidConditionException.class, () -> selection.execute(population(3))));
        assertTrue(error.getMessage().contains("at least 4"), error.getMessage());
    }

    @Test
    void includingTheCurrentSolutionNeedsOneSolutionLess() {
        assertTimeoutPreemptively(TIMEOUT,
            () -> assertThrows(InvalidConditionException.class, () -> selection(3, true, 0).execute(population(2))));

        List<DoubleSolution> population = population(3);
        List<DoubleSolution> selected = selection(3, true, 2).execute(population);

        assertEquals(3, selected.size());
        assertSame(population.get(2), selected.get(2), "the current solution is appended last");
        assertEquals(new HashSet<>(population.subList(0, 2)), new HashSet<>(selected.subList(0, 2)),
            "the random part is the two other solutions");
    }

    // ── Index ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void indexEqualToThePopulationSizeIsRejected(boolean includeCurrent) {
        var selection = selection(2, includeCurrent, 5);

        assertThrows(InvalidConditionException.class, () -> selection.execute(population(5)));
    }

    @Test
    void missingIndexIsRejected() {
        var selection = new DifferentialEvolutionSelection<DoubleSolution>();

        assertThrows(InvalidConditionException.class, () -> selection.execute(population(10)));
    }

    // ── Requested counts ──────────────────────────────────────────────────────

    @Test
    void noRandomIndividualIsDrawnWhenNoneIsRequested() {
        List<DoubleSolution> population = population(4);

        var none = new DifferentialEvolutionSelection<DoubleSolution>(UNUSED, 0, false);
        none.setIndex(1);
        var onlyCurrent = new DifferentialEvolutionSelection<DoubleSolution>(UNUSED, 1, true);
        onlyCurrent.setIndex(1);

        assertEquals(List.of(), none.execute(population), "0 requested must give 0 solutions");
        assertEquals(List.of(population.get(1)), onlyCurrent.execute(population),
            "1 requested with the current solution must give only the current solution");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void selectsDistinctSolutionsOtherThanTheTarget(int index) {
        List<DoubleSolution> population = population(6);
        var selection = selection(5, false, index);

        for (int run = 0; run < 50; run++) {
            List<DoubleSolution> selected = selection.execute(population);

            assertEquals(5, selected.size());
            assertEquals(5, new HashSet<>(selected).size(), "the selected solutions must be distinct");
            assertFalse(selected.contains(population.get(index)), "the target must not be drawn at random");
        }
    }

    @Test
    void constructorRejectsImpossibleCounts() {
        assertThrows(InvalidConditionException.class, () -> new DifferentialEvolutionSelection<>(-1, false));
        assertThrows(InvalidConditionException.class, () -> new DifferentialEvolutionSelection<>(0, true));
        assertThrows(NullParameterException.class, () -> new DifferentialEvolutionSelection<>(null, 3, false));
    }
}

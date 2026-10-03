package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jitter parameter, draws and argument rules of {@link RandomMutationWithRandomProbability}.
 *
 * <p>The scripted generator gives the jitter draw first, then for each variable a trigger
 * draw and, when it mutates, the draw of its new value.
 */
class RandomMutationWithRandomProbabilityTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution of {@code size} variables in [0, 10], all at 5. */
    static DoubleSolution solution(int size) {
        DoubleSolution solution = new DefaultDoubleSolution(Collections.nCopies(size, Bounds.create(0.0, 10.0)), 2, 0);
        for (int i = 0; i < size; i++) solution.variables().set(i, 5.0);
        return solution;
    }

    /** Generator that returns the given numbers in order and fails when asked for one more. */
    static RandomGenerator<Double> script(Double... numbers) {
        Deque<Double> queue = new ArrayDeque<>(List.of(numbers));
        return () -> {
            assertFalse(queue.isEmpty(), "the operator drew more random numbers than scripted");
            return queue.removeFirst();
        };
    }

    // ── Jitter ────────────────────────────────────────────────────────────────

    @Test
    void defaultJitterIsThreePointFiveOverTheNumberOfVariables() {
        // 7 variables, base 0: p_eff = 0.8 * 3.5 / 7 = 0.4. Variable 0 mutates (0.39 -> value
        // 0.3 * 10), variable 1 does not (0.41), and neither do the rest.
        var mutation = new RandomMutationWithRandomProbability(0.0, script(0.8, 0.39, 0.3, 0.41, 0.9, 0.9, 0.9, 0.9, 0.9));

        DoubleSolution mutated = mutation.execute(solution(7));

        assertEquals(RandomMutationWithRandomProbability.DEFAULT_JITTER, mutation.jitter());
        assertEquals(List.of(3.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0), mutated.variables(),
            "variable 0 was reset to 0 + 0.3 * 10, the others are untouched");
    }

    @Test
    void jitterIsAConstructorParameter() {
        // 4 variables, base 0.1, jitter 2: p_eff = 0.1 + 0.5 * 2 / 4 = 0.35.
        var mutation = new RandomMutationWithRandomProbability(0.1, 2.0,
            script(0.5, 0.34, 0.25, 0.36, 0.34, 0.75, 0.99));

        DoubleSolution mutated = mutation.execute(solution(4));

        assertEquals(2.0, mutation.jitter());
        assertEquals(List.of(2.5, 5.0, 7.5, 5.0), mutated.variables());
    }

    @Test
    void zeroJitterLeavesTheBaseProbability() {
        var mutation = new RandomMutationWithRandomProbability(0.2, 0.0, script(0.99, 0.21, 0.19, 0.0));

        DoubleSolution mutated = mutation.execute(solution(2));

        assertEquals(List.of(5.0, 0.0), mutated.variables(), "only the trigger at or below 0.2 mutates");
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsANegativeOrNaNProbabilityNamingIt(double probability) {
        JMetalException error = assertThrows(JMetalException.class,
            () -> new RandomMutationWithRandomProbability(probability));

        assertTrue(error.getMessage().endsWith(String.valueOf(probability)),
            "the message must print the rejected argument, not the unassigned field: " + error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsAJitterThatIsNegativeOrNotFinite(double jitter) {
        assertThrows(JMetalException.class, () -> new RandomMutationWithRandomProbability(0.1, jitter, () -> 0.5));
    }

    @Test
    void rejectsANullSolution() {
        assertThrows(JMetalException.class, () -> new RandomMutationWithRandomProbability(0.1).execute(null));
    }
}

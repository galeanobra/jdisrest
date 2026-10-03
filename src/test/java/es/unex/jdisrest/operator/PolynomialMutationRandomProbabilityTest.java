package es.unex.jdisrest.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jitter parameter and equivalence with jMetal's {@link PolynomialMutation} of
 * {@link PolynomialMutationRandomProbability}.
 *
 * <p>The scripted generator gives the jitter draw first, then for each variable a trigger
 * draw and, when it mutates, the draw of the polynomial step. A mutated variable must equal
 * what jMetal's polynomial mutation computes from the same step draw.
 */
class PolynomialMutationRandomProbabilityTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A solution of {@code size} variables in [0, 10], all at 4. */
    static DoubleSolution solution(int size) {
        DoubleSolution solution = new DefaultDoubleSolution(Collections.nCopies(size, Bounds.create(0.0, 10.0)), 2, 0);
        for (int i = 0; i < size; i++) solution.variables().set(i, 4.0);
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

    /** What jMetal's polynomial mutation with distribution index 20 makes of 4.0 with the step draw {@code rnd}. */
    static double polynomialStep(double rnd) {
        DoubleSolution reference = solution(1);
        new PolynomialMutation(1.0, 20.0, new RepairDoubleSolutionWithBoundValue(), script(0.0, rnd)).execute(reference);
        return reference.variables().get(0);
    }

    // ── Jitter ────────────────────────────────────────────────────────────────

    @Test
    void jitterIsAConstructorParameter() {
        // Base 0.1, jitter 0.5: p_eff = 0.1 + 0.5 * 0.5 = 0.35.
        var mutation = new PolynomialMutationRandomProbability(0.1, 20.0, 0.5,
            new RepairDoubleSolutionWithBoundValue(), script(0.5, 0.34, 0.3, 0.36, 0.35, 0.8));

        DoubleSolution mutated = mutation.execute(solution(3));

        assertEquals(0.5, mutation.jitter());
        assertEquals(List.of(polynomialStep(0.3), 4.0, polynomialStep(0.8)), mutated.variables(),
            "variables whose trigger is at or below p_eff get jMetal's polynomial step");
    }

    @Test
    void defaultJitterIsFiveThirtySixths() {
        // Base 0, U = 0.72: p_eff = 0.72 * 5 / 36 = 0.1.
        var mutation = new PolynomialMutationRandomProbability(0.0, 20.0, script(0.72, 0.0999, 0.4, 0.1001));

        DoubleSolution mutated = mutation.execute(solution(2));

        assertEquals(5.0 / 36, PolynomialMutationRandomProbability.DEFAULT_JITTER);
        for (var defaulted : List.of(new PolynomialMutationRandomProbability(),
                new PolynomialMutationRandomProbability(0.1, 20.0),
                new PolynomialMutationRandomProbability(0.1, 20.0, () -> 0.5),
                new PolynomialMutationRandomProbability(0.1, 20.0, new RepairDoubleSolutionWithBoundValue()))) {
            assertEquals(PolynomialMutationRandomProbability.DEFAULT_JITTER, defaulted.jitter(),
                "the constructors without a jitter keep 5/36");
        }
        assertEquals(List.of(polynomialStep(0.4), 4.0), mutated.variables());
    }

    @Test
    void zeroJitterLeavesTheBaseProbability() {
        var mutation = new PolynomialMutationRandomProbability(0.2, 20.0, 0.0,
            new RepairDoubleSolutionWithBoundValue(), script(0.99, 0.21, 0.19, 0.6));

        DoubleSolution mutated = mutation.execute(solution(2));

        assertEquals(List.of(4.0, polynomialStep(0.6)), mutated.variables());
    }

    // ── Arguments ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsAJitterThatIsNegativeOrNotFinite(double jitter) {
        assertThrows(InvalidConditionException.class, () -> new PolynomialMutationRandomProbability(0.1, 20.0, jitter,
            new RepairDoubleSolutionWithBoundValue(), () -> 0.5));
    }
}

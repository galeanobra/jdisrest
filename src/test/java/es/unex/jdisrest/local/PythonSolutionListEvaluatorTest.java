package es.unex.jdisrest.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.REPAIR;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.SUM;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.start;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PythonSolutionListEvaluator} against the fake child of
 * {@link PythonProcessEvaluatorTest}: objectives and constraints are copied into
 * the solutions, repaired variables are written back in the layout the
 * decision extractor produced and only within the bounds of their variables, and
 * a vector with a non-finite variable is never sent. The tests that start the
 * child are skipped without a Python 3 interpreter.
 */
class PythonSolutionListEvaluatorTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** One objective, one constraint; the first variable is the fake child's opcode. */
    static DoubleSolution solution(double... values) {
        List<Bounds<Double>> bounds = Collections.nCopies(values.length, Bounds.create(-1000.0, 1000.0));
        DoubleSolution s = new DefaultDoubleSolution(bounds, 1, 1);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    /** {@link #solution} whose variables after the first lie within {@code [-limit, limit]}. */
    static DoubleSolution bounded(double limit, double... values) {
        List<Bounds<Double>> bounds = new ArrayList<>();
        bounds.add(Bounds.create(-1000.0, 1000.0));
        bounds.addAll(Collections.nCopies(values.length - 1, Bounds.create(-limit, limit)));
        DoubleSolution s = new DefaultDoubleSolution(bounds, 1, 1);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    /** Sends only the variables at even positions: a layout {@code SolutionVariables.flatten} does not know. */
    static final Function<DoubleSolution, List<? extends Number>> EVEN_POSITIONS =
            s -> List.of(s.variables().get(0), s.variables().get(2));

    /** Writes a vector laid out as {@link #EVEN_POSITIONS} back into its positions. */
    static final BiConsumer<DoubleSolution, List<Number>> TO_EVEN_POSITIONS = (s, v) -> {
        s.variables().set(0, v.get(0).doubleValue());
        s.variables().set(2, v.get(1).doubleValue());
    };

    // ── Write-back ────────────────────────────────────────────────────────────

    @Test
    void objectivesAndConstraintsAreCopiedIntoEverySolution(@TempDir Path dir) throws IOException {
        DoubleSolution a = solution(SUM, 1.5, 2.0);
        DoubleSolution b = solution(SUM, 10.0, 0.25);
        try (PythonProcessEvaluator python = start(dir)) {
            new PythonSolutionListEvaluator<DoubleSolution>(python).evaluate(List.of(a, b), null);
        }
        assertArrayEquals(new double[] {3.5}, a.objectives());
        assertArrayEquals(new double[] {10.25}, b.objectives());
        assertArrayEquals(new double[] {-1.0}, b.constraints());
        assertEquals(List.of((double) SUM, 1.5, 2.0), a.variables(), "variables are kept when none come back");
    }

    @Test
    void repairedVariablesAreWrittenBackInTheFlattenLayoutByDefault(@TempDir Path dir) throws IOException {
        DoubleSolution s = solution(REPAIR, 1.0, 2.0);
        try (PythonProcessEvaluator python = start(dir)) {
            new PythonSolutionListEvaluator<DoubleSolution>(python).evaluate(List.of(s), null);
        }
        assertEquals(List.of(REPAIR + 1.0, 2.0, 3.0), s.variables());
        assertArrayEquals(new double[] {REPAIR + 3.0}, s.objectives());
    }

    @Test
    void customApplierWritesRepairedVariablesInTheExtractorLayout(@TempDir Path dir) throws IOException {
        DoubleSolution s = solution(REPAIR, 1.0, 2.0);
        try (PythonProcessEvaluator python = start(dir)) {
            new PythonSolutionListEvaluator<>(python, EVEN_POSITIONS, TO_EVEN_POSITIONS).evaluate(List.of(s), null);
        }
        assertEquals(List.of(REPAIR + 1.0, 1.0, 3.0), s.variables(),
                "the repaired vector must land in the positions the extractor read");
        assertArrayEquals(new double[] {REPAIR + 2.0}, s.objectives());
    }

    @Test
    void customExtractorWithoutApplierStillAppliesTheFlattenLayout(@TempDir Path dir) throws IOException {
        DoubleSolution s = solution(REPAIR, 1.0, 2.0);
        try (PythonProcessEvaluator python = start(dir)) {
            PythonSolutionListEvaluator<DoubleSolution> evaluator = new PythonSolutionListEvaluator<>(python, EVEN_POSITIONS);
            assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(List.of(s), null),
                    "a 2-element repair cannot be applied to a 3-variable solution in the flatten layout");
        }
        assertEquals(List.of((double) REPAIR, 1.0, 2.0), s.variables(), "a rejected repair must not be written");
        assertArrayEquals(new double[] {0.0}, s.objectives(), "a rejected result must not write objectives");
    }

    // ── Bounds and finite values ──────────────────────────────────────────────

    @Test
    void repairOutsideTheBoundsIsRejectedBeforeAnythingIsWritten(@TempDir Path dir) throws IOException {
        // The fake child's repair adds 1 to every variable: 2.0 becomes 3.0, above 2.5.
        DoubleSolution s = bounded(2.5, REPAIR, 1.0, 2.0);
        try (PythonProcessEvaluator python = start(dir)) {
            PythonSolutionListEvaluator<DoubleSolution> evaluator = new PythonSolutionListEvaluator<>(python);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(List.of(s), null));
            assertEquals("variables[2] = 3.0 is outside the bounds [-2.5, 2.5] of its variable", e.getMessage(),
                    "the local evaluator must reject what the REST master answers with 422");
        }
        assertEquals(List.of((double) REPAIR, 1.0, 2.0), s.variables(), "a rejected repair must not be written");
        assertArrayEquals(new double[] {0.0}, s.objectives(), "a rejected result must not write objectives");
    }

    @Test
    void decisionWithANonFiniteVariableIsNotSent(@TempDir Path dir) throws IOException {
        DoubleSolution s = solution(SUM, Double.NaN);
        try (PythonProcessEvaluator python = start(dir)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PythonSolutionListEvaluator<DoubleSolution>(python).evaluate(List.of(s), null));
            assertArrayEquals(new double[] {1.0}, python.evaluate(List.of(SUM, 1.0)).objectives,
                    "the child must not have received the vector");
        }
        assertArrayEquals(new double[] {0.0}, s.objectives(), "the solution must keep its objectives");
    }

    @Test
    void valuesOnTheBoundsAreWrittenBack() {
        DoubleSolution s = bounded(2.5, 0.0, 0.0);

        PythonSolutionListEvaluator.applyWithinBounds(s, List.of(-2.5, 2));

        assertEquals(List.of(-2.5, 2.0), s.variables(), "bounds are inclusive, and values are converted");
    }

    @Test
    void valueOneUlpOutsideItsBoundsIsRejectedAndNothingIsWritten() {
        DoubleSolution s = bounded(2.5, 0.0, 0.0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PythonSolutionListEvaluator.applyWithinBounds(s, List.of(1.0, Math.nextUp(2.5))));

        assertTrue(e.getMessage().startsWith("variables[1] = 2.5000000000000004 is outside the bounds"), e.getMessage());
        assertEquals(List.of(0.0, 0.0), s.variables(), "there is no tolerance, and nothing is written");
    }
}

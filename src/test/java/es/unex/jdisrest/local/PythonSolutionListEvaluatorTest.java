package es.unex.jdisrest.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.REPAIR;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.SUM;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.requests;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.start;
import static es.unex.jdisrest.local.PythonProcessEvaluatorTest.startLayoutChild;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PythonSolutionListEvaluator} against the fake children of
 * {@link PythonProcessEvaluatorTest}: objectives and constraints are copied into
 * the solutions, repaired variables are written back in the layout the
 * decision extractor produced and only within the bounds of their variables, a
 * vector with a non-finite variable is never sent, and the default extractor sends
 * the layout of binary and composite solutions and takes repaired bits back. The
 * tests that start a child are skipped without a Python 3 interpreter.
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

    /** A binary solution with one objective and one constraint, from the bit strings of its variables. */
    static BinarySolution binary(String... values) {
        BinarySolution s = new DefaultBinarySolution(Arrays.stream(values).map(String::length).toList(), 1, 1);
        for (int i = 0; i < values.length; i++) {
            BinarySet bits = new BinarySet(values[i].length());
            for (int b = 0; b < values[i].length(); b++) {
                if (values[i].charAt(b) == '1') bits.set(b);
            }
            s.variables().set(i, bits);
        }
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

    // ── Layout and bits ───────────────────────────────────────────────────────

    @Test
    void binarySolutionTravelsWithItsLayoutAndARepairedBitComesBack(@TempDir Path dir) throws IOException {
        BinarySolution s = binary("101", "00110");
        try (PythonProcessEvaluator python = startLayoutChild(dir, "flip-last")) {
            new PythonSolutionListEvaluator<BinarySolution>(python).evaluate(List.of(s), null);
        }
        assertEquals(List.of("{\"id\":0,\"vars\":[1,0,1,0,0,1,1,0],\"encoding\":\"binary\",\"bitsPerVariable\":[3,5]}"),
                requests(dir));
        assertEquals("[101, 00111]", s.variables().toString(), "the last bit, sent back as true, is set");
        assertEquals(5, s.variables().get(1).getBinarySetLength());
        assertArrayEquals(new double[] {8.0}, s.objectives());
    }

    @Test
    void compositeWithEveryKindOfSegmentTravelsWithItsLayout(@TempDir Path dir) throws IOException {
        IntegerSolution integers = new DefaultIntegerSolution(Collections.nCopies(2, Bounds.create(-10, 10)), 1, 1);
        integers.variables().set(0, 3);
        integers.variables().set(1, -7);
        DoubleSolution reals = new DefaultDoubleSolution(List.of(Bounds.create(-1.0, 1.0)), 1, 1);
        reals.variables().set(0, 0.25);
        CompositeSolution s = new CompositeSolution(List.<Solution<?>>of(integers, reals, binary("101", "00110")));
        try (PythonProcessEvaluator python = startLayoutChild(dir, "flip-last")) {
            new PythonSolutionListEvaluator<CompositeSolution>(python).evaluate(List.of(s), null);
        }
        assertEquals(List.of("{\"id\":0,\"vars\":[3,-7,0.25,1,0,1,0,0,1,1,0],\"segmentSizes\":[2,1,8],"
                + "\"encoding\":\"mixed\",\"segmentEncodings\":[\"int\",\"double\",\"binary\"],\"bitsPerVariable\":[3,5]}"),
                requests(dir));
        assertEquals("[101, 00111]", s.variables().get(2).variables().toString());
        assertEquals(List.of(3, -7), s.variables().get(0).variables(), "the other values are written back as they were");
        assertArrayEquals(new double[] {11.0}, s.objectives());
    }

    @Test
    void customExtractorSendsNoLayout(@TempDir Path dir) throws IOException {
        DoubleSolution s = solution(SUM, 1.0, 2.0);
        try (PythonProcessEvaluator python = startLayoutChild(dir, "echo")) {
            new PythonSolutionListEvaluator<>(python, EVEN_POSITIONS).evaluate(List.of(s), null);
            new PythonSolutionListEvaluator<DoubleSolution>(python).evaluate(List.of(s), null);
        }
        assertEquals(List.of("{\"id\":0,\"vars\":[0.0,2.0]}",
                "{\"id\":1,\"vars\":[0.0,1.0,2.0],\"encoding\":\"double\"}"), requests(dir),
                "the layout of a custom extractor's vector is unknown");
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

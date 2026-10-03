package es.unex.jdisrest.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.fileoutput.impl.DefaultFileOutputContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static es.unex.jdisrest.util.SolutionVariablesTest.doubleSolution;
import static es.unex.jdisrest.util.SolutionVariablesTest.intSolution;
import static es.unex.jdisrest.util.TraceWriterTest.bits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VAR-row formatting for composites of any number of segments and encodings. */
class CompositeSolutionListOutputTest {

    /** A binary solution with one objective, like those of {@link SolutionVariablesTest}, from its bit strings. */
    static BinarySolution binarySolution(String... values) {
        BinarySolution s = new DefaultBinarySolution(Arrays.stream(values).map(String::length).toList(), 1);
        for (int i = 0; i < values.length; i++) s.variables().set(i, bits(values[i]));
        return s;
    }

    /** A solution of no jMetal binary type whose variables are {@link BinarySet}s at runtime. */
    static Solution<BinarySet> binarySetSolution(String... values) {
        return new Solution<>() {
            private final List<BinarySet> vars = new ArrayList<>(Arrays.stream(values).map(TraceWriterTest::bits).toList());
            @Override public List<BinarySet> variables() { return vars; }
            @Override public double[] objectives() { return new double[1]; }
            @Override public double[] constraints() { return new double[0]; }
            @Override public Map<Object, Object> attributes() { return new HashMap<>(); }
            @Override public Solution<BinarySet> copy() { return this; }
        };
    }

    @Test
    void twoIntegerSegmentsKeepTheOriginalRowFormat() {
        IntegerSolution du = intSolution(1, 2, 3);
        IntegerSolution cu = intSolution(4, 5);
        CompositeSolution c = new CompositeSolution(List.of(du, cu));
        c.objectives()[0] = 10.5;

        assertEquals("1 2 3 4 5,[10.5],[]", CompositeSolutionListOutput.formatSolution(c));
    }

    @Test
    void threeSegmentsAreAllWritten() {
        CompositeSolution c = new CompositeSolution(List.of(intSolution(1), intSolution(2), intSolution(3)));
        String row = CompositeSolutionListOutput.formatSolution(c);

        assertTrue(row.startsWith("1 2 3,"), row);
    }

    @Test
    void realSegmentsAreWrittenAsDoubles() {
        DoubleSolution d = doubleSolution(0.5, -2.0);
        CompositeSolution c = new CompositeSolution(List.of(intSolution(7), d));

        assertTrue(CompositeSolutionListOutput.formatSolution(c).startsWith("7 0.5 -2.0,"),
            CompositeSolutionListOutput.formatSolution(c));
    }

    @Test
    void flatSolutionsAreFormattedToo() {
        assertTrue(CompositeSolutionListOutput.formatSolution(doubleSolution(1.5, 2.5)).startsWith("1.5 2.5,"));
    }

    // ── Binary variables ──────────────────────────────────────────────────────

    @Test
    void binarySegmentsAreWrittenAsOneBitStringPerVariable() {
        CompositeSolution c = new CompositeSolution(List.of(intSolution(7), binarySolution("101", "00110"),
                doubleSolution(0.5), binarySolution("0")));

        assertEquals("7 101 00110 0.5 0,[0.0],[]", CompositeSolutionListOutput.formatSolution(c),
                "bit 0 leftmost, one character per bit of the variable's length, leading zeros kept");
    }

    @Test
    void flatBinarySolutionsAreFormattedToo() {
        assertEquals("000 1,[0.0],[]", CompositeSolutionListOutput.formatSolution(binarySolution("000", "1")),
                "an all-zero variable keeps its length");
    }

    @Test
    void solutionsWhoseVariablesAreBinarySetsAreBinary() {
        CompositeSolution c = new CompositeSolution(List.of(binarySetSolution("01", "1"), intSolution(3)));

        assertEquals("01 1 3,[0.0],[]", CompositeSolutionListOutput.formatSolution(c));
    }

    @Test
    void aNullBinaryVariableIsRejectedWithItsSegmentAndPosition() {
        BinarySolution b = binarySolution("10", "01");
        b.variables().set(1, null);
        CompositeSolution c = new CompositeSolution(List.of(intSolution(3, 4), b));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CompositeSolutionListOutput.formatSolution(c));
        assertEquals("segment 1: variables[1] is null", e.getMessage(), "the position within the segment");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aBinaryVariableOfAnotherTypeIsRejectedWithItsSegmentAndPosition() {
        BinarySolution b = binarySolution("10", "01");
        ((List) b.variables()).set(1, 5);
        CompositeSolution c = new CompositeSolution(List.of(intSolution(3), b));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CompositeSolutionListOutput.formatSolution(c),
                "an IllegalArgumentException, not a ClassCastException");
        assertEquals("segment 1: variables[1] = 5 is a Integer but its segment is binary-encoded", e.getMessage(),
                "worded as SolutionVariables words a variable of another type than its segment");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aSegmentMixingBinarySetsAndNumbersIsRejectedAsSolutionVariablesRejectsIt() {
        Solution<BinarySet> b = binarySetSolution("10", "01");
        ((List) b.variables()).set(1, 5);
        CompositeSolution c = new CompositeSolution(List.of(intSolution(3), b));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CompositeSolutionListOutput.formatSolution(c));
        assertTrue(e.getMessage().startsWith("segment 1: Unsupported solution type "), e.getMessage());
        assertTrue(e.getMessage().endsWith("variables[0] is BinarySet but variables[1] is Integer; "
                + "the variables of a flat solution must be all Integer, all Double or all BinarySet"), e.getMessage());
    }

    @Test
    void aBinaryVariableOfNoBitsIsRejected() {
        BinarySolution b = binarySolution("10");
        b.variables().set(0, new BinarySet(0));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CompositeSolutionListOutput.formatSolution(new CompositeSolution(List.of(intSolution(3), b))));
        assertEquals("segment 1: variables[0] is a BinarySet of 0 bits; a binary variable needs at least one",
                e.getMessage(), "its empty token would leave the row a variable short for any reader");
    }

    @Test
    void anIntegerSegmentKeepsTheRulesOfSolutionVariablesAndNamesItsSegment() {
        IntegerSolution broken = intSolution(1, 2);
        broken.variables().set(1, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CompositeSolutionListOutput.formatSolution(new CompositeSolution(List.of(intSolution(3), broken))));
        assertEquals("segment 1: variables[1] is null", e.getMessage());
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    @Test
    void theSeparatorOnlySeparatesTheObjectivesOfTheFunRows(@TempDir Path dir) throws IOException {
        CompositeSolution c = new CompositeSolution(List.of(TraceWriterTest.integers(0, 7, -3),
                TraceWriterTest.binary(0, "01", "110")));
        c.objectives()[0] = 2.5;
        c.objectives()[1] = 1.0E-4;
        Path var = dir.resolve("VAR.csv");
        Path fun = dir.resolve("FUN.csv");

        new CompositeSolutionListOutput(List.of(c, c))
                .setVarFileOutputContext(new DefaultFileOutputContext(var.toString(), ";"))
                .setFunFileOutputContext(new DefaultFileOutputContext(fun.toString(), ";"))
                .print();

        assertEquals(List.of("7 -3 01 110,[2.5  1.0E-4],[]", "7 -3 01 110,[2.5  1.0E-4],[]"), Files.readAllLines(var),
                "the variables are separated by spaces whatever the separator");
        assertEquals(List.of("2.5;1.0E-4", "2.5;1.0E-4"), Files.readAllLines(fun));
    }

    @Test
    void aSolutionThatCannotBeWrittenLeavesBothFilesAsTheyWere(@TempDir Path dir) throws IOException {
        Path var = Files.writeString(dir.resolve("VAR.csv"), "previous variables\n");
        Path fun = Files.writeString(dir.resolve("FUN.csv"), "previous objectives\n");
        CompositeSolution nested = new CompositeSolution(List.of(intSolution(1),
                new CompositeSolution(List.of(intSolution(2)))));
        CompositeSolutionListOutput output = new CompositeSolutionListOutput(
                List.of(new CompositeSolution(List.of(intSolution(1))), nested))
                .setVarFileOutputContext(new DefaultFileOutputContext(var.toString(), ","))
                .setFunFileOutputContext(new DefaultFileOutputContext(fun.toString(), ","));

        assertThrows(IllegalArgumentException.class, output::print);

        assertEquals("previous objectives\n", Files.readString(fun),
                "a complete FUN file next to an empty VAR file would look like a snapshot of no solution");
        assertEquals("previous variables\n", Files.readString(var));
    }
}

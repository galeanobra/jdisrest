package es.unex.jdisrest.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The trace files {@link TraceWriter} writes for every encoding, compared with the golden files in
 * {@code python/tests/data/traces/<shape>/}, which the tests of the Python trace tools read too, so
 * that the format is pinned on both sides: flat real, integer and binary solutions (binary
 * variables of 30, 5 and 1 bits, with leading zeros and an all-zero one) and composites of
 * integer, real and binary segments.
 *
 * <p>Each shape has three solutions with the objectives {@code (1.0E-4, 4.0)}, {@code (2.0, 1.0)}
 * and {@code (3.0, 3.0)}: the first two are the front, the third is dominated by the second.
 *
 * <p>The files are compared as text with their line separators normalized. jMetal's
 * {@code SolutionListOutput} and {@link CompositeSolutionListOutput} end each row with
 * {@code BufferedWriter.newLine()}, the platform's line separator, so on Windows they write CRLF,
 * while {@code .gitattributes} checks the golden files out with LF everywhere. Nothing else in the
 * files depends on the platform.
 */
class TraceWriterTest {

    /** The golden files, relative to the project folder, where Maven runs the tests. */
    private static final Path GOLDEN = Path.of("python", "tests", "data", "traces");

    private static final double[][] OBJECTIVES = {{1.0E-4, 4.0}, {2.0, 1.0}, {3.0, 3.0}};

    /** The 30-bit variable of the flat binary shape in each row: leading zeros, a mix, all ones. */
    private static final String[] BITS_30 = {
            "000000000000000000000000000101", "110010000000000000000000000001", "111111111111111111111111111111"};

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A binary variable from its bit string, bit 0 first, as {@link BinarySet#toString()} writes it. */
    static BinarySet bits(String text) {
        BinarySet set = new BinarySet(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '1') set.set(i);
        }
        return set;
    }

    static IntegerSolution integers(int constraints, int... values) {
        IntegerSolution s = new DefaultIntegerSolution(
                Collections.nCopies(values.length, Bounds.create(-10, 10)), 2, constraints);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static DoubleSolution reals(int constraints, double... values) {
        DoubleSolution s = new DefaultDoubleSolution(
                Collections.nCopies(values.length, Bounds.create(-10.0, 10.0)), 2, constraints);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static BinarySolution binary(int constraints, String... values) {
        BinarySolution s = new DefaultBinarySolution(Arrays.stream(values).map(String::length).toList(), 2, constraints);
        for (int i = 0; i < values.length; i++) s.variables().set(i, bits(values[i]));
        return s;
    }

    /** The three solutions of a shape, built by {@code solution}, with the objectives (and constraints) of each row. */
    static List<Solution<?>> rows(IntFunction<Solution<?>> solution, double... constraints) {
        List<Solution<?>> rows = new ArrayList<>();
        for (int row = 0; row < OBJECTIVES.length; row++) {
            Solution<?> s = solution.apply(row);
            s.objectives()[0] = OBJECTIVES[row][0];
            s.objectives()[1] = OBJECTIVES[row][1];
            if (constraints.length > 0) s.constraints()[0] = constraints[row];
            rows.add(s);
        }
        return rows;
    }

    static CompositeSolution composite(Solution<?>... segments) {
        return new CompositeSolution(List.of(segments));
    }

    /** Every golden shape, by the name of its folder. */
    static Map<String, List<Solution<?>>> shapes() {
        Map<String, List<Solution<?>>> shapes = new TreeMap<>();
        double[][] real = {{0.25, 1.0E-5}, {-3.5, 2.5}, {1.0, -0.125}};
        int[][] integer = {{3, -7}, {10, 2}, {0, 0}};
        shapes.put("double", rows(r -> reals(0, real[r])));
        shapes.put("int", rows(r -> integers(0, integer[r][0], integer[r][1], new int[]{0, -10, 5}[r])));
        shapes.put("binary", rows(r -> binary(0, BITS_30[r], new String[]{"00000", "00110", "11111"}[r],
                new String[]{"1", "0", "1"}[r])));
        double[] lastReal = {0.25, 1.0E-5, -3.5};
        double[] constraints = {0.0, -1.5, 0.0};
        shapes.put("int-double", rows(r -> composite(integers(1, integer[r]), reals(1, lastReal[r])), constraints));
        shapes.put("int-binary", rows(r -> composite(integers(0, integer[r]),
                binary(0, new String[]{"00000101", "11000000", "11111111"}[r], new String[]{"000", "010", "111"}[r]))));
        shapes.put("double-binary", rows(r -> composite(reals(0, real[r]),
                binary(0, new String[]{"00110", "10000", "11111"}[r]))));
        shapes.put("int-double-binary", rows(r -> composite(integers(1, integer[r]), reals(1, lastReal[r]),
                binary(1, new String[]{"101", "000", "111"}[r], new String[]{"00110", "00000", "11111"}[r])), constraints));
        shapes.put("binary-binary", rows(r -> composite(binary(0, new String[]{"0101", "0000", "1111"}[r]),
                binary(0, new String[]{"10", "01", "11"}[r], new String[]{"000000", "111000", "111111"}[r]))));
        return shapes;
    }

    static Stream<String> shapeNames() {
        return shapes().keySet().stream();
    }

    /** The text of a trace with its line separators normalized to LF. */
    static String text(Path file) throws IOException {
        return Files.readString(file).replace("\r\n", "\n");
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapeNames")
    void tracesOfEveryShapeMatchTheGoldenFiles(String shape, @TempDir Path dir) throws IOException {
        Path var = dir.resolve("aVAR_100.csv");
        Path fun = dir.resolve("aFUN_100.csv");

        TraceWriter.write(shapes().get(shape), var.toString(), fun.toString(), ",");

        assertEquals(text(GOLDEN.resolve(shape).resolve("aVAR_100.csv")), text(var), "the VAR rows of " + shape);
        assertEquals(text(GOLDEN.resolve(shape).resolve("aFUN_100.csv")), text(fun), "the FUN rows of " + shape);
    }

    @Test
    void everyGoldenShapeIsWrittenByThisTest() throws IOException {
        try (Stream<Path> folders = Files.list(GOLDEN)) {
            assertEquals(List.copyOf(shapes().keySet()),
                    folders.map(f -> f.getFileName().toString()).sorted().toList(),
                    "a golden folder without a shape here would pin nothing");
        }
    }

    @Test
    void onlyTheLineSeparatorDependsOnThePlatform(@TempDir Path dir) throws IOException {
        Path var = dir.resolve("VAR.csv");

        TraceWriter.write(shapes().get("int-binary"), var.toString(), dir.resolve("FUN.csv").toString(), ",");

        String written = Files.readString(var);
        assertEquals(text(GOLDEN.resolve("int-binary").resolve("aVAR_100.csv")).replace("\n", System.lineSeparator()),
                written, "every row ends with the platform's line separator, the last one too");
    }

    @Test
    void checkAcceptsEveryShapeAndNamesTheSegmentItCannotWrite() {
        shapes().forEach((shape, solutions) -> assertNull(TraceWriter.check(solutions.get(0)), shape));

        CompositeSolution nested = composite(integers(0, 1), composite(integers(0, 2)));

        assertEquals("segment 1: CompositeSolution found where a flat solution was expected: "
                        + "nested CompositeSolution segments are not supported",
                TraceWriter.check(nested));
    }

    @Test
    void checkRejectsAFlatBinaryVariableOfNoBits() {
        BinarySolution flat = binary(0, "101", "0", "00110");
        flat.variables().set(1, new BinarySet(0));

        assertEquals("variables[1] is a BinarySet of 0 bits; a binary variable needs at least one",
                TraceWriter.check(flat), "jMetal's writer would write 101,,00110, a row one variable short for any reader");
    }
}

package es.unex.jdisrest.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static es.unex.jdisrest.util.TraceWriterTest.binary;
import static es.unex.jdisrest.util.TraceWriterTest.composite;
import static es.unex.jdisrest.util.TraceWriterTest.integers;
import static es.unex.jdisrest.util.TraceWriterTest.reals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Trace rows read back into solutions: the golden traces of every shape, which
 * {@link TraceWriterTest} pins, and the traces {@link TraceWriter} writes on this platform; the
 * separators, the parts of a row that are ignored, the row limit, and every row that is refused,
 * with the line and the variable its message names.
 */
class TraceReaderTest {

    /** The golden traces, relative to the project folder, where Maven runs the tests. */
    private static final Path GOLDEN = Path.of("python", "tests", "data", "traces");

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A problem of two objectives whose solutions are built by {@code solution}; counts them. */
    static final class ShapeProblem implements Problem<Solution<?>> {
        private final Supplier<Solution<?>> solution;
        int created;

        ShapeProblem(Supplier<Solution<?>> solution) {
            this.solution = solution;
        }

        @Override public int numberOfVariables() { return solution.get().variables().size(); }
        @Override public int numberOfObjectives() { return 2; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return "ShapeProblem"; }
        @Override public Solution<?> evaluate(Solution<?> s) { return s; }

        @Override
        public Solution<?> createSolution() {
            created++;
            return solution.get();
        }
    }

    /**
     * The problem of a golden shape: its solutions are copies of the last solution of the shape,
     * so that the rows read into them differ from what they held, except the last one.
     */
    static ShapeProblem shapeProblem(String shape) {
        Solution<?> last = TraceWriterTest.shapes().get(shape).getLast();
        return new ShapeProblem(last::copy);
    }

    /** Two integers in [-10, 10], a real in [-10, 10] and two binary variables of 3 and 5 bits. */
    static ShapeProblem mixed() {
        return new ShapeProblem(() -> composite(integers(0, 0, 0), reals(0, 0.0), binary(0, "000", "00000")));
    }

    /** The flat vector of each solution, which tells their variables apart whatever their types. */
    static List<List<Number>> vectors(List<? extends Solution<?>> solutions) {
        return solutions.stream().map(SolutionVariables::flatten).toList();
    }

    static Path write(Path dir, String text) throws IOException {
        return Files.writeString(dir.resolve("iVAR.csv"), text, UTF_8);
    }

    /** What reading {@code text} for {@code problem} throws. */
    static String refused(Path dir, Problem<Solution<?>> problem, String text) throws IOException {
        Path file = write(dir, text);
        return assertThrows(IllegalArgumentException.class, () -> TraceReader.read(problem, file)).getMessage();
    }

    // ── Rows of the traces ────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("es.unex.jdisrest.util.TraceWriterTest#shapeNames")
    void goldenTracesOfEveryShapeReadBackIntoTheirSolutions(String shape) throws IOException {
        List<Solution<?>> read = TraceReader.read(shapeProblem(shape), GOLDEN.resolve(shape).resolve("aVAR_100.csv"));

        assertEquals(vectors(TraceWriterTest.shapes().get(shape)), vectors(read),
                "the variables the traces of " + shape + " were written from");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("es.unex.jdisrest.util.TraceWriterTest#shapeNames")
    void tracesWrittenOnThisPlatformReadBackIntoTheirSolutions(String shape, @TempDir Path dir) throws IOException {
        List<Solution<?>> written = TraceWriterTest.shapes().get(shape);
        Path var = dir.resolve("VAR.csv");
        TraceWriter.write(written, var.toString(), dir.resolve("FUN.csv").toString(), ",");

        assertEquals(vectors(written), vectors(TraceReader.read(shapeProblem(shape), var)),
                "rows that end with " + (System.lineSeparator().equals("\n") ? "LF" : "CRLF"));
    }

    @Test
    void realVariablesReadBackBitForBit(@TempDir Path dir) throws IOException {
        double[] values = {0.1 + 0.2, Double.MIN_VALUE, -0.0, 1.0E300, 123456.789012345678, -1.0 / 3};
        List<Bounds<Double>> anyValue =
                Collections.nCopies(values.length, Bounds.create(-Double.MAX_VALUE, Double.MAX_VALUE));
        List<Solution<?>> written = List.of(reals(0, values));
        Path var = dir.resolve("VAR.csv");
        TraceWriter.write(written, var.toString(), dir.resolve("FUN.csv").toString(), ",");

        List<Solution<?>> read =
                TraceReader.read(new ShapeProblem(() -> new DefaultDoubleSolution(anyValue, 2, 0)), var);

        assertEquals(written.getFirst().variables(), read.getFirst().variables(),
                "Java writes the shortest decimal that gives the same double back, -0.0 included");
    }

    @Test
    void eachRowIsReadIntoANewSolutionOfTheProblem() throws IOException {
        ShapeProblem problem = shapeProblem("binary");
        Solution<?> template = problem.createSolution();

        List<Solution<?>> read = TraceReader.read(problem, GOLDEN.resolve("binary").resolve("aVAR_100.csv"));

        assertEquals(4, problem.created, "one solution of the problem per row, besides the template");
        assertEquals(3, read.size());
        for (Solution<?> solution : read) {
            BinarySolution bits = assertInstanceOf(BinarySolution.class, solution);
            assertEquals(List.of(30, 5, 1), bits.variables().stream().map(BinarySet::getBinarySetLength).toList(),
                    "each binary variable keeps the length of the problem's");
            assertNotSame(template, solution);
        }
    }

    // ── Form of a row ─────────────────────────────────────────────────────────

    @Test
    void tokensAreSeparatedByCommasWhitespaceOrBoth(@TempDir Path dir) throws IOException {
        Path file = write(dir, "3,-7,0.25,101,00110\n3 -7\t0.25  101 00110\n 3 , -7,\t0.25 101 ,00110 \n");

        List<Solution<?>> read = TraceReader.read(mixed(), file);

        List<Number> expected = List.of(3, -7, 0.25, 1, 0, 1, 0, 0, 1, 1, 0);
        assertEquals(List.of(expected, expected, expected), vectors(read));
    }

    @Test
    void objectivesConstraintsBlankLinesAndAByteOrderMarkAreIgnored(@TempDir Path dir) throws IOException {
        Path file = write(dir,
                "\uFEFF3 -7 0.25 101 00110,[1.0E-4  4.0],[0.0]\n\n   \n10 2 -3.5 000 11111,[2.0  1.0],[]\n");

        List<Solution<?>> read = TraceReader.read(mixed(), file);

        assertEquals(List.of(List.of(3, -7, 0.25, 1, 0, 1, 0, 0, 1, 1, 0),
                List.of(10, 2, -3.5, 0, 0, 0, 1, 1, 1, 1, 1)), vectors(read));
    }

    @Test
    void rowsEndingWithCrlfAreReadOnEveryPlatform(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> integers(0, 0, 0));

        assertEquals(List.of(List.of(1, 2), List.of(3, 4)),
                vectors(TraceReader.read(problem, write(dir, "1,2\r\n3,4\r\n"))),
                "the rows of traces written on Windows");
        assertEquals(dir.resolve("iVAR.csv") + " line 3: variables[1] = 11 is outside the bounds [-10, 10] of its "
                + "variable", refused(dir, problem, "1,2\r\n\r\n1,11\r\n"), "a CRLF ends one line, not two");
    }

    @Test
    void integersAndRealsMayBeWrittenEitherWay(@TempDir Path dir) throws IOException {
        Path file = write(dir, "3.0 -7.0000000001 -3 101 00110\n+3 1e1 .5 101 00110\n");

        List<Solution<?>> read = TraceReader.read(mixed(), file);

        // flatten keeps the types of the variables: Integers in the integer segment, Doubles in the real one.
        assertEquals(List.of(3, -7, -3.0, 1, 0, 1, 0, 0, 1, 1, 0), SolutionVariables.flatten(read.getFirst()),
                "an integral real is an integer within the tolerance of the wire, and an integer is a real");
        assertEquals(List.of(3, 10, 0.5, 1, 0, 1, 0, 0, 1, 1, 0), SolutionVariables.flatten(read.getLast()));
    }

    @Test
    void aLimitReadsOnlyTheFirstRows(@TempDir Path dir) throws IOException {
        Path file = write(dir, "1,2,3\n4,5,6\nnot,a,row\n");
        ShapeProblem problem = new ShapeProblem(() -> integers(0, 0, 0, 0));

        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6)), vectors(TraceReader.read(problem, file, 2)),
                "the row after the limit is neither read nor checked");
        assertEquals(List.of(List.of(1, 2, 3)), vectors(TraceReader.read(problem, file, 1)));
        assertEquals(List.of(), TraceReader.read(problem, file, 0));
        assertEquals("limit must be at least 0, got -1",
                assertThrows(IllegalArgumentException.class, () -> TraceReader.read(problem, file, -1)).getMessage());
    }

    @Test
    void aFileWithoutRowsGivesNoSolutions(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), TraceReader.read(mixed(), write(dir, "\n  \n")));
    }

    // ── Rows refused ──────────────────────────────────────────────────────────

    @Test
    void aRowOfAnotherNumberOfVariablesIsRefusedWithItsLine(@TempDir Path dir) throws IOException {
        String message = refused(dir, mixed(), "3 -7 0.25 101 00110\n\n3 -7 0.25 1 0 1 0 0 1 1 0\n");

        assertEquals(dir.resolve("iVAR.csv") + " line 3: 11 variables, but the solutions of ShapeProblem have 5",
                message, "a binary variable is one bit string, never one value per bit, and blank lines count");
        assertEquals(dir.resolve("iVAR.csv") + " line 1: 1 variable, but the solutions of ShapeProblem have 2",
                refused(dir, new ShapeProblem(() -> integers(0, 0, 0)), "1\n"));
    }

    @Test
    void aValueThatIsNotANumberIsRefused(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> reals(0, 0.0, 0.0));

        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[1] = NaN is not a number",
                refused(dir, problem, "0.5,NaN\n"));
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[0] = 0x10 is not a number",
                refused(dir, problem, "0x10,1\n"));
    }

    @Test
    void aRealBeyondTheRangeOfADoubleIsRefused(@TempDir Path dir) throws IOException {
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[0] = 1e400 is beyond the range of a double",
                refused(dir, new ShapeProblem(() -> reals(0, 0.0)), "1e400\n"), "the token as written, not Infinity");
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[1] = -1.5E309 is beyond the range of a double",
                refused(dir, new ShapeProblem(() -> integers(0, 0, 0)), "1,-1.5E309\n"));
    }

    @Test
    void anIntegerBeyondTheRangeOfALongKeepsItsDigits(@TempDir Path dir) throws IOException {
        String digits = "123456789012345678901234567890";
        List<Bounds<Double>> anyValue = List.of(Bounds.create(-Double.MAX_VALUE, Double.MAX_VALUE));
        ShapeProblem real = new ShapeProblem(() -> new DefaultDoubleSolution(anyValue, 2, 0));

        List<Solution<?>> read = TraceReader.read(real, write(dir, digits + "\n"));

        assertEquals(List.of(1.2345678901234568E29), read.getFirst().variables(),
                "a real variable takes the double nearest to the integer");
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[0] overflows int: " + digits,
                refused(dir, new ShapeProblem(() -> integers(0, 0, 0)), digits + ",1\n"),
                "an integer variable is refused with the integer as written");
    }

    @Test
    void anIntegerVariableRefusesWhatIsNotAnIntOfTheWire(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> integers(0, 0, 0));

        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[1] = 2.5 is not an integer but the destination "
                + "variable is integer-encoded", refused(dir, problem, "1,2.5\n"));
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[0] overflows int: 3000000000",
                refused(dir, problem, "3000000000,1\n"));
    }

    @Test
    void aValueOutsideTheBoundsOfItsVariableIsRefused(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> integers(0, 0, 0));

        assertEquals(dir.resolve("iVAR.csv") + " line 2: variables[1] = 11 is outside the bounds [-10, 10] of its "
                + "variable", refused(dir, problem, "1,2\n1,11\n"));
    }

    @Test
    void aBitStringOfAnotherLengthIsRefused(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> binary(0, "000", "00000"));

        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[1] = 0110 has 4 bits but its variable has 5",
                refused(dir, problem, "101,0110\n"));
    }

    @Test
    void aTokenThatIsNotABitStringIsRefusedForABinaryVariable(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> binary(0, "000", "0"));

        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[0] = 0120 is not a bit string but its variable is "
                + "binary", refused(dir, problem, "0120,1\n"));
        assertEquals(dir.resolve("iVAR.csv") + " line 1: variables[1] = 1.0 is not a bit string but its variable is "
                + "binary", refused(dir, problem, "101,1.0\n"));
    }

    @Test
    void aValueOfACompositeIsNamedWithinItsSegment(@TempDir Path dir) throws IOException {
        assertEquals(dir.resolve("iVAR.csv") + " line 1: segment 2: variables[1] = 0011 has 4 bits but its variable "
                + "has 5", refused(dir, mixed(), "3 -7 0.25 101 0011\n"));
        assertEquals(dir.resolve("iVAR.csv") + " line 1: segment 1: variables[0] = 10.5 is outside the bounds "
                + "[-10.0, 10.0] of its variable", refused(dir, mixed(), "3 -7 10.5 101 00110\n"));
    }

    @Test
    void aProblemWhoseSolutionsCannotBeReadIsRefusedBeforeItsRows(@TempDir Path dir) throws IOException {
        ShapeProblem problem = new ShapeProblem(() -> composite(integers(0, 0), composite(integers(0, 0))));

        assertEquals("CompositeSolution found where a flat solution was expected: nested CompositeSolution segments "
                + "are not supported", refused(dir, problem, "1 2\n"));
    }

    @Test
    void aMissingFileIsAnIOException(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> TraceReader.read(mixed(), dir.resolve("iVAR.csv")));
    }

    // ── Tokens ────────────────────────────────────────────────────────────────

    @Test
    void tokensAreTheTextBeforeTheObjectives() {
        assertEquals(List.of("3", "-7", "101"), TraceReader.tokens("3 -7 101,[1.0  2.0],[0.0]"));
        assertEquals(List.of("0.25", "1.0E-5"), TraceReader.tokens("0.25,1.0E-5"));
        assertEquals(List.of("1", "2"), TraceReader.tokens(",1,,2,"), "commas in a row are one separator");
        assertEquals(List.of(), TraceReader.tokens(",[1.0],[]"));
    }
}

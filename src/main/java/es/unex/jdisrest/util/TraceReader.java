package es.unex.jdisrest.util;

import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the VAR rows of traces back into solutions of a problem: the inverse of
 * {@link TraceWriter}, with which the warm start
 * ({@link es.unex.jdisrest.distributed.WarmStart#initialPopulation WarmStart.initialPopulation})
 * starts a run from the variables that another run of the same problem wrote.
 *
 * <p>A row holds one solution, with one token per jMetal variable, in the order of the variables
 * and segment after segment for a composite, as the traces write it:
 * <ul>
 *   <li>an integer variable: an integer ({@code 3}, {@code -7}), or a real within
 *       {@link SolutionVariables#INTEGRALITY_TOLERANCE} of one ({@code 3.0}), as on the wire;</li>
 *   <li>a real variable: a decimal number of finite value ({@code 0.25}, {@code 1.0E-5},
 *       {@code -3});</li>
 *   <li>a binary variable: its bit string, bit 0 first, with as many bits as the variable has
 *       ({@code 00110} for a variable of 5 bits).</li>
 * </ul>
 * The tokens are separated by commas, whitespace or both, so the rows of flat solutions
 * ({@code 0.25,1.0E-5}, {@code 3,-7}, {@code 101,00110}) and those of composites
 * ({@code 3 -7 0.25 101 00110,[1.0  2.0],[0.0]}) are both read. The text from the first
 * {@code ,[} on, the objectives and the constraints of a composite row, is ignored, and so are
 * blank lines. A binary variable is one token, never one value per bit as on the wire, so the
 * number of tokens of a row is always the number of variables of a solution.
 *
 * <p>Each row is read into a new {@link Problem#createSolution()}, whose variables are all
 * replaced: a binary variable by a new {@link BinarySet} of the length the variable has. The
 * values are checked as the repaired variables of a result are, by
 * {@link SolutionVariables#convert} and {@link SolutionVariables#checkBounds}. The objectives and
 * the constraints are left as the new solution has them, since the solutions are still to be
 * evaluated.
 *
 * <p>A row that cannot be read throws {@link IllegalArgumentException}. Its message names the
 * file and the line, counted from 1 with the blank lines, and gives the position of a value as
 * the messages of the trace writer do: {@code variables[i]} counts the variables of a flat
 * solution, or those of the segment that the message names first for a composite, for instance
 * {@code iVAR.csv line 3: segment 1: variables[0] = 0110 has 4 bits but its variable has 5}.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class TraceReader {

    /** An integer as Java and Python write one: an optional sign and digits. */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    /** A decimal number: an optional sign, digits with an optional point, an optional exponent. */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** A bit string. */
    private static final Pattern BITS = Pattern.compile("[01]+");

    /** What separates two tokens: commas, whitespace or both. */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,]+");

    /** The byte order mark that some editors write at the start of a UTF-8 file. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private TraceReader() {}

    /**
     * Reads every row of {@code file} into a solution of {@code problem}.
     *
     * @param problem the problem whose solutions the rows hold
     * @param file    a VAR trace, or a file of rows of the same form
     * @param <S>     the solution type
     * @return a new list with one new solution per row, in the order of the rows
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if a row cannot be read (see the class description), or
     *                                  the problem's solutions are not of a type
     *                                  {@link SolutionVariables} supports
     */
    public static <S extends Solution<?>> List<S> read(Problem<S> problem, Path file) throws IOException {
        return read(problem, file, Integer.MAX_VALUE);
    }

    /**
     * Reads the first {@code limit} rows of {@code file} into solutions of {@code problem}. The
     * rows after them are neither read nor checked.
     *
     * @param problem the problem whose solutions the rows hold
     * @param file    a VAR trace, or a file of rows of the same form
     * @param limit   the largest number of solutions to read, at least 0
     * @param <S>     the solution type
     * @return a new list with one new solution per row read, in the order of the rows: fewer than
     *         {@code limit} when the file has fewer rows
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if {@code limit} is negative, a row cannot be read (see the
     *                                  class description), or the problem's solutions are not of
     *                                  a type {@link SolutionVariables} supports
     */
    public static <S extends Solution<?>> List<S> read(Problem<S> problem, Path file, int limit) throws IOException {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be at least 0, got " + limit);
        }
        List<S> solutions = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int number = 0;
            String line;
            while (solutions.size() < limit && (line = in.readLine()) != null) {
                number++;
                if (number == 1 && !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK) {
                    line = line.substring(1);
                }
                if (line.isBlank()) {
                    continue;
                }
                S solution = problem.createSolution();
                SolutionVariables.VectorLayout layout = SolutionVariables.layoutOf(solution);
                List<Number> values;
                try {
                    values = values(solution, layout, tokens(line), problem.name());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file + " line " + number + ": " + e.getMessage(), e);
                }
                SolutionVariables.apply(solution, values);
                solutions.add(solution);
            }
        }
        return solutions;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** The tokens of a row: the text before its first {@code ,[}, split at commas and whitespace. */
    static List<String> tokens(String row) {
        int end = row.indexOf(",[");
        String variables = SEPARATOR.matcher(end < 0 ? row : row.substring(0, end)).replaceAll(" ").strip();
        return variables.isEmpty() ? List.of() : List.of(variables.split(" "));
    }

    /**
     * The flat vector of a row for {@code solution}, converted and checked against the bounds.
     *
     * @param solution a new solution of the problem
     * @param layout   its layout
     * @param tokens   the tokens of the row
     * @param problem  the name of the problem, for the message of a row of another length
     * @return the converted vector, laid out as {@link SolutionVariables#flatten} lays it out
     * @throws IllegalArgumentException if a token cannot be read into its variable, or the row
     *                                  does not have one token per variable of the solution
     */
    private static List<Number> values(Solution<?> solution, SolutionVariables.VectorLayout layout, List<String> tokens,
                                       String problem) {
        int variables = 0;
        for (SolutionVariables.VectorLayout.Segment segment : layout.segments()) {
            variables += variablesOf(segment);
        }
        if (tokens.size() != variables) {
            throw new IllegalArgumentException(tokens.size() + " variable" + (tokens.size() == 1 ? "" : "s")
                + ", but the solutions of " + problem + " have " + variables);
        }
        List<? extends Solution<?>> components = solution instanceof CompositeSolution composite
            ? composite.variables() : List.of(solution);
        List<Number> values = new ArrayList<>(layout.width());
        int next = 0;
        for (int k = 0; k < components.size(); k++) {
            SolutionVariables.VectorLayout.Segment segment = layout.segments().get(k);
            List<String> segmentTokens = tokens.subList(next, next + variablesOf(segment));
            next += segmentTokens.size();
            try {
                values.addAll(segmentValues(components.get(k), segment, segmentTokens));
            } catch (IllegalArgumentException e) {
                if (!layout.composite()) throw e;
                throw new IllegalArgumentException("segment " + k + ": " + e.getMessage(), e);
            }
        }
        return values;
    }

    /** The number of jMetal variables of a segment, the number of its tokens in a row. */
    private static int variablesOf(SolutionVariables.VectorLayout.Segment segment) {
        return switch (segment.encoding()) {
            case INT, DOUBLE -> segment.width();
            case BINARY -> segment.bitsPerVariable().size();
        };
    }

    /**
     * The values of one flat solution or segment, from its tokens: the integers or reals,
     * converted and checked against the bounds of their variables, or the bits of each bit string.
     *
     * @throws IllegalArgumentException if a token cannot be read into its variable, with its
     *                                  position within the segment
     */
    private static List<Number> segmentValues(Solution<?> segment, SolutionVariables.VectorLayout.Segment layout,
                                              List<String> tokens) {
        return switch (layout.encoding()) {
            case INT, DOUBLE -> {
                List<Number> numbers = new ArrayList<>(tokens.size());
                for (int i = 0; i < tokens.size(); i++) {
                    numbers.add(number(tokens.get(i), i));
                }
                // One value per variable here, so the positions in their messages are the variables'.
                List<Number> converted = SolutionVariables.convert(segment, numbers);
                String outside = SolutionVariables.checkBounds(segment, converted);
                if (outside != null) {
                    throw new IllegalArgumentException(outside);
                }
                yield converted;
            }
            case BINARY -> {
                List<Number> bits = new ArrayList<>(layout.width());
                for (int i = 0; i < tokens.size(); i++) {
                    String token = tokens.get(i);
                    int length = layout.bitsPerVariable().get(i);
                    if (!BITS.matcher(token).matches()) {
                        throw new IllegalArgumentException("variables[" + i + "] = " + token
                            + " is not a bit string but its variable is binary");
                    }
                    if (token.length() != length) {
                        throw new IllegalArgumentException("variables[" + i + "] = " + token + " has "
                            + token.length() + " bits but its variable has " + length);
                    }
                    for (int bit = 0; bit < length; bit++) {
                        bits.add(token.charAt(bit) == '1' ? 1 : 0);
                    }
                }
                yield bits;
            }
        };
    }

    /**
     * The number a token writes: a {@link BigInteger} for an integer, so that no digit is lost
     * before the conversion checks its range, a {@link Double} for any other decimal number.
     *
     * @throws IllegalArgumentException if the token is not a decimal number, or is beyond the
     *                                  range of a double
     */
    private static Number number(String token, int position) {
        if (INTEGER.matcher(token).matches()) return new BigInteger(token);
        if (!DECIMAL.matcher(token).matches()) {
            throw new IllegalArgumentException("variables[" + position + "] = " + token + " is not a number");
        }
        double value = Double.parseDouble(token);
        if (Double.isInfinite(value)) {
            // Refused here, with the token as written: the conversion would only see Infinity.
            throw new IllegalArgumentException("variables[" + position + "] = " + token
                + " is beyond the range of a double");
        }
        return value;
    }
}

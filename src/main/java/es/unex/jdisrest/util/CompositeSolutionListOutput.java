package es.unex.jdisrest.util;

import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.fileoutput.FileOutputContext;
import org.uma.jmetal.util.fileoutput.impl.DefaultFileOutputContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

/**
 * Output writer for {@link CompositeSolution}s with any number of segments of integer, real or
 * binary variables. The standard jMetal {@code SolutionListOutput} does not serialize composite
 * variables correctly (it falls back to {@code Solution.toString()} which prints the inner
 * components' metadata), so this class writes the variables of all segments in declaration order
 * as a single space-separated row plus the outer composite's objectives/constraints.
 *
 * <p>VAR row format: {@code "v0 v1 ... vN-1,[obj0  obj1 ...],[con0  con1 ...]"}, with one token
 * per jMetal variable: an integer or real variable as Java prints it ({@code 3}, {@code 0.25},
 * {@code 1.0E-5}), a binary variable ({@link BinarySet}) as its bit string, bit 0 leftmost and one
 * character per bit of its length ({@code 00110}), which is how jMetal's {@code SolutionListOutput}
 * writes the variables of a flat {@link BinarySolution}. The tokens are always separated by spaces:
 * the separator of the file context only separates the objectives of the FUN rows. The row of a
 * solution therefore does not depend on how its variables travel to the workers, and a variable
 * reads the same in a flat row and in a composite one.
 * <p>FUN row format: standard jMetal — separator-joined objective values.
 *
 * <p>Flat solutions in the list are written the same way when this class is used directly;
 * {@link TraceWriter} only uses it for lists of composites, and writes lists of flat solutions with
 * jMetal's {@code SolutionListOutput} (separator-joined variables, nothing else).
 *
 * <p>{@link #print()} formats every VAR row before it opens either file, so a solution it cannot
 * write leaves both files as they were, instead of a complete FUN file next to an empty VAR file.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class CompositeSolutionListOutput {
    private FileOutputContext varFileContext;
    private FileOutputContext funFileContext;
    private final List<? extends Solution<?>> solutionList;

    public CompositeSolutionListOutput(List<? extends Solution<?>> solutionList) {
        this.varFileContext = new DefaultFileOutputContext("VAR");
        this.funFileContext = new DefaultFileOutputContext("FUN");
        this.solutionList = solutionList;
    }

    public CompositeSolutionListOutput setVarFileOutputContext(FileOutputContext fileContext) {
        this.varFileContext = fileContext;
        return this;
    }

    public CompositeSolutionListOutput setFunFileOutputContext(FileOutputContext fileContext) {
        this.funFileContext = fileContext;
        return this;
    }

    /**
     * Writes the FUN file, then the VAR file. Every VAR row is formatted first, so a solution that
     * cannot be written ({@link #formatSolution}) throws before either file is opened.
     *
     * @throws IllegalArgumentException if a solution cannot be written, see {@link #formatSolution}
     * @throws JMetalException          if a file cannot be written
     */
    public void print() {
        List<String> rows = new ArrayList<>(solutionList.size());
        for (Solution<?> solution : solutionList) {
            rows.add(formatSolution(solution));
        }
        printObjectivesToFile(funFileContext, solutionList);
        printVariablesToFile(varFileContext, rows);
    }

    private void printVariablesToFile(FileOutputContext context, List<String> rows) {
        try (BufferedWriter bw = context.getFileWriter()) {
            for (String row : rows) {
                bw.write(row);
                bw.newLine();
            }
        } catch (IOException e) {
            throw new JMetalException("Error writing variables: ", e);
        }
    }

    private void printObjectivesToFile(FileOutputContext context, List<? extends Solution<?>> solutionList) {
        try (BufferedWriter bw = context.getFileWriter()) {
            if (!solutionList.isEmpty()) {
                int numberOfObjectives = solutionList.get(0).objectives().length;
                for (Solution<?> s : solutionList) {
                    for (int j = 0; j < numberOfObjectives - 1; j++) {
                        bw.write(s.objectives()[j] + context.getSeparator());
                    }
                    bw.write(String.valueOf(s.objectives()[numberOfObjectives - 1]));
                    bw.newLine();
                }
            }
        } catch (IOException e) {
            throw new JMetalException("Error writing objectives: ", e);
        }
    }

    /**
     * Formats one solution as a VAR row: one token per jMetal variable (segments in declaration
     * order), separated by spaces, then the objectives and constraints arrays. An integer or real
     * variable is written as Java prints it, a binary one as its bit string (see the class
     * description).
     *
     * <p>A flat solution or segment is binary when it is a {@link BinarySolution}, or when its
     * first variable is a {@link BinarySet}; its variables must then all be {@link BinarySet}s of
     * at least one bit, since a variable of no bits would leave an empty token that a reader cannot
     * tell from a missing one. Any other flat solution or segment is read through
     * {@link SolutionVariables#flatten}, with its rules and messages.
     *
     * @param solution a composite or flat solution
     * @return the VAR row
     * @throws IllegalArgumentException if the solution (or one segment) is neither binary nor of an
     *                                  encoding {@link SolutionVariables} supports, or a variable is
     *                                  {@code null}, of another type or a binary variable of no
     *                                  bits; for a composite, the message names the segment first
     *                                  and counts the positions of the variables within it
     */
    public static String formatSolution(Solution<?> solution) {
        StringJoiner variables = new StringJoiner(" ");
        if (solution instanceof CompositeSolution composite) {
            List<Solution<?>> segments = composite.variables();
            for (int k = 0; k < segments.size(); k++) {
                try {
                    addVariables(variables, segments.get(k));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("segment " + k + ": " + e.getMessage(), e);
                }
            }
        } else {
            addVariables(variables, solution);
        }
        return variables
                + "," + Arrays.toString(solution.objectives()).replace(",", " ")
                + "," + Arrays.toString(solution.constraints()).replace(",", " ");
    }

    /**
     * Adds one token per variable of a flat solution or segment to {@code row}: the bit string of
     * each {@link BinarySet} of a binary one, the text of each value of any other.
     *
     * @param row      the tokens of the row so far
     * @param solution the flat solution or segment to write
     * @throws IllegalArgumentException as {@link #formatSolution}, with positions within
     *                                  {@code solution}
     */
    private static void addVariables(StringJoiner row, Solution<?> solution) {
        if (!isBinary(solution)) {
            SolutionVariables.encodingOf(solution);  // rejects a nested composite, which flatten would accept
            for (Number value : SolutionVariables.flatten(solution)) {
                row.add(String.valueOf(value));
            }
            return;
        }
        List<?> variables = solution.variables();
        for (int i = 0; i < variables.size(); i++) {
            Object v = variables.get(i);
            if (v == null) {
                throw new IllegalArgumentException("variables[" + i + "] is null");
            }
            if (!(v instanceof BinarySet bits)) {
                throw new IllegalArgumentException("variables[" + i + "] = " + v + " is a "
                        + v.getClass().getSimpleName() + " but its segment is binary-encoded");
            }
            if (bits.getBinarySetLength() < 1) {
                throw new IllegalArgumentException("variables[" + i + "] is a BinarySet of "
                        + bits.getBinarySetLength() + " bits; a binary variable needs at least one");
            }
            row.add(bits.toString());
        }
    }

    /**
     * Whether a flat solution or segment is written as binary: it is a {@link BinarySolution}, or
     * its first variable is a {@link BinarySet}.
     */
    static boolean isBinary(Solution<?> solution) {
        return solution instanceof BinarySolution
                || (!solution.variables().isEmpty() && solution.variables().get(0) instanceof BinarySet);
    }
}

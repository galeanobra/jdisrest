package es.unex.jdisrest.util;

import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.fileoutput.SolutionListOutput;
import org.uma.jmetal.util.fileoutput.impl.DefaultFileOutputContext;

import java.util.List;

/**
 * Convenience writer used by per-generation trace output in jdisrest's
 * algorithms. Picks {@link CompositeSolutionListOutput} when the population is
 * composed of {@link CompositeSolution}s (so variables are serialized
 * correctly), and falls back to jMetal's {@link SolutionListOutput} otherwise.
 *
 * <p>Either way a VAR row holds one token per jMetal variable, an integer or real one as Java
 * prints it and a binary one as its bit string, bit 0 leftmost:
 * <ul>
 *   <li>flat solutions: the variables joined by the separator and nothing else, for instance
 *       {@code 0.25,1.0E-5}, {@code 3,-7} or {@code 101,00110};</li>
 *   <li>composites: the variables of every segment joined by spaces, then the objectives and the
 *       constraints, for instance {@code 3 -7 0.25 101 00110,[1.0  2.0],[0.0]}.</li>
 * </ul>
 * A FUN row holds the objectives joined by the separator. The rows end with the platform's line
 * separator, as jMetal writes them.
 */
public final class TraceWriter {
    private TraceWriter() {}

    /** Writes a (varPath, funPath) pair for the given snapshot using the proper writer. */
    public static void write(List<? extends Solution<?>> snapshot, String varPath, String funPath, String separator) {
        if (!snapshot.isEmpty() && snapshot.get(0) instanceof CompositeSolution) {
            new CompositeSolutionListOutput(snapshot)
                .setVarFileOutputContext(new DefaultFileOutputContext(varPath, separator))
                .setFunFileOutputContext(new DefaultFileOutputContext(funPath, separator))
                .print();
        } else {
            new SolutionListOutput(snapshot)
                .setVarFileOutputContext(new DefaultFileOutputContext(varPath, separator))
                .setFunFileOutputContext(new DefaultFileOutputContext(funPath, separator))
                .print();
        }
    }

    /**
     * Tells whether the traces {@link #write} writes can hold solutions like {@code solution},
     * without writing anything. A composite can be traced when
     * {@link CompositeSolutionListOutput#formatSolution} formats it, and a flat binary solution (a
     * {@code BinarySolution}, or one whose first variable is a {@code BinarySet}) under the same
     * rules for its variables: jMetal's writer would write a variable of no bits as an empty
     * token, as in {@code 101,,00110}, which a reader cannot tell from a missing one. Any other
     * flat solution can, since jMetal's writer prints every variable with {@code String.valueOf}.
     * Call it on one solution of a problem before a run that writes traces, so that a problem
     * whose solutions cannot be traced fails before its first evaluation rather than at its first
     * snapshot, or leaves rows no reader can read.
     *
     * @param solution a solution of the problem, for instance the first of its initial population
     * @return {@code null} if its traces can be written, otherwise the reason
     */
    public static String check(Solution<?> solution) {
        if (!(solution instanceof CompositeSolution) && !CompositeSolutionListOutput.isBinary(solution)) {
            return null;
        }
        try {
            CompositeSolutionListOutput.formatSolution(solution);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}

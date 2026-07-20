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
}

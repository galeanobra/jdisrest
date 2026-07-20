package es.unex.jdisrest.local;

import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.evaluator.SolutionListEvaluator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;

/**
 * jMetal {@link SolutionListEvaluator} that delegates each solution evaluation
 * to a {@link PythonProcessEvaluator} child process. Per-solution mode: one
 * request/response per Solution. Drop-in replacement for jMetal's
 * {@code SequentialSolutionListEvaluator} when the problem evaluation lives
 * outside the JVM.
 *
 * <p>The decision-vector extractor is injected so this class stays
 * problem-agnostic; vRAN-specific flattening lives at the call site.
 *
 * <p>If the Python evaluator returns a {@code variables} array (Lamarckian
 * repair / local search), the new decision is written back into the solution
 * before returning so that the master-side population stays consistent with
 * the reported objectives.
 *
 * @param <S> jMetal solution type (typically {@code CompositeSolution} for vRAN)
 */
public final class PythonSolutionListEvaluator<S extends Solution<?>> implements SolutionListEvaluator<S> {

    private final PythonProcessEvaluator python;
    private final Function<S, int[]> decisionExtractor;

    public PythonSolutionListEvaluator(PythonProcessEvaluator python, Function<S, int[]> decisionExtractor) {
        this.python = python;
        this.decisionExtractor = decisionExtractor;
    }

    @Override
    public List<S> evaluate(List<S> solutionList, Problem<S> problem) {
        for (S sol : solutionList) {
            try {
                PythonProcessEvaluator.Result r = python.evaluate(decisionExtractor.apply(sol));
                if (r.variables != null && r.variables.length > 0) {
                    applyVariables(sol, r.variables);
                }
                int oN = Math.min(r.objectives.length, sol.objectives().length);
                for (int i = 0; i < oN; i++) sol.objectives()[i] = r.objectives[i];
                int cN = Math.min(r.constraints.length, sol.constraints().length);
                for (int i = 0; i < cN; i++) sol.constraints()[i] = r.constraints[i];
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return solutionList;
    }

    @Override
    public void shutdown() {
        python.close();
    }

    /**
     * Writes a flat decision vector back into a solution, splitting by
     * component when the solution is a {@link CompositeSolution} (mirroring
     * the layout the master sent over the wire). Excess elements on either
     * side are silently ignored.
     */
    @SuppressWarnings("unchecked")
    private static void applyVariables(Solution<?> solution, int[] variables) {
        if (solution instanceof CompositeSolution composite) {
            int idx = 0;
            for (Object component : composite.variables()) {
                List<Integer> segVars = ((Solution<Integer>) component).variables();
                int n = segVars.size();
                for (int i = 0; i < n && idx < variables.length; i++, idx++) {
                    segVars.set(i, variables[idx]);
                }
            }
            return;
        }
        List<Integer> solVars = ((Solution<Integer>) solution).variables();
        for (int i = 0; i < solVars.size() && i < variables.length; i++) {
            solVars.set(i, variables[i]);
        }
    }
}

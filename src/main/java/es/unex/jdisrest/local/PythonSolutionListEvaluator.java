package es.unex.jdisrest.local;

import es.unex.jdisrest.util.SolutionVariables;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
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
 * <p>By default the decision vector sent to Python is
 * {@link SolutionVariables#flatten}, which handles {@code IntegerSolution},
 * {@code DoubleSolution} and {@code CompositeSolution} (segments concatenated
 * in declaration order). A custom extractor can be injected for other layouts.
 *
 * <p>If the Python evaluator returns a {@code variables} array (Lamarckian
 * repair / local search), the new decision is written back into the solution
 * before returning — converting each value to the type of the destination
 * variable — so the population stays consistent with the reported objectives.
 *
 * <p>The number of objectives and constraints returned must match the
 * solution's; a mismatch is reported as an {@link IllegalStateException}
 * instead of being silently truncated.
 *
 * @param <S> jMetal solution type
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class PythonSolutionListEvaluator<S extends Solution<?>> implements SolutionListEvaluator<S> {

    private final PythonProcessEvaluator python;
    private final Function<S, List<? extends Number>> decisionExtractor;

    /**
     * Evaluator using {@link SolutionVariables#flatten} as the decision extractor.
     *
     * @param python the Python child process
     */
    public PythonSolutionListEvaluator(PythonProcessEvaluator python) {
        this(python, SolutionVariables::flatten);
    }

    /**
     * Evaluator with a custom decision extractor.
     *
     * @param python            the Python child process
     * @param decisionExtractor maps a solution to the flat vector sent to Python
     */
    public PythonSolutionListEvaluator(PythonProcessEvaluator python,
                                       Function<S, List<? extends Number>> decisionExtractor) {
        this.python = python;
        this.decisionExtractor = decisionExtractor;
    }

    @Override
    public List<S> evaluate(List<S> solutionList, Problem<S> problem) {
        for (S sol : solutionList) {
            try {
                PythonProcessEvaluator.Result r = python.evaluate(decisionExtractor.apply(sol));
                if (r.objectives.length != sol.objectives().length) {
                    throw new IllegalStateException("Python evaluator returned " + r.objectives.length
                        + " objectives but the problem defines " + sol.objectives().length);
                }
                if (r.constraints.length != sol.constraints().length) {
                    throw new IllegalStateException("Python evaluator returned " + r.constraints.length
                        + " constraints but the problem defines " + sol.constraints().length);
                }
                if (r.variables != null && !r.variables.isEmpty()) {
                    SolutionVariables.apply(sol, r.variables);
                }
                System.arraycopy(r.objectives, 0, sol.objectives(), 0, r.objectives.length);
                System.arraycopy(r.constraints, 0, sol.constraints(), 0, r.constraints.length);
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
}

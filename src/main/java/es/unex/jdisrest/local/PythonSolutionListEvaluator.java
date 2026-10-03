package es.unex.jdisrest.local;

import es.unex.jdisrest.util.SolutionVariables;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.evaluator.SolutionListEvaluator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * jMetal {@link SolutionListEvaluator} that delegates each solution evaluation
 * to a {@link PythonProcessEvaluator} child process. Per-solution mode: one
 * request/response per Solution. Drop-in replacement for jMetal's
 * {@code SequentialSolutionListEvaluator} when the problem evaluation lives
 * outside the JVM; the {@code problem} argument of {@link #evaluate} is not
 * used ({@code Problem.evaluate} is never called).
 *
 * <p>By default the decision vector sent to Python is
 * {@link SolutionVariables#flatten}, which handles {@code IntegerSolution},
 * {@code DoubleSolution} and {@code CompositeSolution} (segments concatenated
 * in declaration order). A custom extractor can be injected for other layouts.
 *
 * <p>If the Python evaluator returns a non-empty {@code variables} array
 * (Lamarckian repair / local search), the new decision is written back into the
 * solution before the objectives — converting each value to the type of the
 * destination variable — so the population stays consistent with the reported
 * objectives. The write-back must use the layout the child received: by default
 * {@link SolutionVariables#apply}, which expects the {@code flatten} layout. A
 * custom extractor that reorders, subsets or transforms the variables needs the
 * matching applier (three-argument constructor); with the two-argument
 * constructor the returned vector is still applied in the {@code flatten}
 * layout — rejected when its length differs, written to the wrong variables when
 * it only has the same length.
 *
 * <p>Before writing, the default write-back also rejects a repaired vector with a
 * value outside the bounds of its variable ({@link SolutionVariables#checkBounds}:
 * inclusive, without tolerance), as the REST master rejects such a result with
 * {@code 422}: a repair must clip exactly to the bounds, or the population, the
 * archive and the result would hold values the operators never produce. A custom
 * applier should make the same check.
 *
 * <p>The number of objectives and constraints returned must match the
 * solution's; a mismatch is reported as an {@link IllegalStateException}
 * instead of being silently truncated.
 *
 * <p>Fail-fast: the first failure — an {@code error} reply or a protocol problem
 * ({@link UncheckedIOException}), a decision vector that cannot be sent (a
 * {@code null}, {@code NaN} or infinite variable, an {@link IllegalArgumentException}
 * from {@link PythonProcessEvaluator#evaluate(List)}), a count mismatch
 * ({@link IllegalStateException}), a repaired vector the applier rejects (for the
 * default one an {@link IllegalArgumentException}) — ends the whole {@link #evaluate}
 * call, and with it the algorithm's run. The solutions before it in the list are already
 * updated; the failing one keeps its old objectives, since counts are checked and
 * the vector applied before they are written (the default applier
 * validates the whole vector before writing any of it). There is no retry or penalty,
 * unlike REST mode, where a failed evaluation is requeued; wrap the
 * {@link PythonProcessEvaluator} in a custom evaluator to get one.
 *
 * <p>Lifecycle: {@link #shutdown()} closes the child. Neither jMetal's NSGA-II
 * nor {@link es.unex.jdisrest.local.algorithms.NSGAII} calls it, so the caller
 * must (e.g. in a {@code finally} block after {@code run()}); until then the
 * child stays alive.
 *
 * <p>{@link SolutionListEvaluator} extends {@link java.io.Serializable}, but this
 * evaluator holds a live child process: serialising it throws
 * {@link java.io.NotSerializableException}.
 *
 * @param <S> jMetal solution type
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class PythonSolutionListEvaluator<S extends Solution<?>> implements SolutionListEvaluator<S> {

    private final PythonProcessEvaluator python;
    private final Function<S, List<? extends Number>> decisionExtractor;
    private final BiConsumer<S, List<Number>> variablesApplier;

    /**
     * Evaluator using {@link SolutionVariables#flatten} as the decision extractor
     * and {@link SolutionVariables#apply}, after a bounds check, to write repaired
     * variables back (see the class description).
     *
     * @param python the Python child process
     */
    public PythonSolutionListEvaluator(PythonProcessEvaluator python) {
        this(python, SolutionVariables::flatten);
    }

    /**
     * Evaluator with a custom decision extractor. Repaired variables returned by
     * the child are written back with {@link SolutionVariables#apply}, after a
     * bounds check, i.e. in the {@link SolutionVariables#flatten} layout: use
     * {@link #PythonSolutionListEvaluator(PythonProcessEvaluator, Function, BiConsumer)}
     * when the extractor produces another layout and the child may return
     * {@code variables}.
     *
     * @param python            the Python child process
     * @param decisionExtractor maps a solution to the flat vector sent to Python
     */
    public PythonSolutionListEvaluator(PythonProcessEvaluator python,
                                       Function<S, List<? extends Number>> decisionExtractor) {
        this(python, decisionExtractor, PythonSolutionListEvaluator::applyWithinBounds);
    }

    /**
     * Evaluator with a custom decision extractor and the matching applier.
     *
     * @param python            the Python child process
     * @param decisionExtractor maps a solution to the flat vector sent to Python
     * @param variablesApplier  writes a non-empty {@code variables} vector returned by
     *                          the child — laid out as {@code decisionExtractor}
     *                          produced it, elements of any {@link Number} type —
     *                          into the solution; it should validate the whole
     *                          vector, bounds included, before writing and throw to
     *                          reject it
     */
    public PythonSolutionListEvaluator(PythonProcessEvaluator python,
                                       Function<S, List<? extends Number>> decisionExtractor,
                                       BiConsumer<S, List<Number>> variablesApplier) {
        this.python = Objects.requireNonNull(python, "python must not be null");
        this.decisionExtractor = Objects.requireNonNull(decisionExtractor, "decisionExtractor must not be null");
        this.variablesApplier = Objects.requireNonNull(variablesApplier, "variablesApplier must not be null");
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
                    variablesApplier.accept(sol, r.variables);
                }
                System.arraycopy(r.objectives, 0, sol.objectives(), 0, r.objectives.length);
                System.arraycopy(r.constraints, 0, sol.constraints(), 0, r.constraints.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return solutionList;
    }

    /**
     * The default write-back of repaired variables: converts the vector to the types of the
     * solution's variables ({@link SolutionVariables#convert}), checks it against their bounds
     * ({@link SolutionVariables#checkBounds}) and only then writes it
     * ({@link SolutionVariables#apply}), so a rejected vector leaves the solution untouched.
     *
     * @param solution the solution to overwrite
     * @param values   the repaired vector, in the {@link SolutionVariables#flatten} layout
     * @throws IllegalArgumentException if the vector does not fit the solution (see
     *                                  {@link SolutionVariables#convert}) or a value lies outside
     *                                  the bounds of its variable
     */
    static void applyWithinBounds(Solution<?> solution, List<? extends Number> values) {
        String outside = SolutionVariables.checkBounds(solution, SolutionVariables.convert(solution, values));
        if (outside != null) {
            throw new IllegalArgumentException(outside);
        }
        SolutionVariables.apply(solution, values);
    }

    /** Closes the Python child process (see {@link PythonProcessEvaluator#close()}). */
    @Override
    public void shutdown() {
        python.close();
    }
}

package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.CompositeCrossover;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.util.errorchecking.Check;

import java.util.ArrayList;
import java.util.List;

/**
 * Defensive wrapper around jMetal's {@link CompositeCrossover}.
 *
 * <p>jMetal's {@code NPointCrossover} (the basis for {@code TwoPointCrossover})
 * returns the input parents <em>as-is</em> when its probability check fails,
 * instead of returning copies. {@link CompositeCrossover} then wraps those
 * parent segment references straight into the new offspring's variable list,
 * so the offspring's segments alias the parents'. A {@code CompositeMutation}
 * applied to that offspring mutates each segment in place — corrupting the
 * parent's genes while its cached objectives are left stale. The result is a
 * latent aliasing bug that breaks the invariant
 * "VAR row matches FUN row" whenever crossover bypasses for at least one
 * segment.
 *
 * <p>This wrapper deep-copies the parents up front and delegates, so any
 * bypass branch returns references to local copies rather than to the
 * population's solutions. Downstream mutation only ever modifies copies.
 * The cost is one extra {@code CompositeSolution.copy()} per parent per
 * crossover invocation, negligible against the evaluation cost.
 *
 * <h2>Where it matters</h2>
 * <p>The hazard needs code that mutates the crossover's output in place. The steady-state
 * algorithms of jdisrest copy the offspring before mutating them, so there the wrapper is only
 * a second line of defence. It matters for jMetal's own generational loops (their
 * {@code reproduction} mutates the crossover output in place), for a custom
 * {@code GenerationalAlgorithm.evolution} and for a {@code createNewTask} override that does
 * the same. It covers {@link CompositeSolution} only: a flat solution crossed with
 * {@code NPointCrossover} or {@code TwoPointCrossover} is not protected by it.
 *
 * <h2>Replacing CompositeCrossover</h2>
 * <p>It takes the same constructor argument as {@link CompositeCrossover} and can replace it
 * wherever a {@code CrossoverOperator<CompositeSolution>} is expected. It is not a subclass:
 * code that tests {@code instanceof CompositeCrossover} does not recognise it, and it offers
 * {@link #getOperators()} itself. Unlike {@link CompositeCrossover}, which only fails with an
 * {@link IndexOutOfBoundsException} on the first crossover when there are fewer operators than
 * segments and silently ignores extra ones, it rejects parents whose number of segments differs
 * from the number of operators with a message that gives both, and it rejects a {@code null}
 * parent with jMetal's {@code NullParameterException}, where {@link CompositeCrossover} fails
 * with a {@link NullPointerException} (before 1.2.0 this class also threw a
 * {@link NullPointerException} for a {@code null} parent list, which it now rejects with a
 * {@code NullParameterException} too). {@link #crossoverProbability()} is {@link CompositeCrossover}'s
 * constant 1.0: each segment operator applies its own probability.
 */
@SuppressWarnings("serial")
public class SafeCompositeCrossover implements CrossoverOperator<CompositeSolution> {
    private final CompositeCrossover delegate;

    /**
     * Wraps a {@link CompositeCrossover} over the given segment operators.
     *
     * @param operators one {@link CrossoverOperator} per segment, in segment order
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if the list is {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.EmptyCollectionException
     *         if it is empty
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if it holds something that is not a {@link CrossoverOperator}
     */
    public SafeCompositeCrossover(List<?> operators) {
        this.delegate = new CompositeCrossover(operators);
    }

    /**
     * Copies both parents and crosses the copies segment by segment.
     *
     * @param parents exactly two composite solutions, each with one segment per operator
     * @return two new composite solutions, whose segments never alias the parents'
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code parents} or one of them is {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if there are not exactly two, or a parent's number of segments differs from the
     *         number of operators
     */
    @Override
    public List<CompositeSolution> execute(List<CompositeSolution> parents) {
        Check.notNull(parents, "parents");
        Check.that(parents.size() == 2, "The number of parents is not two: " + parents.size());
        int operators = delegate.getOperators().size();
        List<CompositeSolution> safe = new ArrayList<>(parents.size());
        for (CompositeSolution p : parents) {
            Check.notNull(p, "parent");
            Check.that(p.variables().size() == operators,
                    "The composite solution has " + p.variables().size() + " segments but the crossover has "
                            + operators + " operators: there must be one per segment, in segment order");
            safe.add((CompositeSolution) p.copy());
        }
        return delegate.execute(safe);
    }

    /**
     * The segment operators, in segment order, as {@link CompositeCrossover#getOperators()}.
     *
     * @return the delegate's operator list
     */
    public List<CrossoverOperator<Solution<?>>> getOperators() {
        return delegate.getOperators();
    }

    @Override
    public double crossoverProbability() {
        return delegate.crossoverProbability();
    }

    @Override
    public int numberOfRequiredParents() {
        return delegate.numberOfRequiredParents();
    }

    @Override
    public int numberOfGeneratedChildren() {
        return delegate.numberOfGeneratedChildren();
    }
}

package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.CompositeCrossover;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;

import java.util.ArrayList;
import java.util.List;

/**
 * Defensive wrapper around jMetal's {@link CompositeCrossover}.
 *
 * <p>jMetal's {@code NPointCrossover} (the basis for {@code TwoPointCrossover})
 * returns the input parents <em>as-is</em> when its probability check fails,
 * instead of returning copies. {@link CompositeCrossover} then wraps those
 * parent segment references straight into the new offspring's variable list,
 * so the offspring's segments alias the parents'. The subsequent
 * {@code CompositeMutation} mutates each segment in place — corrupting the
 * parent's genes while its cached objectives are left stale. The result is a
 * pre-existing latent aliasing bug that breaks the invariant
 * "VAR row matches FUN row" whenever crossover bypasses for at least one
 * segment.
 *
 * <p>This wrapper deep-copies the parents up front and delegates, so any
 * bypass branch returns references to local copies rather than to the
 * population's solutions. Downstream mutation only ever modifies copies.
 * The cost is one extra {@code CompositeSolution.copy()} per parent per
 * crossover invocation, negligible against the evaluation cost.
 *
 * <p>Drop-in replacement: same constructor signature as
 * {@link CompositeCrossover}.
 */
public class SafeCompositeCrossover implements CrossoverOperator<CompositeSolution> {
    private final CompositeCrossover delegate;

    public SafeCompositeCrossover(List<?> operators) {
        this.delegate = new CompositeCrossover(operators);
    }

    @Override
    public List<CompositeSolution> execute(List<CompositeSolution> parents) {
        List<CompositeSolution> safe = new ArrayList<>(parents.size());
        for (CompositeSolution p : parents) safe.add((CompositeSolution) p.copy());
        return delegate.execute(safe);
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

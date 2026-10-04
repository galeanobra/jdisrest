package es.unex.jdisrest.distributed;

import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;

/**
 * A steady-state algorithm without REST server, for the tests of other packages: it starts no
 * Spring application and does not register itself as the master of the JVM, so a unit test can
 * build one, and it shows the operators it would use. It is never run.
 *
 * @param <S> the solutions of the problem
 */
public final class ServerlessAlgorithm<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /**
     * @param problem   the problem
     * @param crossover the crossover it starts with
     * @param mutation  the mutation it starts with
     * @param budget    the evaluations of its termination
     */
    public ServerlessAlgorithm(Problem<S> problem, CrossoverOperator<S> crossover, MutationOperator<S> mutation,
            int budget) {
        super(problem, 10, crossover, mutation, null, new DominanceWithConstraintsComparator<>(),
                new TerminationByEvaluations(budget));
    }

    /** The crossover the next task would use. */
    public CrossoverOperator<S> crossoverInUse() {
        synchronized (population) {
            return crossover;
        }
    }

    /** The mutation the next task would use. */
    public MutationOperator<S> mutationInUse() {
        synchronized (population) {
            return mutation;
        }
    }
}

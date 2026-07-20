package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.operator.NaryTournamentSelection;
import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;

/**
 * Distributed steady-state NSGA-II (Non-dominated Sorting Genetic Algorithm II).
 *
 * <p>This class wires the standard NSGA-II selection criterion — binary tournament
 * selection with Pareto dominance and constraint comparison — into the generic
 * steady-state infrastructure provided by {@link SteadyStateEvolutionaryAlgorithm}.
 *
 * <p>NSGA-II environmental selection in {@code processComputedTask} (inherited):
 * <ol>
 *   <li>Add the new offspring to the current population (size becomes
 *       {@code populationSize + 1}).</li>
 *   <li>Apply fast non-dominated sorting to assign each solution a rank.</li>
 *   <li>Fill the new population front by front; when the last fitting front is
 *       too large, sort it by crowding distance (descending) and take the most
 *       diverse solutions.</li>
 *   <li>Drop the solution with the worst rank / lowest crowding distance.</li>
 * </ol>
 *
 * <p>All algorithm infrastructure — Spring Boot lifecycle, task queues, archive
 * management, periodic trace output, and the warm-start mechanism — is fully
 * inherited from {@link SteadyStateEvolutionaryAlgorithm}. This class only fixes the
 * selection and comparison operators.
 *
 * <p><strong>Note:</strong> worker registration is handled automatically. Spring Boot
 * starts listening for HTTP connections inside the {@code super()} constructor call,
 * so workers that call {@code GET /api/v1/tasks/next} immediately after the master
 * is launched will find the server already running — no explicit
 * {@code waitForWorkers()} barrier is needed.
 *
 * @param <S> the solution type (e.g., {@code IntegerSolution} or {@code CompositeSolution})
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class NSGAII<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /**
     * Constructs a distributed steady-state NSGA-II master.
     *
     * @param host           hostname or IP address the REST server will advertise
     * @param port           HTTP port for the REST server
     * @param problem        the optimization problem (evaluation delegated to workers)
     * @param populationSize number of solutions maintained in the population
     * @param crossover      crossover operator applied during offspring generation
     * @param mutation       mutation operator applied to offspring
     * @param termination    stopping criterion (e.g., max evaluations)
     * @param tracesFolder   directory for periodic VAR/FUN trace files, or {@code null}
     *                       to disable trace output
     */
    public NSGAII(String host, int port, Problem<S> problem, int populationSize,
            CrossoverOperator<S> crossover, MutationOperator<S> mutation,
            Termination termination, String tracesFolder) {

        super(host, port, problem, populationSize, crossover, mutation,
              new NaryTournamentSelection<>(),
              new DominanceWithConstraintsComparator<>(),
              termination, tracesFolder);
    }
}

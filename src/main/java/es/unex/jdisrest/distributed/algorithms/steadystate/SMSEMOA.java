package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.operator.NaryTournamentSelection;
import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.legacy.qualityindicator.impl.hypervolume.Hypervolume;
import org.uma.jmetal.util.legacy.qualityindicator.impl.hypervolume.impl.PISAHypervolume;
import org.uma.jmetal.util.ranking.Ranking;
import org.uma.jmetal.util.ranking.impl.FastNonDominatedSortRanking;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed steady-state SMS-EMOA (S-Metric Selection Evolutionary Multi-objective Algorithm).
 *
 * <p>SMS-EMOA differs from NSGA-II in its environmental selection criterion: rather than
 * using crowding distance to break ties within the last non-dominated front, it removes
 * the solution with the <em>smallest hypervolume contribution</em> to the joint population.
 * This promotes diversity in objective space without requiring a crowding-distance
 * approximation.
 *
 * <p>Selection procedure in {@link #processComputedTask} (steady-state variant):
 * <ol>
 *   <li>Add the new offspring to the current population, forming a joint population of
 *       size {@code populationSize + 1}.</li>
 *   <li>Apply fast non-dominated sorting to identify all Pareto fronts.</li>
 *   <li>From the last (worst) front, compute each solution's hypervolume contribution
 *       relative to the full joint population.</li>
 *   <li>Remove the solution with the minimum hypervolume contribution from the last
 *       front, keeping the population size constant at {@code populationSize}.</li>
 * </ol>
 *
 * <p>All other algorithm infrastructure (Spring Boot, task queues, archive, warm start,
 * trace saving) is inherited from {@link SteadyStateEvolutionaryAlgorithm}.
 *
 * @param <S> the solution type (typically {@code IntegerSolution} or {@code CompositeSolution})
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class SMSEMOA<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /** Ranking algorithm used to compute non-dominated fronts (fast non-dominated sort). */
    private final Ranking<S> ranking;

    /**
     * Hypervolume indicator used to compute each solution's contribution to the last front.
     * Uses the PISA hypervolume implementation.
     */
    private final Hypervolume<S> hypervolume;

    /**
     * Constructs a distributed steady-state SMS-EMOA master.
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
    public SMSEMOA(String host, int port, Problem<S> problem, int populationSize,
            CrossoverOperator<S> crossover, MutationOperator<S> mutation,
            Termination termination, String tracesFolder) {
        super(host, port, problem, populationSize, crossover, mutation,
              new NaryTournamentSelection<>(),
              new DominanceWithConstraintsComparator<>(),
              termination, tracesFolder);

        this.ranking = new FastNonDominatedSortRanking<>();
        this.hypervolume = new PISAHypervolume<>();
    }

    /**
     * Integrates an evaluated offspring into the population using SMS-EMOA selection.
     *
     * <p>If the population has not yet reached {@code populationSize}, the offspring is
     * added directly. Otherwise, the joint population (current + offspring) is ranked
     * by non-domination, and the solution with the minimum hypervolume contribution in
     * the last (worst) front is discarded, maintaining exactly {@code populationSize}
     * solutions.
     *
     * @param task the completed task whose solution has been evaluated by a worker
     */
    @Override
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        S sol = (S) task.getContents().copy();
        archive.add(sol);

        synchronized (population) {
            if (population.size() < populationSize) {
                // Population not yet full — add directly without selection pressure.
                population.add(sol);
                populationSignatures.add(solutionKey(sol));
            } else {
                List<S> jointPopulation = new ArrayList<>(population);
                jointPopulation.add(sol);

                // Step 1: rank all solutions by non-domination.
                ranking.compute(jointPopulation);

                // Step 2: identify the last (worst) front and compute hypervolume contributions.
                List<S> lastSubFront = ranking.getSubFront(ranking.getNumberOfSubFronts() - 1);
                lastSubFront = hypervolume.computeHypervolumeContribution(lastSubFront, jointPopulation);

                // Step 3: rebuild population — keep all fronts except the last in full,
                // then add all but the last (minimum-contribution) solution of the last front.
                List<S> resultPopulation = new ArrayList<>();
                for (int i = 0; i < ranking.getNumberOfSubFronts() - 1; i++) {
                    resultPopulation.addAll(ranking.getSubFront(i));
                }
                // computeHypervolumeContribution sorts the last front so the solution with
                // the smallest contribution is placed last — drop it.
                for (int i = 0; i < lastSubFront.size() - 1; i++) {
                    resultPopulation.add(lastSubFront.get(i));
                }

                population.clear();
                population.addAll(resultPopulation);
                rebuildPopulationSignatures();
            }
        }
    }
}

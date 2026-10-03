package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.operator.NaryTournamentSelection;
import es.unex.jdisrest.util.Log;
import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.densityestimator.impl.CrowdingDistanceDensityEstimator;
import org.uma.jmetal.util.legacy.qualityindicator.impl.hypervolume.Hypervolume;
import org.uma.jmetal.util.legacy.qualityindicator.impl.hypervolume.impl.PISAHypervolume;
import org.uma.jmetal.util.ranking.Ranking;
import org.uma.jmetal.util.ranking.impl.FastNonDominatedSortRanking;

import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>Discard the new offspring if the population already holds a solution with the same
 *       variables, as the other steady-state algorithms do (it still counts as an evaluation
 *       and enters the archive).</li>
 *   <li>Add the new offspring to the current population, forming a joint population of
 *       size {@code populationSize + 1}.</li>
 *   <li>Apply fast non-dominated sorting with the algorithm's dominance comparator, which
 *       takes constraints into account ({@link DominanceWithConstraintsComparator}), to
 *       identify all fronts.</li>
 *   <li>From the last (worst) front, compute each solution's hypervolume contribution
 *       relative to the full joint population.</li>
 *   <li>Remove the solution with the minimum hypervolume contribution from the last
 *       front, keeping the population size constant at {@code populationSize}.</li>
 * </ol>
 *
 * <p>The hypervolume contributions are undefined when an objective has the same value in every
 * member of the joint population: jMetal's PISA hypervolume normalizes by the range of each
 * objective and throws. In that case, when the last front has more than one member, the member of
 * the last front with the smallest crowding distance is removed instead, and a warning is logged
 * once per run.
 *
 * <p>All other algorithm infrastructure (Spring Boot, task queues, archive, warm start,
 * trace saving) is inherited from {@link SteadyStateEvolutionaryAlgorithm}.
 *
 * <p>Since jdisrest 1.2 the ranking is constraint-aware and duplicates are filtered. Earlier
 * versions ranked with plain Pareto dominance (as jMetal's own SMS-EMOA does), so an infeasible
 * solution could push feasible ones out of the population; they also admitted exact duplicates,
 * and a constant objective ended the run with an exception.
 *
 * @param <S> the solution type (typically {@code IntegerSolution} or {@code CompositeSolution})
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class SMSEMOA<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /**
     * Hypervolume indicator used to compute each solution's contribution to the last front.
     * Uses the PISA hypervolume implementation.
     */
    private final Hypervolume<S> hypervolume;

    /** Whether the constant-objective fallback has been reported (once per run). Algorithm thread only. */
    private boolean constantObjectiveReported;

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

        this.hypervolume = new PISAHypervolume<>();
    }

    /**
     * Integrates an evaluated offspring into the population using SMS-EMOA selection.
     *
     * <p>A copy of the offspring enters the archive. If the population already holds a solution
     * with the same variables, the offspring goes no further. If the population has not yet
     * reached {@code populationSize}, the offspring is added directly. Otherwise
     * the SMS-EMOA replacement removes one member of the joint population (current + offspring),
     * maintaining exactly {@code populationSize} solutions.
     *
     * @param task the completed task whose solution has been evaluated by a worker
     */
    @Override
    @SuppressWarnings("unchecked")
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        S sol = (S) task.getContents().copy();
        archive.add((S) sol.copy());  // never share an instance with the population

        synchronized (population) {
            if (solutionInThePopulation(sol)) {
                return;  // exact duplicate of a member: counted and archived, not inserted
            }
            if (population.size() < populationSize) {
                // Population not yet full — add directly without selection pressure.
                population.add(sol);
                populationSignatures.add(solutionKey(sol));
            } else {
                List<S> jointPopulation = new ArrayList<>(population);
                jointPopulation.add(sol);

                int constant = constantObjective(jointPopulation);
                if (constant >= 0 && !constantObjectiveReported) {
                    constantObjectiveReported = true;
                    Log.warn("SMS-EMOA: objective " + constant + " has the same value in the whole population"
                            + " — removing the most crowded member of the last front instead of the smallest"
                            + " hypervolume contribution while that lasts (reported once)");
                }
                List<S> resultPopulation = survivors(jointPopulation, dominanceComparator, hypervolume);

                population.clear();
                population.addAll(resultPopulation);
                rebuildPopulationSignatures();
            }
        }
    }

    // ── Environmental selection ───────────────────────────────────────────────

    /**
     * The SMS-EMOA replacement: the joint population without its least valuable member.
     *
     * <p>The joint population is ranked with {@code dominanceComparator}. Every front but the last
     * survives whole. From the last front the member with the smallest hypervolume contribution,
     * computed against the whole joint population, is removed; when the last front has a single
     * member, that member is removed. When the last front has several members and some objective
     * is constant over the joint population ({@link #constantObjective}), so that the
     * contributions are undefined, the member of the last front with the smallest crowding
     * distance is removed instead (on a tie, the one ranked last).
     *
     * <p>Writes rank, hypervolume-contribution or crowding attributes into the members, so the
     * caller must hold whatever lock guards them.
     *
     * @param jointPopulation     the population plus the new solution; not modified
     * @param dominanceComparator the dominance used to rank
     * @param hypervolume         the hypervolume used for the contributions
     * @param <S>                 the solution type
     * @return a new list with every member of {@code jointPopulation} but one
     */
    static <S extends Solution<?>> List<S> survivors(List<S> jointPopulation, Comparator<S> dominanceComparator,
            Hypervolume<S> hypervolume) {
        Ranking<S> ranking = new FastNonDominatedSortRanking<>(dominanceComparator);
        ranking.compute(jointPopulation);
        int lastFrontIndex = ranking.getNumberOfSubFronts() - 1;

        List<S> lastFront = new ArrayList<>(ranking.getSubFront(lastFrontIndex));
        if (lastFront.size() > 1 && constantObjective(jointPopulation) >= 0) {
            CrowdingDistanceDensityEstimator<S> crowding = new CrowdingDistanceDensityEstimator<>();
            crowding.compute(lastFront);
            lastFront.sort(crowding.comparator());  // decreasing distance: the most crowded last
        } else {
            // Sorted by decreasing contribution, so the smallest is last; a single member stays as is.
            lastFront = hypervolume.computeHypervolumeContribution(lastFront, jointPopulation);
        }

        List<S> resultPopulation = new ArrayList<>(jointPopulation.size() - 1);
        for (int i = 0; i < lastFrontIndex; i++) {
            resultPopulation.addAll(ranking.getSubFront(i));
        }
        resultPopulation.addAll(lastFront.subList(0, lastFront.size() - 1));
        return resultPopulation;
    }

    /**
     * The first objective whose value is the same in every solution, or {@code -1} if there is
     * none (or the list is empty). Values are compared with {@code ==}, as the hypervolume
     * compares the minimum and maximum of each objective.
     *
     * @param solutions the solutions to examine
     * @return the index of a constant objective, or {@code -1}
     */
    static int constantObjective(List<? extends Solution<?>> solutions) {
        if (solutions.isEmpty()) {
            return -1;
        }
        double[] first = solutions.getFirst().objectives();
        for (int i = 0; i < first.length; i++) {
            boolean constant = true;
            for (Solution<?> s : solutions) {
                if (s.objectives()[i] != first[i]) {
                    constant = false;
                    break;
                }
            }
            if (constant) {
                return i;
            }
        }
        return -1;
    }
}

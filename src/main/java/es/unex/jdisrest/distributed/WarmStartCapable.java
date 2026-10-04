package es.unex.jdisrest.distributed;

import java.util.List;

/**
 * Optional contract implemented by problems that can produce a warm-start
 * initial population (e.g., loaded from a file such as {@code iVAR.csv}).
 *
 * <p>When a problem implements this interface and {@link WarmStart#FILE} exists,
 * {@link WarmStart#load} delegates initial population construction to
 * {@link #createInitialPopulationFromFile(int)} instead of generating random
 * solutions via {@code Problem.createSolution()}, and copies that file into the
 * traces folder. The callers are
 * {@link SteadyStateEvolutionaryAlgorithm#createInitialSolutions(int)} — used by
 * every bundled distributed algorithm (NSGA-II, SMS-EMOA, MOEA/D and PAES) —
 * and the local {@link es.unex.jdisrest.local.algorithms.NSGAII NSGA-II}.
 * The method takes no path: implementations must read
 * {@link WarmStart#FILE}, the file whose presence enabled the warm start and the
 * one copied into the traces.
 *
 * <p>{@link WarmStart#initialPopulation(org.uma.jmetal.problem.Problem, int)}
 * implements {@link #createInitialPopulationFromFile(int)} for a file of rows like
 * those of the {@code VAR} traces, such as the {@code VAR.csv} of another run of the
 * problem: {@code return WarmStart.initialPopulation(this, populationSize);}.
 *
 * <p>This interface exists purely to decouple the framework from problem
 * implementations: {@code SteadyStateEvolutionaryAlgorithm} must not depend on any
 * specific problem class.
 *
 * @param <S> the solution type produced by the problem (e.g.
 *            {@code IntegerSolution} or {@code CompositeSolution})
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public interface WarmStartCapable<S> {

    /**
     * Builds an initial population of the requested size, typically by reading
     * pre-existing solutions from disk and padding any missing slots with
     * randomly generated ones.
     *
     * <p>The size requested is not always the algorithm's population size: an
     * algorithm that starts from a single solution asks for {@code 1}, and
     * typically wants the first solution of the file.
     *
     * <p>Implementations that cannot load their persisted state (missing or
     * malformed file) should log the error and return {@code null} rather than
     * throwing, as {@link WarmStart#initialPopulation} does: the run then starts
     * from random solutions, and {@link WarmStart#load} neither reports the file
     * as loaded nor copies it into the traces, which it does for a population
     * padded entirely with random solutions. A list of another size is tolerated
     * with a warning (see {@link WarmStart#load}): the algorithm decides what a
     * short or long list means.
     *
     * @param populationSize number of solutions requested by the algorithm
     * @return a list of {@code populationSize} solutions (see above for what
     *         happens with another size), or {@code null} to start from random
     *         solutions
     */
    List<S> createInitialPopulationFromFile(int populationSize);
}

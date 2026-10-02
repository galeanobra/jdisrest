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
 * every distributed algorithm that keeps the inherited
 * {@link SteadyStateEvolutionaryAlgorithm#createInitialTasks() createInitialTasks()}
 * — and the local {@link es.unex.jdisrest.local.algorithms.NSGAII NSGA-II}.
 * The method takes no path: implementations must read
 * {@link WarmStart#FILE}, the file whose presence enabled the warm start and the
 * one copied into the traces.
 *
 * <p>This interface exists purely to decouple the framework from problem
 * implementations: {@code SteadyStateEvolutionaryAlgorithm} must not depend on any
 * specific problem class.
 *
 * @param <S> the solution type produced by the problem (e.g.
 *            {@code IntegerSolution} or {@code CompositeSolution})
 * @author Jes&uacute;s Galeano Brajones (Universidad de Extremadura)
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
     * malformed file) should log the error and return a population padded
     * entirely with random solutions rather than throwing. A list of another
     * size, or {@code null}, is tolerated with a warning (see
     * {@link WarmStart#load}): the list is used as it is, and {@code null}
     * makes the run start from random solutions.
     *
     * @param populationSize number of solutions requested by the algorithm
     * @return a list of {@code populationSize} solutions (see above for what
     *         happens with another size)
     */
    List<S> createInitialPopulationFromFile(int populationSize);
}

package es.unex.jdisrest.distributed;

import java.util.List;

/**
 * Optional contract implemented by problems that can produce a warm-start
 * initial population (e.g., loaded from a file such as {@code iVAR.csv}).
 *
 * <p>When a problem implements this interface, the distributed algorithm in
 * {@link SteadyStateEvolutionaryAlgorithm#createInitialTasks()} delegates initial
 * population construction to {@link #createInitialPopulationFromFile(int)}
 * instead of generating random solutions via {@code Problem.createSolution()}.
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
     * <p>Implementations that cannot load their persisted state (missing or
     * malformed file) should log the error and return a population padded
     * entirely with random solutions rather than throwing.
     *
     * @param populationSize total number of solutions the algorithm needs
     * @return a list of exactly {@code populationSize} solutions
     */
    List<S> createInitialPopulationFromFile(int populationSize);
}

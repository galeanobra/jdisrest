package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ListUtils;
import org.uma.jmetal.util.SolutionListUtils;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.errorchecking.Check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * N-ary tournament selection operator that returns exactly two parent solutions.
 *
 * <p>The operator runs <em>two</em> independent tournaments, each of size
 * {@code tournamentSize}. In each tournament, {@code tournamentSize} candidates
 * are drawn uniformly at random (without replacement) from the population, and
 * the best candidate according to the supplied {@link Comparator} is selected.
 * The two winners are returned as a list and are intended to serve as the
 * parent pair for a crossover operator.
 *
 * <p>Note: the number of returned solutions is always 2, regardless of
 * {@code tournamentSize}. The parameter {@code tournamentSize} controls
 * <em>selection pressure</em> (how many candidates compete in each tournament),
 * not the number of parents produced.
 *
 * <p>The default constructor uses a tournament size of 2 and
 * {@link DominanceWithConstraintsComparator}, which is suitable for constrained
 * multi-objective problems.
 *
 * @param <S> the solution type
 */
public class NaryTournamentSelection<S extends Solution<?>> implements SelectionOperator<List<S>, List<S>> {

    /**
     * Comparator used to determine the winner of each tournament.
     * A solution is considered better than another if the comparator returns a
     * negative value when comparing it to the other.
     */
    private Comparator<S> comparator;

    /**
     * Number of candidates randomly sampled from the population to compete in
     * each individual tournament. Higher values increase selection pressure.
     */
    private int tournamentSize;

    /**
     * Default constructor: tournament size of 2 and
     * {@link DominanceWithConstraintsComparator}.
     */
    public NaryTournamentSelection() {
        this(2, new DominanceWithConstraintsComparator<S>());
    }

    /**
     * Full constructor.
     *
     * @param tournamentSize number of candidates per tournament (must be ≤ population size)
     * @param comparator     comparator used to pick the winner of each tournament
     */
    public NaryTournamentSelection(int tournamentSize, Comparator<S> comparator) {
        this.tournamentSize = tournamentSize;
        this.comparator = comparator;
    }

    /**
     * Runs two independent tournaments and returns their two winners.
     *
     * <p>The loop always executes exactly twice — once for each parent slot —
     * regardless of {@code tournamentSize}. If the population contains only one
     * solution, that solution is returned twice (both parents are identical).
     *
     * @param solutionList the current population to select from; must not be
     *                     {@code null} or empty, and must contain at least
     *                     {@code tournamentSize} solutions
     * @return a list of 2 solutions selected by tournament
     * @throws org.uma.jmetal.util.errorchecking.JMetalException if preconditions are violated
     */
    @Override
    public List<S> execute(List<S> solutionList) {
        Check.notNull(solutionList);
        Check.collectionIsNotEmpty(solutionList);
        Check.that(
                solutionList.size() >= tournamentSize,
                "The solution list size ("
                        + solutionList.size()
                        + ") is less than "
                        + "the number of requested solutions ("
                        + tournamentSize
                        + ")");

        List<S> result = new ArrayList<>(solutionList.size());

        // Always run exactly 2 tournaments to produce the 2 parents needed for crossover.
        for (int i = 0; i < 2; i++) {
            if (solutionList.size() == 1) {
                // Degenerate case: only one candidate available; use it unconditionally.
                result.add(solutionList.get(0));
            } else {
                // Sample tournamentSize candidates at random (without replacement),
                // then pick the best according to the comparator.
                List<S> selectedSolutions = ListUtils.randomSelectionWithoutReplacement(tournamentSize, solutionList);
                result.add(SolutionListUtils.findBestSolution(selectedSolutions, comparator));
            }
        }

        return result;
    }

    /**
     * Returns the tournament size (number of candidates per tournament).
     *
     * @return tournament size
     */
    public int getTournamentSize() {
        return tournamentSize;
    }
}

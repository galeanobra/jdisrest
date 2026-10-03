package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.pseudorandom.BoundedRandomGenerator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * N-ary tournament selection operator that returns exactly two parent solutions.
 *
 * <p>The operator runs <em>two</em> independent tournaments, each of size
 * {@code tournamentSize}. In each tournament, {@code tournamentSize} candidates
 * are drawn uniformly at random (without replacement) from the population, and
 * the best candidate according to the supplied {@link Comparator} is selected.
 * The two winners are returned as a list and are intended to serve as the
 * parent pair for a crossover operator. They are references to population members, not
 * copies, and since the tournaments are independent the same solution can win both: the
 * crossover then gets two identical parents, and only the mutation makes the children
 * differ from them.
 *
 * <p>Note: the number of returned solutions is always 2, regardless of
 * {@code tournamentSize}. The parameter {@code tournamentSize} controls
 * <em>selection pressure</em> (how many candidates compete in each tournament),
 * not the number of parents produced. A crossover that needs another number of parents
 * cannot be fed by this operator.
 *
 * <p>The default constructor uses a tournament size of 2 and
 * {@link DominanceWithConstraintsComparator}, which is suitable for constrained
 * multi-objective problems.
 *
 * <h2>Sampling</h2>
 * <p>Each tournament draws exactly {@code tournamentSize} indices, distinct and in random
 * order, with a partial Fisher–Yates shuffle, so its cost does not depend on the population
 * size. The draws come from the injected generator, by default
 * {@link JMetalRandom#nextInt(int, int)}. Before 1.2.0 each tournament built a whole random
 * permutation of the population through jMetal's {@code ListUtils} (O(N²) work and N draws,
 * with the steady-state master's population lock held), so a seeded run consumes the
 * {@link JMetalRandom} stream differently from earlier versions.
 *
 * <h2>Not jMetal's class of the same name</h2>
 * <p>jMetal's {@code org.uma.jmetal.operator.selection.impl.NaryTournamentSelection} returns a
 * single solution ({@code SelectionOperator<List<S>, S>}), which is what jMetal's own algorithms
 * expect; this one returns the parent pair the steady-state algorithms of jdisrest need.
 *
 * @param <S> the solution type
 */
@SuppressWarnings("serial")
public class NaryTournamentSelection<S extends Solution<?>> implements SelectionOperator<List<S>, List<S>> {

    /**
     * Comparator used to determine the winner of each tournament.
     * A candidate beats the current winner only if {@code compare(winner, candidate) > 0}, that
     * is, if the comparator ranks it strictly better. Ties keep the candidate drawn first, which
     * is itself random.
     */
    private Comparator<S> comparator;

    /**
     * Number of candidates randomly sampled from the population to compete in
     * each individual tournament, at least 1. Higher values increase selection pressure.
     */
    private int tournamentSize;

    /** Source of uniform random integers in an inclusive range, for the candidate draws. */
    private final BoundedRandomGenerator<Integer> randomGenerator;

    /**
     * Default constructor: tournament size of 2 and
     * {@link DominanceWithConstraintsComparator}.
     */
    public NaryTournamentSelection() {
        this(2, new DominanceWithConstraintsComparator<S>());
    }

    /**
     * Constructor with the default jMetal random generator.
     *
     * @param tournamentSize number of candidates per tournament, at least 1 (and at most the
     *                       population size when the operator is executed)
     * @param comparator     comparator used to pick the winner of each tournament
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if {@code tournamentSize} is smaller than 1 (before 1.2.0 it was accepted here and
     *         made {@link #execute} fail on any population of two or more solutions)
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code comparator} is {@code null}
     */
    public NaryTournamentSelection(int tournamentSize, Comparator<S> comparator) {
        this(tournamentSize, comparator,
                (lowerBound, upperBound) -> JMetalRandom.getInstance().nextInt(lowerBound, upperBound));
    }

    /**
     * Full constructor.
     *
     * @param tournamentSize  number of candidates per tournament, at least 1 (and at most the
     *                        population size when the operator is executed)
     * @param comparator      comparator used to pick the winner of each tournament
     * @param randomGenerator uniform random integers in an inclusive range
     *                        ({@code getRandomValue(a, b)} returns a value in [a, b])
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if {@code tournamentSize} is smaller than 1
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if the comparator or the generator is {@code null}
     */
    public NaryTournamentSelection(int tournamentSize, Comparator<S> comparator,
                                   BoundedRandomGenerator<Integer> randomGenerator) {
        Check.that(tournamentSize >= 1, "The tournament size must be at least 1: " + tournamentSize);
        Check.notNull(comparator, "comparator");
        Check.notNull(randomGenerator, "randomGenerator");
        this.tournamentSize = tournamentSize;
        this.comparator = comparator;
        this.randomGenerator = randomGenerator;
    }

    /**
     * Runs two independent tournaments and returns their two winners.
     *
     * <p>The loop always executes exactly twice — once for each parent slot —
     * regardless of {@code tournamentSize}. Both winners can be the same solution; with a
     * one-solution population (only possible with a tournament size of 1) that solution is
     * returned twice.
     *
     * @param solutionList the current population to select from; must not be
     *                     {@code null} or empty, and must contain at least
     *                     {@code tournamentSize} solutions
     * @return a new list of 2 solutions selected by tournament
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code solutionList} is {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.EmptyCollectionException
     *         if it is empty
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if it holds fewer than {@code tournamentSize} solutions
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

        List<S> result = new ArrayList<>(2);

        // Always run exactly 2 tournaments to produce the 2 parents needed for crossover.
        for (int i = 0; i < 2; i++) {
            result.add(tournament(solutionList));
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

    // ── Tournament ────────────────────────────────────────────────────────────

    /**
     * Draws {@link #tournamentSize} distinct candidates and returns the best one.
     *
     * <p>The candidates are the first positions of a Fisher–Yates shuffle of the indices
     * {@code 0..N-1}, stopped after {@code tournamentSize} steps. The shuffled array is virtual:
     * {@code moved} holds only the positions whose index has been swapped away, so the work and
     * the memory are O({@code tournamentSize}), and step {@code k} makes exactly one draw in
     * {@code [k, N-1]}.
     */
    private S tournament(List<S> solutionList) {
        int size = solutionList.size();
        Map<Integer, Integer> moved = new HashMap<>();
        S winner = null;
        for (int k = 0; k < tournamentSize; k++) {
            int j = randomGenerator.getRandomValue(k, size - 1);
            int candidateIndex = moved.getOrDefault(j, j);
            moved.put(j, moved.getOrDefault(k, k));

            S candidate = solutionList.get(candidateIndex);
            if (k == 0 || comparator.compare(winner, candidate) > 0) {
                winner = candidate;
            }
        }
        return winner;
    }
}

package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.pseudorandom.BoundedRandomGenerator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Selection operator for Differential Evolution (DE) that picks a set of distinct
 * random individuals from the population for use in the DE mutation step.
 *
 * <p>Standard DE/rand/1 requires three randomly chosen, mutually distinct individuals
 * {@code r1, r2, r3} — all different from the current target vector. This operator
 * generalizes that pattern: it selects {@link #numberOfSolutionsToSelect} individuals
 * at random, ensuring:
 * <ul>
 *   <li>No index appears twice in the selection.</li>
 *   <li>None of the selected indices equals {@link #currentSolutionIndex} (the current
 *       target), unless {@link #selectCurrentSolution} is {@code true}.</li>
 * </ul>
 *
 * <p>When {@code selectCurrentSolution} is {@code true} the operator selects
 * {@code numberOfSolutionsToSelect - 1} random distinct individuals and then appends
 * the current solution. This is useful for DE/current-to-best or similar variants
 * that include the target in the donor vector computation.
 *
 * <p>Before each call to {@link #execute}, the caller must set
 * {@link #currentSolutionIndex} via {@link #setIndex(int)} so the operator knows
 * which solution is the current target. That index is mutable state, so an instance must not
 * be shared by threads that select concurrently.
 *
 * <p>The current target is never drawn at random, so the population must hold at least
 * {@code numberOfSolutionsToSelect + 1} solutions when the target is excluded (4 for
 * DE/rand/1) and {@code numberOfSolutionsToSelect} when it is included. This class started as
 * a copy of jMetal's {@code DifferentialEvolutionSelection}; since 1.2.0 it no longer shares
 * that class's defects: a population one solution too small made {@link #execute} loop forever
 * (it is now rejected), an index equal to the population size was accepted (it now fails the
 * index check instead of excluding nothing or throwing an {@code IndexOutOfBoundsException}),
 * and a request for no random individual still drew one (it now draws none).
 *
 * <p>No algorithm of jdisrest uses this operator; it is meant for custom DE algorithms.
 *
 * @param <S> unused: the operator works on lists of {@link DoubleSolution}; the parameter is
 *            kept for source compatibility
 */
public class DifferentialEvolutionSelection<S extends DoubleSolution> implements SelectionOperator<List<DoubleSolution>, List<DoubleSolution>> {

    /**
     * Index of the current target solution in the population list.
     * Must be set via {@link #setIndex(int)} before each call to {@link #execute}.
     * Initialized to {@link Integer#MIN_VALUE} to detect accidental omission.
     */
    private int currentSolutionIndex = Integer.MIN_VALUE;

    /** Source of bounded uniform random integers in {@code [a, b]}. */
    private final BoundedRandomGenerator<Integer> randomGenerator;

    /** Total number of solutions to return from {@link #execute}. */
    private final int numberOfSolutionsToSelect;

    /**
     * When {@code true}, the current target solution (at {@link #currentSolutionIndex})
     * is included as the last element of the returned list; the remaining
     * {@code numberOfSolutionsToSelect - 1} slots are filled with random, distinct
     * individuals different from the current target.
     */
    private final boolean selectCurrentSolution;

    /**
     * Default constructor for standard DE/rand/1: selects 3 random individuals,
     * none of which is the current target.
     */
    public DifferentialEvolutionSelection() {
        this((a, b) -> JMetalRandom.getInstance().nextInt(a, b), 3, false);
    }

    /**
     * Constructor with configurable count and target-inclusion flag, using the
     * default jMetal random generator.
     *
     * @param numberOfSolutionsToSelect total number of solutions to return
     * @param selectCurrentSolution     if {@code true}, include the current target
     *                                  as the last selected solution
     */
    public DifferentialEvolutionSelection(int numberOfSolutionsToSelect, boolean selectCurrentSolution) {
        this((a, b) -> JMetalRandom.getInstance().nextInt(a, b), numberOfSolutionsToSelect, selectCurrentSolution);
    }

    /**
     * Full constructor.
     *
     * @param randomGenerator           bounded integer random generator
     * @param numberOfSolutionsToSelect total number of solutions to return: at least 0, and at
     *                                  least 1 when the current target is included
     * @param selectCurrentSolution     if {@code true}, include the current target
     *                                  as the last selected solution
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if the generator is {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if the number of solutions is out of range
     */
    public DifferentialEvolutionSelection(BoundedRandomGenerator<Integer> randomGenerator, int numberOfSolutionsToSelect, boolean selectCurrentSolution) {
        Check.notNull(randomGenerator, "randomGenerator");
        Check.that(numberOfSolutionsToSelect >= (selectCurrentSolution ? 1 : 0),
                "The number of solutions to select must be at least " + (selectCurrentSolution ? 1 : 0)
                        + (selectCurrentSolution ? " to include the current solution: " : ": ")
                        + numberOfSolutionsToSelect);
        this.randomGenerator = randomGenerator;
        this.numberOfSolutionsToSelect = numberOfSolutionsToSelect;
        this.selectCurrentSolution = selectCurrentSolution;
    }

    /**
     * Sets the index of the current target solution in the population.
     * Must be called before each invocation of {@link #execute}.
     *
     * @param index zero-based index of the target solution in the population list
     */
    public void setIndex(int index) {
        this.currentSolutionIndex = index;
    }

    /**
     * Selects {@link #numberOfSolutionsToSelect} solutions from {@code solutionList}
     * according to the DE selection protocol.
     *
     * <p>The selection loop samples random indices until enough distinct values have
     * been accumulated. Indices equal to {@link #currentSolutionIndex} are rejected
     * (unless {@link #selectCurrentSolution} is {@code true}, in which case the
     * current target is appended at the end after the random draws).
     *
     * @param solutionList the full population; must contain at least
     *                     {@link #numberOfSolutionsToSelect} + 1 elements when the current
     *                     target is excluded, {@link #numberOfSolutionsToSelect} when it is
     *                     included
     * @return a list of {@link #numberOfSolutionsToSelect} distinct solutions
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code solutionList} is {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if the index is not in {@code [0, size)} or the population is too small
     */
    @Override
    public List<DoubleSolution> execute(List<DoubleSolution> solutionList) {
        Check.notNull(solutionList);
        Check.that((currentSolutionIndex >= 0) && (currentSolutionIndex < solutionList.size()), "Index value invalid: " + currentSolutionIndex);

        int solutionsToSelect = selectCurrentSolution ? numberOfSolutionsToSelect - 1 : numberOfSolutionsToSelect;

        // The current target is never drawn at random, so only size - 1 indices are eligible;
        // asking for more would make the rejection loop below run forever.
        Check.that(solutionList.size() - 1 >= solutionsToSelect,
                "The population has " + solutionList.size() + " solutions, but selecting "
                        + numberOfSolutionsToSelect + (selectCurrentSolution ? " including" : " besides")
                        + " the current one needs at least " + (solutionsToSelect + 1));

        List<Integer> indexList = new ArrayList<>(numberOfSolutionsToSelect);

        while (indexList.size() < solutionsToSelect) {
            int index = randomGenerator.getRandomValue(0, solutionList.size() - 1);
            if (index != currentSolutionIndex && !indexList.contains(index)) {
                indexList.add(index);
            }
        }

        if (selectCurrentSolution) {
            indexList.add(currentSolutionIndex);
        }

        return indexList.stream().map(index -> solutionList.get(index)).collect(Collectors.toList());
    }
}

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
 * which solution is the current target.
 *
 * @param <S> the solution type; must extend {@link DoubleSolution}
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
     * @param numberOfSolutionsToSelect total number of solutions to return
     * @param selectCurrentSolution     if {@code true}, include the current target
     *                                  as the last selected solution
     */
    public DifferentialEvolutionSelection(BoundedRandomGenerator<Integer> randomGenerator, int numberOfSolutionsToSelect, boolean selectCurrentSolution) {
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
     *                     {@link #numberOfSolutionsToSelect} elements
     * @return a list of {@link #numberOfSolutionsToSelect} distinct solutions
     * @throws org.uma.jmetal.util.errorchecking.JMetalException if {@code solutionList}
     *         is {@code null}, the index is out of range, or the population is too small
     */
    @Override
    public List<DoubleSolution> execute(List<DoubleSolution> solutionList) {
        Check.notNull(solutionList);
        Check.that((currentSolutionIndex >= 0) && (currentSolutionIndex <= solutionList.size()), "Index value invalid: " + currentSolutionIndex);
        Check.that(solutionList.size() >= numberOfSolutionsToSelect, "The population has less than " + numberOfSolutionsToSelect + " solutions: " + solutionList.size());

        List<Integer> indexList = new ArrayList<>();

        int solutionsToSelect = selectCurrentSolution ? numberOfSolutionsToSelect - 1 : numberOfSolutionsToSelect;

        do {
            int index = randomGenerator.getRandomValue(0, solutionList.size() - 1);
            if (index != currentSolutionIndex && !indexList.contains(index)) {
                indexList.add(index);
            }
        } while (indexList.size() < solutionsToSelect);

        if (selectCurrentSolution) {
            indexList.add(currentSolutionIndex);
        }

        return indexList.stream().map(index -> solutionList.get(index)).collect(Collectors.toList());
    }
}

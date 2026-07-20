package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Uniform random (reset) mutation operator for {@link IntegerSolution}.
 *
 * <p>Each variable is mutated independently with probability
 * {@code mutationProbability}. When a variable is selected for mutation its
 * value is <em>completely replaced</em> by a new value drawn uniformly at
 * random from {@code [lowerBound, upperBound]}.
 *
 * <p>This is more disruptive than {@link IntegerGaussianMutation}: instead of
 * adding a small perturbation to the current value, the variable is reset to an
 * entirely random position within its domain. This can be useful for escaping
 * local optima but may slow convergence in the final stages of optimization.
 *
 * @author Antonio J. Nebro {@literal <antonio@lcc.uma.es>}
 */
@SuppressWarnings("serial")
public class IntegerSimpleRandomMutation implements MutationOperator<IntegerSolution> {

    /** Probability in [0,1] that a given variable is mutated. */
    private double mutationProbability;

    /** Source of uniform random numbers used for the mutation-trigger check. */
    private RandomGenerator<Double> randomGenerator;

    /**
     * Convenience constructor using the default jMetal random generator.
     *
     * @param probability per-variable mutation probability in [0,1]
     */
    public IntegerSimpleRandomMutation(double probability) {
        this(probability, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param probability     per-variable mutation probability in [0,1]
     * @param randomGenerator supplier of uniform random doubles in [0,1)
     * @throws JMetalException if {@code probability} is negative
     */
    public IntegerSimpleRandomMutation(double probability, RandomGenerator<Double> randomGenerator) {
        if (probability < 0) {
            throw new JMetalException("Mutation probability is negative: " + mutationProbability);
        }

        this.mutationProbability = probability;
        this.randomGenerator = randomGenerator;
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    /**
     * Returns the per-variable mutation probability.
     *
     * @return mutation probability in [0,1]
     */
    @Override
    public double mutationProbability() {
        return mutationProbability;
    }

    /**
     * Sets the per-variable mutation probability.
     *
     * @param mutationProbability new probability value in [0,1]
     */
    public void setMutationProbability(double mutationProbability) {
        this.mutationProbability = mutationProbability;
    }

    // -------------------------------------------------------------------------
    // MutationOperator interface
    // -------------------------------------------------------------------------

    /**
     * Applies uniform random mutation to the given solution in-place.
     *
     * @param solution the integer solution to mutate
     * @return the mutated solution (same object, modified in-place)
     * @throws JMetalException if {@code solution} is {@code null}
     */
    @Override
    public IntegerSolution execute(IntegerSolution solution) throws JMetalException {
        if (null == solution) {
            throw new JMetalException("Null parameter");
        }

        doMutation(mutationProbability, solution);

        return solution;
    }

    /**
     * Applies uniform random reset to each variable of the solution.
     *
     * <p>For every variable {@code i}:
     * <ol>
     *   <li>Draw a uniform value; if it exceeds {@code probability}, skip this variable.</li>
     *   <li>Replace the variable's current value with a new integer drawn uniformly
     *       from {@code [lowerBound, upperBound]}.</li>
     * </ol>
     * Unlike Gaussian mutation, the new value is completely independent of the
     * current value — this is a full reset, not a perturbation.
     *
     * @param probability per-variable mutation probability
     * @param solution    solution to mutate in-place
     */
    private void doMutation(double probability, IntegerSolution solution) {
        for (int i = 0; i < solution.variables().size(); i++) {
            if (randomGenerator.getRandomValue() <= probability) {
                Bounds<Integer> bounds = solution.getBounds(i);
                Integer lowerBound = bounds.getLowerBound();
                Integer upperBound = bounds.getUpperBound();

                // Replace with a uniformly random integer in [lowerBound, upperBound].
                Integer value = JMetalRandom.getInstance().nextInt(lowerBound, upperBound);

                solution.variables().set(i, value);
            }
        }
    }
}

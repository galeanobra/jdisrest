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
 * random from {@code [lowerBound, upperBound]}, both ends included.
 *
 * <p>Unlike {@link IntegerGaussianMutation}, which adds a perturbation to the current
 * value, the variable is reset to an entirely random position within its domain, wherever
 * it was. This can be useful for escaping local optima but may slow convergence in the
 * final stages of optimization. It is not necessarily the larger step: from the middle of the
 * range it moves a value by a quarter of the range on average, less than the Gaussian
 * mutation does with its σ of half the range (see that class).
 *
 * <h2>Not jMetal's class of the same name</h2>
 * <p>jMetal 7.1 ships {@code org.uma.jmetal.operator.mutation.impl.IntegerSimpleRandomMutation}
 * with the same constructors but a different draw, {@code (int) (lb + (ub - lb) * r)}: for a
 * non-negative range it never produces {@code ub}, for a negative one it almost never produces
 * {@code lb}, and a range that spans 0 gets 0 twice as often as any other value. This class draws
 * every value of the range with the same probability, so check the import: the wrong one compiles
 * and silently changes the search.
 *
 * <h2>Randomness</h2>
 * <p>Every random number comes from the injected generator (by default {@link JMetalRandom}): one
 * draw per variable decides whether it mutates and one more gives the new value, as
 * {@code lb + floor(r * (ub - lb + 1))} computed in {@code long}, so any {@code int} range works.
 * Before 1.2.0 the new value came from {@code JMetalRandom.nextInt(lb, ub)} even when a generator
 * was injected, so an injected generator did not make the new values reproducible, the default
 * constructor consumed the {@link JMetalRandom} stream differently, and a range wider than
 * {@code Integer.MAX_VALUE} values threw an {@link IllegalArgumentException}.
 *
 * @author Jose Alejandro Cornejo-Acosta (original jMetal class)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SuppressWarnings("serial")
public class IntegerSimpleRandomMutation implements MutationOperator<IntegerSolution> {

    /** Probability in [0,1] that a given variable is mutated. */
    private double mutationProbability;

    /** Source of uniform random numbers in [0, 1), for the mutation-trigger check and the new value. */
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
     * @param randomGenerator supplier of uniform random doubles in [0,1), for the trigger draws
     *                        and the new values
     * @throws JMetalException if {@code probability} is negative or NaN
     */
    public IntegerSimpleRandomMutation(double probability, RandomGenerator<Double> randomGenerator) {
        if (!(probability >= 0)) {
            throw new JMetalException("Mutation probability is negative: " + probability);
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
     *       from {@code [lowerBound, upperBound]} with a second draw (see {@link #uniformInt}).</li>
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
                int value = uniformInt(randomGenerator.getRandomValue(), lowerBound, upperBound);
                solution.variables().set(i, value);
            }
        }
    }

    /**
     * Maps a uniform number in [0, 1) to an integer in {@code [lowerBound, upperBound]}, every
     * value with the same probability: {@code lowerBound + floor(r * (upperBound - lowerBound + 1))}.
     * The width is computed in {@code long}, so a range that spans the whole {@code int} type
     * does not overflow, and the result is capped at {@code upperBound} in case floating-point
     * rounding (or a generator that returns 1.0) reaches the top end.
     *
     * @param r          uniform number in [0, 1)
     * @param lowerBound smallest value, inclusive
     * @param upperBound largest value, inclusive, not smaller than {@code lowerBound}
     * @return the drawn value
     */
    static int uniformInt(double r, int lowerBound, int upperBound) {
        long width = (long) upperBound - lowerBound + 1;
        long offset = Math.min((long) (r * width), width - 1);
        return (int) (lowerBound + offset);
    }
}

package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

import java.util.Random;

/**
 * Gaussian perturbation mutation operator for {@link IntegerSolution}.
 *
 * <p>Each variable is mutated independently with probability
 * {@code mutationProbability}. When a variable is selected for mutation, a
 * Gaussian random noise term is added to its current value:
 * <pre>
 *   newValue = round(currentValue + N(0, σ))
 * </pre>
 * where the standard deviation is:
 * <pre>
 *   σ = max(2.0,  0.5 * (upperBound − lowerBound))
 * </pre>
 * The result is clamped to {@code [lowerBound, upperBound]} before being written
 * back. Using a minimum σ of 2.0 ensures that even variables with very narrow
 * ranges can still be meaningfully perturbed.
 *
 * <p>Unlike {@link IntegerSimpleRandomMutation}, this operator only perturbs
 * the current value rather than replacing it entirely, which tends to produce
 * smaller, more local changes.
 *
 * @author Antonio J. Nebro {@literal <antonio@lcc.uma.es>}
 */
@SuppressWarnings("serial")
public class IntegerGaussianMutation implements MutationOperator<IntegerSolution> {

    /** Probability in [0,1] that a given variable is mutated. */
    private double mutationProbability;

    /** Source of uniform random numbers used for the mutation-trigger check. */
    private RandomGenerator<Double> randomGenerator;

    /**
     * Shared {@link Random} instance used exclusively for Gaussian sampling
     * ({@link Random#nextGaussian()}). Thread-safety note: if multiple threads
     * share an operator instance, access to this field should be synchronized.
     */
    private static final Random gaussian = new Random();

    /**
     * Convenience constructor using the default jMetal random generator.
     *
     * @param probability per-variable mutation probability in [0,1]
     */
    public IntegerGaussianMutation(double probability) {
        this(probability, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param probability     per-variable mutation probability in [0,1]
     * @param randomGenerator supplier of uniform random doubles in [0,1)
     * @throws JMetalException if {@code probability} is negative
     */
    public IntegerGaussianMutation(double probability, RandomGenerator<Double> randomGenerator) {
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
     * Applies Gaussian mutation to the given solution in-place.
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
     * Applies the Gaussian mutation to each variable of the solution.
     *
     * <p>For every variable {@code i}:
     * <ol>
     *   <li>Draw a uniform value; if it exceeds {@code probability}, skip this variable.</li>
     *   <li>Compute the adaptive standard deviation:
     *       {@code σ = max(2.0, 0.5*(upperBound - lowerBound))}. The lower bound
     *       of 2.0 guarantees a non-trivial perturbation even when the variable
     *       range is very narrow (e.g., only 1 or 2 integers wide).</li>
     *   <li>Sample {@code N(0, σ)} and add it to the current value.</li>
     *   <li>Round to the nearest integer and clamp to {@code [lowerBound, upperBound]}.</li>
     * </ol>
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

                // Adaptive σ: at least 2 to produce a meaningful step even for narrow ranges.
                // 0.5*(range) scales the perturbation with the variable's domain width.
                double desviacion = Math.max(2.0, 0.5 * (upperBound - lowerBound));

                // Perturb the current value with Gaussian noise, then round to int.
                Integer value = (int) Math.round(solution.variables().get(i) + gaussian.nextGaussian() * desviacion);

                // Clamp to declared bounds.
                if (value < lowerBound)
                    value = lowerBound;
                if (value > upperBound)
                    value = upperBound;

                solution.variables().set(i, value);
            }
        }
    }
}

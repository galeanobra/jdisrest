package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

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
 * <h2>Step size</h2>
 * <p>Unlike {@link IntegerSimpleRandomMutation}, this operator perturbs the current value
 * rather than replacing it, but the steps are not small: σ is half the variable's range. From
 * the middle of the range a mutation moves the value by about 0.32 of the range on average,
 * slightly more than a uniform reset (0.25), and about a third of the mutations are clamped to
 * a bound. From a bound it moves about 0.2 of the range on average (a uniform reset: 0.5), and
 * at least half of the mutations clamp back to that same bound and leave the value unchanged.
 * It is a wide, bound-biased perturbation, not a local search step.
 *
 * <h2>Randomness</h2>
 * <p>Every random number comes from the injected generator (by default {@link JMetalRandom}):
 * one draw per variable decides whether it mutates and, if it does, two more give the Gaussian
 * noise through the Box–Muller transform. Seeding {@link JMetalRandom}, or injecting a seeded
 * generator, therefore reproduces the mutations. Before 1.2.0 the noise came from a static,
 * unseeded {@link java.util.Random} shared by every instance, so runs that used this operator
 * could not be reproduced; the stream of a seeded run differs from that of earlier versions.
 * The operator keeps no state between calls, so it is thread-safe whenever its generator is
 * and its probability is not changed while it runs.
 *
 * @author Antonio J. Nebro (structure of jMetal's SimpleRandomMutation)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SuppressWarnings("serial")
public class IntegerGaussianMutation implements MutationOperator<IntegerSolution> {

    /** Probability in [0,1] that a given variable is mutated. */
    private double mutationProbability;

    /** Source of uniform random numbers in [0, 1), for the mutation-trigger check and the noise. */
    private RandomGenerator<Double> randomGenerator;

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
     * @param randomGenerator supplier of uniform random doubles in [0,1), for the trigger draws
     *                        and the Gaussian noise
     * @throws JMetalException if {@code probability} is negative or NaN
     */
    public IntegerGaussianMutation(double probability, RandomGenerator<Double> randomGenerator) {
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
     *   <li>Sample {@code N(0, σ)} from two more draws (see {@link #standardNormal}) and add it
     *       to the current value.</li>
     *   <li>Round to the nearest integer and clamp to {@code [lowerBound, upperBound]}.</li>
     * </ol>
     * The arithmetic is done in {@code double} and {@code long}, so bounds near the ends of the
     * {@code int} range do not overflow.
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
                // 0.5*(range) scales the perturbation with the variable's domain width; the range
                // is computed in double, since upperBound - lowerBound can overflow an int.
                double sigma = Math.max(2.0, 0.5 * ((double) upperBound - lowerBound));

                // Perturb the current value with Gaussian noise, then round (to a long, which
                // saturates instead of wrapping) and clamp to the declared bounds.
                long value = Math.round(solution.variables().get(i) + standardNormal() * sigma);
                value = Math.max(lowerBound, Math.min(upperBound, value));

                solution.variables().set(i, (int) value);
            }
        }
    }

    /**
     * Draws a standard normal number from two uniform draws of the injected generator, with the
     * Box–Muller transform: {@code sqrt(-2 ln(1 - u1)) * cos(2π u2)}. Using {@code 1 - u1}, in
     * (0, 1] for {@code u1} in [0, 1), keeps the logarithm finite; a generator that returns 1.0
     * gives a very large but finite number, which the caller clamps to a bound.
     *
     * @return a sample of N(0, 1)
     */
    private double standardNormal() {
        double u1 = randomGenerator.getRandomValue();
        double u2 = randomGenerator.getRandomValue();
        double radius = Math.sqrt(-2.0 * Math.log(Math.max(Double.MIN_VALUE, 1.0 - u1)));
        return radius * Math.cos(2.0 * Math.PI * u2);
    }
}

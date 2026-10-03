package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Uniform random mutation for {@link DoubleSolution} with a per-call randomized
 * effective probability.
 *
 * <p>In standard uniform mutation each variable is independently replaced by a
 * value drawn uniformly at random from its declared bounds {@code [yl, yu]}.
 * This operator adds a stochastic term to the effective mutation probability so
 * that the fraction of variables mutated varies from call to call:
 * <pre>
 *   p_eff = mutationProbability + U[0,1] × jitter / n
 * </pre>
 * where {@code n} is the number of decision variables and {@code jitter} defaults to
 * {@value #DEFAULT_JITTER} (a constructor parameter since 1.2.0). The jitter is divided by
 * {@code n}, so the extra mutated variables it adds do not depend on the problem dimension:
 * between 0 and {@code jitter} per call, {@code jitter / 2} on average (1.75 with the
 * default). The base probability's share, {@code mutationProbability × n}, still grows with
 * {@code n}. Inside a {@code CompositeSolution} mutated through jMetal's
 * {@code CompositeMutation}, {@code n} is the number of variables of the segment, and each
 * segment draws its own jitter. {@code p_eff} may exceed 1, in which case every variable
 * mutates.
 *
 * <p>For each variable {@code i}: draw {@code u ~ U[0,1]}; if {@code u ≤ p_eff},
 * replace the variable value with {@code yl + U[0,1] × (yu - yl)}. Every random number
 * (the jitter, the trigger draws and the new values) comes from the injected generator.
 */
public class RandomMutationWithRandomProbability implements MutationOperator<DoubleSolution> {

    /** Default jitter, in variables: the effective probability adds up to 3.5 / n. */
    public static final double DEFAULT_JITTER = 3.5;

    /** Base per-variable mutation probability; the effective probability adds a random jitter. */
    private double mutationProbability;

    /** Width of the jitter, in variables: {@code p_eff = p + U[0,1] × jitter / n}. */
    private final double jitter;

    /** Source of uniform random numbers in [0, 1). */
    private RandomGenerator<Double> randomGenerator;

    /**
     * Constructor using the default jMetal random generator.
     *
     * @param probability base mutation probability (must be ≥ 0)
     * @throws JMetalException if {@code probability} is negative or NaN
     */
    public RandomMutationWithRandomProbability(double probability) {
        this(probability, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Constructor with the default jitter ({@value #DEFAULT_JITTER}).
     *
     * @param probability     base mutation probability (must be ≥ 0)
     * @param randomGenerator supplier of uniform random doubles in [0, 1)
     * @throws JMetalException if {@code probability} is negative or NaN
     */
    public RandomMutationWithRandomProbability(double probability, RandomGenerator<Double> randomGenerator) {
        this(probability, DEFAULT_JITTER, randomGenerator);
    }

    /**
     * Full constructor.
     *
     * @param probability     base mutation probability (must be ≥ 0)
     * @param jitter          width of the jitter, in variables: the effective probability is
     *                        {@code probability + U[0,1] × jitter / n}; finite and ≥ 0, where 0
     *                        disables the jitter
     * @param randomGenerator supplier of uniform random doubles in [0, 1)
     * @throws JMetalException if {@code probability} is negative or NaN, or {@code jitter} is
     *                         negative or not finite
     */
    public RandomMutationWithRandomProbability(double probability, double jitter,
                                               RandomGenerator<Double> randomGenerator) {
        if (!(probability >= 0)) {
            throw new JMetalException("Mutation probability is negative: " + probability);
        }
        if (!(Double.isFinite(jitter) && jitter >= 0)) {
            throw new JMetalException("The jitter must be finite and not negative: " + jitter);
        }

        this.mutationProbability = probability;
        this.jitter = jitter;
        this.randomGenerator = randomGenerator;
    }

    /**
     * Returns the base mutation probability.
     *
     * @return base probability in [0, 1]
     */
    @Override
    public double mutationProbability() {
        return mutationProbability;
    }

    /**
     * Returns the width of the jitter, in variables.
     *
     * @return the jitter: the effective probability is {@code p + U[0,1] × jitter / n}
     */
    public double jitter() {
        return jitter;
    }

    /**
     * Sets the base mutation probability.
     *
     * @param mutationProbability new base probability (must be ≥ 0)
     */
    public void setMutationProbability(double mutationProbability) {
        this.mutationProbability = mutationProbability;
    }

    /**
     * Applies uniform random mutation with randomized probability to the given solution.
     *
     * @param solution the solution to mutate in-place
     * @return the mutated solution (same object)
     * @throws JMetalException if {@code solution} is {@code null}
     */
    @Override
    public DoubleSolution execute(DoubleSolution solution) throws JMetalException {
        if (null == solution) {
            throw new JMetalException("Null parameter");
        }

        doMutation(mutationProbability, solution);

        return solution;
    }

    /**
     * Performs the actual mutation.
     *
     * <p>Computes the effective probability {@code p_eff = probability + U[0,1] × jitter / n}
     * once per call, then iterates over all variables; each variable is independently
     * replaced by a uniform random value within its bounds with probability {@code p_eff}.
     *
     * @param probability base mutation probability
     * @param solution    solution to mutate in-place
     */
    private void doMutation(double probability, DoubleSolution solution) {
        double p = probability + randomGenerator.getRandomValue() * jitter / solution.variables().size();
        for (int i = 0; i < solution.variables().size(); i++) {
            if (randomGenerator.getRandomValue() <= p) {
                Bounds<Double> bounds = solution.getBounds(i);
                Double lowerBound = bounds.getLowerBound();
                Double upperBound = bounds.getUpperBound();
                Double randomValue = randomGenerator.getRandomValue();
                Double value = lowerBound + ((upperBound - lowerBound) * randomValue);

                solution.variables().set(i, value);
            }
        }
    }
}

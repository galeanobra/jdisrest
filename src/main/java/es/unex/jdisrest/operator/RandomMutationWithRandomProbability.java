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
 *   p_eff = mutationProbability + U[0,1] × 3.5 / n
 * </pre>
 * where {@code n} is the number of decision variables. The additive jitter scales
 * inversely with {@code n}, so the <em>expected number</em> of mutated variables
 * stays roughly constant regardless of problem dimension. This can help maintain
 * population diversity in high-dimensional search spaces.
 *
 * <p>For each variable {@code i}: draw {@code u ~ U[0,1]}; if {@code u ≤ p_eff},
 * replace the variable value with {@code yl + U[0,1] × (yu - yl)}.
 */
public class RandomMutationWithRandomProbability implements MutationOperator<DoubleSolution> {

    /** Base per-variable mutation probability; the effective probability adds a random jitter. */
    private double mutationProbability;

    /** Source of uniform random numbers in [0, 1). */
    private RandomGenerator<Double> randomGenerator;

    /**
     * Constructor using the default jMetal random generator.
     *
     * @param probability base mutation probability (must be ≥ 0)
     * @throws JMetalException if {@code probability} is negative
     */
    public RandomMutationWithRandomProbability(double probability) {
        this(probability, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param probability     base mutation probability (must be ≥ 0)
     * @param randomGenerator supplier of uniform random doubles in [0, 1)
     * @throws JMetalException if {@code probability} is negative
     */
    public RandomMutationWithRandomProbability(double probability, RandomGenerator<Double> randomGenerator) {
        if (probability < 0) {
            throw new JMetalException("Mutation probability is negative: " + mutationProbability);
        }

        this.mutationProbability = probability;
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
     * <p>Computes the effective probability {@code p_eff = probability + U[0,1] × 3.5 / n}
     * once per call, then iterates over all variables; each variable is independently
     * replaced by a uniform random value within its bounds with probability {@code p_eff}.
     *
     * @param probability base mutation probability
     * @param solution    solution to mutate in-place
     */
    private void doMutation(double probability, DoubleSolution solution) {
        double p = probability + randomGenerator.getRandomValue()*3.5/solution.variables().size();
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

package es.unex.jdisrest.operator;

import java.util.ArrayList;
import java.util.List;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * BLX-α (Blend Crossover Alpha) operator adapted for {@link IntegerSolution}.
 *
 * <p>BLX-α generates offspring by sampling uniformly from an interval that is
 * expanded by a factor of α beyond the range spanned by the two parents.
 * Concretely, for a pair of parent values {@code x1} and {@code x2} with
 * {@code min = min(x1,x2)} and {@code max = max(x1,x2)}, each offspring value
 * is drawn from {@code [min - range*α, max + range*α]} where
 * {@code range = max - min}.
 *
 * <p>This is a direct adaptation of the double-coded BLX-α. The only
 * difference is that the sampled real value is truncated to {@code int} after
 * being repaired to the variable bounds.
 *
 * <p>When {@code α = 0} the offspring are restricted to the interval between
 * the parents (no exploration beyond parents). The conventional default is
 * {@code α = 0.5}, which allows moderate exploration; values above 0.5 increase
 * exploration at the cost of diversity loss near the Pareto front.
 *
 * @author Antonio J. Nebro (original double version)
 * @see RepairDoubleSolution
 */
public class IntegerBLXCrossover implements CrossoverOperator<IntegerSolution> {

    /** Default α value used when no explicit alpha is provided (0.5). */
    private static final double DEFAULT_ALPHA = 0.5;

    /** Probability in [0,1] that crossover is applied to a pair of parents. */
    private double crossoverProbability;

    /**
     * Controls the exploration range beyond the parents' interval.
     * <ul>
     *   <li>0.0 — offspring are restricted to the interval [min(x1,x2), max(x1,x2)].</li>
     *   <li>0.5 — default; offspring can extend 50% of the inter-parent range beyond each parent.</li>
     * </ul>
     * Typical range: 0.0–0.5.
     */
    private double alpha;

    /**
     * Repair strategy applied after sampling to clip values that fall outside
     * the variable's declared bounds. The default implementation
     * ({@link RepairDoubleSolutionWithBoundValue}) clamps the value to the
     * nearest bound.
     */
    private final RepairDoubleSolution solutionRepair;

    /** Source of uniform random numbers in [0,1). */
    private final RandomGenerator<Double> randomGenerator;

    /**
     * Convenience constructor using the default α ({@value #DEFAULT_ALPHA}) and
     * bound-clamping repair.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     */
    public IntegerBLXCrossover(double crossoverProbability) {
        this(crossoverProbability, DEFAULT_ALPHA, new RepairDoubleSolutionWithBoundValue());
    }

    /**
     * Convenience constructor using bound-clamping repair.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     * @param alpha                BLX-α exploration parameter, must be ≥ 0
     */
    public IntegerBLXCrossover(double crossoverProbability, double alpha) {
        this(crossoverProbability, alpha, new RepairDoubleSolutionWithBoundValue());
    }

    /**
     * Constructor with explicit repair strategy and the default jMetal random generator.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     * @param alpha                BLX-α exploration parameter, must be ≥ 0
     * @param solutionRepair       strategy for clipping out-of-bounds values
     */
    public IntegerBLXCrossover(double crossoverProbability, double alpha, RepairDoubleSolution solutionRepair) {
        this(crossoverProbability, alpha, solutionRepair, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     * @param alpha                BLX-α exploration parameter, must be ≥ 0
     * @param solutionRepair       strategy for clipping out-of-bounds values
     * @param randomGenerator      supplier of uniform random doubles in [0,1)
     */
    public IntegerBLXCrossover(double crossoverProbability, double alpha,
                               RepairDoubleSolution solutionRepair,
                               RandomGenerator<Double> randomGenerator) {
        Check.probabilityIsValid(crossoverProbability);
        Check.that(alpha >= 0, "Alpha is negative: " + alpha);

        this.crossoverProbability = crossoverProbability;
        this.alpha = alpha;
        this.randomGenerator = randomGenerator;
        this.solutionRepair = solutionRepair;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the crossover probability.
     *
     * @return crossover probability in [0,1]
     */
    @Override
    public double crossoverProbability() {
        return crossoverProbability;
    }

    /**
     * Returns the current α parameter.
     *
     * @return alpha value (≥ 0)
     */
    public double alpha() {
        return alpha;
    }

    // -------------------------------------------------------------------------
    // Setters
    // -------------------------------------------------------------------------

    /**
     * Sets the crossover probability.
     *
     * @param crossoverProbability new probability value in [0,1]
     */
    public void crossoverProbability(double crossoverProbability) {
        this.crossoverProbability = crossoverProbability;
    }

    /**
     * Sets the α exploration parameter.
     *
     * @param alpha new alpha value (must be ≥ 0)
     */
    public void alpha(double alpha) {
        this.alpha = alpha;
    }

    // -------------------------------------------------------------------------
    // CrossoverOperator interface
    // -------------------------------------------------------------------------

    /**
     * Validates inputs and delegates to {@link #doCrossover}.
     *
     * @param solutions list of exactly two parent {@link IntegerSolution}s
     * @return list of two offspring solutions
     * @throws org.uma.jmetal.util.errorchecking.JMetalException if {@code solutions} is null or
     *         does not contain exactly two elements
     */
    @Override
    public List<IntegerSolution> execute(List<IntegerSolution> solutions) {
        Check.notNull(solutions);
        Check.that(solutions.size() == 2, "There must be two parents instead of " + solutions.size());

        return doCrossover(crossoverProbability, solutions.get(0), solutions.get(1));
    }

    /**
     * Performs the BLX-α crossover between two integer parents.
     *
     * <p>For each variable index {@code i}:
     * <ol>
     *   <li>Identify the ordered parent values: {@code min = min(x1,x2)},
     *       {@code max = max(x1,x2)}.</li>
     *   <li>Compute {@code range = max - min} (distance between parents).</li>
     *   <li>Expand the sampling interval by α:
     *       {@code minRange = min - range*α},
     *       {@code maxRange = max + range*α}.</li>
     *   <li>Sample two independent uniform values from {@code [minRange, maxRange]}.</li>
     *   <li>Repair each sampled value to the declared variable bounds
     *       (via {@link #solutionRepair}) and cast to {@code int}.</li>
     * </ol>
     * If the random draw is greater than {@code probability} the offspring are
     * returned as copies of the parents (no crossover applied).
     *
     * @param probability probability threshold for triggering crossover
     * @param parent1     first parent solution
     * @param parent2     second parent solution
     * @return list of two offspring solutions
     */
    public List<IntegerSolution> doCrossover(
            double probability, IntegerSolution parent1, IntegerSolution parent2) {

        List<IntegerSolution> offspring = new ArrayList<>(2);
        offspring.add((IntegerSolution) parent1.copy());
        offspring.add((IntegerSolution) parent2.copy());

        int i;
        double random;
        double valueY1;   // offspring 1 sampled value (real, before cast)
        double valueY2;   // offspring 2 sampled value (real, before cast)
        double valueX1;   // parent 1 value for variable i
        double valueX2;   // parent 2 value for variable i
        double upperBound;
        double lowerBound;

        if (randomGenerator.getRandomValue() <= probability) {
            for (i = 0; i < parent1.variables().size(); i++) {
                Bounds<Integer> bounds = parent1.getBounds(i);
                upperBound = bounds.getUpperBound();
                lowerBound = bounds.getLowerBound();
                valueX1 = parent1.variables().get(i);
                valueX2 = parent2.variables().get(i);

                // Order the two parent values so that min ≤ max.
                double max;
                double min;
                double range;

                if (valueX2 > valueX1) {
                    max = valueX2;
                    min = valueX1;
                } else {
                    max = valueX1;
                    min = valueX2;
                }

                // range: distance between the two parent values.
                range = max - min;

                // Expand sampling interval by α in both directions.
                double minRange;
                double maxRange;

                minRange = min - range * alpha;   // lower exploration boundary
                maxRange = max + range * alpha;   // upper exploration boundary

                // Sample offspring values uniformly from [minRange, maxRange].
                random = randomGenerator.getRandomValue();
                valueY1 = minRange + random * (maxRange - minRange);

                random = randomGenerator.getRandomValue();
                valueY2 = minRange + random * (maxRange - minRange);

                // Clip to declared variable bounds before truncating to int.
                // solutionRepair clamps the value to [lowerBound, upperBound].
                valueY1 = solutionRepair.repairSolutionVariableValue(valueY1, lowerBound, upperBound);
                valueY2 = solutionRepair.repairSolutionVariableValue(valueY2, lowerBound, upperBound);

                // Truncate real value to integer (floor towards zero).
                offspring.get(0).variables().set(i, (int) valueY1);
                offspring.get(1).variables().set(i, (int) valueY2);
            }
        }

        return offspring;
    }

    /**
     * Returns the number of parent solutions required by this operator.
     *
     * @return 2
     */
    public int numberOfRequiredParents() {
        return 2;
    }

    /**
     * Returns the number of offspring solutions generated by this operator.
     *
     * @return 2
     */
    public int numberOfGeneratedChildren() {
        return 2;
    }
}

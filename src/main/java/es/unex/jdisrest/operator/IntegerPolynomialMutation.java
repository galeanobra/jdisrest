package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.integerproblem.IntegerProblem;
import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Polynomial mutation for {@link IntegerSolution}, with the new values rounded to the nearest
 * integer.
 *
 * <p>Each variable is mutated independently with probability {@code mutationProbability}. A
 * mutated variable moves by a fraction {@code δq} of the width of its range, drawn from the
 * polynomial distribution: a large distribution index makes small steps, and a value near a
 * bound moves less toward it. The real result goes through the repair (by default
 * {@link RepairDoubleSolutionWithBoundValue}, which clamps it to the bounds), is rounded to the
 * nearest integer and is clamped to the bounds of the variable. A variable whose lower and upper
 * bounds are equal takes that value without consuming more random numbers, and without calling
 * the repair, which for jMetal's bound repair would throw.
 *
 * <h2>Not jMetal's class of the same name</h2>
 * <p>The algorithm, the constructors and the methods are those of jMetal 7.1's
 * {@code org.uma.jmetal.operator.mutation.impl.IntegerPolynomialMutation} (MIT license), draw
 * for draw, so changing the import is enough to switch. jMetal converts the new value to
 * {@code int} with a cast, which truncates toward zero: a mutated variable of non-negative values
 * moves about half a unit down on average (up for negative values), and in [0, 1] a 0 never
 * becomes a 1 while a 1 becomes a 0 about half of the times it mutates, so such a variable
 * drifts to 0. This class rounds the value to the nearest integer, so a mutation from the middle
 * of the range is centred on the current value, and in [0, 1] both changes are equally likely.
 * Check the import: the wrong one compiles and silently changes the search.
 *
 * <p>The argument checks are stricter than jMetal's, with the same exception: a NaN mutation
 * probability or distribution index is rejected as a negative one is, and the setters check
 * their argument as the constructors do.
 *
 * <p>A mutation step is a fraction of the range, so a short range makes few steps that reach the
 * next integer: with the default distribution index of 20, a mutation in [0, 1] changes the
 * value about once in four million. {@link IntegerSimpleRandomMutation} suits such variables
 * better.
 *
 * <h2>Randomness</h2>
 * <p>Every random number comes from the injected generator, by default {@link JMetalRandom}: one
 * draw per variable decides whether it mutates and, if it does and its bounds differ, one more
 * gives the step. A repair that draws, such as jMetal's
 * {@code RepairDoubleSolutionWithRandomValue}, uses its own generator.
 *
 * @author Antonio J. Nebro (original jMetal class)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 * @see RepairDoubleSolution
 */
@SuppressWarnings("serial")
public class IntegerPolynomialMutation implements MutationOperator<IntegerSolution> {

    /** Mutation probability of the constructor without arguments (0.01). */
    private static final double DEFAULT_PROBABILITY = 0.01;

    /** Distribution index used when none is given (20). */
    private static final double DEFAULT_DISTRIBUTION_INDEX = 20.0;

    /** Probability that a given variable is mutated; 1 or more mutates every variable. */
    private double mutationProbability;

    /** Non-negative distribution index; larger values make smaller steps. */
    private double distributionIndex;

    /** Strategy for a real value that ends outside the bounds of its variable. */
    private final RepairDoubleSolution solutionRepair;

    /** Source of uniform random numbers in [0,1). */
    private final RandomGenerator<Double> randomGenerator;

    /**
     * Constructor with the default distribution index ({@value #DEFAULT_DISTRIBUTION_INDEX}),
     * bound-clamping repair and the default jMetal random generator.
     *
     * @param mutationProbability per-variable mutation probability, must be ≥ 0
     */
    public IntegerPolynomialMutation(double mutationProbability) {
        this(mutationProbability, DEFAULT_DISTRIBUTION_INDEX);
    }

    /**
     * Constructor with a mutation probability of {@value #DEFAULT_PROBABILITY} and the default
     * distribution index ({@value #DEFAULT_DISTRIBUTION_INDEX}).
     */
    public IntegerPolynomialMutation() {
        this(DEFAULT_PROBABILITY, DEFAULT_DISTRIBUTION_INDEX);
    }

    /**
     * Constructor with a mutation probability of {@code 1 / problem.numberOfVariables()}.
     *
     * @param problem           the problem whose solutions are mutated
     * @param distributionIndex distribution index, must be ≥ 0
     */
    public IntegerPolynomialMutation(IntegerProblem problem, double distributionIndex) {
        this(1.0 / problem.numberOfVariables(), distributionIndex);
    }

    /**
     * Constructor with bound-clamping repair and the default jMetal random generator.
     *
     * @param mutationProbability per-variable mutation probability, must be ≥ 0
     * @param distributionIndex   distribution index, must be ≥ 0
     */
    public IntegerPolynomialMutation(double mutationProbability, double distributionIndex) {
        this(mutationProbability, distributionIndex, new RepairDoubleSolutionWithBoundValue());
    }

    /**
     * Constructor with an explicit repair strategy and the default jMetal random generator.
     *
     * @param mutationProbability per-variable mutation probability, must be ≥ 0
     * @param distributionIndex   distribution index, must be ≥ 0
     * @param solutionRepair      strategy for a real value outside the bounds
     */
    public IntegerPolynomialMutation(double mutationProbability, double distributionIndex,
                                     RepairDoubleSolution solutionRepair) {
        this(mutationProbability, distributionIndex, solutionRepair,
            () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param mutationProbability per-variable mutation probability, must be ≥ 0
     * @param distributionIndex   distribution index, must be ≥ 0
     * @param solutionRepair      strategy for a real value outside the bounds
     * @param randomGenerator     supplier of uniform random doubles in [0,1)
     * @throws JMetalException if {@code mutationProbability} or {@code distributionIndex} is
     *                         negative or NaN
     */
    public IntegerPolynomialMutation(double mutationProbability, double distributionIndex,
                                     RepairDoubleSolution solutionRepair,
                                     RandomGenerator<Double> randomGenerator) {
        checkMutationProbability(mutationProbability);
        checkDistributionIndex(distributionIndex);

        this.mutationProbability = mutationProbability;
        this.distributionIndex = distributionIndex;
        this.solutionRepair = solutionRepair;
        this.randomGenerator = randomGenerator;
    }

    /** jMetal's check, which also rejects NaN. */
    private static void checkMutationProbability(double mutationProbability) {
        if (!(mutationProbability >= 0)) {
            throw new JMetalException("Mutation probability is negative: " + mutationProbability);
        }
    }

    /** jMetal's check, which also rejects NaN: a NaN index would turn every mutated value into 0. */
    private static void checkDistributionIndex(double distributionIndex) {
        if (!(distributionIndex >= 0)) {
            throw new JMetalException("Distribution index is negative: " + distributionIndex);
        }
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    /**
     * Returns the per-variable mutation probability.
     *
     * @return mutation probability (≥ 0)
     */
    @Override
    public double mutationProbability() {
        return mutationProbability;
    }

    /**
     * Returns the distribution index.
     *
     * @return distribution index (≥ 0)
     */
    public double getDistributionIndex() {
        return distributionIndex;
    }

    /**
     * Sets the distribution index, with the constructor's check.
     *
     * @param distributionIndex new distribution index (must be ≥ 0)
     * @throws JMetalException if {@code distributionIndex} is negative or NaN; the index is then
     *                         left unchanged
     */
    public void setDistributionIndex(double distributionIndex) {
        checkDistributionIndex(distributionIndex);
        this.distributionIndex = distributionIndex;
    }

    /**
     * Sets the per-variable mutation probability, with the constructor's check.
     *
     * @param mutationProbability new probability value (must be ≥ 0)
     * @throws JMetalException if {@code mutationProbability} is negative or NaN; the probability
     *                         is then left unchanged
     */
    public void setMutationProbability(double mutationProbability) {
        checkMutationProbability(mutationProbability);
        this.mutationProbability = mutationProbability;
    }

    // -------------------------------------------------------------------------
    // MutationOperator interface
    // -------------------------------------------------------------------------

    /**
     * Applies the polynomial mutation to the given solution in-place.
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
     * Applies the polynomial mutation to each variable of the solution.
     *
     * <p>For every variable {@code i}:
     * <ol>
     *   <li>Draw a uniform value; if it exceeds {@code probability}, skip this variable.</li>
     *   <li>If the lower and upper bounds are equal, set the variable to that value.</li>
     *   <li>Otherwise draw the step {@code δq} from a second uniform value, add
     *       {@code δq * (upperBound - lowerBound)} to the current value, pass the result through
     *       the repair, round it to the nearest integer and clamp it to the bounds.</li>
     * </ol>
     * The arithmetic is done in {@code double} and {@code long}, so any {@code int} range works.
     *
     * @param probability per-variable mutation probability
     * @param solution    solution to mutate in-place
     */
    private void doMutation(double probability, IntegerSolution solution) {
        for (int i = 0; i < solution.variables().size(); i++) {
            if (randomGenerator.getRandomValue() <= probability) {
                Bounds<Integer> bounds = solution.getBounds(i);
                int lowerBound = bounds.getLowerBound();
                int upperBound = bounds.getUpperBound();

                // A fixed variable has a single feasible value. jMetal's bound repair would throw
                // on it (it requires lowerBound < upperBound), so it is set before any repair.
                if (lowerBound == upperBound) {
                    solution.variables().set(i, lowerBound);
                    continue;
                }

                double y = solution.variables().get(i);
                double yl = lowerBound;
                double yu = upperBound;
                double delta1 = (y - yl) / (yu - yl);
                double delta2 = (yu - y) / (yu - yl);
                double rnd = randomGenerator.getRandomValue();
                double mutPow = 1.0 / (distributionIndex + 1.0);
                double deltaq;
                if (rnd <= 0.5) {
                    double xy = 1.0 - delta1;
                    double val = 2.0 * rnd + (1.0 - 2.0 * rnd) * Math.pow(xy, distributionIndex + 1.0);
                    deltaq = Math.pow(val, mutPow) - 1.0;
                } else {
                    double xy = 1.0 - delta2;
                    double val = 2.0 * (1.0 - rnd) + 2.0 * (rnd - 0.5) * Math.pow(xy, distributionIndex + 1.0);
                    deltaq = 1.0 - Math.pow(val, mutPow);
                }
                y = y + deltaq * (yu - yl);
                y = solutionRepair.repairSolutionVariableValue(y, yl, yu);

                // Round to the nearest integer (to a long, which saturates instead of wrapping)
                // and clamp, whatever the repair returned. jMetal's (int) cast truncated toward
                // zero instead.
                long value = Math.max(lowerBound, Math.min(upperBound, Math.round(y)));
                solution.variables().set(i, (int) value);
            }
        }
    }
}

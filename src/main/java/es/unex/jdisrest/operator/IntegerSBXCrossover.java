package es.unex.jdisrest.operator;

import java.util.ArrayList;
import java.util.List;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Simulated binary crossover (SBX) for {@link IntegerSolution}, with the children rounded to the
 * nearest integer.
 *
 * <p>For each variable a fair coin decides whether the two parent values are crossed or swapped
 * between the children. A crossed pair of distinct values {@code y1 < y2} gives the real values
 * {@code c1 = ((y1 + y2) - βq (y2 - y1)) / 2} and {@code c2 = ((y1 + y2) + βq (y2 - y1)) / 2},
 * where the spread factor {@code βq} comes from one draw, the distribution index and the distance
 * of each parent to its bound; they are rounded to the nearest integer, clamped to the bounds of
 * the variable and handed to the children in an order that a last coin decides. A large
 * distribution index keeps the children close to their parents. Equal parent values are copied,
 * so a variable whose lower and upper bounds are equal keeps its value.
 *
 * <h2>Not jMetal's class of the same name</h2>
 * <p>The algorithm, the constructors and the methods are those of jMetal 7.1's
 * {@code org.uma.jmetal.operator.crossover.impl.IntegerSBXCrossover} (MIT license), draw for draw,
 * so changing the import is enough to switch. jMetal converts {@code c1} and {@code c2} to
 * {@code int} with a cast, which truncates toward zero: the children of a crossed variable of
 * non-negative values are about half a unit too low on average (too high for negative values),
 * and the parents 0 and 1 of a variable in [0, 1] give two children of 0 whenever they are
 * crossed, so such a variable drifts to 0. This class rounds them to the nearest integer, so the
 * children are centred where the real-coded SBX puts them, and parents 0 and 1 give one child of
 * each. Check the import: the wrong one compiles and silently changes the search.
 *
 * <p>The other differences are smaller. The parent values are compared in {@code double}, so
 * any {@code int} range works: jMetal subtracts them as {@code int}s, so two parents
 * {@code 2^31} apart (such as {@code Integer.MIN_VALUE} and 0) overflow and count as equal. A
 * child is clamped after it is rounded, so it stays within the bounds even when a parent lies
 * outside them (a warm start can give one) by more than half the gap between the parents, which
 * makes the spread factor NaN: jMetal's cast turns such a child into 0, wherever the bounds are.
 * The argument checks are stricter than jMetal's: a negative distribution index gets jMetal's
 * exception, a NaN one is rejected too, and the setters check their argument as the
 * constructors do.
 *
 * <h2>Randomness</h2>
 * <p>Every random number comes from the injected generator, by default {@link JMetalRandom}: one
 * draw decides whether the crossover is applied and, if it is, one per variable decides between
 * crossing and swapping, and a crossed pair of distinct values takes two more, for the spread
 * and the order of the children. The children are always new copies, never the parents
 * themselves.
 *
 * @author Antonio J. Nebro (original jMetal class)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SuppressWarnings("serial")
public class IntegerSBXCrossover implements CrossoverOperator<IntegerSolution> {

    /** Distribution index used when none is given (20). */
    private static final double DEFAULT_DISTRIBUTION_INDEX = 20.0;

    /** Smallest difference between two parent values that counts as distinct. */
    private static final double EPS = 1.0e-14;

    /** Probability in [0,1] that crossover is applied to a pair of parents. */
    private double crossoverProbability;

    /** Non-negative distribution index; larger values keep the children closer to their parents. */
    private double distributionIndex;

    /** Source of uniform random numbers in [0,1). */
    private final RandomGenerator<Double> randomGenerator;

    /**
     * Constructor with the default distribution index ({@value #DEFAULT_DISTRIBUTION_INDEX}) and
     * the default jMetal random generator.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     */
    public IntegerSBXCrossover(double crossoverProbability) {
        this(crossoverProbability, DEFAULT_DISTRIBUTION_INDEX);
    }

    /**
     * Constructor with the default jMetal random generator.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     * @param distributionIndex    distribution index, must be ≥ 0
     */
    public IntegerSBXCrossover(double crossoverProbability, double distributionIndex) {
        this(crossoverProbability, distributionIndex, () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param crossoverProbability probability of applying crossover, must be in [0,1]
     * @param distributionIndex    distribution index, must be ≥ 0
     * @param randomGenerator      supplier of uniform random doubles in [0,1)
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if {@code crossoverProbability} is not in [0, 1]
     * @throws org.uma.jmetal.util.errorchecking.exception.NegativeValueException
     *         if {@code distributionIndex} is negative, as in jMetal
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if {@code distributionIndex} is NaN
     */
    public IntegerSBXCrossover(double crossoverProbability, double distributionIndex,
                               RandomGenerator<Double> randomGenerator) {
        Check.probabilityIsValid(crossoverProbability);
        checkDistributionIndex(distributionIndex);

        this.crossoverProbability = crossoverProbability;
        this.distributionIndex = distributionIndex;
        this.randomGenerator = randomGenerator;
    }

    /** jMetal's check, then one for NaN, which would make every spread factor NaN. */
    private static void checkDistributionIndex(double distributionIndex) {
        Check.valueIsNotNegative(distributionIndex);
        Check.that(!Double.isNaN(distributionIndex), "Distribution index is NaN");
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
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
     * @throws org.uma.jmetal.util.errorchecking.exception.NegativeValueException
     *         if {@code distributionIndex} is negative; the index is then left unchanged
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException
     *         if {@code distributionIndex} is NaN; the index is then left unchanged
     */
    public void setDistributionIndex(double distributionIndex) {
        checkDistributionIndex(distributionIndex);
        this.distributionIndex = distributionIndex;
    }

    /**
     * Sets the crossover probability, with the constructor's check.
     *
     * @param crossoverProbability new probability value in [0,1]
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the value is not in [0, 1]; the probability is then left unchanged
     */
    public void setCrossoverProbability(double crossoverProbability) {
        Check.probabilityIsValid(crossoverProbability);
        this.crossoverProbability = crossoverProbability;
    }

    // -------------------------------------------------------------------------
    // CrossoverOperator interface
    // -------------------------------------------------------------------------

    /**
     * Validates inputs and delegates to {@link #doCrossover}.
     *
     * @param solutions list of exactly two parent {@link IntegerSolution}s
     * @return list of two offspring solutions
     * @throws JMetalException if {@code solutions} is null or does not contain exactly two elements
     */
    @Override
    public List<IntegerSolution> execute(List<IntegerSolution> solutions) {
        if (null == solutions) {
            throw new JMetalException("Null parameter");
        } else if (solutions.size() != 2) {
            throw new JMetalException("There must be two parents instead of " + solutions.size());
        }

        return doCrossover(crossoverProbability, solutions.get(0), solutions.get(1));
    }

    /**
     * Performs the SBX crossover between two integer parents.
     *
     * <p>The children start as copies of the parents. If the first draw is greater than
     * {@code probability} they are returned as they are. Otherwise, for each variable {@code i}:
     * <ol>
     *   <li>A draw greater than 0.5 swaps the parent values: the first child takes the second
     *       parent's and the second child the first parent's.</li>
     *   <li>Otherwise equal parent values are kept, and distinct ones {@code y1 < y2} are
     *       crossed: a draw gives {@code c1} and {@code c2} (see the class description), each is
     *       rounded to the nearest {@code int} and clamped to {@code [lowerBound, upperBound]},
     *       and a last draw no greater than 0.5 gives {@code c2} to the first child and
     *       {@code c1} to the second, otherwise the other way round.</li>
     * </ol>
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

        if (randomGenerator.getRandomValue() <= probability) {
            for (int i = 0; i < parent1.variables().size(); i++) {
                int valueX1 = parent1.variables().get(i);
                int valueX2 = parent2.variables().get(i);

                if (randomGenerator.getRandomValue() > 0.5) {
                    offspring.get(0).variables().set(i, valueX2);
                    offspring.get(1).variables().set(i, valueX1);
                    continue;
                }

                // Compared in double: an int subtraction overflows for values 2^31 apart.
                if (Math.abs((double) valueX1 - valueX2) <= EPS) {
                    offspring.get(0).variables().set(i, valueX1);
                    offspring.get(1).variables().set(i, valueX2);
                    continue;
                }

                double y1 = Math.min(valueX1, valueX2);
                double y2 = Math.max(valueX1, valueX2);
                Bounds<Integer> bounds = parent1.getBounds(i);
                double yL = bounds.getLowerBound();
                double yU = bounds.getUpperBound();

                // One draw spreads both children, each limited by the distance of its parent to
                // its bound.
                double rand = randomGenerator.getRandomValue();
                double c1 = 0.5 * ((y1 + y2) - spread(rand, 1.0 + (2.0 * (y1 - yL) / (y2 - y1))) * (y2 - y1));
                double c2 = 0.5 * ((y1 + y2) + spread(rand, 1.0 + (2.0 * (yU - y2) / (y2 - y1))) * (y2 - y1));

                // Round to the nearest integer, then clamp: a parent outside its bounds can make a
                // spread factor NaN, which rounds to 0. jMetal's (int) cast truncated toward zero
                // instead, and clamped first.
                int child1 = (int) Math.max(yL, Math.min(yU, Math.round(c1)));
                int child2 = (int) Math.max(yL, Math.min(yU, Math.round(c2)));

                if (randomGenerator.getRandomValue() <= 0.5) {
                    offspring.get(0).variables().set(i, child2);
                    offspring.get(1).variables().set(i, child1);
                } else {
                    offspring.get(0).variables().set(i, child1);
                    offspring.get(1).variables().set(i, child2);
                }
            }
        }

        return offspring;
    }

    /**
     * The spread factor {@code βq} of a draw, for a parent whose distance to its bound gives
     * {@code beta = 1 + 2 * distance / (y2 - y1)}: the bounded SBX distribution, as jMetal (and
     * its real-coded {@code SBXCrossover}) computes it.
     */
    private double spread(double rand, double beta) {
        double alpha = 2.0 - Math.pow(beta, -(distributionIndex + 1.0));
        if (rand <= (1.0 / alpha)) {
            return Math.pow(rand * alpha, 1.0 / (distributionIndex + 1.0));
        }
        return Math.pow(1.0 / (2.0 - rand * alpha), 1.0 / (distributionIndex + 1.0));
    }

    /**
     * Returns the number of parent solutions required by this operator.
     *
     * @return 2
     */
    @Override
    public int numberOfRequiredParents() {
        return 2;
    }

    /**
     * Returns the number of offspring solutions generated by this operator.
     *
     * @return 2
     */
    @Override
    public int numberOfGeneratedChildren() {
        return 2;
    }
}

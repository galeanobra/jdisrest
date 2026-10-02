package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.LevyFlightMutation;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Lévy flight mutation whose step size is drawn anew for every solution it mutates, uniform in
 * [0, {@code maximumStepSize}).
 *
 * <p>Each call draws the step size and then mutates exactly as jMetal's
 * {@link LevyFlightMutation} would with it: every variable, with the mutation probability, moves
 * by a Lévy step of exponent {@code beta} (Mantegna's algorithm) times the step size times the
 * variable range, and is repaired if it leaves its bounds. The solution is mutated in place and
 * returned, as jMetal does.
 *
 * <h2>Why a random step size</h2>
 * <p>A fixed step size gives every child the same scale: a small one only explores around the
 * solution, and a large one pins many variables to their bounds and behaves almost like a random
 * mutation. Drawing it per solution mixes both kinds of children in the same run. The draw is
 * uniform, so the mean step size is {@code maximumStepSize / 2}, half the strength of a
 * {@link LevyFlightMutation} whose fixed step size equals the maximum, and only one draw in ten
 * falls below {@code maximumStepSize / 10}.
 *
 * <h2>Choosing beta</h2>
 * <p>{@code beta} must lie in the open interval (1, 2). Mantegna's scale contains
 * sin(&pi;&middot;beta/2), which vanishes at 2: jMetal's {@link LevyFlightMutation} accepts
 * {@code beta = 2}, but its steps there are of the order of 1e-8 and nothing visibly mutates.
 * The collapse is gradual, not only at the endpoint: the scale is about 0.71 at beta 1.5, 0.34 at
 * 1.9 and 0.11 at 1.99, so steps shrink strongly as beta approaches 2. Values close to 1 give
 * heavier tails, that is, more long jumps; 1.5 is jMetal's default.
 *
 * <h2>Bounds and repair</h2>
 * <p>The constructor without a repair clamps a value that leaves its bounds to the nearest bound,
 * as {@link RepairDoubleSolutionWithBoundValue} does, and leaves a variable whose lower and upper
 * bounds are equal at that value. jMetal's repair, the default of {@link LevyFlightMutation},
 * throws {@link InvalidConditionException} for such a variable instead; inside a jdisrest master
 * that would happen during task creation and fail the worker's task request. A repair passed to
 * the full constructor is used as given.
 *
 * <h2>Randomness</h2>
 * <p>The step size draw and the Lévy steps come from the same generator, by default
 * {@link JMetalRandom}, so a seed reproduces the mutations: the first number of each call is the
 * step size draw, and the following ones are exactly those {@link LevyFlightMutation} consumes.
 * A draw of exactly 0 leaves the solution unchanged and consumes nothing more (jMetal rejects a
 * step size of 0, which would not move any variable anyway). The operator holds no mutable state
 * and is thread-safe whenever its generator and its repair are.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
@SuppressWarnings("serial")
public class LevyFlightMutationRandomStepSize implements MutationOperator<DoubleSolution> {

    /**
     * Default repair: {@link RepairDoubleSolutionWithBoundValue}, except that a variable whose
     * bounds are equal takes that value instead of making jMetal's repair throw.
     */
    private static final RepairDoubleSolution BOUND_REPAIR = new RepairDoubleSolution() {
        private final RepairDoubleSolution clamp = new RepairDoubleSolutionWithBoundValue();

        @Override
        public double repairSolutionVariableValue(double value, double lowerBound, double upperBound) {
            return lowerBound == upperBound
                    ? lowerBound
                    : clamp.repairSolutionVariableValue(value, lowerBound, upperBound);
        }
    };

    /** Probability in [0, 1] of mutating each variable. */
    private final double mutationProbability;

    /** Exponent of the Lévy distribution, in (1, 2). */
    private final double beta;

    /** Upper end, finite and positive, of the step size drawn for each solution. */
    private final double maximumStepSize;

    /** Repair of the variables that leave their bounds. */
    private final RepairDoubleSolution solutionRepair;

    /** Uniform numbers in [0, 1), for the step size draw and the Lévy steps. */
    private final RandomGenerator<Double> randomGenerator;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Mutation with the default repair (clamp to the bounds, equal bounds tolerated) and the
     * jMetal random generator.
     *
     * @param mutationProbability probability of mutating each variable, in [0, 1]
     * @param beta                exponent of the Lévy distribution, in (1, 2)
     * @param maximumStepSize     upper end of the step size drawn for each solution, finite and
     *                            greater than 0
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code beta} or {@code maximumStepSize} is out of range
     */
    public LevyFlightMutationRandomStepSize(double mutationProbability, double beta, double maximumStepSize) {
        this(mutationProbability, beta, maximumStepSize, BOUND_REPAIR,
                () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Mutation with an explicit repair and random generator.
     *
     * @param mutationProbability probability of mutating each variable, in [0, 1]
     * @param beta                exponent of the Lévy distribution, in (1, 2): at 2 the steps of
     *                            Mantegna's algorithm vanish
     * @param maximumStepSize     upper end of the step size drawn for each solution, finite and
     *                            greater than 0
     * @param solutionRepair      repair of the variables that leave their bounds, used as given
     * @param randomGenerator     uniform numbers in [0, 1), for the step size draw and the Lévy
     *                            steps
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code beta} or {@code maximumStepSize} is out of range
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if the repair or the generator is {@code null}
     */
    public LevyFlightMutationRandomStepSize(double mutationProbability, double beta, double maximumStepSize,
            RepairDoubleSolution solutionRepair, RandomGenerator<Double> randomGenerator) {
        Check.probabilityIsValid(mutationProbability);
        Check.that(beta > 1.0 && beta < 2.0, "beta must be in (1, 2), got " + beta);
        Check.that(Double.isFinite(maximumStepSize) && maximumStepSize > 0.0,
                "the maximum step size must be finite and greater than 0, got " + maximumStepSize);
        Check.notNull(solutionRepair, "solutionRepair");
        Check.notNull(randomGenerator, "randomGenerator");
        this.mutationProbability = mutationProbability;
        this.beta = beta;
        this.maximumStepSize = maximumStepSize;
        this.solutionRepair = solutionRepair;
        this.randomGenerator = randomGenerator;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Override
    public double mutationProbability() {
        return mutationProbability;
    }

    /**
     * Exponent of the Lévy distribution.
     *
     * @return beta, in (1, 2)
     */
    public double beta() {
        return beta;
    }

    /**
     * Upper end of the step size drawn for each solution; the mean step size is half of it.
     *
     * @return the maximum step size, finite and greater than 0
     */
    public double maximumStepSize() {
        return maximumStepSize;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Draws a step size in [0, {@code maximumStepSize}) and mutates the solution in place as
     * jMetal's {@link LevyFlightMutation} with that step size.
     *
     * @param solution the solution to mutate
     * @return the same solution instance, mutated (unchanged when the draw is exactly 0)
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code solution} is {@code null}
     */
    @Override
    public DoubleSolution execute(DoubleSolution solution) {
        Check.notNull(solution, "solution");
        double stepSize = randomGenerator.getRandomValue() * maximumStepSize;
        return stepSize > 0.0
                ? new LevyFlightMutation(mutationProbability, beta, stepSize, solutionRepair, randomGenerator)
                        .execute(solution)
                : solution;
    }
}

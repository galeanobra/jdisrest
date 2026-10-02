package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.pseudorandom.BoundedRandomGenerator;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * N-point crossover for {@link DoubleSolution}: both parents are cut at the same
 * {@code numberOfPoints} random positions, and the children take the segments between cuts
 * alternately from one parent and the other.
 *
 * <p>The first child starts with the first parent's variables, switches to the second parent's
 * at the first cut, back at the second, and so on; the second child is its complement. Values
 * are copied, never combined, so every variable of a child is a value one of the parents had at
 * that position and stays within its bounds, including a variable whose lower and upper bounds
 * are equal.
 *
 * <h2>Differences with jMetal's {@code NPointCrossover}</h2>
 * <ul>
 *   <li><b>Copies when the crossover is not applied.</b> The children are then fresh copies of
 *       the parents. jMetal's {@link org.uma.jmetal.operator.crossover.impl.NPointCrossover}
 *       returns the input list itself, so its offspring alias the parents and a mutation applied
 *       in place afterwards silently changes solutions that are already evaluated (the defect
 *       {@link SafeCompositeCrossover} works around). The copy protects callers that mutate the
 *       offspring in place, such as jMetal's generational algorithms and the local
 *       {@link es.unex.jdisrest.local.algorithms.NSGAII NSGAII}. jdisrest's steady-state
 *       algorithms already copy the offspring before mutating them, so there it only costs one
 *       redundant copy per child.</li>
 *   <li><b>Exactly {@code numberOfPoints} cuts.</b> The cuts are distinct positions between two
 *       variables, drawn without replacement. jMetal draws each point independently in
 *       [0, n&nbsp;&minus;&nbsp;1] for n variables, so two points can coincide and a point at
 *       n&nbsp;&minus;&nbsp;1 cuts nothing: with 2 points, (3n&nbsp;&minus;&nbsp;2)/n&sup2; of
 *       its crossovers (about 3/n) make fewer cuts than asked.</li>
 *   <li><b>{@link DoubleSolution} only.</b> jMetal's operator is generic over any solution with
 *       {@link Number} variables.</li>
 * </ul>
 *
 * <h2>Blocks of variables</h2>
 * <p>With a block size b greater than 1, the variables are taken in consecutive blocks of b and
 * the cuts fall only between blocks, so every block passes whole from a parent to a child. This
 * suits genomes made of fixed-size tuples, such as the (x, y, z) coordinates of each point of a
 * shape or the (amplitude, frequency, phase) of each term of a sum: a cut never mixes the parts
 * of one tuple taken from different parents. The rules, enforced by {@link #check}, are:
 * <ul>
 *   <li>the number of variables must be a multiple of b, so every block is complete;</li>
 *   <li>there must be at least 2 blocks, so there is a boundary to cut;</li>
 *   <li>the number of points must be smaller than the number of blocks. With as many points as
 *       boundaries every boundary is cut, and the children alternate whole blocks
 *       deterministically.</li>
 * </ul>
 * A block size of 1, which the constructors without one use, is the plain n-point crossover:
 * the candidate cuts, and therefore the random stream consumed, are exactly the same.
 *
 * <h2>When the sizes are checked</h2>
 * <p>The number of variables is only known when the operator meets its first parents, so the
 * rules above are enforced by {@link #execute(List)}, on every call and before the probability
 * draw, with an {@link InvalidConditionException}. Inside a jdisrest steady-state master that is
 * late: the first crossover happens only after the initial solutions (in
 * {@link es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm}, once the population
 * holds more than 2), inside the task creation that serves a worker's request. A block size that
 * does not fit the problem therefore lets the run start and evaluate its first solutions, and
 * then fails every task request. Call {@link #check(int, int, int)} with the problem's number of
 * variables before starting the run to fail early.
 *
 * <h2>Randomness</h2>
 * <p>Each call draws one uniform number in [0, 1) to decide whether to cross and, when it does,
 * {@code numberOfPoints} bounded integers to choose the cuts by a partial Fisher&ndash;Yates
 * shuffle of the candidate positions, so every set of cuts is equally likely. Both generators
 * default to {@link JMetalRandom}, so seeding it reproduces the children. The operator holds no
 * mutable state and is thread-safe whenever its generators are.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
@SuppressWarnings("serial")
public class DoubleNPointCrossover implements CrossoverOperator<DoubleSolution> {

    /** Probability in [0, 1] that the crossover is applied to a pair of parents. */
    private final double crossoverProbability;

    /** Number of cuts of each applied crossover, at least 1 and smaller than the number of blocks. */
    private final int numberOfPoints;

    /** Number of consecutive variables a cut never separates, at least 1. */
    private final int blockSize;

    /** Uniform numbers in [0, 1), to decide whether to cross. */
    private final RandomGenerator<Double> randomGenerator;

    /** Uniform integers in [lower, upper], both inclusive, to choose the cuts. */
    private final BoundedRandomGenerator<Integer> pointGenerator;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Plain n-point crossover (block size 1) drawing from {@link JMetalRandom}.
     *
     * @param crossoverProbability probability of applying the crossover, in [0, 1]
     * @param numberOfPoints       number of cuts, at least 1 and smaller than the number of
     *                             variables of the solutions
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code numberOfPoints < 1}
     */
    public DoubleNPointCrossover(double crossoverProbability, int numberOfPoints) {
        this(crossoverProbability, numberOfPoints, 1);
    }

    /**
     * N-point crossover between blocks of {@code blockSize} variables, drawing from
     * {@link JMetalRandom}.
     *
     * @param crossoverProbability probability of applying the crossover, in [0, 1]
     * @param numberOfPoints       number of cuts, at least 1 and smaller than the number of
     *                             blocks of the solutions
     * @param blockSize            number of consecutive variables a cut never separates, at
     *                             least 1; the number of variables must be a multiple of it
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code numberOfPoints < 1} or {@code blockSize < 1}
     */
    public DoubleNPointCrossover(double crossoverProbability, int numberOfPoints, int blockSize) {
        this(crossoverProbability, numberOfPoints, blockSize,
                () -> JMetalRandom.getInstance().nextDouble(),
                (lower, upper) -> JMetalRandom.getInstance().nextInt(lower, upper));
    }

    /**
     * Plain n-point crossover (block size 1) with explicit random generators.
     *
     * @param crossoverProbability probability of applying the crossover, in [0, 1]
     * @param numberOfPoints       number of cuts, at least 1 and smaller than the number of
     *                             variables of the solutions
     * @param randomGenerator      uniform numbers in [0, 1), to decide whether to cross
     * @param pointGenerator       uniform integers in [lower, upper], both inclusive, to choose
     *                             the cuts
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code numberOfPoints < 1}
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if a generator is {@code null}
     */
    public DoubleNPointCrossover(double crossoverProbability, int numberOfPoints,
            RandomGenerator<Double> randomGenerator, BoundedRandomGenerator<Integer> pointGenerator) {
        this(crossoverProbability, numberOfPoints, 1, randomGenerator, pointGenerator);
    }

    /**
     * N-point crossover between blocks of {@code blockSize} variables, with explicit random
     * generators.
     *
     * @param crossoverProbability probability of applying the crossover, in [0, 1]
     * @param numberOfPoints       number of cuts, at least 1 and smaller than the number of
     *                             blocks of the solutions
     * @param blockSize            number of consecutive variables a cut never separates, at
     *                             least 1; the number of variables must be a multiple of it
     * @param randomGenerator      uniform numbers in [0, 1), to decide whether to cross
     * @param pointGenerator       uniform integers in [lower, upper], both inclusive, to choose
     *                             the cuts
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException
     *         if the probability is not in [0, 1]
     * @throws InvalidConditionException if {@code numberOfPoints < 1} or {@code blockSize < 1}
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if a generator is {@code null}
     */
    public DoubleNPointCrossover(double crossoverProbability, int numberOfPoints, int blockSize,
            RandomGenerator<Double> randomGenerator, BoundedRandomGenerator<Integer> pointGenerator) {
        Check.probabilityIsValid(crossoverProbability);
        String reason = countsReason(numberOfPoints, blockSize);
        if (reason != null) {
            throw new InvalidConditionException(reason);
        }
        Check.notNull(randomGenerator, "randomGenerator");
        Check.notNull(pointGenerator, "pointGenerator");
        this.crossoverProbability = crossoverProbability;
        this.numberOfPoints = numberOfPoints;
        this.blockSize = blockSize;
        this.randomGenerator = randomGenerator;
        this.pointGenerator = pointGenerator;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Override
    public double crossoverProbability() {
        return crossoverProbability;
    }

    /**
     * Number of cuts of each applied crossover.
     *
     * @return the number of points, at least 1
     */
    public int numberOfPoints() {
        return numberOfPoints;
    }

    /**
     * Number of consecutive variables a cut never separates.
     *
     * @return the block size, at least 1 (1 for the plain n-point crossover)
     */
    public int blockSize() {
        return blockSize;
    }

    @Override
    public int numberOfRequiredParents() {
        return 2;
    }

    @Override
    public int numberOfGeneratedChildren() {
        return 2;
    }

    // ── Crossover ─────────────────────────────────────────────────────────────

    /**
     * Crosses two parents into two new children; the parents are never modified.
     *
     * <p>The size rules of {@link #check} are enforced first, whether or not the crossover is
     * then applied, so a configuration that does not fit the solutions fails on the first call
     * instead of at random.
     *
     * @param parents exactly two solutions with the same number of variables
     * @return a new mutable list with the two children: copies of the parents when the crossover
     *         is not applied, the crossed copies otherwise
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException
     *         if {@code parents} is {@code null}
     * @throws InvalidConditionException if there are not exactly two parents, their numbers of
     *         variables differ, or that number breaks a rule of {@link #check}
     */
    @Override
    public List<DoubleSolution> execute(List<DoubleSolution> parents) {
        Check.notNull(parents, "parents");
        if (parents.size() != numberOfRequiredParents()) {
            throw new InvalidConditionException("n-point crossover requires " + numberOfRequiredParents()
                    + " parents, got " + parents.size());
        }
        DoubleSolution first = parents.get(0);
        DoubleSolution second = parents.get(1);
        int size = first.variables().size();
        if (second.variables().size() != size) {
            throw new InvalidConditionException("the parents have different numbers of variables: "
                    + size + " and " + second.variables().size());
        }
        String reason = check(size, numberOfPoints, blockSize);
        if (reason != null) {
            throw new InvalidConditionException(reason);
        }

        DoubleSolution firstChild = (DoubleSolution) first.copy();
        DoubleSolution secondChild = (DoubleSolution) second.copy();
        if (randomGenerator.getRandomValue() < crossoverProbability) {
            int[] cuts = cuts(size);
            boolean swapped = false;
            int nextCut = 0;
            for (int i = 0; i < size; i++) {
                if (nextCut < cuts.length && cuts[nextCut] == i) {
                    swapped = !swapped;
                    nextCut++;
                }
                if (swapped) {
                    firstChild.variables().set(i, second.variables().get(i));
                    secondChild.variables().set(i, first.variables().get(i));
                }
            }
        }
        List<DoubleSolution> children = new ArrayList<>(2);
        children.add(firstChild);
        children.add(secondChild);
        return children;
    }

    /**
     * Chooses {@code numberOfPoints} distinct cuts among the block boundaries, that is, the
     * multiples of the block size in [1, size &minus; 1], and returns them sorted. A cut at
     * position {@code p} falls between variables {@code p - 1} and {@code p}.
     *
     * <p>Partial Fisher&ndash;Yates shuffle: step {@code i} swaps position {@code i} with a
     * uniform one in [i, last], so the first {@code numberOfPoints} positions end up a uniform
     * sample without replacement, at the cost of exactly {@code numberOfPoints} draws.
     *
     * @param size number of variables, already checked by {@link #check}
     * @return the sorted cut positions
     */
    private int[] cuts(int size) {
        int[] positions = IntStream.range(1, size / blockSize).map(block -> block * blockSize).toArray();
        for (int i = 0; i < numberOfPoints; i++) {
            int j = pointGenerator.getRandomValue(i, positions.length - 1);
            int chosen = positions[j];
            positions[j] = positions[i];
            positions[i] = chosen;
        }
        int[] cuts = Arrays.copyOf(positions, numberOfPoints);
        Arrays.sort(cuts);
        return cuts;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Checks whether an n-point crossover with {@code numberOfPoints} cuts between blocks of
     * {@code blockSize} variables can cross solutions of {@code numberOfVariables} variables.
     *
     * <p>Rules, checked in this order: at least 1 point; a block size of at least 1; a number of
     * variables that is a multiple of the block size; at least 2 blocks; fewer points than
     * blocks. {@link #execute(List)} applies exactly these rules, so a {@code null} answer for the
     * problem's number of variables guarantees the operator will not reject its solutions for
     * their size. Call it before starting a run (see the class documentation for why the operator
     * itself can only check at its first crossover). The message is built only when a rule is
     * broken.
     *
     * @param numberOfVariables number of variables of the solutions to cross
     * @param numberOfPoints    number of cuts of each crossover
     * @param blockSize         number of consecutive variables a cut never separates (1 for the
     *                          plain n-point crossover)
     * @return {@code null} if the combination is valid, otherwise the reason it is not
     */
    public static String check(int numberOfVariables, int numberOfPoints, int blockSize) {
        String reason = countsReason(numberOfPoints, blockSize);
        if (reason != null) {
            return reason;
        }
        if (numberOfVariables % blockSize != 0) {
            return "the number of variables (" + numberOfVariables + ") is not a multiple of the block size ("
                    + blockSize + ")";
        }
        int blocks = numberOfVariables / blockSize;
        if (blocks < 2) {
            return blockSize == 1
                    ? "an n-point crossover needs at least 2 variables to cut between, got " + numberOfVariables
                    : "an n-point crossover needs at least 2 blocks of " + blockSize
                            + " variables to cut between, got " + numberOfVariables + " variables";
        }
        if (numberOfPoints >= blocks) {
            return blockSize == 1
                    ? "the number of points (" + numberOfPoints + ") must be smaller than the number of variables ("
                            + numberOfVariables + ")"
                    : "the number of points (" + numberOfPoints + ") must be smaller than the number of blocks ("
                            + blocks + " blocks of " + blockSize + " variables)";
        }
        return null;
    }

    /**
     * The rules that do not depend on the solutions, shared by the constructor and
     * {@link #check}.
     *
     * @param numberOfPoints number of cuts
     * @param blockSize      block size
     * @return {@code null} if both are at least 1, otherwise the reason
     */
    private static String countsReason(int numberOfPoints, int blockSize) {
        if (numberOfPoints < 1) {
            return "the number of points must be at least 1, got " + numberOfPoints;
        }
        if (blockSize < 1) {
            return "the block size must be at least 1, got " + blockSize;
        }
        return null;
    }
}

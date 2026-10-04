package es.unex.jdisrest.config;

import es.unex.jdisrest.operator.DoubleNPointCrossover;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.ArithmeticCrossover;
import org.uma.jmetal.operator.crossover.impl.BLXAlphaCrossover;
import org.uma.jmetal.operator.crossover.impl.LaplaceCrossover;
import org.uma.jmetal.operator.crossover.impl.SBXCrossover;
import org.uma.jmetal.operator.crossover.impl.WholeArithmeticCrossover;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Crossover operators for real-coded problems ({@link DoubleSolution}) that a configuration file
 * can name in its {@code crossover} key, with their parameters as {@code crossover.<parameter>}
 * and the application probability as {@code crossover.probability}; for a real segment of a
 * composite problem, under the same keys with the name of the segment as prefix (see
 * {@link SolutionLayout}).
 *
 * <p>All of them take two parents and return two children, as the steady-state algorithms of
 * jdisrest expect: they select two parents for each task and use both children. Parameters are
 * finite non-negative numbers; the constants below state the further rules, which
 * {@link #check} enforces before the run starts.
 *
 * <h2>Variables with equal bounds</h2>
 * <p>Every entry built on a jMetal operator passes it a repair that tolerates a variable whose
 * lower and upper bounds are equal (it keeps that value) and otherwise clamps to the bounds exactly
 * as jMetal's default repair does. With jMetal's default, BLX-&alpha;, arithmetic and whole
 * arithmetic crossover would throw on every crossover of a problem that fixes a variable that way.
 * {@link #N_POINT} needs no repair: it only copies values between the parents.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public enum CrossoverType implements OperatorType<CrossoverOperator<DoubleSolution>> {

    /**
     * Simulated binary crossover (jMetal's {@link SBXCrossover}), the default:
     * {@code distributionIndex} (default 20) controls the spread, larger values giving children
     * closer to their parents.
     */
    SBX("sbx", List.of(new Parameter("distributionIndex", 20.0)),
            (probability, parameters) -> new SBXCrossover(probability, parameters.get("distributionIndex"),
                    BoundRepair.INSTANCE)),

    /**
     * Blend crossover (jMetal's {@link BLXAlphaCrossover}): each child variable is drawn uniformly
     * from the interval of the parents' values widened by {@code alpha} (default 0.5) times its
     * length on each side.
     */
    BLX_ALPHA("blxAlpha", List.of(new Parameter("alpha", 0.5)),
            (probability, parameters) -> new BLXAlphaCrossover(probability, parameters.get("alpha"),
                    BoundRepair.INSTANCE)),

    /**
     * Laplace crossover (jMetal's {@link LaplaceCrossover}): the children are spread around the
     * parents by offsets drawn from a Laplace distribution of scale {@code scale} (default 0.5),
     * which must be greater than 0 because jMetal's constructor rejects 0.
     */
    LAPLACE("laplace", List.of(new Parameter("scale", 0.5)),
            (probability, parameters) -> new LaplaceCrossover(probability, parameters.get("scale"),
                    BoundRepair.INSTANCE)) {
        @Override
        public String check(Map<String, Double> parameters, int numberOfVariables) {
            return positive("scale", parameters);
        }
    },

    /**
     * Arithmetic crossover (jMetal's {@link ArithmeticCrossover}) with a random weight per
     * variable: each pair of child variables is a convex combination of the parents' values.
     */
    ARITHMETIC("arithmetic", List.of(),
            (probability, parameters) -> new ArithmeticCrossover(probability, BoundRepair.INSTANCE)),

    /**
     * Whole arithmetic crossover (jMetal's {@link WholeArithmeticCrossover}): as
     * {@link #ARITHMETIC}, but with a single random weight for the whole solution.
     */
    WHOLE_ARITHMETIC("wholeArithmetic", List.of(),
            (probability, parameters) -> new WholeArithmeticCrossover(probability, BoundRepair.INSTANCE)),

    /**
     * N-point crossover ({@link DoubleNPointCrossover}): the children swap the segments between
     * {@code points} random cuts (default 2), which fall only between blocks of {@code blockSize}
     * consecutive variables (default 1, any position). Both must be integers; the rules of
     * {@link DoubleNPointCrossover#check} apply: the block size divides the number of variables
     * (of the segment, in a composite), there are at least 2 blocks, and there are fewer points
     * than blocks.
     */
    N_POINT("nPoint", List.of(new Parameter("points", 2.0), new Parameter("blockSize", 1.0)),
            (probability, parameters) -> new DoubleNPointCrossover(probability, parameters.get("points").intValue(),
                    parameters.get("blockSize").intValue())) {
        @Override
        public String check(Map<String, Double> parameters, int numberOfVariables) {
            double points = parameters.get("points");
            double blockSize = parameters.get("blockSize");
            String reason;
            if (!isInteger(points)) {
                reason = "points must be an integer, got '" + OperatorConfig.format(points) + "'";
            } else if (!isInteger(blockSize)) {
                reason = "blockSize must be an integer, got '" + OperatorConfig.format(blockSize) + "'";
            } else {
                // With a single point, only the rules of the block size can fail, so the first
                // reason belongs to blockSize and any later one to points.
                String blockReason = DoubleNPointCrossover.check(numberOfVariables, 1, (int) blockSize);
                String pointsReason = DoubleNPointCrossover.check(numberOfVariables, (int) points, (int) blockSize);
                reason = blockReason != null ? "blockSize: " + blockReason
                        : pointsReason != null ? "points: " + pointsReason
                        : null;
            }
            return reason;
        }
    };

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, CrossoverOperator<DoubleSolution>> factory;

    CrossoverType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, CrossoverOperator<DoubleSolution>> factory) {
        this.key = key;
        this.parameters = parameters;
        this.factory = factory;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public List<Parameter> parameters() {
        return parameters;
    }

    @Override
    public CrossoverOperator<DoubleSolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    /** Why the parameter {@code name} is not greater than 0, or {@code null} if it is. */
    private static String positive(String name, Map<String, Double> parameters) {
        double value = parameters.get(name);
        return value > 0.0 ? null : name + " must be greater than 0, got '" + OperatorConfig.format(value) + "'";
    }

    /** Whether a parameter value is an integer that fits an {@code int}. */
    private static boolean isInteger(double value) {
        return value == Math.rint(value) && Math.abs(value) <= Integer.MAX_VALUE;
    }
}

package es.unex.jdisrest.config;

import es.unex.jdisrest.operator.IntegerBLXCrossover;
import es.unex.jdisrest.operator.IntegerSBXCrossover;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Crossover operators for integer variables ({@link IntegerSolution}) that a configuration file
 * can name in the {@code crossover} key of an integer problem, or in the
 * {@code <segment>.crossover} key of an integer segment of a composite one, with their parameters
 * and the application probability under the same prefix (see {@link SolutionLayout}).
 *
 * <p>Both take two parents and return two children, as the steady-state algorithms of jdisrest
 * expect, and both are jdisrest's operators, which round every new value to the nearest integer:
 * jMetal's integer SBX truncates it toward zero instead, which moves the children about half a
 * unit toward 0 and drives a variable in [0, 1] to 0. A variable whose lower and upper bounds are
 * equal keeps its value. Parameters are finite non-negative numbers.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public enum IntegerCrossoverType implements OperatorType<CrossoverOperator<IntegerSolution>> {

    /**
     * Simulated binary crossover with every child value rounded to the nearest integer
     * ({@link IntegerSBXCrossover} of jdisrest, not jMetal's class of the same name), the default:
     * {@code distributionIndex} (default 20) controls the spread, larger values giving children
     * closer to their parents.
     */
    SBX("sbx", List.of(new Parameter("distributionIndex", 20.0)),
            (probability, parameters) -> new IntegerSBXCrossover(probability, parameters.get("distributionIndex"))),

    /**
     * Blend crossover ({@link IntegerBLXCrossover}): each child value is drawn uniformly from the
     * interval of the parents' values widened by {@code alpha} (default 0.5) times its length on
     * each side, clamped to the bounds and rounded to the nearest integer.
     */
    BLX_ALPHA("blxAlpha", List.of(new Parameter("alpha", 0.5)),
            (probability, parameters) -> new IntegerBLXCrossover(probability, parameters.get("alpha"),
                    BoundRepair.INSTANCE));

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, CrossoverOperator<IntegerSolution>> factory;

    IntegerCrossoverType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, CrossoverOperator<IntegerSolution>> factory) {
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
    public CrossoverOperator<IntegerSolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }
}

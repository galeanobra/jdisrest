package es.unex.jdisrest.config;

import es.unex.jdisrest.operator.IntegerGaussianMutation;
import es.unex.jdisrest.operator.IntegerPolynomialMutation;
import es.unex.jdisrest.operator.IntegerSimpleRandomMutation;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Mutation operators for integer variables ({@link IntegerSolution}) that a configuration file can
 * name in the {@code mutation} key of an integer problem, or in the {@code <segment>.mutation} key
 * of an integer segment of a composite one, with their parameters and the per-variable mutation
 * probability under the same prefix (see {@link SolutionLayout}).
 *
 * <p>Every operator mutates each variable independently with the mutation probability, keeps it
 * within its bounds and leaves a variable whose lower and upper bounds are equal at that value.
 * All of them are jdisrest's: jMetal 7.1 has an {@code IntegerPolynomialMutation} and an
 * {@code IntegerSimpleRandomMutation} too, which truncate toward zero or never draw the upper
 * bound. Parameters are finite non-negative numbers.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public enum IntegerMutationType implements OperatorType<MutationOperator<IntegerSolution>> {

    /**
     * Polynomial mutation with the new value rounded to the nearest integer
     * ({@link IntegerPolynomialMutation} of jdisrest, not jMetal's class of the same name), the
     * default: {@code distributionIndex} (default 20) controls the spread, larger values giving
     * smaller steps. A step is a fraction of the range, so on a range of a few values it seldom
     * reaches the next one: in [0, 1], with the default index, about once in four million
     * mutations. {@link #RANDOM} suits such variables better.
     */
    POLYNOMIAL("polynomial", List.of(new Parameter("distributionIndex", 20.0)),
            (probability, parameters) -> new IntegerPolynomialMutation(probability,
                    parameters.get("distributionIndex"), BoundRepair.INSTANCE)),

    /**
     * Random mutation ({@link IntegerSimpleRandomMutation} of jdisrest): a new value drawn
     * uniformly among the integers within the bounds, both included.
     */
    RANDOM("random", List.of(), (probability, parameters) -> new IntegerSimpleRandomMutation(probability)),

    /**
     * Gaussian mutation ({@link IntegerGaussianMutation}): adds to the value a Gaussian step of
     * standard deviation max(2, (upper &minus; lower)/2), rounded and clamped to the bounds. The
     * steps are wide, about a third of the range from its middle, and at least half of the
     * mutations of a value on a bound leave it there.
     */
    GAUSSIAN("gaussian", List.of(), (probability, parameters) -> new IntegerGaussianMutation(probability));

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, MutationOperator<IntegerSolution>> factory;

    IntegerMutationType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, MutationOperator<IntegerSolution>> factory) {
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
    public MutationOperator<IntegerSolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }
}

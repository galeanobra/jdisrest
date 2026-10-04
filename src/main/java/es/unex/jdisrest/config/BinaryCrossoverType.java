package es.unex.jdisrest.config;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.crossover.impl.HUXCrossover;
import org.uma.jmetal.operator.crossover.impl.SinglePointCrossover;
import org.uma.jmetal.operator.crossover.impl.UniformCrossover;
import org.uma.jmetal.solution.binarysolution.BinarySolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Crossover operators for binary variables ({@link BinarySolution}) that a configuration file can
 * name in the {@code crossover} key of a binary problem, or in the {@code <segment>.crossover} key
 * of a binary segment of a composite one, with the application probability under the same prefix
 * (see {@link SolutionLayout}). They have no other parameter.
 *
 * <p>All of them are jMetal's: they take two parents, return two children that are copies of the
 * parents before they cross, and keep the length of every variable. jMetal's
 * {@code NPointCrossover} and {@code TwoPointCrossover} are not offered: they need numeric
 * variables.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public enum BinaryCrossoverType implements OperatorType<CrossoverOperator<BinarySolution>> {

    /**
     * Single-point crossover (jMetal's {@link SinglePointCrossover}), the default: a bit of the
     * whole solution is drawn, in any variable, and the children swap every bit from it to the
     * end of the solution.
     */
    SINGLE_POINT("singlePoint", List.of(), (probability, parameters) -> new SinglePointCrossover<>(probability)),

    /**
     * Half-uniform crossover (jMetal's {@link HUXCrossover}): each bit in which the parents
     * differ is swapped between the children with probability 0.5.
     */
    HUX("hux", List.of(), (probability, parameters) -> new HUXCrossover<>(probability)),

    /**
     * Uniform crossover (jMetal's {@link UniformCrossover}): each bit is swapped between the
     * children with probability 0.5.
     */
    UNIFORM("uniform", List.of(), (probability, parameters) -> new UniformCrossover<>(probability));

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, CrossoverOperator<BinarySolution>> factory;

    BinaryCrossoverType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, CrossoverOperator<BinarySolution>> factory) {
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
    public CrossoverOperator<BinarySolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }
}

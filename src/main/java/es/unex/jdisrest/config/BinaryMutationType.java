package es.unex.jdisrest.config;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.BitFlipMutation;
import org.uma.jmetal.solution.binarysolution.BinarySolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Mutation operators for binary variables ({@link BinarySolution}) that a configuration file can
 * name in the {@code mutation} key of a binary problem, or in the {@code <segment>.mutation} key of
 * a binary segment of a composite one, with the mutation probability under the same prefix (see
 * {@link SolutionLayout}). The probability applies to each bit, so {@code k/n} counts bits there:
 * the default {@code 1/n} flips one bit of the segment per mutation on average.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public enum BinaryMutationType implements OperatorType<MutationOperator<BinarySolution>> {

    /**
     * Bit-flip mutation (jMetal's {@link BitFlipMutation}), the default and only one: each bit is
     * flipped independently with the mutation probability.
     */
    BIT_FLIP("bitFlip", List.of(), (probability, parameters) -> new BitFlipMutation<>(probability));

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, MutationOperator<BinarySolution>> factory;

    BinaryMutationType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, MutationOperator<BinarySolution>> factory) {
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
    public MutationOperator<BinarySolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }
}

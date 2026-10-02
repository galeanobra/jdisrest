package es.unex.jdisrest.config;

import java.util.List;
import java.util.Map;

/**
 * An operator that an algorithm configuration file can name: its name in the file, its numeric
 * parameters besides the application probability, the rules those parameters must follow, and how
 * to build it.
 *
 * <p>The configuration files offer the real-coded catalogues {@link CrossoverType} and
 * {@link MutationType}. The interface is generic in the operator class so that a catalogue for
 * another encoding could be added without changing the parser.
 *
 * <h2>Validation</h2>
 * <p>A value read from a file passes three gates before the operator is accepted (see
 * {@link AlgorithmConfig#load}):
 * <ol>
 *   <li>the parser accepts only finite, non-negative decimal numbers;</li>
 *   <li>{@link #check} adds the rules of the operator, such as a strictly positive scale, and those
 *       that depend on the problem, such as a block size that divides the number of variables;</li>
 *   <li>the parser then builds the operator once with {@link #create} and reports any exception of
 *       its constructor as an {@link InvalidConfigurationException} naming the key, so that a value
 *       the constructor rejects never reaches a running master.</li>
 * </ol>
 * The second gate gives the clearer message; the third is a safety net for rules that
 * {@link #check} does not repeat.
 *
 * @param <O> the operator class, for instance {@code CrossoverOperator<DoubleSolution>}
 * @author Francisco Luna (Universidad de Málaga)
 */
public interface OperatorType<O> {

    /**
     * Name of the operator in the configuration file, matched without regard to case.
     *
     * @return the name, for instance {@code sbx}
     */
    String key();

    /**
     * Parameters besides the application probability, in the order they are read and described.
     *
     * @return the parameters, empty if the operator has none
     */
    List<Parameter> parameters();

    /**
     * Builds a new operator.
     *
     * @param probability application probability, in [0, 1]
     * @param parameters  a value for every parameter in {@link #parameters()}
     * @return a new operator, never shared with an earlier call
     * @throws RuntimeException (usually a jMetal exception) if the constructor rejects a value;
     *                          the values that pass {@link #check} are always accepted
     */
    O create(double probability, Map<String, Double> parameters);

    /**
     * Checks the parameter values beyond being finite non-negative numbers, for rules that depend
     * on the operator or on the problem.
     *
     * @param parameters        a value for every parameter in {@link #parameters()}
     * @param numberOfVariables number of variables of the problem
     * @return what is wrong, starting with the parameter name ({@code scale must be greater than 0,
     *         got '0'}), or {@code null} if the values are valid
     */
    default String check(Map<String, Double> parameters, int numberOfVariables) {
        return null;
    }

    /**
     * A numeric operator parameter.
     *
     * @param name         the name in the file, after the operator key and a dot
     *                     ({@code crossover.distributionIndex})
     * @param defaultValue the value used when the file does not set it
     */
    record Parameter(String name, double defaultValue) {
    }
}

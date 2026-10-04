package es.unex.jdisrest.config;

import es.unex.jdisrest.operator.LevyFlightMutationRandomStepSize;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.LevyFlightMutation;
import org.uma.jmetal.operator.mutation.impl.LinkedPolynomialMutation;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.operator.mutation.impl.SimpleRandomMutation;
import org.uma.jmetal.operator.mutation.impl.UniformMutation;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Mutation operators for real-coded problems ({@link DoubleSolution}) that a configuration file
 * can name in its {@code mutation} key, with their parameters as {@code mutation.<parameter>} and
 * the per-variable mutation probability as {@code mutation.probability}; for a real segment of a
 * composite problem, under the same keys with the name of the segment as prefix (see
 * {@link SolutionLayout}).
 *
 * <p>Every operator mutates each variable independently with the mutation probability and keeps
 * it within its bounds. Parameters are finite non-negative numbers; the constants below state the
 * further rules, which {@link #check} enforces before the run starts.
 *
 * <h2>Variables with equal bounds</h2>
 * <p>Every entry built on a jMetal operator with a repair passes it one that tolerates a variable
 * whose lower and upper bounds are equal (it keeps that value) and otherwise clamps to the bounds
 * exactly as jMetal's default repair does. With jMetal's default, uniform and L&eacute;vy flight
 * mutation would throw whenever they picked such a variable. {@link #RANDOM} draws within the
 * bounds and needs no repair, and {@link #LEVY_RANDOM}'s operator already defaults to a tolerant
 * repair.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public enum MutationType implements OperatorType<MutationOperator<DoubleSolution>> {

    /**
     * Polynomial mutation (jMetal's {@link PolynomialMutation}), the default:
     * {@code distributionIndex} (default 20) controls the spread, larger values giving smaller
     * perturbations.
     */
    POLYNOMIAL("polynomial", List.of(new Parameter("distributionIndex", 20.0)),
            (probability, parameters) -> new PolynomialMutation(probability, parameters.get("distributionIndex"),
                    BoundRepair.INSTANCE)),

    /**
     * Linked polynomial mutation (jMetal's {@link LinkedPolynomialMutation}), with
     * {@code distributionIndex} (default 20). The published operator uses one random draw for every
     * variable it mutates in a solution; jMetal 7.1 does so only in its {@code double[]} method, and
     * on a {@link DoubleSolution} it draws once per variable, so it behaves as {@link #POLYNOMIAL}.
     */
    LINKED_POLYNOMIAL("linkedPolynomial", List.of(new Parameter("distributionIndex", 20.0)),
            (probability, parameters) -> new LinkedPolynomialMutation(probability,
                    parameters.get("distributionIndex"), BoundRepair.INSTANCE)),

    /**
     * Uniform mutation (jMetal's {@link UniformMutation}): adds to the variable a uniform random
     * value in [&minus;{@code perturbation}/2, {@code perturbation}/2) (default 0.5), an absolute
     * amount in the units of the variable, not a fraction of its range, and clamps the result to
     * the bounds. {@code perturbation} must be greater than 0: with 0 no variable ever changes,
     * which would silently turn the mutation off.
     */
    UNIFORM("uniform", List.of(new Parameter("perturbation", 0.5)),
            (probability, parameters) -> new UniformMutation(probability, parameters.get("perturbation"),
                    BoundRepair.INSTANCE)) {
        @Override
        public String check(Map<String, Double> parameters, int numberOfVariables) {
            return positive("perturbation", parameters);
        }
    },

    /** Random mutation (jMetal's {@link SimpleRandomMutation}): a new uniform value within the bounds. */
    RANDOM("random", List.of(), (probability, parameters) -> new SimpleRandomMutation(probability)),

    /**
     * L&eacute;vy flight mutation (jMetal's {@link LevyFlightMutation}, the one jMetal's Evolver
     * offers): mostly small steps with occasional long jumps, drawn from a L&eacute;vy distribution
     * of exponent {@code beta} (default 1.5) and scaled by {@code stepSize} (default 0.01) times the
     * range of the variable. The defaults are jMetal's.
     *
     * <p>{@code beta} must lie in the open interval (1, 2) and {@code stepSize} must be greater than
     * 0. jMetal accepts {@code beta = 2}, but it draws the steps with Mantegna's algorithm, whose
     * scale is proportional to sin(&pi;&middot;beta/2): at 2 the steps are about 1e-8 times the
     * range and nothing visibly mutates. The collapse is gradual, so steps already shrink strongly
     * as beta approaches 2 (the scale is about 0.71 at 1.5, 0.34 at 1.9 and 0.11 at 1.99).
     */
    LEVY_FLIGHT("levyFlight", List.of(new Parameter("beta", 1.5), new Parameter("stepSize", 0.01)),
            (probability, parameters) -> new LevyFlightMutation(probability, parameters.get("beta"),
                    parameters.get("stepSize"), BoundRepair.INSTANCE)) {
        @Override
        public String check(Map<String, Double> parameters, int numberOfVariables) {
            return checkLevy(parameters);
        }
    },

    /**
     * L&eacute;vy flight mutation with a random step size
     * ({@link LevyFlightMutationRandomStepSize}): each mutated solution draws its step size
     * uniformly in [0, {@code stepSize}) and then mutates as {@link #LEVY_FLIGHT} with it, so
     * children with fine and with long steps mix in the same run. Here {@code stepSize} is the
     * <em>maximum</em> step size: the mean step size is {@code stepSize}/2, so with the same value
     * this mutation is on average half as strong as {@link #LEVY_FLIGHT}. {@code beta} and
     * {@code stepSize} default to, and are checked as in, {@link #LEVY_FLIGHT}.
     */
    LEVY_RANDOM("levyRandom", List.of(new Parameter("beta", 1.5), new Parameter("stepSize", 0.01)),
            (probability, parameters) -> new LevyFlightMutationRandomStepSize(probability, parameters.get("beta"),
                    parameters.get("stepSize"))) {
        @Override
        public String check(Map<String, Double> parameters, int numberOfVariables) {
            return checkLevy(parameters);
        }
    };

    private final String key;
    private final List<Parameter> parameters;
    private final BiFunction<Double, Map<String, Double>, MutationOperator<DoubleSolution>> factory;

    MutationType(String key, List<Parameter> parameters,
            BiFunction<Double, Map<String, Double>, MutationOperator<DoubleSolution>> factory) {
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
    public MutationOperator<DoubleSolution> create(double probability, Map<String, Double> parameters) {
        return factory.apply(probability, parameters);
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    /** Why the {@code beta} and {@code stepSize} of a L&eacute;vy flight mutation are wrong, or {@code null}. */
    private static String checkLevy(Map<String, Double> parameters) {
        double beta = parameters.get("beta");
        return beta > 1.0 && beta < 2.0
                ? positive("stepSize", parameters)
                : "beta must be in (1, 2), got '" + OperatorConfig.format(beta) + "'";
    }

    /** Why the parameter {@code name} is not greater than 0, or {@code null} if it is. */
    private static String positive(String name, Map<String, Double> parameters) {
        double value = parameters.get(name);
        return value > 0.0 ? null : name + " must be greater than 0, got '" + OperatorConfig.format(value) + "'";
    }
}

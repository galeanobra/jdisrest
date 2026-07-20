package es.unex.jdisrest.operator;

import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.RandomGenerator;

/**
 * Polynomial mutation operator for {@link DoubleSolution} with a randomized
 * effective mutation probability.
 *
 * <p>This is the standard NSGA-II-style polynomial mutation (Deb &amp; Goyal,
 * 1996) with one modification: rather than applying a fixed mutation
 * probability, the effective probability used for each call to
 * {@link #execute(DoubleSolution)} is randomized as:
 * <pre>
 *   p_eff = mutationProbability + U[0,1] * (5 / 36)
 * </pre>
 * where {@code 5/36 ≈ 0.139}. The additive jitter is re-sampled on every call,
 * introducing diversity in the mutation rate across evaluations. This can help
 * maintain population diversity and avoid premature convergence.
 *
 * <h2>Polynomial mutation formula</h2>
 * <p>For a variable {@code y} with bounds {@code [yl, yu]}, let:
 * <pre>
 *   δ1 = (y - yl) / (yu - yl)   (normalized distance to lower bound)
 *   δ2 = (yu - y) / (yu - yl)   (normalized distance to upper bound)
 *   η_m = distributionIndex
 *   μ = 1 / (η_m + 1)           (mutation power)
 * </pre>
 * A uniform random number {@code u ~ U[0,1]} determines the perturbation
 * direction:
 * <ul>
 *   <li>If {@code u ≤ 0.5}: {@code Δq = (2u + (1-2u)*(1-δ1)^(η_m+1))^μ - 1}</li>
 *   <li>If {@code u > 0.5}: {@code Δq = 1 - (2(1-u) + 2(u-0.5)*(1-δ2)^(η_m+1))^μ}</li>
 * </ul>
 * The mutated value is {@code y' = y + Δq*(yu - yl)}, clamped to
 * {@code [yl, yu]}.
 *
 * <p>If the lower and upper bounds of a variable are equal, no mutation is
 * applied and the bound value is returned unchanged.
 *
 * @author Antonio J. Nebro {@literal <antonio@lcc.uma.es>}
 * @author Juan J. Durillo
 * @see <a href="http://www.iitk.ac.in/kangal/codes.shtml">NSGA-II reference implementation</a>
 */
@SuppressWarnings("serial")
public class PolynomialMutationRandomProbability implements MutationOperator<DoubleSolution> {

    /** Default base mutation probability when no value is specified. */
    private static final double DEFAULT_PROBABILITY = 0.01;

    /**
     * Default distribution index (η_m). Controls perturbation magnitude:
     * higher values produce smaller perturbations (typical range 5–20).
     */
    private static final double DEFAULT_DISTRIBUTION_INDEX = 20.0;

    /**
     * Distribution index η_m ≥ 0. Controls the shape of the polynomial
     * perturbation distribution:
     * <ul>
     *   <li>Small values (e.g., 5) → large perturbations, more exploratory.</li>
     *   <li>Large values (e.g., 20) → small perturbations, more exploitative.</li>
     * </ul>
     */
    private double distributionIndex;

    /**
     * Base mutation probability. The effective per-call probability will be at
     * least this value and at most {@code mutationProbability + 5/36}.
     */
    private double mutationProbability;

    /**
     * Repair strategy applied after mutation to clamp values outside the
     * variable's declared bounds. Defaults to bound clamping.
     */
    private RepairDoubleSolution solutionRepair;

    /** Source of uniform random numbers in [0,1). */
    private RandomGenerator<Double> randomGenerator;

    /**
     * Default constructor using {@link #DEFAULT_PROBABILITY} and
     * {@link #DEFAULT_DISTRIBUTION_INDEX}.
     */
    public PolynomialMutationRandomProbability() {
        this(DEFAULT_PROBABILITY, DEFAULT_DISTRIBUTION_INDEX);
    }

    /**
     * Convenience constructor that derives the base probability as
     * {@code 1 / numberOfVariables}.
     *
     * @param problem          the problem, used only to obtain the number of variables
     * @param distributionIndex polynomial distribution index (η_m ≥ 0)
     * @param randomGenerator  supplier of uniform random doubles in [0,1)
     */
    public PolynomialMutationRandomProbability(
            DoubleProblem problem, double distributionIndex, RandomGenerator<Double> randomGenerator) {
        this(1.0 / problem.numberOfVariables(), distributionIndex);
        this.randomGenerator = randomGenerator;
    }

    /**
     * Constructor with bound-clamping repair and the default jMetal random generator.
     *
     * @param mutationProbability base per-variable mutation probability in [0,1]
     * @param distributionIndex   polynomial distribution index (η_m ≥ 0)
     */
    public PolynomialMutationRandomProbability(double mutationProbability, double distributionIndex) {
        this(mutationProbability, distributionIndex, new RepairDoubleSolutionWithBoundValue());
    }

    /**
     * Constructor with explicit random generator and bound-clamping repair.
     *
     * @param mutationProbability base per-variable mutation probability in [0,1]
     * @param distributionIndex   polynomial distribution index (η_m ≥ 0)
     * @param randomGenerator     supplier of uniform random doubles in [0,1)
     */
    public PolynomialMutationRandomProbability(
            double mutationProbability,
            double distributionIndex,
            RandomGenerator<Double> randomGenerator) {
        this(
                mutationProbability,
                distributionIndex,
                new RepairDoubleSolutionWithBoundValue(),
                randomGenerator);
    }

    /**
     * Constructor with explicit repair strategy and the default jMetal random generator.
     *
     * @param mutationProbability base per-variable mutation probability in [0,1]
     * @param distributionIndex   polynomial distribution index (η_m ≥ 0)
     * @param solutionRepair      strategy to clamp out-of-bounds values
     */
    public PolynomialMutationRandomProbability(
            double mutationProbability, double distributionIndex, RepairDoubleSolution solutionRepair) {
        this(
                mutationProbability,
                distributionIndex,
                solutionRepair,
                () -> JMetalRandom.getInstance().nextDouble());
    }

    /**
     * Full constructor.
     *
     * @param mutationProbability base per-variable mutation probability in [0,1]
     * @param distributionIndex   polynomial distribution index (η_m ≥ 0)
     * @param solutionRepair      strategy to clamp out-of-bounds values
     * @param randomGenerator     supplier of uniform random doubles in [0,1)
     */
    public PolynomialMutationRandomProbability(
            double mutationProbability,
            double distributionIndex,
            RepairDoubleSolution solutionRepair,
            RandomGenerator<Double> randomGenerator) {
        Check.that(distributionIndex >= 0, "Distribution index is negative: " + distributionIndex);
        Check.probabilityIsValid(mutationProbability);
        this.mutationProbability = mutationProbability;
        this.distributionIndex = distributionIndex;
        this.solutionRepair = solutionRepair;
        this.randomGenerator = randomGenerator;
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    /**
     * Returns the base mutation probability.
     *
     * @return base mutation probability in [0,1]
     */
    @Override
    public double mutationProbability() {
        return mutationProbability;
    }

    /**
     * Returns the polynomial distribution index (η_m).
     *
     * @return distribution index (≥ 0)
     */
    public double getDistributionIndex() {
        return distributionIndex;
    }

    /**
     * Sets the base mutation probability.
     *
     * @param probability new base probability in [0,1]
     */
    public void setMutationProbability(double probability) {
        this.mutationProbability = probability;
    }

    /**
     * Sets the polynomial distribution index (η_m).
     *
     * @param distributionIndex new distribution index (must be ≥ 0)
     */
    public void setDistributionIndex(double distributionIndex) {
        this.distributionIndex = distributionIndex;
    }

    // -------------------------------------------------------------------------
    // MutationOperator interface
    // -------------------------------------------------------------------------

    /**
     * Applies polynomial mutation with randomized probability to the given
     * solution in-place.
     *
     * @param solution the solution to mutate
     * @return the mutated solution (same object, modified in-place)
     * @throws JMetalException if {@code solution} is {@code null}
     */
    @Override
    public DoubleSolution execute(DoubleSolution solution) throws JMetalException {
        Check.notNull(solution);

        doMutation(solution);

        return solution;
    }

    /**
     * Performs polynomial mutation with a per-call randomized probability.
     *
     * <p>A fresh effective probability is drawn once per call:
     * <pre>
     *   p_eff = mutationProbability + U[0,1] * (5/36)
     * </pre>
     * Then, for each variable:
     * <ol>
     *   <li>Draw {@code u ~ U[0,1]}; skip if {@code u > p_eff}.</li>
     *   <li>If the variable's bounds are equal, set to the bound value.</li>
     *   <li>Otherwise, apply the polynomial perturbation formula (see class
     *       JavaDoc) and clamp the result to {@code [yl, yu]}.</li>
     * </ol>
     *
     * @param solution solution to mutate in-place
     */
    private void doMutation(DoubleSolution solution) {
        // Randomize the effective mutation probability for this call.
        // The additive term U[0,1] * 5/36 (≈ 0–0.139) introduces per-call diversity
        // in how aggressively the solution is mutated.
        double randomMutation = mutationProbability + randomGenerator.getRandomValue() * 5 / 36;

        for (int i = 0; i < solution.variables().size(); i++) {
            if (randomGenerator.getRandomValue() <= randomMutation) {
                double y = solution.variables().get(i);
                Bounds<Double> bounds = solution.getBounds(i);
                double yl = bounds.getLowerBound();   // lower bound of variable i
                double yu = bounds.getUpperBound();   // upper bound of variable i

                if (yl == yu) {
                    // No range to mutate within; leave the variable at the bound.
                    y = yl;
                } else {
                    // Normalized distances from the current value to each bound.
                    double delta1 = (y - yl) / (yu - yl);   // distance to lower bound (normalized)
                    double delta2 = (yu - y) / (yu - yl);   // distance to upper bound (normalized)

                    double rnd = randomGenerator.getRandomValue();
                    // Mutation power μ = 1/(η_m + 1); smaller η_m → larger μ → larger Δq.
                    double mutPow = 1.0 / (distributionIndex + 1.0);
                    double deltaq;
                    double val;
                    double xy;

                    if (rnd <= 0.5) {
                        // Left tail of the polynomial distribution (perturbation toward lower bound).
                        xy = 1.0 - delta1;
                        val = 2.0 * rnd + (1.0 - 2.0 * rnd) * (Math.pow(xy, distributionIndex + 1.0));
                        deltaq = Math.pow(val, mutPow) - 1.0;  // negative perturbation
                    } else {
                        // Right tail of the polynomial distribution (perturbation toward upper bound).
                        xy = 1.0 - delta2;
                        val = 2.0 * (1.0 - rnd) + 2.0 * (rnd - 0.5) * (Math.pow(xy, distributionIndex + 1.0));
                        deltaq = 1.0 - Math.pow(val, mutPow);  // positive perturbation
                    }

                    // Apply the perturbation scaled by the variable range.
                    y = y + deltaq * (yu - yl);

                    // Clamp to declared bounds (handles floating-point overshooting).
                    y = solutionRepair.repairSolutionVariableValue(y, yl, yu);
                }
                solution.variables().set(i, y);
            }
        }
    }
}

package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD.AggregationFunction;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ConstraintHandling;
import org.uma.jmetal.util.VectorUtils;
import org.uma.jmetal.util.errorchecking.Check;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The aggregation of MOEA/D, which turns the objectives of a solution into one value for the weight
 * vector of a subproblem, as jMetal 7.1's aggregation functions ({@code Tschebyscheff},
 * {@code WeightedSum}, {@code PenaltyBoundaryIntersection}) and {@code MOEADReplacement} do:
 *
 * <ul>
 *   <li>The ideal point z* is the minimum of each objective over every solution given to
 *       {@link #update}, feasible or not, as jMetal's {@code IdealPoint}.</li>
 *   <li>The nadir point is the maximum of each objective over the non-dominated solutions among
 *       them, by the dominance of jMetal's {@code NonDominatedSolutionListArchive()}
 *       ({@code DominanceWithConstraintsComparator}): a smaller overall constraint violation wins
 *       first, Pareto dominance on the objectives decides between equally violating solutions.
 *       Once a feasible solution has arrived, infeasible ones no longer count, however good their
 *       objectives; and a solution stops counting as soon as another one dominates it.</li>
 *   <li>With normalized objectives, each objective f becomes (f - z*) / (nadir - z* + ε), with
 *       ε = {@value #EPSILON}; without, f - z*.</li>
 *   <li>Tchebycheff multiplies an objective whose weight is 0 by {@value #ZERO_WEIGHT_FACTOR}
 *       instead of 0, so that the objective still breaks ties; corner and lattice weight vectors
 *       have zeros. PBI uses θ = {@value #PBI_THETA}.</li>
 * </ul>
 *
 * <p>The weighted sum adds up the weighted f - z*, which only differs from the weighted f by a
 * constant for each weight vector and ideal point, so it ranks solutions the same way. Without
 * normalization the three functions give jMetal's values up to rounding. With normalization PBI
 * divides by nadir - z* + ε in both of its terms, where jMetal 7.1 leaves ε out of the
 * perpendicular distance and divides by zero when an objective's nadir equals its ideal.
 *
 * <h2>Cost</h2>
 * <p>Both points are tracked whether or not the objectives are normalized, so that normalization
 * can be switched on during a run ({@link #configure}). The non-dominated set behind the nadir is
 * unbounded: every {@link #update} compares the new point with each member, O(|set| · m). That is
 * negligible next to an expensive evaluation, but the set can grow to thousands of points with many
 * objectives.
 *
 * <p>Not thread-safe: {@link MOEAD} calls it while holding the population lock.
 *
 * @author Francisco Luna (Universidad de Málaga)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class MOEADAggregation {

    static final double EPSILON = 1.0e-6;
    static final double ZERO_WEIGHT_FACTOR = 1.0e-4;
    static final double PBI_THETA = 5.0;

    /** A non-dominated point: its objectives and its overall constraint violation (0 or negative). */
    private record Point(double[] objectives, double violation) {}

    private final double[] ideal;
    private final double[] nadir;
    private final List<Point> nonDominated = new ArrayList<>();
    private AggregationFunction function;
    private boolean normalize;

    /**
     * Creates an aggregation with no point seen yet.
     *
     * @param numberOfObjectives the number of objectives of every solution, at least 1
     * @param function           the aggregation function
     * @param normalize          whether the objectives are normalized with the ideal and nadir points
     * @throws RuntimeException (jMetal's {@code InvalidConditionException} or
     *                          {@code NullParameterException}) if there are no objectives or the
     *                          function is {@code null}
     */
    public MOEADAggregation(int numberOfObjectives, AggregationFunction function, boolean normalize) {
        Check.that(numberOfObjectives > 0, "numberOfObjectives must be positive, got " + numberOfObjectives);
        this.ideal = new double[numberOfObjectives];
        this.nadir = new double[numberOfObjectives];
        Arrays.fill(ideal, Double.POSITIVE_INFINITY);
        Arrays.fill(nadir, Double.NEGATIVE_INFINITY);
        configure(function, normalize);
    }

    /**
     * Replaces the aggregation function and whether the objectives are normalized. The ideal and
     * nadir points are kept, so a change during a run takes effect at the next {@link #fitness}.
     *
     * @param function  the aggregation function
     * @param normalize whether the objectives are normalized with the ideal and nadir points
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException if the function is
     *                                    {@code null}
     */
    public void configure(AggregationFunction function, boolean normalize) {
        Check.notNull(function, "function");
        this.function = function;
        this.normalize = normalize;
    }

    // ── Reference points ─────────────────────────────────────────────────────

    /**
     * Takes an evaluated solution into the ideal and nadir points, with the overall constraint
     * violation jMetal computes for it ({@link ConstraintHandling#overallConstraintViolationDegree}).
     *
     * @param solution an evaluated solution with {@code numberOfObjectives} objectives
     * @throws IllegalArgumentException if it has another number of objectives
     */
    public void update(Solution<?> solution) {
        update(solution.objectives(), ConstraintHandling.overallConstraintViolationDegree(solution));
    }

    /**
     * Takes an evaluated point into the ideal and nadir points.
     *
     * @param objectives                 its objectives; copied, not kept
     * @param overallConstraintViolation its overall constraint violation as jMetal measures it: 0
     *                                   when feasible, the (negative) sum of the violated
     *                                   constraints otherwise
     * @throws IllegalArgumentException if {@code objectives} does not have {@code numberOfObjectives}
     *                                  values
     */
    public void update(double[] objectives, double overallConstraintViolation) {
        if (objectives.length != ideal.length) {
            throw new IllegalArgumentException("objectives has " + objectives.length + " values, expected "
                    + ideal.length);
        }
        for (int i = 0; i < ideal.length; i++) {
            ideal[i] = Math.min(ideal[i], objectives[i]);
        }
        if (addNonDominated(new Point(objectives.clone(), overallConstraintViolation))) {
            Arrays.fill(nadir, Double.NEGATIVE_INFINITY);
            for (Point point : nonDominated) {
                for (int i = 0; i < nadir.length; i++) {
                    nadir[i] = Math.max(nadir[i], point.objectives()[i]);
                }
            }
        }
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    /**
     * Returns the aggregated value of {@code objectives} for {@code weights}; lower is better. Only
     * meaningful after the first {@link #update}.
     *
     * @param objectives the objectives of a solution, {@code numberOfObjectives} values
     * @param weights    the weight vector of a subproblem, {@code numberOfObjectives} values
     * @return the aggregated value
     */
    public double fitness(double[] objectives, double[] weights) {
        double[] shifted = new double[objectives.length];
        for (int i = 0; i < objectives.length; i++) {
            shifted[i] = objectives[i] - ideal[i];
            if (normalize) {
                shifted[i] /= nadir[i] - ideal[i] + EPSILON;
            }
        }
        return switch (function) {
            case TCHEBYCHEFF -> tchebycheff(shifted, weights);
            case WSUM -> weightedSum(shifted, weights);
            case PBI -> penaltyBoundaryIntersection(shifted, weights);
        };
    }

    /** A copy of the ideal point; +∞ in every objective before the first update. */
    double[] idealPoint() {
        return ideal.clone();
    }

    /** A copy of the nadir point; -∞ in every objective before the first update. */
    double[] nadirPoint() {
        return nadir.clone();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Adds the point to the non-dominated set, as {@code NonDominatedSolutionListArchive.add} does:
     * it is rejected if a member dominates it or has the same objectives (and the same violation
     * class), and otherwise replaces the members it dominates.
     *
     * @return whether the set changed
     */
    private boolean addNonDominated(Point candidate) {
        for (Point member : nonDominated) {
            int flag = compare(member, candidate);
            if (flag < 0 || (flag == 0 && Arrays.equals(member.objectives(), candidate.objectives()))) {
                return false;
            }
        }
        nonDominated.removeIf(member -> compare(candidate, member) < 0);
        nonDominated.add(candidate);
        return true;
    }

    /**
     * {@code DominanceWithConstraintsComparator} on points: -1 if {@code a} dominates {@code b}, 1 if
     * {@code b} dominates {@code a}, 0 otherwise. The violation is compared first, as
     * {@code OverallConstraintViolationDegreeComparator} does; the objectives decide when it ties.
     */
    private static int compare(Point a, Point b) {
        int flag;
        if (a.violation() < 0.0 && b.violation() < 0.0) {
            flag = Double.compare(b.violation(), a.violation());
        } else if (a.violation() == 0.0 && b.violation() < 0.0) {
            flag = -1;
        } else if (a.violation() < 0.0 && b.violation() == 0.0) {
            flag = 1;
        } else {
            flag = 0;
        }
        return flag != 0 ? flag : VectorUtils.dominanceTest(a.objectives(), b.objectives());
    }

    private static double tchebycheff(double[] shifted, double[] weights) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < shifted.length; i++) {
            double weight = weights[i] == 0.0 ? ZERO_WEIGHT_FACTOR : weights[i];
            max = Math.max(max, weight * Math.abs(shifted[i]));
        }
        return max;
    }

    private static double weightedSum(double[] shifted, double[] weights) {
        double sum = 0.0;
        for (int i = 0; i < shifted.length; i++) {
            sum += weights[i] * shifted[i];
        }
        return sum;
    }

    private static double penaltyBoundaryIntersection(double[] shifted, double[] weights) {
        double norm = 0.0;
        for (double weight : weights) {
            norm += weight * weight;
        }
        norm = Math.sqrt(norm);
        double d1 = 0.0;
        for (int i = 0; i < shifted.length; i++) {
            d1 += shifted[i] * weights[i] / norm;
        }
        double d2 = 0.0;
        for (int i = 0; i < shifted.length; i++) {
            double difference = shifted[i] - d1 * weights[i] / norm;
            d2 += difference * difference;
        }
        return d1 + PBI_THETA * Math.sqrt(d2);
    }
}

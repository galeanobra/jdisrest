package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD.AggregationFunction;
import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.archive.impl.NonDominatedSolutionListArchive;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference points and aggregation functions of {@link MOEADAggregation}: the ideal over every
 * point, the constraint-aware nadir (checked against jMetal's {@code NonDominatedSolutionListArchive}),
 * Tchebycheff with its zero-weight factor, the weighted sum, PBI, and normalization.
 */
class MOEADAggregationTest {

    private static final double TOLERANCE = 1e-9;
    private static final double FEASIBLE = 0.0;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A two-objective aggregation that has seen the given feasible points. */
    static MOEADAggregation withPoints(AggregationFunction function, boolean normalize, double[]... points) {
        MOEADAggregation aggregation = new MOEADAggregation(2, function, normalize);
        for (double[] point : points) {
            aggregation.update(point, FEASIBLE);
        }
        return aggregation;
    }

    /** A two-objective solution with one constraint ({@code constraint < 0} is a violation). */
    static DoubleSolution solution(double f1, double f2, double constraint) {
        DoubleSolution s = new DefaultDoubleSolution(List.of(Bounds.create(0.0, 1.0)), 2, 1);
        s.objectives()[0] = f1;
        s.objectives()[1] = f2;
        s.constraints()[0] = constraint;
        return s;
    }

    static double[] nadirOf(List<DoubleSolution> solutions) {
        double[] nadir = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (DoubleSolution s : solutions) {
            for (int i = 0; i < nadir.length; i++) {
                nadir[i] = Math.max(nadir[i], s.objectives()[i]);
            }
        }
        return nadir;
    }

    // ── Ideal and nadir points ────────────────────────────────────────────────

    @Test
    void idealIsTheMinimumAndNadirTheMaximumOfTwoNonDominatedPoints() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {0, 10}, new double[] {4, 2});

        assertAll(
                () -> assertArrayEquals(new double[] {0, 2}, aggregation.idealPoint(), "ideal = per-objective minimum"),
                () -> assertArrayEquals(new double[] {4, 10}, aggregation.nadirPoint(), "nadir = per-objective maximum"));
    }

    @Test
    void poorPointLeavesTheNadirOnceDominated() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {1000, 1000}, new double[] {0, 10}, new double[] {4, 2});

        assertArrayEquals(new double[] {4, 10}, aggregation.nadirPoint(), "a dominated point no longer counts");
    }

    @Test
    void laterDominatedPointDoesNotMoveTheNadir() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {0, 10}, new double[] {4, 2}, new double[] {5, 11});

        assertArrayEquals(new double[] {4, 10}, aggregation.nadirPoint(), "a dominated point never counts");
    }

    @Test
    void infeasiblePointThatDominatesInObjectiveSpaceDoesNotSetTheNadir() {
        MOEADAggregation aggregation = new MOEADAggregation(2, AggregationFunction.TCHEBYCHEFF, true);
        aggregation.update(new double[] {0, 10}, FEASIBLE);
        aggregation.update(new double[] {4, 2}, FEASIBLE);

        aggregation.update(new double[] {-1, -1}, -0.5);

        assertAll(
                () -> assertArrayEquals(new double[] {4, 10}, aggregation.nadirPoint(),
                        "once a feasible point exists, infeasible ones do not count for the nadir"),
                () -> assertArrayEquals(new double[] {-1, -1}, aggregation.idealPoint(),
                        "the ideal counts every point, as jMetal's IdealPoint"));
    }

    @Test
    void feasiblePointReplacesEveryInfeasibleOneInTheNadir() {
        MOEADAggregation aggregation = new MOEADAggregation(2, AggregationFunction.TCHEBYCHEFF, true);
        aggregation.update(new double[] {0, 1}, -0.5);
        aggregation.update(new double[] {1, 0}, -0.5);

        aggregation.update(new double[] {7, 8}, FEASIBLE);

        assertArrayEquals(new double[] {7, 8}, aggregation.nadirPoint(), "a feasible point dominates every infeasible one");
    }

    @Test
    void amongInfeasiblePointsTheLeastViolatingSetTheNadir() {
        MOEADAggregation aggregation = new MOEADAggregation(2, AggregationFunction.TCHEBYCHEFF, false);
        aggregation.update(new double[] {0, 1}, -2.0);
        aggregation.update(new double[] {5, 6}, -1.0);
        aggregation.update(new double[] {3, 9}, -1.0);

        assertArrayEquals(new double[] {5, 9}, aggregation.nadirPoint(),
                "the least violating points dominate, and Pareto dominance decides between them");
    }

    @Test
    void nadirMatchesJMetalNonDominatedArchiveOnRandomConstrainedPoints() {
        // Infeasible points come first and lie closer to the origin, so the nadir must switch from
        // the least violating infeasible points to the feasible front.
        Random random = new Random(42L);
        MOEADAggregation aggregation = new MOEADAggregation(2, AggregationFunction.TCHEBYCHEFF, true);
        NonDominatedSolutionListArchive<DoubleSolution> reference = new NonDominatedSolutionListArchive<>();
        double[] ideal = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (int i = 0; i < 2000; i++) {
            boolean feasible = i >= 50 && random.nextDouble() < 0.3;
            double scale = feasible ? 10.0 : 5.0;
            double offset = feasible ? 2.0 : 0.0;
            DoubleSolution s = solution(offset + scale * random.nextDouble(), offset + scale * random.nextDouble(),
                    feasible ? random.nextDouble() : -random.nextInt(1, 4) * 0.5);
            aggregation.update(s);
            reference.add((DoubleSolution) s.copy());
            ideal[0] = Math.min(ideal[0], s.objectives()[0]);
            ideal[1] = Math.min(ideal[1], s.objectives()[1]);

            if (i == 10 || i == 49 || i == 60 || i == 500 || i == 1999) {
                int seen = i + 1;
                assertArrayEquals(nadirOf(reference.solutions()), aggregation.nadirPoint(),
                        "after " + seen + " points the nadir must be jMetal's");
                assertArrayEquals(ideal, aggregation.idealPoint(), "after " + seen + " points the ideal must be the minimum");
            }
        }
    }

    @Test
    void objectivesOfAnotherLengthAreRejected() {
        MOEADAggregation aggregation = new MOEADAggregation(2, AggregationFunction.TCHEBYCHEFF, false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> aggregation.update(new double[] {1, 2, 3}, FEASIBLE));
        assertEquals("objectives has 3 values, expected 2", e.getMessage());
    }

    @Test
    void invalidConstructionIsRejected() {
        assertAll(
                () -> assertThrows(InvalidConditionException.class,
                        () -> new MOEADAggregation(0, AggregationFunction.TCHEBYCHEFF, false)),
                () -> assertThrows(NullParameterException.class, () -> new MOEADAggregation(2, null, false)));
    }

    // ── Aggregation functions ─────────────────────────────────────────────────

    @Test
    void tchebycheffIsTheLargestWeightedDistanceToTheIdeal() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {0, 10}, new double[] {4, 2});

        // Distances to the ideal (0, 2) are 2 and 3.
        assertEquals(1.5, aggregation.fitness(new double[] {2, 5}, new double[] {0.5, 0.5}), TOLERANCE);
    }

    @Test
    void zeroWeightStillBreaksTiesInTchebycheff() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false, new double[] {0, 0});
        double[] weights = {1, 0};

        double better = aggregation.fitness(new double[] {0, 1}, weights);
        double worse = aggregation.fitness(new double[] {0, 5}, weights);

        assertAll(
                () -> assertEquals(MOEADAggregation.ZERO_WEIGHT_FACTOR, better, TOLERANCE,
                        "a zero weight counts as " + MOEADAggregation.ZERO_WEIGHT_FACTOR),
                () -> assertTrue(better < worse, "the objective with weight 0 still ranks equal solutions"));
    }

    @Test
    void normalizationDividesEachDistanceToTheIdealByTheRangeToTheNadir() {
        // Ranges 4 and 8.
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, true,
                new double[] {0, 10}, new double[] {4, 2});

        // (2 - 0) / 4 = 0.5 and (5 - 2) / 8 = 0.375, weighted by 0.5.
        assertEquals(0.25, aggregation.fitness(new double[] {2, 5}, new double[] {0.5, 0.5}), 1e-6);
    }

    @Test
    void normalizationLetsAnObjectiveOfSmallRangeCountAsMuchAsOneOfLargeRange() {
        // The first objective ranges over 100, the second over 1.
        MOEADAggregation raw = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {0, 1}, new double[] {100, 0});
        MOEADAggregation normalized = withPoints(AggregationFunction.TCHEBYCHEFF, true,
                new double[] {0, 1}, new double[] {100, 0});
        double[] weights = {0.5, 0.5};
        double[] goodInSecond = {60, 0};
        double[] goodInFirst = {50, 1};

        assertAll(
                () -> assertTrue(raw.fitness(goodInFirst, weights) < raw.fitness(goodInSecond, weights),
                        "without normalization the objective of large range decides alone"),
                () -> assertTrue(normalized.fitness(goodInSecond, weights) < normalized.fitness(goodInFirst, weights),
                        "with normalization both objectives count"));
    }

    @Test
    void normalizationSwitchedOnDuringARunUsesThePointsTrackedSoFar() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false,
                new double[] {0, 10}, new double[] {4, 2});

        aggregation.configure(AggregationFunction.TCHEBYCHEFF, true);

        assertEquals(0.25, aggregation.fitness(new double[] {2, 5}, new double[] {0.5, 0.5}), 1e-6,
                "the nadir was tracked while normalization was off");
    }

    @Test
    void weightedSumAddsTheWeightedDistancesToTheIdeal() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.WSUM, false, new double[] {0, 2});

        assertEquals(0.25 * 2 + 0.75 * 3, aggregation.fitness(new double[] {2, 5}, new double[] {0.25, 0.75}),
                TOLERANCE);
    }

    @Test
    void pbiOfAPointOnTheWeightDirectionIsItsDistanceAlongIt() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.PBI, false, new double[] {0, 0});

        // (3, 3) lies on the direction (0.5, 0.5), at 3·√2 from the ideal, so d2 = 0.
        assertEquals(3 * Math.sqrt(2), aggregation.fitness(new double[] {3, 3}, new double[] {0.5, 0.5}),
                TOLERANCE);
    }

    @Test
    void normalizedPbiStaysFiniteWhenTheNadirEqualsTheIdeal() {
        // A single point: nadir == ideal in both objectives, where jMetal 7.1 divides by zero.
        MOEADAggregation aggregation = withPoints(AggregationFunction.PBI, true, new double[] {1, 1});

        double value = aggregation.fitness(new double[] {1, 1}, new double[] {0.3, 0.7});

        assertTrue(Double.isFinite(value), "ε keeps both PBI terms finite: " + value);
    }

    @Test
    void configureReplacesTheFunction() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false, new double[] {0, 2});

        aggregation.configure(AggregationFunction.WSUM, false);

        assertAll(
                () -> assertEquals(2.5, aggregation.fitness(new double[] {2, 5}, new double[] {0.5, 0.5}), TOLERANCE,
                        "the weighted sum aggregates from now on"),
                () -> assertThrows(NullParameterException.class, () -> aggregation.configure(null, false)));
    }

    @Test
    void pointsAreCopiedOnTheWayInAndOut() {
        MOEADAggregation aggregation = withPoints(AggregationFunction.TCHEBYCHEFF, false, new double[] {1, 4});
        double[] point = {3, 2};
        aggregation.update(point, FEASIBLE);

        point[0] = 100;
        aggregation.idealPoint()[1] = -100;
        aggregation.nadirPoint()[1] = 100;
        aggregation.update(new double[] {2, 3}, FEASIBLE); // recomputes the nadir from the set

        assertAll(
                () -> assertArrayEquals(new double[] {1, 2}, aggregation.idealPoint(), "the ideal is not shared"),
                () -> assertArrayEquals(new double[] {3, 4}, aggregation.nadirPoint(),
                        "neither the nadir nor a point given to update is shared"));
    }
}

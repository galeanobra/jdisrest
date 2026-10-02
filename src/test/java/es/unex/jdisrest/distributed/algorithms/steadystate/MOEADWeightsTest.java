package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.algorithms.steadystate.MOEADWeights.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Weight vectors of {@link MOEADWeights}: the spread and lattice methods, the special cases (one
 * or two objectives, one vector), the size check that MOEA/D runs before starting its server, and
 * the CSV written to the traces folder.
 */
class MOEADWeightsTest {

    private static final double TOLERANCE = 1e-12;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Every vector has non-negative components that add up to 1. */
    static void assertOnSimplex(double[][] weights) {
        for (double[] vector : weights) {
            assertTrue(Arrays.stream(vector).allMatch(component -> component >= 0.0),
                    "every component must be non-negative: " + Arrays.toString(vector));
            assertEquals(1.0, Arrays.stream(vector).sum(), TOLERANCE,
                    "the components must add up to 1: " + Arrays.toString(vector));
        }
    }

    static Set<List<Double>> asSet(double[][] weights) {
        return Arrays.stream(weights)
                .map(vector -> Arrays.stream(vector).boxed().toList())
                .collect(Collectors.toSet());
    }

    static double minimumDistance(double[][] weights) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 0; i < weights.length; i++) {
            for (int j = i + 1; j < weights.length; j++) {
                double sum = 0.0;
                for (int k = 0; k < weights[i].length; k++) {
                    sum += Math.pow(weights[i][k] - weights[j][k], 2);
                }
                minimum = Math.min(minimum, Math.sqrt(sum));
            }
        }
        return minimum;
    }

    // ── Spread ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"3, 5", "3, 100", "3, 137", "5, 100"})
    void spreadGivesAnyNumberOfDistinctVectorsOnTheSimplex(int numberOfObjectives, int count) {
        double[][] weights = MOEADWeights.generate(Method.SPREAD, numberOfObjectives, count);

        assertAll(
                () -> assertEquals(count, weights.length, "one vector per subproblem"),
                () -> assertEquals(count, asSet(weights).size(), "no two subproblems share a vector"),
                () -> assertTrue(Arrays.stream(weights).allMatch(v -> v.length == numberOfObjectives),
                        "one component per objective"),
                () -> assertOnSimplex(weights));
    }

    @Test
    void spreadStartsWithTheCorners() {
        double[][] weights = MOEADWeights.generate(Method.SPREAD, 3, 100);

        for (int k = 0; k < 3; k++) {
            double[] corner = new double[3];
            corner[k] = 1.0;
            assertArrayEquals(corner, weights[k], "vector " + k + " must be the corner of objective " + k);
        }
    }

    @Test
    void spreadVectorsAreFarApart() {
        double[][] weights = MOEADWeights.generate(Method.SPREAD, 3, 100);

        // 100 random vectors of three objectives come as close as 0.01.
        assertTrue(minimumDistance(weights) > 0.05,
                "no two spread vectors may be close: minimum distance " + minimumDistance(weights));
    }

    @Test
    void spreadGivesTheSameVectorsEveryTime() {
        double[][] first = MOEADWeights.generate(Method.SPREAD, 3, 100);
        double[][] second = MOEADWeights.generate(Method.SPREAD, 3, 100);

        assertArrayEquals(first, second, "spread vectors must not depend on the run");
    }

    @Test
    void spreadWithFewerVectorsThanObjectivesGivesTheFirstCorners() {
        double[][] weights = MOEADWeights.generate(Method.SPREAD, 3, 2);

        assertArrayEquals(new double[][] {{1, 0, 0}, {0, 1, 0}}, weights,
                "with fewer vectors than objectives only corners fit");
    }

    @Test
    void spreadAcceptsAnySize() {
        assertNull(MOEADWeights.check(Method.SPREAD, 3, 100), "spread has no size restriction");
    }

    // ── Lattice ───────────────────────────────────────────────────────────────

    @Test
    void latticeHasEveryVectorWhoseComponentsAreMultiplesOfOneOverH() {
        double[][] weights = MOEADWeights.generate(Method.LATTICE, 3, 91);

        assertAll(
                () -> assertEquals(91, asSet(weights).size(), "C(12+2, 2) = 91 distinct vectors"),
                () -> assertOnSimplex(weights),
                () -> assertTrue(Arrays.stream(weights).flatMapToDouble(Arrays::stream)
                                .allMatch(component -> Math.abs(component * 12 - Math.round(component * 12)) < TOLERANCE),
                        "every component must be a multiple of 1/12"));
    }

    @ParameterizedTest
    @ValueSource(ints = {66, 91, 105})
    void latticeSizesAreAccepted(int count) {
        assertNull(MOEADWeights.check(Method.LATTICE, 3, count), count + " is a lattice size for three objectives");
    }

    @Test
    void checkNamesTheLatticeSizesAroundAnInvalidCount() {
        assertEquals("LATTICE weights for 3 objectives need C(H+2, 2) vectors, such as 91 or 105, got 100",
                MOEADWeights.check(Method.LATTICE, 3, 100));
    }

    @Test
    void latticeOfAnInvalidSizeIsRejectedWithTheReasonOfTheCheck() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MOEADWeights.generate(Method.LATTICE, 3, 100));

        assertEquals(MOEADWeights.check(Method.LATTICE, 3, 100), e.getMessage(),
                "generate must fail with the reason check reports");
    }

    @Test
    void latticeVectorsOnTheBoundaryOfTheSimplexHaveAZeroWeight() {
        long withZero = Arrays.stream(MOEADWeights.generate(Method.LATTICE, 3, 91))
                .filter(vector -> Arrays.stream(vector).anyMatch(component -> component == 0.0))
                .count();

        // 91 vectors minus the C(11, 2) = 55 strictly inside: why Tchebycheff must not ignore an
        // objective whose weight is zero.
        assertEquals(36, withZero, "every lattice vector on the boundary has a zero component");
    }

    // ── Special cases ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Method.class)
    void twoObjectivesAreEvenlySpreadByEitherMethod(Method method) {
        double[][] weights = MOEADWeights.generate(method, 2, 5);

        assertArrayEquals(new double[][] {{0, 1}, {0.25, 0.75}, {0.5, 0.5}, {0.75, 0.25}, {1, 0}}, weights,
                "two objectives give (i/(n-1), 1-i/(n-1)) for any n");
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void oneVectorIsTheCentreOfTheSimplex(Method method) {
        double[][] weights = MOEADWeights.generate(method, 5, 1);

        assertArrayEquals(new double[][] {{0.2, 0.2, 0.2, 0.2, 0.2}}, weights, "a single subproblem weighs every objective alike");
    }

    @ParameterizedTest
    @EnumSource(Method.class)
    void singleObjectiveGivesTheUnitWeightForEverySubproblem(Method method) {
        double[][] weights = MOEADWeights.generate(method, 1, 4);

        assertAll(
                () -> assertNull(MOEADWeights.check(method, 1, 4), "one objective accepts any number of vectors"),
                () -> assertArrayEquals(new double[][] {{1.0}, {1.0}, {1.0}, {1.0}}, weights,
                        "with one objective every vector is [1.0]"));
    }

    @Test
    void checkRejectsWhatGenerateCannotProduce() {
        assertAll(
                () -> assertEquals("at least 1 weight vector is needed, got 0",
                        MOEADWeights.check(Method.SPREAD, 3, 0)),
                () -> assertEquals("at least 1 objective is needed, got 0",
                        MOEADWeights.check(Method.SPREAD, 0, 10)),
                () -> assertEquals("the weight method is null", MOEADWeights.check(null, 3, 10)),
                () -> assertThrows(IllegalArgumentException.class, () -> MOEADWeights.generate(Method.LATTICE, 3, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> MOEADWeights.generate(null, 3, 10)));
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    @Test
    void csvHasOneLinePerVectorWithCommas() {
        String csv = MOEADWeights.toCsv(new double[][] {{0.0, 1.0}, {0.25, 0.75}});

        assertEquals("0.0,1.0\n0.25,0.75\n", csv);
    }
}

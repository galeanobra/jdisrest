package es.unex.jdisrest.util;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round trips of {@link SolutionVariables} for the three supported solution
 * shapes and the conversion-by-destination rules.
 */
class SolutionVariablesTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static IntegerSolution intSolution(int... values) {
        List<Bounds<Integer>> bounds = Collections.nCopies(values.length, Bounds.create(-1000, 1000));
        IntegerSolution s = new DefaultIntegerSolution(bounds, 1, 0);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static DoubleSolution doubleSolution(double... values) {
        List<Bounds<Double>> bounds = Collections.nCopies(values.length, Bounds.create(-1000.0, 1000.0));
        DoubleSolution s = new DefaultDoubleSolution(bounds, 1, 0);
        for (int i = 0; i < values.length; i++) s.variables().set(i, values[i]);
        return s;
    }

    static CompositeSolution composite(Solution<?>... components) {
        return new CompositeSolution(List.of(components));
    }

    /** A solution type SolutionVariables does not know: variables are strings. */
    static Solution<String> stringSolution() {
        return new Solution<>() {
            private final List<String> vars = new ArrayList<>(List.of("a", "b"));
            @Override public List<String> variables() { return vars; }
            @Override public double[] objectives() { return new double[1]; }
            @Override public double[] constraints() { return new double[0]; }
            @Override public Map<Object, Object> attributes() { return new HashMap<>(); }
            @Override public Solution<String> copy() { return this; }
        };
    }

    // ── flatten ───────────────────────────────────────────────────────────────

    @Test
    void flattenIntegerSolutionReturnsDefensiveCopy() {
        IntegerSolution s = intSolution(3, -17, 55);
        List<Number> flat = SolutionVariables.flatten(s);

        assertEquals(List.of(3, -17, 55), flat);
        assertInstanceOf(Integer.class, flat.get(0));

        flat.set(0, 999);
        assertEquals(3, s.variables().get(0), "flatten() must not alias the live variable list");
    }

    @Test
    void flattenDoubleSolutionKeepsDoubles() {
        DoubleSolution s = doubleSolution(0.5, -2.25, 1e-9);
        List<Number> flat = SolutionVariables.flatten(s);

        assertEquals(List.of(0.5, -2.25, 1e-9), flat);
        assertInstanceOf(Double.class, flat.get(0));
    }

    @Test
    void flattenCompositeConcatenatesSegmentsInOrder() {
        CompositeSolution c = composite(intSolution(1, 2), doubleSolution(0.5, 0.25), intSolution(7));
        List<Number> flat = SolutionVariables.flatten(c);

        assertEquals(List.of(1, 2, 0.5, 0.25, 7), flat);
        assertEquals(5, SolutionVariables.size(c));
    }

    // ── encodings ─────────────────────────────────────────────────────────────

    @Test
    void encodingIsDecidedByClassOrByElementType() {
        assertEquals(SolutionVariables.Encoding.INT, SolutionVariables.encodingOf(intSolution(1)));
        assertEquals(SolutionVariables.Encoding.DOUBLE, SolutionVariables.encodingOf(doubleSolution(1.0)));
        assertEquals("int", SolutionVariables.wireEncoding(intSolution(1)));
        assertEquals("double", SolutionVariables.wireEncoding(doubleSolution(1.0)));
        assertNull(SolutionVariables.segmentEncodings(intSolution(1)));
    }

    @Test
    void compositeEncodingIsHomogeneousOrMixed() {
        assertEquals("int", SolutionVariables.wireEncoding(composite(intSolution(1), intSolution(2))));
        assertEquals("double", SolutionVariables.wireEncoding(composite(doubleSolution(1.0), doubleSolution(2.0))));

        CompositeSolution mixed = composite(intSolution(1), doubleSolution(2.0));
        assertEquals("mixed", SolutionVariables.wireEncoding(mixed));
        assertEquals(List.of(SolutionVariables.Encoding.INT, SolutionVariables.Encoding.DOUBLE),
            SolutionVariables.segmentEncodings(mixed));
        assertEquals(List.of("int", "double"),
            SolutionVariables.wireNames(SolutionVariables.segmentEncodings(mixed)));
    }

    @Test
    void unsupportedSolutionTypeIsRejectedWithClassName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.flatten(stringSolution()));
        assertTrue(e.getMessage().contains("Unsupported solution type"), e.getMessage());

        CompositeSolution withUnsupportedSegment = composite(intSolution(1), stringSolution());
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.flatten(withUnsupportedSegment));
        assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(withUnsupportedSegment, List.of(1, 2, 3)));
    }

    // ── round trips ───────────────────────────────────────────────────────────

    @Test
    void roundTripIntegerSolution() {
        IntegerSolution source = intSolution(3, -17, 55);
        IntegerSolution target = intSolution(0, 0, 0);

        SolutionVariables.apply(target, SolutionVariables.flatten(source));

        assertEquals(source.variables(), target.variables());
        assertInstanceOf(Integer.class, target.variables().get(0));
    }

    @Test
    void roundTripDoubleSolution() {
        DoubleSolution source = doubleSolution(0.5, -2.25, 1e-9);
        DoubleSolution target = doubleSolution(0, 0, 0);

        SolutionVariables.apply(target, SolutionVariables.flatten(source));

        assertEquals(source.variables(), target.variables());
        assertInstanceOf(Double.class, target.variables().get(0));
    }

    @Test
    void roundTripMixedComposite() {
        CompositeSolution source = composite(intSolution(1, 2), doubleSolution(0.5, 0.25), intSolution(7));
        CompositeSolution target = composite(intSolution(0, 0), doubleSolution(0, 0), intSolution(0));

        SolutionVariables.apply(target, SolutionVariables.flatten(source));

        assertEquals(List.of(1, 2), target.variables().get(0).variables());
        assertEquals(List.of(0.5, 0.25), target.variables().get(1).variables());
        assertEquals(List.of(7), target.variables().get(2).variables());
        assertInstanceOf(Integer.class, target.variables().get(0).variables().get(0));
        assertInstanceOf(Double.class, target.variables().get(1).variables().get(0));
    }

    // ── conversion by destination type ────────────────────────────────────────

    @Test
    void integersArrivingForRealVariablesBecomeDoubles() {
        DoubleSolution target = doubleSolution(0, 0, 0);

        SolutionVariables.apply(target, List.of(1, 2L, 3));

        assertEquals(List.of(1.0, 2.0, 3.0), target.variables());
        for (Object v : target.variables()) assertInstanceOf(Double.class, v);
    }

    @Test
    void integralDoublesArrivingForIntegerVariablesBecomeIntegers() {
        IntegerSolution target = intSolution(0, 0, 0);

        SolutionVariables.apply(target, List.of(2.0, -3.0, 4.0000000000001));

        assertEquals(List.of(2, -3, 4), target.variables());
        for (Object v : target.variables()) assertInstanceOf(Integer.class, v);
    }

    @Test
    void nonIntegralValueForIntegerVariableIsRejected() {
        IntegerSolution target = intSolution(0, 0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(target, List.of(1, 2.7)));

        assertTrue(e.getMessage().contains("variables[1]"), e.getMessage());
        assertEquals(List.of(0, 0), target.variables(), "a rejected vector must not touch the solution");
    }

    @Test
    void nonFiniteAndNullValuesAreRejected() {
        DoubleSolution target = doubleSolution(0, 0);

        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, List.of(1.0, Double.NaN)));
        assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(target, List.of(Double.POSITIVE_INFINITY, 1.0)));
        List<Number> withNull = new ArrayList<>();
        withNull.add(1.0);
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, withNull));
        assertEquals(List.of(0.0, 0.0), target.variables());
    }

    @Test
    void lengthMismatchIsRejected() {
        IntegerSolution target = intSolution(0, 0, 0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(target, List.of(1, 2)));
        assertTrue(e.getMessage().contains("2 values") && e.getMessage().contains("3"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, List.of(1, 2, 3, 4)));
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, null));
    }

    @Test
    void integerOverflowIsRejected() {
        IntegerSolution target = intSolution(0);

        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, List.of(1L << 40)));
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.apply(target, List.of(3e12)));
    }

    @Test
    void compositeRejectionLeavesEverySegmentUntouched() {
        CompositeSolution target = composite(intSolution(0, 0), doubleSolution(0, 0));

        assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(target, List.of(1, 2, 0.5, Double.NaN)));

        assertEquals(List.of(0, 0), target.variables().get(0).variables());
        assertEquals(List.of(0.0, 0.0), target.variables().get(1).variables());
    }

    // ── custom solutions and corrupted variables ──────────────────────────────

    /** A solution of no jMetal type whose variables are the given objects. */
    static Solution<Object> customSolution(Object... values) {
        return new Solution<>() {
            private final List<Object> vars = new ArrayList<>(java.util.Arrays.asList(values));
            @Override public List<Object> variables() { return vars; }
            @Override public double[] objectives() { return new double[1]; }
            @Override public double[] constraints() { return new double[0]; }
            @Override public Map<Object, Object> attributes() { return new HashMap<>(); }
            @Override public Solution<Object> copy() { return this; }
        };
    }

    @Test
    void customSolutionIsAcceptedWhenAllItsVariablesShareOneType() {
        assertEquals(SolutionVariables.Encoding.INT, SolutionVariables.encodingOf(customSolution(3, 1, 2)),
            "an integer permutation");
        assertEquals(SolutionVariables.Encoding.DOUBLE, SolutionVariables.encodingOf(customSolution(0.5, 1.5)));
        assertEquals(List.of(3, 1, 2), SolutionVariables.flatten(customSolution(3, 1, 2)));
    }

    @Test
    void customSolutionMixingIntegersAndDoublesIsRejectedUpFront() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.wireEncoding(customSolution(1, 2.5)));
        assertTrue(e.getMessage().contains("variables[0] is Integer but variables[1] is Double"), e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.flatten(customSolution(0.5, 1)),
            "a flat vector has one encoding: announcing it as either would break the other values");
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.encodingOf(customSolution(1, null)));
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.encodingOf(customSolution(1, 2L)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void flattenReportsANullOrForeignVariableWithItsPosition() {
        IntegerSolution withNull = intSolution(1, 2);
        ((List) withNull.variables()).set(1, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.flatten(composite(doubleSolution(0.5), withNull)));
        assertEquals("variables[2] is null", e.getMessage(), "the position in the flat vector");

        DoubleSolution withString = doubleSolution(0.5);
        ((List) withString.variables()).set(0, "x");
        e = assertThrows(IllegalArgumentException.class, () -> SolutionVariables.flatten(withString),
            "an IllegalArgumentException, not a ClassCastException");
        assertEquals("variables[0] = x is a String but its segment is double-encoded", e.getMessage());
    }

    @Test
    void flattenKeepsNonFiniteValuesAndCheckFiniteReportsThem() {
        List<Number> flat = SolutionVariables.flatten(doubleSolution(0.5, Double.NaN));

        assertEquals(2, flat.size(), "keys and traces may hold a NaN gene");
        assertEquals("variables[1] is not finite: NaN", SolutionVariables.checkFinite(flat));
        assertEquals("variables[0] is not finite: -Infinity",
            SolutionVariables.checkFinite(List.of(Double.NEGATIVE_INFINITY)));
        assertNull(SolutionVariables.checkFinite(List.of(1, 2.5, -3L)));
    }

    @Test
    void nestedCompositeIsReportedAsSuchNotAsALengthMismatch() {
        CompositeSolution nested = composite(composite(intSolution(1), intSolution(2)), intSolution(3));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.convert(nested, List.of(1, 2, 3)));
        assertTrue(e.getMessage().contains("nested CompositeSolution segments are not supported"), e.getMessage());
    }

    // ── bounds ────────────────────────────────────────────────────────────────

    @Test
    void checkBoundsAcceptsTheBoundsThemselvesAndNamesTheFirstValueOutside() {
        IntegerSolution integer = intSolution(0, 0);
        assertNull(SolutionVariables.checkBounds(integer, List.of(-1000, 1000)));
        assertEquals("variables[0] = -1001 is outside the bounds [-1000, 1000] of its variable",
            SolutionVariables.checkBounds(integer, List.of(-1001, 0)));

        DoubleSolution real = doubleSolution(0);
        assertEquals("variables[0] = 1000.25 is outside the bounds [-1000.0, 1000.0] of its variable",
            SolutionVariables.checkBounds(real, List.of(1000.25)));

        CompositeSolution mixed = composite(intSolution(0), doubleSolution(0, 0));
        String reason = SolutionVariables.checkBounds(mixed, List.of(5, 0.5, -2000.0));
        assertNotNull(reason);
        assertTrue(reason.startsWith("variables[2] = -2000.0"), "positions count across segments: " + reason);

        assertNull(SolutionVariables.checkBounds(customSolution(1, 2), List.of(1_000_000, -1_000_000)),
            "a solution without declared bounds accepts any value");
    }

    @Test
    void flattenedKeysHaveValueEquality() {
        assertEquals(SolutionVariables.flatten(intSolution(1, 2)), SolutionVariables.flatten(intSolution(1, 2)));
        assertEquals(SolutionVariables.flatten(composite(intSolution(1), doubleSolution(0.5))),
            SolutionVariables.flatten(composite(intSolution(1), doubleSolution(0.5))));
        assertNotEquals(SolutionVariables.flatten(doubleSolution(0.5)), SolutionVariables.flatten(doubleSolution(0.5000001)));
    }
}

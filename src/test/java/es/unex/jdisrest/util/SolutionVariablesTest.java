package es.unex.jdisrest.util;

import es.unex.jdisrest.util.SolutionVariables.Encoding;
import es.unex.jdisrest.util.SolutionVariables.VectorLayout;
import es.unex.jdisrest.util.SolutionVariables.VectorLayout.Segment;
import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;
import org.uma.jmetal.util.bounds.Bounds;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static es.unex.jdisrest.util.CompositeSolutionListOutputTest.binarySolution;
import static es.unex.jdisrest.util.TraceWriterTest.bits;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Round trips of {@link SolutionVariables} for every supported solution shape, flat and
 * composite, with integer, real and binary variables, the layout of the vector, and the
 * conversion-by-destination rules.
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
    void checkBoundsCountsTheBitsOfABinarySegmentAndChecksNothingInIt() {
        CompositeSolution mixed = composite(intSolution(0), binarySolution("101", "01"), doubleSolution(0));

        assertEquals("variables[6] = 2000.0 is outside the bounds [-1000.0, 1000.0] of its variable",
            SolutionVariables.checkBounds(mixed, List.of(5, 1, 0, 1, 0, 1, 2000.0)),
            "the real variable comes after the five bits");
        assertNull(SolutionVariables.checkBounds(binarySolution("101"), List.of(1, 1, 1)),
            "a bit has no bounds beyond the 0 or 1 that convert requires");
    }

    @Test
    void flattenedKeysHaveValueEquality() {
        assertEquals(SolutionVariables.flatten(intSolution(1, 2)), SolutionVariables.flatten(intSolution(1, 2)));
        assertEquals(SolutionVariables.flatten(composite(intSolution(1), doubleSolution(0.5))),
            SolutionVariables.flatten(composite(intSolution(1), doubleSolution(0.5))));
        assertNotEquals(SolutionVariables.flatten(doubleSolution(0.5)), SolutionVariables.flatten(doubleSolution(0.5000001)));
    }

    // ── binary variables ──────────────────────────────────────────────────────

    @Test
    void flattenWritesOneIntegerPerBitBitZeroFirst() {
        List<Number> flat = SolutionVariables.flatten(binarySolution("101", "00110"));

        assertEquals(List.of(1, 0, 1, 0, 0, 1, 1, 0), flat, "[1,0,1] is the variable a trace writes as 101");
        flat.forEach(bit -> assertInstanceOf(Integer.class, bit, "Jackson writes 0 and 1, not true and false"));
        assertEquals(8, SolutionVariables.size(binarySolution("101", "00110")), "size counts the bits");
    }

    @Test
    void layoutOfEveryShapeGivesTheWidthsOfItsVector() {
        assertEquals(new VectorLayout(false, List.of(new Segment(Encoding.BINARY, 8, List.of(3, 5)))),
            SolutionVariables.layoutOf(binarySolution("101", "00110")));

        VectorLayout mixed = SolutionVariables.layoutOf(
            composite(intSolution(3, -7), doubleSolution(0.25), binarySolution("101", "00110")));
        assertTrue(mixed.composite());
        assertEquals(11, mixed.width());
        assertEquals("mixed", mixed.wireName());
        assertEquals(List.of(2, 1, 8), mixed.segmentSizes(), "a binary segment counts its bits");
        assertEquals(List.of("int", "double", "binary"), mixed.wireSegmentEncodings());
        assertEquals(List.of(3, 5), mixed.bitsPerVariable());

        VectorLayout allBinary = SolutionVariables.layoutOf(composite(binarySolution("101"), binarySolution("00110")));
        assertEquals("binary", allBinary.wireName());
        assertEquals(List.of(3, 5), allBinary.segmentSizes());
        assertEquals(List.of(3, 5), allBinary.bitsPerVariable(), "the lengths in vector order across the segments");

        VectorLayout flatInteger = SolutionVariables.layoutOf(intSolution(1, 2, 3));
        assertNull(flatInteger.segmentSizes());
        assertNull(flatInteger.wireSegmentEncodings());
        assertEquals(List.of(), flatInteger.bitsPerVariable());
    }

    @Test
    void layoutOfEveryGoldenShapeAgreesWithItsVector() {
        TraceWriterTest.shapes().forEach((shape, solutions) -> {
            Solution<?> first = solutions.get(0);
            VectorLayout layout = SolutionVariables.layoutOf(first);
            assertEquals(SolutionVariables.flatten(first).size(), layout.width(), shape);
            assertEquals(SolutionVariables.size(first), layout.width(), shape);
            assertEquals(SolutionVariables.wireEncoding(first), layout.wireName(), shape);
            assertEquals(SolutionVariables.layoutOf(solutions.get(1)), layout, "two solutions of one shape: " + shape);
        });
    }

    @Test
    void lengthsAreTheDeclaredOnesNotThoseOfTheBitsThatAreSet() {
        assertEquals(4, bits("00110").length(), "BitSet.length() stops at the highest bit set");
        assertEquals(0, bits("00000").length());

        assertEquals(List.of(5, 5), SolutionVariables.layoutOf(binarySolution("00110", "00000")).bitsPerVariable());
        assertEquals(List.of(0, 0, 1, 1, 0, 0, 0, 0, 0, 0),
            SolutionVariables.flatten(binarySolution("00110", "00000")), "an all-zero variable keeps its bits");

        BinarySolution stray = binarySolution("101");
        stray.variables().get(0).set(7);
        assertEquals(List.of(1, 0, 1), SolutionVariables.flatten(stray), "a bit set beyond the length is not read");
    }

    @Test
    void flattenCopiesTheBitsSoAKeyOutlivesAnInPlaceFlip() {
        BinarySolution s = binarySolution("101", "00110");
        List<Number> key = SolutionVariables.flatten(s);

        s.variables().get(0).flip(0);  // what BitFlipMutation does

        assertEquals(List.of(1, 0, 1, 0, 0, 1, 1, 0), key, "a key holding the live BinarySet would have changed");
        assertNotEquals(key, SolutionVariables.flatten(s));
    }

    @Test
    void bitsAreZeroOrOneOfAnyNumericType() {
        List<Number> zeros = List.of(0, 0L, (short) 0, (byte) 0, BigInteger.ZERO, 0.0, -0.0, 1e-10,
            new BigDecimal("0.0"), 0.0f);
        List<Number> ones = List.of(1, 1L, (short) 1, (byte) 1, BigInteger.ONE, 1.0, 0.9999999999, 1.0000000001,
            new BigDecimal("1.00"), 1.0f);

        zeros.forEach(v -> assertEquals(0, SolutionVariables.convertValue(v, Encoding.BINARY, 5), "0 from " + v));
        ones.forEach(v -> assertEquals(1, SolutionVariables.convertValue(v, Encoding.BINARY, 5), "1 from " + v));
    }

    @Test
    void anythingButZeroOrOneIsNotABit() {
        for (Number v : List.<Number>of(2, -1, 0.5, 1.00001, -0.001, BigInteger.TWO, Long.MAX_VALUE,
                BigInteger.ONE.shiftLeft(64).add(BigInteger.ONE))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SolutionVariables.convertValue(v, Encoding.BINARY, 5), String.valueOf(v));
            assertEquals("variables[5] = " + v + " is not a bit (0 or 1) but its variable is binary", e.getMessage());
        }
        assertEquals("variables[5] is not finite: NaN", assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.convertValue(Double.NaN, Encoding.BINARY, 5)).getMessage());
        assertEquals("variables[5] is null", assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.convertValue(null, Encoding.BINARY, 5)).getMessage());
    }

    @Test
    void applyReplacesEachBinaryVariableWithANewSetOfItsLength() {
        BinarySolution target = binarySolution("000", "00000");
        BinarySet before = target.variables().get(0);

        SolutionVariables.apply(target, List.of(1, 0, 1.0, 0, 0, 1L, 1, 0));

        assertEquals("101", target.variables().get(0).toString());
        assertEquals("00110", target.variables().get(1).toString());
        assertEquals(3, target.variables().get(0).getBinarySetLength());
        assertEquals(5, target.variables().get(1).getBinarySetLength(), "the length of the variable, not of its bits");
        assertNotSame(before, target.variables().get(0), "a set an operator may share is never written into");
        assertEquals("000", before.toString());
    }

    @Test
    void roundTripCompositeWithEverySegmentKind() {
        CompositeSolution source = composite(intSolution(3, -7), doubleSolution(0.25), binarySolution("101", "00110"));
        CompositeSolution target = composite(intSolution(0, 0), doubleSolution(0), binarySolution("000", "00000"));

        SolutionVariables.apply(target, SolutionVariables.flatten(source));

        assertEquals(SolutionVariables.flatten(source), SolutionVariables.flatten(target));
        assertEquals("[101, 00110]", target.variables().get(2).variables().toString());
    }

    @Test
    void aBitThatIsNotZeroOrOneLeavesEverySegmentUntouched() {
        CompositeSolution target = composite(intSolution(0, 0), binarySolution("000", "00000"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.apply(target, List.of(1, 2, 1, 0, 1, 0, 0, 1, 1, 2)));

        assertEquals("variables[9] = 2 is not a bit (0 or 1) but its variable is binary", e.getMessage());
        assertEquals(List.of(0, 0), target.variables().get(0).variables());
        assertEquals("[000, 00000]", target.variables().get(1).variables().toString());
        assertEquals("variables has 9 values but the solution has 10", assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.convert(target, List.of(1, 2, 1, 0, 1, 0, 0, 1, 1))).getMessage(),
            "the length counts the bits");
    }

    @Test
    void binaryVariableOfNoBitsIsRejected() {
        BinarySolution s = binarySolution("101", "0", "00110");
        s.variables().set(1, new BinarySet(0));
        CompositeSolution c = composite(intSolution(7), s);
        String reason = "variables[4] is a BinarySet of 0 bits; a binary variable needs at least one";

        assertEquals(reason, assertThrows(IllegalArgumentException.class, () -> SolutionVariables.layoutOf(c)).getMessage(),
            "it would take no position in the vector and leave an empty token in a trace");
        assertEquals(reason, assertThrows(IllegalArgumentException.class, () -> SolutionVariables.flatten(c)).getMessage());
        assertEquals(reason, assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.wireEncoding(c)).getMessage(), "which the run() guard of the masters calls");
        assertThrows(IllegalArgumentException.class, () -> SolutionVariables.convert(c, List.of(7, 1, 0, 1, 0, 0, 1, 1, 0)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aNullOrForeignBinaryVariableIsReportedAtThePositionOfItsFirstBit() {
        BinarySolution withNull = binarySolution("101", "01");
        withNull.variables().set(1, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.flatten(composite(intSolution(1, 2), withNull)));
        assertEquals("variables[5] is null", e.getMessage(), "two integers, then three bits");

        BinarySolution withInteger = binarySolution("101", "01");
        ((List) withInteger.variables()).set(1, 7);
        e = assertThrows(IllegalArgumentException.class, () -> SolutionVariables.layoutOf(withInteger),
            "an IllegalArgumentException, not a ClassCastException");
        assertEquals("variables[3] = 7 is a Integer but its segment is binary-encoded", e.getMessage());

        IntegerSolution withSet = intSolution(1, 2);
        ((List) withSet.variables()).set(1, bits("01"));
        e = assertThrows(IllegalArgumentException.class, () -> SolutionVariables.flatten(withSet));
        assertEquals("variables[1] = 01 is a BinarySet but its segment is int-encoded", e.getMessage());
    }

    @Test
    void customSolutionOfBinarySetsIsBinary() {
        assertEquals(Encoding.BINARY, SolutionVariables.encodingOf(customSolution(bits("10"), bits("1"))));
        assertEquals(List.of(1, 0, 1), SolutionVariables.flatten(customSolution(bits("10"), bits("1"))));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SolutionVariables.encodingOf(customSolution(bits("10"), 1)));
        assertTrue(e.getMessage().endsWith(": variables[0] is BinarySet but variables[1] is Integer; "
            + "the variables of a flat solution must be all Integer, all Double or all BinarySet"), e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> SolutionVariables.encodingOf(stringSolution()));
        assertTrue(e.getMessage().endsWith(
            ": only IntegerSolution, DoubleSolution, BinarySolution and CompositeSolution of those are supported"),
            e.getMessage());
    }

    @Test
    void segmentsCheckThatTheirLengthsMakeTheirWidth() {
        assertEquals(8, new Segment(Encoding.BINARY, 8, List.of(3, 5)).width());

        assertThrows(IllegalArgumentException.class, () -> new Segment(Encoding.BINARY, 7, List.of(3, 5)));
        assertThrows(IllegalArgumentException.class, () -> new Segment(Encoding.BINARY, 3, List.of(3, 0)),
            "a binary variable of no bits");
        assertThrows(IllegalArgumentException.class, () -> new Segment(Encoding.INT, 2, List.of(2)),
            "only binary variables have lengths");
        assertThrows(IllegalArgumentException.class, () -> new Segment(Encoding.DOUBLE, -1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new VectorLayout(false, List.of()),
            "a flat solution is one segment");
        assertNotEquals(SolutionVariables.layoutOf(binarySolution("101", "00110")),
            SolutionVariables.layoutOf(binarySolution("10100", "110")), "same width, other lengths");
    }
}

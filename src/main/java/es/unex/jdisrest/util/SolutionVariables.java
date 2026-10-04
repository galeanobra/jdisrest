package es.unex.jdisrest.util;

import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.binarySet.BinarySet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Single place where decision variables cross the boundary between a jMetal
 * {@link Solution} and a flat numeric vector (REST payloads, the local Python
 * protocol, duplicate-detection keys, trace files).
 *
 * <p>Supported solution types: {@link IntegerSolution}, {@link DoubleSolution},
 * {@link BinarySolution} and {@link CompositeSolution} whose components are any mix of
 * the three. A solution that is none of these is accepted when its variables are all
 * {@link Integer}, all {@link Double} or all {@link BinarySet} at runtime (e.g. an
 * integer permutation); anything else (other types, a mix of them, a {@code null}
 * variable, a composite nested in a composite) is rejected with an
 * {@link IllegalArgumentException}.
 *
 * <p>An integer or real variable is one value of the vector. A binary variable (a
 * {@link BinarySet}) is one value per bit of its length, bit 0 first, each an
 * {@link Integer} 0 or 1, so the vector of a solution with binary variables is longer than
 * its list of variables. {@link #layoutOf} reads the shape of the vector, segment by
 * segment, and every method here takes the widths from it. Positions in messages are those
 * of the vector; the position of a binary variable is that of its first bit.
 *
 * <p>Writing values back never trusts the numeric type that arrived on the
 * wire: each value is converted to the type the <em>destination</em> variable
 * requires (an {@code int} for integer encodings, a {@code double} for real
 * encodings, a bit for binary ones). A JSON {@code 5} bound by
 * Jackson to an {@link Integer} therefore lands correctly in a
 * {@code DoubleSolution}, and a {@code 5.0} bound to a {@link Double} lands
 * correctly in an {@code IntegerSolution}. Values that cannot be represented by
 * the destination (non-finite doubles, non-integral values for an integer
 * variable — beyond {@link #INTEGRALITY_TOLERANCE} — integer overflow, anything but 0
 * or 1 for a bit, {@code null}) are rejected — never truncated or ignored silently. (A
 * real-encoded destination takes {@link Number#doubleValue()}, so a {@code long} beyond
 * 2<sup>53</sup>, which Jackson never produces for these vectors, would be rounded.)
 * Variables are not checked against their bounds here; {@link #checkBounds} does that for
 * callers that accept vectors from outside, such as Lamarckian results.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class SolutionVariables {

    private SolutionVariables() {}

    /**
     * Tolerance used when an integer-encoded variable or a bit arrives as a
     * floating-point number. {@code 3.0000000000001} is accepted as {@code 3}
     * and {@code 0.9999999999999} as the bit {@code 1}; {@code 2.7} is rejected.
     */
    public static final double INTEGRALITY_TOLERANCE = 1e-9;

    /** Variable encoding of a flat (non-composite) solution, with its wire name. */
    public enum Encoding {
        INT("int"),
        DOUBLE("double"),
        /** {@link BinarySet} variables, which take one value 0 or 1 per bit in the vector. */
        BINARY("binary");

        private final String wireName;

        Encoding(String wireName) {
            this.wireName = wireName;
        }

        /** Value used in the {@code encoding} / {@code segmentEncodings} JSON fields. */
        public String wireName() {
            return wireName;
        }
    }

    /** Wire name reported for a {@link CompositeSolution} whose segments use different encodings. */
    public static final String MIXED = "mixed";

    /**
     * Shape of the flat vector of a solution, as {@link #layoutOf} reads it: the encoding and
     * the width of each segment, and the length of every binary variable. Every width this
     * class uses comes from here, so the payload sent to a worker, the worker's check of it and
     * the conversions agree; two solutions of one problem have equal layouts.
     *
     * <p>The REST payload carries what a worker needs of it, with the omission rules of
     * {@code TaskPayload}: {@link #segmentSizes()}, {@link #wireName()},
     * {@link #wireSegmentEncodings()} and {@link #bitsPerVariable()}.
     *
     * @param composite whether the solution is a {@link CompositeSolution}
     * @param segments  the segments in vector order: one per component of a composite, the
     *                  solution itself for a flat one
     */
    public record VectorLayout(boolean composite, List<Segment> segments) {

        /**
         * @throws NullPointerException     if {@code segments} or one of them is {@code null}
         * @throws IllegalArgumentException if a flat layout does not have exactly one segment
         */
        public VectorLayout {
            segments = List.copyOf(segments);
            if (!composite && segments.size() != 1) {
                throw new IllegalArgumentException("a flat layout has one segment, not " + segments.size());
            }
        }

        /**
         * One segment of the vector: a flat solution, or one component of a composite.
         *
         * @param encoding        the encoding of its variables
         * @param width           the values it occupies in the vector: one per variable for
         *                        {@link Encoding#INT} and {@link Encoding#DOUBLE}, one per bit for
         *                        {@link Encoding#BINARY}
         * @param bitsPerVariable for {@link Encoding#BINARY}, the length of each of its variables
         *                        in order, which add up to {@code width}; empty for the others
         */
        public record Segment(Encoding encoding, int width, List<Integer> bitsPerVariable) {

            /**
             * @throws NullPointerException     if an argument or a length is {@code null}
             * @throws IllegalArgumentException if the width is negative, a binary variable has no
             *                                  bits, the lengths do not add up to the width, or a
             *                                  segment that is not binary has lengths
             */
            public Segment {
                Objects.requireNonNull(encoding, "encoding");
                bitsPerVariable = List.copyOf(bitsPerVariable);
                if (width < 0) {
                    throw new IllegalArgumentException("a segment cannot have a negative width: " + width);
                }
                if (encoding == Encoding.BINARY) {
                    long bits = 0;
                    for (int length : bitsPerVariable) {
                        if (length < 1) {
                            throw new IllegalArgumentException("a binary variable needs at least one bit: bitsPerVariable "
                                + bitsPerVariable);
                        }
                        bits += length;
                    }
                    if (bits != width) {
                        throw new IllegalArgumentException("bitsPerVariable " + bitsPerVariable + " add up to " + bits
                            + ", not to the width " + width + " of the segment");
                    }
                } else if (!bitsPerVariable.isEmpty()) {
                    throw new IllegalArgumentException("only a binary segment has bitsPerVariable, not a "
                        + encoding.wireName() + " one: " + bitsPerVariable);
                }
            }
        }

        /** The number of values in the vector, the sum of the segment widths: the size of {@link #flatten}. */
        public int width() {
            int width = 0;
            for (Segment segment : segments) width += segment.width();
            return width;
        }

        /**
         * Wire name of the whole vector, the {@code encoding} field of the REST payload: the
         * wire name of the encoding of a flat solution, or of a composite whose segments share
         * one; {@link #MIXED} for a composite whose segments differ; {@code "int"} for a
         * composite of no segments.
         */
        public String wireName() {
            if (segments.isEmpty()) return Encoding.INT.wireName();
            Encoding first = segments.get(0).encoding();
            for (Segment segment : segments) {
                if (segment.encoding() != first) return MIXED;
            }
            return first.wireName();
        }

        /**
         * The width of each segment, the {@code segmentSizes} field of the REST payload (a binary
         * segment counts its bits), or {@code null} for a flat solution.
         */
        public List<Integer> segmentSizes() {
            return composite ? segments.stream().map(Segment::width).toList() : null;
        }

        /**
         * The wire name of each segment's encoding, or {@code null} for a flat solution. Unlike
         * {@link SolutionVariables#segmentEncodings(Solution)} these are names; the REST payload
         * sends them as {@code segmentEncodings} unless every segment is an integer one.
         */
        public List<String> wireSegmentEncodings() {
            return composite ? segments.stream().map(s -> s.encoding().wireName()).toList() : null;
        }

        /**
         * The length of every binary variable, in vector order across the segments (the
         * {@code bitsPerVariable} field of the REST payload); empty when there is none.
         */
        public List<Integer> bitsPerVariable() {
            List<Integer> bits = new ArrayList<>();
            for (Segment segment : segments) bits.addAll(segment.bitsPerVariable());
            return Collections.unmodifiableList(bits);
        }
    }

    // ── Type inspection ───────────────────────────────────────────────────────

    /**
     * Returns the encoding of a flat (non-composite) solution.
     *
     * <p>Decided by class first ({@link IntegerSolution} → {@link Encoding#INT},
     * {@link DoubleSolution} → {@link Encoding#DOUBLE}, {@link BinarySolution} →
     * {@link Encoding#BINARY}); otherwise by the runtime type of the variables, which must be
     * all {@link Integer}, all {@link Double} or all {@link BinarySet}: a flat vector has a
     * single encoding on the wire, so a solution mixing them (or holding anything else) cannot
     * be described and is rejected up front, instead of being announced as one encoding and
     * then failing on every value that is not.
     *
     * @param solution a non-composite solution
     * @return its encoding
     * @throws IllegalArgumentException if the solution is a {@link CompositeSolution}
     *                                  or its encoding cannot be determined
     */
    public static Encoding encodingOf(Solution<?> solution) {
        if (solution instanceof CompositeSolution) {
            throw new IllegalArgumentException("CompositeSolution found where a flat solution was expected: "
                + "nested CompositeSolution segments are not supported");
        }
        if (solution instanceof IntegerSolution) return Encoding.INT;
        if (solution instanceof DoubleSolution) return Encoding.DOUBLE;
        if (solution instanceof BinarySolution) return Encoding.BINARY;
        List<?> vars = solution.variables();
        if (!vars.isEmpty()) {
            Object first = vars.get(0);
            Encoding encoding = first instanceof Integer ? Encoding.INT
                : first instanceof Double ? Encoding.DOUBLE
                : first instanceof BinarySet ? Encoding.BINARY : null;
            if (encoding != null) {
                Class<?> type = variableType(encoding);
                for (int i = 1; i < vars.size(); i++) {
                    Object v = vars.get(i);
                    if (!type.isInstance(v)) {
                        throw new IllegalArgumentException("Unsupported solution type " + solution.getClass().getName()
                            + ": variables[0] is " + type.getSimpleName() + " but variables[" + i + "] is "
                            + (v == null ? "null" : v.getClass().getSimpleName())
                            + "; the variables of a flat solution must be all Integer, all Double or all BinarySet");
                    }
                }
                return encoding;
            }
        }
        throw new IllegalArgumentException("Unsupported solution type " + solution.getClass().getName()
            + ": only IntegerSolution, DoubleSolution, BinarySolution and CompositeSolution of those are supported");
    }

    /**
     * Returns one encoding per component of a {@link CompositeSolution}, in
     * declaration order, or {@code null} for a flat solution.
     *
     * @param solution the solution to inspect
     * @return per-segment encodings, or {@code null} when not composite
     * @throws IllegalArgumentException if a component has an unsupported type
     */
    public static List<Encoding> segmentEncodings(Solution<?> solution) {
        if (!(solution instanceof CompositeSolution composite)) return null;
        List<Encoding> out = new ArrayList<>(composite.variables().size());
        for (Solution<?> component : composite.variables()) {
            out.add(encodingOf(component));
        }
        return out;
    }

    /**
     * Wire name describing the whole solution: {@code "int"}, {@code "double"} or
     * {@code "binary"} for flat solutions and homogeneous composites, {@link #MIXED} for a
     * composite whose segments differ ({@link VectorLayout#wireName()}).
     *
     * @param solution the solution to inspect
     * @return {@code "int"}, {@code "double"}, {@code "binary"} or {@code "mixed"}
     * @throws IllegalArgumentException under the conditions of {@link #layoutOf}
     */
    public static String wireEncoding(Solution<?> solution) {
        return layoutOf(solution).wireName();
    }

    /**
     * Reads the layout of a solution's flat vector: its segments in order, the encoding of each
     * ({@link #encodingOf}), their widths, and the length of every binary variable.
     *
     * <p>The variables of a binary segment are read, since their lengths make its width: each
     * must be a {@link BinarySet} of at least one bit. A variable of no bits would take no
     * position in the vector, and an empty token in a trace that no reader could tell from a
     * missing one, so it is rejected. The variables of an integer or real segment are not read
     * ({@link #flatten} checks them); its width is their number.
     *
     * @param solution the solution to inspect
     * @return its layout
     * @throws IllegalArgumentException if the solution, or one of its segments, is not of a
     *                                  supported type (see the class description), or a binary
     *                                  variable is {@code null}, not a {@link BinarySet} or of no
     *                                  bits
     */
    public static VectorLayout layoutOf(Solution<?> solution) {
        List<? extends Solution<?>> components = segmentsOf(solution);
        List<VectorLayout.Segment> segments = new ArrayList<>(components.size());
        int offset = 0;
        for (Solution<?> component : components) {
            VectorLayout.Segment segment = segmentOf(component, offset);
            segments.add(segment);
            offset += segment.width();
        }
        return new VectorLayout(solution instanceof CompositeSolution, segments);
    }

    /**
     * The layout of one flat solution or segment, whose first value is at {@code offset}.
     *
     * @throws IllegalArgumentException as {@link #layoutOf}
     */
    private static VectorLayout.Segment segmentOf(Solution<?> solution, int offset) {
        Encoding encoding = encodingOf(solution);
        return switch (encoding) {
            case INT, DOUBLE -> new VectorLayout.Segment(encoding, solution.variables().size(), List.of());
            case BINARY -> {
                List<Integer> bitsPerVariable = new ArrayList<>(solution.variables().size());
                int position = offset;
                for (Object v : solution.variables()) {
                    int length = binaryVariable(v, position).getBinarySetLength();
                    bitsPerVariable.add(length);
                    position += length;
                }
                yield new VectorLayout.Segment(encoding, position - offset, bitsPerVariable);
            }
        };
    }

    /**
     * Total number of values in the flat vector: one per integer or real variable, one per bit
     * of each binary variable, summed over the segments of a composite. It is the
     * {@link VectorLayout#width()} of {@link #layoutOf}, and the size of {@link #flatten}.
     *
     * @param solution the solution to measure
     * @return the length of its flat vector
     * @throws IllegalArgumentException under the conditions of {@link #layoutOf}
     */
    public static int size(Solution<?> solution) {
        return layoutOf(solution).width();
    }

    // ── Solution → vector ─────────────────────────────────────────────────────

    /**
     * Copies the decision variables into a new flat list. For a composite the
     * components are concatenated in declaration order: {@code [seg0 | seg1 | ...]}.
     * Element types are preserved ({@link Integer} for integer segments,
     * {@link Double} for real ones), so Jackson serializes integers as
     * {@code 5} and reals as {@code 5.0}. A binary variable contributes one {@link Integer}
     * per bit of its length, {@code 1} or {@code 0}, bit 0 first; a bit set beyond its length is
     * not read.
     *
     * <p>The result is always a fresh, mutable list: callers may store it (e.g.
     * as a {@code HashSet} key) without aliasing the solution's live list, and it does not
     * change when a {@link BinarySet} of the solution is changed in place later.
     *
     * <p>Every element is checked against its segment's encoding, so a {@code null}
     * variable, or one of another type smuggled in through raw types, is reported
     * with its position instead of failing later with a {@link ClassCastException} or
     * travelling to a worker as JSON {@code null}. Non-finite values are copied as they
     * are (they are legitimate keys and trace values); {@link #checkFinite} tells
     * whether the vector can travel as JSON numbers.
     *
     * @param solution the solution to read
     * @return a new list with all the values of the vector
     * @throws IllegalArgumentException if the solution (or one component) is unsupported, or
     *                                  a variable is {@code null}, not of its encoding's type or
     *                                  a binary variable of no bits
     */
    public static List<Number> flatten(Solution<?> solution) {
        VectorLayout layout = layoutOf(solution);
        List<? extends Solution<?>> components = segmentsOf(solution);
        List<Number> flat = new ArrayList<>(layout.width());
        for (int k = 0; k < components.size(); k++) {
            addVariables(flat, components.get(k), layout.segments().get(k).encoding());
        }
        return flat;
    }

    /**
     * Appends the variables of a flat solution or segment to {@code flat}, checking that each
     * is of the type of its encoding: an {@link Integer} ({@link Encoding#INT}), a
     * {@link Double} ({@link Encoding#DOUBLE}) or a {@link BinarySet} of at least one bit
     * ({@link Encoding#BINARY}), whose bits are appended.
     *
     * @param flat     the vector being built; its size gives the position of the next value
     * @param solution the flat solution or segment to read
     * @param encoding its encoding
     * @throws IllegalArgumentException if a variable is {@code null}, of another type or a binary
     *                                  variable of no bits
     */
    private static void addVariables(List<Number> flat, Solution<?> solution, Encoding encoding) {
        Class<?> expected = variableType(encoding);
        for (Object v : solution.variables()) {
            if (v == null) {
                throw new IllegalArgumentException("variables[" + flat.size() + "] is null");
            }
            if (!expected.isInstance(v)) {
                throw new IllegalArgumentException("variables[" + flat.size() + "] = " + v + " is a "
                    + v.getClass().getSimpleName() + " but its segment is " + encoding.wireName() + "-encoded");
            }
            if (v instanceof BinarySet) {
                BinarySet bits = binaryVariable(v, flat.size());
                for (int i = 0; i < bits.getBinarySetLength(); i++) flat.add(bits.get(i) ? 1 : 0);
            } else {
                flat.add((Number) v);
            }
        }
    }

    /** The type of the variables of an encoding. */
    private static Class<?> variableType(Encoding encoding) {
        return switch (encoding) {
            case INT -> Integer.class;
            case DOUBLE -> Double.class;
            case BINARY -> BinarySet.class;
        };
    }

    /**
     * Checks one variable of a binary segment.
     *
     * @param v        the variable
     * @param position its position in the vector, the position of its first bit
     * @return the variable
     * @throws IllegalArgumentException if it is {@code null}, not a {@link BinarySet} or of no bits
     */
    private static BinarySet binaryVariable(Object v, int position) {
        if (v == null) {
            throw new IllegalArgumentException("variables[" + position + "] is null");
        }
        if (!(v instanceof BinarySet bits)) {
            throw new IllegalArgumentException("variables[" + position + "] = " + v + " is a "
                + v.getClass().getSimpleName() + " but its segment is binary-encoded");
        }
        if (bits.getBinarySetLength() < 1) {
            throw new IllegalArgumentException("variables[" + position + "] is a BinarySet of "
                + bits.getBinarySetLength() + " bits; a binary variable needs at least one");
        }
        return bits;
    }

    /** The components of a composite, or a flat solution as its only segment. */
    private static List<? extends Solution<?>> segmentsOf(Solution<?> solution) {
        return solution instanceof CompositeSolution composite ? composite.variables() : List.of(solution);
    }

    /**
     * Checks that every value of a flat vector is finite, as JSON numbers must be: a
     * {@code NaN} or an infinity would be written as a JSON string (or rejected) by the
     * encoder, and no worker could apply it.
     *
     * @param values a flat vector, e.g. from {@link #flatten}
     * @return {@code null} if every value is finite (or not a floating-point number),
     *         otherwise the reason, naming the first offending position
     */
    public static String checkFinite(List<? extends Number> values) {
        for (int i = 0; i < values.size(); i++) {
            Number v = values.get(i);
            if ((v instanceof Double || v instanceof Float) && !Double.isFinite(v.doubleValue())) {
                return "variables[" + i + "] is not finite: " + v;
            }
        }
        return null;
    }

    // ── Vector → solution ─────────────────────────────────────────────────────

    /**
     * Converts a flat vector to the exact element types the destination
     * solution requires, without modifying the solution. Use it to validate a
     * result before touching the master-held solution: if any value is
     * unusable nothing has been written.
     *
     * <p>A value for a bit must be 0 or 1: an integer type exactly, a floating-point one within
     * {@link #INTEGRALITY_TOLERANCE}. It becomes the {@link Integer} {@code 0} or {@code 1}.
     *
     * @param destination the solution whose variables would receive the values
     * @param values      the flat vector, laid out as {@link #flatten(Solution)} produces it
     * @return a new list of the same size whose elements are {@link Integer} or
     *         {@link Double} as each destination variable requires
     * @throws IllegalArgumentException if the vector is {@code null}, the destination's layout
     *                                  cannot be read ({@link #layoutOf}; checked first, so that a
     *                                  nested composite is reported as such rather than as a
     *                                  length mismatch), its length differs from
     *                                  {@link #size(Solution)}, or any value cannot be
     *                                  represented by its destination
     */
    public static List<Number> convert(Solution<?> destination, List<? extends Number> values) {
        if (values == null) throw new IllegalArgumentException("variables is null");
        VectorLayout layout = layoutOf(destination);
        int expected = layout.width();
        if (values.size() != expected) {
            throw new IllegalArgumentException("variables has " + values.size()
                + " values but the solution has " + expected);
        }
        List<Number> out = new ArrayList<>(expected);
        for (VectorLayout.Segment segment : layout.segments()) {
            for (int i = 0; i < segment.width(); i++) {
                int idx = out.size();
                out.add(convertValue(values.get(idx), segment.encoding(), idx));
            }
        }
        return out;
    }

    /**
     * Checks a converted vector against the bounds of the variables it would overwrite: the
     * variables of an {@link IntegerSolution} or a {@link DoubleSolution}, or of such segments of
     * a {@link CompositeSolution}, must lie within {@code [lowerBound, upperBound]}. Solutions
     * of other types declare no bounds and accept any value; a bit has none beyond the 0 or 1
     * {@link #convert} requires.
     *
     * <p>{@link #convert} does not check bounds, because a vector going <em>to</em> a worker is
     * the master's own; use this method for vectors coming back from outside, such as the
     * repaired variables of a Lamarckian result, which would otherwise put values no operator
     * expects into the population, the archive and the final front.
     *
     * @param destination the solution whose variables would receive the values
     * @param converted   the vector as returned by {@link #convert} for {@code destination}
     * @return {@code null} if every value is within its bounds, otherwise the reason, naming the
     *         first offending position, its value and its bounds
     * @throws IllegalArgumentException under the conditions of {@link #layoutOf}
     */
    public static String checkBounds(Solution<?> destination, List<? extends Number> converted) {
        VectorLayout layout = layoutOf(destination);
        List<? extends Solution<?>> components = segmentsOf(destination);
        int offset = 0;
        for (int k = 0; k < components.size(); k++) {
            String reason = checkBounds(components.get(k), converted, offset);
            if (reason != null) return reason;
            offset += layout.segments().get(k).width();
        }
        return null;
    }

    /**
     * {@link #checkBounds(Solution, List)} for one flat solution or segment.
     *
     * @param solution  the flat solution or segment
     * @param converted the whole converted vector
     * @param offset    position of the segment's first variable in {@code converted}
     * @return {@code null} if within bounds, otherwise the reason
     */
    private static String checkBounds(Solution<?> solution, List<? extends Number> converted, int offset) {
        if (!(solution instanceof IntegerSolution) && !(solution instanceof DoubleSolution)) {
            return null;  // no bounds declared
        }
        int n = solution.variables().size();
        for (int i = 0; i < n; i++) {
            double value = converted.get(offset + i).doubleValue();
            Number lower;
            Number upper;
            if (solution instanceof IntegerSolution integer) {
                lower = integer.getBounds(i).getLowerBound();
                upper = integer.getBounds(i).getUpperBound();
            } else {
                DoubleSolution real = (DoubleSolution) solution;
                lower = real.getBounds(i).getLowerBound();
                upper = real.getBounds(i).getUpperBound();
            }
            if (value < lower.doubleValue() || value > upper.doubleValue()) {
                return "variables[" + (offset + i) + "] = " + converted.get(offset + i)
                    + " is outside the bounds [" + lower + ", " + upper + "] of its variable";
            }
        }
        return null;
    }

    /**
     * Writes a flat vector into the solution's variables, splitting by component
     * for a {@link CompositeSolution} and converting every value to the type of
     * its destination variable (see the class description). Each binary variable is replaced
     * by a new {@link BinarySet} of its length holding the bits of the vector, so a set that an
     * operator may share with another solution is never written into.
     *
     * <p>All values are converted before the first write, so a rejected vector
     * leaves the solution untouched.
     *
     * @param destination the solution to overwrite
     * @param values      the flat vector, laid out as {@link #flatten(Solution)} produces it
     * @throws IllegalArgumentException under the same conditions as {@link #convert}
     */
    public static void apply(Solution<?> destination, List<? extends Number> values) {
        List<Number> converted = convert(destination, values);
        VectorLayout layout = layoutOf(destination);
        List<? extends Solution<?>> components = segmentsOf(destination);
        int idx = 0;
        for (int k = 0; k < components.size(); k++) {
            VectorLayout.Segment segment = layout.segments().get(k);
            idx = switch (segment.encoding()) {
                case INT, DOUBLE -> writeValues(components.get(k), converted, idx);
                case BINARY -> writeBits(components.get(k), segment.bitsPerVariable(), converted, idx);
            };
        }
    }

    /**
     * Writes converted integer or real values into the variables of a flat solution or segment.
     *
     * @return the position of the next segment's first value
     */
    @SuppressWarnings("unchecked")
    private static int writeValues(Solution<?> solution, List<Number> converted, int idx) {
        List<Object> target = (List<Object>) solution.variables();
        for (int i = 0; i < target.size(); i++, idx++) target.set(i, converted.get(idx));
        return idx;
    }

    /**
     * Replaces each variable of a binary solution or segment by a new {@link BinarySet} of its
     * length holding the converted bits.
     *
     * @return the position of the next segment's first value
     */
    @SuppressWarnings("unchecked")
    private static int writeBits(Solution<?> solution, List<Integer> bitsPerVariable, List<Number> converted, int idx) {
        List<Object> target = (List<Object>) solution.variables();
        for (int i = 0; i < target.size(); i++) {
            BinarySet bits = new BinarySet(bitsPerVariable.get(i));
            for (int bit = 0; bit < bitsPerVariable.get(i); bit++, idx++) {
                if (converted.get(idx).intValue() == 1) bits.set(bit);
            }
            target.set(i, bits);
        }
        return idx;
    }

    /**
     * Converts one value to the representation required by {@code target}.
     *
     * @param value  the incoming value (any {@link Number} Jackson may produce)
     * @param target the destination encoding
     * @param index  position in the flat vector, for error messages
     * @return an {@link Integer} or a {@link Double}; a bit is the {@link Integer} 0 or 1
     * @throws IllegalArgumentException if the value is unusable for {@code target}
     */
    static Number convertValue(Number value, Encoding target, int index) {
        if (value == null) throw new IllegalArgumentException("variables[" + index + "] is null");
        return switch (target) {
            case DOUBLE -> toDouble(value, index);
            case INT -> toInt(value, index);
            case BINARY -> toBit(value, index);
        };
    }

    private static Double toDouble(Number value, int index) {
        double d = value.doubleValue();
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("variables[" + index + "] is not finite: " + value);
        }
        return d;
    }

    private static Integer toInt(Number value, int index) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return value.intValue();
        }
        if (value instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("variables[" + index + "] overflows int: " + value);
            }
            return l.intValue();
        }
        if (value instanceof BigInteger bi) {
            try {
                return bi.intValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("variables[" + index + "] overflows int: " + value);
            }
        }
        double d = (value instanceof BigDecimal bd) ? bd.doubleValue() : value.doubleValue();
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("variables[" + index + "] is not finite: " + value);
        }
        double r = Math.rint(d);
        if (Math.abs(d - r) > INTEGRALITY_TOLERANCE) {
            throw new IllegalArgumentException("variables[" + index + "] = " + value
                + " is not an integer but the destination variable is integer-encoded");
        }
        if (r < Integer.MIN_VALUE || r > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("variables[" + index + "] overflows int: " + value);
        }
        return (int) r;
    }

    /**
     * A bit: an integer type must hold exactly 0 or 1, a floating-point one must be finite and
     * within {@link #INTEGRALITY_TOLERANCE} of 0 or 1.
     */
    private static Integer toBit(Number value, int index) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long bit = value.longValue();
            if (bit == 0 || bit == 1) return (int) bit;
            throw notABit(value, index);
        }
        if (value instanceof BigInteger bi) {
            if (bi.equals(BigInteger.ZERO) || bi.equals(BigInteger.ONE)) return bi.intValue();
            throw notABit(value, index);
        }
        double d = (value instanceof BigDecimal bd) ? bd.doubleValue() : value.doubleValue();
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("variables[" + index + "] is not finite: " + value);
        }
        if (Math.abs(d) <= INTEGRALITY_TOLERANCE) return 0;
        if (Math.abs(d - 1) <= INTEGRALITY_TOLERANCE) return 1;
        throw notABit(value, index);
    }

    private static IllegalArgumentException notABit(Number value, int index) {
        return new IllegalArgumentException("variables[" + index + "] = " + value
            + " is not a bit (0 or 1) but its variable is binary");
    }

    /**
     * Wire names of a list of encodings, or {@code null} for {@code null} input.
     *
     * @param encodings per-segment encodings as returned by {@link #segmentEncodings}
     * @return the corresponding wire names, or {@code null}
     */
    public static List<String> wireNames(List<Encoding> encodings) {
        if (encodings == null) return null;
        if (encodings.isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>(encodings.size());
        for (Encoding e : encodings) names.add(e.wireName());
        return names;
    }
}

package es.unex.jdisrest.util;

import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single place where decision variables cross the boundary between a jMetal
 * {@link Solution} and a flat numeric vector (REST payloads, the local Python
 * protocol, duplicate-detection keys, trace files).
 *
 * <p>Supported solution types: {@link IntegerSolution}, {@link DoubleSolution}
 * and {@link CompositeSolution} whose components are any mix of the two. A
 * solution that is none of these is accepted when its variables are
 * {@link Integer} or {@link Double} at runtime (e.g. an integer permutation);
 * anything else is rejected with an {@link IllegalArgumentException}.
 *
 * <p>Writing values back never trusts the numeric type that arrived on the
 * wire: each value is converted to the type the <em>destination</em> variable
 * requires ({@link Number#intValue()} for integer encodings,
 * {@link Number#doubleValue()} for real encodings). A JSON {@code 5} bound by
 * Jackson to an {@link Integer} therefore lands correctly in a
 * {@code DoubleSolution}, and a {@code 5.0} bound to a {@link Double} lands
 * correctly in an {@code IntegerSolution}. Values that cannot be represented by
 * the destination (non-finite doubles, non-integral values for an integer
 * variable, integer overflow, {@code null}) are rejected — never truncated or
 * ignored silently.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class SolutionVariables {

    private SolutionVariables() {}

    /**
     * Tolerance used when an integer-encoded variable arrives as a floating-point
     * number. {@code 3.0000000000001} is accepted as {@code 3}; {@code 2.7} is
     * rejected.
     */
    public static final double INTEGRALITY_TOLERANCE = 1e-9;

    /** Variable encoding of a flat (non-composite) solution, with its wire name. */
    public enum Encoding {
        INT("int"),
        DOUBLE("double");

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

    // ── Type inspection ───────────────────────────────────────────────────────

    /**
     * Returns the encoding of a flat (non-composite) solution.
     *
     * <p>Decided by class first ({@link IntegerSolution} → {@link Encoding#INT},
     * {@link DoubleSolution} → {@link Encoding#DOUBLE}); otherwise by the runtime
     * type of the first variable ({@link Integer} or {@link Double}).
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
        List<?> vars = solution.variables();
        if (!vars.isEmpty()) {
            Object first = vars.get(0);
            if (first instanceof Integer) return Encoding.INT;
            if (first instanceof Double) return Encoding.DOUBLE;
        }
        throw new IllegalArgumentException("Unsupported solution type " + solution.getClass().getName()
            + ": only IntegerSolution, DoubleSolution and CompositeSolution of those are supported");
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
     * Wire name describing the whole solution: {@code "int"} or {@code "double"}
     * for flat solutions and homogeneous composites, {@link #MIXED} for a
     * composite whose segments differ.
     *
     * @param solution the solution to inspect
     * @return {@code "int"}, {@code "double"} or {@code "mixed"}
     * @throws IllegalArgumentException if the solution (or one component) is unsupported
     */
    public static String wireEncoding(Solution<?> solution) {
        List<Encoding> segments = segmentEncodings(solution);
        if (segments == null) return encodingOf(solution).wireName();
        if (segments.isEmpty()) return Encoding.INT.wireName();
        Encoding first = segments.get(0);
        for (Encoding e : segments) {
            if (e != first) return MIXED;
        }
        return first.wireName();
    }

    /**
     * Total number of scalar variables: the size of {@code variables()} for a
     * flat solution, the sum of the component sizes for a composite.
     *
     * @param solution the solution to measure
     * @return number of scalar decision variables
     */
    public static int size(Solution<?> solution) {
        if (solution instanceof CompositeSolution composite) {
            int n = 0;
            for (Solution<?> component : composite.variables()) n += component.variables().size();
            return n;
        }
        return solution.variables().size();
    }

    // ── Solution → vector ─────────────────────────────────────────────────────

    /**
     * Copies the decision variables into a new flat list. For a composite the
     * components are concatenated in declaration order: {@code [seg0 | seg1 | ...]}.
     * Element types are preserved ({@link Integer} for integer segments,
     * {@link Double} for real ones), so Jackson serializes integers as
     * {@code 5} and reals as {@code 5.0}.
     *
     * <p>The result is always a fresh, mutable list: callers may store it (e.g.
     * as a {@code HashSet} key) without aliasing the solution's live list.
     *
     * @param solution the solution to read
     * @return a new list with all scalar variables
     * @throws IllegalArgumentException if the solution (or one component) is unsupported
     */
    public static List<Number> flatten(Solution<?> solution) {
        List<Number> flat = new ArrayList<>(size(solution));
        if (solution instanceof CompositeSolution composite) {
            for (Solution<?> component : composite.variables()) {
                encodingOf(component); // validates the component type
                for (Object v : component.variables()) flat.add((Number) v);
            }
            return flat;
        }
        encodingOf(solution); // validates the type
        for (Object v : solution.variables()) flat.add((Number) v);
        return flat;
    }

    // ── Vector → solution ─────────────────────────────────────────────────────

    /**
     * Converts a flat vector to the exact element types the destination
     * solution requires, without modifying the solution. Use it to validate a
     * result before touching the master-held solution: if any value is
     * unusable nothing has been written.
     *
     * @param destination the solution whose variables would receive the values
     * @param values      the flat vector, laid out as {@link #flatten(Solution)} produces it
     * @return a new list of the same size whose elements are {@link Integer} or
     *         {@link Double} as each destination variable requires
     * @throws IllegalArgumentException if the vector is {@code null}, its length
     *                                  differs from {@link #size(Solution)}, or any
     *                                  value cannot be represented by its destination
     */
    public static List<Number> convert(Solution<?> destination, List<? extends Number> values) {
        if (values == null) throw new IllegalArgumentException("variables is null");
        int expected = size(destination);
        if (values.size() != expected) {
            throw new IllegalArgumentException("variables has " + values.size()
                + " values but the solution has " + expected);
        }
        List<Number> out = new ArrayList<>(expected);
        int idx = 0;
        if (destination instanceof CompositeSolution composite) {
            for (Solution<?> component : composite.variables()) {
                Encoding enc = encodingOf(component);
                int n = component.variables().size();
                for (int i = 0; i < n; i++, idx++) out.add(convertValue(values.get(idx), enc, idx));
            }
            return out;
        }
        Encoding enc = encodingOf(destination);
        for (; idx < expected; idx++) out.add(convertValue(values.get(idx), enc, idx));
        return out;
    }

    /**
     * Writes a flat vector into the solution's variables, splitting by component
     * for a {@link CompositeSolution} and converting every value to the type of
     * its destination variable (see the class description).
     *
     * <p>All values are converted before the first write, so a rejected vector
     * leaves the solution untouched.
     *
     * @param destination the solution to overwrite
     * @param values      the flat vector, laid out as {@link #flatten(Solution)} produces it
     * @throws IllegalArgumentException under the same conditions as {@link #convert}
     */
    @SuppressWarnings("unchecked")
    public static void apply(Solution<?> destination, List<? extends Number> values) {
        List<Number> converted = convert(destination, values);
        int idx = 0;
        if (destination instanceof CompositeSolution composite) {
            for (Solution<?> component : composite.variables()) {
                List<Object> target = (List<Object>) component.variables();
                for (int i = 0; i < target.size(); i++, idx++) target.set(i, converted.get(idx));
            }
            return;
        }
        List<Object> target = (List<Object>) destination.variables();
        for (int i = 0; i < target.size(); i++, idx++) target.set(i, converted.get(idx));
    }

    /**
     * Converts one value to the representation required by {@code target}.
     *
     * @param value  the incoming value (any {@link Number} Jackson may produce)
     * @param target the destination encoding
     * @param index  position in the flat vector, for error messages
     * @return an {@link Integer} or a {@link Double}
     * @throws IllegalArgumentException if the value is unusable for {@code target}
     */
    static Number convertValue(Number value, Encoding target, int index) {
        if (value == null) throw new IllegalArgumentException("variables[" + index + "] is null");
        switch (target) {
            case DOUBLE: {
                double d = value.doubleValue();
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("variables[" + index + "] is not finite: " + value);
                }
                return d;
            }
            case INT:
            default: {
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
        }
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

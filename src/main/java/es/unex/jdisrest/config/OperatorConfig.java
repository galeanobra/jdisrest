package es.unex.jdisrest.config;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An operator chosen in a configuration file: its type, its application probability and a value
 * for each of its parameters.
 *
 * <p>The parser builds these records only from values that passed every check of the operator
 * type, so {@link #create()} succeeds for them; a record built directly skips that validation.
 *
 * @param type        the operator
 * @param probability application probability, in [0, 1]
 * @param parameters  a value for every parameter of the operator, in the order of
 *                    {@link OperatorType#parameters()}
 * @param <O>         the operator class
 * @author Francisco Luna (Universidad de Málaga)
 */
public record OperatorConfig<O>(OperatorType<O> type, double probability, Map<String, Double> parameters) {

    /** Significant digits of a long value in {@link #describe()} and in messages. */
    private static final int SIGNIFICANT_DIGITS = 4;

    /** Values with at most this many significant digits, as people type them, are shown exactly. */
    private static final int EXACT_DIGITS = 6;

    /**
     * Builds a new operator with this configuration. Every call returns a new instance, so a run
     * and a later reconfiguration never share one.
     *
     * @return the operator
     */
    public O create() {
        return type.create(probability, parameters);
    }

    /** For instance {@code sbx (probability 0.9, distributionIndex 20)}. */
    String describe() {
        String values = parameters.entrySet().stream()
                .map(entry -> ", " + entry.getKey() + " " + format(entry.getValue()))
                .collect(Collectors.joining());
        return type.key() + " (probability " + format(probability) + values + ")";
    }

    /**
     * A value for summaries and messages that never looks like a different value.
     *
     * <p>A value with at most {@value #EXACT_DIGITS} significant digits, which covers whatever a
     * person types, is written in full: 0.9, 20, 1.99999, 3.00001. A longer one, such as the
     * 1/30 that {@code 1/n} gives for 30 variables, is rounded to {@value #SIGNIFICANT_DIGITS}
     * significant digits (0.03333), unless the rounding would make it look like a rounder number
     * (1.9999999 would read 2, 12345.678 would read 12350): then it is written in full too. "In
     * full" is the shortest decimal that reads back as the same {@code double}. Rounding blindly
     * would print a rejected {@code blockSize = 3.00001} as {@code got '3'}, or log
     * {@code beta = 1.99999} as the forbidden {@code beta 2}.
     *
     * @param value a finite number
     * @return the value in plain notation, without trailing zeros
     */
    static String format(double value) {
        BigDecimal exact = BigDecimal.valueOf(value).stripTrailingZeros();
        BigDecimal shown = exact;
        if (exact.precision() > EXACT_DIGITS) {
            BigDecimal rounded = exact.round(new MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros();
            if (rounded.precision() == SIGNIFICANT_DIGITS && rounded.scale() > 0) {
                shown = rounded;
            }
        }
        return shown.toPlainString();
    }
}

package es.unex.jdisrest.config;

import es.unex.jdisrest.config.SolutionLayout.Segment;
import es.unex.jdisrest.operator.SafeCompositeCrossover;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.CompositeMutation;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The variation operators of a run: a crossover (none in PAES) and a mutation for each segment of
 * the solutions (see {@link SolutionLayout}). A problem that is not composite has a single segment,
 * whose operators are used as they are; a composite one gets composite operators, which apply the
 * operators of each segment to its component of the solution: jdisrest's
 * {@link SafeCompositeCrossover} and jMetal's {@link CompositeMutation}.
 *
 * <p>The parser builds a variation from operators that passed every check for their segment, so
 * {@link #createCrossover()} and {@link #createMutation()} succeed for it; one built directly skips
 * that validation, and nothing checks that its operators fit the encoding of their segments.
 *
 * @param segments the operators of each segment, in the order of the layout
 * @author Francisco Luna (Universidad de Málaga)
 */
public record Variation(List<SegmentOperators> segments) {

    /**
     * The operators of one segment.
     *
     * @param segment   the segment
     * @param crossover its crossover, or {@code null} when the algorithm does not cross (PAES)
     * @param mutation  its mutation
     */
    public record SegmentOperators(Segment segment, OperatorConfig<? extends CrossoverOperator<?>> crossover,
            OperatorConfig<? extends MutationOperator<?>> mutation) {

        /** @throws NullPointerException if {@code segment} or {@code mutation} is {@code null} */
        public SegmentOperators {
            Objects.requireNonNull(segment, "segment must not be null");
            Objects.requireNonNull(mutation, "mutation must not be null");
        }

        /** For instance {@code crossover sbx (probability 0.9, distributionIndex 20), mutation polynomial (...)}. */
        private String describe() {
            return (crossover == null ? "" : "crossover " + crossover.describe() + ", ")
                    + "mutation " + mutation.describe();
        }
    }

    /**
     * @throws NullPointerException     if the list or one of its elements is {@code null}
     * @throws IllegalArgumentException if there are no segments, or their segments do not form a
     *                                  {@link SolutionLayout} (several without a name each, or
     *                                  with a name in common)
     */
    public Variation {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("the variation needs the operators of at least one segment");
        }
        new SolutionLayout(segments.stream().map(SegmentOperators::segment).toList());
    }

    /**
     * The configuration of a problem that is not composite with real variables, as the records of
     * 1.2 held it: a crossover, {@code null} for PAES, and a mutation of {@link DoubleSolution}s.
     * The number of variables they were chosen for is not known, so the segment has a size of 1.
     */
    static Variation real(OperatorConfig<CrossoverOperator<DoubleSolution>> crossover,
            OperatorConfig<MutationOperator<DoubleSolution>> mutation) {
        return new Variation(List.of(new SegmentOperators(new Segment(null, Encoding.DOUBLE, 1), crossover, mutation)));
    }

    /**
     * The layout the operators were read for: the segments, in order.
     *
     * @return the layout
     */
    public SolutionLayout layout() {
        return new SolutionLayout(segments.stream().map(SegmentOperators::segment).toList());
    }

    /**
     * Whether the solutions are composite, so the operators are those of several segments: whether
     * the segments are named.
     *
     * @return {@code true} for the operators of a composite layout
     */
    public boolean composite() {
        return segments.getFirst().segment().name() != null;
    }

    /**
     * The operators of the single segment of a problem that is not composite.
     *
     * @return the operators
     * @throws IllegalStateException if the problem is composite
     */
    public SegmentOperators single() {
        if (composite()) {
            throw new IllegalStateException("a composite problem has the operators of each segment: " + describe());
        }
        return segments.getFirst();
    }

    /**
     * Builds the crossover: that of the single segment, or a {@link SafeCompositeCrossover} of the
     * crossovers of every segment, each built anew.
     *
     * @param <S> the solutions, which must match the layout the configuration was read for: the
     *            caller picks them, and nothing checks the choice
     * @return a new crossover
     * @throws IllegalStateException if the operators have no crossover (PAES)
     */
    @SuppressWarnings("unchecked")
    public <S extends Solution<?>> CrossoverOperator<S> createCrossover() {
        if (segments.stream().anyMatch(operators -> operators.crossover() == null)) {
            throw new IllegalStateException("the configuration has no crossover");
        }
        Object crossover = composite()
                ? new SafeCompositeCrossover(segments.stream().map(operators -> operators.crossover().create()).toList())
                : single().crossover().create();
        return (CrossoverOperator<S>) crossover;
    }

    /**
     * Builds the mutation: that of the single segment, or a jMetal {@link CompositeMutation} of the
     * mutations of every segment, each built anew.
     *
     * @param <S> the solutions, which must match the layout the configuration was read for: the
     *            caller picks them, and nothing checks the choice
     * @return a new mutation
     */
    @SuppressWarnings("unchecked")
    public <S extends Solution<?>> MutationOperator<S> createMutation() {
        Object mutation = composite()
                ? new CompositeMutation(segments.stream().map(operators -> operators.mutation().create()).toList())
                : single().mutation().create();
        return (MutationOperator<S>) mutation;
    }

    /**
     * The operators of every segment, for {@code describe()}.
     *
     * @return for a problem that is not composite, {@code crossover sbx (probability 0.9,
     *         distributionIndex 20), mutation polynomial (...)}, as in 1.2; for a composite one,
     *         {@code integer [crossover sbx (...), mutation polynomial (...)], real [...]}
     */
    public String describe() {
        return composite()
                ? segments.stream().map(operators -> operators.segment().name() + " [" + operators.describe() + "]")
                        .collect(Collectors.joining(", "))
                : single().describe();
    }

    // ── The accessors of 1.2 ──────────────────────────────────────────────────

    /**
     * The crossover of a real-coded problem that is not composite, typed as the records of 1.2
     * returned it.
     *
     * @throws IllegalStateException if the problem is composite or its variables are not real
     */
    @SuppressWarnings("unchecked")
    OperatorConfig<CrossoverOperator<DoubleSolution>> realCrossover() {
        return (OperatorConfig<CrossoverOperator<DoubleSolution>>) realSegment().crossover();
    }

    /**
     * The mutation of a real-coded problem that is not composite, typed as the records of 1.2
     * returned it.
     *
     * @throws IllegalStateException if the problem is composite or its variables are not real
     */
    @SuppressWarnings("unchecked")
    OperatorConfig<MutationOperator<DoubleSolution>> realMutation() {
        return (OperatorConfig<MutationOperator<DoubleSolution>>) realSegment().mutation();
    }

    private SegmentOperators realSegment() {
        SegmentOperators single = single();
        if (single.segment().encoding() != Encoding.DOUBLE) {
            throw new IllegalStateException("the operators are those of " + SolutionLayout.encodingName(
                    single.segment().encoding()) + " variables, not of real ones: use variation()");
        }
        return single;
    }
}

package es.unex.jdisrest.config;

import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How the values of a problem's solutions are grouped for the configuration files: a single
 * unnamed segment for a flat solution ({@link DoubleSolution}, {@link IntegerSolution} or
 * {@link BinarySolution}), or one named segment for each component of a
 * {@link CompositeSolution}, in their order. Each segment has its own operators, taken from the
 * catalogue of its encoding (see {@link AlgorithmConfig}).
 *
 * <p>A file gives the operators of the single segment of a flat problem with the keys
 * {@code crossover} and {@code mutation}, as for a real-coded problem, and those of each named
 * segment with the name as prefix, for instance {@code ints.mutation} and
 * {@code ints.mutation.probability}. In the probability of an operator, {@code k/n} is k over the
 * size of its segment; in the other probabilities, k over {@link #numberOfVariables()}.
 *
 * <h2>Sizes</h2>
 * <p>The size of a segment is the number of values its operators change: its variables, or the
 * bits of all its variables for a binary segment, since jMetal's bit-flip mutation applies its
 * probability to each bit. It is the width {@link SolutionVariables} gives the segment in the flat
 * vector. jMetal's {@code Problem.numberOfVariables()} counts something else for these problems:
 * the variables of a binary problem, whatever their bits, and the components of a composite one.
 *
 * <h2>Names</h2>
 * <p>{@link #of} names the segments of a composite with {@link NamedSegments#segmentNames()} when
 * the problem implements it, and otherwise after their encodings: {@code real}, {@code integer}
 * and {@code binary}, numbered from 1 when an encoding repeats ({@code integer1}, {@code real},
 * {@code integer2}). A name starts with a letter and holds only letters, digits and underscores,
 * so that it can prefix a key in a properties file.
 *
 * @param segments the segments, at least one: a single unnamed one for a flat problem, or one named
 *                 segment per component, with distinct names, for a composite one
 * @author Francisco Luna (Universidad de Málaga)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record SolutionLayout(List<Segment> segments) {

    /** A segment name: a letter, then letters, digits and underscores. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private static final String NOT_COMPOSITE = "it implements NamedSegments, but its solutions are not composite";

    /**
     * A group of consecutive values that the same operators change: a flat solution, or one
     * component of a composite.
     *
     * @param name     the prefix of its keys in the configuration file, or {@code null} for the
     *                 single segment of a problem that is not composite
     * @param encoding the encoding of its variables, which decides the catalogue of its operators
     * @param size     the number of values its operators change, at least 1: its variables, or its
     *                 bits for a binary segment
     */
    public record Segment(String name, Encoding encoding, int size) {

        /**
         * @throws NullPointerException     if {@code encoding} is {@code null}
         * @throws IllegalArgumentException if the size is smaller than 1 or the name cannot prefix
         *                                  a key
         */
        public Segment {
            Objects.requireNonNull(encoding, "a segment needs an encoding");
            if (size < 1) {
                throw new IllegalArgumentException("a segment needs a size of at least 1, got " + size);
            }
            if (name != null && !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("the segment name '" + name
                        + "' must start with a letter and hold only letters, digits and underscores");
            }
        }

        /**
         * The key of a setting of this segment.
         *
         * @param key the key of a flat problem, for instance {@code mutation.probability}
         * @return {@code key} itself for an unnamed segment, {@code <name>.key} for a named one
         */
        public String key(String key) {
            return name == null ? key : name + "." + key;
        }
    }

    /**
     * @throws NullPointerException     if the list or one of its segments is {@code null}
     * @throws IllegalArgumentException if there is no segment, or several of them without a name
     *                                  each or with a name in common
     */
    public SolutionLayout {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a layout needs at least one segment");
        }
        if (segments.size() > 1) {
            Set<String> names = new HashSet<>();
            for (Segment segment : segments) {
                if (segment.name() == null || !names.add(segment.name())) {
                    throw new IllegalArgumentException("the segments of a composite layout need distinct names, got "
                            + segments.stream().map(Segment::name).toList());
                }
            }
        }
    }

    /**
     * The layout of a {@link DoubleSolution} with that many variables: one unnamed real segment,
     * the layout of every real-coded problem.
     *
     * @param numberOfVariables the number of variables, at least 1
     * @return the layout
     * @throws IllegalArgumentException if {@code numberOfVariables} is smaller than 1
     */
    public static SolutionLayout real(int numberOfVariables) {
        return new SolutionLayout(List.of(new Segment(null, Encoding.DOUBLE, numberOfVariables)));
    }

    /**
     * The layout of a problem's solutions, for its configuration files.
     *
     * <p>A {@link DoubleProblem} gives {@link #real}{@code (problem.numberOfVariables())} without
     * creating a solution, so that reading a configuration for a real-coded problem draws no
     * random number, as in 1.2. Any other problem creates one solution with
     * {@link Problem#createSolution()}, which draws the random numbers of its initial values, and
     * the layout is read from it: one unnamed segment for a {@link DoubleSolution},
     * {@link IntegerSolution} or {@link BinarySolution}, and one named segment per component of a
     * {@link CompositeSolution} of those (see the class description for the names). The catalogue
     * operators are built for those types, so a solution of another type is rejected, even one
     * that {@link SolutionVariables} can send to the workers.
     *
     * @param problem the problem
     * @return the layout of its solutions
     * @throws NullPointerException     if {@code problem} is {@code null}
     * @throws IllegalArgumentException if the solution or one of its components is of another
     *                                  type, a composite is nested in it, a segment has no values,
     *                                  a binary variable has no bits, or the names of
     *                                  {@link NamedSegments} do not fit the segments
     */
    public static SolutionLayout of(Problem<?> problem) {
        Objects.requireNonNull(problem, "problem must not be null");
        if (problem instanceof DoubleProblem real) {
            if (problem instanceof NamedSegments) {
                throw cannotConfigure(problem, NOT_COMPOSITE);
            }
            if (real.numberOfVariables() < 1) {
                throw cannotConfigure(problem, "its solutions have no variables");
            }
            return real(real.numberOfVariables());
        }
        Object solution = problem.createSolution();
        if (!(solution instanceof Solution<?> created)) {
            throw cannotConfigure(problem, "its createSolution() returns "
                    + (solution == null ? "null" : "a " + solution.getClass().getName()) + ", not a Solution");
        }
        return of(problem, created);
    }

    /** The layout of a solution of the problem, as {@link #of(Problem)} describes it. */
    static SolutionLayout of(Problem<?> problem, Solution<?> solution) {
        boolean composite = solution instanceof CompositeSolution;
        if (!composite && problem instanceof NamedSegments) {
            throw cannotConfigure(problem, NOT_COMPOSITE);
        }
        List<? extends Solution<?>> components = composite ? ((CompositeSolution) solution).variables() : List.of(solution);
        List<Encoding> encodings = new ArrayList<>(components.size());
        for (int k = 0; k < components.size(); k++) {
            Solution<?> component = components.get(k);
            Encoding encoding = component instanceof DoubleSolution ? Encoding.DOUBLE
                    : component instanceof IntegerSolution ? Encoding.INT
                    : component instanceof BinarySolution ? Encoding.BINARY
                    : null;
            if (encoding == null) {
                throw cannotConfigure(problem, (composite ? "segment " + k + " of its solutions is " : "its solutions are ")
                        + (component == null ? "null" : component.getClass().getName())
                        + ", where the configuration files take a DoubleSolution, an IntegerSolution, a BinarySolution"
                        + (composite ? "" : " or a CompositeSolution of those"));
            }
            encodings.add(encoding);
        }
        if (composite && components.isEmpty()) {
            throw cannotConfigure(problem, "its solutions are composites of no segment");
        }
        List<String> names = composite ? names(problem, encodings) : Collections.singletonList(null);

        SolutionVariables.VectorLayout vector;
        try {
            vector = SolutionVariables.layoutOf(solution);
        } catch (IllegalArgumentException e) {
            throw cannotConfigure(problem, e.getMessage(), e);
        }
        List<Segment> segments = new ArrayList<>(components.size());
        for (int k = 0; k < components.size(); k++) {
            int width = vector.segments().get(k).width();
            if (width == 0) {
                throw cannotConfigure(problem, (composite ? "segment " + k + " of its solutions has" : "its solutions have")
                        + " no variables");
            }
            try {
                segments.add(new Segment(names.get(k), encodings.get(k), width));
            } catch (IllegalArgumentException e) {
                throw cannotConfigure(problem, e.getMessage(), e);
            }
        }
        try {
            return new SolutionLayout(segments);
        } catch (IllegalArgumentException e) {
            throw cannotConfigure(problem, e.getMessage(), e);
        }
    }

    /** The names of the segments of a composite: those of {@link NamedSegments}, or the defaults. */
    private static List<String> names(Problem<?> problem, List<Encoding> encodings) {
        if (problem instanceof NamedSegments named) {
            List<String> names = named.segmentNames();
            if (names == null || names.size() != encodings.size()) {
                throw cannotConfigure(problem, "NamedSegments.segmentNames() gives "
                        + (names == null ? "null" : names.size() + (names.size() == 1 ? " name " : " names ") + names)
                        + " for " + encodings.size() + " segments");
            }
            for (String name : names) {
                if (name == null) {
                    throw cannotConfigure(problem, "NamedSegments.segmentNames() gives a null name: " + names);
                }
            }
            return names;
        }
        Map<Encoding, Integer> repeats = new EnumMap<>(Encoding.class);
        encodings.forEach(encoding -> repeats.merge(encoding, 1, Integer::sum));
        Map<Encoding, Integer> numbers = new EnumMap<>(Encoding.class);
        List<String> names = new ArrayList<>(encodings.size());
        for (Encoding encoding : encodings) {
            String name = encodingName(encoding);
            names.add(repeats.get(encoding) == 1 ? name : name + numbers.merge(encoding, 1, Integer::sum));
        }
        return names;
    }

    private static IllegalArgumentException cannotConfigure(Problem<?> problem, String reason) {
        return cannotConfigure(problem, reason, null);
    }

    private static IllegalArgumentException cannotConfigure(Problem<?> problem, String reason, Throwable cause) {
        return new IllegalArgumentException("cannot configure the operators of " + problem.name() + ": " + reason, cause);
    }

    /**
     * The values of all the segments, which {@code k/n} counts in the probabilities that are not
     * of an operator: the number of variables of a real or integer problem, its bits for a binary
     * one, and the sum over the segments of a composite.
     *
     * @return the sum of the segment sizes
     */
    public int numberOfVariables() {
        return segments.stream().mapToInt(Segment::size).sum();
    }

    /**
     * Whether the solutions are {@link CompositeSolution}s, one component per segment: whether the
     * segments are named.
     *
     * @return {@code true} for a composite layout
     */
    public boolean composite() {
        return segments.getFirst().name() != null;
    }

    /**
     * Whether the flat vector of a solution, as {@link SolutionVariables#layoutOf} reads it, has
     * this shape: composite or not as this layout, with a segment of the same encoding and size
     * for each segment here, in the same order. Names are not compared. A layout that does not
     * match describes operators that do not fit the solutions, for instance a crossover of
     * {@code DoubleSolution}s for a segment of {@code IntegerSolution}s.
     *
     * @param layout the layout of a solution
     * @return {@code true} if the operators of a configuration read for this layout fit the
     *         solution
     */
    public boolean matches(SolutionVariables.VectorLayout layout) {
        return hasShape(layout.composite(), layout.segments().stream().map(SolutionVariables.VectorLayout.Segment::encoding).toList(),
                layout.segments().stream().map(SolutionVariables.VectorLayout.Segment::width).toList());
    }

    /**
     * Why a configuration read for this layout cannot drive the operators of the problem's
     * solutions, or {@code null} if it can: the layout of the problem ({@link #of(Problem)}) must
     * have the same shape, names aside.
     *
     * @throws IllegalArgumentException if the problem's solutions cannot be configured at all
     */
    String mismatch(Problem<?> problem) {
        SolutionLayout actual = of(problem);
        return hasShape(actual.composite(), actual.segments.stream().map(Segment::encoding).toList(),
                actual.segments.stream().map(Segment::size).toList())
                ? null
                : "the configuration was read for " + shape() + ", but the solutions of " + problem.name() + " are "
                        + actual.shape();
    }

    /** Whether this layout is composite or not as given, with these encodings and sizes in order. */
    private boolean hasShape(boolean composite, List<Encoding> encodings, List<Integer> sizes) {
        return composite == composite()
                && encodings.equals(segments.stream().map(Segment::encoding).toList())
                && sizes.equals(segments.stream().map(Segment::size).toList());
    }

    /** For instance {@code real (30 variables)} or {@code a composite of integer (3 variables), binary (8 bits)}. */
    String shape() {
        String shape = segments.stream()
                .map(segment -> encodingName(segment.encoding()) + " (" + values(segment) + ")")
                .collect(Collectors.joining(", "));
        return composite() ? "a composite of " + shape : shape;
    }

    /** For instance {@code 3 variables} or {@code 1 bit}. */
    static String values(Segment segment) {
        String unit = segment.encoding() == Encoding.BINARY ? "bit" : "variable";
        return segment.size() + " " + unit + (segment.size() == 1 ? "" : "s");
    }

    /**
     * The name of an encoding in the configuration files, and the default name of its segments:
     * {@code real}, {@code integer} or {@code binary}.
     */
    static String encodingName(Encoding encoding) {
        return switch (encoding) {
            case DOUBLE -> "real";
            case INT -> "integer";
            case BINARY -> "binary";
        };
    }
}

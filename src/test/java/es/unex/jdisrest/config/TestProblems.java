package es.unex.jdisrest.config;

import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.binarysolution.impl.DefaultBinarySolution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.solution.integersolution.impl.DefaultIntegerSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Problems for the tests of this package, with the shapes of solution the configuration files
 * take: flat integer and binary ones, and composites whose segments have names of their own or
 * the default ones.
 */
final class TestProblems {

    static final int OBJECTIVES = 2;
    private static final long SEED = 7;

    private TestProblems() {
    }

    /** A problem whose solutions come from a factory; it counts the solutions it creates. */
    static class FromFactory<S extends Solution<?>> implements Problem<S> {
        private final String name;
        private final int objectives;
        private final transient Supplier<S> factory;
        final AtomicInteger created = new AtomicInteger();

        /** A problem of two objectives. */
        FromFactory(String name, Supplier<S> factory) {
            this(name, OBJECTIVES, factory);
        }

        FromFactory(String name, int objectives, Supplier<S> factory) {
            this.name = name;
            this.objectives = objectives;
            this.factory = factory;
        }

        /** As jMetal counts them: the variables of a flat solution, the segments of a composite. */
        @Override public int numberOfVariables() { return factory.get().variables().size(); }
        @Override public int numberOfObjectives() { return objectives; }
        @Override public int numberOfConstraints() { return 0; }
        @Override public String name() { return name; }

        @Override
        public S createSolution() {
            created.incrementAndGet();
            return factory.get();
        }

        @Override
        public S evaluate(S solution) {
            return solution;
        }
    }

    /** A composite problem that names its segments. */
    static final class Named extends FromFactory<CompositeSolution> implements NamedSegments {
        private final List<String> names;

        Named(String name, List<String> names, Supplier<CompositeSolution> factory) {
            super(name, factory);
            this.names = names;
        }

        @Override
        public List<String> segmentNames() {
            return names;
        }
    }

    static IntegerSolution integers(int count, int lower, int upper) {
        return new DefaultIntegerSolution(Collections.nCopies(count, Bounds.create(lower, upper)), OBJECTIVES, 0);
    }

    static DoubleSolution reals(int count, double lower, double upper) {
        return new DefaultDoubleSolution(Collections.nCopies(count, Bounds.create(lower, upper)), OBJECTIVES, 0);
    }

    /** A binary solution with random bits, whose variables have these lengths. */
    static BinarySolution bits(Integer... lengths) {
        return new DefaultBinarySolution(List.of(lengths), OBJECTIVES, 0);
    }

    static CompositeSolution composite(Solution<?>... segments) {
        return new CompositeSolution(List.of(segments));
    }

    /**
     * Mixed: three integer variables in [0, 10], two real ones in [-1, 1] and binary variables of
     * 5 and 3 bits, in segments named {@code ints}, {@code reals} and {@code bits}.
     */
    static Named mixed() {
        return new Named("Mixed", List.of("ints", "reals", "bits"),
                () -> composite(integers(3, 0, 10), reals(2, -1.0, 1.0), bits(5, 3)));
    }

    /** Four integer variables in [0, 10], flat. */
    static FromFactory<IntegerSolution> flatIntegers() {
        return new FromFactory<>("FlatIntegers", () -> integers(4, 0, 10));
    }

    /**
     * Whether an action leaves the random numbers of {@link JMetalRandom} as they were: run from a
     * seed, it must be followed by the first number of that seed. The shared generator is restored.
     */
    static boolean drawsNoRandomNumber(Runnable action) {
        JMetalRandom random = JMetalRandom.getInstance();
        PseudoRandomGenerator shared = random.getRandomGenerator();
        try {
            random.setRandomGenerator(new JavaRandomGenerator(SEED));
            action.run();
            return random.nextDouble() == new JavaRandomGenerator(SEED).nextDouble();
        } finally {
            random.setRandomGenerator(shared);
        }
    }
}

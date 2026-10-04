package es.unex.jdisrest.config;

import es.unex.jdisrest.config.SolutionLayout.Segment;
import es.unex.jdisrest.config.TestProblems.FromFactory;
import es.unex.jdisrest.config.TestProblems.Named;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.SolutionVariables.Encoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.multiobjective.MixedIntegerDoubleProblem;
import org.uma.jmetal.problem.multiobjective.NMMin;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.permutationsolution.impl.IntegerPermutationSolution;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static es.unex.jdisrest.config.TestProblems.bits;
import static es.unex.jdisrest.config.TestProblems.composite;
import static es.unex.jdisrest.config.TestProblems.integers;
import static es.unex.jdisrest.config.TestProblems.mixed;
import static es.unex.jdisrest.config.TestProblems.reals;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Layouts of the configuration files: the layout derived from the solutions of each kind of
 * problem, flat and composite, with default names and with those of {@link NamedSegments}; the
 * random numbers the derivation draws; the problems it rejects; the rules of the records; and the
 * comparison with the layout of a flat vector.
 */
class SolutionLayoutTest {

    // ── Derived layouts ───────────────────────────────────────────────────────

    @Test
    void aRealCodedProblemHasOneUnnamedRealSegmentAndCreatesNoSolution() {
        AtomicInteger created = new AtomicInteger();
        ZDT1 problem = new ZDT1() {
            @Override
            public DoubleSolution createSolution() {
                created.incrementAndGet();
                return super.createSolution();
            }
        };

        SolutionLayout layout = SolutionLayout.of(problem);

        assertAll(
                () -> assertEquals(SolutionLayout.real(30), layout, "the layout of 1.2: 30 real variables"),
                () -> assertEquals(0, created.get(), "a DoubleProblem gives its number of variables"),
                () -> assertTrue(TestProblems.drawsNoRandomNumber(() -> SolutionLayout.of(new ZDT1())),
                        "so a seeded real-coded run draws as in 1.2"));
    }

    @Test
    void aFlatIntegerProblemHasOneUnnamedIntegerSegment() {
        SolutionLayout layout = SolutionLayout.of(new NMMin());

        assertAll(
                () -> assertEquals(List.of(new Segment(null, Encoding.INT, 20)), layout.segments(), "20 integers"),
                () -> assertFalse(layout.composite(), "not composite"),
                () -> assertEquals("mutation.probability", layout.segments().getFirst().key("mutation.probability"),
                        "the keys of a flat problem have no prefix"));
    }

    @Test
    void aFlatBinaryProblemCountsTheBitsOfItsVariables() {
        ZDT5 problem = new ZDT5();

        SolutionLayout layout = SolutionLayout.of(problem);

        assertAll(
                () -> assertEquals(List.of(new Segment(null, Encoding.BINARY, 80)), layout.segments(),
                        "11 variables of 30 and 10 times 5 bits"),
                () -> assertEquals(80, layout.numberOfVariables(), "k/n counts bits"),
                () -> assertEquals(11, problem.numberOfVariables(), "where jMetal counts variables"));
    }

    @Test
    void aCompositeGetsTheNamesOfItsEncodingsByDefault() {
        MixedIntegerDoubleProblem problem = new MixedIntegerDoubleProblem();

        SolutionLayout layout = SolutionLayout.of(problem);

        assertAll(
                () -> assertEquals(List.of(new Segment("integer", Encoding.INT, 10), new Segment("real", Encoding.DOUBLE, 10)),
                        layout.segments(), "10 integers, then 10 reals"),
                () -> assertTrue(layout.composite(), "composite"),
                () -> assertEquals(20, layout.numberOfVariables(), "the values of both segments"),
                () -> assertEquals(2, problem.numberOfVariables(), "where jMetal counts the segments"),
                () -> assertEquals("integer.mutation", layout.segments().getFirst().key("mutation"),
                        "the keys of a segment take its name as prefix"));
    }

    @Test
    void anEncodingThatRepeatsIsNumbered() {
        var problem = new FromFactory<>("Repeated",
                () -> composite(integers(2, 0, 5), reals(1, 0.0, 1.0), integers(3, 0, 5), bits(4)));

        List<String> names = SolutionLayout.of(problem).segments().stream().map(Segment::name).toList();

        assertEquals(List.of("integer1", "real", "integer2", "binary"), names, "numbered from 1 when repeated");
    }

    @Test
    void namedSegmentsGiveTheirNamesAndTheBitsOfABinarySegment() {
        Named problem = mixed();

        SolutionLayout layout = SolutionLayout.of(problem);

        assertAll(
                () -> assertEquals(List.of(new Segment("ints", Encoding.INT, 3), new Segment("reals", Encoding.DOUBLE, 2),
                        new Segment("bits", Encoding.BINARY, 8)), layout.segments(), "the names of the problem"),
                () -> assertEquals(13, layout.numberOfVariables(), "3 integers, 2 reals and 5 + 3 bits"),
                () -> assertEquals(1, problem.created.get(), "one solution was created to read the layout"));
    }

    @Test
    void anotherProblemDrawsTheRandomNumbersOfOneSolution() {
        assertFalse(TestProblems.drawsNoRandomNumber(() -> SolutionLayout.of(new NMMin())),
                "its createSolution() draws the initial values");
    }

    // ── Problems that cannot be configured ────────────────────────────────────

    /** jMetal's constructor needs a first component, so the components are removed afterwards. */
    static CompositeSolution compositeOfNoSegment() {
        CompositeSolution solution = composite(integers(1, 0, 5));
        solution.variables().clear();
        return solution;
    }

    static Stream<Arguments> unconfigurableProblems() {
        String types = ", where the configuration files take a DoubleSolution, an IntegerSolution, a BinarySolution";
        return Stream.of(
                Arguments.of(new FromFactory<>("Permutation", () -> new IntegerPermutationSolution(4, 2, 0)),
                        "cannot configure the operators of Permutation: its solutions are "
                                + IntegerPermutationSolution.class.getName() + types + " or a CompositeSolution of those"),
                Arguments.of(new FromFactory<>("Nested", () -> composite(integers(2, 0, 5), composite(reals(2, 0.0, 1.0)))),
                        "cannot configure the operators of Nested: segment 1 of its solutions is "
                                + CompositeSolution.class.getName() + types),
                Arguments.of(new FromFactory<>("NoBits", () -> bits(5, 0)),
                        "cannot configure the operators of NoBits: variables[5] is a BinarySet of 0 bits; "
                                + "a binary variable needs at least one"),
                Arguments.of(new FromFactory<>("EmptySegment", () -> composite(integers(0, 0, 5), reals(2, 0.0, 1.0))),
                        "cannot configure the operators of EmptySegment: segment 0 of its solutions has no variables"),
                Arguments.of(new FromFactory<>("Empty", () -> integers(0, 0, 5)),
                        "cannot configure the operators of Empty: its solutions have no variables"),
                Arguments.of(new ZDT1(0), "cannot configure the operators of ZDT1: its solutions have no variables"),
                Arguments.of(new FromFactory<>("NoSegments", SolutionLayoutTest::compositeOfNoSegment),
                        "cannot configure the operators of NoSegments: its solutions are composites of no segment"),
                Arguments.of(new Named("TooFewNames", List.of("ints"), () -> composite(integers(2, 0, 5), bits(3))),
                        "cannot configure the operators of TooFewNames: NamedSegments.segmentNames() gives 1 name "
                                + "[ints] for 2 segments"),
                Arguments.of(new Named("Spaces", List.of("ints", "my bits"), () -> composite(integers(2, 0, 5), bits(3))),
                        "cannot configure the operators of Spaces: the segment name 'my bits' must start with a letter "
                                + "and hold only letters, digits and underscores"),
                Arguments.of(new Named("Twice", List.of("x", "x"), () -> composite(integers(2, 0, 5), bits(3))),
                        "cannot configure the operators of Twice: the segments of a composite layout need distinct "
                                + "names, got [x, x]"));
    }

    @ParameterizedTest
    @MethodSource("unconfigurableProblems")
    void aProblemWhoseSolutionsTheCataloguesCannotChangeIsRejectedWithTheReason(Problem<?> problem, String message) {
        var exception = assertThrows(IllegalArgumentException.class, () -> SolutionLayout.of(problem));

        assertEquals(message, exception.getMessage(), "the problem and the reason");
    }

    @Test
    void namesForAProblemThatIsNotCompositeAreRejected() {
        class NamedFlat extends FromFactory<DoubleSolution> implements NamedSegments {
            NamedFlat() {
                super("NamedFlat", () -> reals(2, 0.0, 1.0));
            }

            @Override
            public List<String> segmentNames() {
                return List.of("reals");
            }
        }

        var exception = assertThrows(IllegalArgumentException.class, () -> SolutionLayout.of(new NamedFlat()));

        assertEquals("cannot configure the operators of NamedFlat: it implements NamedSegments, but its solutions "
                + "are not composite", exception.getMessage(), "a flat problem has no segments to name");
    }

    // ── Records ───────────────────────────────────────────────────────────────

    @Test
    void aRealLayoutHasOneUnnamedSegmentWhoseKeysHaveNoPrefix() {
        SolutionLayout layout = SolutionLayout.real(12);

        assertAll(
                () -> assertEquals(List.of(new Segment(null, Encoding.DOUBLE, 12)), layout.segments(), "12 reals"),
                () -> assertEquals(12, layout.numberOfVariables(), "its size"),
                () -> assertFalse(layout.composite(), "not composite"),
                () -> assertEquals("mutation", layout.segments().getFirst().key("mutation"), "no prefix"));
    }

    @Test
    void aSingleNamedSegmentIsACompositeOfOneSegment() {
        SolutionLayout layout = new SolutionLayout(List.of(new Segment("bits", Encoding.BINARY, 8)));

        assertAll(
                () -> assertTrue(layout.composite(), "a named segment is a component of a composite"),
                () -> assertEquals("bits.crossover", layout.segments().getFirst().key("crossover"), "its prefix"));
    }

    static Stream<Arguments> wrongLayouts() {
        return Stream.of(
                Arguments.of((Executable) () -> new SolutionLayout(List.of(new Segment(null, Encoding.DOUBLE, 2),
                        new Segment("b", Encoding.INT, 1))), "an unnamed segment in a composite"),
                Arguments.of((Executable) () -> new SolutionLayout(List.of(new Segment("a", Encoding.DOUBLE, 2),
                        new Segment("a", Encoding.INT, 1))), "a repeated name"),
                Arguments.of((Executable) () -> new SolutionLayout(List.of()), "no segment"),
                Arguments.of((Executable) () -> new Segment("a", Encoding.DOUBLE, 0), "a segment of size 0"),
                Arguments.of((Executable) () -> SolutionLayout.real(0), "a real problem of no variables"));
    }

    @ParameterizedTest
    @MethodSource("wrongLayouts")
    void aWrongLayoutIsAnIllegalArgument(Executable building, String wrong) {
        assertThrows(IllegalArgumentException.class, building, wrong + " is rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a.b", "my bits", "1x", "_x", "x-y", "é"})
    void aNameThatCannotPrefixAKeyIsAnIllegalArgument(String name) {
        var exception = assertThrows(IllegalArgumentException.class, () -> new Segment(name, Encoding.INT, 1));

        assertEquals("the segment name '" + name + "' must start with a letter and hold only letters, digits and "
                + "underscores", exception.getMessage(), "also for a lone segment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "ints", "Bits_2", "segment10"})
    void aNameOfLettersDigitsAndUnderscoresIsAccepted(String name) {
        assertEquals(name + ".mutation", new Segment(name, Encoding.INT, 1).key("mutation"), "a valid name");
    }

    // ── Vectors ───────────────────────────────────────────────────────────────

    @Test
    void theLayoutOfAProblemMatchesTheVectorOfItsSolutionsWhateverTheNames() {
        Named problem = mixed();
        SolutionVariables.VectorLayout vector = SolutionVariables.layoutOf(problem.createSolution());
        SolutionLayout renamed = new SolutionLayout(List.of(new Segment("a", Encoding.INT, 3),
                new Segment("b", Encoding.DOUBLE, 2), new Segment("c", Encoding.BINARY, 8)));

        assertAll(
                () -> assertTrue(SolutionLayout.of(problem).matches(vector), "the derived layout"),
                () -> assertTrue(renamed.matches(vector), "names are not compared"));
    }

    static Stream<Arguments> otherShapes() {
        return Stream.of(
                Arguments.of(new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3),
                        new Segment("reals", Encoding.DOUBLE, 3), new Segment("bits", Encoding.BINARY, 8))), "another size"),
                Arguments.of(new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3),
                        new Segment("reals", Encoding.INT, 2), new Segment("bits", Encoding.BINARY, 8))), "another encoding"),
                Arguments.of(new SolutionLayout(List.of(new Segment("bits", Encoding.BINARY, 8),
                        new Segment("ints", Encoding.INT, 3), new Segment("reals", Encoding.DOUBLE, 2))), "another order"),
                Arguments.of(new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3),
                        new Segment("reals", Encoding.DOUBLE, 2))), "a segment fewer"));
    }

    @ParameterizedTest
    @MethodSource("otherShapes")
    void aLayoutOfAnotherShapeDoesNotMatch(SolutionLayout layout, String difference) {
        assertFalse(layout.matches(SolutionVariables.layoutOf(mixed().createSolution())), difference);
    }

    @Test
    void aFlatLayoutDoesNotMatchACompositeOfOneSegment() {
        SolutionVariables.VectorLayout vector = SolutionVariables.layoutOf(composite(integers(3, 0, 10)));

        assertAll(
                () -> assertFalse(new SolutionLayout(List.of(new Segment(null, Encoding.INT, 3))).matches(vector),
                        "flat against composite"),
                () -> assertTrue(new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3))).matches(vector),
                        "a named segment is a composite"));
    }

    @Test
    void aMismatchDescribesBothShapes() {
        SolutionLayout read = new SolutionLayout(List.of(new Segment("ints", Encoding.INT, 3),
                new Segment("reals", Encoding.DOUBLE, 3)));

        assertAll(
                () -> assertEquals("the configuration was read for a composite of integer (3 variables), real (3 variables), "
                        + "but the solutions of Mixed are a composite of integer (3 variables), real (2 variables), "
                        + "binary (8 bits)", read.mismatch(mixed()), "encodings and sizes, in order"),
                () -> assertEquals("the configuration was read for real (1 variable), but the solutions of ZDT5 are "
                        + "binary (80 bits)", SolutionLayout.real(1).mismatch(new ZDT5()), "flat layouts"),
                () -> assertNull(SolutionLayout.of(mixed()).mismatch(mixed()), "the same shape"));
    }
}

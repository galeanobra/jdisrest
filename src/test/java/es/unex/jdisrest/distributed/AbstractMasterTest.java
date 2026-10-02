package es.unex.jdisrest.distributed;

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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link AbstractMaster} that need no running master (constructing one starts
 * Spring): the default failure limit and the decision vector written to the log when a task
 * is discarded, which must stay readable for any encoding and any problem size.
 */
class AbstractMasterTest {

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

    /** A solution type {@code SolutionVariables} does not know: variables are strings. */
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

    // ── Failure limit ─────────────────────────────────────────────────────────

    @Test
    void defaultFailureLimitIsThreeEvaluations() {
        assertEquals(3, AbstractMaster.DEFAULT_MAX_TASK_FAILURES,
            "the documented default: a task is discarded after its third failed evaluation");
    }

    // ── Discard log ───────────────────────────────────────────────────────────

    @Test
    void shortDecisionVectorIsLoggedInFull() {
        assertEquals("[3, -17, 55]", AbstractMaster.variablesOf(intSolution(3, -17, 55)));
        assertEquals("[0.5, -2.25]", AbstractMaster.variablesOf(doubleSolution(0.5, -2.25)));
    }

    @Test
    void compositeDecisionVectorIsLoggedFlat() {
        CompositeSolution composite = new CompositeSolution(List.of(intSolution(1, 2), doubleSolution(0.5)));

        assertEquals("[1, 2, 0.5]", AbstractMaster.variablesOf(composite),
            "a composite is logged in the same flat layout the worker received");
    }

    @Test
    void longDecisionVectorIsAbbreviatedAfterTheLoggedMaximum() {
        int size = AbstractMaster.MAX_LOGGED_VARIABLES + 70;
        String logged = AbstractMaster.variablesOf(intSolution(IntStream.range(0, size).toArray()));

        String expectedHead = IntStream.range(0, AbstractMaster.MAX_LOGGED_VARIABLES)
            .mapToObj(Integer::toString).reduce((a, b) -> a + ", " + b).orElseThrow();
        assertEquals("[" + expectedHead + ", ... 70 more]", logged,
            "only the first MAX_LOGGED_VARIABLES values are printed, then how many were left out");
    }

    @Test
    void unsupportedSolutionIsLoggedWithItsRawVariables() {
        assertEquals("[a, b]", AbstractMaster.variablesOf(stringSolution()));
    }

    @Test
    void contentsThatAreNotASolutionAreLoggedAsIs() {
        assertEquals("plain text", AbstractMaster.variablesOf("plain text"));
        assertEquals("null", AbstractMaster.variablesOf(null));
    }

    @Test
    void abbreviationKeepsListsUpToTheLimitIntact() {
        assertEquals("[]", AbstractMaster.abbreviate(List.of(), 3));
        assertEquals("[1, 2, 3]", AbstractMaster.abbreviate(List.of(1, 2, 3), 3));
        assertEquals("[1, 2, 3, ... 1 more]", AbstractMaster.abbreviate(List.of(1, 2, 3, 4), 3));
    }
}

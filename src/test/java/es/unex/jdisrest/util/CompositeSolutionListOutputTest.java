package es.unex.jdisrest.util;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;

import java.util.List;

import static es.unex.jdisrest.util.SolutionVariablesTest.doubleSolution;
import static es.unex.jdisrest.util.SolutionVariablesTest.intSolution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VAR-row formatting for composites of any number of segments and encodings. */
class CompositeSolutionListOutputTest {

    @Test
    void twoIntegerSegmentsKeepTheOriginalRowFormat() {
        IntegerSolution du = intSolution(1, 2, 3);
        IntegerSolution cu = intSolution(4, 5);
        CompositeSolution c = new CompositeSolution(List.of(du, cu));
        c.objectives()[0] = 10.5;

        assertEquals("1 2 3 4 5,[10.5],[]", CompositeSolutionListOutput.formatSolution(c));
    }

    @Test
    void threeSegmentsAreAllWritten() {
        CompositeSolution c = new CompositeSolution(List.of(intSolution(1), intSolution(2), intSolution(3)));
        String row = CompositeSolutionListOutput.formatSolution(c);

        assertTrue(row.startsWith("1 2 3,"), row);
    }

    @Test
    void realSegmentsAreWrittenAsDoubles() {
        DoubleSolution d = doubleSolution(0.5, -2.0);
        CompositeSolution c = new CompositeSolution(List.of(intSolution(7), d));

        assertTrue(CompositeSolutionListOutput.formatSolution(c).startsWith("7 0.5 -2.0,"),
            CompositeSolutionListOutput.formatSolution(c));
    }

    @Test
    void flatSolutionsAreFormattedToo() {
        assertTrue(CompositeSolutionListOutput.formatSolution(doubleSolution(1.5, 2.5)).startsWith("1.5 2.5,"));
    }
}

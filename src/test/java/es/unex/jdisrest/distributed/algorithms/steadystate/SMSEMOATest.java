package es.unex.jdisrest.distributed.algorithms.steadystate;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.legacy.qualityindicator.impl.hypervolume.impl.PISAHypervolume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SMS-EMOA replacement ({@link SMSEMOA#survivors}): the smallest hypervolume contribution of
 * the last front leaves, ranking takes constraints into account, and an objective that is
 * constant over the joint population (where the hypervolume throws) falls back to crowding
 * distance instead of ending the run.
 */
class SMSEMOATest {

    private static final DominanceWithConstraintsComparator<DoubleSolution> DOMINANCE =
            new DominanceWithConstraintsComparator<>();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A feasible solution whose objectives are {@code f} (its first variable numbers it). */
    static DoubleSolution solution(double... f) {
        return constrained(0.0, f);
    }

    /** A solution with one constraint of value {@code constraint} (negative = violated). */
    static DoubleSolution constrained(double constraint, double... f) {
        DoubleSolution s = new DefaultDoubleSolution(Collections.nCopies(1, Bounds.create(-100.0, 100.0)), f.length, 1);
        s.variables().set(0, f[0]);
        System.arraycopy(f, 0, s.objectives(), 0, f.length);
        s.constraints()[0] = constraint;
        return s;
    }

    static List<DoubleSolution> survivors(List<DoubleSolution> joint) {
        return SMSEMOA.survivors(joint, DOMINANCE, new PISAHypervolume<>());
    }

    /** Asserts that {@code survivors} is {@code joint} without exactly {@code dropped} (by identity). */
    static void assertDropped(DoubleSolution dropped, List<DoubleSolution> joint, List<DoubleSolution> survivors) {
        assertEquals(joint.size() - 1, survivors.size(), "exactly one member must leave");
        for (DoubleSolution s : joint) {
            boolean kept = survivors.stream().anyMatch(t -> t == s);
            assertEquals(s != dropped, kept, "only the expected member may leave: " + List.of(s.objectives()[0]));
        }
    }

    // ── survivors ─────────────────────────────────────────────────────────────

    @Test
    void smallestHypervolumeContributionOfTheLastFrontLeaves() {
        // One front; the inner contributions are 0.2*0.05 = 0.01 for b and 0.75*0.05 = 0.0375 for c.
        DoubleSolution a = solution(0.0, 1.0);
        DoubleSolution b = solution(0.2, 0.8);
        DoubleSolution c = solution(0.25, 0.75);
        DoubleSolution d = solution(1.0, 0.0);
        List<DoubleSolution> joint = List.of(a, b, c, d);

        assertDropped(b, joint, survivors(new ArrayList<>(joint)));
    }

    @Test
    void dominatedSingleMemberOfTheLastFrontLeaves() {
        DoubleSolution dominated = solution(2.0, 2.0);
        List<DoubleSolution> joint = List.of(solution(0.0, 1.0), solution(1.0, 0.0), dominated);

        assertDropped(dominated, joint, survivors(new ArrayList<>(joint)));
    }

    @Test
    void infeasibleSolutionWithBetterObjectivesIsRankedLastAndLeaves() {
        DoubleSolution infeasible = constrained(-1.0, 0.1, 0.1);
        List<DoubleSolution> joint = List.of(solution(0.0, 1.0), solution(0.5, 0.5), solution(1.0, 0.0), infeasible);

        assertDropped(infeasible, joint, survivors(new ArrayList<>(joint)));
    }

    @Test
    void constantObjectiveFallsBackToTheMostCrowdedMemberOfTheLastFront() {
        // A single front with the third objective constant. Crowding over the other two: the
        // extremes are infinite, (1,3) and (2.1,1.9) get 1.0, and (2,2) gets 0.55.
        DoubleSolution crowded = solution(2.0, 2.0, 1.0);
        List<DoubleSolution> joint = List.of(solution(0.0, 4.0, 1.0), solution(1.0, 3.0, 1.0), crowded,
                solution(2.1, 1.9, 1.0), solution(4.0, 0.0, 1.0));
        assertThrows(JMetalException.class,
                () -> new PISAHypervolume<DoubleSolution>().computeHypervolumeContribution(new ArrayList<>(joint), joint),
                "precondition: the hypervolume cannot rank this front");

        assertDropped(crowded, joint, survivors(new ArrayList<>(joint)));
    }

    @Test
    void constantObjectiveWithASingleMemberLastFrontDropsThatMember() {
        // With the second objective constant, the fronts are single solutions ordered by the first.
        DoubleSolution worst = solution(2.0, 1.0);
        List<DoubleSolution> joint = List.of(solution(0.0, 1.0), solution(1.0, 1.0), worst);

        assertDropped(worst, joint, survivors(new ArrayList<>(joint)));
    }

    @Test
    void jointPopulationIsLeftUntouched() {
        List<DoubleSolution> joint = new ArrayList<>(List.of(solution(0.0, 1.0), solution(0.5, 0.5), solution(1.0, 0.0)));
        List<DoubleSolution> before = new ArrayList<>(joint);

        survivors(joint);

        assertEquals(before, joint, "the caller's list must keep its members and order");
    }

    // ── constantObjective ─────────────────────────────────────────────────────

    @Test
    void constantObjectiveFindsTheFirstObjectiveWithASingleValue() {
        assertEquals(1, SMSEMOA.constantObjective(List.of(solution(0.0, 5.0, 7.0), solution(1.0, 5.0, 7.0))));
        assertEquals(-1, SMSEMOA.constantObjective(List.of(solution(0.0, 5.0), solution(1.0, 6.0))));
        assertEquals(-1, SMSEMOA.constantObjective(List.of()), "no solution, no constant objective");
    }

    @Test
    void zeroAndNegativeZeroCountAsTheSameValue() {
        assertEquals(1, SMSEMOA.constantObjective(List.of(solution(0.0, 0.0), solution(1.0, -0.0))),
                "the hypervolume compares with ==, so -0.0 and 0.0 are the same value to it");
    }
}

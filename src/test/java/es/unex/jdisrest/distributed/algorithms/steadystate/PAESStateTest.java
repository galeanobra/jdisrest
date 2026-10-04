package es.unex.jdisrest.distributed.algorithms.steadystate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.mutation.impl.PolynomialMutation;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT1;
import org.uma.jmetal.problem.multiobjective.zdt.ZDT5;
import org.uma.jmetal.solution.binarysolution.BinarySolution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.solution.doublesolution.impl.DefaultDoubleSolution;
import org.uma.jmetal.util.archive.BoundedArchive;
import org.uma.jmetal.util.archive.impl.CrowdingDistanceArchive;
import org.uma.jmetal.util.archive.impl.GenericBoundedArchive;
import org.uma.jmetal.util.bounds.Bounds;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DefaultDominanceComparator;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.densityestimator.impl.GridDensityEstimator;
import org.uma.jmetal.util.errorchecking.exception.InvalidConditionException;
import org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import org.uma.jmetal.util.pseudorandom.PseudoRandomGenerator;
import org.uma.jmetal.util.pseudorandom.impl.JavaRandomGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selection and replacement rules of {@link PAESState}: argument checks, candidate generation
 * (on binary variables too), every branch of the acceptance rule, runtime reconfiguration, and
 * complete searches on ZDT1 (sequential and with candidates in flight, as the distributed master
 * runs it).
 *
 * <p>The state draws from the shared {@link JMetalRandom}; every test runs with a seeded generator
 * of its own and puts the previous one back afterwards.
 */
class PAESStateTest {

    private static final int ZDT1_VARIABLES = 30;
    private static final ZDT1 PROBLEM = new ZDT1(ZDT1_VARIABLES);
    private static final double SHIFT = 0.01;
    private static final long SEED = 1L;
    private static final int ARCHIVE_SIZE = 100;
    private static final int EVALUATIONS = 5000;

    private PseudoRandomGenerator sharedGenerator;

    @BeforeEach
    void seedTheSharedGenerator() {
        sharedGenerator = JMetalRandom.getInstance().getRandomGenerator();
        JMetalRandom.getInstance().setRandomGenerator(new JavaRandomGenerator(SEED));
    }

    @AfterEach
    void restoreTheSharedGenerator() {
        JMetalRandom.getInstance().setRandomGenerator(sharedGenerator);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** A state on ZDT1 with a crowding-distance archive and constraint-aware dominance. */
    static PAESState<DoubleSolution> newState(int archiveSize, MutationOperator<DoubleSolution> mutation,
            double archiveSelectionProbability) {
        var comparator = new DominanceWithConstraintsComparator<DoubleSolution>();
        return new PAESState<>(PROBLEM, new CrowdingDistanceArchive<>(archiveSize, comparator), mutation,
                archiveSelectionProbability, comparator);
    }

    /** Two-variable, two-objective solution whose variables equal its objectives, so each is unique. */
    static DoubleSolution solution(double f1, double f2) {
        return constrainedSolution(new double[0], f1, f2);
    }

    static DoubleSolution constrainedSolution(double[] constraints, double f1, double f2) {
        List<Bounds<Double>> bounds = Collections.nCopies(2, Bounds.create(-10.0, 10.0));
        DoubleSolution s = new DefaultDoubleSolution(bounds, 2, constraints.length);
        s.variables().set(0, f1);
        s.variables().set(1, f2);
        s.objectives()[0] = f1;
        s.objectives()[1] = f2;
        System.arraycopy(constraints, 0, s.constraints(), 0, constraints.length);
        return s;
    }

    /** Records the variables of every solution it receives; shifts the first one from a given call on. */
    static final class StubMutation implements MutationOperator<DoubleSolution> {
        private final int firstActingCall;
        private final List<List<Double>> received = new ArrayList<>();

        StubMutation(int firstActingCall) {
            this.firstActingCall = firstActingCall;
        }

        @Override
        public DoubleSolution execute(DoubleSolution solution) {
            received.add(List.copyOf(solution.variables()));
            if (received.size() >= firstActingCall) {
                solution.variables().set(0, solution.variables().get(0) + SHIFT);
            }
            return solution;
        }

        @Override
        public double mutationProbability() {
            return 1.0;
        }

        int calls() {
            return received.size();
        }

        List<List<Double>> received() {
            return received;
        }
    }

    /** ZDT1's g function: 1 on the Pareto front, around 5.5 for a random solution. */
    static double g(DoubleSolution s) {
        List<Double> x = s.variables();
        return 1.0 + 9.0 * x.subList(1, x.size()).stream().mapToDouble(Double::doubleValue).sum()
                / (x.size() - 1);
    }

    /** Evaluates and integrates {@link #EVALUATIONS} candidates, {@code inFlight} at a time, last first. */
    static void search(PAESState<DoubleSolution> state, int inFlight) {
        for (int evaluations = 0; evaluations < EVALUATIONS; evaluations += inFlight) {
            List<DoubleSolution> batch = new ArrayList<>();
            for (int i = 0; i < inFlight; i++) {
                batch.add(state.nextCandidate());
            }
            batch.forEach(PROBLEM::evaluate);
            Collections.reverse(batch);
            batch.forEach(state::integrate);
        }
    }

    static void assertApproachesTheParetoFront(List<DoubleSolution> archive) {
        var dominance = new DefaultDominanceComparator<DoubleSolution>();
        boolean mutuallyNonDominated = archive.stream()
                .allMatch(a -> archive.stream().allMatch(b -> dominance.compare(a, b) == 0));
        long nearFront = archive.stream().filter(s -> g(s) < 1.1).count();

        assertAll(
                () -> assertTrue(archive.size() > ARCHIVE_SIZE / 2 && archive.size() <= ARCHIVE_SIZE,
                        "the archive must end more than half full and within its bound: " + archive.size()),
                () -> assertTrue(mutuallyNonDominated, "the archive must hold no dominated solution"),
                () -> assertTrue(nearFront >= 0.9 * archive.size(),
                        "at least 90% of the archive must lie near the front: " + nearFront + " of "
                                + archive.size()));
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void nullArchiveIsRejected() {
        assertThrows(NullParameterException.class, () -> new PAESState<>(PROBLEM, null, new StubMutation(1),
                0.0, new DefaultDominanceComparator<DoubleSolution>()));
    }

    @Test
    void nullMutationIsRejected() {
        assertThrows(NullParameterException.class, () -> newState(10, null, 0.0));
    }

    @Test
    void nullProblemOrComparatorIsRejected() {
        var comparator = new DominanceWithConstraintsComparator<DoubleSolution>();
        var archive = new CrowdingDistanceArchive<>(10, comparator);

        assertAll(
                () -> assertThrows(NullParameterException.class,
                        () -> new PAESState<>(null, archive, new StubMutation(1), 0.0, comparator)),
                () -> assertThrows(NullParameterException.class,
                        () -> new PAESState<>(PROBLEM, archive, new StubMutation(1), 0.0, null)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN})
    void selectionProbabilityOutsideTheUnitIntervalIsRejected(double probability) {
        assertThrows(InvalidProbabilityValueException.class, () -> newState(10, new StubMutation(1), probability));
    }

    @Test
    void archiveOfSizeZeroIsRejected() {
        assertThrows(InvalidConditionException.class, () -> newState(0, new StubMutation(1), 0.0));
    }

    // ── Candidates ────────────────────────────────────────────────────────────

    @Test
    void firstCandidateIsARandomSolutionThatIsNotMutated() {
        StubMutation mutation = new StubMutation(1);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);

        DoubleSolution candidate = state.nextCandidate();

        assertAll(
                () -> assertEquals(0, mutation.calls(), "there is no parent to mutate before the first result"),
                () -> assertEquals(ZDT1_VARIABLES, candidate.variables().size(), "the candidate comes from the problem"),
                () -> assertTrue(candidate.variables().stream().allMatch(v -> v >= 0.0 && v <= 1.0),
                        "the random candidate lies within the bounds of the problem"),
                () -> assertNull(state.current(), "a candidate does not become current before it is evaluated"));
    }

    @Test
    void candidateIsAMutatedCopyThatLeavesTheCurrentSolutionUntouched() {
        StubMutation mutation = new StubMutation(1);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);
        DoubleSolution first = solution(0.2, 0.8);
        state.integrate(first);

        DoubleSolution candidate = state.nextCandidate();

        assertAll(
                () -> assertNotSame(first, candidate, "the candidate is a copy"),
                () -> assertEquals(List.of(0.2 + SHIFT, 0.8), candidate.variables(), "the copy is mutated"),
                () -> assertEquals(List.of(0.2, 0.8), first.variables(), "the parent is not mutated"),
                () -> assertEquals(List.of(List.of(0.2, 0.8)), mutation.received(), "the mutation acts once, on the copy"));
    }

    @Test
    void withSelectionProbabilityZeroEveryParentIsTheCurrentSolution() {
        StubMutation mutation = new StubMutation(1);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);
        DoubleSolution first = solution(0.2, 0.8);
        state.integrate(first);
        state.integrate(solution(0.8, 0.2));

        for (int i = 0; i < 50; i++) {
            state.nextCandidate();
        }

        assertEquals(Set.of(first.variables()), new HashSet<>(mutation.received()),
                "with probability 0 only the current solution is mutated");
    }

    @Test
    void withSelectionProbabilityOneParentsAreDrawnFromTheWholeArchive() {
        StubMutation mutation = new StubMutation(1);
        PAESState<DoubleSolution> state = newState(10, mutation, 1.0);
        DoubleSolution first = solution(0.2, 0.8);
        DoubleSolution second = solution(0.8, 0.2);
        state.integrate(first);
        state.integrate(second);

        for (int i = 0; i < 50; i++) {
            state.nextCandidate();
        }

        assertEquals(Set.of(first.variables(), second.variables()), new HashSet<>(mutation.received()),
                "with probability 1 every archive member, the last one included, is drawn as a parent");
    }

    @Test
    void mutationIsRetriedUntilTheCandidateDiffersFromItsParent() {
        StubMutation mutation = new StubMutation(3);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);
        state.integrate(solution(0.2, 0.8));

        DoubleSolution candidate = state.nextCandidate();

        assertAll(
                () -> assertEquals(3, mutation.calls(), "two clones are mutated again"),
                () -> assertEquals(List.of(0.2 + SHIFT, 0.8), candidate.variables(), "the candidate differs from its parent"));
    }

    @Test
    void candidateIsTheUnchangedCopyAfterTheMaximumNumberOfRetries() {
        StubMutation mutation = new StubMutation(Integer.MAX_VALUE);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);
        DoubleSolution first = solution(0.2, 0.8);
        state.integrate(first);

        DoubleSolution candidate = state.nextCandidate();

        assertAll(
                () -> assertEquals(PAESState.MAX_DUPLICATE_RETRIES, mutation.calls(), "the retries are bounded"),
                () -> assertEquals(first.variables(), candidate.variables(), "the clone is returned rather than nothing"),
                () -> assertNotSame(first, candidate, "even a clone is a copy"));
    }

    @Test
    void mutationIsRetriedUntilABinaryCandidateDiffersFromItsParent() {
        // A mutation that flips the first bit in place from its third call on, as BitFlipMutation
        // flips the live BinarySet of the copy it is given.
        int[] calls = {0};
        MutationOperator<BinarySolution> mutation = new MutationOperator<>() {
            @Override
            public BinarySolution execute(BinarySolution solution) {
                if (++calls[0] >= 3) solution.variables().get(0).flip(0);
                return solution;
            }

            @Override
            public double mutationProbability() {
                return 1.0;
            }
        };
        ZDT5 problem = new ZDT5();
        var comparator = new DominanceWithConstraintsComparator<BinarySolution>();
        PAESState<BinarySolution> state = new PAESState<>(problem, new CrowdingDistanceArchive<>(10, comparator),
                mutation, 0.0, comparator);
        BinarySolution first = problem.evaluate(problem.createSolution());
        state.integrate(first);

        BinarySolution candidate = state.nextCandidate();

        assertAll(
                () -> assertEquals(3, calls[0], "two clones of the parent's 80 bits are mutated again"),
                () -> assertNotEquals(first.variables().get(0).get(0), candidate.variables().get(0).get(0)),
                () -> assertEquals(first.variables().subList(1, 11), candidate.variables().subList(1, 11)),
                () -> assertEquals(30, candidate.variables().get(0).getBinarySetLength()));
    }

    // ── Acceptance rule ───────────────────────────────────────────────────────

    @Test
    void firstSolutionBecomesCurrentAndEntersTheArchive() {
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        DoubleSolution evaluated = solution(0.5, 0.5);

        state.integrate(evaluated);

        assertAll(
                () -> assertSame(evaluated, state.current(), "the first result becomes current"),
                () -> assertEquals(List.of(evaluated), state.archiveSolutions(), "the first result is archived"));
    }

    @Test
    void offspringDominatingTheCurrentSolutionReplacesItInTheArchive() {
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        state.integrate(solution(0.5, 0.5));
        DoubleSolution offspring = solution(0.4, 0.4);

        state.integrate(offspring);

        assertAll(
                () -> assertSame(offspring, state.current(), "a dominating offspring becomes current"),
                () -> assertEquals(List.of(offspring), state.archiveSolutions(), "the dominated parent leaves the archive"));
    }

    @Test
    void offspringDominatedByTheCurrentSolutionChangesNothing() {
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        DoubleSolution current = solution(0.4, 0.4);
        state.integrate(current);

        state.integrate(solution(0.5, 0.5));

        assertAll(
                () -> assertSame(current, state.current(), "a dominated offspring does not become current"),
                () -> assertEquals(List.of(current), state.archiveSolutions(), "a dominated offspring is not archived"));
    }

    @Test
    void nonDominatedOffspringIsArchivedButTheCurrentSolutionIsKeptWhileTheArchiveIsNotFull() {
        // The front of the next test, with room for a fourth solution.
        PAESState<DoubleSolution> state = newState(4, new StubMutation(1), 0.0);
        DoubleSolution current = solution(0.5, 0.5);
        DoubleSolution other = solution(0.0, 1.0);
        state.integrate(current);
        state.integrate(other);
        DoubleSolution extreme = solution(1.0, 0.0);

        state.integrate(extreme);

        assertAll(
                () -> assertSame(current, state.current(), "density does not move the search before the archive is full"),
                () -> assertEquals(Set.of(current, other, extreme), Set.copyOf(state.archiveSolutions()),
                        "every mutually non-dominated solution is archived"));
    }

    @Test
    void inAFullArchiveAnOffspringInALessCrowdedRegionBecomesCurrent() {
        // The current solution ends up between the two extremes of the front.
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        state.integrate(solution(0.5, 0.5));
        state.integrate(solution(0.0, 1.0));
        DoubleSolution extreme = solution(1.0, 0.0);

        state.integrate(extreme);

        assertAll(
                () -> assertSame(extreme, state.current(), "an extreme has infinite crowding distance and wins"),
                () -> assertEquals(3, state.archiveSolutions().size(), "the archive is full"));
    }

    @Test
    void inAFullArchiveAnOffspringInAMoreCrowdedRegionLeavesTheCurrentSolution() {
        // The current solution is an extreme of the front.
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        DoubleSolution current = solution(0.0, 1.0);
        state.integrate(current);
        state.integrate(solution(1.0, 0.0));
        DoubleSolution interior = solution(0.5, 0.5);

        state.integrate(interior);

        assertAll(
                () -> assertSame(current, state.current(), "an interior offspring does not beat an extreme"),
                () -> assertTrue(state.archiveSolutions().contains(interior), "the interior offspring is still archived"));
    }

    @Test
    void feasibleOffspringReplacesAnInfeasibleCurrentSolution() {
        // Better objectives do not make up for a violated constraint.
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        state.integrate(constrainedSolution(new double[] {-1.0}, 0.1, 0.1));
        DoubleSolution feasible = constrainedSolution(new double[] {0.0}, 0.9, 0.9);

        state.integrate(feasible);

        assertAll(
                () -> assertSame(feasible, state.current(), "a feasible offspring dominates an infeasible parent"),
                () -> assertEquals(List.of(feasible), state.archiveSolutions(), "the infeasible parent leaves the archive"));
    }

    @Test
    void nullSolutionIsRejected() {
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);

        assertThrows(NullParameterException.class, () -> state.integrate(null));
    }

    @Test
    void archiveSnapshotDoesNotFollowLaterIntegrations() {
        PAESState<DoubleSolution> state = newState(3, new StubMutation(1), 0.0);
        state.integrate(solution(0.5, 0.5));
        List<DoubleSolution> snapshot = state.archiveSolutions();

        state.integrate(solution(0.4, 0.4));

        assertAll(
                () -> assertEquals(List.of(0.5, 0.5), snapshot.getFirst().variables(), "the snapshot keeps what it saw"),
                () -> assertThrows(UnsupportedOperationException.class, () -> snapshot.add(solution(0.1, 0.9)),
                        "the snapshot cannot be used to change the archive"));
    }

    // ── Reconfiguration ───────────────────────────────────────────────────────

    @Test
    void newMutationIsUsedForTheNextCandidate() {
        StubMutation mutation = new StubMutation(1);
        PAESState<DoubleSolution> state = newState(10, mutation, 0.0);
        state.integrate(solution(0.2, 0.8));
        StubMutation newMutation = new StubMutation(1);

        state.setMutation(newMutation);
        state.nextCandidate();

        assertAll(
                () -> assertEquals(0, mutation.calls(), "the replaced mutation is no longer used"),
                () -> assertEquals(1, newMutation.calls(), "the new mutation creates the next candidate"));
    }

    @Test
    void outOfRangeArchiveSelectionProbabilityIsRejectedOnReconfiguration() {
        PAESState<DoubleSolution> state = newState(10, new StubMutation(1), 0.0);

        assertThrows(InvalidProbabilityValueException.class, () -> state.setArchiveSelectionProbability(1.5));
    }

    @Test
    void nullMutationIsRejectedOnReconfiguration() {
        PAESState<DoubleSolution> state = newState(10, new StubMutation(1), 0.0);

        assertThrows(NullParameterException.class, () -> state.setMutation(null));
    }

    // ── Complete searches on ZDT1 ─────────────────────────────────────────────

    @Test
    void sequentialSearchApproachesTheParetoFront() {
        PAESState<DoubleSolution> state = newState(ARCHIVE_SIZE,
                new PolynomialMutation(1.0 / ZDT1_VARIABLES, 20.0), 0.0);

        search(state, 1);

        assertApproachesTheParetoFront(state.archiveSolutions());
    }

    @Test
    void searchWithEightCandidatesInFlightAndResultsInReverseOrderApproachesTheParetoFront() {
        // Emulates eight workers, each evaluating a candidate created before any of their results.
        PAESState<DoubleSolution> state = newState(ARCHIVE_SIZE,
                new PolynomialMutation(1.0 / ZDT1_VARIABLES, 20.0), 0.0);

        search(state, 8);

        assertApproachesTheParetoFront(state.archiveSolutions());
    }

    @Test
    void searchWithTheAdaptiveGridArchiveOfTheOriginalPaesApproachesTheParetoFront() {
        BoundedArchive<DoubleSolution> grid = new GenericBoundedArchive<>(ARCHIVE_SIZE,
                new GridDensityEstimator<>(5, PROBLEM.numberOfObjectives()));
        PAESState<DoubleSolution> state = new PAESState<>(PROBLEM, grid,
                new PolynomialMutation(1.0 / ZDT1_VARIABLES, 20.0), 0.0,
                new DominanceWithConstraintsComparator<>());

        search(state, 1);

        assertApproachesTheParetoFront(state.archiveSolutions());
    }
}

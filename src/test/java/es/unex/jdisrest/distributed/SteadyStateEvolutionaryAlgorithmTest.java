package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static es.unex.jdisrest.distributed.AbstractMasterTest.intSolution;
import static es.unex.jdisrest.distributed.AbstractMasterTest.stderrOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link SteadyStateEvolutionaryAlgorithm} that need no REST server: how the two
 * offspring of a task are taken from whatever the crossover returns ({@code child} and
 * {@code secondChild}), and which results the master refuses once the algorithm has ended its
 * run, tested on an algorithm built without its server (constructing a real one starts Spring).
 * The rest of the class is exercised end to end.
 */
class SteadyStateEvolutionaryAlgorithmTest {

    /** Longest wait for the algorithm thread; a test that reaches it fails. */
    private static final long TIMEOUT_S = 10;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * An algorithm without REST server whose run ends after {@code budget} results. Its four
     * initial tasks cover every claim of these tests, so it breeds no offspring and needs no
     * operators.
     */
    static SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm(int budget) {
        RestWorkerTest.TestProblem<IntegerSolution> problem =
            new RestWorkerTest.TestProblem<>(() -> intSolution(1, 2), s -> { });
        return new SteadyStateEvolutionaryAlgorithm<>(problem, 4, null, null, null,
            new DominanceWithConstraintsComparator<>(), new TerminationByEvaluations(budget));
    }

    /**
     * Runs the algorithm on a daemon thread and returns once its loop is blocked waiting for a
     * result, so that whatever the test does next happens while the loop waits.
     */
    static Thread start(SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm) throws InterruptedException {
        Thread thread = new Thread(algorithm::run, "algorithm-under-test");
        thread.setDaemon(true);
        thread.start();
        Instant deadline = Instant.now().plusSeconds(TIMEOUT_S);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(Instant.now().isBefore(deadline), "the algorithm never started waiting for results");
            Thread.sleep(1);
        }
        return thread;
    }

    /** Waits for {@code run()} to return, and fails (interrupting the run) if it does not in time. */
    static void awaitEnd(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
        if (thread.isAlive()) {
            thread.interrupt();
            fail("run() did not return within " + TIMEOUT_S + " s");
        }
    }

    /** Posts a result for the task, as {@code TaskController} does, with a fixed objective value. */
    static boolean post(SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm,
                        ParallelTask<IntegerSolution> task, String workerId) {
        return algorithm.submitResult(task.getIdentifier(), workerId, t -> t.getContents().objectives()[0] = 5.0);
    }

    // ── Offspring ─────────────────────────────────────────────────────────────

    @Test
    void firstTwoChildrenFeedTheTwoOffspring() {
        List<String> children = List.of("a", "b", "c");

        assertEquals("a", SteadyStateEvolutionaryAlgorithm.child(children, 0));
        assertEquals("b", SteadyStateEvolutionaryAlgorithm.child(children, 1));
    }

    @Test
    void childPastTheLastIsTheLastChild() {
        List<String> children = List.of("only");

        assertEquals("only", SteadyStateEvolutionaryAlgorithm.child(children, 0));
        assertEquals("only", SteadyStateEvolutionaryAlgorithm.child(children, 1),
                "a one-child crossover must not fail task creation");
    }

    @Test
    void secondChildOfATwoChildCrossoverNeedsNoSecondMating() {
        AtomicInteger matings = new AtomicInteger();

        assertEquals("b", SteadyStateEvolutionaryAlgorithm.secondChild(List.of("a", "b", "c"),
                () -> { matings.incrementAndGet(); return List.of("x"); }));
        assertEquals(0, matings.get(), "the random stream of a two-child crossover is unchanged");
    }

    @Test
    void singleChildCrossoverMatesASecondPairForTheSecondOffspring() {
        // With a single child for both, the two offspring would be copies that only the mutation
        // may tell apart, and the duplicate filter never compares siblings.
        AtomicInteger matings = new AtomicInteger();

        assertEquals("other", SteadyStateEvolutionaryAlgorithm.secondChild(List.of("only"),
                () -> { matings.incrementAndGet(); return List.of("other"); }));
        assertEquals(1, matings.get());
    }

    @Test
    void noChildIsAClearError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.child(new ArrayList<String>(), 0));
        assertTrue(e.getMessage().contains("at least one child"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> SteadyStateEvolutionaryAlgorithm.child(null, 1));
        assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.secondChild(List.of(), () -> List.of("x")));
        assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.secondChild(List.of("only"), List::<String>of),
                "a second mating that yields no child fails as the first would");
    }

    // ── End of the run ────────────────────────────────────────────────────────

    @Test
    void resultAfterTheRunHasEndedOnItsStoppingCriterionIsRefused() throws InterruptedException {
        // Two workers evaluate at once and the first result spends the budget: the loop ends,
        // and nobody would process the second result.
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(1);
        Thread thread = start(algorithm);
        ParallelTask<IntegerSolution> first = algorithm.claimNextTask("w1", 1);
        ParallelTask<IntegerSolution> second = algorithm.claimNextTask("w2", 1);

        assertTrue(post(algorithm, first, "w1"));
        awaitEnd(thread);

        assertFalse(post(algorithm, second, "w2"), "the worker gets 404");
        assertTrue(algorithm.getCompletedTaskQueue().isEmpty(), "not queued for the algorithm");
        assertEquals(1, algorithm.getEvaluations(), "the algorithm used the budget, and not one more");
        assertTrue(algorithm.runEnded());
        assertFalse(algorithm.isStopRequested(), "a normal end is not turned into a stop");
    }

    @Test
    void resultIsAcceptedUntilTheLoopHasNoticedItsStoppingCriterion() throws InterruptedException {
        // A criterion already met when it is installed: REST threads see the run finished at
        // once, but the loop, waiting for a result, only notices it when the next one arrives.
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(100);
        Thread thread = start(algorithm);
        ParallelTask<IntegerSolution> first = algorithm.claimNextTask("w1", 1);
        ParallelTask<IntegerSolution> second = algorithm.claimNextTask("w2", 1);
        assertTrue(algorithm.setTermination(new TerminationByEvaluations(0)));

        assertTrue(algorithm.isFinished(), "REST threads see the criterion met");
        assertFalse(algorithm.runEnded(), "but the loop has not noticed it yet");
        assertTrue(post(algorithm, first, "w1"), "so the result that lets it notice is accepted");
        awaitEnd(thread);

        assertEquals(1, algorithm.getEvaluations(), "and used");
        assertFalse(post(algorithm, second, "w2"), "once run() has returned, the next one is refused");
        assertTrue(algorithm.runEnded());
    }

    @Test
    void resultAfterAStoppedRunHasEndedIsRefused() throws InterruptedException {
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(100);
        Thread thread = start(algorithm);
        ParallelTask<IntegerSolution> task = algorithm.claimNextTask("w1", 1);

        stderrOf(algorithm::requestStop);
        awaitEnd(thread);

        assertFalse(post(algorithm, task, "w1"));
        assertTrue(algorithm.getCompletedTaskQueue().isEmpty());
        assertEquals(0, algorithm.getEvaluations());
        assertTrue(algorithm.runEnded(), "a run ended by a stop has ended too");
    }
}

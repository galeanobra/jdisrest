package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static es.unex.jdisrest.distributed.AbstractMasterTest.intSolution;
import static es.unex.jdisrest.distributed.AbstractMasterTest.stderrOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link SteadyStateEvolutionaryAlgorithm} that need no REST server: how the two
 * offspring of a task are taken from whatever the crossover returns ({@code child} and
 * {@code secondChild}), and what the master takes from the workers once the algorithm has ended
 * its run, tested on an algorithm built without its server (constructing a real one starts
 * Spring). The rest of the class is exercised end to end.
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

    @Test
    void failureReportAfterTheRunHasEndedCountsNothing() throws InterruptedException {
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(1);
        algorithm.setMaxTaskFailures(1);  // a counted failure would discard the task at once
        Thread thread = start(algorithm);
        ParallelTask<IntegerSolution> first = algorithm.claimNextTask("w1", 1);
        ParallelTask<IntegerSolution> second = algorithm.claimNextTask("w2", 1);
        assertTrue(post(algorithm, first, "w1"));
        awaitEnd(thread);
        int pending = algorithm.getPendingTaskQueue().size();

        String log = stderrOf(() -> assertFalse(algorithm.failInFlightTask(second.getIdentifier(), "w2"),
            "not counted, as a result would not be"));

        assertTrue(algorithm.inFlightTasks.isEmpty(), "the task leaves flight");
        assertEquals(pending, algorithm.getPendingTaskQueue().size(), "not requeued");
        assertEquals(0, algorithm.getDiscardedTaskCount(), "not discarded");
        assertEquals("", log);
    }

    @Test
    void taskIsNotHandedOutOnceTheRunHasEnded() throws InterruptedException {
        // The initial tasks the run did not need are still queued: a request that passed the
        // controller's check just before the end must not get one, since its result would be
        // refused.
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(1);
        Thread thread = start(algorithm);
        ParallelTask<IntegerSolution> first = algorithm.claimNextTask("w1", 1);
        assertTrue(post(algorithm, first, "w1"));
        awaitEnd(thread);
        assertFalse(algorithm.getPendingTaskQueue().isEmpty());

        assertNull(algorithm.claimNextTask("w2", 1), "204, then 410 on the next request");
        assertTrue(algorithm.inFlightTasks.isEmpty());
    }

    @Test
    void resultsStillQueuedWhenTheRunEndsAreTheOnlyOnesLeftUnused() throws Exception {
        // Two results arrive while the algorithm thread is still busy with the one that spends the
        // budget: both are accepted, since the loop has not noticed the end yet, and neither is
        // ever processed. This pins the end state the status endpoints rely on when they report
        // accepted minus queued once the master needs no more results: the results left unused
        // are exactly those still queued. The reported value itself is not checked here, since
        // MasterFacade reads it from a registered master, which no unit test sets up; the
        // integration tests (EndOfRunScenario) check it over HTTP.
        SteadyStateEvolutionaryAlgorithm<IntegerSolution> algorithm = algorithm(1);
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        algorithm.observable().register((observable, attributes) -> {
            if (algorithm.getEvaluations() == 1) {  // after the first result, before the loop checks
                processing.countDown();
                try {
                    release.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread thread = start(algorithm);
        List<ParallelTask<IntegerSolution>> tasks = new ArrayList<>();
        for (String worker : List.of("w1", "w2", "w3")) {
            tasks.add(algorithm.claimNextTask(worker, 1));
        }

        assertTrue(post(algorithm, tasks.get(0), "w1"));
        assertTrue(processing.await(TIMEOUT_S, TimeUnit.SECONDS), "the first result never reached the algorithm");
        assertFalse(algorithm.needsNoMoreResults(), "the loop has not noticed the end yet");
        assertTrue(post(algorithm, tasks.get(1), "w2"), "so the results that arrive now are accepted");
        assertTrue(post(algorithm, tasks.get(2), "w3"));
        release.countDown();
        awaitEnd(thread);
        int accepted = 3;

        assertTrue(algorithm.needsNoMoreResults());
        assertEquals(1, algorithm.getEvaluations());
        assertEquals(2, algorithm.getCompletedTaskQueue().size(), "the two late ones were never taken");
        assertEquals(algorithm.getEvaluations(), accepted - algorithm.getCompletedTaskQueue().size(),
            "accepted minus queued is what the algorithm used");
    }
}

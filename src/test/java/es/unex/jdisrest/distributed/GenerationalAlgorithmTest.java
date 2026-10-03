package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default {@link GenerationalAlgorithm#run()} loop and its stop and interrupt guards.
 * Without them, a stop on a master whose stopping condition ignores it, or an interrupt of the
 * algorithm thread (whose waits then return at once), makes {@code run()} submit generation
 * after generation that nobody evaluates, so the fixture's stopping condition never ends the
 * run by itself.
 */
class GenerationalAlgorithmTest {

    /** Far more generations than any test needs: reaching it means the loop did not stop. */
    private static final int RUNAWAY = 1_000;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A generational algorithm whose stopping condition is met after {@code generations}
     * evolutions and which keeps the default {@code isStopRequested()}. Records what
     * {@code run()} called.
     */
    static class CountingAlgorithm implements GenerationalAlgorithm<ParallelTask<Integer>, List<Integer>> {
        final int generations;
        int waits;
        int evolutions;

        CountingAlgorithm(int generations) {
            this.generations = generations;
        }

        @Override public List<ParallelTask<Integer>> createInitialTasks() { return List.of(ParallelTask.create(0, 0)); }
        @Override public void submitTasks(List<ParallelTask<Integer>> tasks) { }
        @Override public List<ParallelTask<Integer>> waitForEvaluatedTasks() { waits++; return List.of(); }
        @Override public boolean stoppingConditionIsNotMet() { return evolutions < generations; }
        @Override public List<Integer> getResult() { return List.of(); }

        @Override
        public void evolution(List<ParallelTask<Integer>> population) {
            evolutions++;
            if (evolutions >= RUNAWAY) throw new AssertionError("run() kept evolving after the stop");
        }
    }

    /**
     * A counting algorithm whose stopping condition is never met and whose stop is requested
     * once {@code stopAfter} evolutions have run, as a master overriding
     * {@code isStopRequested()} reports a {@code POST /api/v1/stop}.
     */
    static class StoppableAlgorithm extends CountingAlgorithm {
        final int stopAfter;

        StoppableAlgorithm(int stopAfter) {
            super(Integer.MAX_VALUE);
            this.stopAfter = stopAfter;
        }

        @Override
        public boolean isStopRequested() {
            return evolutions >= stopAfter;
        }
    }

    // ── run ───────────────────────────────────────────────────────────────────

    @Test
    void runEndsWhenTheStoppingConditionIsMet() {
        CountingAlgorithm algorithm = new CountingAlgorithm(2);

        algorithm.run();

        assertEquals(2, algorithm.evolutions, "the default isStopRequested() must not end the run early");
    }

    @Test
    void stopIsNotRequestedByDefault() {
        assertFalse(new CountingAlgorithm(0).isStopRequested(),
            "an implementation that cannot be stopped must never look stopped");
    }

    @Test
    void stopEndsTheRunEvenIfTheStoppingConditionIgnoresIt() {
        StoppableAlgorithm algorithm = new StoppableAlgorithm(3);

        algorithm.run();

        assertEquals(3, algorithm.evolutions, "no generation may start once the stop is requested");
    }

    @Test
    void stopBeforeTheFirstGenerationSkipsEveryEvolution() {
        StoppableAlgorithm algorithm = new StoppableAlgorithm(0);

        algorithm.run();

        assertEquals(0, algorithm.evolutions);
        assertEquals(1, algorithm.waits, "the initial population is still submitted and awaited");
    }

    // ── interrupt ─────────────────────────────────────────────────────────────

    /**
     * A counting algorithm whose stopping condition is never met and whose thread is interrupted
     * during evolution number {@code interruptAt}, as a master's wait returns at once once the
     * flag is set.
     */
    static class InterruptedAlgorithm extends CountingAlgorithm {
        final int interruptAt;

        InterruptedAlgorithm(int interruptAt) {
            super(Integer.MAX_VALUE);
            this.interruptAt = interruptAt;
        }

        @Override
        public void evolution(List<ParallelTask<Integer>> population) {
            super.evolution(population);
            if (evolutions == interruptAt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void interruptEndsTheRunInsteadOfSpinning() {
        InterruptedAlgorithm algorithm = new InterruptedAlgorithm(2);
        try {
            algorithm.run();

            assertEquals(2, algorithm.evolutions, "no generation may start once the thread is interrupted");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be left for the caller");
        } finally {
            Thread.interrupted();  // never leak the flag into other tests
        }
    }

    @Test
    void interruptBeforeTheRunSkipsEveryEvolution() {
        CountingAlgorithm algorithm = new CountingAlgorithm(Integer.MAX_VALUE);
        Thread.currentThread().interrupt();
        try {
            algorithm.run();

            assertEquals(0, algorithm.evolutions);
            assertEquals(1, algorithm.waits, "the initial population is still submitted and awaited");
        } finally {
            Thread.interrupted();
        }
    }
}

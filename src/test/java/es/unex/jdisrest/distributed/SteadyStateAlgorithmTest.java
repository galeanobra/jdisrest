package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default {@link SteadyStateAlgorithm#run()} loop: a {@code null} from
 * {@code waitForComputedTask()} (stopped or interrupted master) and a requested stop must end
 * it, instead of passing {@code null} to {@code processComputedTask()} or waiting for results
 * that never come.
 */
class SteadyStateAlgorithmTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A steady-state algorithm whose stopping condition is never met; {@code waitForComputedTask()}
     * hands out the scripted results and then {@code null}. A stop is requested once
     * {@code stopAfter} results have been processed (never if negative).
     */
    static final class ScriptedAlgorithm implements SteadyStateAlgorithm<ParallelTask<Integer>, List<Integer>> {
        final Deque<ParallelTask<Integer>> results = new ArrayDeque<>();
        final int stopAfter;
        int waits;
        int processed;
        int progressUpdates;
        boolean progressInitialized;

        ScriptedAlgorithm(int stopAfter, int... results) {
            this.stopAfter = stopAfter;
            for (int r : results) this.results.add(ParallelTask.create(r, r));
        }

        @Override public void submitInitialTasks(List<ParallelTask<Integer>> tasks) { }
        @Override public List<ParallelTask<Integer>> createInitialTasks() { return List.of(); }
        @Override public void submitTask(ParallelTask<Integer> task) { }
        @Override public ParallelTask<Integer> createNewTask() { return null; }
        @Override public ParallelTask<Integer> getPendingTask() { return null; }
        @Override public boolean stoppingConditionIsNotMet() { return true; }
        @Override public void initProgress() { progressInitialized = true; }
        @Override public void updateProgress() { progressUpdates++; }
        @Override public int numIdleWorkers() { return 0; }
        @Override public List<Integer> getResult() { return List.of(); }

        @Override
        public ParallelTask<Integer> waitForComputedTask() {
            waits++;
            return results.poll();
        }

        @Override
        public void processComputedTask(ParallelTask<Integer> task) {
            assertNotNull(task, "processComputedTask() must never receive null");
            processed++;
        }

        @Override
        public boolean isStopRequested() {
            return stopAfter >= 0 && processed >= stopAfter;
        }
    }

    // ── run ───────────────────────────────────────────────────────────────────

    @Test
    void nullResultEndsTheLoopWithoutProcessingIt() {
        ScriptedAlgorithm algorithm = new ScriptedAlgorithm(-1, 1, 2);

        algorithm.run();

        assertTrue(algorithm.progressInitialized);
        assertEquals(2, algorithm.processed, "every real result must be processed");
        assertEquals(2, algorithm.progressUpdates, "progress follows processed results only");
        assertEquals(3, algorithm.waits, "the loop ends at the first null");
    }

    @Test
    void stopEndsTheLoopBeforeWaitingAgain() {
        ScriptedAlgorithm algorithm = new ScriptedAlgorithm(1, 1, 2, 3);

        algorithm.run();

        assertEquals(1, algorithm.processed, "no result may be processed after the stop");
        assertEquals(1, algorithm.waits, "the loop must not wait for results once stopped");
    }

    @Test
    void stopBeforeTheLoopProcessesNothing() {
        ScriptedAlgorithm algorithm = new ScriptedAlgorithm(0, 1);

        algorithm.run();

        assertEquals(0, algorithm.waits);
        assertEquals(0, algorithm.processed);
    }
}

package es.unex.jdisrest.distributed.rest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arithmetic of the progress snapshot and the body of {@code GET /api/v1/workers/status},
 * with and without a master. Unit tests never construct a master (its constructor starts
 * Spring), so the facade has none here.
 */
class MasterFacadeTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static StatusSnapshot snapshot(boolean finished, long evaluations, int maxEvals, long elapsedSeconds) {
        return MasterFacade.snapshot(finished, evaluations, maxEvals, START, START.plusSeconds(elapsedSeconds),
            4, 3, 2, 1);
    }

    // ── Progress and ETA ──────────────────────────────────────────────────────

    @Test
    void etaIsExtrapolatedFromTheProgress() {
        StatusSnapshot status = snapshot(false, 250, 1000, 100);

        assertEquals(0.25, status.progress(), 1e-12);
        assertEquals(300, status.estimatedSecondsRemaining(), "100 s for a quarter: 300 s for the rest");
        assertTrue(status.running());
    }

    @Test
    void etaIsNeverNegativeWhenAcceptedResultsExceedTheBudget() {
        StatusSnapshot status = snapshot(false, 1200, 1000, 100);

        assertEquals(1.0, status.progress(), "progress is clamped");
        assertEquals(0, status.estimatedSecondsRemaining(),
            "results accepted but not processed yet push the count past the budget: the ETA is 0, not negative");
    }

    @Test
    void etaIsUnknownBeforeOnePercentAfterTheFinishAndWithoutABudget() {
        assertEquals(-1, snapshot(false, 5, 1000, 100).estimatedSecondsRemaining(), "below 1 %");
        assertEquals(-1, snapshot(true, 500, 1000, 100).estimatedSecondsRemaining(), "finished");
        assertEquals(-1, snapshot(false, 500, -1, 100).estimatedSecondsRemaining(), "no budget");
        assertEquals(0.0, snapshot(false, 500, -1, 100).progress(), "no budget, no progress");
        assertEquals(-1, snapshot(false, 500, 1000, 0).estimatedSecondsRemaining(), "no time elapsed");
    }

    @Test
    void snapshotBeforeInitReportsNoElapsedTime() {
        StatusSnapshot status = MasterFacade.snapshot(false, 0, -1, null, START, 0, 0, 0, 0);

        assertEquals(0, status.elapsedSeconds());
        assertEquals(-1, status.estimatedSecondsRemaining());
        assertEquals(new StatusSnapshot(true, false, 0, -1, 0.0, 0, -1, 0, 0, 0, 0), status);
    }

    // ── Without a master ──────────────────────────────────────────────────────

    @Test
    void withoutAMasterNothingIsReadyOrFinished() {
        assertFalse(MasterFacade.isReady(), "no master means no task can be handed out");
        assertFalse(MasterFacade.isFinished(), "and nothing has finished either");
    }

    @Test
    void clusterStatusWithoutAMasterAnswersZerosInAFixedOrder() throws Exception {
        Map<String, Object> status = MasterFacade.clusterStatus();

        assertEquals(List.of("aliveWorkers", "totalEvaluations", "totalDispatched", "pendingTasks",
                "inFlightTasks", "queuedResults", "workers"), List.copyOf(status.keySet()),
            "the JSON keys must come in the same order on every run");
        assertEquals(0, status.get("pendingTasks"), "no master: no queue, not a NullPointerException");
        assertEquals(0, status.get("queuedResults"));
        assertEquals(Map.of(), status.get("workers"));

        String json = JsonMapper.builder().build().writeValueAsString(status);
        assertTrue(json.startsWith("{\"aliveWorkers\":0,\"totalEvaluations\":"), json);
    }
}

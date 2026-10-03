package es.unex.jdisrest.distributed.rest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire format of {@code GET /api/v1/status} and {@code status.json}: the field order, the
 * additive {@code discardedTasks} field and the ten-component compatibility constructor.
 *
 * <p>The endpoint serializes with the Jackson 3 mapper family Spring WebFlux uses
 * ({@code tools.jackson}); {@code status.json} is written with Jackson 2.
 */
class StatusSnapshotTest {

    private static final JsonMapper JSON3 = JsonMapper.builder().build();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON2 =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** Fields of jdisrest 1.1, in order; anything added later must come after them. */
    private static final List<String> FIELDS_1_1 = List.of(
        "running", "finished", "evaluations", "maxEvaluations", "progress", "elapsedSeconds",
        "estimatedSecondsRemaining", "aliveWorkers", "inFlightTasks", "pendingTasks");

    // ── Fixtures ──────────────────────────────────────────────────────────────

    static StatusSnapshot snapshot(long discardedTasks) {
        return new StatusSnapshot(true, false, 450, 1000, 0.45, 60, 73, 4, 3, 2, discardedTasks);
    }

    @SuppressWarnings("unchecked")
    static List<String> keysOf(String json) {
        return List.copyOf(JSON3.readValue(json, Map.class).keySet());
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    @Test
    void discardedTasksIsAddedAfterTheEarlierFields() throws Exception {
        List<String> expected = new ArrayList<>(FIELDS_1_1);
        expected.add("discardedTasks");

        assertEquals(expected, keysOf(JSON3.writeValueAsString(snapshot(5))),
            "GET /api/v1/status must keep the earlier fields in place and append the new one");
        assertEquals(expected, keysOf(JSON2.writeValueAsString(snapshot(5))),
            "status.json must match GET /api/v1/status");
    }

    @Test
    void statusJsonRoundTripsThroughBothJacksonVersions() throws Exception {
        StatusSnapshot original = snapshot(5);

        assertEquals(original, JSON3.readValue(JSON3.writeValueAsString(original), StatusSnapshot.class));
        assertEquals(original, JSON2.readValue(JSON2.writeValueAsString(original), StatusSnapshot.class),
            "the compatibility constructor must not confuse record binding");
    }

    // ── Compatibility ─────────────────────────────────────────────────────────

    @Test
    void statusJsonOfEarlierVersionsReadsAsNoDiscardedTasks() throws Exception {
        String old = "{\"running\":true,\"finished\":false,\"evaluations\":450,\"maxEvaluations\":1000,"
            + "\"progress\":0.45,\"elapsedSeconds\":60,\"estimatedSecondsRemaining\":73,\"aliveWorkers\":4,"
            + "\"inFlightTasks\":3,\"pendingTasks\":2}";
        assertEquals(FIELDS_1_1, keysOf(old), "the fixture is the JSON of jdisrest 1.1");

        assertEquals(snapshot(0), JSON3.readValue(old, StatusSnapshot.class),
            "a client on Jackson 3 must read the status of a 1.1 master");
        assertEquals(snapshot(0), JSON2.readValue(old, StatusSnapshot.class),
            "and so must one on Jackson 2");
    }

    @Test
    void tenComponentConstructorReportsNoDiscardedTasks() {
        StatusSnapshot legacy = new StatusSnapshot(true, false, 450, 1000, 0.45, 60, 73, 4, 3, 2);

        assertEquals(snapshot(0), legacy, "code written for 1.1 must get the same snapshot with discardedTasks = 0");
    }

    @Test
    void statusWithoutAMasterReportsNoDiscardedTasks() {
        StatusSnapshot status = MasterFacade.currentStatus();

        assertEquals(0, status.discardedTasks(), "no master means nothing discarded");
        assertFalse(status.finished(), "no master means nothing finished");
    }
}

package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run stopped with {@code POST /api/v1/stop} after {@value #USED} of its {@value #BUDGET}
 * evaluations, while {@value EndOfRunScenario#BACKLOG} more results waited in the queue (see
 * {@link EndOfRunScenario}): from the stop on, the status reports the results the algorithm used,
 * and everything that arrives afterwards is refused without being counted.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class RunStoppedIT extends EndOfRunScenario {

    private static final int BUDGET = 100;
    private static final int USED = 3;

    /** The answer to {@code POST /api/v1/stop}. */
    private HttpResponse<String> stopAnswer;

    @Override
    int budget() {
        return BUDGET;
    }

    @Override
    int used() {
        return USED;
    }

    @Override
    void endTheRun() throws Exception {
        stopAnswer = post("/api/v1/stop", "");
    }

    @Test
    @Order(0)  // before the tests of EndOfRunScenario, the last of which shuts the master down
    void stopAnswersWithAStatusThatAlreadyLeavesTheQueuedResultsOut() throws Exception {
        JsonNode status = json(stopAnswer.body());

        assertEquals(202, stopAnswer.statusCode(), "the stop is accepted");
        assertTrue(status.get("finished").asBoolean(), "a stopped run is finished");
        assertEquals(USED, status.get("evaluations").asInt(), "the results queued at the stop will never be used");
        assertTrue(algorithm.isStopRequested(), "the stop reached the master");
    }
}

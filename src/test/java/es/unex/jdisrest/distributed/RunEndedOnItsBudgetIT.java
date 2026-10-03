package es.unex.jdisrest.distributed;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run that ends on its budget of {@value #BUDGET} evaluations, with
 * {@value EndOfRunScenario#BACKLOG} more results accepted while the algorithm thread was busy with
 * the one that spent it (see {@link EndOfRunScenario}): the status reports the budget, not the
 * results accepted, and everything that arrives afterwards is refused without being counted.
 */
@EnabledIfSystemProperty(named = "jdisrest.it", matches = "true",
        disabledReason = "an integration test: mvn verify runs it in a JVM of its own; -Djdisrest.it=true runs it alone")
class RunEndedOnItsBudgetIT extends EndOfRunScenario {

    private static final int BUDGET = 6;

    @Override
    int budget() {
        return BUDGET;
    }

    @Override
    int used() {
        return BUDGET;
    }

    @Override
    void endTheRun() {
        // Nothing to do: the algorithm thread has processed the result that spends the budget,
        // and leaves its loop as soon as it is released.
    }

    @Test
    @Order(0)  // before the tests of EndOfRunScenario, the last of which shuts the master down
    void statusReportsTheBudgetAndFullProgressAfterANormalEnd() throws Exception {
        JsonNode status = get("/api/v1/status");

        // EndOfRunScenario checks the evaluations against the algorithm's count; this checks them
        // against the budget itself.
        assertEquals(BUDGET, status.get("evaluations").asInt(), "not the budget plus the results still queued");
        assertEquals(1.0, status.get("progress").asDouble(), "the whole budget is spent");
        assertFalse(algorithm.isStopRequested(), "a normal end is not turned into a stop");
    }
}

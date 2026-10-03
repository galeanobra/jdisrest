package es.unex.jdisrest.distributed.rest;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /api/v1/stop} without a running master. Unit tests never construct a master,
 * whose constructor starts Spring, so the facade has none and the endpoint must say so
 * instead of pretending a run was stopped.
 */
class StopControllerTest {

    @Test
    void stopWithoutARunningMasterAnswersServiceUnavailable() {
        ResponseEntity<StatusSnapshot> response = new StopController().stop().block();

        assertNotNull(response);
        assertEquals(503, response.getStatusCode().value(), "no master means nothing to stop");
        assertNull(response.getBody(), "a 503 carries no status snapshot");
    }

    @Test
    void repeatedStopsWithoutAMasterLeaveNothingFinished() {
        StopController controller = new StopController();

        controller.stop().block();
        ResponseEntity<StatusSnapshot> second = controller.stop().block();

        assertNotNull(second);
        assertEquals(503, second.getStatusCode().value(), "a repeated stop must answer the same");
        assertFalse(MasterFacade.isFinished(), "a stop without a master must not mark anything finished");
    }
}

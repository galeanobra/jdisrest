package es.unex.jdisrest.distributed.rest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.uma.jmetal.util.errorchecking.exception.NullParameterException;
import reactor.core.publisher.Flux;

/**
 * {@code GET} and {@code POST /api/v1/config} without Spring: the answers without a handler, the
 * status code of every outcome of a handler and of the state of the run, and the body limit and
 * UTF-8 decoding of a {@code POST}.
 */
class ConfigControllerTest {

    private static final BooleanSupplier RUNNING = () -> false;

    private final ConfigController controller = new ConfigController();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Accepts any text except one containing "wrong", and remembers the last one applied. */
    static final class FakeHandler implements ConfigurationHandler {
        private String current = "algorithm=paes\n";
        private String applied;

        @Override
        public String current() {
            return current;
        }

        @Override
        public String apply(String properties) {
            applied = properties;
            if (properties.contains("wrong")) {
                throw new IllegalArgumentException("maxEvaluations is required");
            }
            current = properties;
            return "PAES, applied";
        }
    }

    /** A handler whose {@code apply} throws {@code failure}. */
    static ConfigurationHandler failing(RuntimeException failure) {
        return new ConfigurationHandler() {
            @Override
            public String current() {
                return "";
            }

            @Override
            public String apply(String properties) {
                throw failure;
            }
        };
    }

    /** A request body made of one buffer per chunk. */
    static Flux<DataBuffer> body(byte[]... chunks) {
        return Flux.fromIterable(Arrays.asList(chunks)).map(DefaultDataBufferFactory.sharedInstance::wrap);
    }

    static Flux<DataBuffer> body(String text) {
        return body(text.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> error(String reason) {
        return Map.of("error", reason);
    }

    @AfterEach
    void unregisterTheHandler() {
        MasterFacade.setConfigurationHandler(null);
    }

    // ── Without a handler ─────────────────────────────────────────────────────

    @Test
    void withoutAHandlerReadingTheConfigurationIsNotImplemented() {
        ResponseEntity<String> response = controller.current().block();

        assertEquals(501, response.getStatusCode().value(), "nothing to read without a handler");
    }

    @Test
    void withoutAHandlerPostingIsNotImplementedAndTheBodyIsNotRead() {
        AtomicBoolean read = new AtomicBoolean();

        ResponseEntity<Map<String, String>> response =
                controller.post(body("algorithm=paes\n").doOnSubscribe(subscription -> read.set(true))).block();

        assertAll(
                () -> assertEquals(501, response.getStatusCode().value(), "nothing to apply it to"),
                () -> assertEquals(error("this master cannot change its configuration"), response.getBody()),
                () -> assertFalse(read.get(), "a master without a handler never buffers the body"));
    }

    // ── Outcomes of the handler ───────────────────────────────────────────────

    @Test
    void anAppliedConfigurationIsDescribedAndServedAfterwards() {
        MasterFacade.setConfigurationHandler(new FakeHandler());

        ResponseEntity<Map<String, String>> response = controller.post(body("algorithm=paes\nmaxEvaluations=10\n")).block();

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value(), "applied"),
                () -> assertEquals(Map.of("applied", "PAES, applied"), response.getBody(), "the description"),
                () -> assertEquals("algorithm=paes\nmaxEvaluations=10\n", controller.current().block().getBody(),
                        "GET serves the configuration applied"));
    }

    @Test
    void aConfigurationTheHandlerRejectsIsUnprocessableWithTheReason() {
        MasterFacade.setConfigurationHandler(new FakeHandler());

        ResponseEntity<Map<String, String>> response = controller.post(body("wrong")).block();

        assertAll(
                () -> assertEquals(422, response.getStatusCode().value(), "an IllegalArgumentException"),
                () -> assertEquals(error("maxEvaluations is required"), response.getBody(), "the reason"));
    }

    @Test
    void anEmptyBodyReachesTheHandlerAsAnEmptyText() {
        var handler = new FakeHandler();
        MasterFacade.setConfigurationHandler(handler);

        ResponseEntity<Map<String, String>> response = controller.post(Flux.empty()).block();

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value(), "the fake handler accepts anything"),
                () -> assertEquals("", handler.applied, "an empty text, not null"));
    }

    @Test
    void aHandlerThatRefusesInTheStateOfTheRunAnswersConflict() {
        ResponseEntity<Map<String, String>> response = ConfigController.respond(
                failing(new IllegalStateException("the run has reached its budget of 1000 evaluations")), RUNNING, "");

        assertAll(
                () -> assertEquals(409, response.getStatusCode().value(), "an IllegalStateException"),
                () -> assertEquals(error("the run has reached its budget of 1000 evaluations"), response.getBody()));
    }

    @Test
    void anyOtherFailureOfTheHandlerIsAnInternalErrorWithTheReason() {
        ResponseEntity<Map<String, String>> response =
                ConfigController.respond(failing(new UnsupportedOperationException("not today")), RUNNING, "");

        assertAll(
                () -> assertEquals(500, response.getStatusCode().value(), "neither argument nor state"),
                () -> assertEquals(error("not today"), response.getBody(), "still a JSON reason"));
    }

    @Test
    void aFailureWithoutAMessageIsReportedByItsClass() {
        ResponseEntity<Map<String, String>> response =
                ConfigController.respond(failing(new IllegalArgumentException()), RUNNING, "");

        assertAll(
                () -> assertEquals(422, response.getStatusCode().value(), "an IllegalArgumentException"),
                () -> assertEquals(error("java.lang.IllegalArgumentException"), response.getBody(),
                        "no null in the body"));
    }

    @Test
    void aHandlerThatDescribesNothingAnswersAnEmptyDescription() {
        ConfigurationHandler silent = new ConfigurationHandler() {
            @Override
            public String current() {
                return "";
            }

            @Override
            public String apply(String properties) {
                return null;
            }
        };

        ResponseEntity<Map<String, String>> response = ConfigController.respond(silent, RUNNING, "");

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value(), "applied"),
                () -> assertEquals(Map.of("applied", ""), response.getBody(), "no null in the body"));
    }

    // ── State of the run ──────────────────────────────────────────────────────

    @Test
    void aFinishedRunAnswersConflictWithoutAskingTheHandler() {
        var handler = new FakeHandler();

        ResponseEntity<Map<String, String>> response = ConfigController.respond(handler, () -> true, "algorithm=paes\n");

        assertAll(
                () -> assertEquals(409, response.getStatusCode().value(), "too late to change"),
                () -> assertEquals(error("the run has already finished"), response.getBody()),
                () -> assertNull(handler.applied, "the handler is not asked"));
    }

    @Test
    void aMasterThatCannotTellWhetherItHasFinishedIsNotReadyYet() {
        var handler = new FakeHandler();
        BooleanSupplier startingUp = () -> {
            // What jMetal's TerminationByEvaluations throws before the run has counted anything.
            throw new NullParameterException();
        };

        ResponseEntity<Map<String, String>> response = ConfigController.respond(handler, startingUp, "algorithm=paes\n");

        assertAll(
                () -> assertEquals(503, response.getStatusCode().value(), "not a 500 while the master starts"),
                () -> assertEquals(error("the master is not ready yet"), response.getBody()),
                () -> assertNull(handler.applied, "the handler is not asked"));
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    @Test
    void aBodyOfTheMaximumSizeIsRead() {
        byte[] bytes = new byte[ConfigController.MAX_BODY_BYTES];
        Arrays.fill(bytes, (byte) '#');

        String text = ConfigController.readBody(body(bytes), ConfigController.MAX_BODY_BYTES).block();

        assertEquals(ConfigController.MAX_BODY_BYTES, text.length(), "the limit itself is allowed");
    }

    @Test
    void aBodyAboveTheMaximumSizeFailsWithTheLimit() {
        byte[] bytes = new byte[ConfigController.MAX_BODY_BYTES];

        assertThrows(DataBufferLimitException.class,
                () -> ConfigController.readBody(body(bytes, new byte[1]), ConfigController.MAX_BODY_BYTES).block(),
                "one byte more than the limit");
    }

    @Test
    void aBodyAboveTheMaximumSizeIsTooLargeAndNeverReachesTheHandler() {
        var handler = new FakeHandler();
        MasterFacade.setConfigurationHandler(handler);

        ResponseEntity<Map<String, String>> response =
                controller.post(body(new byte[ConfigController.MAX_BODY_BYTES], new byte[1])).block();

        assertAll(
                () -> assertEquals(413, response.getStatusCode().value(), "content too large"),
                () -> assertEquals(error("the configuration must not be longer than 1048576 bytes"), response.getBody()),
                () -> assertNull(handler.applied, "the handler is not asked"));
    }

    @Test
    void aCharacterSplitAcrossBuffersIsDecodedAsUtf8() {
        byte[] bytes = "# µ and ≥\nalgorithm=paes\n".getBytes(StandardCharsets.UTF_8);
        // "µ" is two bytes, 0xC2 0xB5: cut between them.
        byte[] first = Arrays.copyOfRange(bytes, 0, 3);
        byte[] second = Arrays.copyOfRange(bytes, 3, bytes.length);

        String text = ConfigController.readBody(body(first, second), ConfigController.MAX_BODY_BYTES).block();

        assertEquals("# µ and ≥\nalgorithm=paes\n", text, "the buffers are joined before decoding");
    }

    @Test
    void aBodyThatIsNotUtf8IsUnprocessableAndNeverReachesTheHandler() {
        var handler = new FakeHandler();
        MasterFacade.setConfigurationHandler(handler);

        ResponseEntity<Map<String, String>> response =
                controller.post(body("# Café\nalgorithm=paes\n".getBytes(StandardCharsets.ISO_8859_1))).block();

        assertAll(
                () -> assertEquals(422, response.getStatusCode().value(), "not silently replaced"),
                () -> assertEquals(error("the request body is not UTF-8 text; send the file with the UTF-8 encoding"),
                        response.getBody()),
                () -> assertNull(handler.applied, "the handler is not asked"));
    }

    @Test
    void theBodyReachesTheHandlerAsSent() {
        var handler = new FakeHandler();
        MasterFacade.setConfigurationHandler(handler);
        String text = "\uFEFF# Ünïcode comment\nalgorithm=paes\nmaxEvaluations=10\n";

        controller.post(body(text)).block();

        assertEquals(text, handler.applied, "the handler parses the text, byte order mark included");
    }
}

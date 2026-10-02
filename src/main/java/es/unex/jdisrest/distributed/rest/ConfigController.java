package es.unex.jdisrest.distributed.rest;

import es.unex.jdisrest.util.Log;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST controller that reads and changes the configuration of the running algorithm:
 * {@code GET} and {@code POST /api/v1/config}.
 *
 * <p>Every master maps these endpoints, but they only work once the program has registered a
 * {@link ConfigurationHandler} with {@link MasterFacade#setConfigurationHandler}; until then both
 * answer {@code 501 Not Implemented}, and {@code POST} does so without reading the body.
 *
 * <p>{@code GET} returns the configuration in use as the text of a properties file
 * ({@code text/plain}), ready to be edited and sent back. {@code POST} takes a complete
 * properties file as the request body, read as UTF-8 whatever its content type (so
 * {@code curl --data-binary @file} works as is), and applies it through the handler. Response
 * codes of {@code POST}, every one but {@code 200} with an {@code {"error": "<reason>"}} body:
 * <ul>
 *   <li>{@code 200 OK} — applied; the body is {@code {"applied": "<description>"}}.</li>
 *   <li>{@code 409 Conflict} — the run has already finished or been stopped, or the handler
 *       refuses any change in the state the run is in ({@link IllegalStateException}).</li>
 *   <li>{@code 413 Content Too Large} — the body is longer than 1 MiB.</li>
 *   <li>{@code 422 Unprocessable Content} — the body is not UTF-8 text, or the configuration is
 *       wrong or changes something that is fixed during a run
 *       ({@link IllegalArgumentException}); nothing changes.</li>
 *   <li>{@code 500 Internal Server Error} — the handler failed in another way; the failure is
 *       logged.</li>
 *   <li>{@code 501 Not Implemented} — the program registered no handler.</li>
 *   <li>{@code 503 Service Unavailable} — the master has not started its run yet, so whether it
 *       has finished cannot be told; try again shortly.</li>
 * </ul>
 *
 * <p>Like the rest of the protocol, the endpoint has no authentication: anyone who can reach the
 * master can change its run. The body limit keeps an unauthenticated client from making the
 * master buffer an arbitrarily large request.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    /** Largest {@code POST} body read: far above any configuration file, small enough to buffer. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * Returns the configuration in use as the text of a properties file. The handler is called off
     * the event loop, since a custom one may wait for a change being applied.
     *
     * @return a {@link Mono} emitting {@code 200 OK} with the text ({@code text/plain}), or
     *         {@code 501 Not Implemented} when no {@link ConfigurationHandler} is registered
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> current() {
        return Mono.fromCallable(() -> {
            ConfigurationHandler handler = MasterFacade.configurationHandler();
            return handler != null
                    ? ResponseEntity.ok(handler.current())
                    : ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                            .body("This master does not expose its configuration\n");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Applies a complete properties file sent as the request body (read as UTF-8, at most 1 MiB)
     * through the registered handler.
     *
     * @param request the HTTP request whose body is the configuration
     * @return a {@link Mono} emitting the response; the class description lists the status codes
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, String>>> post(ServerHttpRequest request) {
        return post(request.getBody());
    }

    /**
     * Applies the body of a {@code POST}. The handler is resolved first, so a master without one
     * answers {@code 501} without reading the body.
     */
    Mono<ResponseEntity<Map<String, String>>> post(Flux<DataBuffer> body) {
        return Mono.defer(() -> {
            ConfigurationHandler handler = MasterFacade.configurationHandler();
            if (handler == null) {
                return Mono.just(notImplemented());
            }
            // Applying writes the change to the traces, so it runs off the event loop. Only the
            // body can fail here: respond() turns every failure of the handler into a response.
            return readBody(body, MAX_BODY_BYTES)
                    .flatMap(text -> Mono.fromCallable(() -> respond(handler, MasterFacade::isFinished, text))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .onErrorResume(DataBufferLimitException.class, e -> Mono.just(error(HttpStatus.CONTENT_TOO_LARGE,
                            "the configuration must not be longer than " + MAX_BODY_BYTES + " bytes")))
                    .onErrorResume(IllegalArgumentException.class,
                            e -> Mono.just(error(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage())));
        });
    }

    // ── Body ──────────────────────────────────────────────────────────────────

    /**
     * Joins a request body and decodes it as UTF-8, rejecting bytes that are not UTF-8 instead of
     * replacing them, as a configuration file is read.
     *
     * @param body     the buffers of the body, released here
     * @param maxBytes the largest body accepted
     * @return the text, empty for an empty body; fails with {@link DataBufferLimitException} if the
     *         body is longer than {@code maxBytes}, and with {@link IllegalArgumentException} if it
     *         is not UTF-8
     */
    static Mono<String> readBody(Flux<DataBuffer> body, int maxBytes) {
        return DataBufferUtils.join(body, maxBytes)
                .map(ConfigController::decode)
                .defaultIfEmpty("");
    }

    private static String decode(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "the request body is not UTF-8 text; send the file with the UTF-8 encoding", e);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    // ── Response ──────────────────────────────────────────────────────────────

    /**
     * Applies a configuration through the handler and chooses the response, as listed in the class
     * description.
     *
     * @param handler    the registered handler, or {@code null}
     * @param finished   whether the run has finished ({@link MasterFacade#isFinished()}); a
     *                   failure means the master is not ready yet
     * @param properties the text of the configuration
     * @return the response; never throws for a failure of the handler
     */
    static ResponseEntity<Map<String, String>> respond(ConfigurationHandler handler, BooleanSupplier finished,
            String properties) {
        if (handler == null) {
            return notImplemented();
        }
        boolean over;
        try {
            over = finished.getAsBoolean();
        } catch (RuntimeException e) {
            // Before the run starts, jMetal's terminations cannot be evaluated yet (no EVALUATIONS).
            return error(HttpStatus.SERVICE_UNAVAILABLE, "the master is not ready yet");
        }
        if (over) {
            return error(HttpStatus.CONFLICT, "the run has already finished");
        }
        ResponseEntity<Map<String, String>> response;
        try {
            String description = handler.apply(properties == null ? "" : properties);
            response = ResponseEntity.ok(Map.of("applied", Objects.requireNonNullElse(description, "")));
        } catch (IllegalArgumentException e) {
            response = error(HttpStatus.UNPROCESSABLE_CONTENT, reason(e));
        } catch (IllegalStateException e) {
            response = error(HttpStatus.CONFLICT, reason(e));
        } catch (RuntimeException e) {
            Log.error("POST /api/v1/config failed: " + e, e);
            response = error(HttpStatus.INTERNAL_SERVER_ERROR, reason(e));
        }
        return response;
    }

    private static ResponseEntity<Map<String, String>> notImplemented() {
        return error(HttpStatus.NOT_IMPLEMENTED, "this master cannot change its configuration");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String reason) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Objects.requireNonNullElse(reason, status.getReasonPhrase())));
    }

    /** The message of an exception, or its class name when it has none. */
    private static String reason(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }
}

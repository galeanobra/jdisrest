package es.unex.jdisrest.distributed.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Response body sent by the master to a worker when it claims an evaluation task.
 *
 * <p>{@code variables} is always a flat numeric vector. Integer-encoded
 * variables are serialized as JSON integers ({@code 5}) and real-encoded ones
 * as JSON floats ({@code 5.0}), because each element keeps the runtime type of
 * the master-side variable ({@link Integer} or {@link Double}).
 *
 * <p>The three optional fields are omitted from the JSON when {@code null}, so
 * the payload of an integer problem is identical to the format sent by earlier
 * versions:
 * <ul>
 *   <li>{@code segmentSizes} — only for {@code CompositeSolution} problems: size
 *       of each segment of the concatenated vector {@code [seg0 | seg1 | ...]},
 *       e.g. {@code [3249, 3249, 3249]}.</li>
 *   <li>{@code encoding} — {@code "double"} when every variable is real,
 *       {@code "mixed"} for a composite whose segments differ. Absent means
 *       every variable is an integer.</li>
 *   <li>{@code segmentEncodings} — for composites that are not all-integer:
 *       {@code "int"} or {@code "double"} per segment, aligned with
 *       {@code segmentSizes}.</li>
 * </ul>
 * Workers that pass the whole vector to a simulator may ignore all three.
 *
 * @param taskId           unique task identifier; used by the worker when posting
 *                         the result to {@code POST /api/v1/tasks/{taskId}/result}
 * @param variables        flat decision vector to be evaluated
 * @param segmentSizes     ordered list of segment sizes for composite solutions;
 *                         {@code null} for simple (non-composite) problems
 * @param encoding         {@code "double"} or {@code "mixed"}; {@code null} for
 *                         integer-only problems
 * @param segmentEncodings per-segment encodings for composites that are not
 *                         all-integer; {@code null} otherwise
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskPayload(
    long taskId,
    List<Number> variables,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<Integer> segmentSizes,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String encoding,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<String> segmentEncodings
) {
    /**
     * Compatibility constructor for simple (non-composite) integer problems:
     * {@code segmentSizes}, {@code encoding} and {@code segmentEncodings} are
     * omitted from the serialized JSON.
     *
     * @param taskId    unique task identifier
     * @param variables flat decision vector
     */
    public TaskPayload(long taskId, List<? extends Number> variables) {
        this(taskId, variables, null);
    }

    /**
     * Compatibility constructor for integer problems, composite or not:
     * {@code encoding} and {@code segmentEncodings} are omitted from the
     * serialized JSON.
     *
     * @param taskId       unique task identifier
     * @param variables    flat decision vector
     * @param segmentSizes per-segment sizes, or {@code null} for a flat solution
     */
    public TaskPayload(long taskId, List<? extends Number> variables, List<Integer> segmentSizes) {
        this(taskId, new ArrayList<Number>(variables), segmentSizes, null, null);
    }
}

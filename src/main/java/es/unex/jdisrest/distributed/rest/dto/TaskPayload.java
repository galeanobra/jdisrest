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
 * the master-side variable ({@link Integer} or {@link Double}). A binary variable
 * ({@code BinarySet}) takes one JSON integer {@code 0} or {@code 1} per bit of its
 * length, bit 0 first.
 *
 * <p>The four optional fields are omitted from the JSON when {@code null}, so
 * the payload of an integer problem is identical to the format sent by earlier
 * versions, and that of a problem without binary variables to the format of 1.1 and 1.2:
 * <ul>
 *   <li>{@code segmentSizes} — only for {@code CompositeSolution} problems: size
 *       of each segment of the concatenated vector {@code [seg0 | seg1 | ...]},
 *       e.g. {@code [4, 2]}; a binary segment counts its bits.</li>
 *   <li>{@code encoding} — {@code "double"} when every variable is real,
 *       {@code "binary"} when every variable is binary, {@code "mixed"} for a
 *       composite whose segments differ. Absent means every variable is an
 *       integer, so it is always present when a variable is binary: a worker of an
 *       integer problem of the same length then reports the task instead of
 *       evaluating the bits as integers.</li>
 *   <li>{@code segmentEncodings} — for composites that are not all-integer:
 *       {@code "int"}, {@code "double"} or {@code "binary"} per segment, aligned
 *       with {@code segmentSizes}.</li>
 *   <li>{@code bitsPerVariable} — only when a variable is binary: the length of
 *       every binary variable, in vector order across the segments, e.g.
 *       {@code [3, 5]}.</li>
 * </ul>
 * Workers that pass the whole vector to a simulator may ignore all four.
 *
 * @param taskId           unique task identifier; used by the worker when posting
 *                         the result to {@code POST /api/v1/tasks/{taskId}/result}
 * @param variables        flat decision vector to be evaluated
 * @param segmentSizes     ordered list of segment sizes for composite solutions;
 *                         {@code null} for simple (non-composite) problems
 * @param encoding         {@code "double"}, {@code "binary"} or {@code "mixed"};
 *                         {@code null} for integer-only problems
 * @param segmentEncodings per-segment encodings for composites that are not
 *                         all-integer; {@code null} otherwise
 * @param bitsPerVariable  the lengths of the binary variables; {@code null} when there
 *                         is none
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
    List<String> segmentEncodings,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<Integer> bitsPerVariable
) {
    /**
     * Compatibility constructor with the five components of 1.1 and 1.2, for problems without
     * binary variables: {@code bitsPerVariable} is omitted from the serialized JSON, so it
     * cannot describe a binary one.
     *
     * @param taskId           unique task identifier
     * @param variables        flat decision vector
     * @param segmentSizes     per-segment sizes, or {@code null} for a flat solution
     * @param encoding         {@code "double"} or {@code "mixed"}, or {@code null} for an
     *                         integer-only problem
     * @param segmentEncodings per-segment encodings, or {@code null}
     */
    public TaskPayload(long taskId, List<Number> variables, List<Integer> segmentSizes, String encoding,
                       List<String> segmentEncodings) {
        this(taskId, variables, segmentSizes, encoding, segmentEncodings, null);
    }

    /**
     * Compatibility constructor for simple (non-composite) integer problems:
     * {@code segmentSizes}, {@code encoding}, {@code segmentEncodings} and
     * {@code bitsPerVariable} are omitted from the serialized JSON.
     *
     * @param taskId    unique task identifier
     * @param variables flat decision vector
     */
    public TaskPayload(long taskId, List<? extends Number> variables) {
        this(taskId, variables, null);
    }

    /**
     * Compatibility constructor for integer problems, composite or not:
     * {@code encoding}, {@code segmentEncodings} and {@code bitsPerVariable} are
     * omitted from the serialized JSON.
     *
     * @param taskId       unique task identifier
     * @param variables    flat decision vector
     * @param segmentSizes per-segment sizes, or {@code null} for a flat solution
     */
    public TaskPayload(long taskId, List<? extends Number> variables, List<Integer> segmentSizes) {
        this(taskId, new ArrayList<Number>(variables), segmentSizes, null, null, null);
    }
}

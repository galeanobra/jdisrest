package es.unex.jdisrest.distributed.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body sent by the master to a worker when it claims an evaluation task.
 *
 * <p>The {@code variables} field always contains a flat integer vector. For problems
 * with a simple encoding ({@link org.uma.jmetal.solution.integersolution.IntegerSolution}),
 * {@code segmentSizes} is {@code null} and is omitted from the JSON, keeping the
 * payload identical to the legacy format.
 *
 * <p>For composite problems ({@link org.uma.jmetal.solution.compositesolution.CompositeSolution}),
 * {@code variables} is the concatenated flat vector {@code [seg0 | seg1 | seg2]} and
 * {@code segmentSizes} carries the size of each segment in order, e.g.
 * {@code [3249, 3249, 3249]} for three equal-size segments. Workers that do not need
 * to distinguish segments (e.g., those that pass all values to the simulator as one
 * flat list) may safely ignore this field.
 *
 * @param taskId       unique task identifier; used by the worker when posting
 *                     the result to {@code POST /api/v1/tasks/{taskId}/result}
 * @param variables    flat integer decision vector to be evaluated
 * @param segmentSizes ordered list of segment sizes for composite solutions;
 *                     {@code null} for simple (non-composite) problems
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskPayload(
    long taskId,
    List<Integer> variables,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<Integer> segmentSizes
) {
    /**
     * Compatibility constructor for simple (non-composite) problems.
     * Sets {@code segmentSizes} to {@code null}, which causes the field to be
     * omitted from the serialized JSON.
     *
     * @param taskId    unique task identifier
     * @param variables flat integer decision vector
     */
    public TaskPayload(long taskId, List<Integer> variables) {
        this(taskId, variables, null);
    }
}

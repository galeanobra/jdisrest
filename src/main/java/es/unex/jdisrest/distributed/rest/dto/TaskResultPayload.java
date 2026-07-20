package es.unex.jdisrest.distributed.rest.dto;

import java.util.List;

/**
 * Request body sent by a worker to deliver the result of a completed evaluation.
 *
 * <p>After a worker finishes evaluating a solution it posts this payload to
 * {@code POST /api/v1/tasks/{taskId}/result}. The master deserializes the
 * payload, writes the objective and constraint values back into the
 * corresponding {@code Solution} object, moves the task from the in-flight map
 * to the completed-task queue, and triggers the algorithm's environmental
 * selection step.
 *
 * <p>The {@code evaluationTimeMs} field is informational only; the master logs
 * it but does not use it for scheduling or selection decisions.
 *
 * <p>The {@code variables} field is optional and supports Lamarckian
 * evaluation: when a worker repairs or otherwise modifies the decision vector
 * before computing objectives, it may send back the modified vector so the
 * master can overwrite the {@code Solution}'s variables. When absent (legacy
 * workers, or the worker did not modify the decision) the master keeps the
 * original variables, and only objectives/constraints are updated.
 *
 * @param workerId         identifier of the worker that performed the evaluation
 * @param objectives       evaluated objective values in the order defined by the
 *                         problem (length must equal {@code problem.numberOfObjectives()})
 * @param constraints      evaluated constraint values (negative = violated); must be
 *                         an empty list if the problem has no constraints
 * @param evaluationTimeMs wall-clock time in milliseconds spent evaluating the
 *                         solution, reported by the worker for monitoring purposes
 * @param variables        optional flattened decision vector to overwrite on the
 *                         master-held {@code Solution}; {@code null} or empty
 *                         means keep the original variables. For composite
 *                         solutions the layout is the same concatenation
 *                         {@code [seg0 | seg1 | ...]} that {@code TaskPayload}
 *                         used to send the variables to the worker.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record TaskResultPayload(
    String workerId,
    List<Double> objectives,
    List<Double> constraints,
    long evaluationTimeMs,
    List<Integer> variables
) {}

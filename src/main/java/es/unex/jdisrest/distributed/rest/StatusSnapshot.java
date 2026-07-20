package es.unex.jdisrest.distributed.rest;

/**
 * Single source of truth for the experiment progress payload exposed at
 * {@code GET /api/v1/status} and written periodically to {@code status.json}.
 *
 * <p>Both surfaces serialize this same record so they cannot drift over time.
 *
 * @param running                   {@code true} while the algorithm is still iterating
 * @param finished                  {@code true} once the stopping criterion has been met
 * @param evaluations               cumulative evaluations accepted by the master
 * @param maxEvaluations            evaluation budget configured at startup; {@code -1}
 *                                  if not yet initialized
 * @param progress                  {@code evaluations / maxEvaluations}, clamped to
 *                                  {@code [0.0, 1.0]}; {@code 0.0} when the budget is
 *                                  unknown
 * @param elapsedSeconds            wall-clock seconds since the algorithm started
 * @param estimatedSecondsRemaining ETA in seconds; {@code -1} when not yet computable
 *                                  (progress {@literal <} 1 % or run finished)
 * @param aliveWorkers              workers seen within the heartbeat timeout window
 * @param inFlightTasks             tasks currently held by workers
 * @param pendingTasks              tasks waiting in the dispatch queue
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public record StatusSnapshot(
    boolean running,
    boolean finished,
    long evaluations,
    int maxEvaluations,
    double progress,
    long elapsedSeconds,
    long estimatedSecondsRemaining,
    int aliveWorkers,
    int inFlightTasks,
    int pendingTasks
) {}

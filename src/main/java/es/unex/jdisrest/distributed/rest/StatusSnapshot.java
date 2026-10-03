package es.unex.jdisrest.distributed.rest;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * Single source of truth for the experiment progress payload exposed at
 * {@code GET /api/v1/status} and written periodically to {@code status.json}.
 *
 * <p>Both surfaces serialize this same record so they cannot drift over time.
 * Fields appear in the JSON in declaration order; fields added in later
 * versions go last, so readers that look fields up by name are unaffected.
 * Java clients that bind the JSON to a copy of an older version of this record
 * must ignore unknown properties (Jackson 2 fails on them by default). In the other
 * direction, JSON without {@code discardedTasks} (written by 1.1) reads as {@code 0}.
 *
 * @param running                   {@code true} while the algorithm is still iterating
 * @param finished                  {@code true} once the stopping criterion has been met
 *                                  or a stop has been requested
 *                                  ({@code POST /api/v1/stop})
 * @param evaluations               cumulative evaluations accepted by the master; once a
 *                                  stop has been requested or the algorithm has ended its
 *                                  run, only those it used, without the results still
 *                                  queued (since 1.2.1)
 * @param maxEvaluations            evaluation budget configured at startup; {@code -1}
 *                                  if not yet initialized
 * @param progress                  {@code evaluations / maxEvaluations}, clamped to
 *                                  {@code [0.0, 1.0]}; {@code 0.0} when the budget is
 *                                  unknown
 * @param elapsedSeconds            wall-clock seconds since the algorithm started
 * @param estimatedSecondsRemaining ETA in seconds ({@code 0} or more), or {@code -1} when
 *                                  not computable (progress {@literal <} 1 %, run
 *                                  finished, or no time elapsed yet)
 * @param aliveWorkers              workers seen within the heartbeat timeout window
 * @param inFlightTasks             tasks currently held by workers
 * @param pendingTasks              tasks waiting in the dispatch queue
 * @param discardedTasks            tasks discarded since the master started because
 *                                  their evaluation failed too many times (since 1.2.0)
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
    int pendingTasks,
    @JsonSetter(nulls = Nulls.AS_EMPTY) long discardedTasks
) {
    /**
     * Compatibility constructor with the ten components of earlier versions, for
     * code that builds snapshots itself: {@code discardedTasks} is {@code 0}.
     *
     * @param running                   {@code true} while the algorithm is still iterating
     * @param finished                  {@code true} once the run is over
     * @param evaluations               cumulative evaluations accepted by the master
     * @param maxEvaluations            evaluation budget; {@code -1} if unknown
     * @param progress                  {@code evaluations / maxEvaluations}, clamped
     * @param elapsedSeconds            wall-clock seconds since the algorithm started
     * @param estimatedSecondsRemaining ETA in seconds; {@code -1} when not computable
     * @param aliveWorkers              workers seen within the heartbeat timeout window
     * @param inFlightTasks             tasks currently held by workers
     * @param pendingTasks              tasks waiting in the dispatch queue
     */
    public StatusSnapshot(boolean running, boolean finished, long evaluations, int maxEvaluations,
                          double progress, long elapsedSeconds, long estimatedSecondsRemaining,
                          int aliveWorkers, int inFlightTasks, int pendingTasks) {
        this(running, finished, evaluations, maxEvaluations, progress, elapsedSeconds,
             estimatedSecondsRemaining, aliveWorkers, inFlightTasks, pendingTasks, 0L);
    }
}

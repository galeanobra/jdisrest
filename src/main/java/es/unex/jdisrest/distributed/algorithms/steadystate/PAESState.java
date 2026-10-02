package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.archive.BoundedArchive;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

import java.util.Comparator;
import java.util.List;

/**
 * Search state and rules of {@link PAES}, kept apart from the master so that they can be tested
 * without starting the REST server.
 *
 * <p>Holds the current solution of the (1+1) evolution strategy and the bounded archive used as
 * density estimator. {@link #nextCandidate()} and {@link #integrate(Solution)} port the selection
 * and replacement components of jMetal 7.5 ({@code PAESSelection} and {@code PAESReplacement}),
 * which the jMetal 7.1 used by jdisrest does not include:
 * <ul>
 *   <li><b>Selection</b>: the parent is the current solution or, with probability
 *       {@code archiveSelectionProbability}, a member of the archive drawn uniformly. With 0.0 it is
 *       always the current solution, as in the original PAES.</li>
 *   <li><b>Replacement</b>: an offspring that dominates the current solution enters the archive and
 *       becomes the current solution; a dominated one is discarded; a mutually non-dominated one
 *       enters the archive if the archive accepts it, and becomes the current solution only when the
 *       archive is full and the offspring lies in a less crowded region (see
 *       {@link #applyDensityTiebreak}).</li>
 * </ul>
 *
 * <p>The draws use the shared {@link JMetalRandom}, like every jMetal operator, so seeding it makes
 * a sequential run reproducible.
 *
 * <p>Not thread-safe: {@link PAES} calls every method, the setters included, while holding the
 * population lock.
 *
 * @param <S> the solution type
 * @author Francisco Luna (Universidad de Málaga)
 */
final class PAESState<S extends Solution<?>> {

    /** Mutation attempts allowed to obtain a candidate that differs from its parent. */
    static final int MAX_DUPLICATE_RETRIES = 1000;

    private final Problem<S> problem;
    private final BoundedArchive<S> archive;
    private final Comparator<S> dominanceComparator;
    private final JMetalRandom random = JMetalRandom.getInstance();
    // Replaceable while the run goes on (PAES.reconfigure); guarded by the caller's lock, as every
    // other access to this class.
    private MutationOperator<S> mutation;
    private double archiveSelectionProbability;
    private S current;

    /**
     * @param problem                     source of random solutions until the first result arrives
     * @param archive                     bounded PAES archive; its density comparator breaks ties
     *                                    between mutually non-dominated solutions
     * @param mutation                    the only variation operator of PAES
     * @param archiveSelectionProbability probability of mutating a random archive member instead
     *                                    of the current solution; 0.0 always mutates the current one
     * @param dominanceComparator         decides whether one solution dominates the other
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException if an argument is
     *                                    {@code null}
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidProbabilityValueException if the
     *                                    probability lies outside [0, 1]
     * @throws org.uma.jmetal.util.errorchecking.exception.InvalidConditionException if the archive
     *                                    cannot hold a single solution
     */
    PAESState(Problem<S> problem, BoundedArchive<S> archive, MutationOperator<S> mutation,
            double archiveSelectionProbability, Comparator<S> dominanceComparator) {
        Check.notNull(problem, "problem");
        Check.notNull(archive, "archive");
        Check.notNull(mutation, "mutation");
        Check.notNull(dominanceComparator, "dominanceComparator");
        Check.probabilityIsValid(archiveSelectionProbability, "archiveSelectionProbability");
        Check.that(archive.maximumSize() > 0,
                "the PAES archive size must be greater than 0, got " + archive.maximumSize());

        this.problem = problem;
        this.archive = archive;
        this.mutation = mutation;
        this.archiveSelectionProbability = archiveSelectionProbability;
        this.dominanceComparator = dominanceComparator;
    }

    // ── Settings that can change during a run ────────────────────────────────

    /** Replaces the mutation applied to the parents from the next candidate on. */
    void setMutation(MutationOperator<S> mutation) {
        Check.notNull(mutation, "mutation");
        this.mutation = mutation;
    }

    /** Replaces the probability of mutating a random archive member instead of the current solution. */
    void setArchiveSelectionProbability(double archiveSelectionProbability) {
        Check.probabilityIsValid(archiveSelectionProbability, "archiveSelectionProbability");
        this.archiveSelectionProbability = archiveSelectionProbability;
    }

    // ── Search ───────────────────────────────────────────────────────────────

    /**
     * Returns the next solution to evaluate: a mutated copy of the current solution or, with
     * probability {@code archiveSelectionProbability}, of a random archive member. Until the first
     * result has been integrated there is no current solution, so a random solution is returned.
     *
     * <p>Mutation is repeated while it leaves the copy identical to its parent, because evaluating
     * such a clone would waste a worker. After {@link #MAX_DUPLICATE_RETRIES} attempts the clone is
     * returned anyway and a warning is logged. The attempts run under the caller's lock, so a
     * mutation that almost never changes anything (a probability close to 0, or variables whose
     * bounds are equal) slows down task creation for every worker.
     *
     * @return a new solution, never one held by the state
     */
    S nextCandidate() {
        S candidate;
        if (current == null) {
            candidate = problem.createSolution();
        } else {
            candidate = mutatedCopyOf(selectParent());
        }
        return candidate;
    }

    /**
     * Applies the PAES acceptance rule to an evaluated solution. The first solution becomes the
     * current one. Afterwards the evaluated solution replaces the current one if it dominates it, is
     * discarded if dominated, and otherwise enters the archive, where its density decides.
     *
     * <p>The state keeps the instance it receives: the caller must not change it afterwards.
     *
     * @param evaluated an evaluated solution
     * @throws org.uma.jmetal.util.errorchecking.exception.NullParameterException if it is
     *                                    {@code null}
     */
    void integrate(S evaluated) {
        Check.notNull(evaluated, "evaluated");
        if (current == null) {
            archive.add(evaluated);
            current = evaluated;
        } else {
            // No-op unless a prune dropped the current solution; PAESReplacement does the same.
            archive.add(current);
            int flag = dominanceComparator.compare(current, evaluated);
            if (flag > 0) {
                archive.add(evaluated);
                current = evaluated;
            } else if (flag == 0) {
                applyDensityTiebreak(evaluated);
            }
        }
    }

    /**
     * Returns the solution the strategy is currently mutating.
     *
     * @return the current solution, or {@code null} until the first result has been integrated
     */
    S current() {
        return current;
    }

    /** Returns a snapshot of the bounded PAES archive (feasible or not), in archive order. */
    List<S> archiveSolutions() {
        return List.copyOf(archive.solutions());
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private S selectParent() {
        List<S> members = archive.solutions();
        boolean fromArchive = !members.isEmpty() && random.nextDouble() < archiveSelectionProbability;
        // JMetalRandom.nextInt bounds are inclusive.
        return fromArchive ? members.get(random.nextInt(0, members.size() - 1)) : current;
    }

    @SuppressWarnings("unchecked")
    private S mutatedCopyOf(S parent) {
        List<Number> parentKey = SolutionVariables.flatten(parent);
        S child = null;
        boolean unchanged = true;
        for (int attempt = 0; unchanged && attempt < MAX_DUPLICATE_RETRIES; attempt++) {
            child = (S) parent.copy();
            mutation.execute(child);
            unchanged = parentKey.equals(SolutionVariables.flatten(child));
        }
        if (unchanged) {
            Log.warn("PAES: mutation left the parent unchanged after " + MAX_DUPLICATE_RETRIES
                    + " attempts — using the unchanged copy");
        }
        return child;
    }

    /**
     * Neither solution dominates the other. The evaluated one enters the archive if the archive
     * accepts it, and replaces the current solution only when the archive is full and the evaluated
     * one lies in a less crowded region, according to the archive's density comparator.
     *
     * <p>This is the rule of jMetal 7.5's {@code PAESReplacement}: while the archive is filling up the
     * current solution is always kept, which gives the (1+1) strategy its convergence pressure; moving
     * by density from the start makes the search drift before it converges, badly so with the
     * crowding distance. It departs from the original PAES of Knowles and Corne and from jMetal 7.1's
     * own {@code PAES}, which compare densities on every mutually non-dominated offspring.
     */
    private void applyDensityTiebreak(S evaluated) {
        if (archive.add(evaluated) && archive.size() >= archive.maximumSize()) {
            // Refresh the estimator so that its comparator never reads a missing or stale value:
            // some estimators (angle, spatial spread) have no default for a missing attribute.
            archive.computeDensityEstimator();
            if (archive.comparator().compare(current, evaluated) > 0) {
                current = evaluated;
            }
        }
    }
}

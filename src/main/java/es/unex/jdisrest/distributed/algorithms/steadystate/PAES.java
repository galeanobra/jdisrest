package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ConstraintHandling;
import org.uma.jmetal.util.archive.BoundedArchive;
import org.uma.jmetal.util.archive.impl.CrowdingDistanceArchive;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.errorchecking.Check;

import java.util.List;

/**
 * Distributed steady-state PAES (Pareto Archived Evolution Strategy).
 *
 * <p>PAES is a (1+1) evolution strategy: it keeps a single current solution, creates offspring by
 * mutation only (no crossover) and uses a bounded archive both as the result and as a density
 * estimator that decides between mutually non-dominated solutions. The selection and replacement
 * rules are those of jMetal 7.5's {@code PAESSelection} and {@code PAESReplacement}, on which the
 * configurable PAES of Evolver is built. In particular a mutually non-dominated offspring only
 * moves the search once the archive is full, unlike the original PAES of Knowles and Corne and
 * jMetal 7.1's own {@code PAES}, which compare densities on every such offspring.
 *
 * <h2>Distributed execution</h2>
 * <p>Every worker that asks for work receives a mutated copy of the current solution (or, with
 * probability {@code archiveSelectionProbability}, of an archive member), so several offspring of
 * the same current solution can be under evaluation at once. Each result is compared with the
 * current solution at the moment it arrives. The run starts from a single task; workers that ask for
 * work before its result has come back receive random solutions. No spare task is queued.
 *
 * <h2>Archive</h2>
 * <p>Any {@link BoundedArchive} can be passed; the default is a {@link CrowdingDistanceArchive}, as
 * in Evolver. The adaptive grid of the original PAES, which jMetal 7.1's own {@code PAES} uses by
 * default, is {@code new GenericBoundedArchive<>(archiveSize, new GridDensityEstimator<>(bisections,
 * problem.numberOfObjectives()))}. Other jMetal bounded archives, such as
 * {@code KNNDistanceArchive} or {@code AngleArchive}, work as well.
 *
 * <h2>Differences from Evolver's PAES</h2>
 * <ul>
 *   <li>Dominance takes constraints into account ({@link DominanceWithConstraintsComparator}), as in
 *       the other algorithms of jdisrest; it is the same for unconstrained problems. The archive
 *       keeps and prunes its members with its own dominance comparator, though: the default
 *       archive is built with constraint-aware dominance, but jMetal's
 *       {@code GenericBoundedArchive} and {@code KNNDistanceArchive} always use plain Pareto
 *       dominance, and so do {@code AngleArchive} and {@code CrowdingDistanceArchive} unless a
 *       comparator is passed to them. With such an archive an infeasible member stays until a
 *       solution dominates it in objective space, and it keeps out of the archive every feasible
 *       solution it dominates there, even the current one, so the result can end up smaller;
 *       {@link #getResult()} filters infeasible members out in any case.</li>
 *   <li>Offspring identical to their parent are mutated again instead of being evaluated (1000
 *       attempts at most, then the clone is evaluated and a warning logged).</li>
 * </ul>
 *
 * <h2>Outputs</h2>
 * <p>A warm start ({@link SteadyStateEvolutionaryAlgorithm#createInitialSolutions}) provides only the
 * first solution. Every {@code archiveSize} evaluations (times the trace cadence) the traces write
 * the external archive (see {@link ResultSource#EXTERNAL_ARCHIVE}) to {@code aVAR}/{@code aFUN} and
 * the PAES archive to {@code VAR}/{@code FUN}, so that a run that is killed keeps the set
 * {@link #getResult()} returns by default.
 *
 * @param <S> the solution type
 * @author Francisco Luna (Universidad de Málaga)
 */
public class PAES<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /** Archive returned by {@link #getResult()}. */
    public enum ResultSource {
        /** The bounded PAES archive (the default), the result of PAES as published. */
        PAES_ARCHIVE,
        /**
         * The archive of every non-dominated solution evaluated, reduced to {@code archiveSize}
         * solutions by distance-based subset selection, as the other jdisrest algorithms return.
         */
        EXTERNAL_ARCHIVE
    }

    private final PAESState<S> paes;
    // Replaceable while the run goes on (reconfigure); read by getResult() on the algorithm thread.
    private volatile ResultSource resultSource;

    /**
     * Constructs a PAES master with the usual settings: a crowding-distance archive with
     * constraint-aware dominance, the current solution is always the parent, and the result is the
     * PAES archive.
     *
     * @param host         hostname or IP address the REST server will advertise
     * @param port         HTTP port for the REST server
     * @param problem      the optimization problem (evaluation delegated to workers)
     * @param archiveSize  maximum size of the PAES archive (typical value: 100)
     * @param mutation     mutation operator, the only variation operator of PAES
     * @param termination  stopping criterion (e.g., max evaluations)
     * @param tracesFolder directory for periodic VAR/FUN trace files, or {@code null} to disable
     *                     trace output
     * @throws RuntimeException (jMetal's {@code NullParameterException} or
     *                          {@code InvalidConditionException}) before the REST server starts,
     *                          if an argument is {@code null} or {@code archiveSize < 1}
     */
    public PAES(String host, int port, Problem<S> problem, int archiveSize,
            MutationOperator<S> mutation, Termination termination, String tracesFolder) {
        this(host, port, problem,
             new CrowdingDistanceArchive<>(archiveSize, new DominanceWithConstraintsComparator<>()),
             mutation, 0.0, ResultSource.PAES_ARCHIVE, termination, tracesFolder);
    }

    /**
     * Constructs a PAES master with every PAES setting explicit.
     *
     * @param host                        hostname or IP address the REST server will advertise
     * @param port                        HTTP port for the REST server
     * @param problem                     the optimization problem (evaluation delegated to workers)
     * @param paesArchive                 bounded archive used as density estimator; its maximum
     *                                    size is the number of solutions to find. It prunes with its
     *                                    own dominance comparator (see the class description about
     *                                    constraints)
     * @param mutation                    mutation operator, the only variation operator of PAES
     * @param archiveSelectionProbability probability of mutating a random archive member instead
     *                                    of the current solution; 0.0 always mutates the current
     *                                    one, as the original PAES
     * @param resultSource                archive returned by {@link #getResult()}
     * @param termination                 stopping criterion (e.g., max evaluations)
     * @param tracesFolder                directory for periodic VAR/FUN trace files, or
     *                                    {@code null} to disable trace output
     * @throws RuntimeException (jMetal's {@code NullParameterException},
     *                          {@code InvalidProbabilityValueException} or
     *                          {@code InvalidConditionException}) before the REST server starts, if
     *                          an argument is {@code null}, the probability lies outside [0, 1] or
     *                          the archive cannot hold a single solution
     */
    public PAES(String host, int port, Problem<S> problem, BoundedArchive<S> paesArchive,
            MutationOperator<S> mutation, double archiveSelectionProbability,
            ResultSource resultSource, Termination termination, String tracesFolder) {
        // Checked before super(...), which starts the REST server: a wrong argument must not leave
        // a half-built master with a live server that keeps the JVM running.
        Check.notNull(problem, "problem");
        Check.notNull(paesArchive, "paesArchive");
        Check.notNull(mutation, "mutation");
        Check.probabilityIsValid(archiveSelectionProbability, "archiveSelectionProbability");
        Check.notNull(resultSource, "resultSource");
        Check.notNull(termination, "termination");
        Check.that(paesArchive.maximumSize() > 0,
                "the PAES archive size must be greater than 0, got " + paesArchive.maximumSize());

        // PAES has neither crossover nor parent selection: PAESState creates the offspring.
        super(host, port, problem, paesArchive.maximumSize(), null, mutation, null,
              new DominanceWithConstraintsComparator<>(), termination, tracesFolder);

        this.paes = new PAESState<>(problem, paesArchive, mutation, archiveSelectionProbability,
                dominanceComparator);
        this.resultSource = resultSource;
    }

    // ── Task flow ────────────────────────────────────────────────────────────

    /**
     * A (1+1) strategy starts from a single solution, the first one of a warm start if there is
     * one. Workers that ask for work before its result arrives get random solutions from
     * {@link #createNewTask()}.
     */
    @Override
    public List<ParallelTask<S>> createInitialTasks() {
        List<S> initial = createInitialSolutions(1);
        // A warm start that yields nothing must not abort the run: start from a random solution.
        S first = initial.isEmpty() ? problem.createSolution() : initial.getFirst();
        return List.of(ParallelTask.create(createTaskIdentifier(), first));
    }

    /**
     * Returns a mutated copy of the current solution (or of an archive member), or a random
     * solution before the first result. Runs on the HTTP thread of the worker that asks, under
     * the population lock.
     */
    @Override
    public ParallelTask<S> createNewTask() {
        S candidate;
        synchronized (population) {
            candidate = paes.nextCandidate();
        }
        return ParallelTask.create(createTaskIdentifier(), candidate);
    }

    /**
     * Applies the PAES acceptance rule to the evaluated solution: it becomes the current solution
     * if it dominates it, is discarded if dominated, and otherwise enters the PAES archive, where
     * its density decides once the archive is full. The population holds the current solution
     * only.
     *
     * <p>The external archive receives a copy of its own: its distance-based subset selection
     * writes density attributes into its members without the population lock, while HTTP threads
     * copy PAES members, attributes included, under that lock. Sharing the instances would let both
     * touch the same attribute map at once.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        S solution = (S) task.getContents().copy();
        archive.add((S) solution.copy());

        synchronized (population) {
            paes.integrate(solution);
            population.clear();
            population.add(paes.current());
            rebuildPopulationSignatures();
        }
    }

    // ── Settings that can change during a run ────────────────────────────────

    /**
     * Replaces the PAES settings that can change while the run goes on: the mutation, the archive
     * selection probability and the archive returned as the result. The PAES archive and its size
     * stay as they are. Every argument is checked before anything changes, so a wrong value leaves
     * the run as it was; candidates created from now on use the new settings, candidates already
     * under evaluation keep theirs.
     *
     * @param mutation                    the new mutation operator
     * @param archiveSelectionProbability the new probability of mutating a random archive member
     * @param resultSource                the archive {@link #getResult()} returns from now on
     * @throws RuntimeException (jMetal's {@code NullParameterException} or
     *                          {@code InvalidProbabilityValueException}) if an argument is
     *                          {@code null} or the probability lies outside [0, 1]
     */
    public void reconfigure(MutationOperator<S> mutation, double archiveSelectionProbability,
            ResultSource resultSource) {
        Check.notNull(mutation, "mutation");
        Check.probabilityIsValid(archiveSelectionProbability, "archiveSelectionProbability");
        Check.notNull(resultSource, "resultSource");
        synchronized (population) {
            paes.setMutation(mutation);
            paes.setArchiveSelectionProbability(archiveSelectionProbability);
            // Keeps the inherited field in step; PAES never reads it (nor a crossover).
            setVariation(null, mutation);
            this.resultSource = resultSource;
        }
    }

    // ── Result and traces ────────────────────────────────────────────────────

    /** Returns the feasible solutions of the archive selected by the {@link ResultSource}. */
    @Override
    public List<S> getResult() {
        return switch (resultSource) {
            case PAES_ARCHIVE -> feasiblePaesArchiveSolutions();
            case EXTERNAL_ARCHIVE -> super.getResult();
        };
    }

    /**
     * The {@code VAR}/{@code FUN} traces hold the PAES archive, infeasible members included as in
     * every trace, instead of the population, which only holds the current solution.
     *
     * @return a snapshot of the PAES archive, taken under the population lock
     */
    @Override
    protected List<S> populationTraceSnapshot() {
        synchronized (population) {
            return paes.archiveSolutions();
        }
    }

    private List<S> feasiblePaesArchiveSolutions() {
        List<S> snapshot;
        synchronized (population) {
            snapshot = paes.archiveSolutions();
        }
        return snapshot.stream().filter(ConstraintHandling::isFeasible).toList();
    }
}

package es.unex.jdisrest.local.algorithms;

import es.unex.jdisrest.distributed.WarmStart;
import es.unex.jdisrest.distributed.WarmStartCapable;
import es.unex.jdisrest.util.Log;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.operator.selection.impl.BinaryTournamentSelection;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ConstraintHandling;
import org.uma.jmetal.util.SolutionListUtils;
import org.uma.jmetal.util.archive.Archive;
import org.uma.jmetal.util.archive.impl.BestSolutionsArchive;
import org.uma.jmetal.util.archive.impl.NonDominatedSolutionListArchive;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.evaluator.SolutionListEvaluator;
import es.unex.jdisrest.util.TraceWriter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Generational NSGA-II for the local/sequential execution mode.
 *
 * <p>Thin extension of jMetal's stock NSGA-II that adds, to mirror the
 * contract of the distributed algorithms of {@code es.unex.jdisrest.distributed}:
 * <ul>
 *   <li>Per-generation traces in {@code tracesFolder} using the same filenames:
 *       {@code aVAR_<evals>.csv} / {@code aFUN_<evals>.csv} from the
 *       non-dominated archive, and {@code VAR_<evals>.csv} / {@code FUN_<evals>.csv}
 *       from the current population. Both pairs unfiltered (feasibility
 *       filtering is reserved for the final result). A final snapshot is written
 *       when the run ends, whatever the trace cadence (see {@link #saveTrace()}). A
 *       problem whose solutions the traces cannot hold fails before the first
 *       evaluation (see {@link #createInitialPopulation()}).</li>
 *   <li>{@code iVAR.csv} warm-start when the problem implements
 *       {@link WarmStartCapable}, with a copy of the file in {@code tracesFolder}
 *       (see {@link WarmStart}). A list of another size than {@code populationSize}
 *       is topped up with random solutions, or cut to its first {@code populationSize}
 *       solutions, since jMetal counts the initial population as
 *       {@code populationSize} evaluations whatever its size.</li>
 *   <li>{@link #result()} returning the feasible non-dominated subset of the
 *       archive, downsampled with
 *       {@link SolutionListUtils#distanceBasedSubsetSelection} to at most
 *       {@code populationSize} solutions.</li>
 * </ul>
 *
 * <p>It also differs from jMetal's NSGA-II in two places, to behave like the distributed
 * NSGA-II:
 * <ul>
 *   <li>The replacement ranks the population with constraint-aware dominance
 *       ({@link DominanceWithConstraintsComparator}), so an infeasible solution cannot push a
 *       feasible one out because its objectives are better. jMetal's constructor that this class
 *       used before jdisrest 1.2 ranks with plain Pareto dominance.</li>
 *   <li>Each child of the crossover is copied before it is mutated
 *       ({@link #reproduction(List)}). Crossovers such as jMetal's {@code NPointCrossover} or
 *       {@code CompositeCrossover} may return the parents themselves, or share their parts, and
 *       jMetal mutates the children in place: the mutation would then change population members
 *       and the archive behind their back.</li>
 * </ul>
 *
 * <p>The evaluation strategy is plugged via the {@link SolutionListEvaluator}
 * — typically a {@code PythonSolutionListEvaluator} backed by a Python child
 * process that speaks the line protocol of {@code PythonProcessEvaluator}. This
 * class never shuts the evaluator down: call {@code evaluator.shutdown()} when the
 * run is over, or a Python child process outlives it.
 *
 * @param <S> jMetal solution type
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class NSGAII<S extends Solution<?>> extends org.uma.jmetal.algorithm.multiobjective.nsgaii.NSGAII<S> {

    protected final int populationSize;
    protected final File tracesFolder;
    protected final Archive<S> archive;
    /** Number of generations between trace snapshots. 1 = every generation. */
    protected int traceCadence = 1;
    /** Evaluation count of the last trace snapshot written; the final one is not written twice. */
    private int lastTracedEvaluations = -1;
    /** Set when {@link #run()} has finished its loop: {@link #saveTrace()} then writes the final snapshot. */
    private boolean runEnded;

    /**
     * Sets how often traces are written: every {@code cadence} generations, clamped to a minimum
     * of 1. The final state of the run is traced in any case (see {@link #saveTrace()}).
     */
    public void setTraceCadence(int cadence) {
        this.traceCadence = Math.max(1, cadence);
    }

    /**
     * Builds the algorithm. jMetal's NSGA-II mates the whole population in groups of the
     * crossover's parents, so the population size must be a multiple of
     * {@code crossover.numberOfRequiredParents()} (an even number for the usual two-parent
     * crossovers); it is checked here, instead of failing at the first generation.
     *
     * @param problem        the problem
     * @param populationSize population size, at least 1 and a multiple of the crossover's parents
     * @param maxEvaluations evaluation budget (note the order: jMetal's constructor takes it
     *                       before the population size)
     * @param crossover      the crossover
     * @param mutation       the mutation, applied to a copy of each child
     * @param selection      the mating selection (see {@link #defaultSelection()})
     * @param evaluator      evaluates each generation; shut it down after the run
     * @param tracesFolder   directory for the trace files, or {@code null} for no traces
     * @throws NullPointerException     if {@code crossover} is {@code null}
     * @throws IllegalArgumentException if {@code populationSize} is below 1 or not a multiple of
     *                                  the crossover's number of parents
     */
    public NSGAII(Problem<S> problem,
                  int populationSize,
                  int maxEvaluations,
                  CrossoverOperator<S> crossover,
                  MutationOperator<S> mutation,
                  SelectionOperator<List<S>, S> selection,
                  SolutionListEvaluator<S> evaluator,
                  String tracesFolder) {
        String reason = populationSizeProblem(populationSize,
                Objects.requireNonNull(crossover, "crossover must not be null").numberOfRequiredParents());
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        super(problem, maxEvaluations, populationSize,
              populationSize, populationSize,
              crossover, mutation, selection, new DominanceWithConstraintsComparator<>(), evaluator);
        this.populationSize = populationSize;
        this.tracesFolder   = (tracesFolder != null) ? new File(tracesFolder) : null;
        this.archive        = new BestSolutionsArchive<>(new NonDominatedSolutionListArchive<>(), populationSize);
    }

    /**
     * Why {@code populationSize} cannot be used with a crossover of {@code parents} parents, or
     * {@code null} if it can.
     *
     * @param populationSize the population size
     * @param parents        the crossover's number of required parents
     * @return the reason, or {@code null}
     */
    static String populationSizeProblem(int populationSize, int parents) {
        if (populationSize < 1) {
            return "populationSize = " + populationSize + " must be at least 1";
        }
        if (parents > 0 && populationSize % parents != 0) {
            return "populationSize = " + populationSize + " must be a multiple of the " + parents
                    + " parents the crossover takes (NSGA-II mates the whole population in groups of that size)";
        }
        return null;
    }

    /**
     * Binary tournament decided by constraint-aware Pareto dominance (jMetal's
     * {@link BinaryTournamentSelection} with its default comparator). jMetal's NSGA-II builder
     * decides its tournaments by rank and crowding distance instead; pass
     * {@code new BinaryTournamentSelection<>(new RankingAndCrowdingDistanceComparator<>())} for
     * that.
     */
    public static <T extends Solution<?>> SelectionOperator<List<T>, T> defaultSelection() {
        return new BinaryTournamentSelection<>();
    }

    /**
     * Starts from the warm-start population of {@link WarmStart#load} when there is one, fitted
     * to the population size ({@link #fitted}), and from jMetal's random initial population
     * otherwise.
     *
     * <p>With a traces folder, it then checks that the traces can hold the first solution
     * ({@link TraceWriter#check}), so that a problem whose solutions cannot be traced fails before
     * the initial population is evaluated, not at the first snapshot after it. The check draws no
     * random number, so a seeded run draws as without it. It runs after the warm start, so a run
     * it rejects may already have copied {@code iVAR.csv} into the traces folder.
     *
     * @throws IllegalArgumentException if the traces cannot hold the first solution
     */
    @Override
    protected List<S> createInitialPopulation() {
        List<S> loaded = WarmStart.load(getProblem(), populationSize, WarmStart.FILE,
                tracesFolder == null ? null : tracesFolder.toPath());
        List<S> population = loaded != null ? fitted(loaded, populationSize, getProblem()::createSolution)
                : super.createInitialPopulation();
        String reason = tracesFolder == null || population.isEmpty() ? null : TraceWriter.check(population.get(0));
        if (reason != null) {
            throw new IllegalArgumentException("The traces cannot hold the solutions of " + getProblem().name()
                    + " (" + reason + "); run without a traces folder to evaluate them anyway");
        }
        return population;
    }

    /**
     * A warm-start list fitted to the population size: the first {@code populationSize}
     * solutions of a longer list, a shorter one topped up with random solutions. jMetal counts
     * the initial population as {@code populationSize} evaluations and selects parents from it,
     * so a list of another size would skew the evaluation count and the trace names, and an
     * empty one would fail the first selection.
     *
     * @param loaded         the list the problem returned
     * @param populationSize the population size
     * @param random         creates a random solution
     * @param <T>            the solution type
     * @return a new list of {@code populationSize} solutions
     */
    static <T> List<T> fitted(List<T> loaded, int populationSize, Supplier<T> random) {
        List<T> population = new ArrayList<>(loaded.subList(0, Math.min(loaded.size(), populationSize)));
        if (loaded.size() != populationSize) {
            Log.info("Local NSGA-II: " + (loaded.size() < populationSize
                    ? "adding " + (populationSize - loaded.size()) + " random solutions to the initial population"
                    : "keeping the first " + populationSize + " solutions of the initial population"));
        }
        while (population.size() < populationSize) {
            population.add(random.get());
        }
        return population;
    }

    @Override
    protected void initProgress() {
        super.initProgress();
        for (S sol : getPopulation()) archive.add(sol);
        saveTrace();
    }

    @Override
    protected void updateProgress() {
        super.updateProgress();
        for (S sol : getPopulation()) archive.add(sol);
        saveTrace();
    }

    /**
     * jMetal's reproduction, except that each child is copied before it is mutated, so that the
     * mutation can never reach a parent: jMetal's {@code NPointCrossover} (and
     * {@code TwoPointCrossover}) returns the parents themselves when it does not act, and
     * {@code CompositeCrossover} shares the parents' segments. Without the copy, the mutation would
     * change population members, which then keep stale objectives or leave dominated entries in
     * the archive.
     *
     * @param matingPool the mating pool, whose size is a multiple of the crossover's parents
     * @return the mutated offspring
     */
    @Override
    @SuppressWarnings("unchecked")
    protected List<S> reproduction(List<S> matingPool) {
        int numberOfParents = crossoverOperator.numberOfRequiredParents();

        checkNumberOfParents(matingPool, numberOfParents);

        List<S> offspringPopulation = new ArrayList<>(offspringPopulationSize);
        for (int i = 0; i < matingPool.size(); i += numberOfParents) {
            List<S> parents = new ArrayList<>(numberOfParents);
            for (int j = 0; j < numberOfParents; j++) {
                parents.add(matingPool.get(i + j));
            }

            List<S> offspring = crossoverOperator.execute(parents);

            for (S child : offspring) {
                S s = (S) child.copy();
                mutationOperator.execute(s);
                offspringPopulation.add(s);
                if (offspringPopulation.size() >= offspringPopulationSize) {
                    break;
                }
            }
        }
        return offspringPopulation;
    }

    /**
     * Runs jMetal's generational loop, then writes the final trace snapshot ({@link #saveTrace()});
     * a final snapshot that cannot be written is logged as an error without failing the run.
     */
    @Override
    public void run() {
        runEnded = false;
        lastTracedEvaluations = -1;
        super.run();
        runEnded = true;
        try {
            saveTrace();
        } catch (RuntimeException e) {
            Log.error("Could not write the final trace snapshot (" + e + ") — the result is not affected");
        }
    }

    /**
     * Writes a trace snapshot every {@code populationSize * traceCadence} evaluations when a
     * traces folder is set: the archive to {@code aVAR_<n>.csv} / {@code aFUN_<n>.csv} and the
     * population to {@code VAR_<n>.csv} / {@code FUN_<n>.csv}, where {@code n} is the number of
     * evaluations. Called after every generation, and once more by {@link #run()} at the end:
     * that last call writes the final snapshot whatever the count, unless it already exists. A
     * snapshot is never written twice for the same count. Subclasses may override it to change
     * what is traced.
     */
    protected void saveTrace() {
        if (tracesFolder == null || evaluations == lastTracedEvaluations) return;
        // In long: populationSize * traceCadence overflows int for large cadences.
        if (!runEnded && evaluations % ((long) populationSize * traceCadence) != 0) return;
        if (!tracesFolder.exists() && !tracesFolder.mkdirs()) {
            Log.error("Error creating traces folder " + tracesFolder);
            return;
        }
        String prefix = tracesFolder + "/";
        Log.info("Population trace saved after " + evaluations + " evaluations in " + tracesFolder + " folder");
        lastTracedEvaluations = evaluations;

        List<S> archiveSnapshot    = new ArrayList<>(archiveSolutions());
        List<S> populationSnapshot = new ArrayList<>(getPopulation());

        TraceWriter.write(archiveSnapshot,
                prefix + "aVAR_" + evaluations + ".csv",
                prefix + "aFUN_" + evaluations + ".csv", ",");
        TraceWriter.write(populationSnapshot,
                prefix + "VAR_" + evaluations + ".csv",
                prefix + "FUN_" + evaluations + ".csv", ",");
    }

    /**
     * The feasible solutions of the archive, at most {@code populationSize} of them; empty when
     * none is feasible, and before {@link #run()} (the archive is empty then).
     */
    @Override
    public List<S> result() {
        List<S> feasible = archiveSolutions().stream()
            .filter(ConstraintHandling::isFeasible)
            .collect(Collectors.toList());
        if (feasible.isEmpty()) return List.of();
        return SolutionListUtils.distanceBasedSubsetSelection(
                feasible, Math.min(populationSize, feasible.size()));
    }

    /** The archive's members (at most {@code populationSize}); empty for an empty archive, on which jMetal throws. */
    private List<S> archiveSolutions() {
        return archive.size() == 0 ? List.of() : archive.solutions();
    }
}

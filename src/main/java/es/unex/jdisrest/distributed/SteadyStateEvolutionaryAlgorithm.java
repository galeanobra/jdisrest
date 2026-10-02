package es.unex.jdisrest.distributed;

import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.operator.selection.impl.RankingAndCrowdingSelection;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ConstraintHandling;
import org.uma.jmetal.util.SolutionListUtils;
import org.uma.jmetal.util.archive.Archive;
import org.uma.jmetal.util.archive.impl.BestSolutionsArchive;
import org.uma.jmetal.util.archive.impl.NonDominatedSolutionListArchive;
import org.uma.jmetal.util.observable.impl.DefaultObservable;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.SolutionVariables;
import es.unex.jdisrest.util.TraceWriter;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base class for distributed steady-state multi-objective evolutionary algorithms.
 *
 * <p>Provides the shared infrastructure common to all steady-state variants (NSGA-II,
 * SMS-EMOA, MOEA/D): population management, offspring generation (selection → crossover →
 * mutation), duplicate detection via {@link #populationSignatures}, periodic trace output,
 * warm-start loading through {@link WarmStart}, and the main {@link #run()} loop that drives
 * the algorithm until the stopping criterion is met or a stop is requested.
 *
 * <p>Concrete subclasses override {@link #processComputedTask} to implement their specific
 * environmental selection criterion (e.g., crowding-distance ranking for NSGA-II or
 * hypervolume contribution for SMS-EMOA).
 *
 * <h2>Threads and locks</h2>
 * <ul>
 *   <li>The algorithm thread, the one that calls {@link #run()}, is the only one that processes
 *       results: {@link #processComputedTask}, {@link #updateProgress()} and {@link #saveTrace()}
 *       run there. {@link #evaluations} therefore has a single writer.</li>
 *   <li>REST threads create tasks on demand ({@link #createNewTask()}) and evaluate
 *       {@link #stoppingConditionIsNotMet()}; any thread may call {@link #getEvaluations()},
 *       {@link #setVariation} and {@link #setTermination}. The last takes a private lock with
 *       the algorithm thread's check of the stopping condition.</li>
 *   <li>{@link #population} and {@link #populationSignatures} are read and modified inside
 *       {@code synchronized (population)}, and {@link #createNewTask()} reads {@link #crossover}
 *       and {@link #mutation} under the same lock. {@link #setVariation} takes it too, so no task
 *       mixes an old and a new operator. Subclasses that override {@code createNewTask()} must
 *       read the operators inside that lock as well.</li>
 * </ul>
 *
 * @param <S> the solution type (e.g., {@code IntegerSolution} or {@code CompositeSolution})
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class SteadyStateEvolutionaryAlgorithm<S extends Solution<?>> extends SteadyStateMaster<ParallelTask<S>, List<S>> {

    protected final Problem<S> problem;
    /**
     * Mutation applied to both children of every new task. Replaceable during the run through
     * {@link #setVariation}; read it inside {@code synchronized (population)}.
     */
    protected volatile MutationOperator<S> mutation;
    protected final SelectionOperator<List<S>, List<S>> selection;
    /**
     * Stopping criterion, evaluated on {@link #attributes}. Replaceable during the run through
     * {@link #setTermination}.
     */
    protected volatile Termination termination;
    protected final int populationSize;
    protected final Map<String, Object> attributes;
    protected final org.uma.jmetal.util.observable.Observable<Map<String, Object>> observable;
    /**
     * Crossover applied to the parents of every new task. Replaceable during the run through
     * {@link #setVariation}; read it inside {@code synchronized (population)}. {@code null} only
     * in subclasses whose {@link #createNewTask()} does not use it.
     */
    protected volatile CrossoverOperator<S> crossover;
    protected Comparator<S> dominanceComparator;
    protected List<S> population;
    /**
     * Results processed so far. Written only by the algorithm thread, which makes the
     * non-atomic {@code evaluations++} of {@link #processComputedTask} safe; volatile so that
     * REST threads read it up to date. Subclasses must keep the single writer.
     */
    protected volatile int evaluations = 0;
    protected long initTime;
    protected final AtomicLong idCounter = new AtomicLong(0);
    protected Archive<S> archive;
    /** Mirrors the current population variables for O(1) duplicate detection. */
    protected final Set<List<?>> populationSignatures = Collections.synchronizedSet(new HashSet<>());
    protected File tracesFolder;
    /** Number of "generations" (populationSize evaluations each) between trace snapshots. 1 = every gen. */
    protected int traceCadence = 1;
    /**
     * Set when the algorithm thread finds {@link #termination} met, and when {@link #run()}
     * returns or throws. From then on {@link #stoppingConditionIsNotMet()} is {@code false}
     * whatever {@link #termination} says, so a later {@link #setTermination} cannot reopen the
     * run and a failed run stops handing out tasks nobody would collect.
     */
    private volatile boolean runFinished;
    /**
     * Taken by {@link #setTermination} and by the algorithm thread's check of {@link #termination},
     * so that the loop never ends on a criterion that is being replaced.
     */
    private final Object terminationLock = new Object();
    /** The thread running {@link #run()}, whose checks of the stopping condition end the loop. */
    private volatile Thread algorithmThread;

    /** Set how often traces are written; clamped to a minimum of 1. */
    public void setTraceCadence(int cadence) {
        this.traceCadence = Math.max(1, cadence);
    }

    public SteadyStateEvolutionaryAlgorithm(String host, int port, Problem<S> problem, int populationSize,
            CrossoverOperator<S> crossover, MutationOperator<S> mutation,
            SelectionOperator<List<S>, List<S>> selection, Comparator<S> dominanceComparator,
            Termination termination, String tracesFolder) {

        super(host, port, problem);  // boots Spring Boot here
        this.problem = problem;
        this.crossover = crossover;
        this.mutation = mutation;
        this.populationSize = populationSize;
        this.termination = termination;
        this.selection = selection;
        this.dominanceComparator = dominanceComparator;
        // All accesses to `population` are wrapped in synchronized(population), so the
        // synchronizedList wrapper would only add a redundant second layer of locking.
        this.population = new ArrayList<>();

        attributes  = new HashMap<>();
        observable  = new DefaultObservable<>("Observable");
        archive     = new BestSolutionsArchive<>(new NonDominatedSolutionListArchive<>(), populationSize);

        if (tracesFolder != null) this.tracesFolder = new File(tracesFolder);
    }

    // ── waitForWorkers() and acceptConnection() REMOVED ──────────────────────
    // Spring Boot (started in SteadyStateMaster) accepts HTTP connections from workers.
    // No ServerSocket, no per-worker threads, no ssWorkerTalker.

    public long createTaskIdentifier() {
        return idCounter.getAndIncrement();
    }

    /**
     * Canonical key for a solution used as the HashSet element.
     *
     * <p>The key is a defensive copy of the flat decision vector produced by
     * {@link SolutionVariables#flatten}: for {@code CompositeSolution} the component
     * variables are concatenated (jMetal's inner solutions use identity equality, so
     * {@code variables()} itself is not usable as a key), and for flat solutions the
     * copy avoids storing a reference to the live variable list inside the set.
     *
     * <p>Equality is exact, element by element. With integer encodings this catches
     * every duplicate. With real encodings two independently generated vectors are
     * practically never bit-identical, so the filter only catches exact clones —
     * offspring on which neither crossover nor mutation acted — which is what real-coded
     * evolution actually produces as duplicates.
     */
    protected List<Number> solutionKey(S sol) {
        return SolutionVariables.flatten(sol);
    }

    /** Rebuilds populationSignatures to match the current population. Call inside synchronized(population). */
    protected void rebuildPopulationSignatures() {
        populationSignatures.clear();
        population.forEach(s -> populationSignatures.add(solutionKey(s)));
    }

    @Override
    public void initProgress() {
        attributes.put("EVALUATIONS", evaluations);
        attributes.put("POPULATION", population);
        attributes.put("COMPUTING_TIME", System.currentTimeMillis() - initTime);
        observable.setChanged();
        observable.notifyObservers(attributes);
    }

    @Override
    public void updateProgress() {
        attributes.put("EVALUATIONS", evaluations);
        attributes.put("POPULATION", population);
        attributes.put("COMPUTING_TIME", System.currentTimeMillis() - initTime);
        observable.setChanged();
        observable.notifyObservers(attributes);
        saveTrace();
    }

    // ── Initial population ────────────────────────────────────────────────────

    /**
     * Creates one task per solution of {@link #createInitialSolutions(int)
     * createInitialSolutions(populationSize)}, in order.
     */
    @Override
    public List<ParallelTask<S>> createInitialTasks() {
        List<ParallelTask<S>> initialTaskList = new ArrayList<>();
        createInitialSolutions(populationSize).forEach(solution ->
            initialTaskList.add(ParallelTask.create(createTaskIdentifier(), solution)));

        return initialTaskList;
    }

    /**
     * Creates {@code count} unevaluated solutions for the initial tasks.
     *
     * <p>When {@link WarmStart#FILE} exists and the problem implements {@link WarmStartCapable},
     * the solutions are those of {@link WarmStartCapable#createInitialPopulationFromFile(int)},
     * which receives {@code count}, and the file is copied into the traces folder when one is set
     * (see {@link WarmStart#load}). Otherwise they are {@code count} results of
     * {@link Problem#createSolution()}. A warm-start problem may return another number of
     * solutions than requested: the list is used as it is and a warning is logged.
     *
     * <p>This is the extension point for algorithms that do not start from
     * {@link #populationSize} solutions: a subclass whose {@link #createInitialTasks()} calls
     * {@code createInitialSolutions(1)}, for instance, starts a single-solution strategy with the
     * same warm-start support. A subclass that overrides {@code createInitialTasks()}
     * <em>without</em> calling this method (MOEA/D does) bypasses it, and gets neither the warm
     * start nor the copy of the file.
     *
     * @param count number of solutions requested
     * @return the solutions, not yet evaluated
     */
    protected List<S> createInitialSolutions(int count) {
        List<S> loaded = WarmStart.load(problem, count, WarmStart.FILE,
                tracesFolder == null ? null : tracesFolder.toPath());
        if (loaded != null) {
            return loaded;
        }
        List<S> initialPopulation = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> initialPopulation.add(problem.createSolution()));
        return initialPopulation;
    }

    // ── Steady-state step ─────────────────────────────────────────────────────

    @Override
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        S sol = (S) task.getContents().copy();
        archive.add(sol);

        synchronized (population) {
            if (!solutionInThePopulation(sol)) {
                if (population.size() < populationSize) {
                    population.add(sol);
                    populationSignatures.add(solutionKey(sol));
                } else {
                    List<S> offspringPopulation = new ArrayList<>(population);
                    offspringPopulation.add(sol);
                    List<S> newPopulation = new RankingAndCrowdingSelection<>(populationSize, dominanceComparator)
                        .execute(offspringPopulation);
                    population.clear();
                    population.addAll(newPopulation);
                    rebuildPopulationSignatures();
                }
            }
        }
    }

    private static final int MAX_DUPLICATE_RETRIES = 1000;

    @Override
    public ParallelTask<S> createNewTask() {
        synchronized (population) {
            if (population.size() > 2) {
                List<S> parents;
                S sol0, sol1;
                int retries = 0;
                do {
                    parents = selection.execute(population);
                    List<S> offspring = crossover.execute(parents);
                    sol0 = (S) offspring.get(0).copy();
                    sol1 = (S) offspring.get(1).copy();
                    mutation.execute(sol0);
                    mutation.execute(sol1);
                    if (++retries >= MAX_DUPLICATE_RETRIES) {
                        Log.warn("Could not generate non-duplicate solution after "
                                + MAX_DUPLICATE_RETRIES + " retries, using last generated solution");
                        break;
                    }
                } while (solutionInThePopulation(sol0) || solutionInThePopulation(sol1));

                if (JMetalRandom.getInstance().nextInt(0, 1) == 0) {
                    pendingTaskQueue.add(ParallelTask.create(createTaskIdentifier(), sol1));
                    return ParallelTask.create(createTaskIdentifier(), sol0);
                } else {
                    pendingTaskQueue.add(ParallelTask.create(createTaskIdentifier(), sol0));
                    return ParallelTask.create(createTaskIdentifier(), sol1);
                }
            } else {
                return ParallelTask.create(createTaskIdentifier(), problem.createSolution());
            }
        }
    }

    protected boolean solutionInThePopulation(S sol0) {
        return populationSignatures.contains(solutionKey(sol0));
    }

    // ── Changes during the run ────────────────────────────────────────────────

    /**
     * Number of results processed so far: the count the trace files are named after, and the
     * value the termination sees as {@code EVALUATIONS} after each {@link #updateProgress()}.
     * Safe to call from any thread.
     *
     * @return the evaluations processed so far
     */
    public int getEvaluations() {
        return evaluations;
    }

    /**
     * Replaces the variation operators while the run goes on.
     *
     * <p>May be called from any thread. The assignment happens inside
     * {@code synchronized (population)}, the lock under which {@link #createNewTask()} reads the
     * operators, so every task is created with either the old pair or the new one, never with a
     * mix. Tasks created before the call keep their solutions, including the spare child already
     * waiting in {@link #pendingTaskQueue}.
     *
     * <p>The operators must fit the running algorithm's {@code createNewTask()}. This class mates
     * the two parents returned by the selection and keeps the first two children, so a crossover
     * that needs another number of parents or yields fewer than two children makes every later
     * task creation fail. Nothing beyond {@code null} is checked here, because subclasses may use
     * the operators differently.
     *
     * @param crossover the new crossover, or {@code null} only in subclasses whose
     *                  {@code createNewTask()} does not use one
     * @param mutation  the new mutation
     * @throws NullPointerException if {@code mutation} is {@code null}
     */
    public void setVariation(CrossoverOperator<S> crossover, MutationOperator<S> mutation) {
        Objects.requireNonNull(mutation, "mutation must not be null");
        synchronized (population) {
            this.crossover = crossover;
            this.mutation = mutation;
        }
    }

    /**
     * Replaces the stopping criterion while the run goes on.
     *
     * <p>May be called from any thread. The new criterion applies from the next check on: after
     * the next result on the algorithm thread, before the next task creation on a REST thread.
     * Once the algorithm thread has found the old criterion met, or {@link #run()} has finished,
     * a new criterion cannot reopen the run: nothing changes and the call returns {@code false}.
     * The two cannot cross, so a criterion installed with {@code true} is the one the loop checks
     * next. A stop ({@link #requestStop()}) is not covered: callers check
     * {@link #isStopRequested()} themselves.
     *
     * <p>A criterion that is already met stops task creation at once, but the loop only notices
     * it when the next result arrives; if no task is under evaluation at that moment, the run
     * waits until {@link #requestStop()} is called. Callers that change an evaluation budget
     * should therefore only accept one above {@link #getEvaluations()}, and request a stop if the
     * run reaches it while they install it.
     *
     * <p>Only the algorithm sees the change. The evaluation budget used for the progress estimate
     * of the status report is set separately, through
     * {@link es.unex.jdisrest.distributed.rest.MasterFacade#setMaxEvaluations(int)}.
     *
     * @param termination the new stopping criterion
     * @return {@code true} if the criterion was installed, {@code false} if the run had already
     *         ended
     * @throws NullPointerException if {@code termination} is {@code null}
     */
    public boolean setTermination(Termination termination) {
        Objects.requireNonNull(termination, "termination must not be null");
        synchronized (terminationLock) {
            if (runFinished) {
                return false;
            }
            this.termination = termination;
            return true;
        }
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    /**
     * Whether the run must go on: {@link #run()} has not finished, no stop has been requested
     * ({@link #requestStop()}) and {@link #termination} is not met.
     *
     * <p>Called by the algorithm thread after every result, and by REST threads before they
     * create a task and whenever they ask {@link #isFinished()}. The first two checks
     * short-circuit the termination, so once the run has finished or a stop has been requested
     * the termination is no longer consulted. On the algorithm thread a met termination marks
     * the run finished, under the lock {@link #setTermination} takes, so that the last check of
     * the loop and a new criterion cannot cross. Subclasses that override this method should
     * combine their condition with {@code super.stoppingConditionIsNotMet()} to keep these
     * guarantees.
     */
    @Override
    public boolean stoppingConditionIsNotMet() {
        if (runFinished || isStopRequested()) {
            return false;
        }
        if (Thread.currentThread() != algorithmThread) {
            return !termination.isMet(attributes);
        }
        synchronized (terminationLock) {
            if (termination.isMet(attributes)) {
                runFinished = true;
                return false;
            }
            return true;
        }
    }

    /**
     * Checks that the problem's encoding can travel over the wire, records the start time and
     * runs the steady-state loop of {@link SteadyStateAlgorithm#run()}.
     *
     * <p>However the loop ends — termination met, stop requested, interrupted, or an exception —
     * the run is marked finished on the way out, so {@link #isFinished()} is {@code true} from
     * then on and workers are told to stop instead of being handed tasks nobody will collect.
     */
    @Override
    public void run() {
        try {
            algorithmThread = Thread.currentThread();
            // Fail before dispatching anything if the problem's encoding cannot travel
            // over the wire (see SolutionVariables): a clear exception here beats one
            // 500 per task later.
            SolutionVariables.wireEncoding(problem.createSolution());
            initTime = System.currentTimeMillis();
            super.run();
        } finally {
            runFinished = true;
        }
    }

    // ── Result and traces ─────────────────────────────────────────────────────

    @Override
    public List<S> getResult() {
        List<S> feasible = archive.solutions().stream()
            .filter(ConstraintHandling::isFeasible)
            .collect(Collectors.toList());
        if (feasible.isEmpty()) {
            return List.of();
        }
        return SolutionListUtils.distanceBasedSubsetSelection(
            feasible, Math.min(populationSize, feasible.size()));
    }

    /**
     * Writes a trace snapshot every {@code populationSize * traceCadence} evaluations when a
     * traces folder is set: the archive to {@code aVAR_<n>.csv} / {@code aFUN_<n>.csv} and
     * {@link #populationTraceSnapshot()} to {@code VAR_<n>.csv} / {@code FUN_<n>.csv}, where
     * {@code n} is {@link #getEvaluations()}. Called by {@link #updateProgress()} on the
     * algorithm thread.
     */
    public void saveTrace() {
        if (evaluations % (populationSize * traceCadence) == 0 && tracesFolder != null) {
            if (!tracesFolder.exists() && !tracesFolder.mkdirs()) {
                Log.error("Error creating traces folder " + tracesFolder + " — skipping snapshot");
                return;
            }
            Log.info("Population trace saved after " + evaluations + " evaluations in " + tracesFolder + " folder");

            String prefix = tracesFolder + "/";
            // Traces dump the full archive and population (feasible or not).
            // Feasibility filtering is reserved for the final result (see getResult()).
            List<S> archiveSnapshot = new ArrayList<>(archive.solutions());
            List<S> populationSnapshot = populationTraceSnapshot();
            TraceWriter.write(archiveSnapshot,
                    prefix + "aVAR_" + evaluations + ".csv",
                    prefix + "aFUN_" + evaluations + ".csv", ",");
            TraceWriter.write(populationSnapshot,
                    prefix + "VAR_" + evaluations + ".csv",
                    prefix + "FUN_" + evaluations + ".csv", ",");
        }
    }

    /**
     * Returns the solutions written to the {@code VAR_<n>.csv} / {@code FUN_<n>.csv} traces: a
     * copy of the population, taken under the population lock. Subclasses whose population is
     * not the set worth tracing (for instance an algorithm whose result is an archive of its own)
     * override it.
     *
     * <p>Called by {@link #saveTrace()} on the algorithm thread while REST threads may be creating
     * tasks, so an override must copy its set under the lock that guards it.
     *
     * @return a new list, safe to write while the run goes on
     */
    protected List<S> populationTraceSnapshot() {
        synchronized (population) {
            return new ArrayList<>(population);
        }
    }
}

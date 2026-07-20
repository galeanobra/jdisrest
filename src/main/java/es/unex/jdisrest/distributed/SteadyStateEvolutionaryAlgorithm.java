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
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.TraceWriter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * warm-start loading from {@code iVAR.csv}, and the main {@link #run()} loop that drives
 * the algorithm until the stopping criterion is met.
 *
 * <p>Concrete subclasses override {@link #processComputedTask} to implement their specific
 * environmental selection criterion (e.g., crowding-distance ranking for NSGA-II or
 * hypervolume contribution for SMS-EMOA).
 *
 * @param <S> the solution type (e.g., {@code IntegerSolution} or {@code CompositeSolution})
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class SteadyStateEvolutionaryAlgorithm<S extends Solution<?>> extends SteadyStateMaster<ParallelTask<S>, List<S>> {

    protected final Problem<S> problem;
    protected final MutationOperator<S> mutation;
    protected final SelectionOperator<List<S>, List<S>> selection;
    protected final Termination termination;
    protected final int populationSize;
    protected final Map<String, Object> attributes;
    protected final org.uma.jmetal.util.observable.Observable<Map<String, Object>> observable;
    protected CrossoverOperator<S> crossover;
    protected Comparator<S> dominanceComparator;
    protected List<S> population;
    protected int evaluations = 0;
    protected long initTime;
    protected final AtomicLong idCounter = new AtomicLong(0);
    protected Archive<S> archive;
    /** Mirrors the current population variables for O(1) duplicate detection. */
    protected final Set<List<?>> populationSignatures = Collections.synchronizedSet(new HashSet<>());
    protected File tracesFolder;
    /** Number of "generations" (populationSize evaluations each) between trace snapshots. 1 = every gen. */
    protected int traceCadence = 1;

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
     * <p>For {@code IntegerSolution}, {@code variables()} already returns {@code List<Integer>}
     * which has value-based equality — usable directly.
     * <p>For {@code CompositeSolution}, {@code variables()} returns {@code List<Solution<?>>}
     * whose elements use identity equality (jMetal does not override equals/hashCode), so we
     * flatten the component variables into a single {@code List<Integer>} instead.
     */
    @SuppressWarnings("unchecked")
    protected List<Integer> solutionKey(S sol) {
        if (sol instanceof CompositeSolution composite) {
            List<Integer> flat = new ArrayList<>();
            for (Object component : composite.variables()) {
                flat.addAll(((Solution<Integer>) component).variables());
            }
            return flat;
        }
        return (List<Integer>) sol.variables();
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

    @Override
    @SuppressWarnings("unchecked")
    public List<ParallelTask<S>> createInitialTasks() {
        boolean existsIVAR = Files.exists(Path.of("iVAR.csv"));
        List<ParallelTask<S>> initialTaskList = new ArrayList<>();
        List<S> initialPopulation;

        if (existsIVAR && problem instanceof WarmStartCapable<?> warmStart) {
            Log.info("Initial population loaded from iVAR.csv file");
            initialPopulation = ((WarmStartCapable<S>) warmStart).createInitialPopulationFromFile(populationSize);
        } else {
            if (existsIVAR) {
                Log.warn("iVAR.csv found but problem does not implement WarmStartCapable — using random initialization");
            }
            initialPopulation = new ArrayList<>();
            IntStream.range(0, populationSize).forEach(i -> initialPopulation.add(problem.createSolution()));
        }
        initialPopulation.forEach(solution ->
            initialTaskList.add(ParallelTask.create(createTaskIdentifier(), solution)));

        return initialTaskList;
    }

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

    @Override
    public boolean stoppingConditionIsNotMet() {
        return !termination.isMet(attributes);
    }

    @Override
    public void run() {
        initTime = System.currentTimeMillis();
        super.run();
    }

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
            List<S> populationSnapshot;
            synchronized (population) {
                populationSnapshot = new ArrayList<>(population);
            }
            TraceWriter.write(archiveSnapshot,
                    prefix + "aVAR_" + evaluations + ".csv",
                    prefix + "aFUN_" + evaluations + ".csv", ",");
            TraceWriter.write(populationSnapshot,
                    prefix + "VAR_" + evaluations + ".csv",
                    prefix + "FUN_" + evaluations + ".csv", ",");
        }
    }
}

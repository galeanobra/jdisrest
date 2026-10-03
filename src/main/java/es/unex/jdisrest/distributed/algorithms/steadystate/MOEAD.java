package es.unex.jdisrest.distributed.algorithms.steadystate;

import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import org.uma.jmetal.component.catalogue.common.termination.Termination;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.parallel.asynchronous.task.ParallelTask;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;
import org.uma.jmetal.util.errorchecking.Check;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;
import es.unex.jdisrest.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

/**
 * Distributed steady-state MOEA/D with configurable aggregation and limited replacement.
 *
 * <h2>Population and subproblems</h2>
 * <p>Once the population is full, member {@code k} is the current solution of subproblem
 * {@code k}, the one replacement compares against weight vector {@code k}. Every task records the
 * subproblem it was created for: initial task {@code i} is subproblem {@code i}, and offspring
 * belong to the subproblem whose neighbourhood (or, with probability {@code 1 - delta}, the whole
 * population) supplied their parents. While the population fills, the solution of subproblem
 * {@code k} takes slot {@code k} when it is free and otherwise the nearest free one, and the
 * population is put in slot order when the last slot is taken; in the meantime, workers that ask
 * for work before any result has arrived get random solutions. A solution whose variables equal
 * those of a member is not admitted, and the duplicate filter stays exact even when one offspring
 * replaces several neighbours.
 *
 * <h2>Initial population and result</h2>
 * <p>The initial solutions come from
 * {@link SteadyStateEvolutionaryAlgorithm#createInitialSolutions(int)}, so a warm start is used
 * when there is one (solution {@code i} for subproblem {@code i}); an initial solution that
 * duplicates an earlier one is replaced by a random one. {@link #getResult()} returns the feasible
 * solutions of the archive, as the other algorithms do. Every random draw (subproblem, mating
 * scope, parents) comes from {@link JMetalRandom}, so its seed covers MOEA/D too.
 *
 * <h2>Weight vectors</h2>
 * <p>The weight vectors, one per subproblem, come from {@link MOEADWeights}:
 * {@link MOEADWeights.Method#SPREAD} (the default, any population size) or
 * {@link MOEADWeights.Method#LATTICE} (Das and Dennis, only for lattice sizes from three objectives
 * on). Both are deterministic. With two objectives they are the evenly spread vectors jdisrest
 * always used; with three or more objectives SPREAD replaces the unseeded random vectors of
 * jdisrest 1.1, so runs are reproducible but not comparable with runs of that version. When there
 * is a traces folder the vectors are written to {@value #WEIGHTS_FILE} in it, one line per
 * subproblem in population order, as soon as the master is built.
 *
 * <h2>Aggregation</h2>
 * <p>{@link MOEADAggregation} keeps the ideal and nadir points and aggregates the objectives with
 * Tchebycheff, the weighted sum or PBI, optionally normalizing them as jMetal does. Tchebycheff
 * weighs an objective whose weight is 0 by 10<sup>-4</sup>, as jMetal does, instead of ignoring it
 * as jdisrest 1.1 did; this changes the two corner subproblems of every two-objective run.
 *
 * <h2>Validation</h2>
 * <p>Every argument is checked before the REST server starts, so a wrong setting (for instance a
 * population size that is not a lattice size) fails at once instead of leaving a live server
 * behind.
 *
 * <h2>Changes from jdisrest 1.1</h2>
 * <p>Besides the weights and the Tchebycheff weighting above: the warm start is honoured (1.1
 * ignored it), {@code getResult()} filters infeasible solutions out (1.1 returned the archive as
 * it was), the random draws come from {@code JMetalRandom} instead of an unseeded
 * {@code java.util.Random} (the stochastic stream of a run changes), solutions fill the slot of
 * their subproblem (1.1 appended them in arrival order, so slot and weight vector did not match
 * until replacements settled them), and a worker asking for work before the first result gets a
 * random solution instead of an error.
 *
 * @param <S> the solution type
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class MOEAD<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /** Name of the file, in the traces folder, with the weight vectors. */
    public static final String WEIGHTS_FILE = "weights.csv";

    /* --------------- MOEA/D parameters ------------- */
    protected int T;                   // neighborhood size
    protected double delta;            // prob. of neighborhood mating
    protected int maxReplaced;         // max neighbors replaced per evaluation
    protected final MOEADWeights.Method weightMethod; // how lambda is generated
    protected final MOEADAggregation aggregation; // ideal and nadir points, aggregation function
    protected double[][] lambda;       // weight vectors
    protected int[][] neighbor;       // neighborhood indices
    /**
     * No longer used: every draw comes from {@link JMetalRandom}, so that its seed covers MOEA/D.
     * Kept so that subclasses that read it still compile.
     *
     * @deprecated draw from {@code JMetalRandom.getInstance()} instead
     */
    @Deprecated
    protected Random random = new Random();
    protected ConcurrentMap<Long, Integer> taskSubproblemMap = new ConcurrentHashMap<>();
    /**
     * While the population fills, the solution taken for each subproblem, {@code null} while the
     * slot is free; the population is put in this order when the last slot is taken. Guarded by
     * the population lock.
     */
    private final List<S> fillingSlots;

    /**
     * Constructs a MOEA/D master with {@link MOEADWeights.Method#SPREAD} weight vectors and
     * objectives that are not normalized. See
     * {@link #MOEAD(String, int, Problem, int, CrossoverOperator, MutationOperator, Termination, int,
     * double, AggregationFunction, int, MOEADWeights.Method, boolean, SelectionOperator, String)}
     * for the parameters and the exceptions.
     */
    public MOEAD(String host, int port, Problem<S> problem, int populationSize, CrossoverOperator<S> crossover, MutationOperator<S> mutation, Termination termination, int T, double delta, AggregationFunction aggFun, int maxReplacedSolutions, SelectionOperator<List<S>, List<S>> selectionOperator, String tracesFolder) {
        this(host, port, problem, populationSize, crossover, mutation, termination, T, delta, aggFun, maxReplacedSolutions,
             MOEADWeights.Method.SPREAD, false, selectionOperator, tracesFolder);
    }

    /**
     * Constructs a MOEA/D master with every setting explicit.
     *
     * @param host                 hostname or IP address the REST server will advertise
     * @param port                 HTTP port for the REST server
     * @param problem              the optimization problem (evaluation delegated to workers)
     * @param populationSize       number of subproblems, and of weight vectors
     * @param crossover            crossover operator; it must return at least one child
     * @param mutation             mutation operator applied to every child
     * @param termination          stopping criterion (e.g., max evaluations)
     * @param T                    neighbourhood size, in [1, {@code populationSize}]
     * @param delta                probability of mating within the neighbourhood instead of the
     *                             whole population
     * @param aggFun               aggregation function
     * @param maxReplacedSolutions maximum number of neighbours an offspring replaces, in [1, T]
     * @param weightMethod         how the weight vectors are generated (see {@link MOEADWeights})
     * @param normalizeObjectives  whether the aggregation normalizes the objectives with the ideal
     *                             and nadir points (see {@link MOEADAggregation})
     * @param selectionOperator    passed to the base class; MOEA/D selects the parents itself, so
     *                             it is not used and may be {@code null}
     * @param tracesFolder         directory for periodic trace files and {@value #WEIGHTS_FILE},
     *                             or {@code null} to disable both
     * @throws IllegalArgumentException if {@code weightMethod} cannot generate
     *                                  {@code populationSize} vectors for the number of objectives
     *                                  of the problem (see {@link MOEADWeights#check})
     * @throws RuntimeException         (jMetal's {@code NullParameterException},
     *                                  {@code InvalidProbabilityValueException} or
     *                                  {@code InvalidConditionException}) if another argument is
     *                                  {@code null} or out of range; every check runs before the
     *                                  REST server starts
     */
    public MOEAD(String host, int port, Problem<S> problem, int populationSize, CrossoverOperator<S> crossover,
            MutationOperator<S> mutation, Termination termination, int T, double delta, AggregationFunction aggFun,
            int maxReplacedSolutions, MOEADWeights.Method weightMethod, boolean normalizeObjectives,
            SelectionOperator<List<S>, List<S>> selectionOperator, String tracesFolder) {
        // Checked before super(...), which starts the REST server: a wrong argument must not leave
        // a half-built master with a live server that keeps the JVM running.
        Check.notNull(problem, "problem");
        Check.notNull(crossover, "crossover");
        Check.notNull(mutation, "mutation");
        Check.notNull(termination, "termination");
        Check.notNull(aggFun, "aggFun");
        Check.notNull(weightMethod, "weightMethod");
        Check.probabilityIsValid(delta, "delta");
        String reason = MOEADWeights.check(weightMethod, problem.numberOfObjectives(), populationSize);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        Check.that(T >= 1 && T <= populationSize, "T (neighborhood size) = " + T
                + " must be in [1, populationSize = " + populationSize + "]");
        Check.that(maxReplacedSolutions >= 1 && maxReplacedSolutions <= T,
                "maxReplacedSolutions = " + maxReplacedSolutions + " must be in [1, T = " + T + "]");

        super(host, port, problem, populationSize, crossover, mutation, selectionOperator, new DominanceWithConstraintsComparator<>(), termination, tracesFolder);

        this.T = T;
        this.delta = delta;
        this.maxReplaced = maxReplacedSolutions;
        this.weightMethod = weightMethod;
        this.aggregation = new MOEADAggregation(problem.numberOfObjectives(), aggFun, normalizeObjectives);
        this.fillingSlots = new ArrayList<>(Collections.nCopies(populationSize, null));

        this.neighbor = new int[populationSize][T];

        initWeightVectors();
        initNeighborhood();
        saveWeightVectors();
    }

    /**
     * Assigns {@link #lambda}, one weight vector per subproblem, with {@link MOEADWeights}. An
     * override must assign a new {@code populationSize × numberOfObjectives} array: {@code lambda}
     * is not allocated beforehand.
     */
    protected void initWeightVectors() {
        lambda = MOEADWeights.generate(weightMethod, problem.numberOfObjectives(), populationSize);
    }

    /** Writes the weight vectors to the traces folder, if there is one; a failure only warns. */
    private void saveWeightVectors() {
        if (tracesFolder != null) {
            Path file = tracesFolder.toPath().resolve(WEIGHTS_FILE);
            try {
                Files.createDirectories(tracesFolder.toPath());
                Files.writeString(file, MOEADWeights.toCsv(lambda), StandardCharsets.UTF_8);
                Log.info("MOEA/D weight vectors (" + lambda.length + ") written to " + file);
            } catch (IOException e) {
                Log.warn("Could not write the MOEA/D weight vectors to " + file + " (" + e.getMessage()
                        + ") — continuing without them");
            }
        }
    }

    /**
     * Assigns {@link #neighbor}: for each subproblem, the {@link #T} subproblems whose weight
     * vectors are nearest to its own (Euclidean distance), nearest first, itself included.
     */
    protected void initNeighborhood() {
        double[][] dist = new double[populationSize][populationSize];
        int m = problem.numberOfObjectives();
        for (int i = 0; i < populationSize; i++) {
            for (int j = 0; j < populationSize; j++) {
                double sum = 0.0;
                for (int k = 0; k < m; k++) {
                    double d = lambda[i][k] - lambda[j][k];
                    sum += d * d;
                }
                dist[i][j] = Math.sqrt(sum);
            }
        }
        for (int i = 0; i < populationSize; i++) {
            final int row = i;
            neighbor[i] = IntStream.range(0, populationSize).boxed().sorted(Comparator.comparingDouble(j -> dist[row][j])).limit(T).mapToInt(Integer::intValue).toArray();
        }
    }

    /**
     * One task per initial solution of
     * {@link SteadyStateEvolutionaryAlgorithm#createInitialSolutions(int)
     * createInitialSolutions(populationSize)} (the warm start when there is one, random solutions
     * otherwise), task {@code i} for subproblem {@code i}. A solution whose variables equal those
     * of an earlier one is replaced by a random solution, up to 1000
     * times; a duplicate that survives is kept with a warning, and is discarded when its result
     * arrives. A warm start with more solutions than subproblems assigns solution {@code i} to
     * subproblem {@code i % populationSize}; one with fewer leaves the remaining slots to the
     * offspring created later.
     */
    @Override
    public List<ParallelTask<S>> createInitialTasks() {
        List<S> initial = createInitialSolutions(populationSize);
        List<ParallelTask<S>> list = new ArrayList<>(initial.size());
        // Track already-created initial solutions locally; populationSignatures is empty at this
        // point (solutions are not yet evaluated), so we cannot use solutionInThePopulation().
        Set<List<?>> seen = new HashSet<>();
        int keptDuplicates = 0;
        for (int i = 0; i < initial.size(); i++) {
            S s = initial.get(i);
            boolean duplicate = !seen.add(solutionKey(s));
            for (int attempt = 0; duplicate && attempt < MAX_DUPLICATE_RETRIES; attempt++) {
                s = problem.createSolution();
                duplicate = !seen.add(solutionKey(s));
            }
            if (duplicate) {
                keptDuplicates++;
            }

            long id = createTaskIdentifier();
            taskSubproblemMap.put(id, i % populationSize);
            list.add(ParallelTask.create(id, s));
        }
        if (keptDuplicates > 0) {
            Log.warn("MOEAD: " + keptDuplicates + " initial solution(s) still duplicate another one after "
                    + MAX_DUPLICATE_RETRIES + " random replacements — they are discarded when evaluated");
        }
        return list;
    }

    /**
     * Counts the result, adds a copy of it to the archive, updates the ideal and nadir points and,
     * unless the population already holds a solution with the same variables, inserts it: while
     * the population fills, into the slot of its subproblem (or the nearest free one); afterwards,
     * into each of the nearest neighbours of its subproblem (at most {@link #maxReplaced}) whose
     * aggregated value it improves. The duplicate filter is rebuilt after replacements, because
     * one offspring may take several slots and the solutions it evicts may still fill others.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        Integer subProb = taskSubproblemMap.remove(task.getIdentifier());
        if (subProb == null) return; // unknown id

        S offspring = (S) task.getContents().copy();
        archive.add((S) offspring.copy());  // never share an instance with the population

        synchronized (population) {
            aggregation.update(offspring); // ideal and nadir points
            if (!solutionInThePopulation(offspring)) {
                if (population.size() < populationSize) {
                    fill(offspring, subProb);
                } else {
                    int replaced = 0;
                    for (int k : neighbor[subProb]) {
                        if (replaced >= maxReplaced) break;
                        S current = population.get(k);
                        if (aggregationFitness(offspring, lambda[k]) < aggregationFitness(current, lambda[k])) {
                            population.set(k, offspring);
                            replaced++;
                        }
                    }
                    if (replaced > 0) {
                        // A set of keys cannot count how many slots hold each solution: rebuild it.
                        rebuildPopulationSignatures();
                    }
                }
            }
        }
    }

    /**
     * Filling phase: puts {@code solution} in the slot of subproblem {@code subproblem} (or the
     * nearest free one) and, once every slot is taken, reorders the population so that member
     * {@code k} is the solution of subproblem {@code k}. Call under the population lock.
     */
    private void fill(S solution, int subproblem) {
        int slot = fillingSlot(fillingSlots, subproblem, neighbor[subproblem]);
        if (slot < 0) {
            throw new IllegalStateException("MOEAD: no free slot while the population holds "
                    + population.size() + " of " + populationSize + " solutions");
        }
        fillingSlots.set(slot, solution);
        population.add(solution);
        populationSignatures.add(solutionKey(solution));
        if (population.size() == populationSize) {
            population.clear();
            population.addAll(fillingSlots);  // from now on, member k is the solution of subproblem k
        }
    }

    /**
     * The slot a solution created for {@code subproblem} takes while the population fills:
     * {@code subproblem} itself when free, otherwise the first free one among its
     * {@code neighbours} (nearest first), otherwise the lowest free slot.
     *
     * @param slots      the solution of each subproblem so far, {@code null} for a free slot
     * @param subproblem the subproblem the solution was created for
     * @param neighbours the neighbourhood of {@code subproblem}, nearest first
     * @return the slot, or {@code -1} if every slot is taken
     */
    static int fillingSlot(List<?> slots, int subproblem, int[] neighbours) {
        if (slots.get(subproblem) == null) {
            return subproblem;
        }
        for (int k : neighbours) {
            if (slots.get(k) == null) {
                return k;
            }
        }
        return slots.indexOf(null);
    }

    /**
     * A task discarded after repeated failures never reaches {@link #processComputedTask}, which
     * would drop its subproblem entry; drop it here so that the map does not leak.
     */
    @Override
    protected void onTaskDiscarded(ParallelTask<S> task) {
        taskSubproblemMap.remove(task.getIdentifier());
    }

    private static final int MAX_DUPLICATE_RETRIES = 1000;

    /**
     * Creates two offspring for a random subproblem and returns a task for one of them, queueing
     * the other as a spare. With probability {@link #delta}, once the population is full, both
     * parents come from the subproblem's neighbourhood; otherwise from the whole population.
     * Copies of the first two children are mutated (a crossover with a single child mates a
     * second pair of parents, drawn the same way, for the second one; see
     * {@link SteadyStateEvolutionaryAlgorithm#secondChild}); a pair with a duplicate of a member is
     * created again, up to 1000 attempts, after which a warning is logged if the last attempt
     * still produced one. While the population is empty (more workers than initial tasks, or
     * every initial result still on its way) the task holds a random solution instead.
     */
    @Override
    @SuppressWarnings("unchecked")
    public ParallelTask<S> createNewTask() {
        // Synchronize on population for two reasons:
        // 1. processComputedTask() modifies population under this lock; compound operations
        //    (size check → get) must be atomic to avoid IndexOutOfBoundsException.
        // 2. Consistent with SteadyStateEvolutionaryAlgorithm.createNewTask() which also synchronizes.
        JMetalRandom rnd = JMetalRandom.getInstance();
        final int subP;
        final S child0;
        final S child1;
        synchronized (population) {
            subP = rnd.nextInt(0, populationSize - 1);
            if (population.isEmpty()) {
                // No parent yet: a random solution, as the base class does before it can mate.
                long id = createTaskIdentifier();
                taskSubproblemMap.put(id, subP);
                return ParallelTask.create(id, problem.createSolution());
            }
            S c0, c1;
            boolean duplicate;
            int attempts = 0;
            do {
                List<S> children = mate(subP, rnd);
                c0 = (S) child(children, 0).copy();
                c1 = (S) secondChild(children, () -> mate(subP, rnd)).copy();
                mutation.execute(c0);
                mutation.execute(c1);
                duplicate = solutionInThePopulation(c0) || solutionInThePopulation(c1);
            } while (duplicate && ++attempts < MAX_DUPLICATE_RETRIES);
            if (duplicate) {
                Log.warn("MOEAD: Could not generate non-duplicate solution after "
                        + MAX_DUPLICATE_RETRIES + " retries, using last generated solution");
            }
            child0 = c0;
            child1 = c1;
        }

        long id0 = createTaskIdentifier();
        long id1 = createTaskIdentifier();
        taskSubproblemMap.put(id0, subP);
        taskSubproblemMap.put(id1, subP);
        if (JMetalRandom.getInstance().nextInt(0, 1) == 0) {
            pendingTaskQueue.add(ParallelTask.create(id1, child1));
            return ParallelTask.create(id0, child0);
        } else {
            pendingTaskQueue.add(ParallelTask.create(id0, child0));
            return ParallelTask.create(id1, child1);
        }
    }

    /**
     * Draws two parents for subproblem {@code subP} and mates them: with probability
     * {@link #delta}, once the population is full, from the subproblem's neighbourhood; otherwise
     * from the whole population. Called under the population lock, with a non-empty population.
     */
    private List<S> mate(int subP, JMetalRandom rnd) {
        List<S> parents = new ArrayList<>(2);
        // Use neighborhood mating only when population is fully initialized; neighbor
        // indices span [0, populationSize-1] so population.size() must be >= populationSize
        // to guarantee all indices are valid (>= T was insufficient during filling phase).
        if (rnd.nextDouble() < delta && population.size() >= populationSize) {
            int[] neigh = neighbor[subP];
            while (parents.size() < 2) {
                parents.add(population.get(neigh[rnd.nextInt(0, T - 1)]));
            }
        } else {
            while (parents.size() < 2) {
                parents.add(population.get(rnd.nextInt(0, population.size() - 1)));
            }
        }
        return crossover.execute(parents);
    }

    /** The aggregated value of {@code s} for the weight vector {@code w} (see {@link MOEADAggregation}). */
    protected double aggregationFitness(S s, double[] w) {
        return aggregation.fitness(s.objectives(), w);
    }

    /**
     * Replaces the MOEA/D settings that can change while the run goes on: the variation operators,
     * the neighbourhood selection probability, the maximum number of replaced solutions, the
     * aggregation function and whether it normalizes the objectives. The weight vectors, the
     * neighbourhoods, T and the population size stay as they are. Every argument is checked before
     * anything changes, so a wrong value leaves the run as it was; the ideal and nadir points are
     * kept, so normalization can be switched on mid-run.
     *
     * @param crossover                        the new crossover operator
     * @param mutation                         the new mutation operator
     * @param neighborhoodSelectionProbability the new probability of mating within the
     *                                         neighbourhood (delta)
     * @param maxReplacedSolutions             the new maximum number of replaced neighbours, in
     *                                         [1, T]
     * @param aggregationFunction              the new aggregation function
     * @param normalizeObjectives              whether the aggregation normalizes the objectives
     *                                         from now on
     * @throws RuntimeException (jMetal's {@code NullParameterException},
     *                          {@code InvalidProbabilityValueException} or
     *                          {@code InvalidConditionException}) if an argument is {@code null} or
     *                          out of range
     */
    public void reconfigure(CrossoverOperator<S> crossover, MutationOperator<S> mutation,
            double neighborhoodSelectionProbability, int maxReplacedSolutions, AggregationFunction aggregationFunction,
            boolean normalizeObjectives) {
        Check.notNull(crossover, "crossover");
        Check.notNull(mutation, "mutation");
        Check.probabilityIsValid(neighborhoodSelectionProbability, "neighborhoodSelectionProbability");
        Check.that(maxReplacedSolutions >= 1 && maxReplacedSolutions <= T,
                "maxReplacedSolutions = " + maxReplacedSolutions + " must be in [1, T = " + T + "]");
        Check.notNull(aggregationFunction, "aggregationFunction");
        // Task creation and result processing read these settings under the same lock.
        synchronized (population) {
            setVariation(crossover, mutation);
            this.delta = neighborhoodSelectionProbability;
            this.maxReplaced = maxReplacedSolutions;
            aggregation.configure(aggregationFunction, normalizeObjectives);
        }
    }

    /* ---------------- public config --------------- */
    public enum AggregationFunction {TCHEBYCHEFF, WSUM, PBI}
}

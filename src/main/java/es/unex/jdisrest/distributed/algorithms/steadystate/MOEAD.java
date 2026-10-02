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
 * Distributed steady‑state MOEA/D with configurable aggregation and limited replacement.
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
    protected Random random = new Random();
    protected ConcurrentMap<Long, Integer> taskSubproblemMap = new ConcurrentHashMap<>();

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
     * Neighborhood.
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

    @Override
    public List<ParallelTask<S>> createInitialTasks() {
        List<ParallelTask<S>> list = new ArrayList<>();
        // Track already-created initial solutions locally; populationSignatures is empty at this
        // point (solutions are not yet evaluated), so we cannot use solutionInThePopulation().
        Set<List<?>> seen = new HashSet<>();
        for (int i = 0; i < populationSize; i++) {
            S s;
            int retries = 0;
            do {
                s = problem.createSolution();
            } while (!seen.add(solutionKey(s)) && ++retries < MAX_DUPLICATE_RETRIES);

            long id = createTaskIdentifier();
            taskSubproblemMap.put(id, i);
            list.add(ParallelTask.create(id, s));
        }
        return list;
    }

    @Override
    public void processComputedTask(ParallelTask<S> task) {
        evaluations++;
        Integer subProb = taskSubproblemMap.remove(task.getIdentifier());
        if (subProb == null) return; // unknown id

        S offspring = (S) task.getContents().copy();
        archive.add(offspring);

        synchronized (population) {
            aggregation.update(offspring); // ideal and nadir points
            if (!solutionInThePopulation(offspring)) {
                if (population.size() < populationSize) {
                    population.add(offspring); // filling phase
                    populationSignatures.add(solutionKey(offspring));
                } else {
                    int replaced = 0;
                    for (int k : neighbor[subProb]) {
                        if (replaced >= maxReplaced) break;
                        S current = population.get(k);
                        if (aggregationFitness(offspring, lambda[k]) < aggregationFitness(current, lambda[k])) {
                            populationSignatures.remove(solutionKey(current));
                            population.set(k, offspring);
                            populationSignatures.add(solutionKey(offspring));
                            replaced++;
                        }
                    }
                }
            }
        }
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

    @Override
    public ParallelTask<S> createNewTask() {
        // Synchronize on population for two reasons:
        // 1. processComputedTask() modifies population under this lock; compound operations
        //    (size check → get) must be atomic to avoid IndexOutOfBoundsException.
        // 2. Consistent with SteadyStateEvolutionaryAlgorithm.createNewTask() which also synchronizes.
        final int subP;
        final S child0;
        final S child1;
        synchronized (population) {
            subP = random.nextInt(populationSize);
            S c0 = null, c1 = null;
            int retries = 0;
            do {
                List<S> parents = new ArrayList<>(2);
                // Use neighborhood mating only when population is fully initialized; neighbor
                // indices span [0, populationSize-1] so population.size() must be >= populationSize
                // to guarantee all indices are valid (>= T was insufficient during filling phase).
                if (random.nextDouble() < delta && population.size() >= populationSize) {
                    int[] neigh = neighbor[subP];
                    while (parents.size() < 2) {
                        parents.add(population.get(neigh[JMetalRandom.getInstance().nextInt(0, T - 1)]));
                    }
                } else {
                    while (parents.size() < 2) {
                        parents.add(population.get(random.nextInt(Math.max(1, population.size()))));
                    }
                }

                List<S> children = crossover.execute(parents);
                while (children.size() < 2) children.add((S) children.get(0).copy());
                c0 = (S) children.get(0).copy();
                c1 = (S) children.get(1).copy();
                mutation.execute(c0);
                mutation.execute(c1);
                if (++retries >= MAX_DUPLICATE_RETRIES) {
                    Log.warn("MOEAD: Could not generate non-duplicate solution after "
                            + MAX_DUPLICATE_RETRIES + " retries, using last generated solution");
                    break;
                }
            } while (solutionInThePopulation(c0) || solutionInThePopulation(c1));
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

    @Override
    public List<S> getResult() {
        return new ArrayList<>(archive.solutions());
    }

    /* ---------------- public config --------------- */
    public enum AggregationFunction {TCHEBYCHEFF, WSUM, PBI}
}

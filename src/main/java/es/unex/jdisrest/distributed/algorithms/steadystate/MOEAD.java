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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

/**
 * Distributed steady‑state MOEA/D with configurable aggregation and limited replacement.
  * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class MOEAD<S extends Solution<?>> extends SteadyStateEvolutionaryAlgorithm<S> {

    /* --------------- MOEA/D parameters ------------- */
    protected int T;                   // neighborhood size
    protected double delta;            // prob. of neighborhood mating
    protected AggregationFunction agg; // aggregation function
    protected int maxReplaced;         // max neighbors replaced per evaluation
    protected double[][] lambda;       // weight vectors
    protected int[][] neighbor;       // neighborhood indices
    protected double[] idealPoint;     // z*
    protected Random random = new Random();
    protected ConcurrentMap<Long, Integer> taskSubproblemMap = new ConcurrentHashMap<>();

    public MOEAD(String host, int port, Problem<S> problem, int populationSize, CrossoverOperator<S> crossover, MutationOperator<S> mutation, Termination termination, int T, double delta, AggregationFunction aggFun, int maxReplacedSolutions, SelectionOperator<List<S>, List<S>> selectionOperator, String tracesFolder) {

        super(host, port, problem, populationSize, crossover, mutation, selectionOperator, new DominanceWithConstraintsComparator<>(), termination, tracesFolder);

        Check.that(T <= populationSize, "T (neighborhood size) must be ≤ populationSize");
        Check.that(maxReplacedSolutions >= 1 && maxReplacedSolutions <= T, "maxReplacedSolutions must be in [1,T]");

        this.T = T;
        this.delta = delta;
        this.agg = aggFun;
        this.maxReplaced = maxReplacedSolutions;

        this.lambda = new double[populationSize][problem.numberOfObjectives()];
        this.neighbor = new int[populationSize][T];
        this.idealPoint = new double[problem.numberOfObjectives()];
        Arrays.fill(idealPoint, Double.POSITIVE_INFINITY);

        initWeightVectors();
        initNeighborhood();
    }

    /**
     * Regular spread for 2 objectives; random simplex sampling for ≥3.
     */
    protected void initWeightVectors() {
        int m = problem.numberOfObjectives();
        if (m == 2) {
            for (int i = 0; i < populationSize; i++) {
                double a = (double) i / (populationSize - 1);
                lambda[i][0] = a;
                lambda[i][1] = 1.0 - a;
            }
        } else {
            for (int i = 0; i < populationSize; i++) {
                double sum = 0.0;
                for (int j = 0; j < m; j++) {
                    lambda[i][j] = random.nextDouble();
                    sum += lambda[i][j];
                }
                for (int j = 0; j < m; j++) {
                    lambda[i][j] /= sum;
                }
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
        Set<List<Integer>> seen = new HashSet<>();
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

        // update z*
        for (int i = 0; i < idealPoint.length; i++) {
            idealPoint[i] = Math.min(idealPoint[i], offspring.objectives()[i]);
        }

        synchronized (population) {
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

    /**
     * Aggregation.
     *
     * @param s
     * @param w
     * @return
     */
    protected double aggregationFitness(S s, double[] w) {
        switch (agg) {
            case WSUM: {
                double sum = 0.0;
                for (int i = 0; i < s.objectives().length; i++) {
                    sum += w[i] * s.objectives()[i];
                }
                return sum;
            }
            case PBI: {
                double norm = 0.0;
                for (double v : w) norm += v * v;
                norm = Math.sqrt(norm);

                double d1 = 0.0;
                for (int i = 0; i < s.objectives().length; i++) {
                    d1 += (s.objectives()[i] - idealPoint[i]) * w[i] / norm;
                }

                double d2 = 0.0;
                for (int i = 0; i < s.objectives().length; i++) {
                    double diff = s.objectives()[i] - (idealPoint[i] + d1 * w[i] / norm);
                    d2 += diff * diff;
                }
                d2 = Math.sqrt(d2);
                double theta = 5.0; // typical value
                return d1 + theta * d2;
            }
            case TCHEBYCHEFF:
            default: {
                double maxFun = -Double.MAX_VALUE;
                for (int i = 0; i < s.objectives().length; i++) {
                    double diff = Math.abs(s.objectives()[i] - idealPoint[i]);
                    maxFun = Math.max(maxFun, w[i] * diff);
                }
                return maxFun;
            }
        }
    }

    @Override
    public List<S> getResult() {
        return new ArrayList<>(archive.solutions());
    }

    /* ---------------- public config --------------- */
    public enum AggregationFunction {TCHEBYCHEFF, WSUM, PBI}
}

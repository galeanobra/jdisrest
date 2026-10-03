package es.unex.jdisrest.distributed.algorithms.steadystate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Weight vectors of MOEA/D: one per subproblem, with non-negative components that add up to 1.
 *
 * <ul>
 *   <li>{@link Method#SPREAD}: any number of vectors. The candidates are the corners of the simplex
 *       (one objective each) and {@value #CANDIDATES_PER_VECTOR} vectors per subproblem, at least
 *       {@value #MINIMUM_CANDIDATES}, drawn uniformly on the simplex with a fixed seed. Starting
 *       with the corners, it picks one after another the candidate farthest from those already
 *       picked (max-min selection). Every run, on every platform, gets the same vectors.</li>
 *   <li>{@link Method#LATTICE}: the simplex lattice of Das and Dennis, every vector whose components
 *       are multiples of 1/H. It only exists for C(H+m-1, m-1) vectors, m being the number of
 *       objectives: for three objectives 66, 91 or 105 (H = 10, 12 or 13), for instance.</li>
 * </ul>
 *
 * <p>With two objectives both give the evenly spread vectors (i/(n-1), 1-i/(n-1)) for any number
 * of vectors, a single vector is the centre of the simplex, and with a single objective every
 * vector is [1.0]. jMetal and Evolver read the vectors of three or more objectives from files
 * instead, which exist for a few sizes only.
 *
 * <h2>Cost and small sizes</h2>
 * <p>SPREAD compares every candidate with every vector picked, so it costs
 * O(n · max({@value #MINIMUM_CANDIDATES}, {@value #CANDIDATES_PER_VECTOR}·n) · m) for n vectors:
 * about 10 ms for 100 vectors, 0.2 s for 1000, 2 s for 3000 and 5 s for 5000, growing
 * quadratically beyond. With fewer vectors than objectives (n &lt; m) it returns only the first n
 * corners, so the remaining objectives have weight 0 in every vector.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public final class MOEADWeights {

    /** How the weight vectors are generated. */
    public enum Method {
        /** Max-min selection among uniform candidates; any number of vectors. */
        SPREAD,
        /** Das and Dennis simplex lattice; only C(H+m-1, m-1) vectors. */
        LATTICE
    }

    static final int CANDIDATES_PER_VECTOR = 50;
    static final int MINIMUM_CANDIDATES = 20_000;
    private static final long SEED = 1L;

    private MOEADWeights() {
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /**
     * Tells why {@link #generate} cannot produce {@code count} vectors of {@code numberOfObjectives}
     * components with {@code method}. Beyond a non-null method, one objective and one vector at
     * least, only the lattice restricts the number of vectors, and only from three objectives on.
     *
     * @param method             how the vectors would be generated
     * @param numberOfObjectives the number of components of every vector
     * @param count              the number of vectors (the MOEA/D population size)
     * @return the reason, naming the valid sizes around {@code count} for the lattice, or
     *         {@code null} if {@link #generate} would succeed
     */
    public static String check(Method method, int numberOfObjectives, int count) {
        String reason = null;
        if (method == null) {
            reason = "the weight method is null";
        } else if (numberOfObjectives < 1) {
            reason = "at least 1 objective is needed, got " + numberOfObjectives;
        } else if (count < 1) {
            reason = "at least 1 weight vector is needed, got " + count;
        } else if (method == Method.LATTICE && numberOfObjectives > 2 && divisions(numberOfObjectives, count) < 0) {
            long below = 1;
            long above = 1;
            for (int h = 0; above < count; h++) {
                below = above;
                above = latticeSize(numberOfObjectives, h + 1);
            }
            reason = "LATTICE weights for " + numberOfObjectives + " objectives need C(H+"
                    + (numberOfObjectives - 1) + ", " + (numberOfObjectives - 1) + ") vectors, such as "
                    + below + " or " + above + ", got " + count;
        }
        return reason;
    }

    // ── Generation ───────────────────────────────────────────────────────────

    /**
     * Generates {@code count} weight vectors of {@code numberOfObjectives} components.
     *
     * @param method             how the vectors are generated
     * @param numberOfObjectives the number of components of every vector, at least 1
     * @param count              the number of vectors, at least 1
     * @return {@code count} new vectors, in subproblem order (the corners first for SPREAD)
     * @throws IllegalArgumentException with the reason of {@link #check} if it reports one
     */
    public static double[][] generate(Method method, int numberOfObjectives, int count) {
        String reason = check(method, numberOfObjectives, count);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        double[][] weights;
        if (numberOfObjectives == 1) {
            weights = new double[count][];
            for (int i = 0; i < count; i++) {
                weights[i] = new double[] {1.0};
            }
        } else if (count == 1) {
            weights = new double[1][numberOfObjectives];
            Arrays.fill(weights[0], 1.0 / numberOfObjectives);
        } else if (numberOfObjectives == 2) {
            weights = new double[count][];
            for (int i = 0; i < count; i++) {
                double a = (double) i / (count - 1);
                weights[i] = new double[] {a, 1.0 - a};
            }
        } else if (method == Method.LATTICE) {
            weights = lattice(numberOfObjectives, divisions(numberOfObjectives, count));
        } else {
            weights = spread(numberOfObjectives, count);
        }
        return weights;
    }

    /**
     * Formats the vectors as CSV: one line per vector, in order, with its components written by
     * {@link Double#toString(double)} and separated by commas; every line ends with {@code \n}.
     *
     * @param weights the weight vectors
     * @return the CSV text
     */
    public static String toCsv(double[][] weights) {
        return Arrays.stream(weights)
                .map(vector -> Arrays.stream(vector).mapToObj(Double::toString).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    // ── Lattice ──────────────────────────────────────────────────────────────

    /** Number of lattice vectors with H divisions: C(H+m-1, m-1). */
    private static long latticeSize(int numberOfObjectives, int divisions) {
        long size = 1;
        for (int k = 1; k < numberOfObjectives; k++) {
            size = size * (divisions + k) / k;
        }
        return size;
    }

    /** The H whose lattice has exactly {@code count} vectors, or -1 if there is none. */
    private static int divisions(int numberOfObjectives, int count) {
        int h = 0;
        while (latticeSize(numberOfObjectives, h) < count) {
            h++;
        }
        return latticeSize(numberOfObjectives, h) == count ? h : -1;
    }

    private static double[][] lattice(int numberOfObjectives, int divisions) {
        List<double[]> vectors = new ArrayList<>();
        addCompositions(new int[numberOfObjectives], 0, divisions, divisions, vectors);
        return vectors.toArray(double[][]::new);
    }

    /** Adds every way of splitting {@code remaining} among the components from {@code index} on. */
    private static void addCompositions(int[] parts, int index, int remaining, int divisions, List<double[]> vectors) {
        if (index == parts.length - 1) {
            parts[index] = remaining;
            vectors.add(Arrays.stream(parts).mapToDouble(part -> (double) part / divisions).toArray());
        } else {
            for (int part = 0; part <= remaining; part++) {
                parts[index] = part;
                addCompositions(parts, index + 1, remaining - part, divisions, vectors);
            }
        }
    }

    // ── Spread ───────────────────────────────────────────────────────────────

    private static double[][] spread(int numberOfObjectives, int count) {
        Random random = new Random(SEED);
        int total = numberOfObjectives + Math.max(MINIMUM_CANDIDATES, CANDIDATES_PER_VECTOR * count);
        double[][] candidates = new double[total][];
        for (int i = 0; i < total; i++) {
            candidates[i] = i < numberOfObjectives ? corner(numberOfObjectives, i) : uniformOnSimplex(numberOfObjectives, random);
        }

        // distance[j]: squared distance from candidate j to the nearest vector picked so far.
        double[] distance = new double[total];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        double[][] weights = new double[count][];
        for (int picked = 0; picked < count; picked++) {
            int next = picked < numberOfObjectives ? picked : farthest(distance);
            weights[picked] = candidates[next];
            for (int j = 0; j < total; j++) {
                distance[j] = Math.min(distance[j], squaredDistance(candidates[j], candidates[next]));
            }
        }
        return weights;
    }

    private static double[] corner(int numberOfObjectives, int objective) {
        double[] vector = new double[numberOfObjectives];
        vector[objective] = 1.0;
        return vector;
    }

    /**
     * Normalized exponential variables are uniform on the simplex. StrictMath gives the same vectors
     * on every platform.
     */
    private static double[] uniformOnSimplex(int numberOfObjectives, Random random) {
        double[] vector = new double[numberOfObjectives];
        double sum = 0.0;
        for (int k = 0; k < numberOfObjectives; k++) {
            vector[k] = -StrictMath.log(1.0 - random.nextDouble());
            sum += vector[k];
        }
        for (int k = 0; k < numberOfObjectives; k++) {
            vector[k] /= sum;
        }
        return vector;
    }

    private static int farthest(double[] distance) {
        int best = 0;
        for (int j = 1; j < distance.length; j++) {
            if (distance[j] > distance[best]) {
                best = j;
            }
        }
        return best;
    }

    private static double squaredDistance(double[] a, double[] b) {
        double sum = 0.0;
        for (int k = 0; k < a.length; k++) {
            double d = a[k] - b[k];
            sum += d * d;
        }
        return sum;
    }
}

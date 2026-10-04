package es.unex.jdisrest.config;

import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEADWeights;
import es.unex.jdisrest.distributed.algorithms.steadystate.PAES;
import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Settings of an NSGA-II, PAES or MOEA/D run, read from a properties file. For a real-coded
 * problem ({@link DoubleProblem}, whose solutions are {@link DoubleSolution}s):
 *
 * <pre>
 * algorithm = nsgaii                # nsgaii, paes or moead
 * maxEvaluations = 25000
 * populationSize = 100              # NSGA-II and MOEA/D; PAES uses archiveSize (default 100 for all)
 * tracesFolder = traces             # optional: no traces when missing
 *
 * crossover = sbx                   # NSGA-II and MOEA/D; see CrossoverType
 * crossover.probability = 0.9
 * crossover.distributionIndex = 20
 *
 * mutation = polynomial             # see MutationType
 * mutation.probability = 1/n        # k/n is k over the number of variables
 * mutation.distributionIndex = 20
 *
 * archiveSelectionProbability = 0.0 # PAES only (see PAES)
 * result = paes                     # PAES only: paes or external (see PAES.ResultSource)
 *
 * weights = spread                       # MOEA/D only: spread or lattice (see MOEADWeights)
 * neighborSize = 20                      # MOEA/D only (see MOEAD); at most populationSize
 * neighborhoodSelectionProbability = 0.9 # MOEA/D only
 * maximumNumberOfReplacedSolutions = 2   # MOEA/D only; at most neighborSize
 * aggregation = tchebycheff              # MOEA/D only: tchebycheff, wsum or pbi
 * normalizeObjectives = false            # MOEA/D only (see MOEADAggregation)
 * </pre>
 *
 * <p>The repository's {@code examples} folder holds a self-documenting file for each algorithm.
 *
 * <h2>Scope</h2>
 * <p>The algorithms are exactly {@code nsgaii}, {@code paes} and {@code moead}, one record each
 * ({@link NSGAIIConfig}, {@link PAESConfig}, {@link MOEADConfig}). The problem's solutions may be
 * {@link DoubleSolution}s, {@code IntegerSolution}s, {@code BinarySolution}s or
 * {@code CompositeSolution}s of those, which {@link SolutionLayout} describes as segments. The
 * operators of each segment come from the catalogues of its encoding: {@link CrossoverType} and
 * {@link MutationType} for real variables, {@link IntegerCrossoverType} and
 * {@link IntegerMutationType} for integer ones, {@link BinaryCrossoverType} and
 * {@link BinaryMutationType} for binary ones. A problem that is not composite uses the keys above,
 * whatever its encoding; the operator keys of a composite one take the name of each segment as
 * prefix, and the keys without a prefix are then unknown:
 *
 * <pre>
 * # an integer segment named ints
 * ints.crossover = blxAlpha
 * ints.crossover.alpha = 0.3
 * ints.mutation = random
 * # n is the size of the segment
 * ints.mutation.probability = 1/n
 *
 * # a binary segment named bits
 * bits.crossover = hux
 * # n counts its bits
 * bits.mutation.probability = 2/n
 * </pre>
 *
 * <p>The notes after the values of the first listing only explain them: in a file a comment takes
 * a line of its own, as in the second, since a {@link java.util.Properties} file reads a
 * {@code #} after a value as part of the value.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Only {@code algorithm} and {@code maxEvaluations} are required. The others default to the
 *       values above: in every segment, a crossover with probability 0.9 and a mutation with
 *       probability 1/n, SBX and polynomial mutation with distribution index 20 for real and
 *       integer variables, single-point crossover and bit-flip mutation for binary ones. With a
 *       population smaller than 20, the MOEA/D neighborhood defaults to the whole population, and
 *       the replaced solutions to at most the neighborhood size.</li>
 *   <li>Every probability can be written as {@code k/n}, for instance {@code 3/n}; the result must
 *       still lie in [0, 1]. In the probability of an operator, n is the size of its segment: its
 *       variables, or its bits for a binary segment, so 1/n is 1 in a segment of one variable. In
 *       the other probabilities, n is the sum of those sizes ({@link SolutionLayout}): for a
 *       real-coded problem, both are the number of variables of the problem.</li>
 *   <li>Names of algorithms and operators are case-insensitive, and spaces around values are
 *       ignored. Numbers are plain decimals ({@code 0.5}, {@code 1e-3}).</li>
 *   <li>A key that the chosen algorithm or operators do not use is an error, so that a misspelt
 *       key is never silently ignored. A key written twice is not detected: as in any
 *       {@link java.util.Properties} file, the last occurrence wins.</li>
 *   <li>Files are read as UTF-8; a leading byte order mark is ignored.</li>
 *   <li>In a properties file a backslash starts an escape sequence ({@code \t} is a tab), so a
 *       Windows path must be written with {@code /} or with doubled backslashes
 *       ({@code C:/runs/traces} or {@code C:\\runs\\traces}). A {@code tracesFolder} that can
 *       only come from single backslashes (one with a control character, or a drive letter not
 *       followed by a separator, such as {@code C:runs}) is rejected. A {@code key=value} override
 *       is taken literally.</li>
 * </ul>
 *
 * <h2>Validation</h2>
 * <p>Every value is checked before anything starts: ranges, the rules of each operator, the
 * operator constructors themselves (each operator is built once while parsing) and, with the
 * methods that take the problem, the MOEA/D lattice size against the number of objectives. A
 * configuration that loads therefore builds its algorithm, and the error of one that does not
 * names the key. The overloads that take only the number of variables or the layout check
 * everything but the lattice size, which the MOEA/D constructor checks again before its server
 * starts.
 *
 * <p>The methods that take a problem read its layout with {@link SolutionLayout#of}: a
 * {@link DoubleProblem} draws no random number, as in 1.2, while any other problem creates one
 * solution, which draws the random numbers of its initial values.
 *
 * <h2>API</h2>
 * <p>The public statics are the ones a launcher or a custom
 * {@link es.unex.jdisrest.distributed.rest.ConfigurationHandler} needs: {@link #load} for a file
 * and its command-line overrides, {@link #parseText} for the body of {@code POST /api/v1/config},
 * {@link #fixedDuringRun} to reject changes a running algorithm cannot take, and
 * {@link #writeCopy} to record the configuration in the traces folder. The helpers behind them
 * are package-private. The records are public so that a launcher can build the algorithm from
 * their values, with {@link Variation#createCrossover()} and {@link Variation#createMutation()};
 * their constructors check no value, only that each segment is given a mutation, so build them
 * through these methods.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public sealed interface AlgorithmConfig {

    /**
     * Evaluations after which the algorithm stops.
     *
     * @return the budget, a positive integer
     */
    int maxEvaluations();

    /**
     * Folder for the periodic trace files.
     *
     * @return the folder as written in the file (relative paths are relative to the working
     *         directory), or {@code null} when the run writes no traces
     */
    String tracesFolder();

    /**
     * The operators of each segment of the solutions: the crossover (none for PAES) and the
     * mutation, which build the operators of the run.
     *
     * @return the operators
     */
    Variation variation();

    /**
     * The layout the configuration was read for, which decided its operator keys and the
     * {@code n} of its {@code k/n} probabilities.
     *
     * @return the layout of {@link #variation()}
     */
    default SolutionLayout layout() {
        return variation().layout();
    }

    /**
     * One-line summary for logs and answers, with every value the run uses.
     *
     * @return for instance {@code NSGA-II, 25000 evaluations, population 100, crossover sbx
     *         (probability 0.9, distributionIndex 20), ...}, or for a composite problem
     *         {@code ..., population 100, ints [crossover sbx (...), mutation polynomial (...)],
     *         bits [...], ...}
     */
    String describe();

    // ── Reading ───────────────────────────────────────────────────────────────

    /**
     * Reads a configuration file for a real-coded problem, checking everything that depends on it:
     * the number of variables ({@code k/n} probabilities, the n-point crossover) and the number of
     * objectives (the MOEA/D lattice size). This is what a launcher should call, so that a file
     * it accepts is one the algorithm accepts. It creates no solution of the problem.
     *
     * @param file      the properties file, read as UTF-8
     * @param overrides {@code key=value} entries that replace or add keys of the file, taken
     *                  literally (no escape sequences)
     * @param problem   the problem the run optimizes
     * @return the configuration
     * @throws InvalidConfigurationException if the file cannot be read, an override is not
     *                                       {@code key=value}, or a value is missing or wrong
     */
    static AlgorithmConfig load(Path file, List<String> overrides, DoubleProblem problem) {
        AlgorithmConfig config = load(file, overrides, problem.numberOfVariables());
        AlgorithmConfigParser.checkObjectives(config, problem.numberOfObjectives());
        return config;
    }

    /**
     * Reads a configuration file, checking everything that depends on the number of variables but
     * not the MOEA/D lattice size, which depends on the number of objectives (see
     * {@link #load(Path, List, DoubleProblem)}).
     *
     * @param file              the properties file, read as UTF-8
     * @param overrides         {@code key=value} entries that replace or add keys of the file,
     *                          taken literally (no escape sequences)
     * @param numberOfVariables number of variables of the problem, which {@code k/n} refers to
     * @return the configuration
     * @throws InvalidConfigurationException if the file cannot be read, an override is not
     *                                       {@code key=value}, or a value is missing or wrong
     * @throws IllegalArgumentException      if {@code numberOfVariables} is not positive
     */
    static AlgorithmConfig load(Path file, List<String> overrides, int numberOfVariables) {
        return AlgorithmConfigParser.parse(AlgorithmConfigParser.read(file, overrides), numberOfVariables);
    }

    /**
     * Reads a configuration file for a problem of any encoding, checking everything that depends
     * on it: the layout of its solutions ({@link SolutionLayout#of}), which decides the operator
     * keys, their catalogues, the {@code k/n} probabilities and the operator checks, and the number
     * of objectives (the MOEA/D lattice size). A {@link DoubleProblem} gives the configuration of
     * {@link #load(Path, List, DoubleProblem)}; any other problem creates one solution to read its
     * layout.
     *
     * @param file      the properties file, read as UTF-8
     * @param overrides {@code key=value} entries that replace or add keys of the file, taken
     *                  literally (no escape sequences)
     * @param problem   the problem the run optimizes
     * @return the configuration
     * @throws InvalidConfigurationException if the file cannot be read, an override is not
     *                                       {@code key=value}, or a value is missing or wrong
     * @throws IllegalArgumentException      if the problem's solutions cannot be configured (see
     *                                       {@link SolutionLayout#of})
     */
    static AlgorithmConfig load(Path file, List<String> overrides, Problem<?> problem) {
        AlgorithmConfig config = load(file, overrides, SolutionLayout.of(problem));
        AlgorithmConfigParser.checkObjectives(config, problem.numberOfObjectives());
        return config;
    }

    /**
     * Reads a configuration file for the segments of a problem's solutions, checking everything
     * that depends on them but not the MOEA/D lattice size, which depends on the number of
     * objectives (see {@link #load(Path, List, Problem)}).
     *
     * @param file      the properties file, read as UTF-8
     * @param overrides {@code key=value} entries that replace or add keys of the file, taken
     *                  literally (no escape sequences)
     * @param layout    the segments of the solutions, which decide the operator keys and what
     *                  {@code k/n} refers to
     * @return the configuration
     * @throws InvalidConfigurationException if the file cannot be read, an override is not
     *                                       {@code key=value}, or a value is missing or wrong
     */
    static AlgorithmConfig load(Path file, List<String> overrides, SolutionLayout layout) {
        return AlgorithmConfigParser.parse(AlgorithmConfigParser.read(file, overrides), layout);
    }

    /**
     * Reads a configuration from the text of a properties file, for instance the body of
     * {@code POST /api/v1/config}, checking everything that depends on the real-coded problem
     * (see {@link #load(Path, List, DoubleProblem)}).
     *
     * @param text    the text of a complete properties file: keys left out take their defaults
     * @param problem the problem the run optimizes
     * @return the configuration
     * @throws InvalidConfigurationException if a value is missing or wrong
     */
    static AlgorithmConfig parseText(String text, DoubleProblem problem) {
        AlgorithmConfig config = parseText(text, problem.numberOfVariables());
        AlgorithmConfigParser.checkObjectives(config, problem.numberOfObjectives());
        return config;
    }

    /**
     * Reads a configuration from the text of a properties file, checking everything that depends
     * on the number of variables but not the MOEA/D lattice size (see
     * {@link #parseText(String, DoubleProblem)}).
     *
     * @param text              the text of a complete properties file: keys left out take their
     *                          defaults
     * @param numberOfVariables number of variables of the problem, which {@code k/n} refers to
     * @return the configuration
     * @throws InvalidConfigurationException if a value is missing or wrong
     * @throws IllegalArgumentException      if {@code numberOfVariables} is not positive
     */
    static AlgorithmConfig parseText(String text, int numberOfVariables) {
        return AlgorithmConfigParser.parse(AlgorithmConfigParser.read(text), numberOfVariables);
    }

    /**
     * Reads a configuration from the text of a properties file, checking everything that depends
     * on the problem, whatever its encoding (see {@link #load(Path, List, Problem)}).
     *
     * @param text    the text of a complete properties file: keys left out take their defaults
     * @param problem the problem the run optimizes
     * @return the configuration
     * @throws InvalidConfigurationException if a value is missing or wrong
     * @throws IllegalArgumentException      if the problem's solutions cannot be configured (see
     *                                       {@link SolutionLayout#of})
     */
    static AlgorithmConfig parseText(String text, Problem<?> problem) {
        AlgorithmConfig config = parseText(text, SolutionLayout.of(problem));
        AlgorithmConfigParser.checkObjectives(config, problem.numberOfObjectives());
        return config;
    }

    /**
     * Reads a configuration from the text of a properties file for the segments of a problem's
     * solutions, checking everything that depends on them but not the MOEA/D lattice size (see
     * {@link #parseText(String, Problem)}).
     *
     * @param text   the text of a complete properties file: keys left out take their defaults
     * @param layout the segments of the solutions, which decide the operator keys and what
     *               {@code k/n} refers to
     * @return the configuration
     * @throws InvalidConfigurationException if a value is missing or wrong
     */
    static AlgorithmConfig parseText(String text, SolutionLayout layout) {
        return AlgorithmConfigParser.parse(AlgorithmConfigParser.read(text), layout);
    }

    // ── Runs ──────────────────────────────────────────────────────────────────

    /**
     * Writes the configuration of a run, a file and its overrides, into a folder that it creates
     * if needed, so that reading the copy gives the configuration the run used.
     *
     * <p>The copy has the name of the file and its text with exactly one active line per key: the
     * lines of the file that an override replaces, or that a later line of the file sets again,
     * are commented out where they are ({@code # overridden on the command line: maxEvaluations
     * = 25000}), and the overrides are appended at the end. Editing the value where a key appears
     * in the copy therefore changes that key.
     *
     * <p>When the file is already in the folder (a run started from a copy in its traces folder),
     * the file is never rewritten: if its text is already the configuration of the run, nothing
     * is written and the file is the copy; otherwise the copy goes to
     * {@code <name>_0.properties} ({@code <name>_0_2.properties} and so on if that exists too).
     *
     * @param file      the configuration file, read as UTF-8
     * @param overrides the {@code key=value} entries the run was given, already validated by
     *                  {@link #load}
     * @param folder    the folder for the copy
     * @return the path of the copy
     * @throws IOException if the file cannot be read or the copy cannot be written
     */
    static Path writeCopy(Path file, List<String> overrides, Path folder) throws IOException {
        return ConfigHistory.writeCopy(file, AlgorithmConfigParser.textWithOverrides(file, overrides), folder);
    }

    /**
     * The settings that {@code next} changes but that are fixed while a run goes on: the
     * algorithm, the population or archive size (which shape the population, the archive and the
     * trace cadence), the MOEA/D weight vectors and neighborhood size (which shape the
     * subproblems and their neighborhoods) and the traces folder. Everything else can change.
     * Two spellings of the same traces folder, such as {@code traces}, {@code ./traces} and
     * {@code traces/}, are the same folder.
     *
     * @param current the configuration in use
     * @param next    the configuration to apply
     * @return one message per forbidden change, empty if {@code next} can be applied
     */
    static List<String> fixedDuringRun(AlgorithmConfig current, AlgorithmConfig next) {
        List<String> problems = new ArrayList<>();
        if (current.getClass() != next.getClass()) {
            problems.add("algorithm cannot change during a run (" + name(current) + " to " + name(next) + ")");
        } else if (current instanceof NSGAIIConfig c && next instanceof NSGAIIConfig n
                && c.populationSize() != n.populationSize()) {
            problems.add("populationSize cannot change during a run (" + c.populationSize() + " to "
                    + n.populationSize() + ")");
        } else if (current instanceof PAESConfig c && next instanceof PAESConfig n
                && c.archiveSize() != n.archiveSize()) {
            problems.add("archiveSize cannot change during a run (" + c.archiveSize() + " to " + n.archiveSize() + ")");
        } else if (current instanceof MOEADConfig c && next instanceof MOEADConfig n) {
            if (c.populationSize() != n.populationSize()) {
                problems.add("populationSize cannot change during a run (" + c.populationSize() + " to "
                        + n.populationSize() + ")");
            }
            if (c.weights() != n.weights()) {
                problems.add("weights cannot change during a run (" + MOEADConfig.name(c.weights()) + " to "
                        + MOEADConfig.name(n.weights()) + ")");
            }
            if (c.neighborSize() != n.neighborSize()) {
                problems.add("neighborSize cannot change during a run (" + c.neighborSize() + " to "
                        + n.neighborSize() + ")");
            }
        }
        if (!sameFolder(current.tracesFolder(), next.tracesFolder())) {
            problems.add("tracesFolder cannot change during a run (" + Objects.requireNonNullElse(current.tracesFolder(),
                    "none") + " to " + Objects.requireNonNullElse(next.tracesFolder(), "none") + ")");
        }
        return problems;
    }

    private static boolean sameFolder(String first, String second) {
        return first == null || second == null
                ? first == second
                : Path.of(first).toAbsolutePath().normalize().equals(Path.of(second).toAbsolutePath().normalize());
    }

    private static String name(AlgorithmConfig config) {
        return switch (config) {
            case NSGAIIConfig _ -> "nsgaii";
            case PAESConfig _ -> "paes";
            case MOEADConfig _ -> "moead";
        };
    }

    private static String traces(String folder) {
        return folder == null ? ", no traces" : ", traces in " + folder;
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /**
     * NSGA-II settings.
     *
     * @param maxEvaluations evaluations after which the run stops
     * @param populationSize number of solutions of the population
     * @param variation      the crossover and the mutation of each segment
     * @param tracesFolder   folder for the trace files, or {@code null} for none
     */
    record NSGAIIConfig(int maxEvaluations, int populationSize, Variation variation, String tracesFolder)
            implements AlgorithmConfig {

        /**
         * The settings of a real-coded problem that is not composite, with the components of 1.2.
         * The record does not know the number of variables the operators were chosen for, so its
         * {@link #layout()} has a real segment of size 1; the methods that take a
         * {@link DoubleProblem} read the number from the problem, as in 1.2.
         *
         * @param maxEvaluations evaluations after which the run stops
         * @param populationSize number of solutions of the population
         * @param crossover      the crossover operator
         * @param mutation       the mutation operator
         * @param tracesFolder   folder for the trace files, or {@code null} for none
         */
        public NSGAIIConfig(int maxEvaluations, int populationSize,
                OperatorConfig<CrossoverOperator<DoubleSolution>> crossover,
                OperatorConfig<MutationOperator<DoubleSolution>> mutation, String tracesFolder) {
            this(maxEvaluations, populationSize, Variation.real(crossover, mutation), tracesFolder);
        }

        /**
         * The crossover of a real-coded problem that is not composite, as in 1.2.
         *
         * @return the crossover operator
         * @throws IllegalStateException if the problem is composite or its variables are not real:
         *                               use {@link #variation()}
         */
        public OperatorConfig<CrossoverOperator<DoubleSolution>> crossover() {
            return variation.realCrossover();
        }

        /**
         * The mutation of a real-coded problem that is not composite, as in 1.2.
         *
         * @return the mutation operator
         * @throws IllegalStateException if the problem is composite or its variables are not real:
         *                               use {@link #variation()}
         */
        public OperatorConfig<MutationOperator<DoubleSolution>> mutation() {
            return variation.realMutation();
        }

        @Override
        public String describe() {
            return "NSGA-II, " + maxEvaluations + " evaluations, population " + populationSize
                    + ", " + variation.describe() + traces(tracesFolder);
        }
    }

    /**
     * PAES settings. The PAES archive is a crowding-distance archive of {@code archiveSize}
     * solutions.
     *
     * @param maxEvaluations              evaluations after which the run stops
     * @param archiveSize                 maximum number of solutions of the PAES archive
     * @param variation                   the mutation of each segment, the only variation of PAES
     *                                    (the segments have no crossover)
     * @param archiveSelectionProbability probability of mutating a random archive member instead
     *                                    of the current solution (0 for the classic rule)
     * @param resultSource                the archive the run returns
     * @param tracesFolder                folder for the trace files, or {@code null} for none
     */
    record PAESConfig(int maxEvaluations, int archiveSize, Variation variation,
            double archiveSelectionProbability, PAES.ResultSource resultSource,
            String tracesFolder) implements AlgorithmConfig {

        /**
         * The settings of a real-coded problem that is not composite, with the components of 1.2.
         * The record does not know the number of variables the mutation was chosen for, so its
         * {@link #layout()} has a real segment of size 1; the methods that take a
         * {@link DoubleProblem} read the number from the problem, as in 1.2.
         *
         * @param maxEvaluations              evaluations after which the run stops
         * @param archiveSize                 maximum number of solutions of the PAES archive
         * @param mutation                    the mutation operator, the only variation of PAES
         * @param archiveSelectionProbability probability of mutating a random archive member
         *                                    instead of the current solution (0 for the classic
         *                                    rule)
         * @param resultSource                the archive the run returns
         * @param tracesFolder                folder for the trace files, or {@code null} for none
         */
        public PAESConfig(int maxEvaluations, int archiveSize, OperatorConfig<MutationOperator<DoubleSolution>> mutation,
                double archiveSelectionProbability, PAES.ResultSource resultSource, String tracesFolder) {
            this(maxEvaluations, archiveSize, Variation.real(null, mutation), archiveSelectionProbability,
                    resultSource, tracesFolder);
        }

        /**
         * The mutation of a real-coded problem that is not composite, as in 1.2.
         *
         * @return the mutation operator
         * @throws IllegalStateException if the problem is composite or its variables are not real:
         *                               use {@link #variation()}
         */
        public OperatorConfig<MutationOperator<DoubleSolution>> mutation() {
            return variation.realMutation();
        }

        @Override
        public String describe() {
            return "PAES, " + maxEvaluations + " evaluations, archive " + archiveSize
                    + ", " + variation.describe()
                    + ", archive selection probability " + OperatorConfig.format(archiveSelectionProbability)
                    + ", result " + (resultSource == PAES.ResultSource.PAES_ARCHIVE ? "paes" : "external")
                    + traces(tracesFolder);
        }
    }

    /**
     * MOEA/D settings. Each of the {@code populationSize} subproblems has a weight vector and a
     * neighborhood of the {@code neighborSize} subproblems with the closest weights (see
     * {@link MOEAD}).
     *
     * @param maxEvaluations                   evaluations after which the run stops
     * @param populationSize                   number of subproblems, one solution each
     * @param weights                          how the weight vectors are generated (see
     *                                         {@link MOEADWeights})
     * @param neighborSize                     subproblems of each neighborhood, at most
     *                                         {@code populationSize}
     * @param neighborhoodSelectionProbability probability of taking the parents from the
     *                                         neighborhood instead of the whole population
     * @param maximumNumberOfReplacedSolutions solutions of the neighborhood that one offspring can
     *                                         replace at most, at most {@code neighborSize}
     * @param aggregation                      how a subproblem compares solutions
     * @param normalizeObjectives              whether the aggregation normalizes the objectives
     *                                         with the ideal and nadir points
     * @param variation                        the crossover and the mutation of each segment
     * @param tracesFolder                     folder for the trace files, or {@code null} for none
     */
    record MOEADConfig(int maxEvaluations, int populationSize, MOEADWeights.Method weights, int neighborSize,
            double neighborhoodSelectionProbability, int maximumNumberOfReplacedSolutions,
            MOEAD.AggregationFunction aggregation, boolean normalizeObjectives, Variation variation,
            String tracesFolder) implements AlgorithmConfig {

        /**
         * The settings of a real-coded problem that is not composite, with the components of 1.2.
         * The record does not know the number of variables the operators were chosen for, so its
         * {@link #layout()} has a real segment of size 1; the methods that take a
         * {@link DoubleProblem} read the number from the problem, as in 1.2.
         *
         * @param maxEvaluations                   evaluations after which the run stops
         * @param populationSize                   number of subproblems, one solution each
         * @param weights                          how the weight vectors are generated
         * @param neighborSize                     subproblems of each neighborhood
         * @param neighborhoodSelectionProbability probability of taking the parents from the
         *                                         neighborhood instead of the whole population
         * @param maximumNumberOfReplacedSolutions solutions of the neighborhood that one offspring
         *                                         can replace at most
         * @param aggregation                      how a subproblem compares solutions
         * @param normalizeObjectives              whether the aggregation normalizes the
         *                                         objectives with the ideal and nadir points
         * @param crossover                        the crossover operator
         * @param mutation                         the mutation operator
         * @param tracesFolder                     folder for the trace files, or {@code null} for
         *                                         none
         */
        public MOEADConfig(int maxEvaluations, int populationSize, MOEADWeights.Method weights, int neighborSize,
                double neighborhoodSelectionProbability, int maximumNumberOfReplacedSolutions,
                MOEAD.AggregationFunction aggregation, boolean normalizeObjectives,
                OperatorConfig<CrossoverOperator<DoubleSolution>> crossover,
                OperatorConfig<MutationOperator<DoubleSolution>> mutation, String tracesFolder) {
            this(maxEvaluations, populationSize, weights, neighborSize, neighborhoodSelectionProbability,
                    maximumNumberOfReplacedSolutions, aggregation, normalizeObjectives,
                    Variation.real(crossover, mutation), tracesFolder);
        }

        /**
         * The crossover of a real-coded problem that is not composite, as in 1.2.
         *
         * @return the crossover operator
         * @throws IllegalStateException if the problem is composite or its variables are not real:
         *                               use {@link #variation()}
         */
        public OperatorConfig<CrossoverOperator<DoubleSolution>> crossover() {
            return variation.realCrossover();
        }

        /**
         * The mutation of a real-coded problem that is not composite, as in 1.2.
         *
         * @return the mutation operator
         * @throws IllegalStateException if the problem is composite or its variables are not real:
         *                               use {@link #variation()}
         */
        public OperatorConfig<MutationOperator<DoubleSolution>> mutation() {
            return variation.realMutation();
        }

        @Override
        public String describe() {
            return "MOEA/D, " + maxEvaluations + " evaluations, population " + populationSize
                    + ", weights " + name(weights)
                    + ", neighborhood " + neighborSize + " (selection probability "
                    + OperatorConfig.format(neighborhoodSelectionProbability) + ", at most "
                    + maximumNumberOfReplacedSolutions + " replaced), aggregation "
                    + aggregation.name().toLowerCase(Locale.ROOT)
                    + (normalizeObjectives ? " of normalized objectives" : "")
                    + ", " + variation.describe() + traces(tracesFolder);
        }

        /** The value of the {@code weights} key for a method. */
        static String name(MOEADWeights.Method weights) {
            return weights.name().toLowerCase(Locale.ROOT);
        }
    }
}

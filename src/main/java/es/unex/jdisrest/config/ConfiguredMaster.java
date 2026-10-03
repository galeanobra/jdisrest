package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.MOEADConfig;
import es.unex.jdisrest.config.AlgorithmConfig.NSGAIIConfig;
import es.unex.jdisrest.config.AlgorithmConfig.PAESConfig;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD;
import es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII;
import es.unex.jdisrest.distributed.algorithms.steadystate.PAES;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.util.Log;
import es.unex.jdisrest.util.TraceWriter;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;
import org.uma.jmetal.util.archive.impl.CrowdingDistanceArchive;
import org.uma.jmetal.util.comparator.dominanceComparator.impl.DominanceWithConstraintsComparator;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Launches a master for a real-coded problem with the algorithm and settings of a configuration
 * file (see {@link AlgorithmConfig}): NSGA-II, PAES or MOEA/D, whose evaluations the workers do.
 *
 * <pre>
 * java -cp &lt;classpath&gt; es.unex.jdisrest.config.ConfiguredMaster &lt;problemClass&gt; &lt;host&gt; &lt;port&gt; &lt;configFile&gt; [key=value ...]
 * java -cp &lt;classpath&gt; es.unex.jdisrest.config.ConfiguredMaster &lt;problemClass&gt; --check &lt;configFile&gt; [key=value ...]
 * </pre>
 *
 * <p>{@code <problemClass>} is the fully qualified name of a {@link DoubleProblem} with a public
 * constructor without arguments, on the class path with jdisrest. A program that builds its
 * problem otherwise, or wants its own command name, calls
 * {@link #run(Supplier, String, List, String)} from its own {@code main} and passes the status it
 * returns to {@link System#exit}.
 *
 * <h2>Arguments</h2>
 * <ul>
 *   <li>{@code <host>} and {@code <port>}: the address the master advertises to the workers, the
 *       port in [1, 65535].</li>
 *   <li>{@code <configFile>}: the properties file, for instance one of the repository's
 *       {@code examples}.</li>
 *   <li>{@code key=value}: replaces or adds that key of the file, taken literally, for instance
 *       {@code maxEvaluations=200} for a short test.</li>
 *   <li>{@code --check}: reads the file for the problem, which checks everything a run would
 *       reject (every value, the operators, which are built, and the MOEA/D lattice size against
 *       the objectives), prints {@code Configuration OK for <title>: <summary>} and exits without
 *       starting the master. A script can gate the submission of a job on it.</li>
 * </ul>
 * <p>The exit status is 0 when the run ends (or the check passes) and 1 for a usage error, an
 * invalid or unreadable configuration file, or any failure while the run starts or goes on.
 * Usage and configuration errors are printed to the standard error, without a stack trace;
 * failures are logged with theirs.
 *
 * <h2>A run</h2>
 * <ol>
 *   <li>The problem is created once, and the configuration is read for it.</li>
 *   <li>The algorithm is built ({@link #createAlgorithm}), which starts the REST server, with a
 *       {@link TerminationByEvaluations} of {@code maxEvaluations}.</li>
 *   <li>{@link MasterFacade#init} receives the same budget, and writes {@code status.json} every
 *       30 seconds.</li>
 *   <li>The traces folder, if the file names one, receives the configuration (see
 *       {@link ConfigHistory}). This happens after the algorithm is built, so that a run that
 *       fails to start leaves no record.</li>
 *   <li>An {@link AlgorithmReconfiguration} is registered, so {@code GET /api/v1/config}
 *       returns the configuration in use and {@code POST /api/v1/config} changes it. It comes
 *       after {@code init}, which would otherwise overwrite the budget of a change, and after
 *       the start record, which would otherwise follow the first change in the log.</li>
 *   <li>The algorithm runs, and its result is written to {@code VAR.csv} and {@code FUN.csv}
 *       in the working directory (both empty for a run stopped before its first result).</li>
 *   <li>The master is shut down ({@code AbstractMaster.shutdown()}), also when a step after
 *       the second one fails: {@code .master-endpoint} is deleted, so that workers started later
 *       do not connect to a master that is gone, the REST server closes, and {@code status.json}
 *       gets a final snapshot that reports the run as finished. Workers that asked for a task
 *       before the close got {@code 410} and stopped; those still evaluating find the master
 *       gone and stop after their failed heartbeats or requests, typically 10 to 25 s later
 *       (the bundled Python worker started from the endpoint file then sees the file deleted
 *       and ends as finished, not as having lost its master).</li>
 * </ol>
 *
 * <p>Every failure is caught and turned into status 1, and {@link #main} always ends in
 * {@link System#exit}: a failure while the algorithm is being built can leave a REST server
 * running that nothing can shut down, and the problem may have threads of its own, either of
 * which would keep the JVM alive.
 *
 * @author Francisco Luna (Universidad de Málaga)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class ConfiguredMaster {

    static final String CHECK_OPTION = "--check";
    static final int STATUS_FILE_INTERVAL_S = 30;
    static final String VARIABLES_FILE = "VAR.csv";
    static final String OBJECTIVES_FILE = "FUN.csv";
    private static final String COMMAND = "ConfiguredMaster";

    private ConfiguredMaster() {
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Runs {@code <problemClass> <host> <port> <configFile> [key=value ...]} or
     * {@code <problemClass> --check <configFile> [key=value ...]}, then exits the JVM with the
     * status (see the class description).
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        int status = 1;
        try {
            status = launch(List.of(args), System.out, System.err);
        } catch (Throwable t) {
            // launch catches everything already; this guard only makes sure the JVM exits.
            Log.error("The master failed: " + t, t);
        }
        System.exit(status);
    }

    /**
     * Runs the master of a problem with {@code <host> <port> <configFile> [key=value ...]}, or
     * only checks the configuration with {@code --check <configFile> [key=value ...]}. Never
     * throws: every failure is reported and gives status 1. The master is shut down before this
     * method returns (see the class description), but the caller should still pass the status to
     * {@link System#exit}: a failure while the algorithm is being built can leave a REST server
     * running that nothing can shut down.
     *
     * @param problemFactory creates the problem, once, after the arguments have been checked
     * @param title          how messages name the problem, for instance its class name
     * @param args           the arguments after the problem
     * @param command        how the usage message names the program, for instance
     *                       {@code ConfiguredMaster com.example.MyProblem}
     * @return the exit status: 0 for a run that ended or a check that passed, 1 otherwise
     * @throws NullPointerException if an argument is {@code null}
     */
    public static int run(Supplier<? extends DoubleProblem> problemFactory, String title, List<String> args,
            String command) {
        return run(problemFactory, title, args, command, System.out, System.err);
    }

    /**
     * {@link #run(Supplier, String, List, String)} with the streams for the messages of
     * {@code --check} and of usage and configuration errors.
     */
    static int run(Supplier<? extends DoubleProblem> problemFactory, String title, List<String> args,
            String command, PrintStream out, PrintStream err) {
        Objects.requireNonNull(problemFactory, "problemFactory must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(args, "args must not be null");
        Objects.requireNonNull(command, "command must not be null");
        int status = 1;
        try {
            if (!args.isEmpty() && args.getFirst().equals(CHECK_OPTION)) {
                status = check(problemFactory, title, args.subList(1, args.size()), command, out, err);
            } else {
                status = start(problemFactory, title, args, command, err);
            }
        } catch (Throwable t) {
            // Not a usage error, even if it is an IllegalArgumentException: no usage text.
            Log.error(title + " failed: " + t, t);
        }
        return status;
    }

    /** {@link #main} without the exit, for tests. */
    static int launch(List<String> args, PrintStream out, PrintStream err) {
        String command = COMMAND + " <problemClass>";
        if (args.isEmpty()) {
            err.println("Invalid arguments: the problem class is missing");
            err.println(usage(command));
            return 1;
        }
        Constructor<? extends DoubleProblem> constructor;
        try {
            constructor = problemConstructor(args.getFirst());
        } catch (UsageException e) {
            err.println("Invalid arguments: " + e.getMessage());
            err.println(usage(command));
            return 1;
        }
        return run(() -> newInstance(constructor), constructor.getDeclaringClass().getSimpleName(),
                args.subList(1, args.size()), COMMAND + " " + args.getFirst(), out, err);
    }

    // ── Checking and starting ─────────────────────────────────────────────────

    private static int check(Supplier<? extends DoubleProblem> problemFactory, String title, List<String> args,
            String command, PrintStream out, PrintStream err) {
        Path file;
        try {
            if (args.isEmpty()) {
                throw new UsageException(CHECK_OPTION + " needs a configuration file");
            }
            file = parsePath(args.getFirst());
        } catch (UsageException e) {
            err.println("Invalid arguments: " + e.getMessage());
            err.println(usage(command));
            return 1;
        }
        DoubleProblem problem = problemFactory.get();
        int status = 1;
        try {
            AlgorithmConfig config = AlgorithmConfig.load(file, args.subList(1, args.size()), problem);
            out.println("Configuration OK for " + title + ": " + config.describe());
            status = 0;
        } catch (InvalidConfigurationException e) {
            err.println("Invalid configuration: " + e.getMessage());
        }
        return status;
    }

    private static int start(Supplier<? extends DoubleProblem> problemFactory, String title, List<String> args,
            String command, PrintStream err) {
        Launch launch;
        try {
            launch = Launch.parse(args);
        } catch (UsageException e) {
            err.println("Invalid arguments: " + e.getMessage());
            err.println(usage(command));
            return 1;
        }
        DoubleProblem problem = problemFactory.get();
        AlgorithmConfig config;
        ConfigHistory history;
        try {
            config = AlgorithmConfig.load(launch.configFile(), launch.overrides(), problem);
            history = ConfigHistory.of(launch.configFile(), launch.overrides(), config);
        } catch (InvalidConfigurationException e) {
            err.println("Invalid configuration: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("Cannot read the configuration file " + launch.configFile() + ": " + e);
            return 1;
        }
        run(problem, title, launch, config, history);
        return 0;
    }

    /** The run itself, in the order the class description gives. */
    private static void run(DoubleProblem problem, String title, Launch launch, AlgorithmConfig config,
            ConfigHistory history) {
        Log.info(title + " configuration: " + config.describe());
        SteadyStateEvolutionaryAlgorithm<DoubleSolution> algorithm =
                createAlgorithm(launch.host(), launch.port(), problem, config);
        try {
            MasterFacade.init(config.maxEvaluations(), STATUS_FILE_INTERVAL_S);
            try {
                Path copy = history.recordStart(config);
                if (copy != null) {
                    Log.info("Configuration recorded in " + copy);
                }
            } catch (IOException e) {
                Log.warn("Could not record the configuration in the traces folder (" + e
                        + ") — running without the record");
            }
            MasterFacade.setConfigurationHandler(new AlgorithmReconfiguration(algorithm, config, problem, history));
            algorithm.run();

            // Empty for a run stopped before its first result.
            List<DoubleSolution> result = algorithm.getResult();
            TraceWriter.write(result, VARIABLES_FILE, OBJECTIVES_FILE, ",");
            Log.info(title + " finished after " + algorithm.getEvaluations() + " evaluations: " + result.size()
                    + " solutions written to " + VARIABLES_FILE + " and " + OBJECTIVES_FILE);
        } finally {
            shutDown(algorithm);
        }
    }

    /**
     * Shuts the master down ({@code AbstractMaster.shutdown()}), after the result has been written
     * or the run has failed: {@code .master-endpoint} is deleted, the REST server closes, so the
     * workers stop once they find the master gone, and {@code status.json} gets its final
     * snapshot. A failure here is only logged, so that it neither hides the failure of the run
     * nor fails a run whose result is already written.
     */
    private static void shutDown(SteadyStateEvolutionaryAlgorithm<DoubleSolution> algorithm) {
        try {
            algorithm.shutdown();
        } catch (RuntimeException e) {
            Log.error("Could not shut the master down cleanly: " + e + " — the result is not affected", e);
        }
    }

    // ── Algorithms ────────────────────────────────────────────────────────────

    /**
     * Builds the algorithm a configuration describes, which starts its REST server: an
     * {@link NSGAII}; a {@link PAES} whose archive is a {@link CrowdingDistanceArchive} of
     * {@code archiveSize} solutions with constraint-aware dominance
     * ({@link DominanceWithConstraintsComparator}); or a {@link MOEAD}, which picks its parents
     * itself and so takes no selection operator. The stopping criterion is a
     * {@link TerminationByEvaluations} of {@code maxEvaluations}, which an
     * {@link AlgorithmReconfiguration} can replace.
     *
     * <p>A configuration read by {@link AlgorithmConfig#load(Path, List, DoubleProblem)} for the
     * same problem builds without errors: its operators have been built once already, and the
     * MOEA/D lattice size checked against the objectives.
     *
     * @param host    the host the master advertises to the workers
     * @param port    the port of the REST server
     * @param problem the problem
     * @param config  the configuration, read for {@code problem}
     * @return the algorithm, ready to run
     * @throws RuntimeException if the algorithm cannot be built, for a configuration read for
     *                          another problem; MOEA/D and PAES check their arguments before the
     *                          server starts
     */
    public static SteadyStateEvolutionaryAlgorithm<DoubleSolution> createAlgorithm(String host, int port,
            DoubleProblem problem, AlgorithmConfig config) {
        var termination = new TerminationByEvaluations(config.maxEvaluations());
        String traces = config.tracesFolder();
        return switch (config) {
            case NSGAIIConfig nsgaii -> new NSGAII<>(host, port, problem, nsgaii.populationSize(),
                    nsgaii.crossover().create(), nsgaii.mutation().create(), termination, traces);
            case PAESConfig paes -> new PAES<>(host, port, problem,
                    new CrowdingDistanceArchive<>(paes.archiveSize(), new DominanceWithConstraintsComparator<>()),
                    paes.mutation().create(), paes.archiveSelectionProbability(), paes.resultSource(),
                    termination, traces);
            case MOEADConfig moead -> new MOEAD<>(host, port, problem, moead.populationSize(),
                    moead.crossover().create(), moead.mutation().create(), termination, moead.neighborSize(),
                    moead.neighborhoodSelectionProbability(), moead.aggregation(),
                    moead.maximumNumberOfReplacedSolutions(), moead.weights(), moead.normalizeObjectives(), null,
                    traces);
        };
    }

    // ── Command line ──────────────────────────────────────────────────────────

    /**
     * The arguments of a run.
     *
     * @param host       the host the master advertises
     * @param port       the port of the REST server, in [1, 65535]
     * @param configFile the configuration file
     * @param overrides  the {@code key=value} arguments after it
     */
    record Launch(String host, int port, Path configFile, List<String> overrides) {

        /**
         * Parses {@code <host> <port> <configFile> [key=value ...]}. The overrides are checked
         * when the file is read.
         *
         * @throws UsageException if the arguments do not follow the usage
         */
        static Launch parse(List<String> args) {
            if (args.size() < 3) {
                throw new UsageException("expected <host> <port> <configFile> [key=value ...], got "
                        + args.size() + " argument" + (args.size() == 1 ? "" : "s"));
            }
            if (args.get(0).isBlank()) {
                throw new UsageException("host must not be empty");
            }
            return new Launch(args.get(0), parsePort(args.get(1)), parsePath(args.get(2)),
                    List.copyOf(args.subList(3, args.size())));
        }
    }

    /**
     * A command line that does not follow the usage. Reported with the usage text; any other
     * exception, {@link IllegalArgumentException} included, is a failure of the run.
     */
    @SuppressWarnings("serial")
    static final class UsageException extends IllegalArgumentException {
        UsageException(String message) {
            super(message);
        }
    }

    static String usage(String command) {
        return String.join(System.lineSeparator(),
                "Usage: " + command + " <host> <port> <configFile> [key=value ...]",
                "       " + command + " " + CHECK_OPTION + " <configFile> [key=value ...]");
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // Reported below, as a port out of range.
        }
        throw new UsageException("port must be an integer in [1, 65535], got '" + text + "'");
    }

    private static Path parsePath(String text) {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException("configFile is not a valid path: " + e.getMessage());
        }
    }

    /**
     * The public constructor without arguments of a {@link DoubleProblem} class, which is loaded
     * but not initialized: a failing static initializer is a failure of the run, not a usage
     * error.
     */
    static Constructor<? extends DoubleProblem> problemConstructor(String name) {
        Class<?> type;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            type = Class.forName(name, false, loader != null ? loader : ConfiguredMaster.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new UsageException("problem class " + name + " is not on the class path");
        } catch (LinkageError e) {
            throw new UsageException("problem class " + name + " cannot be loaded: " + e);
        }
        if (!DoubleProblem.class.isAssignableFrom(type)) {
            throw new UsageException(name + " is not a " + DoubleProblem.class.getName());
        }
        if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
            throw new UsageException(name + " must be a public class that is not abstract");
        }
        try {
            return type.asSubclass(DoubleProblem.class).getConstructor();
        } catch (NoSuchMethodException e) {
            throw new UsageException(name + " has no public constructor without arguments");
        }
    }

    private static DoubleProblem newInstance(Constructor<? extends DoubleProblem> constructor) {
        try {
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("cannot create " + constructor.getDeclaringClass().getName() + ": " + cause,
                    cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create " + constructor.getDeclaringClass().getName() + ": " + e, e);
        }
    }
}

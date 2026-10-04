package es.unex.jdisrest.config;

import es.unex.jdisrest.config.AlgorithmConfig.MOEADConfig;
import es.unex.jdisrest.config.AlgorithmConfig.NSGAIIConfig;
import es.unex.jdisrest.config.AlgorithmConfig.PAESConfig;
import es.unex.jdisrest.distributed.SteadyStateEvolutionaryAlgorithm;
import es.unex.jdisrest.distributed.algorithms.steadystate.MOEAD;
import es.unex.jdisrest.distributed.algorithms.steadystate.NSGAII;
import es.unex.jdisrest.distributed.algorithms.steadystate.PAES;
import es.unex.jdisrest.distributed.rest.ConfigurationHandler;
import es.unex.jdisrest.distributed.rest.MasterFacade;
import es.unex.jdisrest.util.Log;
import org.uma.jmetal.component.catalogue.common.termination.impl.TerminationByEvaluations;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.problem.doubleproblem.DoubleProblem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.doublesolution.DoubleSolution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Changes the configuration of a running NSGA-II, PAES or MOEA/D ({@code POST /api/v1/config})
 * and records every change in the traces with a {@link ConfigHistory}. {@link ConfiguredMaster}
 * registers one with {@link MasterFacade#setConfigurationHandler}; a program that builds its
 * algorithm from an {@link AlgorithmConfig} by other means can register one too.
 *
 * <h2>What can change</h2>
 * <p>The new text is a complete properties file, read for the layout of the run (the operator
 * keys of each segment, the {@code k/n} probabilities and the operator checks, see
 * {@link SolutionLayout}) and checked against the objectives of the problem (the MOEA/D lattice
 * size). For a composite problem it therefore needs the prefixed keys of each segment, and a key
 * without a prefix is unknown. It must describe the same algorithm, with the same population or
 * archive size, MOEA/D weights and neighborhood size, and traces folder (see
 * {@link AlgorithmConfig#fixedDuringRun}), and a {@code maxEvaluations} greater than the
 * evaluations already done. Everything else comes from the new text, so a key left out takes its
 * default value, as when the run starts. The new operators of each segment and PAES or MOEA/D
 * settings apply from then on: to the tasks created and, for the MOEA/D replacement, to the
 * results that arrive.
 *
 * <h2>An evaluation budget</h2>
 * <p>Every change installs a {@link TerminationByEvaluations} with the new {@code maxEvaluations}
 * and passes it to {@link MasterFacade#setMaxEvaluations}, whatever stopping criterion the
 * algorithm was built with: registering this handler implies that the run stops after a number
 * of evaluations. {@link ConfiguredMaster} builds every algorithm that way.
 *
 * <h2>Failures</h2>
 * <p>Nothing changes unless everything can change: the text is parsed and checked, and the
 * operators are built, before the running algorithm is touched, and {@code PAES} and
 * {@code MOEAD} check every argument of their {@code reconfigure} before they change anything.
 * <ul>
 *   <li>{@link InvalidConfigurationException} (an {@link IllegalArgumentException}, answered
 *       {@code 422}): the text is wrong, changes a fixed setting, or cannot be applied, which
 *       includes any failure of the jMetal constructors and checks.</li>
 *   <li>{@link IllegalStateException} (answered {@code 409}): the run cannot take a change any
 *       more, because it was stopped or has reached its budget, also when that happens while the
 *       change is being applied. The change is then neither recorded nor shown by
 *       {@link #current()}.</li>
 * </ul>
 * <p>The run goes on while the text is read, so the state of the run is checked again just
 * before the change. A {@code maxEvaluations} the run reaches while it is being installed ends
 * the run through {@link SteadyStateEvolutionaryAlgorithm#requestStop()}, as the budget would.
 * <p>A change that is applied but cannot be written to the traces folder is only logged.
 *
 * @author Francisco Luna (Universidad de Málaga)
 */
public final class AlgorithmReconfiguration implements ConfigurationHandler {

    private final SteadyStateEvolutionaryAlgorithm<?> algorithm;
    /** The layout new configurations are read for. */
    private final SolutionLayout layout;
    /** The objectives of the problem, which the MOEA/D lattice size of new configurations must fit. */
    private final int numberOfObjectives;
    private final ConfigHistory history;
    private AlgorithmConfig current;

    /**
     * Creates the handler for a running algorithm on a real-coded problem and the configuration
     * it started with. New configurations are read for the number of variables of the problem,
     * and no solution of it is created.
     *
     * @param algorithm the running algorithm, built from {@code initial}, for instance by
     *                  {@link ConfiguredMaster#createAlgorithm}: a {@link PAES} or {@link MOEAD}
     *                  for their configurations, and for NSGA-II an {@link NSGAII} or another
     *                  algorithm whose only variation is a crossover and a mutation
     * @param initial   the configuration the run started with, of a problem that is not
     *                  composite with real variables; as in 1.2, the number of variables it was
     *                  read for is not compared with the problem's
     * @param problem   the problem of the run, which new configurations are read for
     * @param history   the history of the run, where changes are recorded
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the algorithm is not the one the configuration describes,
     *                                  the configuration is not that of a real-coded problem, or
     *                                  the problem's solutions cannot be configured, for instance
     *                                  because they have no variables (see {@link SolutionLayout#of})
     */
    public AlgorithmReconfiguration(SteadyStateEvolutionaryAlgorithm<DoubleSolution> algorithm,
            AlgorithmConfig initial, DoubleProblem problem, ConfigHistory history) {
        this(algorithm, initial, (Problem<DoubleSolution>) problem, history);
    }

    /**
     * Creates the handler for a running algorithm on a problem of any encoding and the
     * configuration it started with. New configurations are read for the layout of
     * {@code initial}, which is checked once against the problem ({@link SolutionLayout#of}): a
     * {@link DoubleProblem} is handled as by the constructor for it, without creating a solution,
     * while any other problem creates one solution, which draws the random numbers of its initial
     * values.
     *
     * @param algorithm the running algorithm, built from {@code initial}: a {@link PAES} or
     *                  {@link MOEAD} for their configurations, and for NSGA-II an {@link NSGAII}
     *                  or another algorithm whose only variation is a crossover and a mutation
     * @param initial   the configuration the run started with, read for the layout of the
     *                  problem's solutions
     * @param problem   the problem of the run
     * @param history   the history of the run, where changes are recorded
     * @param <S>       the solutions of the problem
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the algorithm is not the one the configuration describes,
     *                                  the configuration was read for another layout (another
     *                                  encoding or size of a segment, or a composite where the
     *                                  solutions are not), or the problem's solutions cannot be
     *                                  configured
     */
    public <S extends Solution<?>> AlgorithmReconfiguration(SteadyStateEvolutionaryAlgorithm<S> algorithm,
            AlgorithmConfig initial, Problem<S> problem, ConfigHistory history) {
        this(algorithm, initial, fittedLayout(algorithm, initial, problem), problem.numberOfObjectives(), history);
    }

    /**
     * Creates the handler of a run whose initial configuration was read for {@code layout}, the
     * layout of the problem's solutions, which is not checked again: {@link ConfiguredMaster} has
     * just read it from the problem, creating one solution unless the problem is a
     * {@link DoubleProblem}, and need not create another.
     *
     * @param layout             the layout new configurations are read for
     * @param numberOfObjectives the objectives of the problem, which the MOEA/D lattice size of new
     *                           configurations must fit
     * @throws IllegalArgumentException if the algorithm is not the one the configuration describes
     */
    AlgorithmReconfiguration(SteadyStateEvolutionaryAlgorithm<?> algorithm, AlgorithmConfig initial,
            SolutionLayout layout, int numberOfObjectives, ConfigHistory history) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        this.current = Objects.requireNonNull(initial, "initial must not be null");
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
        this.numberOfObjectives = numberOfObjectives;
        this.history = Objects.requireNonNull(history, "history must not be null");
        requireDrives(initial, algorithm);
    }

    /**
     * The layout new configurations are read for, once the algorithm is the one {@code initial}
     * describes: that of {@code initial}, checked against the problem
     * ({@link SolutionLayout#fittedTo}), which for a {@link DoubleProblem} is the layout of its
     * number of variables, read without creating a solution, as in 1.2.
     */
    private static SolutionLayout fittedLayout(SteadyStateEvolutionaryAlgorithm<?> algorithm, AlgorithmConfig initial,
            Problem<?> problem) {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        Objects.requireNonNull(initial, "initial must not be null");
        Objects.requireNonNull(problem, "problem must not be null");
        requireDrives(initial, algorithm);
        return initial.layout().fittedTo(problem);
    }

    @Override
    public String current() {
        return history.current();
    }

    /**
     * Applies a new configuration to the running algorithm, as the class description explains.
     *
     * @param properties the text of the new properties file
     * @return the description of the configuration now in use
     * @throws InvalidConfigurationException if the text is wrong, changes a setting fixed during
     *                                       the run, or cannot be applied; nothing changes then
     * @throws IllegalStateException         if the run was stopped or has reached its budget,
     *                                       before or while the change was being applied
     */
    @Override
    public synchronized String apply(String properties) {
        // Only the algorithm thread counts, so the value is a lower bound of the evaluations done
        // when the change takes effect.
        int done = algorithm.getEvaluations();
        requireOpen(done);

        AlgorithmConfig next = AlgorithmConfig.parseText(properties, layout);
        AlgorithmConfigParser.checkObjectives(next, numberOfObjectives);
        List<String> problems = new ArrayList<>(AlgorithmConfig.fixedDuringRun(current, next));
        if (next.maxEvaluations() <= done) {
            problems.add(budgetProblem(done, next));
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigurationException(String.join("; ", problems));
        }

        // Results kept arriving while the text was read: check again just before the change, so
        // that it neither misses the end of the run nor installs a budget already reached.
        done = algorithm.getEvaluations();
        requireOpen(done);
        if (next.maxEvaluations() <= done) {
            throw new InvalidConfigurationException(budgetProblem(done, next));
        }

        reconfigure(next);
        if (!algorithm.setTermination(new TerminationByEvaluations(next.maxEvaluations()))
                || algorithm.isStopRequested()) {
            // A run that has ended no longer uses its operators or its termination.
            throw new IllegalStateException("the run ended before the change could take effect");
        }
        if (algorithm.getEvaluations() >= next.maxEvaluations()) {
            // Reached while being installed: the loop may wait for a result that never comes.
            Log.info("The run reached the new budget of " + next.maxEvaluations()
                    + " evaluations while it was installed — finishing");
            algorithm.requestStop();
        }
        MasterFacade.setMaxEvaluations(next.maxEvaluations());
        current = next;

        Log.info("Configuration changed after " + done + " evaluations: " + next.describe());
        try {
            Path saved = history.recordChange(properties, done, next);
            if (saved != null) {
                Log.info("Configuration change recorded in " + saved);
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not record the configuration change in the traces folder (" + e
                    + ") — the change applies all the same");
        }
        return next.describe();
    }

    /**
     * Refuses a change once the run cannot take one: a stop has been requested or the budget in
     * use is reached.
     */
    private void requireOpen(int done) {
        if (algorithm.isStopRequested()) {
            throw new IllegalStateException("the run has been stopped");
        }
        if (done >= current.maxEvaluations()) {
            throw new IllegalStateException("the run has reached its budget of " + current.maxEvaluations()
                    + " evaluations");
        }
    }

    private static String budgetProblem(int done, AlgorithmConfig next) {
        return "maxEvaluations must be greater than the " + done + " evaluations already done, got "
                + next.maxEvaluations();
    }

    // ── Applying ──────────────────────────────────────────────────────────────

    /**
     * Passes the operators and settings of {@code next} to the running algorithm. The operators
     * are built as arguments, before the call that installs them, so a failure leaves the run as
     * it was.
     */
    private void reconfigure(AlgorithmConfig next) {
        reconfigure(algorithm, next);
    }

    /**
     * {@link #reconfigure(AlgorithmConfig)} with the solutions of the running algorithm named, so
     * that the operators of each segment are built for them: {@code next} was read for the layout
     * of those solutions.
     */
    private static <S extends Solution<?>> void reconfigure(SteadyStateEvolutionaryAlgorithm<S> algorithm,
            AlgorithmConfig next) {
        Variation variation = next.variation();
        switch (next) {
            case NSGAIIConfig _ -> applying(() ->
                    algorithm.setVariation(variation.createCrossover(), variation.createMutation()));
            case PAESConfig paes -> {
                if (!(algorithm instanceof PAES<S> running)) {
                    throw new IllegalStateException("the running algorithm is not PAES");
                }
                applying(() -> running.reconfigure(variation.createMutation(), paes.archiveSelectionProbability(),
                        paes.resultSource()));
            }
            case MOEADConfig moead -> {
                if (!(algorithm instanceof MOEAD<S> running)) {
                    throw new IllegalStateException("the running algorithm is not MOEA/D");
                }
                applying(() -> running.reconfigure(variation.createCrossover(), variation.createMutation(),
                        moead.neighborhoodSelectionProbability(), moead.maximumNumberOfReplacedSolutions(),
                        moead.aggregation(), moead.normalizeObjectives()));
            }
        }
    }

    /**
     * Runs a change, turning a failure into a configuration error. The parser already builds every
     * operator once, so this is a second line of defence: jMetal reports wrong values with its own
     * runtime exceptions, which would otherwise reach the client as an internal error.
     */
    private static void applying(Runnable change) {
        try {
            change.run();
        } catch (RuntimeException e) {
            throw new InvalidConfigurationException("the configuration cannot be applied, nothing changed: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
    }

    /**
     * Throws unless the algorithm is one the configuration can drive: PAES and MOEA/D need their
     * own class, whose {@code reconfigure} takes their settings; NSGA-II needs an algorithm whose
     * variation is the crossover and mutation pair of
     * {@link SteadyStateEvolutionaryAlgorithm#setVariation}, which PAES and MOEA/D are not.
     */
    private static void requireDrives(AlgorithmConfig config, SteadyStateEvolutionaryAlgorithm<?> algorithm) {
        boolean fits = switch (config) {
            case NSGAIIConfig _ -> !(algorithm instanceof PAES || algorithm instanceof MOEAD);
            case PAESConfig _ -> algorithm instanceof PAES;
            case MOEADConfig _ -> algorithm instanceof MOEAD;
        };
        if (!fits) {
            throw new IllegalArgumentException("a " + config.getClass().getSimpleName() + " cannot drive a running "
                    + algorithm.getClass().getName());
        }
    }
}

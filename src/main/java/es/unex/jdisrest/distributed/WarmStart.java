package es.unex.jdisrest.distributed;

import es.unex.jdisrest.util.Log;
import org.uma.jmetal.problem.Problem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Warm start shared by the distributed algorithms and the local NSGA-II: when a file of
 * initial solutions exists and the problem implements {@link WarmStartCapable}, the run starts
 * from the solutions the problem builds from that file instead of from random ones.
 *
 * <p>Callers:
 * <ul>
 *   <li>{@link SteadyStateEvolutionaryAlgorithm#createInitialSolutions(int)}, and through it every
 *       bundled steady-state algorithm: those that keep the inherited
 *       {@link SteadyStateEvolutionaryAlgorithm#createInitialTasks() createInitialTasks()}, PAES
 *       (which asks for one solution) and MOEA/D (solution {@code i} for subproblem {@code i}).
 *       An algorithm that overrides {@code createInitialTasks()} without calling
 *       {@code createInitialSolutions} ignores the warm start.</li>
 *   <li>The local
 *       {@link es.unex.jdisrest.local.algorithms.NSGAII#createInitialPopulation() NSGA-II}.</li>
 * </ul>
 *
 * <h2>Copy into the traces folder</h2>
 * <p>When the warm start is used and a traces folder is configured, the file is copied into that
 * folder (replacing an older copy), so that the traces record which warm-start file the run was
 * given next to what the run produced. The copy is the warm-start <em>file</em> the problem was
 * asked to read, not the population the run started from:
 * {@link WarmStartCapable#createInitialPopulationFromFile(int)} decides how many rows it uses,
 * and may pad the population with random solutions or ignore a malformed file altogether. A
 * failed copy is logged as a warning and never stops the run.
 *
 * <p>The problem reads the file itself — {@code createInitialPopulationFromFile} takes no path —
 * so the {@code file} given to {@link #load} only decides whether the warm start happens and
 * what gets copied. A problem must therefore read the same file the caller checks, which for
 * every bundled caller is {@link #FILE}.
 *
 * @author Francisco Luna (Universidad de Málaga)
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public final class WarmStart {

    /**
     * The warm-start file every bundled algorithm checks: {@code iVAR.csv}, resolved against the
     * working directory of the JVM.
     */
    public static final Path FILE = Path.of("iVAR.csv");

    private WarmStart() {}

    /**
     * Asks the problem for {@code count} initial solutions when {@code file} exists and the
     * problem implements {@link WarmStartCapable}, and copies {@code file} into
     * {@code tracesFolder}.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@code file} does not exist: returns {@code null}, logs nothing and creates no
     *       folder.</li>
     *   <li>{@code file} exists but the problem is not {@link WarmStartCapable}: logs a warning
     *       and returns {@code null}.</li>
     *   <li>Otherwise logs {@code "Initial population loaded from <file> file"} <em>before</em>
     *       calling {@link WarmStartCapable#createInitialPopulationFromFile(int)}, so that any
     *       message the problem logs while parsing follows it. If the problem returns
     *       {@code null}, a warning is logged, nothing is copied and {@code null} is returned. If
     *       it returns a list of another size than {@code count}, a warning is logged and the
     *       list is returned unchanged: what a short or long list means is up to the caller
     *       (the steady-state algorithms fill the missing slots with the tasks they create
     *       later; an algorithm that starts from a single solution needs at least one; the local
     *       NSGA-II tops a short list up with random solutions and drops the surplus of a long
     *       one), and rejecting it would break problems that have always returned such lists.
     *       The file is then copied when {@code tracesFolder} is not {@code null}.</li>
     * </ul>
     *
     * <p>The copy goes to {@code tracesFolder/<file name>}; the folder is created when missing
     * and an existing copy is replaced. When the target already is {@code file} (the traces
     * folder is the file's own folder) nothing is written. An {@link IOException} is logged as a
     * warning and the solutions are returned anyway.
     *
     * @param problem      the problem; it reads the warm-start file itself
     * @param count        number of solutions requested, forwarded to the problem
     * @param file         the file whose existence enables the warm start, and the one copied
     * @param tracesFolder the folder that receives the copy, or {@code null} to keep no copy
     * @param <S>          the solution type
     * @return the solutions built by the problem, or {@code null} when the run must start from
     *         random solutions
     */
    @SuppressWarnings("unchecked")
    public static <S> List<S> load(Problem<S> problem, int count, Path file, Path tracesFolder) {
        if (!Files.exists(file)) {
            return null;
        }
        if (!(problem instanceof WarmStartCapable<?> warmStart)) {
            Log.warn(file + " found but problem does not implement WarmStartCapable — using random initialization");
            return null;
        }
        Log.info("Initial population loaded from " + file + " file");
        List<S> population = ((WarmStartCapable<S>) warmStart).createInitialPopulationFromFile(count);
        if (population == null) {
            Log.warn("The problem built no initial population from " + file + " — using random initialization");
            return null;
        }
        if (population.size() != count) {
            Log.warn("Initial population from " + file + " has size " + population.size() + " instead of the "
                    + count + " requested — starting from it as it is");
        }
        if (tracesFolder != null) {
            copy(file, tracesFolder);
        }
        return population;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Copies {@code file} into {@code folder}, keeping its name; see {@link #load} for the rules.
     * Logs {@code "Initial population file copied to <target>"} only after a real copy.
     */
    private static void copy(Path file, Path folder) {
        Path target = folder.resolve(file.getFileName());
        try {
            Files.createDirectories(folder);
            if (Files.exists(target) && Files.isSameFile(target, file)) {
                return;  // the traces folder holds the file itself: nothing to copy
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Initial population file copied to " + target);
        } catch (IOException e) {
            Log.warn("Could not copy " + file + " into " + folder + " (" + e + ") — the run goes on without the copy");
        }
    }
}

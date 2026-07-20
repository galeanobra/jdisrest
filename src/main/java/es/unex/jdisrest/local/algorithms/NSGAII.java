package es.unex.jdisrest.local.algorithms;

import es.unex.jdisrest.distributed.WarmStartCapable;
import es.unex.jdisrest.util.Log;

import org.uma.jmetal.operator.crossover.CrossoverOperator;
import org.uma.jmetal.operator.mutation.MutationOperator;
import org.uma.jmetal.operator.selection.SelectionOperator;
import org.uma.jmetal.operator.selection.impl.BinaryTournamentSelection;
import org.uma.jmetal.problem.Problem;
import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.util.ConstraintHandling;
import org.uma.jmetal.util.SolutionListUtils;
import org.uma.jmetal.util.archive.Archive;
import org.uma.jmetal.util.archive.impl.BestSolutionsArchive;
import org.uma.jmetal.util.archive.impl.NonDominatedSolutionListArchive;
import org.uma.jmetal.util.evaluator.SolutionListEvaluator;
import es.unex.jdisrest.util.TraceWriter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generational NSGA-II for the local/sequential execution mode.
 *
 * <p>Thin extension of jMetal's stock NSGA-II that adds, to mirror the
 * contract of {@code es.unex.jdisrest.distributed} after the recent traces fix:
 * <ul>
 *   <li>Per-generation traces in {@code tracesFolder} using the same filenames:
 *       {@code aVAR_<evals>.csv} / {@code aFUN_<evals>.csv} from the
 *       non-dominated archive, and {@code VAR_<evals>.csv} / {@code FUN_<evals>.csv}
 *       from the current population. Both pairs unfiltered (feasibility
 *       filtering is reserved for the final result).</li>
 *   <li>{@code iVAR.csv} warm-start when the problem implements
 *       {@link WarmStartCapable}.</li>
 *   <li>{@link #result()} returning the feasible non-dominated subset of the
 *       archive, downsampled with
 *       {@link SolutionListUtils#distanceBasedSubsetSelection} to at most
 *       {@code populationSize} solutions.</li>
 * </ul>
 *
 * <p>The evaluation strategy is plugged via the {@link SolutionListEvaluator}
 * — typically a {@code PythonSolutionListEvaluator} backed by a Python child
 * process so the existing Python evaluators are reused without changes.
 *
 * @param <S> jMetal solution type
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public class NSGAII<S extends Solution<?>> extends org.uma.jmetal.algorithm.multiobjective.nsgaii.NSGAII<S> {

    protected final int populationSize;
    protected final File tracesFolder;
    protected final Archive<S> archive;
    /** Number of generations between trace snapshots. 1 = every generation. */
    protected int traceCadence = 1;

    /** Set how often traces are written; clamped to a minimum of 1. */
    public void setTraceCadence(int cadence) {
        this.traceCadence = Math.max(1, cadence);
    }

    public NSGAII(Problem<S> problem,
                  int populationSize,
                  int maxEvaluations,
                  CrossoverOperator<S> crossover,
                  MutationOperator<S> mutation,
                  SelectionOperator<List<S>, S> selection,
                  SolutionListEvaluator<S> evaluator,
                  String tracesFolder) {
        super(problem, maxEvaluations, populationSize,
              populationSize, populationSize,
              crossover, mutation, selection, evaluator);
        this.populationSize = populationSize;
        this.tracesFolder   = (tracesFolder != null) ? new File(tracesFolder) : null;
        this.archive        = new BestSolutionsArchive<>(new NonDominatedSolutionListArchive<>(), populationSize);
    }

    /** Convenience: the canonical NSGA-II selection (binary tournament). */
    public static <T extends Solution<?>> SelectionOperator<List<T>, T> defaultSelection() {
        return new BinaryTournamentSelection<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<S> createInitialPopulation() {
        boolean existsIVAR = Files.exists(Path.of("iVAR.csv"));
        if (existsIVAR && getProblem() instanceof WarmStartCapable<?> ws) {
            Log.info("Initial population loaded from iVAR.csv file");
            return ((WarmStartCapable<S>) ws).createInitialPopulationFromFile(populationSize);
        }
        if (existsIVAR) {
            Log.warn("iVAR.csv found but problem does not implement WarmStartCapable — using random initialization");
        }
        return super.createInitialPopulation();
    }

    @Override
    protected void initProgress() {
        super.initProgress();
        for (S sol : getPopulation()) archive.add(sol);
        saveTrace();
    }

    @Override
    protected void updateProgress() {
        super.updateProgress();
        for (S sol : getPopulation()) archive.add(sol);
        saveTrace();
    }

    private void saveTrace() {
        if (tracesFolder == null) return;
        if (evaluations % (populationSize * traceCadence) != 0) return;
        if (!tracesFolder.exists() && !tracesFolder.mkdirs()) {
            Log.error("Error creating traces folder " + tracesFolder);
            return;
        }
        String prefix = tracesFolder + "/";
        Log.info("Population trace saved after " + evaluations + " evaluations in " + tracesFolder + " folder");

        List<S> archiveSnapshot    = new ArrayList<>(archive.solutions());
        List<S> populationSnapshot = new ArrayList<>(getPopulation());

        TraceWriter.write(archiveSnapshot,
                prefix + "aVAR_" + evaluations + ".csv",
                prefix + "aFUN_" + evaluations + ".csv", ",");
        TraceWriter.write(populationSnapshot,
                prefix + "VAR_" + evaluations + ".csv",
                prefix + "FUN_" + evaluations + ".csv", ",");
    }

    @Override
    public List<S> result() {
        List<S> feasible = archive.solutions().stream()
            .filter(ConstraintHandling::isFeasible)
            .collect(Collectors.toList());
        if (feasible.isEmpty()) return List.of();
        return SolutionListUtils.distanceBasedSubsetSelection(
                feasible, Math.min(populationSize, feasible.size()));
    }
}

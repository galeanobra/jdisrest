package es.unex.jdisrest.util;

import org.uma.jmetal.solution.Solution;
import org.uma.jmetal.solution.compositesolution.CompositeSolution;
import org.uma.jmetal.solution.integersolution.IntegerSolution;
import org.uma.jmetal.util.errorchecking.JMetalException;
import org.uma.jmetal.util.fileoutput.FileOutputContext;
import org.uma.jmetal.util.fileoutput.impl.DefaultFileOutputContext;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Output writer for {@link CompositeSolution}s composed of two integer segments
 * (e.g. DU + CU in the vRAN problem). The standard jMetal
 * {@code SolutionListOutput} does not serialize composite variables correctly
 * (it falls back to {@code Solution.toString()} which prints the inner
 * components' metadata), so this class flattens both segments back into a
 * single space-separated row plus the outer composite's objectives/constraints.
 *
 * <p>VAR row format: {@code "v0 v1 ... vN-1,[obj0  obj1 ...],[con0  con1 ...]"}.
 * <p>FUN row format: standard jMetal — separator-joined objective values.
 */
public class CompositeSolutionListOutput {
    private FileOutputContext varFileContext;
    private FileOutputContext funFileContext;
    private final List<? extends Solution<?>> solutionList;

    public CompositeSolutionListOutput(List<? extends Solution<?>> solutionList) {
        this.varFileContext = new DefaultFileOutputContext("VAR");
        this.funFileContext = new DefaultFileOutputContext("FUN");
        this.solutionList = solutionList;
    }

    public CompositeSolutionListOutput setVarFileOutputContext(FileOutputContext fileContext) {
        this.varFileContext = fileContext;
        return this;
    }

    public CompositeSolutionListOutput setFunFileOutputContext(FileOutputContext fileContext) {
        this.funFileContext = fileContext;
        return this;
    }

    public void print() {
        printObjectivesToFile(funFileContext, solutionList);
        printVariablesToFile(varFileContext, solutionList);
    }

    private void printVariablesToFile(FileOutputContext context, List<? extends Solution<?>> solutionList) {
        try (BufferedWriter bw = context.getFileWriter()) {
            for (Solution<?> solution : solutionList) {
                bw.write(formatSolution((CompositeSolution) solution));
                bw.newLine();
            }
        } catch (IOException e) {
            throw new JMetalException("Error writing variables: ", e);
        }
    }

    private void printObjectivesToFile(FileOutputContext context, List<? extends Solution<?>> solutionList) {
        try (BufferedWriter bw = context.getFileWriter()) {
            if (!solutionList.isEmpty()) {
                int numberOfObjectives = solutionList.get(0).objectives().length;
                for (Solution<?> s : solutionList) {
                    for (int j = 0; j < numberOfObjectives - 1; j++) {
                        bw.write(s.objectives()[j] + context.getSeparator());
                    }
                    bw.write(String.valueOf(s.objectives()[numberOfObjectives - 1]));
                    bw.newLine();
                }
            }
        } catch (IOException e) {
            throw new JMetalException("Error writing objectives: ", e);
        }
    }

    public static String formatSolution(CompositeSolution solution) {
        String du = Arrays.stream(((IntegerSolution) solution.variables().get(0)).variables().toArray())
                .map(String::valueOf).collect(Collectors.joining(" "));
        String cu = Arrays.stream(((IntegerSolution) solution.variables().get(1)).variables().toArray())
                .map(String::valueOf).collect(Collectors.joining(" "));
        return du + " " + cu
                + "," + Arrays.toString(solution.objectives()).replace(",", " ")
                + "," + Arrays.toString(solution.constraints()).replace(",", " ");
    }
}

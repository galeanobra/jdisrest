package es.unex.jdisrest.config;

import org.uma.jmetal.solution.doublesolution.repairsolution.RepairDoubleSolution;
import org.uma.jmetal.solution.doublesolution.repairsolution.impl.RepairDoubleSolutionWithBoundValue;

/**
 * The repair that the catalogue operators of the configuration files use: a value that leaves its
 * bounds moves to the nearest bound, as with jMetal's {@link RepairDoubleSolutionWithBoundValue},
 * and a variable whose lower and upper bounds are equal takes that value.
 *
 * <p>jMetal's repair checks {@code lowerBound < upperBound} on every call, before it looks at the
 * value. A problem that fixes a variable by giving it equal bounds therefore makes every operator
 * that repairs through it throw an {@code InvalidConditionException} whenever it touches that
 * variable: BLX-&alpha;, arithmetic and whole arithmetic crossover on every crossover, uniform and
 * L&eacute;vy flight mutation whenever the variable is picked. Inside a jdisrest master that
 * happens while a task is created, so the worker's task request fails. For bounds that differ this
 * repair returns exactly what jMetal's does, so the operators behave and draw random numbers as
 * before; a lower bound greater than the upper one is still rejected by jMetal's check.
 *
 * <p>Stateless and thread-safe; {@link #INSTANCE} is shared by every operator the catalogues
 * build.
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
@SuppressWarnings("serial")
final class BoundRepair implements RepairDoubleSolution {

    /** The shared instance. */
    static final BoundRepair INSTANCE = new BoundRepair();

    private final RepairDoubleSolution clamp = new RepairDoubleSolutionWithBoundValue();

    private BoundRepair() {
    }

    @Override
    public double repairSolutionVariableValue(double value, double lowerBound, double upperBound) {
        return lowerBound == upperBound
                ? lowerBound
                : clamp.repairSolutionVariableValue(value, lowerBound, upperBound);
    }
}

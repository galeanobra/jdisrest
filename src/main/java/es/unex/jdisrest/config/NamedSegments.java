package es.unex.jdisrest.config;

import org.uma.jmetal.solution.compositesolution.CompositeSolution;

import java.util.List;

/**
 * A problem whose solutions are {@link CompositeSolution}s and that names their segments for the
 * configuration files, which take the name of a segment as the prefix of its operator keys
 * ({@code <name>.crossover}, {@code <name>.mutation.probability}...). A composite problem that does
 * not implement it gets the default names of {@link SolutionLayout#of}: {@code real},
 * {@code integer} and {@code binary}, numbered when an encoding repeats.
 *
 * <pre>
 * public class MyProblem implements Problem&lt;CompositeSolution&gt;, NamedSegments {
 *     ...
 *     &#64;Override
 *     public List&lt;String&gt; segmentNames() {
 *         return List.of("counts", "weights", "switches");
 *     }
 * }
 * </pre>
 *
 * @author Jesús Galeano Brajones (Universidad de Extremadura)
 */
public interface NamedSegments {

    /**
     * The names of the segments of the problem's solutions, one per component of its
     * {@link CompositeSolution}s, in their order. Each name starts with a letter and holds only
     * letters, digits and underscores, so that it can prefix a key in a properties file, and no
     * two are equal.
     *
     * @return the names, as many as the components of a solution
     */
    List<String> segmentNames();
}

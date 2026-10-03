package es.unex.jdisrest.distributed;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The parts of {@link SteadyStateEvolutionaryAlgorithm} that need no running master: how the two
 * offspring of a task are taken from whatever the crossover returns ({@code child} and
 * {@code secondChild}). The rest of the class starts a REST server in its constructor and is
 * exercised end to end instead.
 */
class SteadyStateEvolutionaryAlgorithmTest {

    @Test
    void firstTwoChildrenFeedTheTwoOffspring() {
        List<String> children = List.of("a", "b", "c");

        assertEquals("a", SteadyStateEvolutionaryAlgorithm.child(children, 0));
        assertEquals("b", SteadyStateEvolutionaryAlgorithm.child(children, 1));
    }

    @Test
    void childPastTheLastIsTheLastChild() {
        List<String> children = List.of("only");

        assertEquals("only", SteadyStateEvolutionaryAlgorithm.child(children, 0));
        assertEquals("only", SteadyStateEvolutionaryAlgorithm.child(children, 1),
                "a one-child crossover must not fail task creation");
    }

    @Test
    void secondChildOfATwoChildCrossoverNeedsNoSecondMating() {
        AtomicInteger matings = new AtomicInteger();

        assertEquals("b", SteadyStateEvolutionaryAlgorithm.secondChild(List.of("a", "b", "c"),
                () -> { matings.incrementAndGet(); return List.of("x"); }));
        assertEquals(0, matings.get(), "the random stream of a two-child crossover is unchanged");
    }

    @Test
    void singleChildCrossoverMatesASecondPairForTheSecondOffspring() {
        // With a single child for both, the two offspring would be copies that only the mutation
        // may tell apart, and the duplicate filter never compares siblings.
        AtomicInteger matings = new AtomicInteger();

        assertEquals("other", SteadyStateEvolutionaryAlgorithm.secondChild(List.of("only"),
                () -> { matings.incrementAndGet(); return List.of("other"); }));
        assertEquals(1, matings.get());
    }

    @Test
    void noChildIsAClearError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.child(new ArrayList<String>(), 0));
        assertTrue(e.getMessage().contains("at least one child"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> SteadyStateEvolutionaryAlgorithm.child(null, 1));
        assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.secondChild(List.of(), () -> List.of("x")));
        assertThrows(IllegalStateException.class,
                () -> SteadyStateEvolutionaryAlgorithm.secondChild(List.of("only"), List::<String>of),
                "a second mating that yields no child fails as the first would");
    }
}

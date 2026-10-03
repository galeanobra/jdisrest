package es.unex.jdisrest.distributed.algorithms.steadystate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where MOEA/D puts a solution while the population fills ({@link MOEAD#fillingSlot}): the slot of
 * its own subproblem when free, so that member {@code k} ends up as the solution of subproblem
 * {@code k}, otherwise the nearest free one.
 */
class MOEADTest {

    /** Slots of a population of {@code size}, with the given ones taken. */
    static List<String> slots(int size, int... taken) {
        List<String> slots = new ArrayList<>(Arrays.asList(new String[size]));
        for (int k : taken) {
            slots.set(k, "s" + k);
        }
        return slots;
    }

    @Test
    void freeSlotOfTheSubproblemIsTaken() {
        assertEquals(3, MOEAD.fillingSlot(slots(5, 0, 1), 3, new int[] {3, 2, 4}));
    }

    @Test
    void takenSlotFallsBackToTheNearestFreeNeighbour() {
        assertEquals(4, MOEAD.fillingSlot(slots(6, 3, 2), 3, new int[] {3, 2, 4, 1}),
                "the neighbours are visited nearest first, skipping taken slots");
    }

    @Test
    void takenNeighbourhoodFallsBackToTheLowestFreeSlot() {
        assertEquals(1, MOEAD.fillingSlot(slots(6, 0, 3, 4, 5), 4, new int[] {4, 5, 3}));
    }

    @Test
    void noFreeSlotGivesMinusOne() {
        assertEquals(-1, MOEAD.fillingSlot(slots(3, 0, 1, 2), 1, new int[] {1, 0}));
    }

    @Test
    void neighbourhoodThatDoesNotStartWithItsSubproblemStillPrefersIt() {
        // Identical weight vectors can sort another subproblem first in a neighbourhood.
        assertEquals(2, MOEAD.fillingSlot(slots(4), 2, new int[] {1, 2}));
    }
}

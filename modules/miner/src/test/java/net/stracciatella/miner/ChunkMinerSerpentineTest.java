package net.stracciatella.miner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The corridor plan, checked without a game. A plan that skips a column
 * silently leaves blocks standing, and a plan that jumps between columns
 * makes the bot walk over ground it has already dug out — both would only
 * show up in-game as a slow run or a timeout, never as a clear failure.
 */
class ChunkMinerSerpentineTest {

    private static final int CHUNK_SIZE = 16;
    private static final int COLUMNS = CHUNK_SIZE * CHUNK_SIZE;

    @Test
    void everyColumnIsVisitedExactlyOnce() {
        for (int slab = 0; slab < 4; slab++) {
            List<Integer> order = SerpentinePlan.order(slab);
            assertEquals(COLUMNS, order.size(), "slab " + slab + " column count");
            Set<Integer> unique = new HashSet<>(order);
            assertEquals(COLUMNS, unique.size(), "slab " + slab + " has duplicate columns");
            for (int packed : order) {
                assertTrue(packed >= 0 && packed < COLUMNS, "column out of the chunk: " + packed);
            }
        }
    }

    @Test
    void consecutiveColumnsAreNeighbours() {
        for (int slab = 0; slab < 2; slab++) {
            List<Integer> order = SerpentinePlan.order(slab);
            for (int i = 1; i < order.size(); i++) {
                int prevX = order.get(i - 1) % CHUNK_SIZE;
                int prevZ = order.get(i - 1) / CHUNK_SIZE;
                int x = order.get(i) % CHUNK_SIZE;
                int z = order.get(i) / CHUNK_SIZE;
                int distance = Math.abs(x - prevX) + Math.abs(z - prevZ);
                assertEquals(1, distance,
                        "slab " + slab + " step " + i + " jumps from (" + prevX + "," + prevZ
                                + ") to (" + x + "," + z + ")");
            }
        }
    }

    @Test
    void theNextSlabStartsWhereThePreviousOneEnded() {
        List<Integer> first = SerpentinePlan.order(0);
        List<Integer> second = SerpentinePlan.order(1);
        assertEquals(first.get(first.size() - 1), second.get(0),
                "descending should not send the bot back across the chunk");
    }
}

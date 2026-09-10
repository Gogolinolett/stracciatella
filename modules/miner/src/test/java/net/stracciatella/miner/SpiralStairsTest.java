package net.stracciatella.miner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The staircase geometry, checked without a game.
 *
 * <p>Every property here is one the bot's way out depends on, and none of them
 * shows up in-game as anything but a stuck run: a ramp with a two-block step in it
 * cannot be climbed at all (the mesh refuses {@code dy < -1}), a ring with a gap
 * in it is a ledge that ends in mid-air, and a predicate claiming more cells than
 * it should leaves the chunk full of pillars the miner reports as finished.
 *
 * <p>Written against the chunk-local integer API, which is the reason that API
 * exists — see the note on {@link SpiralStairs} about {@code ChunkPos} throwing
 * from its static initialiser outside a running game.
 */
class SpiralStairsTest {

    private static final int TOP_Y = 64;
    private static final int LAST = SpiralStairs.CHUNK_SIZE - 1;

    @Test
    void ringIndexAndOffsetAreInverses() {
        for (int index = 0; index < SpiralStairs.RING_LENGTH; index++) {
            int[] offset = SpiralStairs.ringOffset(index);
            assertEquals(index, SpiralStairs.ringIndex(offset[0], offset[1]),
                    "round trip failed for index " + index);
        }
    }

    @Test
    void theRingCoversEveryEdgeColumnExactlyOnce() {
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < SpiralStairs.RING_LENGTH; index++) {
            int[] offset = SpiralStairs.ringOffset(index);
            assertTrue(seen.add(offset[0] * SpiralStairs.CHUNK_SIZE + offset[1]),
                    "index " + index + " repeats a column");
        }
        // Counted from the chunk rather than against the constant: a ring that is
        // 60 cells long but misses a corner would satisfy a size check.
        int edgeColumns = 0;
        for (int x = 0; x < SpiralStairs.CHUNK_SIZE; x++) {
            for (int z = 0; z < SpiralStairs.CHUNK_SIZE; z++) {
                if (x == 0 || x == LAST || z == 0 || z == LAST) {
                    edgeColumns++;
                    assertTrue(seen.contains(x * SpiralStairs.CHUNK_SIZE + z),
                            "edge column " + x + "," + z + " is not on the ring");
                }
            }
        }
        assertEquals(edgeColumns, seen.size());
    }

    @Test
    void consecutiveStepsAreOneAcrossAndOneDown() {
        for (int index = 1; index < SpiralStairs.RING_LENGTH; index++) {
            int[] previous = SpiralStairs.ringOffset(index - 1);
            int[] current = SpiralStairs.ringOffset(index);
            int horizontal = Math.abs(current[0] - previous[0]) + Math.abs(current[1] - previous[1]);
            assertEquals(1, horizontal, "step " + index + " is not horizontally adjacent");
            assertEquals(SpiralStairs.stepY(TOP_Y, index - 1) - 1,
                    SpiralStairs.stepY(TOP_Y, index),
                    "step " + index + " drops by more than one");
        }
    }

    @Test
    void theCircuitClosesOntoItsOwnStart() {
        int[] first = SpiralStairs.ringOffset(0);
        int[] last = SpiralStairs.ringOffset(SpiralStairs.RING_LENGTH - 1);
        int horizontal = Math.abs(first[0] - last[0]) + Math.abs(first[1] - last[1]);
        assertEquals(1, horizontal, "the ring does not close");
        // One step down from the last lands a full circuit under the first, which
        // is what makes the next loop continue the same ramp instead of starting
        // a second one beside it.
        assertEquals(TOP_Y - SpiralStairs.RING_LENGTH,
                SpiralStairs.stepY(TOP_Y, SpiralStairs.RING_LENGTH - 1) - 1);
    }

    @Test
    void everyStepIsAStairCellAndTheCellsAroundItAreNot() {
        for (int index = 0; index < SpiralStairs.RING_LENGTH; index++) {
            int[] offset = SpiralStairs.ringOffset(index);
            int y = SpiralStairs.stepY(TOP_Y, index);
            assertTrue(SpiralStairs.isStairCell(TOP_Y, offset[0], offset[1], y),
                    "step " + index + " is not recognised");
            // The two cells above have to be free, or there is no headroom to
            // walk in and the ramp is a row of blocks in a solid wall.
            assertFalse(SpiralStairs.isStairCell(TOP_Y, offset[0], offset[1], y + 1),
                    "the headroom above step " + index + " is claimed");
            assertFalse(SpiralStairs.isStairCell(TOP_Y, offset[0], offset[1], y + 2),
                    "the second headroom cell above step " + index + " is claimed");
            assertFalse(SpiralStairs.isStairCell(TOP_Y, offset[0], offset[1], y - 1),
                    "the cell under step " + index + " is claimed");
        }
    }

    @Test
    void exactlyOneCellPerLayerIsAStairCell() {
        // The sweep's whole cost for the staircase — and the reason descending
        // through the bot's own column almost never meets one.
        for (int y = TOP_Y; y > TOP_Y - 3 * SpiralStairs.RING_LENGTH; y--) {
            int claimed = 0;
            for (int x = 0; x < SpiralStairs.CHUNK_SIZE; x++) {
                for (int z = 0; z < SpiralStairs.CHUNK_SIZE; z++) {
                    if (SpiralStairs.isStairCell(TOP_Y, x, z, y)) {
                        claimed++;
                    }
                }
            }
            assertEquals(1, claimed, "layer y=" + y + " claims " + claimed + " stair cells");
        }
    }

    @Test
    void theTwoDirectionsAgree() {
        // The repair asks "which column of this layer", the sweep asks "is this
        // cell a step". They must be the same fact: a repair that rebuilds a
        // cell the sweep then mines is a bot that fills in its own staircase
        // forever, and one that names a different cell leaves the real step out
        // and builds a pillar beside it.
        for (int y = TOP_Y + 2; y > TOP_Y - 2 * SpiralStairs.RING_LENGTH; y--) {
            int index = SpiralStairs.ringIndexAt(TOP_Y, y);
            for (int x = 0; x < SpiralStairs.CHUNK_SIZE; x++) {
                for (int z = 0; z < SpiralStairs.CHUNK_SIZE; z++) {
                    boolean claimed = SpiralStairs.isStairCell(TOP_Y, x, z, y);
                    assertEquals(index >= 0 && SpiralStairs.ringIndex(x, z) == index, claimed,
                            "disagreement at " + x + "," + z + " y=" + y);
                }
            }
        }
    }

    @Test
    void nothingAboveTheTopIsAStairCell() {
        for (int index = 0; index < SpiralStairs.RING_LENGTH; index++) {
            int[] offset = SpiralStairs.ringOffset(index);
            assertFalse(SpiralStairs.isStairCell(TOP_Y, offset[0], offset[1], TOP_Y + 1),
                    "a cell above the mined range is claimed at index " + index);
        }
    }

    @Test
    void theChunkInteriorIsNeverAStairCell() {
        for (int x = 1; x < LAST; x++) {
            for (int z = 1; z < LAST; z++) {
                for (int y = TOP_Y; y > TOP_Y - 70; y--) {
                    assertFalse(SpiralStairs.isStairCell(TOP_Y, x, z, y),
                            "interior column " + x + "," + z + " claimed at y=" + y);
                }
            }
        }
    }

    @Test
    void columnsOutsideTheChunkAreNotOnTheRing() {
        assertEquals(-1, SpiralStairs.ringIndex(-1, 0));
        assertEquals(-1, SpiralStairs.ringIndex(0, -1));
        assertEquals(-1, SpiralStairs.ringIndex(SpiralStairs.CHUNK_SIZE, 0));
        assertEquals(-1, SpiralStairs.ringIndex(0, SpiralStairs.CHUNK_SIZE));
        assertFalse(SpiralStairs.isStairCell(TOP_Y, -1, 0, TOP_Y));
        assertFalse(SpiralStairs.isStairCell(TOP_Y, SpiralStairs.CHUNK_SIZE, 0, TOP_Y));
    }
}

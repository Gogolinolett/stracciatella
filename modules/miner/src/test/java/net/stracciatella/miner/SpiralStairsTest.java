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

    /**
     * Where the scans below start. Nothing but a place to begin — the geometry
     * has no notion of a top any more, which is the whole of
     * {@link #theRampIsPinnedToTheWorldsHeightGrid}.
     */
    private static final int SCAN_TOP = 64;

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
        // Asked layer by layer rather than step by step, because a layer is now
        // the only thing the geometry is given: one down is true by construction
        // and one across is the claim.
        for (int y = SCAN_TOP; y > SCAN_TOP - 2 * SpiralStairs.RING_LENGTH; y--) {
            int[] above = SpiralStairs.ringOffset(SpiralStairs.ringIndexAt(y));
            int[] below = SpiralStairs.ringOffset(SpiralStairs.ringIndexAt(y - 1));
            int horizontal = Math.abs(below[0] - above[0]) + Math.abs(below[1] - above[1]);
            assertEquals(1, horizontal,
                    "the step under y=" + y + " is not horizontally adjacent to it");
        }
    }

    @Test
    void theCircuitClosesOntoItsOwnStart() {
        int[] first = SpiralStairs.ringOffset(0);
        int[] last = SpiralStairs.ringOffset(SpiralStairs.RING_LENGTH - 1);
        int horizontal = Math.abs(first[0] - last[0]) + Math.abs(first[1] - last[1]);
        assertEquals(1, horizontal, "the ring does not close");
        // A full circuit lands directly under where it started, which is what
        // makes the next loop continue the same ramp instead of starting a
        // second one beside it.
        assertEquals(SpiralStairs.ringIndexAt(SCAN_TOP),
                SpiralStairs.ringIndexAt(SCAN_TOP - SpiralStairs.RING_LENGTH));
    }

    @Test
    void everyStepIsAStairCellAndTheCellsAroundItAreNot() {
        for (int y = SCAN_TOP; y > SCAN_TOP - SpiralStairs.RING_LENGTH; y--) {
            int[] offset = SpiralStairs.ringOffset(SpiralStairs.ringIndexAt(y));
            assertTrue(SpiralStairs.isStairCell(offset[0], offset[1], y),
                    "the step at y=" + y + " is not recognised");
            // The two cells above have to be free, or there is no headroom to
            // walk in and the ramp is a row of blocks in a solid wall.
            assertFalse(SpiralStairs.isStairCell(offset[0], offset[1], y + 1),
                    "the headroom above the step at y=" + y + " is claimed");
            assertFalse(SpiralStairs.isStairCell(offset[0], offset[1], y + 2),
                    "the second headroom cell above the step at y=" + y + " is claimed");
            assertFalse(SpiralStairs.isStairCell(offset[0], offset[1], y - 1),
                    "the cell under the step at y=" + y + " is claimed");
        }
    }

    @Test
    void exactlyOneCellPerLayerIsAStairCell() {
        // The sweep's whole cost for the staircase — and the reason descending
        // through the bot's own column almost never meets one.
        for (int y = SCAN_TOP; y > SCAN_TOP - 3 * SpiralStairs.RING_LENGTH; y--) {
            int claimed = 0;
            for (int x = 0; x < SpiralStairs.CHUNK_SIZE; x++) {
                for (int z = 0; z < SpiralStairs.CHUNK_SIZE; z++) {
                    if (SpiralStairs.isStairCell(x, z, y)) {
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
        for (int y = SCAN_TOP + 2; y > SCAN_TOP - 2 * SpiralStairs.RING_LENGTH; y--) {
            int index = SpiralStairs.ringIndexAt(y);
            for (int x = 0; x < SpiralStairs.CHUNK_SIZE; x++) {
                for (int z = 0; z < SpiralStairs.CHUNK_SIZE; z++) {
                    assertEquals(SpiralStairs.ringIndex(x, z) == index,
                            SpiralStairs.isStairCell(x, z, y),
                            "disagreement at " + x + "," + z + " y=" + y);
                }
            }
        }
    }

    @Test
    void theRampIsPinnedToTheWorldsHeightGrid() {
        // The regression this class exists to prevent a second time. The ring
        // position used to be counted down from a topY the miner took from
        // wherever the bot was standing when the run began, so restarting a run
        // two blocks lower rotated every step in the shaft by two and the sweep
        // ate the staircase it had just left. The layer alone may decide.
        assertEquals(0, SpiralStairs.ringIndexAt(0));
        assertEquals(1, SpiralStairs.ringIndexAt(-1));
        assertEquals(SpiralStairs.RING_LENGTH - 1, SpiralStairs.ringIndexAt(1));
        assertEquals(0, SpiralStairs.ringIndexAt(SpiralStairs.RING_LENGTH));
        assertEquals(21, SpiralStairs.ringIndexAt(39));

        // Pinning it to the height grid puts the wrap at y=0, which is inside
        // every real shaft: the miner's default floor is -59 and a chunk started
        // at the surface crosses zero on the way down. A ramp that stepped twice
        // or not at all there would be a two-block climb nobody can make, forty
        // layers into a run.
        for (int y = 2; y > -SpiralStairs.RING_LENGTH; y--) {
            int[] above = SpiralStairs.ringOffset(SpiralStairs.ringIndexAt(y));
            int[] below = SpiralStairs.ringOffset(SpiralStairs.ringIndexAt(y - 1));
            assertEquals(1, Math.abs(below[0] - above[0]) + Math.abs(below[1] - above[1]),
                    "the ramp breaks stride between y=" + y + " and y=" + (y - 1));
        }
    }

    @Test
    void theChunkInteriorIsNeverAStairCell() {
        for (int x = 1; x < LAST; x++) {
            for (int z = 1; z < LAST; z++) {
                for (int y = SCAN_TOP; y > SCAN_TOP - 70; y--) {
                    assertFalse(SpiralStairs.isStairCell(x, z, y),
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
        assertFalse(SpiralStairs.isStairCell(-1, 0, SCAN_TOP));
        assertFalse(SpiralStairs.isStairCell(SpiralStairs.CHUNK_SIZE, 0, SCAN_TOP));
    }

    /**
     * The way out looks past a step at the ground outside the chunk, so every
     * direction it is given has to land there — one block over and outside —
     * and a corner, which has two outsides, has to get both.
     */
    @Test
    void outwardLeadsOutOfTheChunk() {
        for (int index = 0; index < SpiralStairs.RING_LENGTH; index++) {
            int[] at = SpiralStairs.ringOffset(index);
            boolean corner = (at[0] == 0 || at[0] == LAST) && (at[1] == 0 || at[1] == LAST);
            int[][] out = SpiralStairs.outward(index);
            assertEquals(corner ? 2 : 1, out.length, "directions at index " + index);
            for (int[] d : out) {
                assertEquals(1, Math.abs(d[0]) + Math.abs(d[1]), "not a unit step at index " + index);
                int x = at[0] + d[0];
                int z = at[1] + d[1];
                assertTrue(x < 0 || x > LAST || z < 0 || z > LAST,
                        "index " + index + " points at " + x + "," + z + ", inside the chunk");
            }
        }
    }
}

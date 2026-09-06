package net.stracciatella.miner;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which the chunk miner walks a chunk's 256 columns: along a
 * row, step over, back along the next — and the first row alternates per
 * slab, so finishing one slab leaves the bot next to where the one below it
 * starts.
 *
 * <p>Its own class, free of every Minecraft type, so the two properties that
 * matter can be asserted in a plain unit test: every column is visited
 * exactly once, and consecutive columns are neighbours. In-game those
 * failures would only ever surface as blocks left standing or a run that
 * mysteriously takes too long.
 */
public final class SerpentinePlan {

    public static final int CHUNK_SIZE = 16;

    private SerpentinePlan() {
    }

    /**
     * Column offsets from the chunk corner, packed as {@code dz * 16 + dx}.
     *
     * @param slabIndex 0 for the topmost slab, counting down
     */
    public static List<Integer> order(int slabIndex) {
        boolean reverseRows = Math.floorMod(slabIndex, 2) == 1;
        List<Integer> columns = new ArrayList<>(CHUNK_SIZE * CHUNK_SIZE);
        for (int r = 0; r < CHUNK_SIZE; r++) {
            int dz = reverseRows ? CHUNK_SIZE - 1 - r : r;
            for (int c = 0; c < CHUNK_SIZE; c++) {
                int dx = (r % 2 == 0) ? c : CHUNK_SIZE - 1 - c;
                columns.add(dz * CHUNK_SIZE + dx);
            }
        }
        return columns;
    }
}

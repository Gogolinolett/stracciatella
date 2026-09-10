package net.stracciatella.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The staircase a chunk miner leaves standing so it can walk back out of its own
 * pit — and walk back in.
 *
 * <p>It is needed because of a hard limit one layer down: a mesh edge climbs at
 * most <em>one</em> block ({@code ChunkMeshBuilder.isBlockReachable} returns
 * false for {@code dy < -1}) and drops at most three. A bot that has cleared
 * twenty slabs is forty blocks down a sheer 16x16 shaft, and no amount of
 * pathfinding gets it out of that. So one block in every layer is never mined,
 * and those blocks form a ramp.
 *
 * <p><b>A pure function, not a plan.</b> Nothing is stored, nothing is built up
 * front, and no cursor records how far the staircase has got: given a top height
 * and a position, {@link #isStairCell} simply answers whether that position is a
 * step. That is the same principle the rest of the chunk miner is built on —
 * progress is read back out of the world rather than remembered — and it is what
 * makes the answer the same for the sweep deciding what to skip, for the finish
 * condition deciding whether a slab is done, and for the repair deciding what to
 * put back. Three callers that must agree, agreeing because there is only one
 * answer to agree with.
 *
 * <p><b>Pure in its arguments, too.</b> The geometry is expressed in chunk-local
 * integers, with thin {@link BlockPos} overloads on top. Not a style preference:
 * constructing a {@code ChunkPos} outside a running game throws from its static
 * initialiser, so a signature taking Minecraft types cannot be unit tested at
 * all — and geometry this load-bearing is exactly what wants testing without a
 * game. Same reason {@code BotAlarm.isAttackerInRange} takes bare doubles.
 *
 * <h2>The geometry</h2>
 *
 * <p>One step per layer, walking the chunk's outer ring: 16 along the north edge,
 * down the east edge, back along the south, up the west — {@value #RING_LENGTH}
 * cells, each horizontally adjacent to the next, the last adjacent to the first.
 * Step {@code i} sits one block lower than step {@code i - 1}, so the ramp falls
 * at exactly the 1:1 slope the mesh can climb, and after a full circuit it is
 * {@value #RING_LENGTH} blocks deeper and directly under where it started.
 *
 * <p>The outer ring rather than a tighter spiral in the middle: its outward side
 * is the chunk wall, which is never mined, so every step has rock on one side and
 * a view of the shaft on the other — a quarry staircase. A ring further in would
 * be a free-standing ledge with a drop on both sides.
 */
public final class SpiralStairs {

    static final int CHUNK_SIZE = 16;

    /** Cells in one circuit of the ring: {@code 4 * 16 - 4}. */
    public static final int RING_LENGTH = 4 * CHUNK_SIZE - 4;

    private SpiralStairs() {
    }

    // --- Pure geometry, in chunk-local coordinates ---

    /**
     * Whether the chunk-local column {@code (localX, localZ)} carries a step at
     * height {@code y}.
     *
     * @param topY height of the first step — the top of the mined range, so the
     *             ramp starts at the surface the bot arrived on
     */
    public static boolean isStairCell(int topY, int localX, int localZ, int y) {
        if (y > topY) {
            return false;
        }
        int index = ringIndex(localX, localZ);
        if (index < 0) {
            return false;
        }
        // How far this cell is below its column's first step. A whole number of
        // circuits means this is that column's step on a later loop.
        int below = topY - y - index;
        return below >= 0 && below % RING_LENGTH == 0;
    }

    /** Height of step {@code index}, counting down from {@code topY}. */
    public static int stepY(int topY, int index) {
        return topY - index;
    }

    /**
     * The ring position carrying the step at height {@code y}, or -1 when
     * {@code y} is above the ramp's top.
     *
     * <p>The same fact {@link #isStairCell} states, asked the other way round,
     * and the repair needs it that way: it has a layer and wants the one column
     * in it that must not stay empty, where scanning all 256 columns to find the
     * single hit would be the same answer computed 256 times. The two agree by
     * construction — {@code isStairCell} holds exactly when the column's ring
     * index equals this one — and {@code theTwoDirectionsAgree} pins that.
     */
    public static int ringIndexAt(int topY, int y) {
        if (y > topY) {
            return -1;
        }
        return Math.floorMod(topY - y, RING_LENGTH);
    }

    /**
     * Position of a column along the ring, or -1 when it is not on the ring.
     *
     * <p>The inverse of {@link #ringOffset}, written out rather than searched:
     * this is asked for every cell of a 256-column slab on every pass, and a
     * linear scan of 60 offsets for each of them is work nobody needs to do.
     */
    public static int ringIndex(int localX, int localZ) {
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            return -1;
        }
        int last = CHUNK_SIZE - 1;
        if (localZ == 0) {
            return localX;
        }
        if (localX == last) {
            return last + localZ;
        }
        if (localZ == last) {
            return 2 * last + (last - localX);
        }
        if (localX == 0) {
            return 3 * last + (last - localZ);
        }
        return -1;
    }

    /** The chunk-local {@code {x, z}} of ring position {@code index}. */
    public static int[] ringOffset(int index) {
        int last = CHUNK_SIZE - 1;
        if (index <= last) {
            return new int[] {index, 0};
        }
        if (index <= 2 * last) {
            return new int[] {last, index - last};
        }
        if (index <= 3 * last) {
            return new int[] {last - (index - 2 * last), last};
        }
        return new int[] {0, last - (index - 3 * last)};
    }

    // --- World-coordinate conveniences ---

    /** @see #isStairCell(int, int, int, int) */
    public static boolean isStairCell(ChunkPos chunk, int topY, BlockPos pos) {
        return isStairCell(topY, pos.getX() - chunk.getMinBlockX(),
                pos.getZ() - chunk.getMinBlockZ(), pos.getY());
    }

    /**
     * The step at ring position {@code index} on its first circuit, or
     * {@code null} when {@code index} is not a ring position.
     */
    public static BlockPos step(ChunkPos chunk, int topY, int index) {
        if (index < 0 || index >= RING_LENGTH) {
            return null;
        }
        int[] offset = ringOffset(index);
        return new BlockPos(chunk.getMinBlockX() + offset[0], stepY(topY, index),
                chunk.getMinBlockZ() + offset[1]);
    }

    /**
     * The one cell of layer {@code y} that carries a step, or {@code null} above
     * the ramp's top.
     *
     * @see #ringIndexAt
     */
    public static BlockPos stepAt(ChunkPos chunk, int topY, int y) {
        int index = ringIndexAt(topY, y);
        if (index < 0) {
            return null;
        }
        int[] offset = ringOffset(index);
        return new BlockPos(chunk.getMinBlockX() + offset[0], y,
                chunk.getMinBlockZ() + offset[1]);
    }
}

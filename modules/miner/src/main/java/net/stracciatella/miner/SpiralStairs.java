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
 * front, and no cursor records how far the staircase has got: given a position,
 * {@link #isStairCell} simply answers whether that position is a
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
 * The step one layer down sits one ring position further along, so the ramp falls
 * at exactly the 1:1 slope the mesh can climb, and after a full circuit it is
 * {@value #RING_LENGTH} blocks deeper and directly under where it started.
 *
 * <p><b>The layer alone decides which column, and that is the whole of the
 * bug this used to have.</b> The ring position is {@code -y mod} {@value
 * #RING_LENGTH} — pinned to the world's own height grid, nothing else. It used
 * to be measured down from a {@code topY} the miner set from wherever the bot
 * happened to be standing when the run began, which made the answer a property
 * of the run rather than of the chunk. Any second start — the player restarting
 * a stopped run, or a restock resuming one — arrived at a different height and
 * rotated every step in the shaft by that difference, so the sweep mined the
 * staircase it had left on the way down and left a fresh one beside it. Observed
 * as {@code staircase from y=103} and then {@code y=101} in the same shaft. A
 * chunk miner that finds its place again by reading the world cannot have
 * geometry that depends on when it started reading.
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
     */
    public static boolean isStairCell(int localX, int localZ, int y) {
        int index = ringIndex(localX, localZ);
        return index >= 0 && index == ringIndexAt(y);
    }

    /**
     * The ring position carrying the step at height {@code y}.
     *
     * <p>The same fact {@link #isStairCell} states, asked the other way round,
     * and the repair needs it that way: it has a layer and wants the one column
     * in it that must not stay empty, where scanning all 256 columns to find the
     * single hit would be the same answer computed 256 times. The two agree by
     * construction — {@code isStairCell} holds exactly when the column's ring
     * index equals this one — and {@code theTwoDirectionsAgree} pins that.
     *
     * <p>Every layer has one, including layers no run will ever reach. Capping
     * the ramp is the caller's business and the miner already does it, by only
     * ever asking about layers inside the range it is clearing; a cap in here
     * would have to be told where the range ends, and being told that is what
     * made the staircase move between runs.
     */
    public static int ringIndexAt(int y) {
        return Math.floorMod(-y, RING_LENGTH);
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

    /** @see #isStairCell(int, int, int) */
    public static boolean isStairCell(ChunkPos chunk, BlockPos pos) {
        return isStairCell(pos.getX() - chunk.getMinBlockX(),
                pos.getZ() - chunk.getMinBlockZ(), pos.getY());
    }

    /**
     * The one cell of layer {@code y} that carries a step.
     *
     * @see #ringIndexAt
     */
    public static BlockPos stepAt(ChunkPos chunk, int y) {
        int[] offset = ringOffset(ringIndexAt(y));
        return new BlockPos(chunk.getMinBlockX() + offset[0], y,
                chunk.getMinBlockZ() + offset[1]);
    }
}

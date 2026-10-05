package net.stracciatella.pathfinding.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The two questions every part of the module asks about a block: can a body be
 * in it, and can a body stand on it. Asked here and nowhere else, so the mesh
 * that plans a route and the walker that runs it cannot disagree about it.
 *
 * <p>Both are answered by collision, not by {@code isAir()}. Air was the old
 * test, and the overworld is not made of air: short grass, flowers, ferns, a
 * torch, a rail or a carpet are not air, so every one of them made the cell
 * above a floor "solid" — in a plains biome most of the surface had no node at
 * all, and every edge across a tuft of grass was refused.
 */
public final class Terrain {

    /**
     * Lowest collision top a block may have and still count as a floor, as a
     * fraction of a block. Low enough for a dirt path or farmland (15/16) and
     * soul sand or mud (14/16), high enough to keep out slabs (8/16): a node
     * stands one whole block above its floor block, and a half-block floor would
     * plan steps half a block higher than they are.
     */
    private static final double MIN_FLOOR_TOP = 0.8;
    /** Vanilla's step height: anything up to this much above the feet is walked onto. */
    private static final double STEP_HEIGHT = 0.6;
    /** Half of a player's 0.6-wide hitbox. */
    private static final double BODY_HALF_WIDTH = 0.3;
    /** Spacing of the samples along a straight walk. */
    private static final double LINE_SAMPLE_STEP = 0.25;

    private Terrain() {
    }

    /**
     * Whether a body may occupy {@code pos}: nothing to collide with, no fluid,
     * and nothing that hurts or traps on contact. Water stays out on purpose —
     * the walker has no swimming, and a route it cannot walk is worse than none.
     */
    public static boolean isPassable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(level, pos).isEmpty()
                && !isHazard(state);
    }

    /**
     * Whether a body may stand on {@code pos}: a full top face, or a near-full
     * one that covers the whole block — the dirt paths of a village, farmland,
     * soul sand. A block that hurts to stand on is not a floor.
     */
    public static boolean isStandable(BlockGetter level, BlockPos pos, Entity entity) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        if (state.entityCanStandOn(level, pos, entity)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        return top >= MIN_FLOOR_TOP && top <= 1.0
                && shape.min(Direction.Axis.X) <= 0.0 && shape.max(Direction.Axis.X) >= 1.0
                && shape.min(Direction.Axis.Z) <= 0.0 && shape.max(Direction.Axis.Z) >= 1.0;
    }

    /**
     * Whether {@code pos} has anything to collide with at all — the walker's
     * question about the ground ahead (is there a hole) and about the block it
     * is about to land on, where a fluid or a flower counts as nothing.
     */
    public static boolean hasCollision(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Whether the column at {@code (x, z)} rises more than a step above
     * {@code feetY} — something only a jump gets onto. The cell asked is the one
     * a step's height above the feet: on a dirt path, whose top sits a sixteenth
     * below a full block, the cell at foot level is the next ordinary floor
     * block, and asking that one read every block of a path as a step.
     */
    public static boolean isStepUp(BlockGetter level, int x, int z, double feetY) {
        BlockPos pos = new BlockPos(x, (int) Math.floor(feetY + STEP_HEIGHT), z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        return !shape.isEmpty() && pos.getY() + shape.max(Direction.Axis.Y) - feetY > STEP_HEIGHT;
    }

    /**
     * Whether a body can walk the straight line from {@code (fromX, fromZ)} to
     * {@code (toX, toZ)} on the floor at {@code floorY}: a standable floor and
     * {@code headroom} passable cells over it along the whole line, under the
     * body's whole width. Asked every quarter block, at the centre and both
     * edges of the body, so no corner of a hole or a wall slips in between.
     *
     * <p>The walker's licence to leave the node-by-node route — a grid path of
     * straight and diagonal steps is not how anyone crosses open ground.
     */
    public static boolean isStraightWalk(BlockGetter level, Entity entity, double fromX, double fromZ,
                                         double toX, double toZ, int floorY, int headroom) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / LINE_SAMPLE_STEP));
        double sideX = length < 1.0e-6 ? 0.0 : -dz / length * BODY_HALF_WIDTH;
        double sideZ = length < 1.0e-6 ? 0.0 : dx / length * BODY_HALF_WIDTH;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            for (int side = -1; side <= 1; side++) {
                int x = (int) Math.floor(fromX + dx * t + sideX * side);
                int z = (int) Math.floor(fromZ + dz * t + sideZ * side);
                if (!isStandable(level, pos.set(x, floorY, z), entity)) {
                    return false;
                }
                for (int up = 1; up <= headroom; up++) {
                    if (!isPassable(level, pos.set(x, floorY + up, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Collision-free blocks that a body must still not walk into. */
    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POWDER_SNOW);
    }
}

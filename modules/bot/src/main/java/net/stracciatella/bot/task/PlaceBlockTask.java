package net.stracciatella.bot.task;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Task to place a block at {@code placePos}. Completes when that position
 * holds a solid, fluid-free block.
 *
 * <p>Placement is not the mirror image of mining: you cannot aim at the
 * position where the block should go, because a raycast passes straight
 * through it. A block is placed on the side of an existing block you click,
 * so this task targets a <em>support</em> block adjacent to {@code placePos}
 * and interacts with the face pointing at it. That is why
 * {@link #targetPos()} returns the support, not the destination — the whole
 * LOOKING pipeline (aim, hit-result gate, re-approach) then works unchanged.
 */
public class PlaceBlockTask implements BotTask {

    private final BlockPos placePos;
    private final BlockPos supportPos;
    private final Direction face;
    private final Predicate<ItemStack> item;
    private final String itemName;
    private boolean complete = false;

    /**
     * Build a placement against a neighbour of the destination.
     *
     * @param placePos   where the block must end up
     * @param supportPos an adjacent block to build off; must share a face with
     *                   {@code placePos}
     * @param item       matches the stacks usable for this placement
     * @param itemName   short label for logs and status lines
     */
    public PlaceBlockTask(BlockPos placePos, BlockPos supportPos,
                          Predicate<ItemStack> item, String itemName) {
        if (supportPos.distManhattan(placePos) != 1) {
            throw new IllegalArgumentException(
                    "Support " + supportPos.toShortString() + " is not adjacent to place position "
                            + placePos.toShortString());
        }
        Direction toPlace = Direction.getNearest(
                placePos.getX() - supportPos.getX(),
                placePos.getY() - supportPos.getY(),
                placePos.getZ() - supportPos.getZ(),
                null);
        if (toPlace == null) {
            throw new IllegalArgumentException("Cannot derive placement face from "
                    + supportPos.toShortString() + " to " + placePos.toShortString());
        }
        this.placePos = placePos;
        this.supportPos = supportPos;
        this.face = toPlace;
        this.item = item;
        this.itemName = itemName;
    }

    /**
     * Find a block adjacent to {@code placePos} that can be built off — one
     * whose face toward {@code placePos} is sturdy, preferring the one whose
     * face is turned most toward {@code eye}. Returns {@code null} only when
     * nothing adjacent is sturdy at all, in which case the placement is
     * impossible from any angle and the caller should skip it rather than
     * enqueue a task that can only time out. Visibility ranks the candidates,
     * it does not filter them: a face the bot cannot see from where it stands
     * may well be visible two steps later, and LOOKING's re-approach exists
     * for exactly that.
     *
     * <p>Sturdiness alone is not enough, and picking the first sturdy face was
     * a bug with a misleading symptom. {@code Direction.values()} starts DOWN,
     * UP, so a gap in the floor almost always resolved to the block <em>above
     * it</em>, whose face toward the gap is its underside. A bot standing
     * beside the gap can never see an underside: the raycast lands on the same
     * block's side face, LOOKING's hit-result gate additionally requires the
     * direction to match for a placement, and the task burns its look timeout.
     * Three of those in a row and the run stopped with "out of filler blocks?"
     * while the hotbar was full of them.
     *
     * <p>Scoring by how directly the face points at the eye picks what a person
     * picks: the top of the block <em>under</em> the gap when there is one,
     * otherwise the side face of the block on the far side of it, whose face
     * toward the gap is turned back toward the bot. A face the eye is level
     * with or behind scores zero or less and is rejected outright.
     */
    public static BlockPos findSupport(Level level, BlockPos placePos, Vec3 eye) {
        BlockPos best = null;
        double bestFacing = Double.NEGATIVE_INFINITY;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = placePos.relative(dir);
            Direction toward = dir.getOpposite();
            if (!level.getBlockState(neighbor).isFaceSturdy(level, neighbor, toward)) {
                continue;
            }
            Vec3 normal = new Vec3(toward.getStepX(), toward.getStepY(), toward.getStepZ());
            Vec3 faceCenter = Vec3.atCenterOf(neighbor).add(normal.scale(0.5));
            Vec3 toEye = eye.subtract(faceCenter);
            double length = toEye.length();
            double facing = length < 1.0E-4 ? 0.0 : toEye.dot(normal) / length;
            if (facing > bestFacing) {
                bestFacing = facing;
                best = neighbor;
            }
        }
        return best;
    }

    /**
     * Where the block ends up — distinct from {@link #targetPos()}, which is
     * the block being clicked.
     */
    public BlockPos placePos() {
        return placePos;
    }

    @Override
    public BlockPos targetPos() {
        return supportPos;
    }

    @Override
    public String description() {
        return "Place " + itemName + " at " + placePos.getX() + ", " + placePos.getY() + ", "
                + placePos.getZ();
    }

    @Override
    public InteractionType interactionType() {
        return InteractionType.USE;
    }

    @Override
    public boolean isCurrentTargetComplete(Level level) {
        var state = level.getBlockState(placePos);
        // A fluid-free non-air block is the placement having landed. The fluid
        // check matters: sealing a water source succeeds only once the water
        // itself is gone, and a flowing-water state is neither air nor a
        // finished placement.
        if (!state.isAir() && state.getFluidState().isEmpty()) {
            complete = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean advanceToNextTarget() {
        return false;
    }

    @Override
    public boolean isFullyComplete() {
        return complete;
    }

    @Override
    public int maxInteractionTicks() {
        return 600;
    }

    @Override
    public Predicate<ItemStack> requiredItem() {
        return item;
    }

    @Override
    public Direction preferredFace() {
        return face;
    }
}

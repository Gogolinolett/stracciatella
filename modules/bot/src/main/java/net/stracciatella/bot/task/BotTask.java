package net.stracciatella.bot.task;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A single unit of work for the bot. Each task targets a block position
 * and defines how to interact with it and when it is complete.
 */
public interface BotTask {

    /**
     * The block position this task currently targets.
     * For multi-block tasks (e.g. tree chopping), this returns the current sub-target.
     */
    BlockPos targetPos();

    /**
     * Human-readable description for debug output and status display.
     */
    String description();

    /**
     * The type of interaction required (ATTACK for mining, USE for placing).
     */
    InteractionType interactionType();

    /**
     * Check if the current sub-target is complete.
     * For single-block tasks, returns true when the block is air.
     * For multi-block tasks, returns true when the current sub-target is done.
     */
    boolean isCurrentTargetComplete(Level level);

    /**
     * Advance to the next sub-target if this is a multi-block task.
     * Returns true if there is a next target, false if the task is fully done.
     */
    boolean advanceToNextTarget();

    /**
     * Whether the entire task is complete (all sub-targets done).
     */
    boolean isFullyComplete();

    /**
     * Maximum ticks allowed for a single block interaction before timeout.
     */
    int maxInteractionTicks();

    /**
     * The item this task needs in hand, or {@code null} to let the controller
     * pick the fastest tool for the target block (the default for mining).
     * USE tasks return the item they place — a pickaxe would be a nonsensical
     * choice there.
     */
    default Predicate<ItemStack> requiredItem() {
        return null;
    }

    /**
     * The face of {@link #targetPos()} this task must interact with, or
     * {@code null} to let the controller pick the face most visible from the
     * bot's eye (the default for mining, see
     * {@code BlockInteractor.faceTowardPlayer}).
     *
     * <p>Placement cannot use the most-visible face: a block is placed on the
     * side you click, so the face is dictated by where the new block must end
     * up, not by what is easiest to look at.
     */
    default Direction preferredFace() {
        return null;
    }
}

package net.stracciatella.bot.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Task to make one position free. Completes when the block is air and
 * nothing is about to fall into the hole.
 *
 * <p>Not "mine the block that is there": gravel or sand stacked above the
 * target drops into the cell the moment it opens, and a task that had
 * latched "air" at that point walked away from a cell that was full again
 * two ticks later. The chunk miner's first layer met exactly that — gravel
 * from the world above the slab — and it handled the fallen block as a
 * retry, at the price of a step retry each time. So a block standing in the
 * cell again after a confirmed break is the task's <i>next sub-target</i>,
 * the way the next log is for a tree, and the controller re-aims, picks the
 * tool for it and breaks it with a fresh budget. The controller decides when
 * to ask, after waiting out an incoming fall; this task only answers whether
 * the cell is standing.
 */
public class MineBlockTask implements BotTask {

    /**
     * How many fallen blocks one position is cleared of before the task gives
     * up. A gravel column that tall does not occur naturally; the bound only
     * stops a cell that refills for a reason other than falling from being
     * broken forever.
     */
    private static final int MAX_FALLS = 32;

    private final BlockPos pos;
    // What the last observation found. Starts as "standing" so a task that
    // has never looked is not reported complete.
    private boolean standing = true;
    private int falls = 0;

    public MineBlockTask(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public BlockPos targetPos() {
        return pos;
    }

    @Override
    public String description() {
        return "Mine block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + (falls > 0 ? " (fallen block " + falls + ")" : "");
    }

    @Override
    public InteractionType interactionType() {
        return InteractionType.ATTACK;
    }

    @Override
    public boolean isCurrentTargetComplete(Level level) {
        standing = !level.getBlockState(pos).isAir();
        return !standing;
    }

    /**
     * True when a block stands in the cell again after the last break was
     * confirmed — the fallen block is the next thing to mine here. False once
     * the cell is free, or when it has refilled more often than a falling
     * column could account for; the controller then reads
     * {@link #isFullyComplete()} to tell the two apart.
     */
    @Override
    public boolean advanceToNextTarget() {
        if (!standing || falls >= MAX_FALLS) {
            return false;
        }
        falls++;
        return true;
    }

    @Override
    public boolean isFullyComplete() {
        return !standing;
    }

    @Override
    public int maxInteractionTicks() {
        return 600;
    }
}

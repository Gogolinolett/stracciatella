package net.stracciatella.bot.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Task to mine a single block. Completes when the block becomes air.
 */
public class MineBlockTask implements BotTask {

    private final BlockPos pos;
    private boolean complete = false;

    public MineBlockTask(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public BlockPos targetPos() {
        return pos;
    }

    @Override
    public String description() {
        return "Mine block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    @Override
    public InteractionType interactionType() {
        return InteractionType.ATTACK;
    }

    @Override
    public boolean isCurrentTargetComplete(Level level) {
        if (level.getBlockState(pos).isAir()) {
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
}

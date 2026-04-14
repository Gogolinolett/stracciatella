package net.stracciatella.bot.task;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.scan.TreeInfo;

/**
 * Task to chop an entire tree by mining logs top-to-bottom.
 * Each log is a sub-target that completes when it becomes air.
 */
public class ChopTreeTask implements BotTask {

    private final TreeInfo tree;
    private final List<BlockPos> logs;
    private int currentIndex = 0;

    public ChopTreeTask(TreeInfo tree) {
        this.tree = tree;
        this.logs = tree.logs();
    }

    @Override
    public BlockPos targetPos() {
        if (currentIndex >= logs.size()) {
            return logs.get(logs.size() - 1);
        }
        return logs.get(currentIndex);
    }

    @Override
    public String description() {
        BlockPos base = tree.basePos();
        return "Chop tree at " + base.getX() + ", " + base.getY() + ", " + base.getZ()
                + " (" + logs.size() + " logs, " + (currentIndex) + " done)";
    }

    @Override
    public InteractionType interactionType() {
        return InteractionType.ATTACK;
    }

    @Override
    public boolean isCurrentTargetComplete(Level level) {
        if (currentIndex >= logs.size()) {
            return true;
        }
        return level.getBlockState(logs.get(currentIndex)).isAir();
    }

    @Override
    public boolean advanceToNextTarget() {
        currentIndex++;
        return currentIndex < logs.size();
    }

    @Override
    public boolean isFullyComplete() {
        return currentIndex >= logs.size();
    }

    @Override
    public int maxInteractionTicks() {
        return 300;
    }
}

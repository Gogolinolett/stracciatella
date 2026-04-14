package net.stracciatella.bot.task;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.stracciatella.bot.scan.BlockScanner;
import net.stracciatella.bot.scan.TreeDetector;
import net.stracciatella.bot.scan.TreeInfo;

/**
 * Meta-task that scans an area for matching blocks and enqueues
 * individual mine/chop tasks into the task queue.
 */
public class GatherAreaTask {

    private final Predicate<BlockState> matcher;
    private final int radius;
    private final boolean detectTrees;

    public GatherAreaTask(Predicate<BlockState> matcher, int radius, boolean detectTrees) {
        this.matcher = matcher;
        this.radius = radius;
        this.detectTrees = detectTrees;
    }

    /**
     * Scan the area around the given center and populate the task queue.
     * Returns the number of tasks enqueued.
     */
    public int scanAndEnqueue(Level level, BlockPos center, TaskQueue queue) {
        int count = 0;

        if (detectTrees) {
            List<TreeInfo> trees = TreeDetector.findTrees(level, center, radius);
            for (TreeInfo tree : trees) {
                queue.addLast(new ChopTreeTask(tree));
                count++;
            }
        } else {
            List<BlockPos> blocks = BlockScanner.scan(level, center, radius, matcher);
            for (BlockPos pos : blocks) {
                queue.addLast(new MineBlockTask(pos));
                count++;
            }
        }

        return count;
    }
}

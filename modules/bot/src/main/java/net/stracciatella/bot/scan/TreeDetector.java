package net.stracciatella.bot.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Detects tree structures by scanning for log blocks, tracing to the base,
 * and collecting all trunk logs.
 */
public class TreeDetector {

    /**
     * Find all trees within the given radius of the center position.
     * Returns trees sorted by distance to center, with logs ordered top-to-bottom
     * for human-like chopping.
     */
    public static List<TreeInfo> findTrees(Level level, BlockPos center, int radius) {
        List<BlockPos> logs = BlockScanner.scan(level, center, radius, BlockScanner.IS_LOG);
        Set<BlockPos> visited = new HashSet<>();
        List<TreeInfo> trees = new ArrayList<>();

        for (BlockPos logPos : logs) {
            BlockPos base = traceToBase(level, logPos);
            if (visited.contains(base)) {
                continue;
            }
            visited.add(base);

            List<BlockPos> trunk = collectTrunk(level, base);
            if (trunk.isEmpty()) {
                continue;
            }

            // Reverse to get top-to-bottom order for chopping
            Collections.reverse(trunk);
            trees.add(new TreeInfo(base, trunk, trunk.size()));
        }

        trees.sort((a, b) -> Double.compare(
                a.basePos().distSqr(center),
                b.basePos().distSqr(center)));
        return trees;
    }

    /**
     * Trace downward from a log position to find the base (lowest log block
     * above non-log ground).
     */
    private static BlockPos traceToBase(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos current = pos.mutable();
        while (current.getY() > level.getMinY()) {
            current.move(0, -1, 0);
            BlockState below = level.getBlockState(current);
            if (!below.is(BlockTags.LOGS)) {
                current.move(0, 1, 0);
                return current.immutable();
            }
        }
        return current.immutable();
    }

    /**
     * Collect all contiguous trunk logs upward from the base.
     * Returns logs in bottom-to-top order.
     */
    private static List<BlockPos> collectTrunk(Level level, BlockPos base) {
        List<BlockPos> trunk = new ArrayList<>();
        BlockPos.MutableBlockPos current = base.mutable();

        while (current.getY() < level.getMaxY()) {
            BlockState state = level.getBlockState(current);
            if (!state.is(BlockTags.LOGS)) {
                break;
            }
            trunk.add(current.immutable());
            current.move(0, 1, 0);
        }

        return trunk;
    }
}

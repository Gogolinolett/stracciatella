package net.stracciatella.bot.scan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Scans a cubic volume around a center position for blocks matching a predicate.
 * Results are sorted by distance to the center.
 */
public class BlockScanner {

    /**
     * Matches all vanilla ore blocks including deepslate variants.
     */
    public static final Predicate<BlockState> IS_ORE = state ->
            state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
                    || state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
                    || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                    || state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                    || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                    || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                    || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                    || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                    || state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE)
                    || state.is(Blocks.ANCIENT_DEBRIS);

    /**
     * Matches all log blocks via the LOGS tag.
     */
    public static final Predicate<BlockState> IS_LOG = state -> state.is(BlockTags.LOGS);

    /**
     * Scan a cubic volume for blocks matching the predicate.
     * Returns positions sorted by distance to center.
     */
    public static List<BlockPos> scan(Level level, BlockPos center, int radius, Predicate<BlockState> matcher) {
        List<BlockPos> results = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                    mutable.set(x, y, z);
                    if (!level.hasChunkAt(mutable)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(mutable);
                    if (matcher.test(state)) {
                        results.add(mutable.immutable());
                    }
                }
            }
        }

        results.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return results;
    }
}

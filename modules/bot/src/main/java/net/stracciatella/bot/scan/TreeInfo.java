package net.stracciatella.bot.scan;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Describes a detected tree: its base position, ordered log positions (top-to-bottom
 * for chopping), and height.
 */
public record TreeInfo(BlockPos basePos, List<BlockPos> logs, int height) {
}

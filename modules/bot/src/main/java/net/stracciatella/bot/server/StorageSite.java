package net.stracciatella.bot.server;

import net.minecraft.core.BlockPos;

/**
 * One storage block the bot is allowed to deposit into and restock from.
 *
 * <p>Carries its dimension, which is the "world" half of "server- and
 * world-specific": a barrel in the nether is not a place an overworld run can
 * walk to, and a position alone cannot say which of the two it is. The
 * dimension is stored as the registry id string ({@code minecraft:overworld})
 * rather than a {@code ResourceKey} so the file stays readable and survives a
 * dimension the client cannot resolve.
 *
 * <p>A record for its equality: {@code /bot storage scan} has to drop the
 * second half of every double chest, and "have I already got this position"
 * is the whole of that check.
 */
public record StorageSite(String dimension, int x, int y, int z) {

    public static StorageSite of(String dimension, BlockPos pos) {
        return new StorageSite(dimension, pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public boolean isIn(String currentDimension) {
        return dimension.equals(currentDimension);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z + " (" + dimension + ")";
    }
}

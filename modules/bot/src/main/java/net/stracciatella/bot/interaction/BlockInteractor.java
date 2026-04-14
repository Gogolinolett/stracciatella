package net.stracciatella.bot.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.task.InteractionType;

/**
 * Simulates block interaction through vanilla key press input.
 * Mining = hold attack key while looking at block.
 * Using = hold use key while looking at block.
 */
public class BlockInteractor {

    private static boolean interacting = false;
    private static InteractionType currentType = null;

    public static void startInteraction(InteractionType type) {
        Options options = Minecraft.getInstance().options;
        interacting = true;
        currentType = type;
        if (type == InteractionType.ATTACK) {
            options.keyAttack.setDown(true);
        } else {
            options.keyUse.setDown(true);
        }
    }

    public static void stopInteraction() {
        if (!interacting) {
            return;
        }
        Options options = Minecraft.getInstance().options;
        if (currentType == InteractionType.ATTACK) {
            options.keyAttack.setDown(false);
        } else if (currentType == InteractionType.USE) {
            options.keyUse.setDown(false);
        }
        interacting = false;
        currentType = null;
    }

    public static boolean isInteracting() {
        return interacting;
    }

    /**
     * Check if the target block has been broken (is now air).
     */
    public static boolean isBlockBroken(Level level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }
}

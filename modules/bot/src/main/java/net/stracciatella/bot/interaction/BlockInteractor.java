package net.stracciatella.bot.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.task.InteractionType;

/**
 * Drives block interaction by calling Minecraft's attack methods directly.
 * <p>
 * Uses startAttack() for the initial hit and continueAttack() each subsequent
 * tick. This bypasses key simulation which has timing issues with the tick
 * multiplier's GLFW event reset cycle.
 */
public class BlockInteractor {

    private static boolean interacting = false;
    private static InteractionType currentType = null;
    private static boolean started = false;

    /**
     * Begin block interaction. The actual startAttack/continueAttack calls
     * happen in {@link #tickInteraction()} which must be called each tick.
     */
    public static void startInteraction(InteractionType type) {
        interacting = true;
        currentType = type;
        started = false;
    }

    /**
     * Called at START_CLIENT_TICK (before handleKeybinds) to drive the mining.
     * On the first tick, calls startAttack() to begin breaking.
     * On subsequent ticks, calls continueAttack(true) to progress the break.
     */
    public static void tickInteraction() {
        if (!interacting || currentType != InteractionType.ATTACK) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (!started) {
            mc.missTime = 0;
            mc.startAttack();
            started = true;
        } else {
            mc.continueAttack(true);
        }
    }

    public static void stopInteraction() {
        if (!interacting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        interacting = false;
        currentType = null;
        started = false;
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

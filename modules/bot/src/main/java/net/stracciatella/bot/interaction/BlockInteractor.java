package net.stracciatella.bot.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.task.InteractionType;

/**
 * Drives block interaction by calling Minecraft's game-mode methods directly
 * with an explicit target position, bypassing {@code mc.hitResult}.
 * <p>
 * {@code mc.hitResult} is recomputed in the client tick BEFORE our phase
 * logic sets the player's yaw/pitch, so on the first tick of INTERACTING
 * the hitResult can still reflect a stale (previous-target) aim. Using
 * {@code mc.gameMode.startDestroyBlock(pos, dir)} directly — with the
 * task's target position — ensures the attack always lands on the block
 * we intend to mine.
 */
public class BlockInteractor {

    private static boolean interacting = false;
    private static InteractionType currentType = null;
    private static boolean started = false;
    private static BlockPos targetPos = null;

    /**
     * Begin block interaction against an explicit target. The destroy packets
     * are sent from {@link #tickInteraction()} which must run each tick.
     */
    public static void startInteraction(InteractionType type, BlockPos target) {
        interacting = true;
        currentType = type;
        started = false;
        targetPos = target;
    }

    /**
     * Called at START_CLIENT_TICK (before handleKeybinds) to drive the mining.
     * Sends destroy packets against the explicit target position we captured
     * in {@link #startInteraction(InteractionType, BlockPos)}.
     */
    public static void tickInteraction() {
        if (!interacting || currentType != InteractionType.ATTACK) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || targetPos == null) {
            return;
        }

        Direction face = faceTowardPlayer(mc, targetPos);

        if (!started) {
            mc.missTime = 0;
            mc.gameMode.startDestroyBlock(targetPos, face);
            started = true;
        } else {
            mc.gameMode.continueDestroyBlock(targetPos, face);
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
        targetPos = null;
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

    /**
     * Pick the face of {@code target} that faces the player's eye. Used as the
     * direction hint in the destroy-block packets; the server uses this for
     * adjacency checks.
     */
    private static Direction faceTowardPlayer(Minecraft mc, BlockPos target) {
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();
        double cx = target.getX() + 0.5;
        double cy = target.getY() + 0.5;
        double cz = target.getZ() + 0.5;
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}

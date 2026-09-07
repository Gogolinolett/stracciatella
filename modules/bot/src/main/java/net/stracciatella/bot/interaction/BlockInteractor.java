package net.stracciatella.bot.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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

    /**
     * Ticks between repeated use attempts while a placement has not been
     * confirmed. A human who clicks and sees nothing happen clicks again; it
     * also covers a dropped use packet without stalling for the full
     * interaction timeout.
     */
    private static final int USE_RETRY_TICKS = 15;

    private static boolean interacting = false;
    private static InteractionType currentType = null;
    private static boolean started = false;
    private static BlockPos targetPos = null;
    // Face to interact with, or null to re-derive the most visible one each
    // tick. Mining passes null (the best face can change as the bot moves);
    // placement pins the face, because it decides where the block ends up.
    private static Direction fixedFace = null;
    private static int useCooldown = 0;

    /**
     * Begin block interaction against an explicit target. The interaction
     * packets are sent from {@link #tickInteraction()} which must run each
     * tick.
     *
     * @param face the face to interact with, or {@code null} to re-derive the
     *             most visible face every tick (mining)
     */
    public static void startInteraction(InteractionType type, BlockPos target, Direction face) {
        interacting = true;
        currentType = type;
        started = false;
        targetPos = target;
        fixedFace = face;
        useCooldown = 0;
    }

    /**
     * Called at START_CLIENT_TICK (before handleKeybinds) to drive the
     * interaction. Sends packets against the explicit target position we
     * captured in {@link #startInteraction(InteractionType, BlockPos, Direction)}.
     */
    public static void tickInteraction() {
        if (!interacting) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || targetPos == null) {
            return;
        }

        Direction face = fixedFace != null ? fixedFace : faceTowardPlayer(mc, targetPos);

        if (currentType == InteractionType.ATTACK) {
            boolean hitting;
            if (!started) {
                mc.missTime = 0;
                hitting = mc.gameMode.startDestroyBlock(targetPos, face);
                started = true;
            } else {
                hitting = mc.gameMode.continueDestroyBlock(targetPos, face);
            }
            // The swing is not decoration and does not follow from the call
            // above: Minecraft.continueAttack is the only place vanilla swings
            // while mining, and LocalPlayer.swing is what sends
            // ServerboundSwingPacket. Driving the game mode alone therefore
            // broke blocks with a motionless arm and without a single swing
            // packet — to the server and to everyone watching, a player whose
            // blocks dissolve while he stands still. Vanilla really does send
            // one per tick for as long as the button is held, and the guard is
            // vanilla's own: it swings only when the game-mode call reports it
            // is chewing on the block.
            //
            // continueAttack also calls level.addBreakingBlockEffect here, and
            // that one is deliberately left out. It only adds the chips flying
            // off the face — the crack overlay that reads as "this block is
            // being mined" comes from continueDestroyBlock via
            // destroyBlockProgress and is already there — and it is local
            // cosmetics: nothing about it reaches the server or another
            // player. What it does cost is a shape query and a particle
            // allocation on every tick of every break, and that is enough to
            // matter: with it in, the client fell far enough behind that drops
            // synced too late to be collected, and the chunk miner left the
            // corridor's cobblestone lying on the ground (the corridor test
            // caught it). Chips are not worth losing the loot for.
            if (hitting) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        // USE — place the held block against `face` of the target. Unlike
        // mining there is no "continue" packet: one use either places or does
        // not, so retry on a slow cadence until the controller confirms the
        // placement or times the task out.
        if (started && ++useCooldown < USE_RETRY_TICKS) {
            return;
        }
        useCooldown = 0;
        Vec3 hitVec = Vec3.atCenterOf(targetPos)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitVec, face, targetPos, false));
        if (result.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        started = true;
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
        fixedFace = null;
        useCooldown = 0;
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
     * Pick the face of {@code target} to aim at: of the (up to three) faces
     * oriented toward the player's eye, the most directly facing one whose
     * neighbor block is air — i.e. a face the raycast can actually reach.
     * Plain axis dominance is not enough: for a floor block right in front
     * of the feet the dominant face is UP, which is still covered by the
     * block above it — the bot would stare at an invisible face until
     * lookTimeout. If every candidate is covered, the dominant face is
     * returned as fallback (callers clear the blockers first). Used as the
     * direction hint in the destroy-block packets and as the aim point in
     * LOOKING.
     */
    public static Direction faceTowardPlayer(Minecraft mc, BlockPos target) {
        double px = mc.player.getX();
        double py = mc.player.getEyeY();
        double pz = mc.player.getZ();
        double dx = px - (target.getX() + 0.5);
        double dy = py - (target.getY() + 0.5);
        double dz = pz - (target.getZ() + 0.5);

        Direction faceX = dx >= 0 ? Direction.EAST : Direction.WEST;
        Direction faceY = dy >= 0 ? Direction.UP : Direction.DOWN;
        Direction faceZ = dz >= 0 ? Direction.SOUTH : Direction.NORTH;

        // Candidate faces sorted by how directly they point at the eye.
        Direction[] byDominance = new Direction[3];
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            byDominance[0] = faceX;
            byDominance[1] = ay >= az ? faceY : faceZ;
            byDominance[2] = ay >= az ? faceZ : faceY;
        } else if (ay >= ax && ay >= az) {
            byDominance[0] = faceY;
            byDominance[1] = ax >= az ? faceX : faceZ;
            byDominance[2] = ax >= az ? faceZ : faceX;
        } else {
            byDominance[0] = faceZ;
            byDominance[1] = ax >= ay ? faceX : faceY;
            byDominance[2] = ax >= ay ? faceY : faceX;
        }

        if (mc.level != null) {
            for (Direction face : byDominance) {
                if (mc.level.getBlockState(target.relative(face)).isAir()) {
                    return face;
                }
            }
        }
        return byDominance[0];
    }
}

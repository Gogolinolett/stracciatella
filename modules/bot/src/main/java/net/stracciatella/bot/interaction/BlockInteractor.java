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

    /**
     * How long vanilla makes the pickaxe idle after a block comes apart.
     * {@code MultiPlayerGameMode} sets {@code destroyDelay = 5} the moment a
     * break completes, and {@code continueDestroyBlock} opens with
     * {@code if (destroyDelay > 0) { destroyDelay--; return true; }} — five
     * calls that report progress and make none. Nothing else spends it:
     * {@code MultiPlayerGameMode.tick} does not decrement it, so it only runs
     * down while something keeps calling that method.
     *
     * <p>The count is exact and is what keeps {@link #tickCooldownDrain} off
     * the branch below the early-out. Once the delay reaches zero,
     * {@code continueDestroyBlock} falls through to {@code sameDestroyTarget},
     * and a hotbar switch made while aiming at the next block is enough to
     * fail it — which routes into {@code startDestroyBlock}, and that sends a
     * START_DESTROY_BLOCK packet through {@code startPrediction} with no air
     * check in front of it. Draining exactly five never gets there.
     */
    private static final int DESTROY_DELAY_TICKS = 5;

    private static boolean interacting = false;
    private static InteractionType currentType = null;
    private static boolean started = false;
    private static BlockPos targetPos = null;
    // Face to interact with, or null to re-derive the most visible one each
    // tick. Mining passes null (the best face can change as the bot moves);
    // placement pins the face, because it decides where the block ends up.
    private static Direction fixedFace = null;
    private static int useCooldown = 0;
    // The block just broken, kept only to spend vanilla's post-break delay
    // while the bot aims at the next one. See DESTROY_DELAY_TICKS.
    private static BlockPos cooldownPos = null;
    private static Direction cooldownFace = null;
    private static int cooldownTicks = 0;

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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            return;
        }
        if (!interacting) {
            tickCooldownDrain(mc);
            return;
        }
        if (targetPos == null) {
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

    /**
     * Let go of the button after a break, but keep spending vanilla's
     * post-break delay while the bot lines up the next block.
     *
     * <p>A player mining a wall holds the button down and moves the mouse; the
     * five idle ticks pass <i>during</i> the mouse travel, which is why a human
     * gets 16 stone in 200 ticks where 96 of those are the only real breaking.
     * The bot used to release on every confirmed break and re-press on the next
     * one, so it paid the delay after aiming instead of through it — five ticks
     * per block, on top of the aim rather than inside it.
     *
     * <p>Only for mining, and only once the target really is air: it is the
     * completed break that arms the delay, and a target still standing means
     * the break did not finish.
     */
    public static void releaseAfterBreak() {
        BlockPos broken = currentType == InteractionType.ATTACK ? targetPos : null;
        Direction face = fixedFace;
        stopInteraction();
        if (broken == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.getBlockState(broken).isAir()) {
            return;
        }
        cooldownPos = broken;
        cooldownFace = face != null ? face : faceTowardPlayer(mc, broken);
        cooldownTicks = DESTROY_DELAY_TICKS;
    }

    /**
     * Spend one tick of the post-break delay. Swings for the same reason the
     * attack branch does — {@code Minecraft.continueAttack} swings whenever the
     * game-mode call reports it is still working, and during these five ticks
     * it does, which is exactly the stretch where a human's arm keeps moving.
     */
    private static void tickCooldownDrain(Minecraft mc) {
        if (cooldownPos == null) {
            return;
        }
        if (cooldownTicks-- <= 0 || !mc.level.getBlockState(cooldownPos).isAir()) {
            cooldownPos = null;
            return;
        }
        if (mc.gameMode.continueDestroyBlock(cooldownPos, cooldownFace)) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    public static void stopInteraction() {
        // Unconditional: a hard stop must not leave the drain running, and
        // stop()/pause()/failCurrentTask() all come through here.
        cooldownPos = null;
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

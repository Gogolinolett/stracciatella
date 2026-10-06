package net.stracciatella.bot.humanize;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.interaction.BlockInteractor;
import net.stracciatella.bot.task.InteractionType;

/**
 * Idle left clicks while a journey walks: now and then a burst of a few, the
 * way a player taps the button out of boredom on a long walk.
 *
 * <p>A click goes where the crosshair already is. Into the air it is vanilla's
 * miss: a swing and a reset attack strength. On a block it is a short press —
 * {@link BlockInteractor} starts the break and lets go after a tick or few,
 * which on the wire is a player's START then ABORT — held only as long as the
 * block cannot come apart in that time ({@link #MAX_PROGRESS}); a block that
 * breaks at once, a flower or a torch, is not clicked at all. Onto an entity
 * it is never anything: those clicks are skipped, so no mob and no player is
 * ever hit.
 */
public final class TravelClicks {

    /** How far a held click may take a block's break before it lets go. */
    private static final float MAX_PROGRESS = 0.5f;
    private static final int HOLD_MIN_TICKS = 1;
    private static final int HOLD_MAX_TICKS = 4;
    private static final int GAP_MIN_TICKS = 2;
    private static final int GAP_MAX_TICKS = 5;

    private static int clicksLeft;
    private static int gapTicks;
    private static int holdTicks;
    private static BlockPos held;

    private TravelClicks() {
    }

    /** One tick of a journey's walk. */
    public static void tick(Minecraft mc, BotConfig config) {
        // The journey has the controller lay a block now and then; its hands
        // are busy then, and the interactor is the controller's — a press of
        // ours it has replaced is not ours to end.
        if (BotController.isActive()) {
            held = null;
            clicksLeft = 0;
            return;
        }
        if (held != null) {
            if (--holdTicks <= 0) {
                release();
            }
            return;
        }
        if (clicksLeft == 0) {
            if (!HumanBehavior.blunder(config.travelClickChance)) {
                return;
            }
            clicksLeft = HumanBehavior.randomIntInRange(config.travelClickMin, config.travelClickMax);
            gapTicks = 0;
        }
        if (gapTicks-- > 0) {
            return;
        }
        click(mc);
        clicksLeft--;
        gapTicks = HumanBehavior.randomIntInRange(GAP_MIN_TICKS, GAP_MAX_TICKS);
    }

    /** End a burst, letting go of a block still pressed. */
    public static void stop() {
        release();
        clicksLeft = 0;
    }

    private static void click(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (mc.player == null || mc.level == null || hit == null || hit instanceof EntityHitResult) {
            return;
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.player.resetAttackStrengthTicker();
            return;
        }
        BlockHitResult block = (BlockHitResult) hit;
        BlockPos pos = block.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        // Creative breaks whatever is clicked, and so does a block whose first
        // tick of progress is already too much.
        float perTick = state.getDestroyProgress(mc.player, mc.level, pos);
        int hold = Math.min(HumanBehavior.randomIntInRange(HOLD_MIN_TICKS, HOLD_MAX_TICKS),
                perTick <= 0 ? HOLD_MAX_TICKS : (int) (MAX_PROGRESS / perTick));
        if (mc.player.getAbilities().instabuild || hold < 1 || BlockInteractor.isInteracting()) {
            return;
        }
        BlockInteractor.startInteraction(InteractionType.ATTACK, pos, block.getDirection());
        held = pos;
        holdTicks = hold;
    }

    private static void release() {
        // Only the press this class started: anything else in the interactor
        // by now is the controller's, and not ours to end.
        if (held != null && held.equals(BlockInteractor.currentTarget())) {
            BlockInteractor.stopInteraction();
        }
        held = null;
        holdTicks = 0;
    }
}

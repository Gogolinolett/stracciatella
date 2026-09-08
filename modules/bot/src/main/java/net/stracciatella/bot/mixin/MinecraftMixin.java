package net.stracciatella.bot.mixin;

import net.stracciatella.bot.interaction.BlockInteractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes the client behave as though the attack button were held while the bot
 * mines, which is the only way its packets can match a player's.
 *
 * <p>{@code handleKeybinds} calls {@code continueAttack(keyAttack.isDown())}
 * every tick. A player holding the button takes the branch that drives
 * {@code continueDestroyBlock}; with the button up the method falls through to
 * {@code stopDestroyBlock}, and that sends an {@code ABORT_DESTROY_BLOCK} for
 * whatever block is being destroyed — and zeroes the client's own progress
 * along the way. The bot drives the game mode itself from
 * {@link BlockInteractor}, with no key down, so vanilla aborted every break on
 * the tick after it started: the wire read START → ABORT → silence → STOP,
 * where a player sends START → silence → STOP. One packet no player ever
 * sends, on every single block, plus a tick of progress thrown away.
 *
 * <p>Suppressed rather than redirected to {@code continueAttack(true)} on
 * purpose: that branch drives the break from {@code Minecraft.hitResult},
 * which is recomputed before the bot's phase logic sets its yaw and pitch and
 * so can still name the previous target. Aiming the packets with an explicit
 * position is the whole reason {@link BlockInteractor} exists.
 */
@Mixin(net.minecraft.client.Minecraft.class)
public class MinecraftMixin {

    @Inject(at = @At("HEAD"), method = "continueAttack", cancellable = true)
    private void stracciatella$holdAttack(boolean held, CallbackInfo ci) {
        if (BlockInteractor.isMining()) {
            ci.cancel();
        }
    }
}

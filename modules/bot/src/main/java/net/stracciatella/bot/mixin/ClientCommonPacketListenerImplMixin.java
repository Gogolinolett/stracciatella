package net.stracciatella.bot.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.stracciatella.bot.interaction.BlockWireTrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY (multiplayer break investigation, remove with the fix). Taps the
 * one place every outgoing packet passes through, so the block actions are
 * logged as they really go out — including any that vanilla sends behind the
 * bot's back, which is the whole point: {@code Minecraft.handleKeybinds} calls
 * {@code continueAttack(false)} every tick and that path sends an
 * ABORT_DESTROY_BLOCK the bot never asked for.
 */
@Mixin(net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerImplMixin {

    @Inject(at = @At("HEAD"), method = "send")
    private void stracciatella$onSend(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundPlayerActionPacket action) {
            BlockWireTrace.onAction(action);
        }
        BlockWireTrace.onOutgoing(packet);
    }
}

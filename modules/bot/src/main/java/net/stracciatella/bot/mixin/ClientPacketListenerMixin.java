package net.stracciatella.bot.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.stracciatella.bot.interaction.BlockWireTrace;
import net.stracciatella.bot.interaction.ServerBlockSync;
import net.stracciatella.bot.safety.BotAlarm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link BotAlarm} from the packets that tell the client something
 * happened to the player it did not do itself, so a run needs no per-tick
 * polling to notice.
 *
 * <p>All injections sit at TAIL rather than HEAD on purpose. These handlers
 * start with {@code PacketUtils.ensureRunningOnSameThread}, which runs once on
 * the netty thread (throwing to reschedule) and once on the client thread — a
 * HEAD injection would therefore fire on the netty thread as well and read
 * {@code Minecraft.player} off-thread.
 */
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(at = @At("TAIL"), method = "handleDamageEvent")
    private void stracciatella$onDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && packet.entityId() == player.getId()) {
            BotAlarm.raiseDamage();
        }
    }

    /**
     * A player attacking with no damage dealt (PvP disabled, a blocked hit)
     * produces no damage event at all — only this sound. It is the sole signal
     * that reaches the bot's client, and it is broadcast to everyone in range
     * including the bot, so an attacker adjacent to the bot is detectable even
     * though the bot itself is never told it was the target.
     */
    @Inject(at = @At("TAIL"), method = "handleSoundEvent")
    private void stracciatella$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || !packet.getSound().value().equals(SoundEvents.PLAYER_ATTACK_NODAMAGE)) {
            return;
        }
        if (BotAlarm.isAttackerInRange(packet.getX(), packet.getY(), packet.getZ(),
                player.getX(), player.getY(), player.getZ())) {
            BotAlarm.raiseAttack();
        }
    }

    /**
     * Someone moved the bot. Every server-forced position arrives here and
     * nowhere else — on the server side {@code ServerGamePacketListenerImpl}
     * has exactly one method that sends this packet, and {@code /tp}, a plugin
     * warp, a portal and a rubber-band correction all go through it — so this
     * one injection covers the lot without the client having to guess from a
     * position that jumped.
     *
     * <p>Where the player ends up is read off the player rather than out of the
     * packet, which is the other thing TAIL buys: the packet's coordinates can
     * be relative to the player's own and its rotation-only variants leave the
     * position alone, and after the handler has run none of that has to be
     * worked out again — the player is standing at the answer. Verified over a
     * full test run: every position packet left the player exactly on the
     * coordinates it carried, so nothing here is interpolating.
     */
    @Inject(at = @At("TAIL"), method = "handleMovePlayer")
    private void stracciatella$onMovePlayer(ClientboundPlayerPositionPacket packet,
                                            CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null
                && BotAlarm.isNewPlace(player.getX(), player.getY(), player.getZ())) {
            BotAlarm.raiseTeleport();
        }
    }

    /**
     * The server settling a block prediction. TAIL again, and here it also
     * matters for a second reason: the handler is what retires the prediction
     * (reverting the block if the server disagreed), so only afterwards does
     * the client's block state carry the server's answer.
     */
    @Inject(at = @At("TAIL"), method = "handleBlockChangedAck")
    private void stracciatella$onBlockChangedAck(ClientboundBlockChangedAckPacket packet,
                                                 CallbackInfo ci) {
        ServerBlockSync.onAck(packet.sequence());
        BlockWireTrace.onAck(packet.sequence());
    }

    /**
     * TEMPORARY (multiplayer break investigation, remove with the fix). The
     * server sending a block back is how it says no — the refusal path of
     * {@code ServerPlayerGameMode} answers a rejected break with the block's
     * real state and nothing else. Logging it next to the outgoing actions is
     * what separates "the server refused" from "the server never answered".
     */
    @Inject(at = @At("TAIL"), method = "handleBlockUpdate")
    private void stracciatella$onBlockUpdate(ClientboundBlockUpdatePacket packet,
                                             CallbackInfo ci) {
        BlockWireTrace.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }
}

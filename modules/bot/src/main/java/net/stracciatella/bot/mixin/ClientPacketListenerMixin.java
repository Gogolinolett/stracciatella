package net.stracciatella.bot.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.stracciatella.bot.safety.BotAlarm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link BotAlarm} from the two packets that tell the client something
 * hostile happened, so behaviors with a stop policy need no per-tick polling.
 *
 * <p>Both injections sit at TAIL rather than HEAD on purpose. These handlers
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
}

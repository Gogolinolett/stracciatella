package net.stracciatella.bot.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens {@code ClientLevel.getBlockStatePredictionHandler()}, which vanilla
 * keeps package-private. The bot needs the handler's current sequence number to
 * know which server acknowledgement will settle the break it just predicted —
 * see {@link net.stracciatella.bot.interaction.ServerBlockSync}.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {

    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler stracciatella$predictionHandler();
}

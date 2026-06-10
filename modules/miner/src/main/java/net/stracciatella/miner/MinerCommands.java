package net.stracciatella.miner;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.stracciatella.bot.behavior.BehaviorRunner;

public class MinerCommands {

    public static void register() {
        var command = ClientCommandManager.literal("miner")
                // /miner start [tunnelLength]
                .then(literal("start")
                        .executes(context -> start(0, context.getSource()))
                        .then(argument("tunnelLength", IntegerArgumentType.integer(1, 512))
                                .executes(context -> start(
                                        IntegerArgumentType.getInteger(context, "tunnelLength"),
                                        context.getSource()))))

                // /miner stop
                .then(literal("stop").executes(context -> {
                    BehaviorRunner.stop();
                    context.getSource().sendFeedback(Component.literal("Miner stopped"));
                    return 1;
                }))

                // /miner status
                .then(literal("status").executes(context -> {
                    context.getSource().sendFeedback(
                            Component.literal("Behavior: " + BehaviorRunner.statusLine()));
                    return 1;
                }));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(command);
        });
    }

    private static int start(int tunnelLength,
                             net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        MinerSetup.diamondMiner().setRequestedTunnelLength(tunnelLength);
        BehaviorRunner.start(MinerSetup.diamondMiner().id());
        source.sendFeedback(Component.literal("Diamond miner started ("
                + (tunnelLength > 0 ? tunnelLength + " blocks" : "default length") + ")"));
        return 1;
    }
}

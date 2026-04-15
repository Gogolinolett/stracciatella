package net.stracciatella.pathfinding.commands;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.stracciatella.pathfinding.travel.Navigator;
import net.stracciatella.pathfinding.travel.TravelMethod;

public class NavigateCommands {

    public void register() {

        var navigateCommand = literal("navigate")
                .then(literal("to")
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    int x = IntegerArgumentType.getInteger(context, "x");
                                                    int y = IntegerArgumentType.getInteger(context, "y");
                                                    int z = IntegerArgumentType.getInteger(context, "z");
                                                    Navigator.navigate(new BlockPos(x, y, z));
                                                    return 1;
                                                })))))
                .then(literal("stop").executes(context -> {
                    Navigator.stop();
                    context.getSource().sendFeedback(Component.literal("[Navigator] Stopped"));
                    return 1;
                }))
                .then(literal("methods").executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    BlockPos playerPos = client.player != null ? client.player.blockPosition() : BlockPos.ZERO;
                    context.getSource().sendFeedback(Component.literal("--- Travel Methods ---"));
                    for (TravelMethod method : Navigator.getMethods()) {
                        boolean usable = client.player != null
                                && method.canUse(client, playerPos, playerPos);
                        String status = usable ? "available" : "unavailable";
                        context.getSource().sendFeedback(Component.literal(
                                "  " + method.id() + " — " + status));
                    }
                    return 1;
                }));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(navigateCommand);
        });
    }
}

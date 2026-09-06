package net.stracciatella.miner;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.stracciatella.bot.behavior.BehaviorRunner;

public class MinerCommands {

    /**
     * Every block id in the game. The blacklist is deliberately not limited to
     * what happens to be nearby — a player sets it up before starting a run,
     * usually for something they expect to hit later.
     */
    private static final SuggestionProvider<FabricClientCommandSource> BLOCK_IDS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.BLOCK.keySet(), builder);

    private static final SuggestionProvider<FabricClientCommandSource> BLACKLISTED =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    MinerSetup.CONFIG.chunkMinerBlacklist, builder);

    public static void register() {
        var command = ClientCommandManager.literal("miner")
                .then(diamond())
                .then(chunk())

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

    /** Builds the {@code /miner diamond start [tunnelLength]} subtree. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            diamond() {
        return literal("diamond")
                .then(literal("start")
                        .executes(context -> startDiamond(0, context.getSource()))
                        .then(argument("tunnelLength", IntegerArgumentType.integer(1, 512))
                                .executes(context -> startDiamond(
                                        IntegerArgumentType.getInteger(context, "tunnelLength"),
                                        context.getSource()))));
    }

    /** Builds {@code /miner chunk start [fromY] [toY]} and the blacklist subtree. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            chunk() {
        return literal("chunk")
                .then(literal("start")
                        .executes(context -> startChunk(false, 0, 0, context.getSource()))
                        .then(argument("fromY", IntegerArgumentType.integer(-64, 320))
                                .then(argument("toY", IntegerArgumentType.integer(-64, 320))
                                        .executes(context -> startChunk(true,
                                                IntegerArgumentType.getInteger(context, "fromY"),
                                                IntegerArgumentType.getInteger(context, "toY"),
                                                context.getSource())))))
                .then(literal("blacklist")
                        .then(literal("add")
                                .then(argument("block", StringArgumentType.string())
                                        .suggests(BLOCK_IDS)
                                        .executes(context -> blacklistAdd(
                                                StringArgumentType.getString(context, "block"),
                                                context.getSource()))))
                        .then(literal("remove")
                                .then(argument("block", StringArgumentType.string())
                                        .suggests(BLACKLISTED)
                                        .executes(context -> blacklistRemove(
                                                StringArgumentType.getString(context, "block"),
                                                context.getSource()))))
                        .then(literal("list")
                                .executes(context -> blacklistList(context.getSource())))
                        .then(literal("clear")
                                .executes(context -> blacklistClear(context.getSource()))));
    }

    private static int startDiamond(int tunnelLength, FabricClientCommandSource source) {
        MinerSetup.diamondMiner().setRequestedTunnelLength(tunnelLength);
        BehaviorRunner.start(MinerSetup.diamondMiner().id());
        source.sendFeedback(Component.literal("Diamond miner started ("
                + (tunnelLength > 0 ? tunnelLength + " blocks" : "default length") + ")"));
        return 1;
    }

    private static int startChunk(boolean ranged, int fromY, int toY,
                                  FabricClientCommandSource source) {
        if (ranged && fromY < toY) {
            source.sendError(Component.literal(
                    "fromY is the top layer and must not be below toY"));
            return 0;
        }
        MinerSetup.chunkMiner().setRequestedRange(ranged, fromY, toY);
        BehaviorRunner.start(MinerSetup.chunkMiner().id());
        source.sendFeedback(Component.literal("Chunk miner started ("
                + (ranged ? "y " + toY + ".." + fromY : "from here down") + ")"));
        return 1;
    }

    private static int blacklistAdd(String block, FabricClientCommandSource source) {
        String id = normalize(block);
        if (id == null) {
            source.sendError(Component.literal("'" + block + "' is not a block"));
            return 0;
        }
        if (MinerSetup.CONFIG.chunkMinerBlacklist.contains(id)) {
            source.sendFeedback(Component.literal(id + " is already on the blacklist"));
            return 1;
        }
        MinerSetup.CONFIG.chunkMinerBlacklist.add(id);
        MinerSetup.CONFIG.save();
        source.sendFeedback(Component.literal("Blacklisted " + id));
        return 1;
    }

    private static int blacklistRemove(String block, FabricClientCommandSource source) {
        String id = normalize(block);
        if (id == null || !MinerSetup.CONFIG.chunkMinerBlacklist.remove(id)) {
            source.sendError(Component.literal("'" + block + "' is not on the blacklist"));
            return 0;
        }
        MinerSetup.CONFIG.save();
        source.sendFeedback(Component.literal("Removed " + id + " from the blacklist"));
        return 1;
    }

    private static int blacklistList(FabricClientCommandSource source) {
        if (MinerSetup.CONFIG.chunkMinerBlacklist.isEmpty()) {
            source.sendFeedback(Component.literal("Chunk miner blacklist is empty"));
            return 1;
        }
        source.sendFeedback(Component.literal("Chunk miner blacklist ("
                + MinerSetup.CONFIG.chunkMinerBlacklist.size() + "):"));
        for (String id : MinerSetup.CONFIG.chunkMinerBlacklist) {
            source.sendFeedback(Component.literal("  " + id));
        }
        return 1;
    }

    private static int blacklistClear(FabricClientCommandSource source) {
        int size = MinerSetup.CONFIG.chunkMinerBlacklist.size();
        MinerSetup.CONFIG.chunkMinerBlacklist.clear();
        MinerSetup.CONFIG.save();
        source.sendFeedback(Component.literal("Cleared " + size + " blacklist entries"));
        return 1;
    }

    /**
     * Canonical block id, or null when the argument names no block. Accepts
     * "stone" as well as "minecraft:stone" — the namespace is what a player
     * forgets, and a blacklist entry that silently never matches is worse
     * than a rejected command.
     */
    public static String normalize(String block) {
        Identifier id = Identifier.tryParse(block.contains(":") ? block : "minecraft:" + block);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return id.toString();
    }
}

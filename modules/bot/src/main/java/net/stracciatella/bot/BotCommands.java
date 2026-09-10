package net.stracciatella.bot;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.List;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.scan.BlockScanner;
import net.stracciatella.bot.scan.TreeDetector;
import net.stracciatella.bot.scan.TreeInfo;
import net.stracciatella.bot.task.ChopTreeTask;
import net.stracciatella.bot.task.GatherAreaTask;
import net.stracciatella.bot.task.MineBlockTask;

public class BotCommands {

    /**
     * Every item id in the game, not only what happens to be lying around:
     * the list is set up before a run, for the drops a player expects to be
     * a nuisance later.
     */
    private static final SuggestionProvider<FabricClientCommandSource> ITEM_IDS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ITEM.keySet(), builder);

    private static final SuggestionProvider<FabricClientCommandSource> IGNORED =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    BotController.CONFIG.ignoredItems, builder);

    public static void register() {
        var command = ClientCommandManager.literal("bot")
                // /bot mine — mine looked-at block
                .then(literal("mine").executes(context -> {
                    LocalPlayer player = context.getSource().getPlayer();
                    BlockPos pos = getLookedBlockPos(player);
                    if (pos == null) {
                        context.getSource().sendFeedback(Component.literal("Not looking at a block"));
                        return 0;
                    }
                    BotController.enqueueTask(new MineBlockTask(pos));
                    context.getSource().sendFeedback(Component.literal("Mining block at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
                    return 1;
                })
                // /bot mine <x> <y> <z>
                .then(argument("x", IntegerArgumentType.integer())
                        .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                        .executes(context -> {
                                            int x = IntegerArgumentType.getInteger(context, "x");
                                            int y = IntegerArgumentType.getInteger(context, "y");
                                            int z = IntegerArgumentType.getInteger(context, "z");
                                            BlockPos pos = new BlockPos(x, y, z);
                                            BotController.enqueueTask(new MineBlockTask(pos));
                                            context.getSource().sendFeedback(Component.literal("Mining block at " + x + ", " + y + ", " + z));
                                            return 1;
                                        })))))

                // /bot chop — chop looked-at tree
                .then(literal("chop").executes(context -> {
                    LocalPlayer player = context.getSource().getPlayer();
                    BlockPos pos = getLookedBlockPos(player);
                    if (pos == null) {
                        context.getSource().sendFeedback(Component.literal("Not looking at a block"));
                        return 0;
                    }
                    Level level = context.getSource().getWorld();
                    List<TreeInfo> trees = TreeDetector.findTrees(level, pos, 3);
                    if (trees.isEmpty()) {
                        context.getSource().sendFeedback(Component.literal("No tree found near looked-at block"));
                        return 0;
                    }
                    TreeInfo tree = trees.get(0);
                    BotController.enqueueTask(new ChopTreeTask(tree));
                    context.getSource().sendFeedback(Component.literal("Chopping tree with " + tree.logs().size() + " logs"));
                    return 1;
                }))

                // /bot gather ores [radius]
                .then(literal("gather")
                        .then(literal("ores")
                                .executes(context -> gatherOres(context.getSource().getPlayer(), context.getSource().getWorld(), BotController.CONFIG.scanRadius, context.getSource()))
                                .then(argument("radius", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> gatherOres(context.getSource().getPlayer(), context.getSource().getWorld(),
                                                IntegerArgumentType.getInteger(context, "radius"), context.getSource()))))

                        // /bot gather logs [radius]
                        .then(literal("logs")
                                .executes(context -> gatherLogs(context.getSource().getPlayer(), context.getSource().getWorld(), BotController.CONFIG.scanRadius, context.getSource()))
                                .then(argument("radius", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> gatherLogs(context.getSource().getPlayer(), context.getSource().getWorld(),
                                                IntegerArgumentType.getInteger(context, "radius"), context.getSource())))))

                // /bot stop — stops the behavior layer first (it would
                // otherwise immediately re-plan new tasks), then the controller.
                .then(literal("stop").executes(context -> {
                    BehaviorRunner.stop();
                    BotController.stop();
                    context.getSource().sendFeedback(Component.literal("Bot stopped"));
                    return 1;
                }))

                // /bot pause
                .then(literal("pause").executes(context -> {
                    BotController.pause();
                    context.getSource().sendFeedback(Component.literal("Bot paused"));
                    return 1;
                }))

                // /bot resume
                .then(literal("resume").executes(context -> {
                    BotController.resume();
                    context.getSource().sendFeedback(Component.literal("Bot resumed"));
                    return 1;
                }))

                // /bot status
                .then(literal("status").executes(context -> {
                    String status = "Phase: " + BotController.getPhase();
                    if (BotController.isPaused()) {
                        status += " (PAUSED)";
                    }
                    if (BotController.getCurrentTask() != null) {
                        status += "\nTask: " + BotController.getCurrentTask().description();
                    }
                    status += "\nQueue: " + BotController.getTaskQueue().size() + " tasks";
                    status += "\nBehavior: " + BehaviorRunner.statusLine();
                    context.getSource().sendFeedback(Component.literal(status));
                    return 1;
                }))

                // /bot debug on|off
                .then(literal("debug")
                        .then(literal("on").executes(context -> {
                            BotController.CONFIG.debugEnabled = true;
                            BotController.CONFIG.save();
                            context.getSource().sendFeedback(Component.literal("Bot debug enabled"));
                            return 1;
                        }))
                        .then(literal("off").executes(context -> {
                            BotController.CONFIG.debugEnabled = false;
                            BotController.CONFIG.save();
                            context.getSource().sendFeedback(Component.literal("Bot debug disabled"));
                            return 1;
                        })))

                // /bot ignore add|remove <item>, list, clear — drops the bot
                // leaves lying where they fall (BotConfig.ignoredItems).
                .then(literal("ignore")
                        .then(literal("add")
                                .then(argument("item", IdentifierArgument.id())
                                        .suggests(ITEM_IDS)
                                        .executes(context -> ignoreAdd(
                                                context.getArgument("item", Identifier.class),
                                                context.getSource()))))
                        .then(literal("remove")
                                .then(argument("item", IdentifierArgument.id())
                                        .suggests(IGNORED)
                                        .executes(context -> ignoreRemove(
                                                context.getArgument("item", Identifier.class),
                                                context.getSource()))))
                        .then(literal("list")
                                .executes(context -> ignoreList(context.getSource())))
                        .then(literal("clear")
                                .executes(context -> ignoreClear(context.getSource()))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(command);
        });
    }

    private static int gatherOres(LocalPlayer player, Level level, int radius,
                                   FabricClientCommandSource source) {
        GatherAreaTask gather = new GatherAreaTask(BlockScanner.IS_ORE, radius, false);
        int count = gather.scanAndEnqueue(level, player.blockPosition(), BotController.getTaskQueue());
        if (count == 0) {
            source.sendFeedback(Component.literal("No ores found within " + radius + " blocks"));
            return 0;
        }
        source.sendFeedback(Component.literal("Gathering " + count + " ore blocks within " + radius + " blocks"));
        // Kick off if idle
        if (!BotController.isActive() && !BotController.isPaused()) {
            BotController.enqueueTask(BotController.getTaskQueue().poll());
        }
        return 1;
    }

    private static int gatherLogs(LocalPlayer player, Level level, int radius,
                                   FabricClientCommandSource source) {
        GatherAreaTask gather = new GatherAreaTask(BlockScanner.IS_LOG, radius, true);
        int count = gather.scanAndEnqueue(level, player.blockPosition(), BotController.getTaskQueue());
        if (count == 0) {
            source.sendFeedback(Component.literal("No trees found within " + radius + " blocks"));
            return 0;
        }
        source.sendFeedback(Component.literal("Chopping " + count + " trees within " + radius + " blocks"));
        if (!BotController.isActive() && !BotController.isPaused()) {
            BotController.enqueueTask(BotController.getTaskQueue().poll());
        }
        return 1;
    }

    private static int ignoreAdd(Identifier item, FabricClientCommandSource source) {
        String id = normalizeItem(item.toString());
        if (id == null) {
            source.sendError(Component.literal("'" + item + "' is not an item"));
            return 0;
        }
        if (BotController.CONFIG.ignoredItems.contains(id)) {
            source.sendFeedback(Component.literal(id + " is already ignored"));
            return 1;
        }
        BotController.CONFIG.ignoredItems.add(id);
        BotController.CONFIG.save();
        source.sendFeedback(Component.literal("Ignoring " + id + " — the bot no longer walks to it"));
        return 1;
    }

    private static int ignoreRemove(Identifier item, FabricClientCommandSource source) {
        String id = item.toString();
        if (!BotController.CONFIG.ignoredItems.remove(id)) {
            source.sendError(Component.literal("'" + id + "' is not on the ignore list"));
            return 0;
        }
        BotController.CONFIG.save();
        source.sendFeedback(Component.literal("Collecting " + id + " again"));
        return 1;
    }

    private static int ignoreList(FabricClientCommandSource source) {
        List<String> ignored = BotController.CONFIG.ignoredItems;
        if (ignored.isEmpty()) {
            source.sendFeedback(Component.literal("No items ignored — the bot walks to every drop"));
            return 1;
        }
        source.sendFeedback(Component.literal("Ignored items (" + ignored.size() + "):"));
        for (String id : ignored) {
            source.sendFeedback(Component.literal("  " + id));
        }
        return 1;
    }

    private static int ignoreClear(FabricClientCommandSource source) {
        int size = BotController.CONFIG.ignoredItems.size();
        BotController.CONFIG.ignoredItems.clear();
        BotController.CONFIG.save();
        source.sendFeedback(Component.literal("Cleared " + size + " ignored item(s)"));
        return 1;
    }

    /**
     * Canonical item id, or null when the text names no item. Accepts
     * "cobblestone" as well as "minecraft:cobblestone" — the namespace is
     * what a player forgets, and an entry that silently never matches is
     * worse than a rejected one. The GUI page's text box comes through here;
     * the command lets the identifier argument do the parsing first.
     */
    public static String normalizeItem(String item) {
        Identifier id = Identifier.tryParse(item.contains(":") ? item : "minecraft:" + item);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        return id.toString();
    }

    private static BlockPos getLookedBlockPos(LocalPlayer player) {
        var lookingAt = player.raycastHitResult(0, player);
        if (lookingAt.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) lookingAt).getBlockPos();
    }
}

package net.stracciatella.bot;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.stracciatella.bot.server.ServerSettings;
import net.stracciatella.bot.server.ServerSettingsStore;
import net.stracciatella.bot.server.StorageSite;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.MeshManager;

/**
 * The {@code /bot storage} and {@code /bot server} subcommands — everything that
 * edits {@code servers.json}.
 *
 * <p>Commands rather than GUI for the acts that need the world: which chest the
 * bot should use is answered by <em>looking at it</em>, and a coordinate typed
 * into a text box is the same answer with three chances to get a digit wrong.
 * The one-click bulk form, {@code scan}, is here for the same reason — it reads
 * the blocks around the player.
 */
final class StorageCommands {

    /**
     * Upper bound on {@code /bot storage scan}. A scan walks every block in a
     * cube, so the cost is the cube of this; 64 is already 2 million block
     * lookups and a visible stutter. The cap is a guard against a typo, not a
     * taste — nobody keeps chests 200 blocks from their camp.
     */
    static final int MAX_SCAN_RADIUS = 32;

    private StorageCommands() {
    }

    /** Add the block the player is looking at. */
    static int add(LocalPlayer player, Level level, FabricClientCommandSource source) {
        BlockPos pos = lookedAt(player);
        if (pos == null) {
            source.sendError(Component.literal("Not looking at a block"));
            return 0;
        }
        if (!isStorageBlock(level, pos)) {
            source.sendError(Component.literal("That is "
                    + blockId(level, pos) + ", not one of the configured storage blocks "
                    + "(" + String.join(", ", BotController.CONFIG.storageBlocks) + ")"));
            return 0;
        }
        ServerSettings settings = ServerSettingsStore.current();
        StorageSite site = StorageSite.of(ServerSettingsStore.dimensionOf(Minecraft.getInstance()), pos);
        if (settings.storages.contains(site)) {
            source.sendFeedback(Component.literal(site + " is already a storage"));
            return 1;
        }
        settings.storages.add(site);
        ServerSettingsStore.save();
        source.sendFeedback(Component.literal("Storage added: " + site)
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** Remove the block the player is looking at. */
    static int remove(LocalPlayer player, FabricClientCommandSource source) {
        BlockPos pos = lookedAt(player);
        if (pos == null) {
            source.sendError(Component.literal("Not looking at a block"));
            return 0;
        }
        ServerSettings settings = ServerSettingsStore.current();
        StorageSite site = StorageSite.of(ServerSettingsStore.dimensionOf(Minecraft.getInstance()), pos);
        // Removal does not check the block type. A chest somebody has since
        // broken is exactly the entry most worth removing, and refusing to do it
        // because there is no chest there any more would be perverse.
        if (!settings.storages.remove(site)) {
            source.sendFeedback(Component.literal(site + " is not a storage"));
            return 0;
        }
        ServerSettingsStore.save();
        source.sendFeedback(Component.literal("Storage removed: " + site));
        return 1;
    }

    static int list(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        ServerSettings settings = ServerSettingsStore.current();
        String here = ServerSettingsStore.dimensionOf(client);
        if (settings.storages.isEmpty()) {
            source.sendFeedback(Component.literal("No storages on "
                    + ServerSettingsStore.keyFor(client)
                    + " — look at a chest and run /bot storage add"));
            return 1;
        }
        StringBuilder text = new StringBuilder("Storages on " + ServerSettingsStore.keyFor(client) + ":");
        for (StorageSite site : settings.storages) {
            // Marked rather than filtered: a player wondering why a restock says
            // "no storage" is usually in the wrong dimension, and a list that
            // hides the other ones hides the answer.
            text.append("\n  ").append(site).append(site.isIn(here) ? "" : "  [other dimension]");
        }
        source.sendFeedback(Component.literal(text.toString()));
        return 1;
    }

    static int clear(FabricClientCommandSource source) {
        ServerSettings settings = ServerSettingsStore.current();
        int had = settings.storages.size();
        settings.storages.clear();
        ServerSettingsStore.save();
        source.sendFeedback(Component.literal("Cleared " + had + " storages"));
        return 1;
    }

    /**
     * Add every storage block within {@code radius}.
     *
     * <p>Two things make this more than a loop. Only <b>loaded</b> chunks are
     * read: {@code level.getBlockState} in a chunk the client does not have
     * answers air for solid stone rather than saying so (see
     * {@code MeshManager.isChunkLoaded}), so a scan past the edge of what is
     * loaded would quietly find nothing and report success. And a <b>double
     * chest is one storage</b>: both halves open the same container, so adding
     * both would have the bot walk to the same chest twice and count it as two
     * options.
     */
    static int scan(LocalPlayer player, Level level, int radius, FabricClientCommandSource source) {
        if (radius > MAX_SCAN_RADIUS) {
            source.sendError(Component.literal("Radius " + radius + " is above the "
                    + MAX_SCAN_RADIUS + "-block cap"));
            return 0;
        }
        ServerSettings settings = ServerSettingsStore.current();
        String dimension = ServerSettingsStore.dimensionOf(Minecraft.getInstance());
        BlockPos center = player.blockPosition();
        List<StorageSite> found = new ArrayList<>();
        int skippedHalves = 0;
        int unloaded = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Asked once per column, and asked of MeshManager rather than of
                // level.hasChunkAt: on the client that one answers yes for a
                // chunk it does not have, because the chunk source hands out a
                // shared empty placeholder. A scan trusting it would read air
                // for every chest past the loaded edge and report success.
                BlockPos column = center.offset(dx, 0, dz);
                if (!MeshManager.isChunkLoaded(level,
                        new ChunkCoordinate(column.getX() >> 4, column.getZ() >> 4))) {
                    unloaded++;
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!BotController.CONFIG.storageBlocks.contains(
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
                        continue;
                    }
                    if (isSecondHalfOfDoubleChest(state)) {
                        skippedHalves++;
                        continue;
                    }
                    StorageSite site = StorageSite.of(dimension, pos);
                    if (!settings.storages.contains(site)) {
                        found.add(site);
                    }
                }
            }
        }

        settings.storages.addAll(found);
        ServerSettingsStore.save();
        String message = "Added " + found.size() + " storages within " + radius + " blocks";
        if (skippedHalves > 0) {
            message += " (" + skippedHalves + " double-chest halves skipped)";
        }
        if (unloaded > 0) {
            message += " — " + unloaded + " columns were in unloaded chunks and not read";
        }
        source.sendFeedback(Component.literal(message).withStyle(ChatFormatting.GREEN));
        return 1;
    }

    /** Print what the bot is allowed to do on this server. */
    static int server(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        ServerSettings settings = ServerSettingsStore.current();
        String text = "Server: " + ServerSettingsStore.keyFor(client)
                + "\n  dimension: " + ServerSettingsStore.dimensionOf(client)
                + "\n  exit strategy: " + settings.exitStrategy
                + "\n  exit commands: " + (settings.exitCommands.isEmpty()
                        ? "none (set one with /bot server exit command <command>)"
                        : String.join(", ", settings.exitCommands))
                + "\n  route placement: " + (settings.allowPathPlacement ? "allowed" : "not allowed")
                + "\n  storages: " + settings.storages.size();
        source.sendFeedback(Component.literal(text));
        return 1;
    }

    static int setExitStrategy(ServerSettings.ExitStrategy strategy,
                               FabricClientCommandSource source) {
        ServerSettings settings = ServerSettingsStore.current();
        settings.exitStrategy = strategy;
        ServerSettingsStore.save();
        if (strategy == ServerSettings.ExitStrategy.COMMAND && settings.exitCommands.isEmpty()) {
            source.sendFeedback(Component.literal(
                    "Exit strategy is COMMAND, but no exit commands are configured — "
                            + "say which one with /bot server exit command <command>, "
                            + "e.g. /bot server exit command t spawn")
                    .withStyle(ChatFormatting.YELLOW));
            return 1;
        }
        source.sendFeedback(Component.literal("Exit strategy: " + strategy));
        return 1;
    }

    /**
     * Set the command the bot sends to get out of a pit, and switch to the
     * strategy that sends it. Both in one go: asking for {@code /t spawn} and
     * then having to remember a second command to arm it is a menu, not an
     * answer.
     *
     * <p>It <b>replaces</b> what was configured rather than appending. A player
     * who types this twice means "no, that one", and an appending form would
     * quietly build a list that sends both — with the second command arriving
     * after the first has already teleported the bot somewhere else. Servers
     * that genuinely need a sequence still have one: the field stays a list and
     * {@code servers.json} takes as many entries as they like.
     */
    static int setExitCommand(String command, FabricClientCommandSource source) {
        // Typed the way it is typed in chat, leading slash and all. The
        // connection wants it without one — sendCommand("/tp 1 1 1") asks the
        // server for a command literally named "/tp".
        String cleaned = command.strip();
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1).strip();
        }
        if (cleaned.isEmpty()) {
            source.sendError(Component.literal(
                    "Say which command to send, e.g. /bot server exit command tp 1 1 1"));
            return 0;
        }

        ServerSettings settings = ServerSettingsStore.current();
        String replaced = settings.exitCommands.size() > 1
                ? String.join(", ", settings.exitCommands) : null;
        settings.exitCommands.clear();
        settings.exitCommands.add(cleaned);
        settings.exitStrategy = ServerSettings.ExitStrategy.COMMAND;
        ServerSettingsStore.save();

        source.sendFeedback(Component.literal("Exit strategy: COMMAND, sending /" + cleaned
                + " on " + ServerSettingsStore.keyFor(Minecraft.getInstance()))
                .withStyle(ChatFormatting.GREEN));
        if (replaced != null) {
            source.sendFeedback(Component.literal("Replaced the sequence that was there: "
                    + replaced).withStyle(ChatFormatting.YELLOW));
        }
        return 1;
    }

    static int setPlacement(boolean allowed, FabricClientCommandSource source) {
        ServerSettings settings = ServerSettingsStore.current();
        settings.allowPathPlacement = allowed;
        ServerSettingsStore.save();
        source.sendFeedback(Component.literal("Route placement "
                + (allowed ? "allowed" : "not allowed") + " on "
                + ServerSettingsStore.keyFor(Minecraft.getInstance())));
        return 1;
    }

    /**
     * Whether this is the half of a double chest that should be ignored.
     *
     * <p>Either half opens the same container, so exactly one has to be kept and
     * which one does not matter. LEFT is the one dropped, arbitrarily but
     * consistently — consistency is what makes a second scan of the same camp
     * add nothing.
     */
    private static boolean isSecondHalfOfDoubleChest(BlockState state) {
        return state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) == ChestType.LEFT;
    }

    private static boolean isStorageBlock(Level level, BlockPos pos) {
        return BotController.CONFIG.storageBlocks.contains(blockId(level, pos));
    }

    private static String blockId(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    private static BlockPos lookedAt(LocalPlayer player) {
        var hit = player.raycastHitResult(0, player);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return null;
        }
        return ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
    }
}

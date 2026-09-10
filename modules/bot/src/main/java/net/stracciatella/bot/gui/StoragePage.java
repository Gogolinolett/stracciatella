package net.stracciatella.bot.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.bot.BotController;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.screen.IdListScreen;

/**
 * Which <em>kinds</em> of block count as storage — {@code storageBlocks} in the
 * bot config — on the gui module's shared list editor.
 *
 * <p>Deliberately not the list of chest <em>positions</em>. Those live per server
 * in {@code servers.json} and are edited by {@code /bot storage add} while
 * looking at the chest, because "that one" is a thing you point at and not a
 * coordinate you type. What belongs in a menu is the part that is a standing
 * preference: that barrels count, say, or that a modded crate does.
 *
 * <p>Ids rather than "any block with a Container block entity", which would also
 * catch hoppers, droppers and furnaces — and a bot tipping its diamonds into a
 * hopper is a bug report.
 */
public class StoragePage implements GuiPage {

    @Override
    public String id() {
        return "storage_blocks";
    }

    @Override
    public Component title() {
        return Component.literal("Storage blocks");
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new IdListScreen(parent, new IdListScreen.Spec(
                "Storage blocks",
                BotController.CONFIG.storageBlocks,
                StoragePage::normalizeBlock,
                "is not a block",
                "No storage blocks — a restock has nothing it is allowed to open",
                "minecraft:barrel",
                BotController.CONFIG::save,
                "Add block in crosshair",
                StoragePage::blockInCrosshair,
                "Look at a block before opening this menu"));
    }

    /**
     * Canonical block id, or null when the text names no block. Accepts
     * "barrel" as well as "minecraft:barrel" — the namespace is what a player
     * forgets, and an entry that silently never matches is worse than a
     * rejected one.
     */
    public static String normalizeBlock(String block) {
        Identifier id = Identifier.tryParse(block.contains(":") ? block : "minecraft:" + block);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return id.toString();
    }

    /** The id of the block under the crosshair, or null when looking at nothing. */
    private static String blockInCrosshair() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        HitResult hit = player.raycastHitResult(0, player);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        var state = client.level.getBlockState(((BlockHitResult) hit).getBlockPos());
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}

package net.stracciatella.miner.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.screen.IdListScreen;
import net.stracciatella.miner.MinerCommands;
import net.stracciatella.miner.MinerConfig;

/**
 * The chunk miner's blacklist, as a page in the shared GUI. Registered from
 * {@code MinerSetup} — the gui module never learns that the miner exists.
 * The screen is the gui module's list editor; this page only says what the
 * ids are, and which block the context button adds.
 */
public class BlacklistPage implements GuiPage {

    private final MinerConfig config;

    public BlacklistPage(MinerConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "chunk_blacklist";
    }

    @Override
    public Component title() {
        return Component.literal("Chunk miner blacklist");
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new IdListScreen(parent, new IdListScreen.Spec(
                "Chunk miner blacklist",
                config.chunkMinerBlacklist,
                MinerCommands::normalize,
                "is not a block",
                "Blacklist is empty — the miner digs everything",
                "minecraft:chest",
                config::save,
                "Add block you're looking at",
                BlacklistPage::lookedAtBlock,
                "Aim at a block before opening this menu"));
    }

    /**
     * The block the crosshair was on when the menu opened, or null.
     *
     * <p>{@code Minecraft.hitResult} still holds it while a screen is up.
     * That is the whole block-selection mode — aim at the block, open the
     * menu, add it — and it needs no click handling of its own, no mode the
     * player can get stuck in, and no way to accidentally blacklist
     * something while walking around.
     */
    private static String lookedAtBlock() {
        Minecraft minecraft = Minecraft.getInstance();
        HitResult hit = minecraft.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK
                || minecraft.level == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(
                minecraft.level.getBlockState(blockHit.getBlockPos()).getBlock()).toString();
    }
}

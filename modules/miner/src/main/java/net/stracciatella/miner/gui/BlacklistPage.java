package net.stracciatella.miner.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.miner.MinerConfig;

/**
 * The chunk miner's blacklist, as a page in the shared GUI. Registered from
 * {@code MinerSetup} — the gui module never learns that the miner exists.
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
        return new BlacklistScreen(parent, config);
    }
}

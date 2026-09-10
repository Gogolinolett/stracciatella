package net.stracciatella.miner.gui;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.screen.SettingsScreen;
import net.stracciatella.miner.MinerConfig;

/**
 * The chunk miner's own knobs, on the gui module's settings screen. Registered
 * from {@code MinerSetup}, like the blacklist page — the gui module never learns
 * that a miner exists.
 *
 * <p>Only the two settings a player actually changes between runs are here. The
 * rest of {@link MinerConfig} is either a tuning constant nobody touches twice
 * (scan radius, retries) or something a command already says better, because it
 * needs an argument: a layer range belongs on {@code /miner chunk start}, not in
 * a menu that can only cycle.
 */
public class MinerSettingsPage implements GuiPage {

    /**
     * The values the free-slot threshold cycles through. A cycling row cannot
     * offer a number line, and these are the useful answers: one slot is as
     * tight as it goes, two is the default, and the larger ones are for a run
     * that should come home with room to spare.
     */
    private static final int[] FREE_SLOT_STEPS = {1, 2, 3, 4, 6};

    private final MinerConfig config;

    public MinerSettingsPage(MinerConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "chunk_miner";
    }

    @Override
    public Component title() {
        return Component.literal("Chunk miner");
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new SettingsScreen(parent, new SettingsScreen.Spec(
                "Chunk miner",
                List.of(
                        new SettingsScreen.Setting(
                                "Restock",
                                "Whether a run out of room, tools, filler blocks or food walks to "
                                        + "a chest and comes back instead of stopping. Needs a "
                                        + "storage configured for this server — add one by looking "
                                        + "at a chest and running /bot storage add.",
                                () -> config.chunkMinerRestock ? "on" : "off",
                                this::toggleRestock),
                        new SettingsScreen.Setting(
                                "Min free slots",
                                "How many empty inventory slots the run keeps in hand. Below this "
                                        + "it restocks, or stops when restocking is off.",
                                () -> String.valueOf(config.chunkMinerMinFreeSlots),
                                this::cycleFreeSlots)),
                config::save));
    }

    private void toggleRestock() {
        config.chunkMinerRestock = !config.chunkMinerRestock;
    }

    private void cycleFreeSlots() {
        for (int i = 0; i < FREE_SLOT_STEPS.length; i++) {
            if (FREE_SLOT_STEPS[i] == config.chunkMinerMinFreeSlots) {
                config.chunkMinerMinFreeSlots =
                        FREE_SLOT_STEPS[(i + 1) % FREE_SLOT_STEPS.length];
                return;
            }
        }
        // A value edited in miner.json by hand is not on the list; land on the
        // default rather than leaving a button that does nothing when clicked.
        config.chunkMinerMinFreeSlots = FREE_SLOT_STEPS[1];
    }
}

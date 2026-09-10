package net.stracciatella.miner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configurable parameters for the miner module. Persisted to miner.json.
 */
public class MinerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "stracciatella/miner.json";

    // Tunnel floor height: the bot mines with its feet this many blocks above
    // the highest bedrock found in the start column.
    public int floorOffsetAboveBedrock = 3;

    // Default tunnel length in blocks when /miner start is given no argument.
    public int defaultTunnelLength = 32;

    // Radius (around the player) scanned for diamond ore after every step.
    // Kept at mining reach: found ore is always attackable once its line of
    // sight is cleared, so no navigation away from the tunnel is needed.
    public int oreScanRadius = 4;

    // How often a planned step is re-enqueued when blocks survive it (task
    // failures, gravel falling into the cleared space) before the behavior
    // gives up.
    public int maxStepRetries = 2;

    // --- Chunk miner ---

    // Block ids the chunk miner leaves standing. Ids only, no block states:
    // a blacklist the player edits by name is worth more than one that can
    // distinguish a facing, and no realistic entry needs the distinction.
    // Bedrock is skipped unconditionally and does not belong here.
    public List<String> chunkMinerBlacklist = new ArrayList<>(List.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:spawner",
            "minecraft:barrel",
            "minecraft:shulker_box"));

    // Blocks the chunk miner will place, in order of preference, to seal a
    // water source or dam off a liquid. The first one it actually carries is
    // used; carrying none makes liquid handling fail the run.
    public List<String> fillerBlocks = new ArrayList<>(List.of(
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:deepslate",
            "minecraft:stone",
            "minecraft:netherrack"));

    // Lowest layer the chunk miner digs when /miner chunk start gets no
    // second argument. -59 is the first layer that is never bedrock in a
    // vanilla overworld; clamped to the world floor at runtime.
    public int chunkMinerBottomY = -59;

    // With fewer than this many empty slots the chunk miner restocks, or — with
    // restocking off — stops. One threshold for both, because it is the same
    // question: from here on, everything mined is lost.
    public int chunkMinerMinFreeSlots = 2;

    // Whether the chunk miner walks to a chest when it runs out of room, tools,
    // filler or food instead of ending the run. On by default: a run that stops
    // two slabs in because the inventory filled up is the thing this exists to
    // prevent, and the bot does nothing on an unconfigured server anyway —
    // `servers.json` starts with no storages, so the restock has nowhere to go
    // and says so rather than wandering off. The two defaults are a pair: if
    // this one is ever turned off by default, the tame server defaults stop
    // being the safety net they are here for.
    public boolean chunkMinerRestock = true;

    public void applyFrom(MinerConfig other) {
        this.floorOffsetAboveBedrock = other.floorOffsetAboveBedrock;
        this.defaultTunnelLength = other.defaultTunnelLength;
        this.oreScanRadius = other.oreScanRadius;
        this.maxStepRetries = other.maxStepRetries;
        this.chunkMinerBlacklist = new ArrayList<>(other.chunkMinerBlacklist);
        this.fillerBlocks = new ArrayList<>(other.fillerBlocks);
        this.chunkMinerBottomY = other.chunkMinerBottomY;
        this.chunkMinerMinFreeSlots = other.chunkMinerMinFreeSlots;
        this.chunkMinerRestock = other.chunkMinerRestock;
    }

    public static MinerConfig load() {
        Path configPath = Path.of(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            MinerConfig config = new MinerConfig();
            config.save();
            return config;
        }
        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            MinerConfig loaded = GSON.fromJson(reader, MinerConfig.class);
            if (loaded != null) {
                loaded.save();
                return loaded;
            }
        } catch (IOException ignored) {
            // Unreadable config: fall through to the defaults rather than
            // stopping the module from loading over a settings file.
        }
        return new MinerConfig();
    }

    public void save() {
        Path configPath = Path.of(CONFIG_FILE);
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException ignored) {
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
            // Settings that fail to persist are not worth interrupting a run.
        }
    }
}

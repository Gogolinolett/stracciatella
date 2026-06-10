package net.stracciatella.miner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public void applyFrom(MinerConfig other) {
        this.floorOffsetAboveBedrock = other.floorOffsetAboveBedrock;
        this.defaultTunnelLength = other.defaultTunnelLength;
        this.oreScanRadius = other.oreScanRadius;
        this.maxStepRetries = other.maxStepRetries;
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
        }
    }
}

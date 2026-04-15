package net.stracciatella.bot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configurable parameters for the bot module. Persisted to bot.json.
 */
public class BotConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "stracciatella/bot.json";

    // Aim humanization: random offset on block face (blocks)
    public double aimOffsetMin = 0.05;
    public double aimOffsetMax = 0.30;

    // Camera speed variation per target (multiplier on effective turn speed)
    public double lookSpeedMin = 0.7;
    public double lookSpeedMax = 1.3;

    // Settle delay after aim converges before starting attack (ticks)
    public int settleDelayMin = 2;
    public int settleDelayMax = 5;

    // Item collection wait before walking away (ticks)
    // Must be long enough to walk to drops, wait for the 10-tick pickup delay,
    // and actually pick them up
    // collectWaitMin is unused now — collection ends when items are gone.
    // collectWaitMax is the hard timeout to prevent infinite collecting.
    public int collectWaitMin = 20;
    public int collectWaitMax = 400;

    // Scanning: look toward distant target before walking
    public int scanTimeout = 30;
    public double scanFacingTolerance = 15.0;

    // Block scan radius
    public int scanRadius = 32;

    // Facing tolerance for aim convergence (degrees)
    public double facingTolerance = 5.0;

    // Maximum reach distance for mining
    public double reachDistance = 4.0;

    // Maximum ticks to wait for a block to break before giving up
    public int maxBreakTicks = 100;

    // Phase timeouts
    public int navigateTimeout = 200;
    public int positionTimeout = 60;
    public int lookTimeout = 40;

    // Debug logging
    public boolean debugEnabled = false;

    public void applyFrom(BotConfig other) {
        this.aimOffsetMin = other.aimOffsetMin;
        this.aimOffsetMax = other.aimOffsetMax;
        this.lookSpeedMin = other.lookSpeedMin;
        this.lookSpeedMax = other.lookSpeedMax;
        this.settleDelayMin = other.settleDelayMin;
        this.settleDelayMax = other.settleDelayMax;
        this.collectWaitMin = other.collectWaitMin;
        this.collectWaitMax = other.collectWaitMax;
        this.scanTimeout = other.scanTimeout;
        this.scanFacingTolerance = other.scanFacingTolerance;
        this.scanRadius = other.scanRadius;
        this.facingTolerance = other.facingTolerance;
        this.reachDistance = other.reachDistance;
        this.maxBreakTicks = other.maxBreakTicks;
        this.navigateTimeout = other.navigateTimeout;
        this.positionTimeout = other.positionTimeout;
        this.lookTimeout = other.lookTimeout;
        this.debugEnabled = other.debugEnabled;
    }

    public static BotConfig load() {
        Path configPath = Path.of(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            BotConfig config = new BotConfig();
            config.save();
            return config;
        }
        try (BufferedReader reader = Files.newBufferedReader(configPath)) {
            BotConfig loaded = GSON.fromJson(reader, BotConfig.class);
            if (loaded != null) {
                loaded.save();
                return loaded;
            }
        } catch (IOException ignored) {
        }
        return new BotConfig();
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

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

    // Aim humanization: random offset on block face (blocks). Distribution is
    // Gaussian centred at 0 with sigma = aimOffsetMax/2, clamped to ±aimOffsetMax.
    public double aimOffsetMin = 0.05;
    public double aimOffsetMax = 0.30;

    // Camera speed variation per target (multiplier on spring acceleration).
    // Drawn fresh at the start of each new SCANNING/LOOKING session so
    // successive aims feel different. 1.0 = default critical damping; values
    // above 1.0 introduce a hint of overshoot.
    public double lookSpeedMin = 0.7;
    public double lookSpeedMax = 1.3;

    // Reaction delay at phase transitions: tick count drawn from a clamped
    // Gaussian on every transition where currently the bot reacts instantly
    // (SCANNING→NAVIGATING, POSITIONING→LOOKING, block-broken→next phase).
    // ≈ 50-500 ms at 20 TPS, median 200 ms. Set min == max == 0 to disable.
    public int reactionDelayMeanTicks = 4;
    public int reactionDelaySigmaTicks = 2;
    public int reactionDelayMinTicks = 1;
    public int reactionDelayMaxTicks = 10;

    // Pre-attack commit hesitation: a small uniform pause after both LOOKING
    // gates fire (angular + hit-result) and before the first startAttack call.
    // Replaces the old settleDelay — narrower range and a distinct concept (the
    // "commit moment" between locking on and clicking, not a timing buffer for
    // server sync, which already happens during the LOOKING camera turn).
    public int preAttackHesitationMin = 1;
    public int preAttackHesitationMax = 3;

    // Occasional longer "breather" pause replacing the standard reaction delay
    // at the SCANNING→NAVIGATING transition (the "spotted it, about to walk
    // over" moment). A human doesn't keep a perfectly constant cadence — every
    // now and then there's a beat of distraction before setting off. Chance is
    // rolled once per transition. Set chance to 0 to disable.
    public double longPauseChance = 0.04;
    public int longPauseMinTicks = 12;
    public int longPauseMaxTicks = 25;

    // Hard timeout for COLLECTING if drops never become reachable. Needs to
    // be large enough for the server→client item-entity sync under heavy load
    // (accelerated ticks amplify packet-queue backup).
    public int collectWaitMax = 1200;

    // Consecutive ticks the target block must be observed as air before the
    // break is considered server-confirmed (see design.md).
    public int airConfirmTicks = 8;

    // Ticks items must be absent from the search AABB after at least one
    // sighting before COLLECTING exits (see design.md).
    public int itemAbsenceTicks = 60;

    // Scanning: look toward distant target before walking
    public int scanTimeout = 30;
    public double scanFacingTolerance = 15.0;

    // Block scan radius
    public int scanRadius = 32;

    // Facing tolerance for aim convergence (degrees)
    public double facingTolerance = 5.0;

    // Maximum reach distance for mining
    public double reachDistance = 4.0;

    // Maximum ticks INTERACTING runs before giving up (mine + wait for drop
    // entity). Must cover the slowest legitimate break on the server plus
    // item-entity spawn sync under load.
    public int maxBreakTicks = 400;

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
        this.reactionDelayMeanTicks = other.reactionDelayMeanTicks;
        this.reactionDelaySigmaTicks = other.reactionDelaySigmaTicks;
        this.reactionDelayMinTicks = other.reactionDelayMinTicks;
        this.reactionDelayMaxTicks = other.reactionDelayMaxTicks;
        this.preAttackHesitationMin = other.preAttackHesitationMin;
        this.preAttackHesitationMax = other.preAttackHesitationMax;
        this.longPauseChance = other.longPauseChance;
        this.longPauseMinTicks = other.longPauseMinTicks;
        this.longPauseMaxTicks = other.longPauseMaxTicks;
        this.collectWaitMax = other.collectWaitMax;
        this.airConfirmTicks = other.airConfirmTicks;
        this.itemAbsenceTicks = other.itemAbsenceTicks;
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

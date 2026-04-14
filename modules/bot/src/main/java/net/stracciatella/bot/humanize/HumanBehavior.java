package net.stracciatella.bot.humanize;

import java.util.concurrent.ThreadLocalRandom;

import net.stracciatella.bot.BotConfig;

/**
 * Provides randomization utilities for human-like behavior.
 * All methods are deterministic per-call (generate once, use for duration of a phase).
 */
public class HumanBehavior {

    /**
     * Generate a random aim offset within the configured range.
     * Returns a value between -aimOffsetMax and +aimOffsetMax, biased toward center.
     */
    public static double randomAimOffset(BotConfig config) {
        double range = randomInRange(config.aimOffsetMin, config.aimOffsetMax);
        return ThreadLocalRandom.current().nextBoolean() ? range : -range;
    }

    /**
     * Generate a random settle delay in ticks after aim convergence.
     */
    public static int randomSettleDelay(BotConfig config) {
        return randomIntInRange(config.settleDelayMin, config.settleDelayMax);
    }

    /**
     * Generate a random inter-task cooldown in ticks.
     * Has a configurable chance of being a long pause.
     */
    public static int randomInterTaskDelay(BotConfig config) {
        if (ThreadLocalRandom.current().nextDouble() < config.longPauseChance) {
            return randomIntInRange(config.longPauseMin, config.longPauseMax);
        }
        return randomIntInRange(config.interTaskDelayMin, config.interTaskDelayMax);
    }

    /**
     * Generate a random post-break delay in ticks.
     */
    public static int randomPostBreakDelay(BotConfig config) {
        return randomIntInRange(config.postBreakDelayMin, config.postBreakDelayMax);
    }

    /**
     * Generate a random collection wait time in ticks.
     */
    public static int randomCollectWait(BotConfig config) {
        return randomIntInRange(config.collectWaitMin, config.collectWaitMax);
    }

    /**
     * Generate a random look speed multiplier for this target.
     */
    public static double randomLookSpeedMultiplier(BotConfig config) {
        return randomInRange(config.lookSpeedMin, config.lookSpeedMax);
    }

    private static double randomInRange(double min, double max) {
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private static int randomIntInRange(int min, int max) {
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}

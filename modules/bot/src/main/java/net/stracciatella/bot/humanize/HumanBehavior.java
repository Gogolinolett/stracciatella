package net.stracciatella.bot.humanize;

import java.util.concurrent.ThreadLocalRandom;

import net.stracciatella.bot.BotConfig;

/**
 * Provides randomization utilities for human-like behavior.
 * All methods are deterministic per-call (generate once, use for duration of a phase).
 *
 * <p>A "session skill" multiplier is rolled once per world join via
 * {@link #rollSessionSkill()} and applied to look-speed (multiplicatively) and
 * reaction delay (divisively). Each session feels like a slightly different
 * human — sometimes more responsive, sometimes more languid — without
 * persistent state.
 */
public class HumanBehavior {

    private static final double SESSION_SKILL_MEAN = 1.0;
    private static final double SESSION_SKILL_SIGMA = 0.05;
    private static final double SESSION_SKILL_MIN = 0.85;
    private static final double SESSION_SKILL_MAX = 1.15;
    private static double sessionSkill = 1.0;

    /**
     * Roll a fresh session skill multiplier. Call once per session start
     * (e.g. from {@code BotController.loadConfig()} on world join).
     */
    public static void rollSessionSkill() {
        double g = ThreadLocalRandom.current().nextGaussian() * SESSION_SKILL_SIGMA + SESSION_SKILL_MEAN;
        if (g < SESSION_SKILL_MIN) g = SESSION_SKILL_MIN;
        if (g > SESSION_SKILL_MAX) g = SESSION_SKILL_MAX;
        sessionSkill = g;
    }

    public static double sessionSkill() {
        return sessionSkill;
    }

    // Blunders — the rare deliberate slips (a long zone-out, a detour, a block
    // dug past the chunk border and put back, a stair step knocked out and
    // rebuilt, idle clicks on the way) —
    // switched off for the length of a run that asserts an exact outcome. A
    // runtime flag rather than the config chances, so nothing a test does here
    // can be written back to the player's files.
    private static boolean blundersSuppressed = false;

    public static void suppressBlunders(boolean suppressed) {
        blundersSuppressed = suppressed;
    }

    /**
     * Whether a blunder that happens with probability {@code chance} happens
     * now. Never while blunders are suppressed.
     */
    public static boolean blunder(double chance) {
        return !blundersSuppressed && chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    /** {@code chance}, or 0 while blunders are suppressed — for a caller that rolls it itself. */
    public static double blunderChance(double chance) {
        return blundersSuppressed ? 0.0 : chance;
    }

    /**
     * Generate a random aim offset within the configured range, distributed as
     * a clamped Gaussian centred at 0 with σ = aimOffsetMax/2. Most samples
     * cluster near the centre (where a human's aim naturally lands), with
     * heavy tails rare but possible. Values below {@code aimOffsetMin} in
     * magnitude are pushed out to ±aimOffsetMin so the result is never
     * imperceptibly close to dead-centre.
     */
    public static double randomAimOffset(BotConfig config) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double sigma = config.aimOffsetMax / 2.0;
        double v = r.nextGaussian() * sigma;
        // Clamp magnitude to [min, max]
        if (v > config.aimOffsetMax) v = config.aimOffsetMax;
        if (v < -config.aimOffsetMax) v = -config.aimOffsetMax;
        if (Math.abs(v) < config.aimOffsetMin) {
            v = v < 0 ? -config.aimOffsetMin : config.aimOffsetMin;
        }
        return v;
    }

    /**
     * Generate a random look-speed multiplier for this target, uniformly
     * distributed in [lookSpeedMin, lookSpeedMax] then scaled by the
     * current {@link #sessionSkill()}. Applied via
     * {@code CameraController.setLookSpeedMultiplier} so successive aims have
     * varying turn speeds; a "skilled" session aims slightly faster on
     * every block.
     */
    public static double randomLookSpeedMultiplier(BotConfig config) {
        return randomInRange(config.lookSpeedMin, config.lookSpeedMax) * sessionSkill;
    }

    /**
     * Generate a Gaussian-distributed reaction delay in ticks. Clamped to
     * {@code [reactionDelayMinTicks, reactionDelayMaxTicks]} then divided
     * by the current {@link #sessionSkill()} (skilled session = faster
     * reactions). Returns 0 when the configured mean is zero
     * (humanness disabled).
     */
    public static int randomReactionDelayTicks(BotConfig config) {
        if (config.reactionDelayMeanTicks <= 0
                && config.reactionDelaySigmaTicks <= 0) {
            return 0;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double g = r.nextGaussian() * config.reactionDelaySigmaTicks
                + config.reactionDelayMeanTicks;
        g /= sessionSkill;
        int v = (int) Math.round(g);
        if (v < config.reactionDelayMinTicks) v = config.reactionDelayMinTicks;
        if (v > config.reactionDelayMaxTicks) v = config.reactionDelayMaxTicks;
        return Math.max(0, v);
    }

    /**
     * Generate a uniform pre-attack hesitation in ticks. The "commit moment"
     * between the LOOKING gates firing and the first startAttack call.
     */
    public static int randomPreAttackHesitation(BotConfig config) {
        return randomIntInRange(config.preAttackHesitationMin, config.preAttackHesitationMax);
    }

    /**
     * Gap in ticks between two shift-clicks inside an open container, uniform in
     * [restockClickDelayMin, restockClickDelayMax]. Emptying thirty stacks into a
     * chest in thirty consecutive ticks is not something a person's hand does.
     */
    public static int randomRestockClickDelay(BotConfig config) {
        return randomIntInRange(config.restockClickDelayMin, config.restockClickDelayMax);
    }

    /**
     * Downward gaze pitch (degrees below horizon) used while collecting
     * drops, uniform in [collectGazePitchMinDeg, collectGazePitchMaxDeg].
     * Rolled once per COLLECTING phase — the "scanning the ground ahead"
     * angle a player holds instead of craning ever steeper at a nearby item.
     */
    public static double randomCollectGazePitch(BotConfig config) {
        return randomInRange(config.collectGazePitchMinDeg, config.collectGazePitchMaxDeg);
    }

    /**
     * A pause between two pieces of work: how long, and whether it is spent
     * away from the game — tabbed out, with the camera dead still.
     */
    public record Breather(int ticks, boolean away) {
        public static final Breather NONE = new Breather(0, false);
    }

    /**
     * The occasional breather: a pause in
     * {@code [longPauseMinTicks, longPauseMaxTicks]} with probability
     * {@code longPauseChance}, now and then (a blunder, {@code zoneOutChance})
     * a spell away from the game of {@code [zoneOutMinTicks, zoneOutMaxTicks]},
     * and nothing the rest of the time.
     *
     * <p>For an action a behavior repeats thousands of times. Pausing on
     * *every* repetition is itself the machine-constant cadence the pause
     * exists to break, and it lands in the critical path of every single one.
     *
     * <p>Both lengths are log-normal across their range, not uniform: most
     * pauses short, some a good deal longer, the way a person's are. Uniform
     * over 12-25 ticks every pause was the same second, reported as pauses
     * with no variety in them.
     */
    public static Breather randomBreather(BotConfig config) {
        // Far rarer and far longer: the player tabbed out for a few seconds,
        // the way a person's attention leaves the game now and then.
        if (blunder(config.zoneOutChance)) {
            return new Breather(randomLogNormalInRange(config.zoneOutMinTicks, config.zoneOutMaxTicks), true);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (config.longPauseChance > 0 && r.nextDouble() < config.longPauseChance) {
            return new Breather(randomLogNormalInRange(config.longPauseMinTicks, config.longPauseMaxTicks), false);
        }
        return Breather.NONE;
    }

    /**
     * A length in {@code [min, max]}, log-normal round their geometric mean
     * with the range four standard deviations wide, so about one draw in twenty
     * is clamped to an end.
     */
    static int randomLogNormalInRange(int min, int max) {
        if (min <= 0 || min >= max) {
            return randomIntInRange(min, max);
        }
        double low = Math.log(min);
        double high = Math.log(max);
        double draw = Math.exp((low + high) / 2.0
                + ThreadLocalRandom.current().nextGaussian() * (high - low) / 4.0);
        return (int) Math.max(min, Math.min(max, Math.round(draw)));
    }

    private static double randomInRange(double min, double max) {
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    static int randomIntInRange(int min, int max) {
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}

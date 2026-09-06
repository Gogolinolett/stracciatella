package net.stracciatella.bot.safety;

/**
 * Latch between the packet mixins that detect trouble and
 * {@code BehaviorRunner}, which decides what to do about it.
 *
 * <p>Detection is packet-driven rather than polled: the client already gets
 * told when the player is damaged ({@code ClientboundDamageEventPacket}) and
 * when someone swings at something nearby ({@code ClientboundSoundPacket}).
 * Sampling health every tick would miss a hit that is regenerated within the
 * same tick and costs work on every tick that nothing happens.
 *
 * <p>The two triggers latch separately. A single slot would mean a trigger the
 * active policy ignores could mask one it cares about when both land in the
 * same tick. The runner therefore consumes <em>both</em> every tick and only
 * then decides which ones matter — a flag left set because nobody was
 * interested would otherwise fire the moment a behavior that does care starts.
 *
 * <p>Not thread-safe, and deliberately so: both mixins inject at TAIL, past
 * {@code PacketUtils.ensureRunningOnSameThread}, so they only ever run on the
 * client thread — the same thread that ticks the runner.
 */
public final class BotAlarm {

    /**
     * Radius in which another player's attack swing is taken to be aimed at
     * the bot. A swing sound plays at the <em>attacker's</em> position and
     * melee reach is ~3 blocks; 5 leaves room for the packet's 1/8-block
     * position quantisation and for the attacker moving between the swing and
     * the packet arriving.
     */
    private static final double ATTACK_RANGE = 5.0;

    /**
     * Sounds this close to the bot are the bot's own swing echoing back.
     * {@code Player.attack} broadcasts with a {@code null} source player, so
     * the attacker's own client receives its own attack sound — without this
     * band the bot would stop itself the first time it swung at anything.
     */
    private static final double SELF_RANGE = 0.5;

    private static boolean damaged = false;
    private static boolean attacked = false;

    private BotAlarm() {
    }

    /** The bot took damage from any source. */
    public static void raiseDamage() {
        damaged = true;
    }

    /** A player swung at the bot, whether or not it did damage. */
    public static void raiseAttack() {
        attacked = true;
    }

    /** Read and clear the damage latch. */
    public static boolean consumeDamage() {
        boolean value = damaged;
        damaged = false;
        return value;
    }

    /** Read and clear the player-attack latch. */
    public static boolean consumeAttack() {
        boolean value = attacked;
        attacked = false;
        return value;
    }

    /**
     * Drop both latches. Called when a behavior starts so an event from before
     * the run cannot kill it on its first tick.
     */
    public static void clear() {
        damaged = false;
        attacked = false;
    }

    /**
     * Whether an attack sound at the given position counts as "someone is
     * hitting the bot": close enough to be aimed at it, but not so close that
     * it is the bot's own swing.
     *
     * <p>Kept free of Minecraft types on purpose — the distance bands are the
     * part with actual failure modes (too wide stops the run whenever anyone
     * fights nearby, no self-filter stops it whenever the bot swings), and
     * this way they are testable without booting a game.
     */
    public static boolean isAttackerInRange(double soundX, double soundY, double soundZ,
                                            double botX, double botY, double botZ) {
        double dx = soundX - botX;
        double dy = soundY - botY;
        double dz = soundZ - botZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        return distSq > SELF_RANGE * SELF_RANGE && distSq <= ATTACK_RANGE * ATTACK_RANGE;
    }
}

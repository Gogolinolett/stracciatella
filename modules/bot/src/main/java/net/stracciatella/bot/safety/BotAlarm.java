package net.stracciatella.bot.safety;

/**
 * Latch between the packet mixins that detect trouble and
 * {@code BehaviorRunner}, which decides what to do about it.
 *
 * <p>Detection is packet-driven rather than polled: the client already gets
 * told when the player is damaged ({@code ClientboundDamageEventPacket}), when
 * someone swings at something nearby ({@code ClientboundSoundPacket}) and when
 * the server puts the player somewhere ({@code ClientboundPlayerPositionPacket}).
 * Sampling health every tick would miss a hit that is regenerated within the
 * same tick and costs work on every tick that nothing happens.
 *
 * <p>The three triggers latch separately. A single slot would mean a trigger the
 * active policy ignores could mask one it cares about when both land in the
 * same tick. The runner therefore consumes <em>all</em> of them every tick and
 * only then decides which ones matter — a flag left set because nobody was
 * interested would otherwise fire the moment a behavior that does care starts.
 *
 * <p>Not thread-safe, and deliberately so: the mixins all inject at TAIL, past
 * {@code PacketUtils.ensureRunningOnSameThread}, so they only ever run on the
 * client thread — the same thread that ticks the runner and feeds the trail.
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

    /**
     * How many ticks of the bot's own path to keep, so a server-placed position
     * can be told apart from a teleport — see {@link #isNewPlace}.
     *
     * <p>Two seconds. It has to outlast the furthest the client can get from a
     * position the server still holds as good, which is a fall or a stride: the
     * widest correction measured was 1.41 blocks, about seven ticks of walking.
     * It also must not be so long that the bot's own route starts hiding real
     * teleports, and nothing puts a bot back where it stood two seconds ago
     * except the server.
     */
    private static final int TRAIL_TICKS = 40;

    /**
     * How close a placed position has to be to one on the trail to be the same
     * spot. The two ought to match to the bit — the server hands back the very
     * doubles the client sent it — so this only absorbs a rounding somewhere on
     * the way, and it stays far under the 0.6 blocks that two players standing
     * next to each other cannot get inside of.
     */
    private static final double SAME_SPOT = 0.05;

    private static boolean damaged = false;
    private static boolean attacked = false;
    private static boolean teleported = false;

    /** The last {@link #TRAIL_TICKS} positions, flat as x, y, z triples. */
    private static final double[] trail = new double[TRAIL_TICKS * 3];
    private static int trailNext = 0;
    private static int trailSize = 0;

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

    /** The server put the player somewhere it did not walk to. */
    public static void raiseTeleport() {
        teleported = true;
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

    /** Read and clear the teleport latch. */
    public static boolean consumeTeleport() {
        boolean value = teleported;
        teleported = false;
        return value;
    }

    /**
     * Drop every latch. Called when a behavior starts so an event from before
     * the run cannot kill it on its first tick — which is what makes the test
     * suite's habit of teleporting the bot into position harmless.
     */
    public static void clear() {
        damaged = false;
        attacked = false;
        teleported = false;
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

    /** Note where the bot is standing this tick, for {@link #isNewPlace}. */
    public static void recordPosition(double x, double y, double z) {
        trail[trailNext * 3] = x;
        trail[trailNext * 3 + 1] = y;
        trail[trailNext * 3 + 2] = z;
        trailNext = (trailNext + 1) % TRAIL_TICKS;
        if (trailSize < TRAIL_TICKS) {
            trailSize++;
        }
    }

    /**
     * Whether a position the server is placing the player at is somewhere the
     * bot has <em>not</em> just been — which is what separates a teleport from
     * the server correcting a move it did not accept.
     *
     * <p>A distance band cannot do this job, and the attempt is worth recording
     * because it looked obvious. Vanilla's own re-placements ride the same
     * packet: with a move refused for landing the player inside a block, the
     * server puts them back on the last position it did accept, and a bot that
     * mines the floor from under itself provokes exactly that. Measured on real
     * runs those came in at 0.078, 0.768 and <b>1.41</b> blocks — while the
     * event the guard exists for, staff pulling the bot onto the spot they are
     * standing on, is about 0.6. The two ranges overlap, so no threshold
     * separates them.
     *
     * <p>What separates them is where the position points. A correction always
     * names somewhere the client itself reported a moment ago; a teleport names
     * somewhere it has never been. So the trail is the test, and the only
     * number left is how long a memory to keep ({@link #TRAIL_TICKS}).
     *
     * <p>Kept free of Minecraft types for the same reason as
     * {@link #isAttackerInRange}: this is where the failure modes live, and they
     * are checkable without booting a game.
     */
    public static boolean isNewPlace(double x, double y, double z) {
        for (int i = 0; i < trailSize; i++) {
            double dx = x - trail[i * 3];
            double dy = y - trail[i * 3 + 1];
            double dz = z - trail[i * 3 + 2];
            if (dx * dx + dy * dy + dz * dz <= SAME_SPOT * SAME_SPOT) {
                return false;
            }
        }
        return true;
    }
}

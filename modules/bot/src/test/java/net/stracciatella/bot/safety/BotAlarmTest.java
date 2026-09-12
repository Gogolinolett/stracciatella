package net.stracciatella.bot.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the two judgement calls the alarm makes before it fires: whether a
 * swing was aimed at the bot, and whether a position the server placed the bot
 * at is somewhere it has not just been.
 *
 * <p>Neither is reachable in-game. The attack path needs a second player to
 * swing at the bot, and the teleport path's hard case is the server correcting
 * a move — which the in-game test can only produce by accident, never on
 * demand. Both are pure arithmetic over doubles, so the failure modes that
 * would actually bite (never firing; firing on the bot's own swing; firing
 * every time the bot digs the floor out from under itself) are pinned down here
 * instead.
 */
public class BotAlarmTest {

    private static final double BOT_X = 100.0;
    private static final double BOT_Y = 64.0;
    private static final double BOT_Z = -50.0;

    @Test
    public void attackerAtMeleeRangeCounts() {
        assertTrue(inRange(BOT_X + 2.0, BOT_Y, BOT_Z));
        assertTrue(inRange(BOT_X, BOT_Y, BOT_Z + 3.0));
        // Standing on top of the bot, e.g. one block up on a ledge.
        assertTrue(inRange(BOT_X + 1.0, BOT_Y + 2.0, BOT_Z));
    }

    @Test
    public void ownSwingIsIgnored() {
        // Player.attack broadcasts with a null source player, so the bot hears
        // its own swing at (very nearly) its own position.
        assertFalse(inRange(BOT_X, BOT_Y, BOT_Z));
        assertFalse(inRange(BOT_X + 0.2, BOT_Y + 0.1, BOT_Z - 0.2));
    }

    @Test
    public void distantFightIsIgnored() {
        assertFalse(inRange(BOT_X + 8.0, BOT_Y, BOT_Z));
        assertFalse(inRange(BOT_X, BOT_Y + 20.0, BOT_Z));
    }

    @Test
    public void bandEdgesAreInclusiveOutwardExclusiveInward() {
        // Exactly on the self radius is still the bot itself; exactly on the
        // attack radius still counts as an attacker.
        assertFalse(inRange(BOT_X + 0.5, BOT_Y, BOT_Z));
        assertTrue(inRange(BOT_X + 0.5001, BOT_Y, BOT_Z));
        assertTrue(inRange(BOT_X + 5.0, BOT_Y, BOT_Z));
        assertFalse(inRange(BOT_X + 5.0001, BOT_Y, BOT_Z));
    }

    @Test
    public void aCorrectionOntoTheOwnPathIsNotATeleport() {
        // A bot walking away from where it stood, then a move the server
        // refuses: it is put back on the position it last reported. The three
        // distances are the ones measured in real runs — a nudge back onto the
        // block grid after digging the floor away, a fall wound back, and a
        // whole diagonal block. All three are further than a player standing
        // next to the bot, which is why the trail and not a band decides.
        standStill(BOT_X, BOT_Y, BOT_Z);
        walkTo(BOT_X, BOT_Y - 0.078, BOT_Z);
        assertFalse(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z));

        walkTo(BOT_X, BOT_Y - 0.768, BOT_Z);
        assertFalse(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z));

        walkTo(BOT_X - 1.0, BOT_Y - 1.0, BOT_Z);
        assertFalse(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z));
    }

    @Test
    public void beingPutSomewhereElseIsATeleport() {
        standStill(BOT_X, BOT_Y, BOT_Z);
        // Staff pulling the bot onto the spot they are standing on: two players
        // cannot be closer than their 0.6-block width, and that is the whole
        // event — never the long throw a jump-distance test would look for.
        assertTrue(BotAlarm.isNewPlace(BOT_X + 0.6, BOT_Y, BOT_Z));
        assertTrue(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z + 1.0));
        assertTrue(BotAlarm.isNewPlace(BOT_X - 4000.0, BOT_Y + 20.0, BOT_Z + 300.0));
    }

    @Test
    public void thePathIsForgottenAgain() {
        // Two seconds of standing somewhere else is enough to make the old spot
        // strange again — otherwise a long run's route would hide a teleport
        // back into any part of it.
        standStill(BOT_X, BOT_Y, BOT_Z);
        assertFalse(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z));
        standStill(BOT_X + 50.0, BOT_Y, BOT_Z);
        assertTrue(BotAlarm.isNewPlace(BOT_X, BOT_Y, BOT_Z));
    }

    private static boolean inRange(double soundX, double soundY, double soundZ) {
        return BotAlarm.isAttackerInRange(soundX, soundY, soundZ, BOT_X, BOT_Y, BOT_Z);
    }

    /** One tick of the bot's path. */
    private static void walkTo(double x, double y, double z) {
        BotAlarm.recordPosition(x, y, z);
    }

    /**
     * Long enough in one place to push everything before it off the trail —
     * the only way to get a known state out of a static ring buffer.
     */
    private static void standStill(double x, double y, double z) {
        for (int tick = 0; tick < 64; tick++) {
            BotAlarm.recordPosition(x, y, z);
        }
    }
}

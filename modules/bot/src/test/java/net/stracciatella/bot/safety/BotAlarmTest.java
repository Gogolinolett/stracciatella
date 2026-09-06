package net.stracciatella.bot.safety;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the distance bands of the player-attack trigger. The end-to-end path
 * cannot be tested in-game — it needs a second player to swing at the bot —
 * so the two failure modes that would actually bite (never firing, or firing
 * on the bot's own swing) are pinned down here instead.
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

    private static boolean inRange(double soundX, double soundY, double soundZ) {
        return BotAlarm.isAttackerInRange(soundX, soundY, soundZ, BOT_X, BOT_Y, BOT_Z);
    }
}

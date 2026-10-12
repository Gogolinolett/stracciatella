package net.stracciatella.bot.humanize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import net.stracciatella.bot.BotConfig;
import org.junit.jupiter.api.Test;

/**
 * The length of a breather: spread out the way a person's pauses are, most of
 * them short and some a good deal longer, never outside the configured range.
 * Uniform over 12 to 25 ticks every pause was the same second long.
 */
public class HumanBehaviorTest {

    private static final int MIN = 6;
    private static final int MAX = 60;
    private static final int DRAWS = 20000;

    @Test
    public void breathersStayInTheirRange() {
        for (int i = 0; i < DRAWS; i++) {
            int ticks = HumanBehavior.randomLogNormalInRange(MIN, MAX);
            assertTrue(ticks >= MIN && ticks <= MAX, "drew " + ticks);
        }
    }

    @Test
    public void breathersAreMostlyShortAndSometimesLong() {
        int[] draws = new int[DRAWS];
        for (int i = 0; i < DRAWS; i++) {
            draws[i] = HumanBehavior.randomLogNormalInRange(MIN, MAX);
        }
        Arrays.sort(draws);
        int median = draws[DRAWS / 2];
        assertTrue(median >= 16 && median <= 22, "median " + median);
        long shortOnes = Arrays.stream(draws).filter(ticks -> ticks < 12).count();
        long longOnes = Arrays.stream(draws).filter(ticks -> ticks > 30).count();
        assertTrue(shortOnes > DRAWS * 0.15, "only " + shortOnes + " pauses under 12 ticks");
        assertTrue(longOnes > DRAWS * 0.15, "only " + longOnes + " pauses over 30 ticks");
    }

    /**
     * A walk stuck against something it will not climb goes round at once,
     * and with {@code wallBumpChance} stays pressed against it for longer
     * first — never past {@code wallBumpMaxTicks}, and never while blunders
     * are suppressed.
     */
    @Test
    public void aBumpIsLongerThanGoingRoundAndBounded() {
        BotConfig config = new BotConfig();
        config.wallBumpMaxTicks = 10;
        HumanBehavior.suppressBlunders(false);
        try {
            config.wallBumpChance = 0.0;
            for (int i = 0; i < DRAWS; i++) {
                assertEquals(2, HumanBehavior.randomGoRoundPatience(config, 2));
            }
            config.wallBumpChance = 1.0;
            boolean sawMax = false;
            for (int i = 0; i < DRAWS; i++) {
                int ticks = HumanBehavior.randomGoRoundPatience(config, 2);
                assertTrue(ticks >= 3 && ticks <= 10, "drew " + ticks);
                sawMax |= ticks == 10;
            }
            assertTrue(sawMax, "never drew the longest bump");
            HumanBehavior.suppressBlunders(true);
            assertEquals(2, HumanBehavior.randomGoRoundPatience(config, 2));
        } finally {
            HumanBehavior.suppressBlunders(false);
        }
    }
}

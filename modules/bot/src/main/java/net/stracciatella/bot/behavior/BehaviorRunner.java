package net.stracciatella.bot.behavior;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.safety.BotAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static registry and executor for {@link BotBehavior}s — the same pattern as
 * the pathfinding module's Navigator for travel methods. Specialized modules
 * register their behaviors at init; at most one behavior runs at a time and
 * is ticked from the bot module's END_CLIENT_TICK hook (before the
 * BotController tick, so plans made this tick are executed this tick).
 *
 * <p>The runner also owns the active {@link BotPolicy}: it reads the policy
 * once at start, pushes it down to {@link BotController}, and enforces the
 * stop guards here rather than inside each behavior. A behavior would have to
 * check them between every planned batch to react as fast, and each new
 * behavior would have to remember to do it.
 */
public class BehaviorRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("BehaviorRunner");

    /** Ticks between the three alert plings, so they read as three beeps. */
    private static final int ALERT_PLING_SPACING = 5;
    private static final int ALERT_PLING_COUNT = 3;

    private static final Map<String, BotBehavior> registry = new LinkedHashMap<>();
    private static BotBehavior active = null;
    private static BotPolicy activePolicy = BotPolicy.none();

    public static void register(BotBehavior behavior) {
        registry.put(behavior.id(), behavior);
    }

    /**
     * Start the behavior with the given id, aborting any currently active
     * one first. Returns false if no behavior with that id is registered.
     */
    public static boolean start(String id) {
        BotBehavior behavior = registry.get(id);
        if (behavior == null) {
            return false;
        }
        stop();
        active = behavior;
        activePolicy = behavior.policy();
        BotController.setPolicy(activePolicy);
        // Damage or a swing from before this run must not kill it on tick 1.
        BotAlarm.clear();
        behavior.start(Minecraft.getInstance());
        LOGGER.info("Behavior started: {}", id);
        return true;
    }

    /**
     * Abort the active behavior, if any. Does not touch the BotController
     * beyond what the behavior's own abort() does — stopping flows top-down.
     */
    public static void stop() {
        if (active != null) {
            LOGGER.info("Behavior aborted: {}", active.id());
            active.abort();
            clearActive();
        }
    }

    public static boolean isActive() {
        return active != null;
    }

    public static String activeId() {
        return active != null ? active.id() : null;
    }

    public static String statusLine() {
        return active != null ? active.id() + ": " + active.statusLine() : "none";
    }

    public static void tick(Minecraft client) {
        if (active == null) {
            return;
        }

        // Guards run before the behavior's own tick so a run stops on the tick
        // the trouble is detected, not after one more planned batch. Both
        // alarm latches are consumed unconditionally: a trigger this policy
        // ignores must not stay set and fire at the start of the next run.
        boolean damaged = BotAlarm.consumeDamage();
        boolean attacked = BotAlarm.consumeAttack();
        String stopReason = stopReason(client.player, damaged, attacked);
        if (stopReason != null) {
            active.abort();
            finish(client, BehaviorStatus.FAILED, stopReason);
            return;
        }

        BehaviorStatus status = active.tick(client);
        if (status == BehaviorStatus.RUNNING) {
            return;
        }
        finish(client, status, null);
    }

    /**
     * Why the active policy wants this run stopped, or {@code null} to carry
     * on. Reasons are phrased for the supervising player, who sees them in
     * chat without any other context about what the bot was doing.
     */
    private static String stopReason(LocalPlayer player, boolean damaged, boolean attacked) {
        if (player == null) {
            return null;
        }
        if (activePolicy.stopOnDamage() && damaged) {
            return "took damage";
        }
        if (activePolicy.stopOnPlayerAttack() && attacked) {
            return "a player attacked the bot";
        }
        if (activePolicy.stopWhenInventoryFull()) {
            int free = InventoryHelper.freeSlots(player);
            if (free < activePolicy.minFreeSlots()) {
                return "inventory full (" + free + " empty slots left)";
            }
        }
        return null;
    }

    /**
     * End the run, tell the supervising player how it went, and hand the
     * execution layer back to its unconfigured state.
     *
     * @param stopReason why a guard cut the run short, or {@code null} when
     *                   the behavior itself decided it was done
     */
    private static void finish(Minecraft client, BehaviorStatus status, String stopReason) {
        boolean abnormal = status != BehaviorStatus.SUCCEEDED;
        String summary = active.statusLine();
        String message = "Behavior " + active.id()
                + (abnormal ? " STOPPED" : " finished")
                + (stopReason != null ? " — " + stopReason : "")
                + " (" + summary + ")";
        LOGGER.info(message);
        if (client.player != null) {
            // Red on any abnormal stop: the supervising player may be several
            // chunks away and needs to tell "it's done" from "it gave up".
            client.player.displayClientMessage(
                    Component.literal(message)
                            .withStyle(abnormal ? ChatFormatting.RED : ChatFormatting.GRAY),
                    false);
        }
        playAlert(client, abnormal);
        clearActive();
    }

    /**
     * Audible counterpart to the chat line, so an unattended run gets noticed
     * without watching the chat: an insistent triple pling when something went
     * wrong, a single soft chime when the bot simply finished.
     */
    private static void playAlert(Minecraft client, boolean abnormal) {
        // forUI takes (pitch, volume) in that order — not the usual
        // (volume, pitch) of Level.playSound.
        var sounds = client.getSoundManager();
        if (!abnormal) {
            sounds.play(SimpleSoundInstance.forUI(
                    SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 0.4f));
            return;
        }
        for (int i = 0; i < ALERT_PLING_COUNT; i++) {
            sounds.playDelayed(
                    SimpleSoundInstance.forUI(
                            SoundEvents.NOTE_BLOCK_PLING.value(), 2.0f, 1.0f),
                    i * ALERT_PLING_SPACING);
        }
    }

    private static void clearActive() {
        active = null;
        activePolicy = BotPolicy.none();
        BotController.setPolicy(null);
    }
}

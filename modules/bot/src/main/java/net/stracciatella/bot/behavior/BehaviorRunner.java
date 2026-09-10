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
 *
 * <h2>The restock interruption</h2>
 *
 * <p>Running out of pickaxes, filler blocks or empty slots is not a reason to
 * end a run — it is a reason to walk to a chest and come back. So the runner
 * holds <b>two</b> slots rather than one: the behavior being ticked, and one
 * behavior suspended underneath it. A shortfall against the active behavior's
 * {@link BotBehavior#restockNeeds()} moves it into the lower slot and puts
 * {@link RestockBehavior} in the upper one; when that succeeds the lower one is
 * started again.
 *
 * <p>Two slots, not a stack of N: a restock cannot itself need a restock, and
 * anything deeper would be a bug rather than a use case. A fixed pair says so
 * in the type, where a {@code Deque} would invite one.
 *
 * <p><b>Suspend is {@code abort()} and resume is {@code start()}</b> — no new
 * interface method, no saved state. That is a real constraint on who may opt
 * in, and it is stated on {@link BotBehavior#restockNeeds()}: a behavior that
 * cannot find its place again by reading the world must keep the default and
 * never declare a manifest. The alternative, a {@code suspend}/{@code resume}
 * pair on the interface, was rejected because every behavior would then have to
 * implement two more methods to say "I do not do that", and a wrong
 * implementation of them fails in exactly the same way as a wrong
 * {@code start} — with no compiler help either way.
 */
public class BehaviorRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("BehaviorRunner");

    /** Ticks between the three alert plings, so they read as three beeps. */
    private static final int ALERT_PLING_SPACING = 5;
    private static final int ALERT_PLING_COUNT = 3;

    private static final Map<String, BotBehavior> registry = new LinkedHashMap<>();
    private static BotBehavior active = null;
    private static BotPolicy activePolicy = BotPolicy.none();
    /**
     * The behavior a restock interrupted, waiting to be started again. Non-null
     * exactly while {@code active} is the restock, which is also what stops a
     * restock from interrupting a restock.
     */
    private static BotBehavior suspended = null;
    /**
     * Whether this run has already said that it would restock if it could. One
     * line per run, like the controller's no-food warning: the shortfall is
     * re-checked every tick and would otherwise fill the log.
     */
    private static boolean unservedRestockWarned = false;

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
        unservedRestockWarned = false;
        enter(Minecraft.getInstance(), behavior);
        LOGGER.info("Behavior started: {}", id);
        return true;
    }

    /**
     * Go and restock now, without waiting for anything to run out — what
     * {@code /bot restock} does.
     *
     * <p>With a behavior running this is the ordinary interruption, so the run
     * resumes afterwards. With nothing running it is a plain restock on its own,
     * and since there is no suspended manifest the trip deposits the whole
     * inventory and brings nothing back: "go and empty your pockets", which is a
     * useful thing to be able to ask for.
     *
     * @return false when a restock is already in flight
     */
    public static boolean requestRestock(Minecraft client) {
        if (suspended != null) {
            return false;
        }
        if (active == null) {
            return start(RestockBehavior.ID);
        }
        if (RestockBehavior.ID.equals(active.id())) {
            return false;
        }
        beginRestock(client, "asked for by hand");
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
        if (active == null) {
            return "none";
        }
        String line = active.id() + ": " + active.statusLine();
        return suspended == null ? line : line + " (" + suspended.id() + " suspended)";
    }

    /**
     * The manifest of the behavior a restock is currently serving. Read by
     * {@link RestockBehavior} to know what to deposit and what to withdraw —
     * one list doing both jobs, see {@link RestockNeeds}.
     */
    public static RestockNeeds suspendedNeeds() {
        return suspended != null ? suspended.restockNeeds() : RestockNeeds.none();
    }

    public static void tick(Minecraft client) {
        // Consumed unconditionally, like the alarm latches below and for the
        // same reason: a block the bot could not mine during a run nobody asked
        // to restock must not still be sitting here when the next run starts.
        var missingTool = BotController.consumeMissingTool();

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
            abandonSuspended();
            finish(client, BehaviorStatus.FAILED, stopReason);
            return;
        }

        // Safety first, supplies second: a bot that is being hit stops, it does
        // not go shopping. Skipped entirely while a restock is in flight —
        // that is what `suspended != null` means.
        if (suspended == null) {
            String restockReason = restockReason(client, missingTool);
            if (restockReason != null) {
                beginRestock(client, restockReason);
                return;
            }
        }

        // Supplies before the supply guard, which is why this is not up with the
        // safety checks: a full inventory is a reason to walk to a chest where
        // there is one, and only a reason to stop where there is not. Asked after
        // the restock, the same threshold serves both — and a behavior needs no
        // second number and no conditional policy to say so.
        String supplyStop = inventoryFullReason(client.player);
        if (supplyStop != null) {
            active.abort();
            abandonSuspended();
            finish(client, BehaviorStatus.FAILED, supplyStop);
            return;
        }

        BehaviorStatus status = active.tick(client);
        if (status == BehaviorStatus.RUNNING) {
            return;
        }
        if (suspended != null) {
            endRestock(client, status);
            return;
        }
        finish(client, status, null);
    }

    /**
     * Why the active behavior cannot carry on with what it is holding, or
     * {@code null} when it can.
     *
     * @param missingTool a block the execution layer just tried to mine without
     *                    carrying anything that would drop it, or {@code null}
     */
    private static String restockReason(Minecraft client, Component missingTool) {
        LocalPlayer player = client.player;
        if (player == null) {
            return null;
        }
        RestockNeeds needs = active.restockNeeds();
        if (needs.isEmpty()) {
            // Not opted in. The missing tool is still a real fact, but a restock
            // that has no manifest would walk to a chest and withdraw nothing.
            return null;
        }
        String reason = missingTool != null
                ? "no tool that drops " + missingTool.getString()
                : needs.shortfall(player);
        if (reason == null) {
            return null;
        }
        // A shortfall on a server nobody has pointed at a chest is not a reason
        // to end a run — it is a reason the run cannot be helped. Said once and
        // then left alone, so the behavior carries on and ends on its own terms:
        // the inventory-full guard, or a block it has nothing to break with.
        // Without this, `chunkMinerRestock` defaulting to on would turn the first
        // `/miner chunk start` on a fresh install into an instant failure,
        // because a bot that has just been handed a pickaxe is short of
        // everything else on its manifest.
        if (!RestockBehavior.hasStorage(client)) {
            if (!unservedRestockWarned) {
                unservedRestockWarned = true;
                LOGGER.warn("Behavior {} needs to restock ({}) but no storage is configured"
                        + " for this server — carrying on", active.id(), reason);
            }
            return null;
        }
        return reason;
    }

    /**
     * Move the running behavior into the lower slot and put the restock in the
     * upper one. The restock reads its shopping list from
     * {@link #suspendedNeeds()} and its way home from where the bot is standing
     * right now — which is exactly the work site, because this happens on the
     * tick the shortfall was noticed.
     */
    private static void beginRestock(Minecraft client, String reason) {
        BotBehavior restock = registry.get(RestockBehavior.ID);
        if (restock == null) {
            // A behavior declared a manifest but nothing can serve it. Ending
            // the run says so; carrying on would mine a whole chunk into an
            // inventory with no room and no pickaxe.
            active.abort();
            finish(client, BehaviorStatus.FAILED,
                    "needs to restock (" + reason + ") but no restock behavior is registered");
            return;
        }
        LOGGER.info("Suspending {} for a restock: {}", active.id(), reason);
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("Restocking — " + reason).withStyle(ChatFormatting.YELLOW),
                    false);
        }
        active.abort();
        suspended = active;
        enter(client, restock);
    }

    /**
     * The restock finished. Start the suspended behavior again, or end the whole
     * run when the trip did not actually fix anything.
     */
    private static void endRestock(Minecraft client, BehaviorStatus status) {
        BotBehavior resumed = suspended;
        suspended = null;
        if (status != BehaviorStatus.SUCCEEDED) {
            String restockSummary = active.statusLine();
            active = resumed;
            finish(client, BehaviorStatus.FAILED, "restock failed — " + restockSummary);
            return;
        }

        // The loop guard. Resuming into the same shortfall would suspend again
        // on the very next tick, and the bot would shuttle to the chest forever
        // — the failure mode is a bot that looks busy and never mines a block.
        String stillShort = resumed.restockNeeds().shortfall(client.player);
        if (stillShort != null) {
            active = resumed;
            finish(client, BehaviorStatus.FAILED,
                    "restocked but still " + stillShort + " — nothing left in storage?");
            return;
        }

        LOGGER.info("Resuming {} after restock", resumed.id());
        enter(client, resumed);
    }

    /** Make {@code behavior} the ticked one, with its own policy and a clean alarm. */
    private static void enter(Minecraft client, BotBehavior behavior) {
        active = behavior;
        activePolicy = behavior.policy();
        BotController.setPolicy(activePolicy);
        // Damage or a swing from before this run must not kill it on tick 1.
        BotAlarm.clear();
        behavior.start(client);
    }

    /**
     * Drop the suspended behavior without resuming it. It was already aborted
     * when it went into the lower slot, so there is nothing to stop — but the
     * slot has to be cleared or the next run starts with a stranger underneath.
     */
    private static void abandonSuspended() {
        if (suspended != null) {
            LOGGER.info("Dropping suspended behavior {}", suspended.id());
            suspended = null;
        }
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
        return null;
    }

    /**
     * Why a full inventory should end this run, or {@code null}. Separate from
     * {@link #stopReason} because it is not a safety question and must not be
     * asked in the same breath: see the call site.
     */
    private static String inventoryFullReason(LocalPlayer player) {
        if (player == null || !activePolicy.stopWhenInventoryFull()) {
            return null;
        }
        int free = InventoryHelper.freeSlots(player);
        return free < activePolicy.minFreeSlots()
                ? "inventory full (" + free + " empty slots left)"
                : null;
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
        abandonSuspended();
        activePolicy = BotPolicy.none();
        BotController.setPolicy(null);
    }
}

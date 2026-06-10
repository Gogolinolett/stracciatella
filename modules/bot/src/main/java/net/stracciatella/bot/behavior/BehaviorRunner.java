package net.stracciatella.bot.behavior;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static registry and executor for {@link BotBehavior}s — the same pattern as
 * the pathfinding module's Navigator for travel methods. Specialized modules
 * register their behaviors at init; at most one behavior runs at a time and
 * is ticked from the bot module's END_CLIENT_TICK hook (before the
 * BotController tick, so plans made this tick are executed this tick).
 */
public class BehaviorRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("BehaviorRunner");

    private static final Map<String, BotBehavior> registry = new LinkedHashMap<>();
    private static BotBehavior active = null;

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
            active = null;
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
        BehaviorStatus status = active.tick(client);
        if (status == BehaviorStatus.RUNNING) {
            return;
        }
        String message = "Behavior " + active.id()
                + (status == BehaviorStatus.SUCCEEDED ? " finished: " : " FAILED: ")
                + active.statusLine();
        LOGGER.info(message);
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(message), false);
        }
        active = null;
    }
}

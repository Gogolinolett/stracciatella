package net.stracciatella.bot.behavior;

import net.minecraft.client.Minecraft;

/**
 * A long-running, stateful strategy executed on top of the task layer.
 *
 * <p>Behaviors are the extension point for specialized modules (strip mining,
 * farming, building, ...): they decide <em>which</em> blocks to work on and in
 * what order, while {@link net.stracciatella.bot.BotController} remains the
 * execution engine that performs each individual interaction with human-like
 * camera movement, timing and collection. A behavior must never simulate
 * input itself — it plans by enqueuing {@link net.stracciatella.bot.task.BotTask}s
 * and waiting for the controller to go idle.
 *
 * <p>Implementations are registered once with {@link BehaviorRunner#register}
 * (typically from their module's init) and started by id. Only one behavior
 * is active at a time.
 */
public interface BotBehavior {

    /**
     * Unique id used to start this behavior (e.g. {@code "diamond_miner"}).
     */
    String id();

    /**
     * Called once when the behavior is started. Reset all internal state here.
     */
    void start(Minecraft client);

    /**
     * Called every client tick while this behavior is active. Plan the next
     * unit of work (or wait for the controller to finish the current one) and
     * report whether the behavior is still running, finished, or failed.
     */
    BehaviorStatus tick(Minecraft client);

    /**
     * Called when the behavior is stopped externally (user command or another
     * behavior starting). Must stop any work it delegated, typically via
     * {@code BotController.stop()}.
     */
    void abort();

    /**
     * One-line human-readable progress description for status commands.
     */
    String statusLine();
}

package net.stracciatella.bot.behavior;

import net.minecraft.client.Minecraft;
import net.stracciatella.bot.BotPolicy;

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
     * The safety and collection behaviour this strategy wants from the layers
     * below it. Read once by {@link BehaviorRunner#start} and held for the
     * whole run.
     *
     * <p>Deliberately abstract rather than defaulted to
     * {@link BotPolicy#none()}: whether a strategy should abort on damage or
     * keep digging is a decision its author has to make, and a silent default
     * would let a new behavior inherit "no safety at all" by omission. A
     * strategy that genuinely wants nothing says so in one line.
     */
    BotPolicy policy();

    /**
     * What this strategy needs in its inventory to keep working. A shortfall
     * against this manifest suspends the run, sends the bot to a storage block
     * to deposit and restock, and then starts it again.
     *
     * <p>Defaulted, unlike {@link #policy()}, because the two defaults point in
     * opposite directions. A silent policy default would hand a new behavior
     * "no safety at all"; the silent default here is "never leaves its work
     * site", which is the conservative answer and the only correct one for most
     * strategies.
     *
     * <p><b>The contract for opting in:</b> a restock <em>suspends</em> the
     * behavior by calling {@link #abort()} and resumes it by calling
     * {@link #start(Minecraft)} again — there is no save/restore hook. So only a
     * strategy that can pick up where it left off <em>by reading the world</em>
     * may declare a manifest. The chunk miner can (it finds its next slab by
     * looking at what is already mined); the diamond miner cannot (its tunnel
     * position lives in fields that {@code start} resets), and it therefore
     * keeps the default.
     */
    default RestockNeeds restockNeeds() {
        return RestockNeeds.none();
    }

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

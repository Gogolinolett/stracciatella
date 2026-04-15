package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public interface TravelMethod {

    /** Unique identifier for logging/debug (e.g. "walk", "ender_pearl", "tp"). */
    String id();

    /**
     * Whether this method can currently be used to travel from {@code from} to {@code to}.
     * Checks preconditions such as inventory contents, permissions, mesh availability, etc.
     */
    boolean canUse(Minecraft client, BlockPos from, BlockPos to);

    /**
     * Estimated cost to travel from {@code from} to {@code to}. Lower is better.
     * Cost should roughly reflect time-in-ticks so methods are comparable.
     * Return {@link Double#MAX_VALUE} if unusable.
     */
    double cost(Minecraft client, BlockPos from, BlockPos to);

    /**
     * Begin traveling. Called once per leg. Non-blocking — actual work happens in {@link #tick}.
     */
    void start(Minecraft client, BlockPos from, BlockPos to);

    /**
     * Called every client tick while this method is the active leg.
     * Returns the current status of the travel.
     */
    TravelStatus tick(Minecraft client);

    /** Abort travel. Release keys, clean up state. */
    void abort();
}

package net.stracciatella.bot.task;

import java.util.ArrayDeque;

import net.minecraft.core.BlockPos;

/**
 * Queue of bot tasks. Tasks can be taken in FIFO order ({@link #poll()}) or
 * by proximity to the player's current position ({@link #pollNearest}) — the
 * latter is what the bot uses so it works targets the way a human routes
 * through an area (always the closest one from where it stands) instead of
 * replaying a fixed scan order.
 */
public class TaskQueue {

    private final ArrayDeque<BotTask> queue = new ArrayDeque<>();

    public void addLast(BotTask task) {
        queue.addLast(task);
    }

    public void addFirst(BotTask task) {
        queue.addFirst(task);
    }

    public BotTask poll() {
        return queue.poll();
    }

    public BotTask peek() {
        return queue.peek();
    }

    /**
     * Returns (without removing) the task whose current target is nearest to
     * {@code from}. Ties keep insertion order. Null when empty.
     */
    public BotTask peekNearest(BlockPos from) {
        BotTask best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BotTask task : queue) {
            double distSq = task.targetPos().distSqr(from);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = task;
            }
        }
        return best;
    }

    /**
     * Removes and returns the task whose current target is nearest to
     * {@code from}. Ties keep insertion order. Null when empty.
     */
    public BotTask pollNearest(BlockPos from) {
        BotTask best = peekNearest(from);
        if (best != null) {
            queue.remove(best);
        }
        return best;
    }

    public void clear() {
        queue.clear();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}

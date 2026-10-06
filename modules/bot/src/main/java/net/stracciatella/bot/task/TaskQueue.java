package net.stracciatella.bot.task;

import java.util.ArrayDeque;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Queue of bot tasks. Tasks can be taken in FIFO order ({@link #poll()}) or
 * by proximity to the player's current position ({@link #pollNearest}) — the
 * latter is what the bot uses so it works targets the way a human routes
 * through an area (always the closest one from where it stands) instead of
 * replaying a fixed scan order.
 */
public class TaskQueue {

    /**
     * What turning round costs, in blocks of distance: a block straight behind
     * counts this much further away than the same block straight ahead, one to
     * the side half as much. A person takes the block in front of them before
     * an equally near one they would have to turn round for.
     */
    private static final double TURN_COST_BLOCKS = 3.0;

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
     * the block {@code from}, a target away from the {@code view} direction
     * seen from {@code eye} counting further ({@link #TURN_COST_BLOCKS}). Ties
     * keep insertion order. Null when empty.
     */
    public BotTask peekNearest(BlockPos from, Vec3 eye, Vec3 view) {
        BotTask best = null;
        double bestCost = Double.MAX_VALUE;
        for (BotTask task : queue) {
            BlockPos target = task.targetPos();
            Vec3 toTarget = Vec3.atCenterOf(target).subtract(eye).normalize();
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, toTarget.dot(view)))) / Math.PI;
            double cost = Math.sqrt(target.distSqr(from)) + TURN_COST_BLOCKS * turn;
            if (cost < bestCost) {
                bestCost = cost;
                best = task;
            }
        }
        return best;
    }

    /** {@link #peekNearest}, and take it. */
    public BotTask pollNearest(BlockPos from, Vec3 eye, Vec3 view) {
        BotTask best = peekNearest(from, eye, view);
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

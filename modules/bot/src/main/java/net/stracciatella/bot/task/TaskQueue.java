package net.stracciatella.bot.task;

import java.util.ArrayDeque;

/**
 * FIFO queue of bot tasks. Tasks are processed in order.
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

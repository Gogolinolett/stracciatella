package net.stracciatella.bot.task;

public record TaskResult(boolean success, String reason, int ticksElapsed) {

    public static TaskResult success(int ticksElapsed) {
        return new TaskResult(true, "completed", ticksElapsed);
    }

    public static TaskResult failure(String reason, int ticksElapsed) {
        return new TaskResult(false, reason, ticksElapsed);
    }
}

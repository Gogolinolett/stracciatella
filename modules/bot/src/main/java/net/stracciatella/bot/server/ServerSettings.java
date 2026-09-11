package net.stracciatella.bot.server;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot is allowed to do on one particular server, and where its
 * storages are.
 *
 * <p>Per server rather than global because every field here is a fact about the
 * server, not a preference: whether {@code /t spawn} exists, whether reshaping
 * terrain is acceptable, and which chests belong to the player. A global
 * setting for any of them would be wrong the first time the player joined a
 * second server.
 *
 * <p><b>Every default is the tame one</b>, and they are tame together: no
 * storages, no commands, a staircase rather than a teleport, and no permission
 * to place blocks. Joining an unknown server therefore gets a bot that neither
 * types into a stranger's chat nor reshapes their terrain, and one that cannot
 * start a restock at all until somebody points it at a chest. The opposite
 * arrangement — useful defaults, with the player expected to lock them down —
 * fails in the direction that gets someone banned.
 */
public class ServerSettings {

    /** How the bot gets out of a pit it has dug around itself. */
    public enum ExitStrategy {
        /**
         * Build a staircase out of filler blocks. Works everywhere, costs
         * blocks and time, and is the default for exactly that reason.
         */
        STAIRCASE,
        /**
         * Come to a stop and send {@link ServerSettings#exitCommands} from
         * there — the {@code /t spawn} arrangement many servers use, where the
         * server counts its own seconds from the command and movement cancels
         * the teleport. Arrival is detected by the position jumping, not by a
         * timer; {@code teleportWaitTicks} only bounds the believing.
         */
        COMMAND
    }

    public ExitStrategy exitStrategy = ExitStrategy.STAIRCASE;

    /**
     * Commands sent (without the leading slash) as soon as the bot has come to
     * a stop, when {@link #exitStrategy} is {@link ExitStrategy#COMMAND}.
     * Empty by default: a bot that types into the chat of a server nobody
     * configured is a bot that gets its owner in trouble.
     *
     * <p>A list, because a server may want a sequence, but the in-game command
     * ({@code /bot server exit command <command>}) sets exactly one and replaces
     * whatever was here — the second command of a pair would arrive after the
     * first had already teleported the bot away, so a sequence is something to
     * write into the file deliberately rather than to accumulate by typing.
     */
    public List<String> exitCommands = new ArrayList<>();

    /**
     * Whether the pathfinder may place blocks to open a route — bridging a gap
     * it cannot walk around. Off by default; see the class comment.
     */
    public boolean allowPathPlacement = false;

    /** Chests, trapped chests and barrels the bot may use. */
    public List<StorageSite> storages = new ArrayList<>();

    /**
     * Storages in the given dimension, in file order.
     *
     * <p>File order carries no preference — a restock picks the nearest of these,
     * the same rule {@code TaskQueue.pollNearest} applies to blocks and for the
     * same reason: a person walks to the chest they are standing closest to. The
     * order is just the order they were added in, so the file reads like a log.
     */
    public List<StorageSite> storagesIn(String dimension) {
        List<StorageSite> matching = new ArrayList<>();
        for (StorageSite site : storages) {
            if (site.isIn(dimension)) {
                matching.add(site);
            }
        }
        return matching;
    }
}

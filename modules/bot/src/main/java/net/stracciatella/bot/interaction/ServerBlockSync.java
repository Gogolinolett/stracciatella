package net.stracciatella.bot.interaction;

import net.minecraft.client.multiplayer.ClientLevel;
import net.stracciatella.bot.mixin.ClientLevelAccessor;

/**
 * Tracks which of the client's block predictions the server has settled.
 *
 * <p>Breaking a block is predicted client-side: the block turns to air
 * immediately, before the server has said anything. The server replies with a
 * {@code ClientboundBlockChangedAckPacket} carrying the sequence number of the
 * prediction, and the client then retires every prediction up to it — reverting
 * any block whose real state differs. So once the acknowledgement for sequence
 * <i>s</i> has arrived, the client's view of anything predicted at <i>s</i> or
 * earlier <b>is</b> the server's view, with no guesswork left.
 *
 * <p>That is what the bot needs to stop waiting. The alternative it used before
 * — hold the block in its finished state for a fixed window and look for a
 * dropped item — was an approximation of exactly this fact, and it cost eight
 * ticks on every single block, roughly a third of the whole mining loop. The
 * drop was never proof either: in a corridor the item from the previous column
 * is still lying within the search box.
 */
public final class ServerBlockSync {

    private static int lastAckedSequence = -1;

    private ServerBlockSync() {
    }

    /** Called from the packet mixin once the client has applied the ack. */
    public static void onAck(int sequence) {
        if (sequence > lastAckedSequence) {
            lastAckedSequence = sequence;
        }
    }

    /**
     * The sequence the next prediction would settle under. Sampled the moment
     * the target is first seen in its finished state, which is at or after the
     * prediction that put it there.
     */
    public static int currentSequence(ClientLevel level) {
        return ((ClientLevelAccessor) level).stracciatella$predictionHandler().currentSequence();
    }

    /** Whether the server has settled everything up to {@code sequence}. */
    public static boolean isSettled(int sequence) {
        return sequence >= 0 && lastAckedSequence >= sequence;
    }

    /**
     * Forgets past acknowledgements. Sequence numbers restart with a new
     * connection, so a stale high-water mark would report a fresh prediction as
     * already settled.
     */
    public static void reset() {
        lastAckedSequence = -1;
    }
}

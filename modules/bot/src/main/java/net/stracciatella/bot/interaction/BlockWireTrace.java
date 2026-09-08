package net.stracciatella.bot.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEMPORARY (multiplayer break investigation, remove with the fix).
 *
 * <p>Every diagnostic so far has been a summary of what the bot <i>believed</i>
 * — how long it waited, how often the block came back. It cannot say why,
 * because the answer is on the wire: which actions the client sends, in which
 * order, with which sequence number, and what the server sends back. This logs
 * exactly that, for the block currently under the pick and nothing else.
 *
 * <p>What the lines mean, read against the 1.21.11 server code
 * ({@code ServerPlayerGameMode.handleBlockBreakAction}):
 * <ul>
 *   <li>{@code out START} sets the server's {@code destroyProgressStart} to the
 *       tick it arrives. A second START for the same block resets that clock
 *       <b>and</b> makes the server send the block's real state back — so two
 *       STARTs per break is itself a defect.</li>
 *   <li>{@code out STOP} is accepted only if
 *       {@code getDestroyProgress * (elapsed + 1) >= 0.7}. Refused, it arms a
 *       delayed destroy that the server's own tick finishes a few ticks later,
 *       so a single refusal is harmless — a break that never lands means the
 *       destroy itself is being cancelled.</li>
 *   <li>{@code in UPDATE} is the server putting the block back. That is its
 *       only way of saying no.</li>
 *   <li>{@code in ACK} retires the prediction: afterwards the client's state
 *       for that block <i>is</i> the server's.</li>
 * </ul>
 */
public final class BlockWireTrace {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlockWire");

    private BlockWireTrace() {
    }

    /** Client game time, so the four line kinds can be ordered against each other. */
    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? -1L : mc.level.getGameTime();
    }

    /**
     * An outgoing block action, logged for every position while the bot is
     * mining — not just the target. An action for a <i>different</i> block is
     * the interesting case: the server keeps one {@code destroyPos}, so a
     * START for anything else silently invalidates the STOP that follows for
     * the target. Filtering those out would hide the defect being looked for,
     * so they are logged and marked {@code other}.
     */
    public static void onAction(ServerboundPlayerActionPacket packet) {
        BlockPos target = BlockInteractor.currentTarget();
        if (target == null) {
            return;
        }
        LOGGER.warn("t={} out {} {} seq={} face={}{}", now(), packet.getAction(),
                packet.getPos().toShortString(), packet.getSequence(), packet.getDirection(),
                target.equals(packet.getPos()) ? "" : " other(target=" + target.toShortString() + ")");
    }

    /**
     * Every outgoing packet while the bot is mining, by type. The question it
     * answers is the one that cannot be settled by reading our own code: what
     * does this client actually put on the wire that a player holding the
     * mouse button would not? Counting types is enough — the ones that differ
     * differ by existing at all.
     */
    public static void onOutgoing(Object packet) {
        String type = packet.getClass().getSimpleName();
        // The two types the comparison turns on are logged unconditionally.
        // Gating them on "the bot is mining" hid the carried-item packet
        // entirely: it went out before startInteraction had run, so the
        // inventory it was supposed to appear in came back clean.
        boolean always = type.equals("ServerboundSetCarriedItemPacket")
                || type.equals("ServerboundPlayerActionPacket");
        if (!always && BlockInteractor.currentTarget() == null) {
            return;
        }
        LOGGER.warn("PKT {}", type);
    }

    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        BlockPos target = BlockInteractor.currentTarget();
        if (target == null || !target.equals(pos)) {
            return;
        }
        LOGGER.warn("t={} in UPDATE {} {}", now(), pos.toShortString(), state);
    }

    public static void onAck(int sequence) {
        BlockPos target = BlockInteractor.currentTarget();
        if (target == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Read at TAIL of the handler, so this is the state the ack settled on.
        String state = mc.level == null ? "?" : mc.level.getBlockState(target).toString();
        LOGGER.warn("t={} in ACK seq={} {} -> {}", now(), sequence,
                target.toShortString(), state);
    }
}

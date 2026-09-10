package net.stracciatella.bot.interaction;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.bot.task.PlaceBlockTask;
import net.stracciatella.pathfinding.place.BlockPlacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot's answer to {@link BlockPlacer}: a block the pathfinder needs becomes
 * an ordinary {@link PlaceBlockTask} on the task layer.
 *
 * <p>That one line is the entire reason the interface exists in the other
 * module. Everything that makes a placement work against a real server already
 * lives in {@link BotController} and is reached for free here — the pinned
 * placement face, the crouch out past the rim when the support's side is hidden
 * under the bot's own feet, the inventory-shrink confirmation, the retry on a
 * dropped use packet. A placement written inside the pathfinding module would
 * have had to learn all of it again.
 *
 * <p>Busy-ness is tracked with the task itself rather than by asking the
 * controller whether it is doing <em>something</em>: the controller is shared,
 * and a behavior's own mining task would otherwise read as this placement still
 * being in flight.
 */
public class RoutePlacer implements BlockPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoutePlacer");

    private final BotConfig config;
    private PlaceBlockTask inFlight;

    public RoutePlacer(BotConfig config) {
        this.config = config;
    }

    @Override
    public boolean place(BlockPos pos, BlockPos support) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        // A paused bot starts nothing. Without this the task would sit in the
        // queue unstarted, isBusy() would report free, and every re-plan would
        // enqueue another copy of the same placement.
        if (player == null || client.level == null || BotController.isPaused() || isBusy()) {
            return false;
        }
        Predicate<ItemStack> routeBlock = InventoryHelper.blockIdMatcher(config.routeBlocks);
        if (InventoryHelper.countMatching(player, routeBlock) == 0) {
            // Nothing to build with. Reported as "cannot take it", which the
            // caller treats as a blocked route — the honest answer, and the one
            // that lets a restock be the fix rather than a stuck bot.
            return false;
        }
        PlaceBlockTask task;
        try {
            task = new PlaceBlockTask(pos, support, routeBlock, "route block");
        } catch (IllegalArgumentException notAdjacent) {
            // The support is prescribed by the caller, so a bad pair is a bug in
            // the caller, not a runtime condition. Refuse it loudly rather than
            // letting the exception unwind through a tick hook.
            LOGGER.warn("Refusing route placement: {}", notAdjacent.getMessage());
            return false;
        }
        inFlight = task;
        BotController.enqueueTask(task);
        return true;
    }

    @Override
    public boolean isBusy() {
        if (inFlight == null) {
            return false;
        }
        if (inFlight.isFullyComplete()) {
            inFlight = null;
            return false;
        }
        // Busy while the controller is working at all, not only while this task
        // is the one under the pick: a placement enqueued behind another task is
        // still in flight, and releasing the slot there would let the next
        // re-plan enqueue a second copy of it. An idle controller means the task
        // is gone — finished, failed or cleared — and the caller re-reads the
        // world in every one of those cases.
        if (!BotController.isActive()) {
            inFlight = null;
            return false;
        }
        return true;
    }
}

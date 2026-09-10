package net.stracciatella.bot.task;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Right-click a chest, trapped chest or barrel and wait for its screen.
 *
 * <p>A task rather than a few lines inside the restock, because the hard part of
 * clicking a block is the part the task layer already does: turning the head
 * toward it at a human rate, waiting until the crosshair really lands on
 * <em>that</em> block before clicking, walking a step closer when something is
 * in the way, and giving up with a diagnostic instead of hanging. A restock that
 * opened containers itself would either duplicate all of that or snap its gaze
 * and click through a wall.
 *
 * <p>Completion is the container menu being something other than the player's
 * own inventory. That is the client's own state, set when the server sends the
 * open packet, so it is the server's answer and not a prediction — a stronger
 * signal than this task could get from the block.
 */
public class OpenContainerTask implements BotTask {

    /**
     * Leaves the hand alone. Returning {@code null} here would make the
     * controller pick the fastest tool for a chest, i.e. put an axe in the
     * bot's hand before opening it — harmless but pointless, and it would also
     * run the missing-tool check against a block nobody is mining.
     */
    private static final Predicate<ItemStack> KEEP_WHATEVER_IS_HELD = stack -> false;

    private final BlockPos containerPos;
    private boolean complete = false;

    public OpenContainerTask(BlockPos containerPos) {
        this.containerPos = containerPos;
    }

    /** Whether a container other than the player's own inventory is open. */
    public static boolean isContainerOpen() {
        var player = Minecraft.getInstance().player;
        return player != null && !(player.containerMenu instanceof InventoryMenu);
    }

    @Override
    public BlockPos targetPos() {
        return containerPos;
    }

    @Override
    public String description() {
        return "Open container at " + containerPos.getX() + ", " + containerPos.getY() + ", "
                + containerPos.getZ();
    }

    @Override
    public InteractionType interactionType() {
        return InteractionType.USE;
    }

    @Override
    public boolean isCurrentTargetComplete(Level level) {
        if (isContainerOpen()) {
            complete = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean advanceToNextTarget() {
        return false;
    }

    @Override
    public boolean isFullyComplete() {
        return complete;
    }

    @Override
    public int maxInteractionTicks() {
        return 200;
    }

    @Override
    public Predicate<ItemStack> requiredItem() {
        return KEEP_WHATEVER_IS_HELD;
    }

    @Override
    public boolean consumesItem() {
        return false;
    }
}

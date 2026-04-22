package net.stracciatella.bot.interaction;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the player's hotbar and selects the best tool for a given block.
 */
public class InventoryHelper {

    /**
     * Select the hotbar slot with the fastest tool for the given block.
     * Returns the slot index that was selected, or -1 if bare hand is best.
     * <p>
     * {@link Inventory#setSelectedSlot} only updates client state — the server
     * needs {@link ServerboundSetCarriedItemPacket} to know which slot is held,
     * otherwise break/use actions are resolved against a stale server-side slot
     * and drops may be computed with the wrong tool (e.g. iron_ore mined with
     * server-side bare hand drops nothing).
     */
    public static int selectBestTool(LocalPlayer player, BlockState targetBlock) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f; // bare hand baseline

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(targetBlock);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            inv.setSelectedSlot(bestSlot);
            // Always send — even if client-side selectedSlot matches bestSlot,
            // the server's copy may disagree under accelerated ticks or after
            // a prior test's state bled through. The packet is small and
            // idempotent.
            player.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
        }
        return bestSlot;
    }

    /**
     * Re-send the ServerboundSetCarriedItemPacket for the player's current
     * client-side selected slot. Used during the tool-settle window so a
     * dropped or reordered packet doesn't leave the server on a stale held
     * slot when the first attack arrives.
     */
    public static void resendCarriedItem(LocalPlayer player) {
        int slot = player.getInventory().getSelectedSlot();
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }
}

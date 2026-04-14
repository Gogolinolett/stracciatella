package net.stracciatella.bot.interaction;

import net.minecraft.client.player.LocalPlayer;
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
        }
        return bestSlot;
    }
}

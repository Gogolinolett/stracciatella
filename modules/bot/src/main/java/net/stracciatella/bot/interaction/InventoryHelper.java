package net.stracciatella.bot.interaction;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the player's main inventory and selects the best tool for a given
 * block, swapping a non-hotbar tool into the hotbar if needed.
 */
public class InventoryHelper {

    /**
     * Select the slot with the fastest tool for the given block. Searches the
     * full 36 main-inventory slots (hotbar + storage rows); if the best tool
     * sits in storage it is swapped into the currently selected hotbar slot
     * via a {@link ClickType#SWAP} container-click packet. Returns the hotbar
     * slot index now holding the tool, or -1 if bare hand is best.
     * <p>
     * Why search beyond the hotbar: human players carry tools in storage and
     * shuffle them into the hotbar when needed. A hotbar-only search misses a
     * pickaxe sitting in row 2 and silently falls back to bare hand — the
     * server then drops nothing when mining ore.
     * <p>
     * {@link Inventory#setSelectedSlot} only updates client state — the server
     * needs {@link ServerboundSetCarriedItemPacket} to know which slot is held,
     * otherwise break/use actions are resolved against a stale server-side slot
     * and drops may be computed with the wrong tool.
     */
    public static int selectBestTool(LocalPlayer player, BlockState targetBlock) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f; // bare hand baseline

        // Slots 0–8 = hotbar, 9–35 = storage rows. Armor (36–39) and offhand
        // (40) are deliberately skipped — they aren't tool slots in any
        // useful sense.
        for (int i = 0; i < 36; i++) {
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

        return equip(player, bestSlot);
    }

    /**
     * Select the slot holding the largest stack matching {@code matcher},
     * swapping it into the hotbar the same way {@link #selectBestTool} does.
     * Returns the hotbar slot now holding it, or -1 if nothing matches.
     * <p>
     * Used by USE tasks, which need a specific item in hand rather than the
     * fastest tool for a block. Largest stack wins so the bot keeps using the
     * pile it is carrying instead of burning through singles.
     */
    public static int selectItem(LocalPlayer player, Predicate<ItemStack> matcher) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        int bestCount = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlot = i;
            }
        }

        return equip(player, bestSlot);
    }

    /**
     * Number of completely empty slots in the 36 main-inventory slots.
     * <p>
     * Empty slots, not free space: a mining run fills partial stacks constantly,
     * so counting remaining stack capacity would report "room left" right up to
     * the moment a new block type drops and has nowhere to go. Empty slots are
     * what actually decides whether the next unknown drop fits.
     */
    public static int freeSlots(LocalPlayer player) {
        Inventory inv = player.getInventory();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * Bring the item in {@code bestSlot} into the player's hand and tell the
     * server about it. Returns the resulting hotbar slot, or -1 when
     * {@code bestSlot} is negative (nothing to equip).
     */
    private static int equip(LocalPlayer player, int bestSlot) {
        if (bestSlot < 0) {
            return -1;
        }

        Inventory inv = player.getInventory();
        int hotbarSlot;
        if (bestSlot < 9) {
            hotbarSlot = bestSlot;
        } else {
            // Item is in storage — swap it into the currently selected hotbar
            // slot via a container-click packet. `bestSlot` doubles as the
            // menu slot index in InventoryMenu (storage rows map 1:1 to menu
            // slots 9–35). The hotbar key `button` for SWAP is the
            // destination hotbar index (0–8).
            hotbarSlot = inv.getSelectedSlot();
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode != null) {
                mc.gameMode.handleInventoryMouseClick(
                        player.inventoryMenu.containerId,
                        bestSlot,
                        hotbarSlot,
                        ClickType.SWAP,
                        player);
            }
        }

        inv.setSelectedSlot(hotbarSlot);
        // Always send — even if client-side selectedSlot matches hotbarSlot,
        // the server's copy may disagree under accelerated ticks or after a
        // prior state-bleed. The packet is small and idempotent.
        player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
        return hotbarSlot;
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

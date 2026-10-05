package net.stracciatella.bot.behavior;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.stracciatella.bot.interaction.InventoryHelper;

/**
 * Decides, one stack at a time, what moves between the bot's inventory and an
 * open container — and clicks it the way a player would.
 *
 * <p><b>Whole stacks, one per call.</b> Every move is a shift-click
 * ({@link ClickType#QUICK_MOVE}), which is what a person actually does at a
 * chest; the caller spaces them out with a small random delay. Moving exact
 * counts would need pick-up/place-half/put-down sequences that no player
 * performs thirty times in a row, and the precision buys nothing: a restock that
 * comes back with 64 cobblestone instead of 37 is not a worse restock.
 *
 * <p><b>One manifest, both directions.</b> {@link RestockNeeds} says what the
 * suspended behavior needs; this class reads it twice over. Anything the
 * manifest does not want is loot and goes into the chest, and so does the
 * <em>excess</em> of anything it does want — a miner whose manifest asks for 64
 * cobblestone is carrying the other 300 as loot, and a rule that simply kept
 * everything matching the manifest would come away from the chest with an
 * inventory just as full as it arrived. Anything the manifest is short of is
 * taken out.
 */
public final class ContainerTransfer {

    private ContainerTransfer() {
    }

    /**
     * The menu slot holding the next thing to put in the chest, or -1 when
     * nothing in the inventory is loot that the chest still has room for.
     *
     * <p>Room is asked before the click, the way a person sees a full chest and
     * shuts it. Clicking to find out cost a stalled click per tick until a
     * timeout ran out: six seconds and sixty shift-clicks into every full
     * barrel of a camp.
     */
    public static int nextDeposit(LocalPlayer player, RestockNeeds needs) {
        AbstractContainerMenu menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isCarrySlot(player, slot) || !slot.hasItem()) {
                continue;
            }
            // The bot's own hotbar is fair game: a stack of loot is loot
            // wherever it sits, and the manifest protects the tools by name.
            if (isLoot(player, needs, slot.getItem())
                    && hasRoom(menu, slot.getItem(), s -> isContainerSlot(player, s))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether the inventory holds loot at all, room or no room — what decides
     * whether a full chest is the end of the trip or only of this stop.
     */
    public static boolean hasLoot(LocalPlayer player, RestockNeeds needs) {
        for (Slot slot : player.containerMenu.slots) {
            if (isCarrySlot(player, slot) && slot.hasItem() && isLoot(player, needs, slot.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The menu slot holding the next thing to take out, or -1 when the manifest
     * is satisfied, the chest has nothing that would help, or the bot has no
     * room for it.
     */
    public static int nextWithdraw(LocalPlayer player, RestockNeeds needs) {
        AbstractContainerMenu menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isContainerSlot(player, slot) || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            for (RestockNeeds.Need need : needs.needs()) {
                if (need.matcher().test(stack) && RestockNeeds.shortfallOf(player, need) > 0
                        && hasRoom(menu, stack, s -> isCarrySlot(player, s))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Whether a shift-click of {@code stack} would find somewhere to go among
     * the slots {@code side} accepts: an empty slot that takes it, or a stack of
     * the same item with room left — the two places vanilla's quick move tries.
     */
    private static boolean hasRoom(AbstractContainerMenu menu, ItemStack stack,
                                   java.util.function.Predicate<Slot> side) {
        for (Slot slot : menu.slots) {
            if (!side.test(slot) || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack there = slot.getItem();
            if (there.isEmpty()
                    || (ItemStack.isSameItemSameComponents(there, stack)
                            && there.getCount() < Math.min(there.getMaxStackSize(), slot.getMaxStackSize(there)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code stack} should go into the chest.
     *
     * <p>Excess counts as loot, but only when <em>every</em> need that matches
     * the stack would still be satisfied without it. A stack that two needs both
     * claim — filler blocks and a building material, say — is kept as long as
     * either still wants it; the alternative is depositing something the run is
     * about to walk back for.
     */
    private static boolean isLoot(LocalPlayer player, RestockNeeds needs, ItemStack stack) {
        for (RestockNeeds.Need need : needs.needs()) {
            if (!need.matcher().test(stack)) {
                continue;
            }
            int held = InventoryHelper.countMatching(player, need.matcher());
            if (held - stack.getCount() < need.target()) {
                return false;
            }
        }
        // Reaching the end means either nothing claimed the stack, or everything
        // that did is still satisfied without it. Both are loot.
        return true;
    }

    /** Shift-click one menu slot, the way a player empties a stack across. */
    public static void quickMove(LocalPlayer player, int menuSlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null) {
            return;
        }
        client.gameMode.handleInventoryMouseClick(
                player.containerMenu.containerId, menuSlot, 0, ClickType.QUICK_MOVE, player);
    }

    /**
     * Whether this menu slot is one of the bot's own 36 carrying slots.
     *
     * <p>The index bound is not decoration. Armour, the offhand and the saddle
     * slot are part of the same {@code Inventory} container at indices 36 and
     * up, so the container test alone would call a chestplate a candidate for
     * deposit — and then shift-click it into the chest, because no manifest
     * mentions armour. The same 0–35 bound every other inventory query in this
     * module uses.
     *
     * <p>It has to be {@link Slot#getContainerSlot()}, not the public
     * {@code Slot.index}: that one is the slot's position in the <em>menu</em>,
     * assigned by {@code addSlot}, and for a three-row chest the player's own
     * slots sit at menu positions 27–62. Measured against it, the bound accepted
     * nine of the thirty-six — the first storage row — so a bot whose loot was in
     * the hotbar deposited nothing at all and came home with the chest's contents
     * on top of a full inventory.
     */
    private static boolean isCarrySlot(LocalPlayer player, Slot slot) {
        int inventorySlot = slot.getContainerSlot();
        return slot.container == player.getInventory()
                && inventorySlot >= 0 && inventorySlot < 36;
    }

    /**
     * Whether this menu slot belongs to the chest rather than to the player.
     *
     * <p>Deliberately <em>not</em> the negation of {@link #isCarrySlot}: that one
     * excludes the equipment slots, and treating "not a carry slot" as "part of
     * the chest" would have the bot shift-clicking its own boots out of its feet
     * as if they were stock.
     */
    private static boolean isContainerSlot(LocalPlayer player, Slot slot) {
        return slot.container != player.getInventory();
    }
}

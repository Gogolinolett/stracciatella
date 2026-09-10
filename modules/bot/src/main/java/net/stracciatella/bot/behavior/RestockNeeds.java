package net.stracciatella.bot.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.stracciatella.bot.interaction.InventoryHelper;

/**
 * What a behavior needs in its inventory to keep working — declared the way
 * {@link net.stracciatella.bot.BotPolicy} is, once per behavior, and read by
 * {@link BehaviorRunner} every tick.
 *
 * <p>One list does two jobs, and that is the point of the type. It is the
 * <em>trigger</em>: a shortfall against it is a restock reason, phrased for the
 * supervising player. It is also the <em>shopping list</em> at the chest — the
 * restock brings every entry back up to its target and nothing else. A separate
 * config list for "what to fetch" would be a second place to edit and would
 * drift from the first one the moment somebody changed only one of them.
 *
 * <p>The deposit rule falls out of the same list by complement: anything that is
 * not needed here is loot and goes in the chest, and so does the <em>excess</em>
 * of anything that is — see {@link ContainerTransfer#nextDeposit}, which is the
 * only place that rule lives. A hand-written deposit list was the alternative
 * and is worse — every ore nobody thought of while writing it stays in the
 * inventory, and the bot is full again two slabs later.
 */
public record RestockNeeds(List<Need> needs, int minFreeSlots) {

    /**
     * One line of the manifest.
     *
     * @param label   how this reads in a chat message, e.g. "a pickaxe"
     * @param matcher which stacks count towards it
     * @param target  how many items of it the bot wants to carry; a tool is 1
     */
    public record Need(String label, Predicate<ItemStack> matcher, int target) {
    }

    private static final RestockNeeds NONE = new RestockNeeds(List.of(), 0);

    /**
     * Needs nothing and never triggers a restock — the default for any behavior
     * that has not opted in, and the only correct answer for one that cannot be
     * resumed from the world after a trip.
     */
    public static RestockNeeds none() {
        return NONE;
    }

    public boolean isEmpty() {
        return needs.isEmpty() && minFreeSlots <= 0;
    }

    /**
     * Why this manifest is not satisfied right now, or {@code null} when it is.
     *
     * <p>Free slots are checked first on purpose: a full inventory is also the
     * reason a withdrawal could not happen, so reporting a missing pickaxe while
     * the real problem is that there is nowhere to put one would send the player
     * looking in the wrong place.
     */
    public String shortfall(LocalPlayer player) {
        if (minFreeSlots > 0) {
            int free = InventoryHelper.freeSlots(player);
            if (free < minFreeSlots) {
                return "inventory full (" + free + " empty slots left)";
            }
        }
        for (Need need : needs) {
            int held = InventoryHelper.countMatching(player, need.matcher());
            if (held < need.target()) {
                return "out of " + need.label()
                        + (need.target() > 1 ? " (" + held + " of " + need.target() + ")" : "");
            }
        }
        return null;
    }

    /**
     * How many more items of {@code need} the bot wants. Zero when it already
     * carries enough — the restock uses this to decide what to take out of a
     * chest, so it must never be negative.
     */
    public static int shortfallOf(LocalPlayer player, Need need) {
        return Math.max(0, need.target() - InventoryHelper.countMatching(player, need.matcher()));
    }

    /** Builder-ish helper so a behavior can declare its manifest in one expression. */
    public static class Builder {
        private final List<Need> needs = new ArrayList<>();
        private int minFreeSlots;

        public Builder need(String label, Predicate<ItemStack> matcher, int target) {
            needs.add(new Need(label, matcher, target));
            return this;
        }

        public Builder minFreeSlots(int slots) {
            this.minFreeSlots = slots;
            return this;
        }

        public RestockNeeds build() {
            return new RestockNeeds(List.copyOf(needs), minFreeSlots);
        }
    }
}

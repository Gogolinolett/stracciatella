package net.stracciatella.pathfinding.place;

import net.minecraft.core.BlockPos;

/**
 * Places a single block on behalf of the pathfinder.
 *
 * <p>Declared here and implemented elsewhere on purpose. Making a route
 * passable means putting a block somewhere, and doing that in a way that
 * survives contact with a real server is not a one-liner: the support face has
 * to be fixed rather than ranked, the bot has to be crouched past the rim
 * before it clicks, and the placement has to be verified against the world
 * afterwards. All of that lives in the bot module's task layer, where it was
 * won over five rounds of corrections.
 *
 * <p>The pathfinding module must not grow a second copy of it. It also cannot
 * call into the bot module — {@code bot} already depends on {@code pathfinding},
 * so the reverse would be a cycle. So the dependency is inverted: this module
 * says <em>where</em> a block is needed, and whoever knows <em>how</em> to place
 * one like a person registers itself with {@link PathPlacement}.
 *
 * <p>With nobody registered the capability is simply absent and a route that
 * would need a block counts as impassable. That is deliberate: no flag
 * describing a half-present ability, and no dead branch pretending to place.
 */
public interface BlockPlacer {

    /**
     * Ask for a block in {@code pos}, placed against {@code support}.
     *
     * <p>The support is <em>prescribed, not searched</em>. Over a gap, ranking
     * candidate faces by how squarely they face the eye picks the far rim and
     * walks the bot around — into the hole it was supposed to bridge. The only
     * support a bridge is ever built from is the block under the bot's own feet
     * or the one it just laid, and that is the caller's knowledge, not the
     * placer's.
     *
     * @return true when the placement was accepted and is now in flight; false
     *         when the placer cannot take it (no block to place, busy, no
     *         player). A false is not an error — the caller treats the route as
     *         blocked, the same as if no placer were registered at all.
     */
    boolean place(BlockPos pos, BlockPos support);

    /**
     * Whether a previously accepted placement is still being carried out.
     * Callers poll this instead of being called back, the same way the bot's
     * behaviors poll the task layer for idleness.
     */
    boolean isBusy();
}

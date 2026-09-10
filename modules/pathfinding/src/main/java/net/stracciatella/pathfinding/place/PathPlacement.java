package net.stracciatella.pathfinding.place;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static hand-off point for the one {@link BlockPlacer} a session may have —
 * the same shape as {@code Navigator.register} for travel methods and
 * {@code BehaviorRunner.register} for behaviors: a module contributes to
 * another without the dependency turning around.
 *
 * <p>Placement is additionally gated by {@link #setAllowed(boolean)}, which the
 * bot module drives from its per-server settings. The gate defaults to
 * <em>off</em>: reshaping terrain on a server the player has not configured is
 * not something a pathfinder should start doing on its own. Note this governs
 * the <em>pathfinder</em> placing blocks to open a route, and nothing else — the
 * chunk miner's own placements (damming water, bridging a gap in its slab) are
 * a different permission and do not pass through here.
 */
public final class PathPlacement {

    private static final Logger LOGGER = LoggerFactory.getLogger("PathPlacement");

    private static BlockPlacer placer;
    private static boolean allowed;

    private PathPlacement() {
    }

    /** Install the implementation. The last registration wins. */
    public static void register(BlockPlacer implementation) {
        placer = implementation;
        LOGGER.info("Block placer registered: {}", implementation.getClass().getSimpleName());
    }

    /**
     * Turn route placement on or off for the server the player is on. Switching
     * it off does not cancel a placement already in flight; it only stops new
     * ones from being asked for, which is all a permission needs to do.
     */
    public static void setAllowed(boolean value) {
        allowed = value;
    }

    /**
     * Whether asking for a block could do anything at all — a placer exists and
     * the current server permits it. Callers check this <em>before</em> planning
     * a route that depends on placement, so a forbidden placement shows up as
     * "no path" rather than as a half-built bridge.
     */
    public static boolean isAvailable() {
        return allowed && placer != null;
    }

    /** @see BlockPlacer#place(BlockPos, BlockPos) */
    public static boolean place(BlockPos pos, BlockPos support) {
        if (!isAvailable()) {
            return false;
        }
        return placer.place(pos, support);
    }

    /** @see BlockPlacer#isBusy() */
    public static boolean isBusy() {
        return placer != null && placer.isBusy();
    }
}

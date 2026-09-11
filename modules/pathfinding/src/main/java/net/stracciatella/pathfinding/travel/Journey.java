package net.stracciatella.pathfinding.travel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.display.PathDisplay;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.MeshPathfinder;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import net.stracciatella.pathfinding.place.PathPlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Travel to an arbitrary position, however far away and whether or not its
 * chunk has been sent to the client yet, by walking it in legs and re-planning
 * after each one.
 *
 * <p>Why legs rather than one path: a route to an unloaded chunk <em>cannot</em>
 * be planned. The client holds no block data out there, so the mesh has no nodes
 * at all — it is not a matter of search effort. The only way to learn that
 * terrain is to walk towards it and let the chunks stream in. So each leg aims
 * at the best node the mesh currently has in the target's direction
 * ({@link MeshPathfinder#findPathTowards}), walks it, meshes whatever arrived on
 * the way, and asks again.
 *
 * <p>Two things fall out of that for free, both of which the module was missing.
 * <b>Re-planning:</b> {@link PathWalker} keeps the node list it was handed no
 * matter what happens to the world — the chunk mixin dutifully invalidates the
 * mesh when a block changes, but nobody ever planned again. Every leg does.
 * <b>A verdict:</b> {@code PathWalker.isActive()} going false means the walk
 * ended and says nothing about whether it worked; every caller had to measure
 * the distance itself. This class reports {@link Status}.
 *
 * <p>Static, like {@code PathWalker} and {@code Navigator} — one journey at a
 * time, and the walker it drives is a single global anyway.
 */
public final class Journey {

    private static final Logger LOGGER = LoggerFactory.getLogger("Journey");

    /** Result of a journey, as a verdict rather than "the walker stopped". */
    public enum Status {
        RUNNING,
        ARRIVED,
        FAILED
    }

    private enum Phase {
        /** Working through the chunk queue under the per-tick budget. */
        MESHING,
        /** One tick: run A*, hand the path to the walker. */
        PLANNING,
        /** The walker has the legs; poll it. */
        WALKING,
        /** A block was asked for; wait for it before planning again. */
        PLACING
    }

    /** Close enough to count as arrived — the same 2 blocks WalkTravelMethod used. */
    private static final double ARRIVAL_RADIUS_SQ = 4.0;
    /**
     * Chunk meshes built per tick. A mesh is tens of thousands of block reads
     * even with a band, so a long route must not be paid for in one frame: the
     * bot stands a moment longer and the client never stutters.
     */
    private static final int CHUNKS_PER_TICK = 2;
    /** Band around the traveller's height that a route actually uses. */
    private static final int BAND_ABOVE = 16;
    private static final int BAND_BELOW = 32;
    /**
     * Chunks kept meshed around the player. Doubles as the window the corridor
     * is clipped to: further out nothing is loaded anyway, so enumerating it
     * would only cost hasChunk calls.
     */
    private static final int KEEP_RADIUS = 8;
    /** A single leg may not take longer than this. */
    private static final int LEG_TIMEOUT_TICKS = 600;
    /**
     * Ticks a leg may go without the player moving anywhere before the leg is
     * given up on. The leg timeout alone is a bound on <em>length</em>, and a
     * walk that is going nowhere is not long, it is stuck: measured in a real
     * return trip, the bot walked for sixteen seconds, came to a stop against
     * something it could not pass, and then stood perfectly still for seventy-five
     * seconds while three legs each spent the full six hundred ticks and the
     * journey finally reported no progress. Five seconds is already a long time
     * for a walk that covers four blocks a second, and it leaves room for the
     * walker's own pauses — a jump being timed, a mesh being rebuilt under it.
     */
    private static final int STILL_TICKS_LIMIT = 100;
    /** Nor the whole journey. */
    private static final int TOTAL_TIMEOUT_TICKS = 12000;
    /**
     * Legs allowed to end without getting closer before the journey gives up.
     * Two, not one: the first stall is what triggers an attempt to mend the way
     * ahead, and that attempt deserves a leg to prove itself.
     */
    private static final int STALLED_LEGS_LIMIT = 2;
    /** Progress smaller than this is not progress. */
    private static final double PROGRESS_EPSILON = 1.0;
    /**
     * Cells of the way ahead one stall may try to mend. Deliberately tiny. The
     * equivalent logic in the chunk miner took five rounds to get right, and
     * every wrong version walked the bot somewhere it should not have gone; this
     * fills a step in front of the feet or it gives up.
     */
    private static final int MAX_BRIDGE_CELLS = 2;

    private static boolean active;
    private static Status status = Status.ARRIVED;
    private static String failReason = "";
    private static BlockPos target;
    private static Phase phase = Phase.MESHING;
    private static final Deque<ChunkCoordinate> pending = new ArrayDeque<>();
    private static int totalTicks;
    private static int legTicks;
    private static int stalledLegs;
    private static double bestDistance;
    /** Where the player last actually was, and for how long it has been true. */
    private static BlockPos stillAt;
    private static int stillTicks;
    /** What the current leg was asked to walk, for the diagnostics when it fails. */
    private static BlockPos legTarget;
    private static int legLength;

    private Journey() {
    }

    /**
     * Head for {@code destination}. Replaces any journey already running.
     */
    public static void start(BlockPos destination) {
        stop();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            status = Status.FAILED;
            failReason = "no player";
            return;
        }
        target = destination;
        active = true;
        status = Status.RUNNING;
        failReason = "";
        totalTicks = 0;
        legTicks = 0;
        stalledLegs = 0;
        stillAt = player.blockPosition();
        stillTicks = 0;
        legTarget = null;
        legLength = 0;
        bestDistance = Math.sqrt(player.blockPosition().distSqr(destination));
        phase = Phase.MESHING;
        queueCorridor(player);
        LOGGER.info("Journey to {} {} {} started, {} chunks queued",
                destination.getX(), destination.getY(), destination.getZ(), pending.size());
    }

    /** Abort. Safe when no journey is running. */
    public static void stop() {
        if (active) {
            PathWalker.stop();
        }
        active = false;
        pending.clear();
        target = null;
    }

    public static boolean isActive() {
        return active;
    }

    /** The verdict of the running or most recently finished journey. */
    public static Status status() {
        return status;
    }

    /** Why the last journey failed, or an empty string. */
    public static String failReason() {
        return failReason;
    }

    public static BlockPos target() {
        return target;
    }

    /**
     * Advance the journey. Must be called once per client tick while active;
     * {@code PathfindingModule} registers it.
     */
    public static Status tick(Minecraft client) {
        if (!active) {
            return status;
        }
        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null) {
            return fail("left the world");
        }
        if (++totalTicks > TOTAL_TIMEOUT_TICKS) {
            return fail("journey timed out after " + totalTicks + " ticks");
        }

        switch (phase) {
            case MESHING -> tickMeshing(player, level);
            case PLANNING -> tickPlanning(player, level);
            case WALKING -> tickWalking(player, level);
            case PLACING -> tickPlacing(player);
            default -> { }
        }
        return status;
    }

    /**
     * Build queued chunk meshes, at most {@link #CHUNKS_PER_TICK} per tick.
     * Unloaded chunks are dropped rather than built: a mesh made from the empty
     * chunk the client hands out for those would be cached and would make the
     * chunk permanently nodeless (see {@code MeshManager.isChunkLoaded}). They
     * come back into the queue on the next leg, by which time they may have
     * arrived.
     */
    private static void tickMeshing(LocalPlayer player, Level level) {
        int built = 0;
        while (built < CHUNKS_PER_TICK && !pending.isEmpty()) {
            ChunkCoordinate coord = pending.poll();
            if (MeshManager.hasMesh(player, coord) || !MeshManager.isChunkLoaded(level, coord)) {
                continue;
            }
            int feetY = player.blockPosition().getY();
            MeshManager.generateMesh(level.getChunk(coord.x(), coord.z()), player,
                    feetY - BAND_BELOW, feetY + BAND_ABOVE);
            built++;
        }
        if (pending.isEmpty()) {
            phase = Phase.PLANNING;
        }
    }

    private static void tickPlanning(LocalPlayer player, Level level) {
        MeshNode start = MeshManager.findOrBuildNearestNode(level, player, player.blockPosition());
        if (start == null) {
            fail("standing where the mesh has no node at all");
            return;
        }
        // The target's own chunk may well be unloaded, in which case there is no
        // goal node to aim at. Aim at the target's position through the nearest
        // node the mesh does have towards it: findPathTowards does exactly that,
        // and a synthetic node carrying the target's coordinates is enough for
        // the heuristic to point the right way.
        MeshNode goal = MeshManager.findOrBuildNearestNode(level, player, target);
        if (goal == null) {
            goal = new MeshNode(target.getX(), target.getY(), target.getZ());
        }

        List<MeshNode> path = new MeshPathfinder().findPathTowards(start, goal);
        if (path.isEmpty()) {
            // Nowhere reachable is closer than where we stand. Mending the way
            // ahead is the only move left, and only if this server allows it.
            if (tryBridge(player, level)) {
                phase = Phase.PLACING;
                return;
            }
            fail("no way towards " + shortPos(target) + " from " + shortPos(player.blockPosition()));
            return;
        }

        PathDisplay.setHighlightedPath(path);
        PathWalker.start(path);
        legTicks = 0;
        legTarget = path.get(path.size() - 1).getBlockPos();
        legLength = path.size();
        stillAt = player.blockPosition();
        stillTicks = 0;
        phase = Phase.WALKING;
    }

    private static void tickWalking(LocalPlayer player, Level level) {
        if (PathWalker.isActive()) {
            legTicks++;
            if (Math.sqrt(player.blockPosition().distSqr(stillAt)) > PROGRESS_EPSILON) {
                stillAt = player.blockPosition();
                stillTicks = 0;
            } else if (++stillTicks > STILL_TICKS_LIMIT) {
                endLeg(player, level, "stood still for " + stillTicks + " ticks");
                return;
            }
            if (legTicks > LEG_TIMEOUT_TICKS) {
                endLeg(player, level, "timed out after " + legTicks + " ticks");
            }
            return;
        }
        endLeg(player, level, "walked out");
    }

    private static void tickPlacing(LocalPlayer player) {
        if (PathPlacement.isBusy()) {
            return;
        }
        // The block is down (or the placement gave up — either way nothing is in
        // flight). Re-read the world rather than assume: the next plan finds the
        // step if it worked, and stalls again if it did not, which the stall
        // counter then turns into a failure.
        queueCorridor(player);
        phase = Phase.MESHING;
    }

    /**
     * A leg ended. Decide whether we are there, making progress, or stuck.
     *
     * @param why how the leg ended, for the log — the walker finishing, the leg
     *            timing out and the bot standing still look identical from a
     *            distance that did not change, and they want different fixes
     */
    private static void endLeg(LocalPlayer player, Level level, String why) {
        PathWalker.stop();
        double distance = Math.sqrt(player.blockPosition().distSqr(target));
        if (player.blockPosition().distSqr(target) <= ARRIVAL_RADIUS_SQ) {
            LOGGER.info("Journey arrived at {} after {} ticks", shortPos(target), totalTicks);
            PathDisplay.clearHighlightedPath();
            active = false;
            status = Status.ARRIVED;
            MeshManager.evictBeyond(player, new ChunkCoordinate(
                    player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4), KEEP_RADIUS);
            return;
        }

        if (bestDistance - distance > PROGRESS_EPSILON) {
            bestDistance = distance;
            stalledLegs = 0;
        } else {
            // Failure-only diagnostics, and the reason this class had none is
            // the reason a real failure could not be read at all: ninety seconds
            // of a stuck return trip produced three identical lines saying a leg
            // had timed out, and nothing about where the bot stood, what it had
            // been told to walk, or whether a path had even been found. All of
            // that is known right here.
            LOGGER.warn("Journey leg {} stalled: {} — player={} legTarget={} legNodes={}"
                    + " distance={} best={} walker={}", stalledLegs + 1, why,
                    shortPos(player.blockPosition()),
                    legTarget == null ? "none" : shortPos(legTarget), legLength,
                    String.format("%.1f", distance), String.format("%.1f", bestDistance),
                    PathWalker.isActive());
            if (++stalledLegs >= STALLED_LEGS_LIMIT) {
                fail("no progress towards " + shortPos(target) + " over " + stalledLegs
                        + " legs, still " + String.format("%.1f", distance) + " blocks away"
                        + " (stopped at " + shortPos(player.blockPosition())
                        + ", last leg aimed at "
                        + (legTarget == null ? "nothing" : shortPos(legTarget)) + ")");
                return;
            }
            // The way ahead is what is wrong, so mend it if this server allows
            // that — which is what STALLED_LEGS_LIMIT's second leg is for. It
            // used to be reachable only when A* found nothing at all, and that
            // is not what being stuck looks like: the search keeps handing back
            // a perfectly good partial path to the best node it can see, the bot
            // is already standing on it, and the leg that follows goes nowhere
            // for the full timeout. So the attempt belongs to the stall, not to
            // the empty path.
            if (tryBridge(player, level)) {
                phase = Phase.PLACING;
                return;
            }
        }

        // Keep the cache bounded as we move; the player's surroundings are what
        // the next plan needs, everything behind us is paid for already.
        MeshManager.evictBeyond(player, new ChunkCoordinate(
                player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4), KEEP_RADIUS);
        queueCorridor(player);
        phase = Phase.MESHING;
    }

    /**
     * Queue the chunks between the player and the target for meshing, clipped to
     * the window around the player that can plausibly be loaded.
     */
    private static void queueCorridor(LocalPlayer player) {
        pending.clear();
        BlockPos feet = player.blockPosition();
        int playerChunkX = feet.getX() >> 4;
        int playerChunkZ = feet.getZ() >> 4;
        int targetChunkX = target.getX() >> 4;
        int targetChunkZ = target.getZ() >> 4;

        // A band around the straight line, not the bounding box of the two ends.
        // The box is the same thing for a route straight along an axis and
        // quadratically worse for a diagonal one: a target eight chunks away on
        // both axes would queue 289 chunks to find a route through about twenty.
        int spanX = targetChunkX - playerChunkX;
        int spanZ = targetChunkZ - playerChunkZ;
        // Length of the line in chunks along its dominant axis, and how much of
        // it is worth queueing. Interpolation divides by the FULL length so each
        // step advances at most one chunk; dividing by the clipped count instead
        // would stride three chunks at a time and leave holes in the corridor.
        int length = Math.max(Math.abs(spanX), Math.abs(spanZ));
        int steps = Math.min(length, KEEP_RADIUS);
        Set<ChunkCoordinate> queued = new LinkedHashSet<>();
        for (int step = 0; step <= steps; step++) {
            int centerX = playerChunkX + (length == 0 ? 0 : Math.round((float) spanX * step / length));
            int centerZ = playerChunkZ + (length == 0 ? 0 : Math.round((float) spanZ * step / length));
            // One chunk of padding around each point on the line: a route rarely
            // runs down the exact straight line, and a corridor one chunk wide
            // makes every sidestep a dead end.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (Math.abs(x - playerChunkX) > KEEP_RADIUS
                            || Math.abs(z - playerChunkZ) > KEEP_RADIUS) {
                        continue;
                    }
                    queued.add(new ChunkCoordinate(x, z));
                }
            }
        }
        pending.addAll(queued);
    }

    /**
     * Ask for a block in the first missing floor cell on the way towards the
     * target, supported by the last solid one before it.
     *
     * <p>Cell by cell, one axis per step — the larger remaining difference wins —
     * because a placement needs a face to build against and a diagonal probe
     * names a cell that is not orthogonally adjacent to the last one. The support
     * is <em>prescribed</em> as the previous floor cell and never searched: over a
     * gap, ranking faces by how squarely they face the eye picks the far rim and
     * walks the bot into the hole.
     *
     * @return true when a placement is in flight
     */
    private static boolean tryBridge(LocalPlayer player, Level level) {
        if (!PathPlacement.isAvailable()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        BlockPos floor = feet.below();
        if (!isSolidFloor(level, player, floor)) {
            // Nothing under our own feet to build out from. Whatever is wrong
            // here, a bridge is not the answer.
            return false;
        }

        int x = feet.getX();
        int z = feet.getZ();
        BlockPos support = floor;
        for (int step = 0; step < MAX_BRIDGE_CELLS; step++) {
            int dx = target.getX() - x;
            int dz = target.getZ() - z;
            if (dx == 0 && dz == 0) {
                return false;
            }
            if (Math.abs(dx) >= Math.abs(dz)) {
                x += Integer.signum(dx);
            } else {
                z += Integer.signum(dz);
            }
            BlockPos candidate = new BlockPos(x, floor.getY(), z);
            if (isSolidFloor(level, player, candidate)) {
                support = candidate;
                continue;
            }
            LOGGER.info("Journey bridging {} against {}", shortPos(candidate), shortPos(support));
            return PathPlacement.place(candidate, support);
        }
        return false;
    }

    /**
     * The same question the mesh builder asks of a node's base block, so a cell
     * this accepts is a cell the mesh will turn into a node.
     */
    private static boolean isSolidFloor(Level level, LocalPlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.entityCanStandOn(level, pos, player);
    }

    private static Status fail(String reason) {
        LOGGER.warn("Journey failed: {}", reason);
        PathWalker.stop();
        PathDisplay.clearHighlightedPath();
        active = false;
        pending.clear();
        status = Status.FAILED;
        failReason = reason;
        return status;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

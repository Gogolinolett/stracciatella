package net.stracciatella.pathfinding.travel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.display.PathDisplay;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.MeshPathfinder;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.Terrain;
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
        PLACING,
        /** Standing a moment between two legs; see {@link Quirks}. */
        PAUSED
    }

    /**
     * The traveller's rare slips, chance per leg: heading off to a point beside
     * the way first (a detour — another way than the best one, still a walkable
     * one), and standing still for a moment between two legs. Off for every
     * journey but those whose caller asks — the bot's own trips.
     */
    public record Quirks(double detourChance, double pauseChance, int pauseMinTicks, int pauseMaxTicks) {
        public static final Quirks NONE = new Quirks(0.0, 0.0, 0, 0);
    }

    /** How far ahead on the way a detour's point lies; only taken with twice that still to go. */
    private static final double DETOUR_REACH = 10.0;
    /** How far to the side of the way it lies. */
    private static final double DETOUR_SIDE_MIN = 3.0;
    private static final double DETOUR_SIDE_MAX = 6.0;
    /** How far the nearest node may be from the point, either way, and still stand for it. */
    private static final double DETOUR_NODE_SLACK = 2.0;

    /** Close enough to count as arrived — the same 2 blocks WalkTravelMethod used. */
    private static final double ARRIVAL_RADIUS_SQ = 4.0;
    /**
     * Chunk meshes built per tick. A mesh is tens of thousands of block reads
     * even with a band, so a long route must not be paid for in one frame: the
     * bot stands a moment longer and the client never stutters.
     */
    private static final int CHUNKS_PER_TICK = 2;
    /**
     * Band a route actually uses: from below the lower of the traveller's and
     * the target's height to above the higher one. Both ends, because a trip
     * up out of a mine to a chest on the surface walks every layer in between,
     * and a band around the feet alone hid the far end of every climb.
     */
    private static final int BAND_ABOVE = 16;
    private static final int BAND_BELOW = 32;
    /**
     * Chunks kept meshed around the player. Doubles as the window the corridor
     * is clipped to: further out nothing is loaded anyway, so enumerating it
     * would only cost hasChunk calls.
     */
    private static final int KEEP_RADIUS = 8;
    /** Chunks of padding either side of the straight line, while it works. */
    private static final int CORRIDOR_PAD = 1;
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
    /** The band the queued chunks are meshed over, fixed when they are queued. */
    private static int bandMinY;
    private static int bandMaxY;
    /**
     * Whether the corridor has been widened to the whole window around the
     * player — set the moment the narrow one stops helping, cleared as soon as
     * the journey makes progress again.
     */
    private static boolean wide;
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
    private static Quirks quirks = Quirks.NONE;
    private static int pauseTicks;

    private Journey() {
    }

    /**
     * Head for {@code destination}. Replaces any journey already running.
     */
    public static void start(BlockPos destination) {
        start(destination, Quirks.NONE);
    }

    /** The same, with the traveller's slips — see {@link Quirks}. */
    public static void start(BlockPos destination, Quirks slips) {
        stop();
        quirks = slips;
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
        wide = false;
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
            case PAUSED -> {
                if (--pauseTicks <= 0) {
                    phase = Phase.MESHING;
                }
            }
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
     * arrived. A chunk that already has a mesh is rebuilt when that mesh does
     * not cover the band: skipping it on "has a mesh" alone kept the band of
     * the first leg that saw it, and a hill rising above it stayed invisible.
     */
    private static void tickMeshing(LocalPlayer player, Level level) {
        int built = 0;
        while (built < CHUNKS_PER_TICK && !pending.isEmpty()) {
            if (MeshManager.ensureMesh(level, player, pending.poll(), bandMinY, bandMaxY)) {
                built++;
            }
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

        if (roll(quirks.detourChance())) {
            MeshNode aside = detourPoint(player, level);
            if (aside != null) {
                goal = aside;
            }
        }
        List<MeshNode> path = new MeshPathfinder().findPathTowards(start, goal);
        if (path.isEmpty()) {
            // Nowhere reachable is closer than where we stand — inside the
            // corridor. The way round may well lie outside it, so look at the
            // whole window once before concluding there is none.
            if (!wide) {
                widen(player);
                return;
            }
            // Mending the way ahead is the only move left, and only if this
            // server allows it.
            if (tryBridge(player, level)) {
                phase = Phase.PLACING;
                return;
            }
            fail("no way towards " + shortPos(target) + " from " + shortPos(player.blockPosition()));
            return;
        }

        PathDisplay.setHighlightedPath(path);
        // A stretch of a trip: Journey judges arrival itself, so the walker
        // need not stop dead on the leg's last node, and crosses open ground
        // in straight lines with sprint-jumps.
        PathWalker.startTravel(path);
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
            wide = false;
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
            // Nothing to mend, so the next leg searches the whole window: a leg
            // that ends no closer is one whose way round the corridor did not
            // contain.
            wide = true;
        }

        // Keep the cache bounded as we move; the player's surroundings are what
        // the next plan needs, everything behind us is paid for already.
        MeshManager.evictBeyond(player, new ChunkCoordinate(
                player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4), KEEP_RADIUS);
        queueCorridor(player);
        phase = Phase.MESHING;
        // A moment's standstill between two legs that got somewhere — never
        // on top of a stall, which has spent its time standing already.
        if (stalledLegs == 0 && roll(quirks.pauseChance())) {
            pauseTicks = quirks.pauseMinTicks() >= quirks.pauseMaxTicks() ? quirks.pauseMinTicks()
                    : ThreadLocalRandom.current().nextInt(quirks.pauseMinTicks(), quirks.pauseMaxTicks() + 1);
            phase = Phase.PAUSED;
            LOGGER.info("Journey pausing for {} ticks", pauseTicks);
        }
    }

    /** Re-mesh with the corridor widened to the whole window, then plan again. */
    private static void widen(LocalPlayer player) {
        LOGGER.info("Journey widening its search around {} towards {}",
                shortPos(player.blockPosition()), shortPos(target));
        wide = true;
        queueCorridor(player);
        phase = Phase.MESHING;
    }

    /**
     * Queue the chunks between the player and the target for meshing, clipped to
     * the window around the player that can plausibly be loaded — or the whole
     * window, once the corridor has been widened.
     */
    private static void queueCorridor(LocalPlayer player) {
        pending.clear();
        BlockPos feet = player.blockPosition();
        bandMinY = Math.min(feet.getY(), target.getY()) - BAND_BELOW;
        bandMaxY = Math.max(feet.getY(), target.getY()) + BAND_ABOVE;
        int pad = wide ? KEEP_RADIUS : CORRIDOR_PAD;
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
            // Padding around each point on the line: a route rarely runs down
            // the exact straight line, and a corridor one chunk wide makes every
            // sidestep a dead end.
            for (int dx = -pad; dx <= pad; dx++) {
                for (int dz = -pad; dz <= pad; dz++) {
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
     * <p>Only a gap with a far rim: at most {@link #MAX_BRIDGE_CELLS} missing
     * cells, then floor again on the same level. Without that the bridge led
     * nowhere and nothing bounded it either — each planning round that found no
     * path laid one more cell, and a return trip to a pit ten blocks below the
     * ground built seven of them straight out over it at ground level, stood
     * above its target and failed there.
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
        BlockPos gap = null;
        int gapCells = 0;
        for (int step = 0; step <= 2 * MAX_BRIDGE_CELLS; step++) {
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
                if (gap != null) {
                    LOGGER.info("Journey bridging {} against {}", shortPos(gap), shortPos(support));
                    return PathPlacement.place(gap, support);
                }
                if (step + 1 >= MAX_BRIDGE_CELLS) {
                    // Floor all the way in front of the feet: nothing to mend.
                    return false;
                }
                support = candidate;
                continue;
            }
            if (gap == null) {
                gap = candidate;
            }
            if (++gapCells > MAX_BRIDGE_CELLS) {
                // Wider than a step: a pit, not a gap.
                return false;
            }
        }
        return false;
    }

    /**
     * A node beside the way ahead for a detour leg to head for, or null where
     * there is none to be had: too close to the target for a detour to still be
     * progress, or no ground near the point. Asked of the planner rather than
     * of the walk — a leg bent round an obstacle that is not there is walked
     * straight again by the travel cruise wherever the straight line is free,
     * which on open ground is everywhere; a leg that ends beside the way is
     * not.
     */
    private static MeshNode detourPoint(LocalPlayer player, Level level) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 2 * DETOUR_REACH) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double side = random.nextDouble(DETOUR_SIDE_MIN, DETOUR_SIDE_MAX) * (random.nextBoolean() ? 1 : -1);
        double x = player.getX() + (dx * DETOUR_REACH - dz * side) / length;
        double z = player.getZ() + (dz * DETOUR_REACH + dx * side) / length;
        MeshNode node = MeshManager.findOrBuildNearestNode(level, player,
                BlockPos.containing(x, player.getY(), z));
        // On about the level the bot walks on, too: the nearest node to a point
        // over a ravine is down in it, and that is no detour.
        if (node == null || Math.hypot(node.getX() + 0.5 - x, node.getZ() + 0.5 - z) > DETOUR_NODE_SLACK
                || Math.abs(node.getY() + 1 - player.getY()) > DETOUR_NODE_SLACK) {
            return null;
        }
        LOGGER.info("Journey taking a detour by {}", shortPos(node.getBlockPos()));
        return node;
    }

    private static boolean roll(double chance) {
        return chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
    }

    /**
     * The same question the mesh builder asks of a node's base block, so a cell
     * this accepts is a cell the mesh will turn into a node.
     */
    private static boolean isSolidFloor(Level level, LocalPlayer player, BlockPos pos) {
        return Terrain.isStandable(level, pos, player);
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

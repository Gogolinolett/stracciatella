package net.stracciatella.pathfinding.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.mesh.Mesh;
import net.stracciatella.pathfinding.place.PathPlacement;
import net.stracciatella.pathfinding.travel.Journey;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link Journey} — travel that crosses chunk borders and heads for a
 * target the client has not been sent yet.
 *
 * <p>The hard part of testing this is the condition itself: "the target chunk is
 * not loaded". These tests <b>manufacture</b> it rather than walk into it, which
 * is what keeps them fast and keeps world generation out of the measurement.
 *
 * <p>Two mechanics do that, and the choice between them was the interesting part.
 * <b>Distance</b> creates the condition: a target 400 blocks out is 25 chunks
 * away and outside any render distance a test environment is going to have.
 * <b>Walking the player along the walkway while building it</b> makes it
 * buildable: {@code /fill} runs on the server, whose view distance is its own —
 * waiting for {@code mc.level.hasChunkAt}, the <em>client</em>, proves nothing
 * about it, and the first version of this fixture got "That position is not
 * loaded" for both fills and then hung with no bridge at all. The player's own
 * presence is the one thing that makes a chunk resident on both sides, so the
 * walkway is laid in segments with a teleport in front of each. Fiddling with the
 * client's render distance was another candidate and is not used: it depends on
 * option plumbing reaching the server to have any effect, where distance simply
 * holds.
 *
 * <p>Both tests assert the precondition before anything else, and the bridge is
 * verified end to end before the journey starts. A fixture where the target turns
 * out to be loaded, or where the walkway was never built, would pass or fail for
 * reasons that say nothing about the code under test.
 */
@TestSuite(name = "Journey Tests")
public class JourneyTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("JourneyTests");

    /** Far from the PathWalker courses, which live around x 100-800. */
    private static final int ORIGIN_X = 2000;
    private static final int ORIGIN_Y = 40;
    private static final int ORIGIN_Z = 2000;
    /** 25 chunks — beyond any render distance, so the far end is never loaded. */
    private static final int BRIDGE_LENGTH = 400;
    /** Three wide, so a sidestep does not end the run. */
    private static final int BRIDGE_HALF_WIDTH = 1;
    /**
     * How much walkway one {@code /fill} lays. Two chunks, so the player standing
     * in the middle has both ends one chunk away and inside any view distance the
     * test environment could be running with.
     */
    private static final int SEGMENT_LENGTH = 32;

    @MinecraftTest(name = "Journey walks to an unloaded target across chunks",
            timeoutTicks = 8000, order = 50)
    public void journeyCrossesChunksToUnloadedTarget(TestContext ctx) {
        BlockPos start = new BlockPos(ORIGIN_X, ORIGIN_Y + 1, ORIGIN_Z);
        BlockPos target = new BlockPos(ORIGIN_X + BRIDGE_LENGTH - 2, ORIGIN_Y + 1, ORIGIN_Z);
        try {
            if (!buildBridge(ctx, target)) {
                return;
            }
            standAt(ctx, start);

            ChunkCoordinate targetChunk = new ChunkCoordinate(target.getX() >> 4, target.getZ() >> 4);
            ChunkCoordinate startChunk = new ChunkCoordinate(start.getX() >> 4, start.getZ() >> 4);
            LOGGER.info("Start chunk {}, target chunk {} — {} chunks apart",
                    startChunk, targetChunk, Math.abs(targetChunk.x() - startChunk.x()));

            // Asked through the production predicate on purpose: if that one is
            // wrong about what the client holds, this test must not be right by
            // accident. mc.level.hasChunk answers yes for a placeholder chunk and
            // is unusable — see MeshManager.isChunkLoaded.
            //
            // Waited for rather than asserted outright: building the walkway walked
            // the player the length of it, so the far end was loaded a moment ago
            // and the server drops it a few ticks after the player leaves.
            try {
                ctx.waitFor(mc -> !MeshManager.isChunkLoaded(mc.level, targetChunk), 400);
            } catch (AssertionError timedOut) {
                ctx.fail("fixture is useless: target chunk " + targetChunk + " stayed loaded "
                        + "after walking back to the start, so nothing about streaming is "
                        + "being tested");
                return;
            }
            if (ctx.computeOnClient(mc -> MeshManager.hasMesh(mc.player, targetChunk))) {
                ctx.fail("fixture is useless: target chunk " + targetChunk + " is already meshed");
                return;
            }

            // Deliberately no mesh pre-generation. Working out which chunks it
            // needs, and meshing them as they stream in, is the whole job.
            ctx.runOnClient(mc -> Journey.start(target));

            ctx.waitFor(mc -> {
                if (mc.player.position().y < ORIGIN_Y - 3) {
                    throw new AssertionError("fell off the walkway at " + mc.player.blockPosition());
                }
                Journey.Status status = Journey.status();
                if (status == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason()
                            + " (at " + mc.player.blockPosition() + ")");
                }
                return status == Journey.Status.ARRIVED;
            });

            double distance = ctx.computeOnClient(mc ->
                    Math.sqrt(mc.player.blockPosition().distSqr(target)));
            if (distance > 2.5) {
                ctx.fail("journey reported arrival " + String.format("%.2f", distance)
                        + " blocks from " + target);
                return;
            }
            LOGGER.info("Journey arrived {} blocks from the target",
                    String.format("%.2f", distance));
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * Probing an unloaded chunk must not touch the mesh cache — and the entry it
     * would damage is <b>chunk (0,0)</b>, not the chunk being probed.
     *
     * <p>{@code level.getChunk(pos)} asks the chunk source with {@code load=true},
     * and for anything the client has not been sent the client answers with its
     * placeholder. That placeholder is a single shared instance whose own
     * {@code ChunkPos} is {@code (0,0)} — read off the bytecode of
     * {@code ClientChunkCache}, which builds it once as
     * {@code new EmptyLevelChunk(level, new ChunkPos(0, 0), PLAINS)} and returns
     * that same object for every out-of-range request. So a mesh built from a
     * probe of a chunk 8000 blocks away is not filed under that chunk at all.
     * {@code MeshManager.generateMesh} keys it by {@code new ChunkCoordinate(chunk)}
     * — the chunk's own position — and files an empty mesh over whatever chunk
     * (0,0) had, then reconnects its neighbours' border nodes to the hole. Nothing
     * ever rebuilds it: chunk load deliberately does not invalidate meshes.
     *
     * <p>This test was written the other way round first, asserting that the
     * <em>probed</em> chunk picked up no cache entry, and it passed with the guard
     * ripped out — the lookup misses either way, because the entry landed
     * somewhere else entirely. Hence the shape below: mesh chunk (0,0) for real,
     * probe far away, and require the real mesh to have survived.
     */
    @MinecraftTest(name = "Probing an unloaded chunk does not poison the mesh cache",
            timeoutTicks = 600, order = 51)
    public void probingUnloadedChunkDoesNotPoisonCache(TestContext ctx) {
        ChunkCoordinate home = new ChunkCoordinate(0, 0);
        // Far enough that no render distance could reach it.
        BlockPos far = new BlockPos(ORIGIN_X + 8000, ORIGIN_Y, ORIGIN_Z + 8000);
        ChunkCoordinate farChunk = new ChunkCoordinate(far.getX() >> 4, far.getZ() >> 4);

        ctx.runCommand("gamemode creative");
        ctx.runCommand("tp @s 8.5 120 8.5");
        ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, home) && mc.player.onGround());

        int homeNodes = ctx.computeOnClient(mc -> {
            MeshManager.findOrBuildNearestNode(mc.level, mc.player, mc.player.blockPosition());
            return nodeCount(mc.player, home);
        });
        if (homeNodes == 0) {
            ctx.fail("fixture failed: chunk " + home + " has no walkable nodes, so there is "
                    + "nothing for a bad probe to destroy");
            return;
        }
        LOGGER.info("Chunk {} meshed with {} nodes before the probe", home, homeNodes);

        if (ctx.computeOnClient(mc -> MeshManager.isChunkLoaded(mc.level, farChunk))) {
            ctx.fail("fixture is useless: chunk " + farChunk + " is loaded");
            return;
        }

        Object node = ctx.computeOnClient(mc ->
                MeshManager.findOrBuildNearestNode(mc.level, mc.player, far));
        if (node != null) {
            ctx.fail("got a mesh node out of an unloaded chunk: " + node);
            return;
        }

        if (ctx.computeOnClient(mc -> MeshManager.hasMesh(mc.player, farChunk))) {
            ctx.fail("probing unloaded chunk " + farChunk + " cached an empty mesh under its "
                    + "own coordinate — it can never be meshed again, even once it arrives");
            return;
        }

        int homeNodesAfter = ctx.computeOnClient(mc -> nodeCount(mc.player, home));
        if (homeNodesAfter != homeNodes) {
            ctx.fail("probing unloaded chunk " + farChunk + " overwrote the mesh of " + home
                    + ": " + homeNodes + " nodes before, " + homeNodesAfter + " after — the "
                    + "client's placeholder chunk reports its position as (0,0), so that is "
                    + "where the empty mesh gets filed");
            return;
        }
        LOGGER.info("Unloaded chunk {} left the cache untouched, {} still has {} nodes",
                farChunk, home, homeNodesAfter);
    }

    /**
     * A walk that cannot go on must be given up on <b>quickly</b>, and the
     * failure has to say where it stopped.
     *
     * <p>The wall goes up <em>while the bot is walking</em>, which is the only way
     * to reach the case that matters: {@link PathWalker} keeps the node list it was
     * handed, so it walks into the new wall and presses against it. Planning
     * against a wall that was already there never gets that far — A* hands back a
     * path of one node, the leg ends on its own tick, and nothing is being timed.
     *
     * <p>Reported from a real return trip, and the reason the budget below is the
     * assertion: the bot walked for sixteen seconds, stopped against something it
     * could not pass, and then stood perfectly still for seventy-five more while
     * three legs each spent the full {@code LEG_TIMEOUT_TICKS}. The leg timeout is
     * a bound on how <em>long</em> a leg may be, and a walk that is going nowhere
     * is not long — so a leg that stops moving now ends on {@code
     * STILL_TICKS_LIMIT} instead, and two of those are the whole failure.
     */
    @MinecraftTest(name = "Journey gives up quickly when the way is walled off",
            timeoutTicks = 2000, order = 52)
    public void journeyGivesUpWhenWalledOff(TestContext ctx) {
        final int z = ORIGIN_Z + 40;
        final int length = 48;
        final BlockPos start = new BlockPos(ORIGIN_X, ORIGIN_Y + 1, z);
        final BlockPos target = new BlockPos(ORIGIN_X + length - 2, ORIGIN_Y + 1, z);
        final boolean placementWasAllowed = PathPlacement.isAvailable();
        try {
            // Placement off for the duration: with it on, a stall tries to mend
            // the way ahead first, which is correct behaviour and a different
            // test. Set explicitly rather than trusted — a bot test that ran
            // earlier may have left it on.
            ctx.runOnClient(mc -> PathPlacement.setAllowed(false));
            if (!buildWalkway(ctx, z, length, target)) {
                return;
            }
            standAt(ctx, start);

            ctx.runOnClient(mc -> Journey.start(target));
            // Genuinely under way before the wall appears, so the leg being
            // measured is one that was walking.
            ctx.waitFor(mc -> mc.player.getX() > start.getX() + 6, 600);

            final int wallX = ctx.computeOnClient(mc -> (int) Math.floor(mc.player.getX()) + 3);
            ctx.runCommand("fill " + wallX + " " + (ORIGIN_Y + 1) + " " + (z - BRIDGE_HALF_WIDTH)
                    + " " + wallX + " " + (ORIGIN_Y + 3) + " " + (z + BRIDGE_HALF_WIDTH)
                    + " stone");
            ctx.waitFor(mc -> !mc.level.getBlockState(
                    new BlockPos(wallX, ORIGIN_Y + 2, z)).isAir());
            final long walled = ctx.computeOnClient(mc -> mc.level.getGameTime());
            LOGGER.info("Walled off at x={} while the bot was at {}", wallX,
                    ctx.computeOnClient(mc -> mc.player.blockPosition()));

            ctx.waitFor(mc -> Journey.status() != Journey.Status.RUNNING);
            long spent = ctx.computeOnClient(mc -> mc.level.getGameTime()) - walled;

            if (Journey.status() != Journey.Status.FAILED) {
                ctx.fail("the way is walled off and the journey reported "
                        + Journey.status() + " — there is no way through a three-block"
                        + " wall on a walkway three wide");
                return;
            }
            // Two stalled legs at STILL_TICKS_LIMIT plus the re-planning between
            // them. The old behaviour spent LEG_TIMEOUT_TICKS on the first one
            // alone and could not come in under this.
            if (spent > 400) {
                ctx.fail("the journey took " + spent + " ticks to give up on a wall it was"
                        + " standing against; a leg that stops moving must not run out its"
                        + " whole length first");
                return;
            }
            // Either wording is right, and which one this fixture gets is worth
            // knowing: once the wall is up there is nothing better than the cell
            // the bot stands on, so A* hands back nothing at all and the verdict
            // comes from the planner ("no way towards X from Y") rather than from
            // the stall counter ("stopped at Y"). Both name the position, and
            // that is the assertion — the reason is what reaches the player in
            // chat, and a failure that does not say where it happened is the one
            // this whole round started from.
            if (!Journey.failReason().contains("from ")
                    && !Journey.failReason().contains("stopped at ")) {
                ctx.fail("the failure reads \"" + Journey.failReason() + "\" and does not say"
                        + " where the bot stopped — which is the one thing the log needs");
                return;
            }
            LOGGER.info("Journey gave up {} ticks after the wall went up: {}",
                    spent, Journey.failReason());
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
                PathPlacement.setAllowed(placementWasAllowed);
            });
        }
    }

    /**
     * A short flat walkway along +X at {@code z}, three wide with headroom. Short
     * enough that one fill lays it with the player standing in the middle, which
     * is what makes the span resident on the server — see {@link #buildBridge}.
     *
     * @return false when the fixture could not be built; the test has been failed
     */
    private boolean buildWalkway(TestContext ctx, int z, int length, BlockPos target) {
        ctx.runOnClient(mc -> {
            Journey.stop();
            PathWalker.stop();
        });
        ctx.runCommand("gamemode creative");
        ctx.runCommand("tp @s " + (ORIGIN_X + length / 2 + 0.5) + " " + (ORIGIN_Y + 8)
                + " " + (z + 0.5));
        ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level,
                        new ChunkCoordinate(ORIGIN_X >> 4, z >> 4))
                && MeshManager.isChunkLoaded(mc.level,
                        new ChunkCoordinate((ORIGIN_X + length) >> 4, z >> 4)));
        ctx.runCommand("fill " + ORIGIN_X + " " + ORIGIN_Y + " " + (z - BRIDGE_HALF_WIDTH)
                + " " + (ORIGIN_X + length) + " " + ORIGIN_Y + " " + (z + BRIDGE_HALF_WIDTH)
                + " stone");
        ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 1) + " " + (z - BRIDGE_HALF_WIDTH)
                + " " + (ORIGIN_X + length) + " " + (ORIGIN_Y + 4) + " "
                + (z + BRIDGE_HALF_WIDTH) + " air");
        ctx.waitTicks(4);
        if (ctx.computeOnClient(mc -> mc.level.getBlockState(target.below()).isAir())) {
            ctx.fail("fixture failed to build: no walkway under " + target);
            return false;
        }
        return true;
    }

    /** Nodes in the cached mesh of {@code coord}, or 0 when there is none. */
    private static int nodeCount(Entity entity, ChunkCoordinate coord) {
        var forEntity = MeshManager.meshes.get(entity);
        if (forEntity == null) {
            return 0;
        }
        Mesh mesh = forEntity.get(coord);
        return mesh == null ? 0 : mesh.getNodes().size();
    }

    /**
     * Build a flat three-wide walkway along +X with headroom above it, and prove
     * it exists at both ends before anyone walks on it.
     *
     * @return false when the fixture could not be built; the test has already
     *         been failed in that case
     */
    private boolean buildBridge(TestContext ctx, BlockPos target) {
        ctx.runOnClient(mc -> {
            Journey.stop();
            PathWalker.stop();
        });

        // Creative, for the same reason the PathWalker courses run in it: the
        // segments are teleported to before there is anything to stand on, so the
        // player falls, and in survival that fall costs health until one of the
        // thirteen teleports kills them. Journey does not care about gamemode.
        ctx.runCommand("gamemode creative");

        int minZ = ORIGIN_Z - BRIDGE_HALF_WIDTH;
        int maxZ = ORIGIN_Z + BRIDGE_HALF_WIDTH;

        // Built in segments with the player teleporting along it, rather than in
        // one /fill. A fill runs on the server and needs the span resident there;
        // the server's view distance is its own business and waiting on
        // mc.level.hasChunkAt — the client — proves nothing about it. The player
        // standing in a segment is the one thing that reliably makes it resident
        // on both sides, and it needs no assumptions about forceload either.
        for (int from = ORIGIN_X; from < ORIGIN_X + BRIDGE_LENGTH; from += SEGMENT_LENGTH) {
            int to = Math.min(from + SEGMENT_LENGTH, ORIGIN_X + BRIDGE_LENGTH);
            int midX = (from + to) / 2;
            ctx.runCommand("tp @s " + (midX + 0.5) + " " + (ORIGIN_Y + 8) + " " + (ORIGIN_Z + 0.5));
            int segmentFrom = from;
            int segmentTo = to;
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate(segmentFrom >> 4, ORIGIN_Z >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate(segmentTo >> 4, ORIGIN_Z >> 4)));

            ctx.runCommand("fill " + from + " " + ORIGIN_Y + " " + minZ
                    + " " + to + " " + ORIGIN_Y + " " + maxZ + " stone");
            ctx.runCommand("fill " + from + " " + (ORIGIN_Y + 1) + " " + minZ
                    + " " + to + " " + (ORIGIN_Y + 4) + " " + maxZ + " air");
            ctx.waitTicks(4);
        }

        // Prove the far end really carries a walkway. A silently failed fill would
        // otherwise surface much later as a journey that cannot make progress, and
        // that failure would read like a defect in the code under test.
        ctx.runCommand("tp @s " + (target.getX() + 0.5) + " " + (ORIGIN_Y + 2)
                + " " + (target.getZ() + 0.5));
        ChunkCoordinate targetChunk = new ChunkCoordinate(target.getX() >> 4, target.getZ() >> 4);
        ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, targetChunk));
        boolean farEndBuilt = ctx.computeOnClient(mc ->
                !mc.level.getBlockState(target.below()).isAir());
        if (!farEndBuilt) {
            ctx.fail("fixture failed to build: no walkway under " + target);
            return false;
        }
        LOGGER.info("Walkway verified at its far end {}", target);
        return true;
    }

    private void standAt(TestContext ctx, BlockPos feet) {
        ctx.runCommand("tp @s " + (feet.getX() + 0.5) + " " + feet.getY() + " " + (feet.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().distSqr(feet) < 4.0);
    }
}

package net.stracciatella.pathfinding.test;

import java.util.ArrayList;
import java.util.List;
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
     * Open level ground and a target off both axes. A journey crosses it the
     * way a person does: one straight line at the target, sprinting and
     * sprint-jumping, its heading corrected now and then — not the grid's run of
     * straight and diagonal steps with the camera swinging between them. And it
     * never turns back to touch a node a jump carried it past.
     *
     * <p>Three measurements, each failing on the node-by-node walk: the share of
     * walking ticks whose yaw is within {@link #HEADING_TOLERANCE_DEG} of the
     * bearing to the target (a grid walk heads along the axis or the diagonal,
     * 22 degrees either side of it); the number of times the feet leave the
     * ground (none, on flat ground, without sprint-jumps); and the largest step
     * back along the line from the furthest point reached.
     */
    @MinecraftTest(name = "Journey crosses open ground in a straight line", timeoutTicks = 2000, order = 53)
    public void journeyCrossesOpenGroundStraight(TestContext ctx) {
        final int z0 = ORIGIN_Z + 80;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, z0 + 2);
        final BlockPos target = new BlockPos(ORIGIN_X + 42, ORIGIN_Y + 1, z0 + 18);
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + 22.5) + " " + (ORIGIN_Y + 8) + " " + (z0 + 10.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + 44) >> 4, (z0 + 20) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + ORIGIN_Y + " " + z0 + " "
                    + (ORIGIN_X + 44) + " " + ORIGIN_Y + " " + (z0 + 20) + " stone");
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 1) + " " + z0 + " "
                    + (ORIGIN_X + 44) + " " + (ORIGIN_Y + 4) + " " + (z0 + 20) + " air");
            ctx.waitFor(mc -> !mc.level.getBlockState(target.below()).isAir());
            standAt(ctx, start);

            final double lineX = target.getX() - start.getX();
            final double lineZ = target.getZ() - start.getZ();
            final double lineLength = Math.sqrt(lineX * lineX + lineZ * lineZ);
            final float bearing = (float) Math.toDegrees(Math.atan2(-lineX, lineZ));
            final int[] walkingTicks = {0};
            final int[] onHeadingTicks = {0};
            final int[] takeOffs = {0};
            final boolean[] wasOnGround = {true};
            final double[] furthest = {0.0};
            final double[] worstBack = {0.0};

            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> {
                if (Journey.status() == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason());
                }
                double along = ((mc.player.getX() - (start.getX() + 0.5)) * lineX
                        + (mc.player.getZ() - (start.getZ() + 0.5)) * lineZ) / lineLength;
                furthest[0] = Math.max(furthest[0], along);
                worstBack[0] = Math.max(worstBack[0], furthest[0] - along);
                boolean onGround = mc.player.onGround();
                if (wasOnGround[0] && !onGround) {
                    takeOffs[0]++;
                }
                wasOnGround[0] = onGround;
                // The first and last few blocks are turning onto the line and
                // coming off it; the heading is measured in between.
                if (along > 3.0 && along < lineLength - 4.0) {
                    walkingTicks[0]++;
                    float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - bearing));
                    if (off <= HEADING_TOLERANCE_DEG) {
                        onHeadingTicks[0]++;
                    }
                }
                return Journey.status() == Journey.Status.ARRIVED;
            });

            double distance = ctx.computeOnClient(mc -> Math.sqrt(mc.player.blockPosition().distSqr(target)));
            if (distance > 2.5) {
                ctx.fail("journey reported arrival " + String.format("%.2f", distance) + " blocks from " + target);
                return;
            }
            double onHeading = walkingTicks[0] == 0 ? 0.0 : (double) onHeadingTicks[0] / walkingTicks[0];
            String summary = String.format("heading held %.0f%% of %d ticks, %d take-offs, worst step back %.2f",
                    onHeading * 100, walkingTicks[0], takeOffs[0], worstBack[0]);
            if (onHeading < MIN_ON_HEADING_SHARE) {
                ctx.fail("the walk did not hold a straight line at the target: " + summary);
                return;
            }
            if (takeOffs[0] < 2) {
                ctx.fail("no sprint-jumps across open level ground: " + summary);
                return;
            }
            if (worstBack[0] > MAX_STEP_BACK) {
                ctx.fail("the walk turned back along its line: " + summary);
                return;
            }
            LOGGER.info("Journey crossed open ground straight: {}", summary);
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * Terraces stepping down a block and then three, and a journey across them.
     * A person walks off a ledge without breaking stride; the bot, on the way
     * back from a restock, stopped at the edge. Measured as the longest stretch
     * on the ground at a standstill between the first step and the arrival.
     *
     * <p>Repeated, because whether the walk ticks the node below the edge off
     * before the fall depends on where the ticks happen to fall at the rim: one
     * run in eight turned round in the air for it before that was fixed.
     */
    @MinecraftTest(name = "Journey walks down ledges without stopping", timeoutTicks = 2000, order = 54, repeat = 5)
    public void journeyWalksDownLedges(TestContext ctx) {
        final int z0 = ORIGIN_Z + 120;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 5, z0 + 3);
        final BlockPos target = new BlockPos(ORIGIN_X + 42, ORIGIN_Y + 1, z0 + 7);
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + 22.5) + " " + (ORIGIN_Y + 12) + " " + (z0 + 5.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + 44) >> 4, (z0 + 10) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y - 2) + " " + z0 + " "
                    + (ORIGIN_X + 44) + " " + (ORIGIN_Y + 10) + " " + (z0 + 10) + " air");
            // Floors at Y+4, Y+3 and Y: a step down of one, then the deepest
            // drop the mesh plans, three — the fall that carries a walk off the
            // edge furthest past the node below it.
            int[][] terraces = {{0, 14, ORIGIN_Y + 4}, {15, 29, ORIGIN_Y + 3}, {30, 44, ORIGIN_Y}};
            for (int[] terrace : terraces) {
                ctx.runCommand("fill " + (ORIGIN_X + terrace[0]) + " " + (ORIGIN_Y - 2) + " " + z0 + " "
                        + (ORIGIN_X + terrace[1]) + " " + terrace[2] + " " + (z0 + 10) + " stone");
            }
            ctx.waitFor(mc -> !mc.level.getBlockState(target.below()).isAir()
                    && !mc.level.getBlockState(start.below()).isAir());
            standAt(ctx, start);

            final boolean[] moving = {false};
            final int[] still = {0};
            final int[] longestStill = {0};
            final BlockPos[] stillAt = {null};
            final double[] slowest = {Double.MAX_VALUE};
            final BlockPos[] slowestAt = {null};
            final LedgeJumps ledgeJumps = new LedgeJumps();
            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> {
                if (Journey.status() == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason());
                }
                if (mc.player.position().y < ORIGIN_Y - 0.5) {
                    throw new AssertionError("fell off the terraces at " + mc.player.blockPosition());
                }
                ledgeJumps.sample(mc.player);
                var velocity = mc.player.getDeltaMovement();
                double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                moving[0] |= speed > 0.1;
                if (moving[0] && mc.player.onGround() && speed < STANDSTILL_SPEED) {
                    if (++still[0] > longestStill[0]) {
                        longestStill[0] = still[0];
                        stillAt[0] = mc.player.blockPosition();
                    }
                } else {
                    still[0] = 0;
                }
                // The last few blocks are the arrival, where slowing down is right.
                if (moving[0] && mc.player.onGround() && speed < slowest[0]
                        && mc.player.blockPosition().distSqr(target) > 9.0) {
                    slowest[0] = speed;
                    slowestAt[0] = mc.player.blockPosition();
                }
                return Journey.status() == Journey.Status.ARRIVED;
            });

            double distance = ctx.computeOnClient(mc -> Math.sqrt(mc.player.blockPosition().distSqr(target)));
            if (distance > 2.5) {
                ctx.fail("journey reported arrival " + String.format("%.2f", distance) + " blocks from " + target);
                return;
            }
            if (!ledgeJumps.found.isEmpty()) {
                ctx.fail("the walk jumped down a ledge instead of walking off it: " + String.join("; ", ledgeJumps.found));
                return;
            }
            if (longestStill[0] > MAX_STANDSTILL_TICKS) {
                ctx.fail("the walk stood still for " + longestStill[0] + " ticks at " + stillAt[0]
                        + " on its way down the terraces");
                return;
            }
            if (slowest[0] < MIN_WALK_SPEED) {
                ctx.fail("the walk slowed to " + String.format("%.3f", slowest[0]) + " b/t at " + slowestAt[0]
                        + " on its way down the terraces — a person walks off a ledge at walking pace");
                return;
            }
            LOGGER.info("Journey walked down the ledges, slowest {} b/t", String.format("%.3f", slowest[0]));
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * Rough ground falling away: three-by-three cells at heights drawn from a
     * fixed seed, stepping down one, two or three blocks at straight and
     * diagonal edges, the way a hillside does. A journey down it, measured the
     * same way as the terraces — every standstill on the ground is recorded.
     */
    @MinecraftTest(name = "Journey walks down rough ground without stopping", timeoutTicks = 3000, order = 55)
    public void journeyWalksDownRoughGround(TestContext ctx) {
        final int z0 = ORIGIN_Z + 160;
        final int cellsX = 16;
        final int cellsZ = 8;
        final int cell = 3;
        final int[][] height = new int[cellsX][cellsZ];
        java.util.Random random = new java.util.Random(20261005L);
        for (int cx = 0; cx < cellsX; cx++) {
            for (int cz = 0; cz < cellsZ; cz++) {
                int h = (int) Math.round(10 - cx * 0.65) + random.nextInt(3) - 1;
                height[cx][cz] = Math.max(0, Math.min(11, h));
            }
        }
        final BlockPos start = new BlockPos(ORIGIN_X + 1, ORIGIN_Y + height[0][1] + 1, z0 + 4);
        final BlockPos target = new BlockPos(ORIGIN_X + cellsX * cell - 2, ORIGIN_Y + height[cellsX - 1][6] + 1,
                z0 + 19);
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + 24.5) + " " + (ORIGIN_Y + 20) + " " + (z0 + 12.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + cellsX * cell) >> 4, (z0 + cellsZ * cell) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y - 2) + " " + z0 + " "
                    + (ORIGIN_X + cellsX * cell - 1) + " " + (ORIGIN_Y + 15) + " " + (z0 + cellsZ * cell - 1) + " air");
            for (int cx = 0; cx < cellsX; cx++) {
                for (int cz = 0; cz < cellsZ; cz++) {
                    int x = ORIGIN_X + cx * cell;
                    int z = z0 + cz * cell;
                    ctx.runCommand("fill " + x + " " + (ORIGIN_Y - 2) + " " + z + " " + (x + cell - 1) + " "
                            + (ORIGIN_Y + height[cx][cz]) + " " + (z + cell - 1) + " stone");
                }
            }
            ctx.waitFor(mc -> !mc.level.getBlockState(target.below()).isAir()
                    && !mc.level.getBlockState(start.below()).isAir());
            standAt(ctx, start);

            final boolean[] moving = {false};
            final int[] still = {0};
            final BlockPos[] stillFrom = {null};
            final List<String> standstills = new ArrayList<>();
            final LedgeJumps ledgeJumps = new LedgeJumps();
            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> {
                if (Journey.status() == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason());
                }
                ledgeJumps.sample(mc.player);
                var velocity = mc.player.getDeltaMovement();
                double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
                moving[0] |= speed > 0.1;
                if (moving[0] && mc.player.onGround() && speed < STANDSTILL_SPEED) {
                    if (still[0]++ == 0) {
                        stillFrom[0] = mc.player.blockPosition();
                    }
                } else {
                    if (still[0] > MAX_STANDSTILL_TICKS) {
                        standstills.add(still[0] + " ticks at " + stillFrom[0].toShortString());
                    }
                    still[0] = 0;
                }
                return Journey.status() == Journey.Status.ARRIVED;
            });

            double distance = ctx.computeOnClient(mc -> Math.sqrt(mc.player.blockPosition().distSqr(target)));
            if (distance > 2.5) {
                ctx.fail("journey reported arrival " + String.format("%.2f", distance) + " blocks from " + target);
                return;
            }
            if (!ledgeJumps.found.isEmpty()) {
                ctx.fail("the walk jumped down a ledge instead of walking off it: " + String.join("; ", ledgeJumps.found));
                return;
            }
            if (!standstills.isEmpty()) {
                ctx.fail("the walk stood still on its way down: " + String.join("; ", standstills));
                return;
            }
            LOGGER.info("Journey walked down the rough ground without a standstill");
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * A two-cell hole in the floor of a two-high corridor: too low to jump
     * across, narrow enough to mend. The journey bridges it and arrives.
     */
    @MinecraftTest(name = "Journey bridges a narrow gap it cannot jump", timeoutTicks = 3000, order = 56)
    public void journeyBridgesNarrowGap(TestContext ctx) {
        final int z = ORIGIN_Z + 200;
        final int length = 24;
        final int gapX = ORIGIN_X + 10;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, z);
        final BlockPos target = new BlockPos(ORIGIN_X + length - 2, ORIGIN_Y + 1, z);
        final boolean placementWasAllowed = PathPlacement.isAvailable();
        try {
            if (!buildWalkway(ctx, z, length, target)) {
                return;
            }
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 3) + " " + (z - BRIDGE_HALF_WIDTH) + " "
                    + (ORIGIN_X + length) + " " + (ORIGIN_Y + 3) + " " + (z + BRIDGE_HALF_WIDTH) + " stone");
            ctx.runCommand("fill " + gapX + " " + ORIGIN_Y + " " + (z - BRIDGE_HALF_WIDTH) + " "
                    + (gapX + 1) + " " + ORIGIN_Y + " " + (z + BRIDGE_HALF_WIDTH) + " air");
            ctx.waitFor(mc -> mc.level.getBlockState(new BlockPos(gapX, ORIGIN_Y, z)).isAir()
                    && !mc.level.getBlockState(new BlockPos(gapX, ORIGIN_Y + 3, z)).isAir());
            ctx.runCommand("clear @s");
            ctx.runCommand("give @s cobblestone 64");
            ctx.runOnClient(mc -> PathPlacement.setAllowed(true));
            standAt(ctx, start);

            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> {
                if (mc.player.position().y < ORIGIN_Y - 3) {
                    throw new AssertionError("fell into the gap at " + mc.player.blockPosition());
                }
                return Journey.status() != Journey.Status.RUNNING;
            });
            if (Journey.status() != Journey.Status.ARRIVED) {
                ctx.fail("the journey did not get across a two-cell gap it may bridge: " + Journey.failReason());
                return;
            }
            LOGGER.info("Journey bridged the gap and arrived");
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
                PathPlacement.setAllowed(placementWasAllowed);
            });
        }
    }

    /**
     * A target at the bottom of a pit eight blocks deep, with no way down —
     * what a chunk miner's shaft is to a bot coming back from a restock. There
     * is nothing a bridge can do there, and the journey must say so rather than
     * build one: it laid a walkway out over the pit at ground level, cell by
     * cell, until it stood above the target and failed anyway.
     */
    @MinecraftTest(name = "Journey does not bridge out over a pit", timeoutTicks = 3000, order = 57)
    public void journeyDoesNotBridgeOverPit(TestContext ctx) {
        final int z0 = ORIGIN_Z + 240;
        final int size = 24;
        final int pitFrom = 8;
        final int pitTo = 15;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, z0 + 11);
        final BlockPos target = new BlockPos(ORIGIN_X + 12, ORIGIN_Y - 7, z0 + 11);
        final boolean placementWasAllowed = PathPlacement.isAvailable();
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + 12.5) + " " + (ORIGIN_Y + 10) + " " + (z0 + 12.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + size) >> 4, (z0 + size) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y - 8) + " " + z0 + " " + (ORIGIN_X + size - 1) + " "
                    + ORIGIN_Y + " " + (z0 + size - 1) + " stone");
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 1) + " " + z0 + " " + (ORIGIN_X + size - 1) + " "
                    + (ORIGIN_Y + 6) + " " + (z0 + size - 1) + " air");
            ctx.runCommand("fill " + (ORIGIN_X + pitFrom) + " " + (ORIGIN_Y - 7) + " " + (z0 + pitFrom) + " "
                    + (ORIGIN_X + pitTo) + " " + ORIGIN_Y + " " + (z0 + pitTo) + " air");
            ctx.waitFor(mc -> mc.level.getBlockState(target).isAir()
                    && mc.level.getBlockState(new BlockPos(ORIGIN_X + pitFrom, ORIGIN_Y, z0 + pitFrom)).isAir()
                    && !mc.level.getBlockState(start.below()).isAir());
            ctx.runCommand("clear @s");
            ctx.runCommand("give @s cobblestone 64");
            ctx.runOnClient(mc -> PathPlacement.setAllowed(true));
            standAt(ctx, start);

            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> Journey.status() != Journey.Status.RUNNING
                    && !PathPlacement.isBusy());

            List<String> bridged = ctx.computeOnClient(mc -> {
                List<String> found = new ArrayList<>();
                for (int x = pitFrom; x <= pitTo; x++) {
                    for (int dz = pitFrom; dz <= pitTo; dz++) {
                        BlockPos cell = new BlockPos(ORIGIN_X + x, ORIGIN_Y, z0 + dz);
                        if (!mc.level.getBlockState(cell).isAir()) {
                            found.add(cell.toShortString());
                        }
                    }
                }
                return found;
            });
            if (!bridged.isEmpty()) {
                ctx.fail("the journey bridged out over the pit: " + String.join("; ", bridged));
                return;
            }
            if (Journey.status() != Journey.Status.FAILED) {
                ctx.fail("the journey reported " + Journey.status() + " for a target with no way down to it");
                return;
            }
            LOGGER.info("Journey gave up on the pit without bridging it: {}", Journey.failReason());
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
                PathPlacement.setAllowed(placementWasAllowed);
            });
        }
    }

    /**
     * A straight walk along an axis across open ground, started on a block
     * centre. A person walks wherever they happen to be; the cruise aimed at
     * node centres and walked forty blocks without leaving the middle of the
     * row. Measured as the share of walking ticks whose sideways position is
     * within {@link #CENTRE_BAND} of a block centre.
     */
    @MinecraftTest(name = "Journey does not walk down the middle of the blocks", timeoutTicks = 2000, order = 58)
    public void journeyLeavesTheBlockCentres(TestContext ctx) {
        final int z0 = ORIGIN_Z + 280;
        final int length = 48;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, z0 + 4);
        final BlockPos target = new BlockPos(ORIGIN_X + length - 2, ORIGIN_Y + 1, z0 + 4);
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + length / 2 + 0.5) + " " + (ORIGIN_Y + 8) + " " + (z0 + 4.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + length) >> 4, (z0 + 8) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + ORIGIN_Y + " " + z0 + " "
                    + (ORIGIN_X + length) + " " + ORIGIN_Y + " " + (z0 + 8) + " stone");
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 1) + " " + z0 + " "
                    + (ORIGIN_X + length) + " " + (ORIGIN_Y + 4) + " " + (z0 + 8) + " air");
            ctx.waitFor(mc -> !mc.level.getBlockState(target.below()).isAir());
            standAt(ctx, start);

            final int[] walking = {0};
            final int[] centred = {0};
            ctx.runOnClient(mc -> Journey.start(target));
            ctx.waitFor(mc -> {
                if (Journey.status() == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason());
                }
                double along = mc.player.getX() - (start.getX() + 0.5);
                if (mc.player.onGround() && along > 3.0 && along < length - 8.0) {
                    walking[0]++;
                    double sideways = mc.player.getZ() - Math.floor(mc.player.getZ()) - 0.5;
                    if (Math.abs(sideways) < CENTRE_BAND) {
                        centred[0]++;
                    }
                }
                return Journey.status() == Journey.Status.ARRIVED;
            });
            double share = walking[0] == 0 ? 1.0 : (double) centred[0] / walking[0];
            String summary = String.format("%.0f%% of %d walking ticks within %.2f of a block centre",
                    share * 100, walking[0], CENTRE_BAND);
            if (share > MAX_CENTRED_SHARE) {
                ctx.fail("the walk kept to the middle of the blocks: " + summary);
                return;
            }
            LOGGER.info("Journey walked its own line: {}", summary);
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * The detour slip, forced on every leg, across open ground where the
     * straight line is free: the walk has to leave that line by more than its
     * lane and heading noise ever take it, and still arrive.
     */
    @MinecraftTest(name = "Journey takes a detour when it slips", timeoutTicks = 2000, order = 59)
    public void journeyTakesADetour(TestContext ctx) {
        final int z0 = ORIGIN_Z + 320;
        final int length = 48;
        final int width = 16;
        final BlockPos start = new BlockPos(ORIGIN_X + 2, ORIGIN_Y + 1, z0 + width / 2);
        final BlockPos target = new BlockPos(ORIGIN_X + length - 6, ORIGIN_Y + 1, z0 + width / 2);
        try {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
            ctx.runCommand("tp @s " + (ORIGIN_X + length / 2 + 0.5) + " " + (ORIGIN_Y + 8) + " "
                    + (z0 + width / 2 + 0.5));
            ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(ORIGIN_X >> 4, z0 >> 4))
                    && MeshManager.isChunkLoaded(mc.level,
                            new ChunkCoordinate((ORIGIN_X + length) >> 4, (z0 + width) >> 4)));
            ctx.runCommand("fill " + ORIGIN_X + " " + ORIGIN_Y + " " + z0 + " "
                    + (ORIGIN_X + length) + " " + ORIGIN_Y + " " + (z0 + width) + " stone");
            ctx.runCommand("fill " + ORIGIN_X + " " + (ORIGIN_Y + 1) + " " + z0 + " "
                    + (ORIGIN_X + length) + " " + (ORIGIN_Y + 4) + " " + (z0 + width) + " air");
            ctx.waitFor(mc -> !mc.level.getBlockState(target.below()).isAir());
            standAt(ctx, start);

            final double[] widest = {0.0};
            ctx.runOnClient(mc -> Journey.start(target, new Journey.Quirks(1.0, 0.0, 0, 0)));
            ctx.waitFor(mc -> {
                if (Journey.status() == Journey.Status.FAILED) {
                    throw new AssertionError("journey failed: " + Journey.failReason());
                }
                widest[0] = Math.max(widest[0], Math.abs(mc.player.getZ() - (start.getZ() + 0.5)));
                return Journey.status() == Journey.Status.ARRIVED;
            });
            if (widest[0] < MIN_DETOUR_WIDTH) {
                ctx.fail(String.format("the walk never left its line: %.2f blocks at the widest, at least %.1f"
                        + " expected", widest[0], MIN_DETOUR_WIDTH));
                return;
            }
            LOGGER.info("Journey went round: {} blocks off the line at the widest",
                    String.format("%.2f", widest[0]));
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
        }
    }

    /**
     * How far off the straight line a detour has to take the walk: well past
     * the lane (0.35) plus the drift the heading noise is allowed (0.35).
     */
    private static final double MIN_DETOUR_WIDTH = 2.0;

    /** Sideways distance from a block centre that counts as walking down the middle. */
    private static final double CENTRE_BAND = 0.05;
    /** Share of a walk that may be spent down the middle of the blocks. */
    private static final double MAX_CENTRED_SHARE = 0.6;

    /** Horizontal speed under which a tick on the ground counts as standing still. */
    private static final double STANDSTILL_SPEED = 0.03;
    /** Longest standstill a walk down the terraces may have — a brief settle, not a stop. */
    private static final int MAX_STANDSTILL_TICKS = 10;
    /** Slowest a walk down the terraces may get on the ground before its arrival: about walking pace. */
    private static final double MIN_WALK_SPEED = 0.08;

    /**
     * Take-offs that landed lower than they left: a jump down a ledge, where a
     * person walks off it. A sprint-jump on level ground lands where it left,
     * a step up lands higher, and walking off an edge is no take-off at all.
     */
    private static final class LedgeJumps {
        final List<String> found = new ArrayList<>();
        private boolean wasOnGround = true;
        private double takeOffY = Double.NaN;
        private BlockPos takeOffAt;

        void sample(net.minecraft.client.player.LocalPlayer player) {
            boolean onGround = player.onGround();
            if (wasOnGround && !onGround && player.getDeltaMovement().y > 0.0) {
                takeOffY = player.getY();
                takeOffAt = player.blockPosition();
            } else if (!wasOnGround && onGround) {
                if (!Double.isNaN(takeOffY) && player.getY() < takeOffY - 0.5) {
                    found.add("from " + takeOffAt.toShortString() + " down to y=" + player.blockPosition().getY());
                }
                takeOffY = Double.NaN;
            }
            wasOnGround = onGround;
        }
    }

    /** How far off the bearing to the target the yaw may be and still count as on the line. */
    private static final float HEADING_TOLERANCE_DEG = 10.0f;
    /** Share of the walk the yaw has to stay on the line. */
    private static final double MIN_ON_HEADING_SHARE = 0.75;
    /** Largest step back along the line the walk may take. */
    private static final double MAX_STEP_BACK = 0.5;

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

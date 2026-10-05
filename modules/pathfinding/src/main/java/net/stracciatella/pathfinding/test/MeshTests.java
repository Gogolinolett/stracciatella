package net.stracciatella.pathfinding.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.MeshPathfinder;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.mesh.Mesh;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import net.stracciatella.pathfinding.travel.Journey;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for the mesh on terrain that is not a stone slab in the sky.
 *
 * <p>Every other suite in this module plans on fixtures that cannot show what is
 * wrong with a mesh: the PathWalker courses hand the walker its node list
 * directly, the journeys cross a flat three-wide bridge, and the bot tests build
 * their meshes before the bot needs them. Grass, a two-high corridor, an open
 * diagonal and a chunk corner were all broken at once, and none of them was ever
 * built. Each test here builds one and asks the mesh — or the journey — about it.
 */
@TestSuite(name = "Mesh Tests")
public class MeshTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("MeshTests");

    /**
     * Clear of the PathWalker courses (x 100-800), the bot (1000-1800), the
     * journeys (2000-2400) and the miners. Chunk-aligned: 4000 = 250 * 16.
     */
    private static final int ORIGIN_X = 4000;
    private static final int ORIGIN_Y = 50;
    private static final int ORIGIN_Z = 4000;

    /**
     * Short grass on every block, a flower and a torch on the line. None of it
     * is air, and all of it was a wall to the mesh: the floor under it had no
     * node, so the "path" came from the superflat ground a hundred blocks down.
     */
    @MinecraftTest(name = "Mesh walks across grass and flowers", timeoutTicks = 400, order = 60)
    public void walksAcrossVegetation(TestContext ctx) {
        int z = ORIGIN_Z + 8;
        BlockPos from = new BlockPos(ORIGIN_X + 2, ORIGIN_Y, z);
        BlockPos to = new BlockPos(ORIGIN_X + 13, ORIGIN_Y, z);
        prepare(ctx, from, to);
        fill(ctx, from.offset(0, 0, -1), to.offset(0, 0, 1), "grass_block");
        fill(ctx, from.offset(0, 1, -1), to.offset(0, 4, 1), "air");
        fill(ctx, from.offset(0, 1, -1), to.offset(0, 1, 1), "short_grass");
        ctx.runCommand("setblock " + (from.getX() + 4) + " " + (ORIGIN_Y + 1) + " " + z + " poppy");
        ctx.runCommand("setblock " + (from.getX() + 7) + " " + (ORIGIN_Y + 1) + " " + z + " torch");
        awaitBlock(ctx, from.offset(7, 1, 0), Blocks.TORCH);

        assertWalk(ctx, freshPath(ctx, from, to), from, to, 12);
    }

    /**
     * A one-wide corridor exactly two high — every tunnel a miner digs. A node
     * needs two free cells above its floor and a walk used to need three, so the
     * corridor had a node on every block and not one edge between them.
     */
    @MinecraftTest(name = "Mesh walks a two-high corridor", timeoutTicks = 400, order = 61)
    public void walksTwoHighCorridor(TestContext ctx) {
        int z = ORIGIN_Z + 24;
        BlockPos from = new BlockPos(ORIGIN_X + 2, ORIGIN_Y, z);
        BlockPos to = new BlockPos(ORIGIN_X + 11, ORIGIN_Y, z);
        prepare(ctx, from, to);
        fill(ctx, from.offset(0, 0, -1), to.offset(0, 3, 1), "stone");
        fill(ctx, from.above(), to.above(2), "air");
        awaitBlock(ctx, to.above(2), Blocks.AIR);

        assertWalk(ctx, freshPath(ctx, from, to), from, to, 10);
    }

    /**
     * Open flat ground, a dead-diagonal route. A (2,2) jump used to cost 25
     * against 26 for the two diagonal steps it replaces, so A* planned every
     * open diagonal as a chain of hops and the walker jumped them all.
     */
    @MinecraftTest(name = "Mesh plans an open diagonal without a jump", timeoutTicks = 400, order = 62)
    public void plansOpenDiagonalWithoutJump(TestContext ctx) {
        int z0 = ORIGIN_Z + 33;
        BlockPos min = new BlockPos(ORIGIN_X + 1, ORIGIN_Y, z0);
        BlockPos max = new BlockPos(ORIGIN_X + 13, ORIGIN_Y, z0 + 12);
        BlockPos from = new BlockPos(ORIGIN_X + 2, ORIGIN_Y, z0 + 1);
        BlockPos to = new BlockPos(ORIGIN_X + 12, ORIGIN_Y, z0 + 11);
        prepare(ctx, min, max);
        fill(ctx, min, max, "stone");
        fill(ctx, min.above(), max.above(4), "air");
        awaitBlock(ctx, max, Blocks.STONE);

        assertWalk(ctx, freshPath(ctx, from, to), from, to, 11);
    }

    /**
     * Four chunks around a corner, one floor across all of them, and every
     * walking link that crosses a chunk border has to exist. Borders used to be
     * relinked one neighbour at a time and each relink replaced a node's links
     * instead of adding to them, so a node near a corner kept only the links
     * into whichever neighbour came last. The meshing order is fixed so that a
     * corner node faces two meshed neighbours when the third one arrives.
     */
    @MinecraftTest(name = "Mesh links every chunk border at a corner", timeoutTicks = 400, order = 63)
    public void linksEveryChunkBorder(TestContext ctx) {
        final int cx = (ORIGIN_X >> 4) + 2;
        final int cz = (ORIGIN_Z >> 4) + 4;
        BlockPos min = new BlockPos(cx * 16, ORIGIN_Y, cz * 16);
        BlockPos max = new BlockPos(cx * 16 + 31, ORIGIN_Y, cz * 16 + 31);
        prepare(ctx, min, max);
        fill(ctx, min, max, "stone");
        fill(ctx, min.above(), max.above(4), "air");
        awaitBlock(ctx, max, Blocks.STONE);

        List<String> missing = ctx.computeOnClient(mc -> {
            MeshManager.meshes.remove(mc.player);
            int[][] order = {{1, 0}, {0, 1}, {0, 0}, {1, 1}};
            for (int[] c : order) {
                MeshManager.generateMesh(mc.level.getChunk(cx + c[0], cz + c[1]), mc.player);
            }
            var forPlayer = MeshManager.meshes.get(mc.player);
            List<String> out = new ArrayList<>();
            for (int[] c : order) {
                ChunkCoordinate coord = new ChunkCoordinate(cx + c[0], cz + c[1]);
                for (MeshNode node : forPlayer.get(coord).getNodes().values()) {
                    if (node.getY() != ORIGIN_Y) {
                        continue;
                    }
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos acrossPos = node.getBlockPos().relative(dir);
                        ChunkCoordinate acrossChunk = new ChunkCoordinate(acrossPos.getX() >> 4, acrossPos.getZ() >> 4);
                        Mesh other = forPlayer.get(acrossChunk);
                        if (acrossChunk.equals(coord) || other == null) {
                            continue;
                        }
                        MeshNode across = other.getNodes().get(acrossPos);
                        if (across != null && node.getNeighbors().stream()
                                .noneMatch(n -> n.getNode().equals(across))) {
                            out.add(node.getBlockPos().toShortString() + " -> " + acrossPos.toShortString());
                        }
                    }
                }
            }
            return out;
        });
        if (!missing.isEmpty()) {
            ctx.fail(missing.size() + " walking links across a chunk border are missing, e.g. "
                    + missing.subList(0, Math.min(5, missing.size())));
            return;
        }
        LOGGER.info("Every walking link across the corner of chunks {},{} exists", cx, cz);
    }

    /**
     * A staircase 24 blocks high, the target at the top. A journey meshed its
     * corridor over a band around the feet it started with, then skipped every
     * chunk that had a mesh — so everything above the first sixteen steps was
     * never scanned, the second leg found nothing closer, and the journey gave
     * up halfway up a staircase it could see the top of.
     */
    @MinecraftTest(name = "Journey climbs above the band it started with", timeoutTicks = 3000, order = 64)
    public void journeyClimbsAboveStartingBand(TestContext ctx) {
        final int steps = 24;
        final int y0 = 40;
        final int z = ORIGIN_Z + 120;
        final BlockPos start = new BlockPos(ORIGIN_X, y0 + 1, z);
        final BlockPos top = new BlockPos(ORIGIN_X + 2 * steps - 1, y0 + steps, z);
        try {
            prepare(ctx, new BlockPos(ORIGIN_X, y0, z), top);
            for (int i = 0; i < steps; i++) {
                int x = ORIGIN_X + 2 * i;
                fill(ctx, new BlockPos(x, y0, z - 1), new BlockPos(x + 1, y0 + i, z + 1), "stone");
                fill(ctx, new BlockPos(x, y0 + i + 1, z - 1), new BlockPos(x + 1, y0 + i + 4, z + 1), "air");
            }
            awaitBlock(ctx, top.below(), Blocks.STONE);

            // Survival: a creative player who presses jump twice within seven
            // ticks starts flying, and a staircase is a jump every two blocks.
            ctx.runCommand("tp @s " + (start.getX() + 0.5) + " " + start.getY() + " " + (start.getZ() + 0.5));
            ctx.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().distSqr(start) < 4.0);
            ctx.runCommand("gamemode survival");
            ctx.waitFor(mc -> !mc.player.getAbilities().instabuild);

            ctx.runOnClient(mc -> {
                MeshManager.meshes.remove(mc.player);
                Journey.start(top);
            });
            ctx.waitFor(mc -> Journey.status() != Journey.Status.RUNNING);
            if (Journey.status() != Journey.Status.ARRIVED) {
                ctx.fail("journey up the staircase failed: " + Journey.failReason());
                return;
            }
            double distance = ctx.computeOnClient(mc -> Math.sqrt(mc.player.blockPosition().distSqr(top)));
            if (distance > 2.5) {
                ctx.fail("journey reported arrival " + String.format("%.2f", distance) + " blocks from " + top);
                return;
            }
            LOGGER.info("Journey climbed {} steps to {}", steps, top);
        } finally {
            ctx.runOnClient(mc -> {
                Journey.stop();
                PathWalker.stop();
            });
            ctx.runCommand("gamemode creative");
        }
    }

    /**
     * Creative, nothing walking, and both ends of the fixture loaded on the
     * client — the player standing above it is what keeps it resident on the
     * server too, see {@code JourneyTests.buildBridge}.
     */
    private void prepare(TestContext ctx, BlockPos a, BlockPos b) {
        ctx.runOnClient(mc -> {
            Journey.stop();
            PathWalker.stop();
        });
        ctx.runCommand("gamemode creative");
        ctx.runCommand("tp @s " + ((a.getX() + b.getX()) / 2 + 0.5) + " " + (Math.max(a.getY(), b.getY()) + 8)
                + " " + ((a.getZ() + b.getZ()) / 2 + 0.5));
        ctx.waitFor(mc -> MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(a.getX() >> 4, a.getZ() >> 4))
                && MeshManager.isChunkLoaded(mc.level, new ChunkCoordinate(b.getX() >> 4, b.getZ() >> 4)));
    }

    private void fill(TestContext ctx, BlockPos a, BlockPos b, String block) {
        ctx.runCommand("fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " "
                + b.getX() + " " + b.getY() + " " + b.getZ() + " " + block);
    }

    /** Until the client sees the last edit of the fixture, the mesh would not. */
    private void awaitBlock(TestContext ctx, BlockPos pos, Block block) {
        ctx.waitFor(mc -> mc.level.getBlockState(pos).is(block));
    }

    /** A* between two floor blocks over meshes built from scratch, as positions. */
    private List<BlockPos> freshPath(TestContext ctx, BlockPos from, BlockPos to) {
        return ctx.computeOnClient(mc -> {
            MeshManager.meshes.remove(mc.player);
            MeshNode start = MeshManager.findOrBuildNearestNode(mc.level, mc.player, from);
            MeshNode end = MeshManager.findOrBuildNearestNode(mc.level, mc.player, to);
            List<BlockPos> path = new ArrayList<>();
            for (MeshNode node : new MeshPathfinder().findPath(start, end)) {
                path.add(node.getBlockPos());
            }
            return path;
        });
    }

    /**
     * The path runs from {@code from} to {@code to} on the fixture's own floor,
     * one walking step at a time, in exactly {@code nodes} nodes — the straight
     * walk, with nothing jumped and no detour.
     */
    private void assertWalk(TestContext ctx, List<BlockPos> path, BlockPos from, BlockPos to, int nodes) {
        if (path.isEmpty()) {
            ctx.fail("no path from " + from.toShortString() + " to " + to.toShortString());
            return;
        }
        if (!path.get(0).equals(from) || !path.get(path.size() - 1).equals(to)) {
            ctx.fail("path runs " + path.get(0).toShortString() + " -> "
                    + path.get(path.size() - 1).toShortString() + ", not between the fixture's ends "
                    + from.toShortString() + " -> " + to.toShortString());
            return;
        }
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            int gap = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
            if (gap > 1 || b.getY() != from.getY()) {
                ctx.fail("step " + i + " goes " + a.toShortString() + " -> " + b.toShortString()
                        + " — a jump or a level change on flat ground");
                return;
            }
        }
        if (path.size() != nodes) {
            ctx.fail("path has " + path.size() + " nodes where the straight walk has " + nodes + ": " + path);
            return;
        }
        LOGGER.info("Walked {} -> {} in {} nodes", from.toShortString(), to.toShortString(), path.size());
    }
}

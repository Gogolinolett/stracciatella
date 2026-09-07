package net.stracciatella.miner.test;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.miner.MinerSetup;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk miner tests. Each runs against a chunk that is empty apart from the
 * handful of blocks the test cares about: the behavior skips columns with
 * nothing to dig, so a sparse chunk exercises the same code path as a full
 * one in a fraction of the ticks. The layer range is pinned to one slab (two
 * for the descent test) so a run has a defined end instead of eating its way
 * down to bedrock.
 *
 * <p>Liquids are always placed on the bot's own side of the block being
 * mined. Sealing one means clicking the face of its neighbour *through* the
 * liquid cell, so anything standing between the bot and that face blocks the
 * aim — which in a real corridor never happens, because the corridor is
 * cleared towards the bot one column at a time.
 */
@TestSuite(name = "Chunk Miner Tests")
public class ChunkMinerTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkMinerTests");

    /** Chunk-aligned, so chunk-local offsets are just added to this. */
    private static final int BASE_X = 3008;
    private static final int BASE_Z = 3008;
    private static final int STAND_DX = 7;
    private static final int STAND_DZ = 7;
    private static final int Y = 40;

    // ================================================================
    // Test 1: clears the blocks of a single slab
    // ================================================================
    @MinecraftTest(name = "Chunk miner clears a slab", timeoutTicks = 1200, order = 10)
    public void clearsSlab(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        BlockPos lower = stand.offset(1, 0, 0);
        setBlock(ctx, lower, "stone");
        setBlock(ctx, lower.above(), "stone");

        runChunkMiner(ctx, Y + 1, Y, true);

        assertAir(ctx, lower, "lower slab block");
        assertAir(ctx, lower.above(), "upper slab block");
        LOGGER.info("Chunk miner slab clear test passed");
    }

    // ================================================================
    // Test 2: blacklisted blocks are left standing
    // ================================================================
    @MinecraftTest(name = "Chunk miner respects the blacklist", timeoutTicks = 1200, order = 11)
    public void respectsTheBlacklist(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        BlockPos protectedPos = stand.offset(1, 0, 0);
        BlockPos minedPos = stand.offset(2, 0, 0);
        setBlock(ctx, protectedPos, "gold_block");
        setBlock(ctx, minedPos, "stone");

        withBlacklisted("minecraft:gold_block", () -> runChunkMiner(ctx, Y + 1, Y, true));

        assertAir(ctx, minedPos, "non-blacklisted block");
        assertNotAir(ctx, protectedPos, "blacklisted gold block");
        LOGGER.info("Chunk miner blacklist test passed");
    }

    // ================================================================
    // Test 3: bedrock is skipped without being on the blacklist
    // ================================================================
    @MinecraftTest(name = "Chunk miner leaves bedrock", timeoutTicks = 1200, order = 12)
    public void leavesBedrock(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        BlockPos bedrock = stand.offset(1, 0, 0);
        BlockPos stone = stand.offset(2, 0, 0);
        setBlock(ctx, bedrock, "bedrock");
        setBlock(ctx, stone, "stone");

        runChunkMiner(ctx, Y + 1, Y, true);

        assertAir(ctx, stone, "stone next to the bedrock");
        assertNotAir(ctx, bedrock, "bedrock");
        LOGGER.info("Chunk miner bedrock test passed");
    }

    // ================================================================
    // Test 4: nothing outside the chunk is touched
    // ================================================================
    @MinecraftTest(name = "Chunk miner stays inside the chunk", timeoutTicks = 1500, order = 13)
    public void staysInsideTheChunk(TestContext ctx) {
        // Stand next to the border so the run needs no long navigation:
        // chunk-local x 15 is the last column inside, 16 is the next chunk.
        final BlockPos stand = prepare(ctx, 14, STAND_DZ);
        BlockPos inside = stand.offset(1, 0, 0);
        BlockPos outside = stand.offset(2, 0, 0);
        setBlock(ctx, inside, "stone");
        setBlock(ctx, outside, "stone");

        runChunkMiner(ctx, Y + 1, Y, true);

        assertAir(ctx, inside, "block on the chunk's last column");
        assertNotAir(ctx, outside, "block in the neighbouring chunk");
        LOGGER.info("Chunk miner boundary test passed");
    }

    // ================================================================
    // Test 5: digs down into the next slab
    // ================================================================
    @MinecraftTest(name = "Chunk miner descends a slab", timeoutTicks = 3000, order = 14)
    public void descendsSlab(TestContext ctx) {
        // A one-wide strip, not a patch: the floor of a slab is the *head
        // layer* of the slab below it, so everything laid at Y-1 becomes work
        // once the run descends — and a corridor is what the sweep actually
        // produces, whereas a patch leaves columns standing diagonally beside
        // the next target and hides it.
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 1, STAND_DX + 2, STAND_DZ, STAND_DZ);
        // Something to land on two levels down.
        fill(ctx, 5, Y - 3, 5, 10, Y - 3, 9, "stone");
        setBlock(ctx, stand.offset(1, 0, 0), "stone");

        runChunkMiner(ctx, Y + 1, Y - 2, true);

        assertAir(ctx, stand.offset(1, 0, 0), "first slab block");
        assertAir(ctx, stand.below(), "the block the bot dug through to descend");
        int feetY = ctx.computeOnClient(mc -> mc.player.blockPosition().getY());
        if (feetY > Y - 2) {
            throw new AssertionError("Bot did not descend: feet at y=" + feetY
                    + ", expected y=" + (Y - 2) + " or below");
        }
        LOGGER.info("Chunk miner descent test passed (feet at y={})", feetY);
    }

    // ================================================================
    // Test 6: a small water source is capped rather than dammed
    // ================================================================
    @MinecraftTest(name = "Chunk miner caps a water source", timeoutTicks = 2000, order = 15)
    public void capsWaterSource(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        // One cell of already-cleared corridor between the bot and the source,
        // the way a run actually meets one: the aim line to the support face
        // stays clear, and the liquid is two spread steps from the bot instead
        // of one.
        BlockPos water = stand.offset(2, 0, 0);
        setBlock(ctx, stand.offset(3, 0, 0), "stone");
        ctx.runCommand("give @s cobblestone 64");

        startAgainstLiquid(ctx, water, "water");
        awaitChunkMiner(ctx, true);

        boolean stillWater = ctx.computeOnClient(mc -> !mc.level.getFluidState(water).isEmpty());
        if (stillWater) {
            throw new AssertionError("Water source at " + water + " was never capped");
        }
        LOGGER.info("Chunk miner water test passed");
    }

    // ================================================================
    // Test 7: lava is dammed, never waited out
    // ================================================================
    @MinecraftTest(name = "Chunk miner dams lava", timeoutTicks = 2000, order = 16)
    public void damsLava(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        BlockPos lava = stand.offset(2, 0, 0);
        setBlock(ctx, stand.offset(3, 0, 0), "stone");
        ctx.runCommand("give @s cobblestone 64");

        startAgainstLiquid(ctx, lava, "lava");
        awaitChunkMiner(ctx, true);

        boolean stillLava = ctx.computeOnClient(mc -> !mc.level.getFluidState(lava).isEmpty());
        if (stillLava) {
            throw new AssertionError("Lava at " + lava + " was never dammed");
        }
        LOGGER.info("Chunk miner lava test passed");
    }

    // ================================================================
    // Test 8: a corridor of several columns — the steady-state loop
    // ================================================================

    /**
     * Every other test here is one column wide, which is exactly the case the
     * corridor loop never runs: with nothing left to plan, the controller
     * collects to completion and the run ends. This one digs four columns in
     * a row, so the miner plans the next column while the last one's drops are
     * still on the ground and COLLECTING hands off instead of finishing.
     *
     * <p>It asserts the cobblestone count, not just that the blocks are gone.
     * The hand-off deliberately leaves COLLECTING before the drops are in,
     * betting that opportunistic collection strafes to them during the next
     * break — and a bet on drops being picked up later is worth nothing
     * unless something counts them.
     */
    @MinecraftTest(name = "Chunk miner clears a corridor", timeoutTicks = 3000, order = 17)
    public void clearsCorridor(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        final int columns = 4;
        for (int dx = 1; dx <= columns; dx++) {
            setBlock(ctx, stand.offset(dx, 0, 0), "stone");
            setBlock(ctx, stand.offset(dx, 1, 0), "stone");
        }

        runChunkMiner(ctx, Y + 1, Y, true);

        for (int dx = 1; dx <= columns; dx++) {
            assertAir(ctx, stand.offset(dx, 0, 0), "corridor floor block " + dx);
            assertAir(ctx, stand.offset(dx, 1, 0), "corridor head block " + dx);
        }
        int mined = columns * 2;
        int timeout = 120 * net.stracciatella.testing.runner.TestRunner.getTickMultiplier();
        ctx.waitFor(mc -> countItem(mc, net.minecraft.world.item.Items.COBBLESTONE) >= mined,
                timeout);
        int collected = ctx.computeOnClient(
                mc -> countItem(mc, net.minecraft.world.item.Items.COBBLESTONE));
        LOGGER.info("Chunk miner corridor test passed ({} cobblestone)", collected);
    }

    private static int countItem(net.minecraft.client.Minecraft mc,
                                 net.minecraft.world.item.Item item) {
        var inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // --- Setup helpers ---

    private BlockPos prepare(TestContext ctx, int standDx, int standDz) {
        return prepareWithFloor(ctx, standDx, standDz, standDx - 3, standDx + 4,
                standDz - 3, standDz + 3);
    }

    /**
     * Empty the working chunk, lay a stone floor over the given chunk-local
     * rectangle at {@code Y - 1}, and put the player on it in survival with a
     * pickaxe. Returns the stand position.
     */
    private BlockPos prepareWithFloor(TestContext ctx, int standDx, int standDz,
                                      int fromDx, int toDx, int fromDz, int toDz) {
        final BlockPos stand = new BlockPos(BASE_X + standDx, Y, BASE_Z + standDz);
        ctx.runOnClient(mc -> {
            BehaviorRunner.stop();
            BotController.stop();
        });
        ctx.runCommand("clear @s");
        ctx.runCommand("kill @e[type=item]");
        ctx.runCommand("gamemode creative");
        teleportAndWaitForChunks(ctx, stand);

        // Clear the chunk plus a margin, so nothing from world generation
        // lands in a column the test never mentions. Done before the floor is
        // laid, so the player is never left standing over the cleared volume.
        fill(ctx, -1, Y - 4, -1, 17, Y + 6, 17, "air");
        fill(ctx, fromDx, Y - 1, fromDz, toDx, Y - 1, toDz, "stone");
        ctx.runCommand("give @s diamond_pickaxe");
        standAt(ctx, stand);
        return stand;
    }

    /** Fill a chunk-local box (dx/dz offsets, absolute y). */
    private void fill(TestContext ctx, int fromDx, int fromY, int fromDz,
                      int toDx, int toY, int toDz, String block) {
        ctx.runCommand("fill " + (BASE_X + fromDx) + " " + fromY + " " + (BASE_Z + fromDz) + " "
                + (BASE_X + toDx) + " " + toY + " " + (BASE_Z + toDz) + " " + block);
    }

    /**
     * Set a block and wait until the *client* sees it. A command is executed
     * server-side and the block update reaches the client a tick or two
     * later; the behavior reads the client's world, so starting a run without
     * this wait can survey a chunk that still looks empty and finish having
     * done nothing.
     */
    private void setBlock(TestContext ctx, BlockPos pos, String block) {
        ctx.runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " " + block);
        ctx.waitFor(mc -> !mc.level.getBlockState(pos).isAir());
    }

    private void runChunkMiner(TestContext ctx, int fromY, int toY, boolean expectSuccess) {
        startChunkMiner(ctx, fromY, toY);
        awaitChunkMiner(ctx, expectSuccess);
    }

    private void startChunkMiner(TestContext ctx, int fromY, int toY) {
        ctx.runOnClient(mc -> {
            MinerSetup.chunkMiner().setRequestedRange(true, fromY, toY);
            BehaviorRunner.start(MinerSetup.chunkMiner().id());
        });
    }

    private void awaitChunkMiner(TestContext ctx, boolean expectSuccess) {
        ctx.waitFor(mc -> !BehaviorRunner.isActive());
        ctx.runOnClient(mc -> BotController.stop());
        if (expectSuccess && ctx.computeOnClient(mc -> MinerSetup.chunkMiner().failed())) {
            throw new AssertionError("Chunk miner aborted: "
                    + ctx.computeOnClient(mc -> MinerSetup.chunkMiner().statusLine()));
        }
    }

    /**
     * Place a liquid source and start the run without letting a single fluid
     * tick pass in between.
     *
     * <p>A source in the open corridor is what the behavior is built for, but
     * it is also only a few spread steps from the bot's own cell. The setup
     * commands are wall-clock bound while the world runs at an accelerated
     * tick rate, so the gap between placing the liquid and the behavior's
     * first tick is worth hundreds of fluid ticks — and varies with load.
     * Measured: the bot was standing in {@code lava[level=2]} on the tick the
     * damage guard fired, with nothing mined, and no run that starts in lava
     * can be saved by damming. Freezing the world spends none of those ticks,
     * so the run always begins against exactly the liquid the test placed.
     */
    private void startAgainstLiquid(TestContext ctx, BlockPos liquid, String block) {
        ctx.runCommand("tick freeze");
        setBlock(ctx, liquid, block);
        startChunkMiner(ctx, Y + 1, Y);
        ctx.runCommand("tick unfreeze");
    }

    /** Run {@code body} with an extra blacklist entry, restored afterwards. */
    private void withBlacklisted(String id, Runnable body) {
        List<String> blacklist = MinerSetup.CONFIG.chunkMinerBlacklist;
        boolean added = !blacklist.contains(id);
        if (added) {
            blacklist.add(id);
        }
        try {
            body.run();
        } finally {
            if (added) {
                blacklist.remove(id);
            }
        }
    }

    private void assertAir(TestContext ctx, BlockPos pos, String what) {
        boolean air = ctx.computeOnClient(mc -> mc.level.getBlockState(pos).isAir());
        if (!air) {
            String block = ctx.computeOnClient(mc ->
                    mc.level.getBlockState(pos).getBlock().toString());
            throw new AssertionError(what + " at " + pos + " was not mined (still " + block + ")");
        }
    }

    private void assertNotAir(TestContext ctx, BlockPos pos, String what) {
        boolean air = ctx.computeOnClient(mc -> mc.level.getBlockState(pos).isAir());
        if (air) {
            throw new AssertionError(what + " at " + pos + " was mined but should have survived");
        }
    }

    private void standAt(TestContext ctx, BlockPos standPos) {
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + standPos.getY() + " "
                + (standPos.getZ() + 0.5) + " 0 0");
        ctx.waitFor(mc -> mc.player.onGround()
                && Math.abs(mc.player.getX() - (standPos.getX() + 0.5)) < 1.0
                && Math.abs(mc.player.getZ() - (standPos.getZ() + 0.5)) < 1.0);
        ctx.runCommand("gamemode survival");
        // See BotTests: without the ability sync the first breaks happen in
        // server-side creative and drop nothing.
        ctx.waitFor(mc -> !mc.player.getAbilities().instabuild);
    }

    private void teleportAndWaitForChunks(TestContext ctx, BlockPos anchor) {
        ctx.runCommand("tp @s " + (anchor.getX() + 0.5) + " " + anchor.getY() + " "
                + (anchor.getZ() + 0.5));
        ctx.waitFor(mc -> {
            if (mc.player == null || mc.level == null) {
                return false;
            }
            return mc.level.hasChunkAt(anchor)
                    && Math.abs(mc.player.position().x - (anchor.getX() + 0.5)) < 20
                    && Math.abs(mc.player.position().z - (anchor.getZ() + 0.5)) < 20;
        });
        ctx.runCommand("setblock " + anchor.getX() + " " + (anchor.getY() - 1) + " "
                + anchor.getZ() + " stone");
        ctx.runCommand("tp @s " + (anchor.getX() + 0.5) + " " + anchor.getY() + " "
                + (anchor.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());
    }
}

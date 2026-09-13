package net.stracciatella.miner.test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.task.MineBlockTask;
import net.stracciatella.miner.MinerConfig;
import net.stracciatella.miner.MinerSetup;
import net.stracciatella.miner.SpiralStairs;
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
 * down to bedrock. {@link #resumesCurrentLayer} is the exception: it requests
 * no range, because where an argument-less run puts the top of its range is
 * the thing it tests.
 *
 * <p>Liquids are always placed on the bot's own side of the block being
 * mined. Sealing one means clicking the face of its neighbour *through* the
 * liquid cell, so anything standing between the bot and that face blocks the
 * aim — which for a liquid never happens, because the corridor is cleared
 * towards the bot one column at a time.
 *
 * <p>That is a statement about the <em>mining</em> order, and it was read for
 * a long time as a statement about the chunk. It is not, and a sparse chunk
 * hides the difference: with nothing standing anywhere, no fixture here can
 * put a block between the bot and a face it has to click. A <b>floor fill</b>
 * is where the difference bites, because its support is chosen by
 * {@code findSupport} and not by the serpentine — so it can be two cells away
 * on a diagonal, with a column the sweep has not reached yet in the line.
 * {@link #fillsAHoleInASolidSlab} is the one dense fixture in this file and
 * exists for that; when adding a test about aiming, ask whether it needs to
 * be dense before reaching for {@code prepare}.
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

    /**
     * Fixture height for the three staircase tests, which is not free to be 40
     * like everything else here.
     *
     * <p>All three are built around the chunk's <em>minimum corner</em> — the one
     * cell with two faces pointing out of the chunk, which is what they are
     * about — so they need a fixture whose floor layer is the layer that owes
     * its step there. The ramp is pinned to the world's height grid (see
     * {@link SpiralStairs}), so that is a fact about the height and nothing
     * else: at {@link #Y} the step for the floor layer sits on the east edge
     * instead, and the corner would come out with the rest of the slab.
     *
     * <p>Derived rather than written down, so it follows the geometry if the
     * ramp is ever re-pinned: the first height at or above {@code Y} whose floor
     * layer is a corner layer.
     */
    private static final int STAIR_Y = Y + SpiralStairs.ringIndexAt(Y - 1);

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
     * still on the ground — the steady-state loop, where collecting and
     * planning overlap.
     *
     * <p>It asserts the cobblestone count, not just that the blocks are gone.
     * Everything that makes the loop fast — the opportunistic strafe during a
     * break, leaving COLLECTING as soon as the ground is clear — trades
     * against picking the drops up at all, and nothing else in the suite would
     * notice a corridor mined clean with the cobblestone left lying in it.
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

    // ================================================================
    // Test 9: the whole run — dig down into the hole, keep mining, hold the aim
    // ================================================================

    /**
     * Every test above asks the same question — is the block gone at the end? —
     * and none of them can fail on a bot that breaks one block, stares at a
     * wall for a thousand ticks and breaks the next. The annotation timeout
     * does not catch that either: {@code TestRunner} multiplies it by the tick
     * multiplier, so the corridor test's nominal 3000 ticks is a 30000-tick
     * budget for eight blocks, and the bot may idle 99% of it. Three
     * regressions walked past the suite through that hole — no arm swing while
     * breaking, drops abandoned on the floor, and a column scan that
     * ping-ponged the aim through 150 degrees between neighbouring blocks.
     *
     * <p>So this test measures the run rather than its result. It samples the
     * tick thread once per tick (via the {@code waitFor} predicate, which is
     * evaluated there anyway — logging per tick is not an option, it perturbs
     * the run enough to flip the corridor test) and turns four properties into
     * hard assertions:
     *
     * <ul>
     *   <li><b>Budget.</b> Its own limit in game ticks, derived from the block
     *       count, not the annotation. Stalling fails by timeout.
     *   <li><b>Duty cycle.</b> The share of ticks between the first and last
     *       swing in which the player is swinging its arm. That is the literal
     *       form of the requirement — the bot must hold left click — and it is
     *       vanilla state we only ever set through the vanilla call:
     *       {@code BlockInteractor} swings exactly when
     *       {@code continueDestroyBlock} reports it is still breaking, so
     *       before the swing was mirrored from {@code Minecraft.continueAttack}
     *       this number was flat zero for a whole run.
     *       <p>{@code MultiPlayerGameMode.isDestroying()} would look like the
     *       more direct sensor and is useless here: measured over 482 ticks of
     *       a passing run it was never once set. Vanilla's
     *       {@code handleKeybinds} calls {@code continueAttack(false)} every
     *       tick — no key is down in a test client — which calls
     *       {@code stopDestroyBlock} and clears the flag, and only
     *       {@code startDestroyBlock} ever sets it, never
     *       {@code continueDestroyBlock}. The flag survives one tick per block.
     *   <li><b>Gaps.</b> A break is allowed to be interrupted — a drop that
     *       landed out of reach has to be walked to, and
     *       {@code randomBreatherTicks} pauses 12-25 ticks with 4% probability
     *       per column by design. What is not allowed is that happening often.
     *   <li><b>Aim.</b> Degrees of yaw travelled per block mined, as a coarse
     *       guard — see {@link #holdsItsAim} for what that number can and
     *       cannot tell apart. A raw "never turn more than N degrees" would be
     *       wrong: the serpentine legitimately reverses at the end of a row.
     * </ul>
     *
     * <p>The range spans two slabs so the run has to dig through its own floor
     * and walk into the hole, which is where it failed in a real world.
     */
    @MinecraftTest(name = "Chunk miner mines without stalling", timeoutTicks = 4000, order = 18)
    public void minesWithoutStalling(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 1, STAND_DX + TRACE_COLUMNS + 1, STAND_DZ, STAND_DZ);
        // Something to land on once the lower slab's head layer — the floor the
        // corridor is standing on — has been mined away.
        fill(ctx, STAND_DX - 1, Y - 3, STAND_DZ,
                STAND_DX + TRACE_COLUMNS + 1, Y - 3, STAND_DZ, "stone");
        for (int dx = 1; dx <= TRACE_COLUMNS; dx++) {
            setBlock(ctx, stand.offset(dx, 0, 0), "stone");
            setBlock(ctx, stand.offset(dx, 1, 0), "stone");
        }
        // Upper slab: the corridor, two layers deep. Lower slab: the floor
        // strip, which is that slab's head layer and the only work left in it.
        final int floorBlocks = TRACE_COLUMNS + 3;
        final int blocks = TRACE_COLUMNS * 2 + floorBlocks;

        MiningTrace trace = traceChunkMiner(ctx, true, Y + 1, Y - 2, blocks);

        for (int dx = 1; dx <= TRACE_COLUMNS; dx++) {
            assertAir(ctx, stand.offset(dx, 0, 0), "corridor floor block " + dx);
            assertAir(ctx, stand.offset(dx, 1, 0), "corridor head block " + dx);
        }
        assertAir(ctx, stand.below(), "the block the bot dug through to descend");
        assertNotAir(ctx, stand.below(3), "the landing floor below the working range");

        // Walking into the hole is the point of the two-slab range: digging it
        // and then standing on the rim is the failure seen in a real world.
        if (trace.maxFeetY() > Y || trace.minFeetY() < Y - 2) {
            throw new AssertionError("Bot left the working range vertically: feet spanned y="
                    + trace.minFeetY() + ".." + trace.maxFeetY() + ", expected " + (Y - 2)
                    + ".." + Y + " — " + trace);
        }
        int feetY = ctx.computeOnClient(mc -> mc.player.blockPosition().getY());
        if (feetY > Y - 2) {
            throw new AssertionError("Bot never entered the hole it dug: feet at y=" + feetY
                    + ", expected y=" + (Y - 2) + " — " + trace);
        }
        assertMiningQuality(trace, blocks);
        LOGGER.info("Chunk miner stall test passed: {}", trace);
    }

    // ================================================================
    // Test 10: a run started mid-slab picks that slab back up
    // ================================================================

    /**
     * The behaviour keeps no cursor: {@code tickSelectSlab} re-derives the
     * working slab from the world every time it runs, taking the topmost one
     * that still holds something diggable. This test starts a run in a world
     * that looks like an interrupted one — upper slab cleared, the bot standing
     * in the lower slab with part of it already open — and asserts that the run
     * picks up where that bot stands.
     *
     * <p>The decisive measurement is the number of ticks before the first
     * break. Descending, re-surveying from the top or walking off to some other
     * column all cost far more than aiming at the block in front of the bot,
     * so a resume that is not a resume cannot pass this by accident. The feet
     * bound catches the same thing from the other side: the layer must not
     * change at all during the run.
     *
     * <p>It starts <b>without a requested range</b>, which every other test in
     * this file hands in, and that is the point: the range is what anchors the
     * slab grid, and deriving it from the bot is the half of the resume the
     * other tests skip. Handed {@code Y+1} the grid lined up whatever the
     * behaviour did with the bot's own position, so this test passed while
     * {@code /miner chunk start} — the only way a person starts a run — took
     * the feet layer as the top of the range, anchored the grid a level too
     * low and descended out of every half-finished slab it was restarted in.
     */
    @MinecraftTest(name = "Chunk miner resumes the layer it stands in",
            timeoutTicks = 2000, order = 19)
    public void resumesCurrentLayer(TestContext ctx) {
        // The floor strip is the lower slab's head layer, and after the two
        // columns below the bot are opened it is exactly RESUME_REMAINING long.
        prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 1, STAND_DX + RESUME_REMAINING, STAND_DZ, STAND_DZ);
        fill(ctx, STAND_DX - 1, Y - 3, STAND_DZ,
                STAND_DX + RESUME_REMAINING, Y - 3, STAND_DZ, "stone");
        // Open the part of the lower slab the interrupted run had already done,
        // and drop the bot into it. What is left is the strip ahead of it.
        fill(ctx, STAND_DX - 1, Y - 1, STAND_DZ, STAND_DX, Y - 1, STAND_DZ, "air");
        final BlockPos lowStand = new BlockPos(BASE_X + STAND_DX, Y - 2, BASE_Z + STAND_DZ);
        ctx.waitFor(mc -> mc.level.getBlockState(lowStand.above()).isAir());
        standAt(ctx, lowStand);

        // The bottom of an argument-less run is the config's, and the run has
        // to end for the trace to close, so it is pinned to this fixture's one
        // slab the way the other tests pin their range. Only the bottom: the
        // top is what is under test and must keep coming from the bot.
        final int configuredBottom = MinerSetup.CONFIG.chunkMinerBottomY;
        MinerSetup.CONFIG.chunkMinerBottomY = Y - 2;
        final MiningTrace trace;
        try {
            trace = traceChunkMiner(ctx, false, 0, 0, RESUME_REMAINING);
        } finally {
            MinerSetup.CONFIG.chunkMinerBottomY = configuredBottom;
        }

        if (trace.ticksToFirstBreak() < 0 || trace.ticksToFirstBreak() > RESUME_FIRST_BREAK_TICKS) {
            throw new AssertionError("Run did not resume the slab it started in: first break after "
                    + trace.ticksToFirstBreak() + " ticks, allowed " + RESUME_FIRST_BREAK_TICKS
                    + " — " + trace);
        }
        if (trace.minFeetY() != Y - 2 || trace.maxFeetY() != Y - 2) {
            throw new AssertionError("Run left the layer it resumed: feet spanned y="
                    + trace.minFeetY() + ".." + trace.maxFeetY() + ", expected y=" + (Y - 2)
                    + " throughout — " + trace);
        }
        for (int dx = 1; dx <= RESUME_REMAINING; dx++) {
            assertAir(ctx, lowStand.offset(dx, 1, 0), "remaining head block " + dx);
        }
        assertNotAir(ctx, lowStand.below(), "the landing floor below the working range");
        LOGGER.info("Chunk miner resume test passed: {}", trace);
    }

    // ================================================================
    // Test 11: the aim holds when every column is in reach at once
    // ================================================================

    /**
     * The stall test digs a corridor, so the bot walks and the column it should
     * take next is simply the one ahead — it never has to choose. This one lays
     * a single-layer patch reaching three columns to either side, so every
     * column is in reach and the order is the scan's own. One layer, because a
     * two-high column hides the one behind it and {@code isAimable} then defers
     * it, which imposes an order by itself and hides the choice.
     *
     * <p><b>What this does not do.</b> It was built to catch the scan that
     * shipped — {@code nextColumn} searching outward in both directions from
     * the bot's index, which in a real run took targets 3016, 3014, 3017 in a
     * row with 147, 151 and 145 degree turns between them — and measurement
     * says it does not. On this patch the broken scan costs 98 degrees per
     * block and the fixed one 81, against a run-to-run spread of about 11%.
     * No threshold separates those. A ring of eight columns was tried first and
     * was worse still: a circle costs a full turn in any order, 61 degrees per
     * block on both. So the assertion here is a coarse guard against an aim
     * that goes wild, not a regression test for column ordering; what actually
     * pins that behaviour down is the tick budget and the duty cycle.
     */
    @MinecraftTest(name = "Chunk miner holds its aim", timeoutTicks = 3000, order = 20)
    public void holdsItsAim(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        int blocks = 0;
        for (int dx = -AIM_REACH; dx <= AIM_REACH; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                setBlock(ctx, stand.offset(dx, 0, dz), "stone");
                blocks++;
            }
        }

        MiningTrace trace = traceChunkMiner(ctx, true, Y + 1, Y, blocks);

        for (int dx = -AIM_REACH; dx <= AIM_REACH; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                assertAir(ctx, stand.offset(dx, 0, dz), "patch block " + dx + "/" + dz);
            }
        }
        double yawPerBlock = trace.yawTravel() / blocks;
        if (yawPerBlock > MAX_YAW_PER_BLOCK) {
            throw new AssertionError("Aim was not steady: " + Math.round(yawPerBlock)
                    + " degrees of yaw per block, allowed " + Math.round(MAX_YAW_PER_BLOCK)
                    + " — " + trace);
        }
        LOGGER.info("Chunk miner aim test passed ({} deg/block): {}",
                Math.round(yawPerBlock), trace);
    }

    // ================================================================
    // Test 12: water in the next chunk is dammed, not refused
    // ================================================================

    /**
     * A chunk that borders water used to end the run: every cell the water
     * could flow in through lay in the next chunk, and the dam refused to
     * leave the chunk it was mining, so it had nothing to place and stopped.
     * The face beyond the border is the only thing that can hold the water
     * back — the body is too big to cap, and there is nothing inside the
     * chunk to dam.
     *
     * <p>The pocket is walled in on every other side, so the border column is
     * the only way into the chunk and the cell beyond it the only cell that
     * closes it.
     */
    @MinecraftTest(name = "Chunk miner dams beyond the chunk border",
            timeoutTicks = 2000, order = 21)
    public void damsBeyondTheChunkBorder(TestContext ctx) {
        // Chunk-local x 15 is the last column inside the chunk, 16 the first
        // one outside it.
        final BlockPos stand = prepare(ctx, 13, STAND_DZ);
        BlockPos corridor = stand.offset(1, 0, 0);
        BlockPos border = stand.offset(2, 0, 0);
        BlockPos water = stand.offset(3, 0, 0);
        // Stone first, water last: an open pocket floods the fixture while the
        // remaining setup commands are still on their way to the server.
        fill(ctx, 16, Y - 1, 6, 22, Y + 1, 8, "stone");
        setBlock(ctx, corridor, "stone");
        setBlock(ctx, border, "stone");
        setBlock(ctx, border.above(), "stone");
        // Six sources — past MAX_SEALABLE_SOURCES, so this is a dam and not a
        // capping run, which is the case that was allowed out of the chunk.
        fill(ctx, 16, Y, 7, 21, Y, 7, "water");
        ctx.waitFor(mc -> !mc.level.getFluidState(water).isEmpty());
        ctx.runCommand("give @s cobblestone 64");

        runChunkMiner(ctx, Y + 1, Y, true);

        boolean sealed = ctx.computeOnClient(mc -> mc.level.getFluidState(water).isEmpty());
        if (!sealed) {
            throw new AssertionError("Water at " + water + " was never dammed");
        }
        // What ran in while the wall was down has no source behind it any more
        // and drains by itself, a handful of ticks past the last block — so the
        // corridor is checked after the drain, not on the tick the run ends.
        ctx.waitFor(mc -> mc.level.getFluidState(border).isEmpty()
                && mc.level.getFluidState(corridor).isEmpty());
        assertAir(ctx, border, "the chunk's last column");
        LOGGER.info("Chunk miner border dam test passed");
    }

    // ================================================================
    // Test 12: the floor under the next column is missing
    // ================================================================

    /**
     * A hole under the column ahead. The fill cannot go in before that column
     * comes out — the column is the roof over the hole, so nowhere the bot can
     * stand has a line to any face of it, and findSupport hands back the
     * column's own underside, which a ray from the side never meets. A run
     * that tried died on three look timeouts with 718 blocks behind it.
     *
     * <p>The hole is three cells deep, because two is a depth the bot is
     * *allowed* to enter: {@code hasFloorWithinOneBlock} tolerates a one-block
     * drop, which is a step and not a fall. Its floor is the fixture's own
     * block, so nothing under the cleared band matters.
     *
     * <p>The column sits three over rather than next to the bot, so the run
     * has to cover the distance the way a real one does: at 3.0 blocks the
     * column itself is inside {@code reachDistance} 4.0 and is mined without a
     * step, and what closes the remaining gap is the collect walk going after
     * the drops that fell into the hole.
     *
     * <p>The feet check is the second assertion, and it is a guard rather than
     * a reproduction. <b>It does not fail on the pre-fix code.</b> Measured:
     * with the POSITIONING ledge check removed the bot still stops at the rim,
     * because COLLECTING refuses the step first and the placement is in reach
     * from there ({@code place 3019, 39, 3015 ... dist=2,87}) — so POSITIONING
     * never runs in this geometry, at one cell of depth or at three. The fall
     * this was written for happened in a real world against a cave, and no
     * single-cell fixture reproduced it. What the assertion is worth is the
     * other direction: it fails the moment anything lets the bot into the hole
     * it is about to fill, which is the cell it then cannot place into.
     */
    @MinecraftTest(name = "Chunk miner fills a hole under the next column",
            timeoutTicks = 2000, order = 22)
    public void fillsHoleUnderNextColumn(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        final BlockPos over = stand.offset(3, 0, 0);
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        setBlock(ctx, over.below(4), "stone");
        fill(ctx, STAND_DX + 3, Y - 3, STAND_DZ, STAND_DX + 3, Y - 1, STAND_DZ, "air");
        ctx.waitFor(mc -> mc.level.getBlockState(over.below()).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertAir(ctx, over, "the column over the hole");
        assertAir(ctx, over.above(), "the head block of the column over the hole");
        assertNotAir(ctx, over.below(), "the floor under the column over the hole");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot went into the hole instead of filling it from the rim:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        LOGGER.info("Chunk miner hole test passed");
    }

    // ================================================================
    // Test 13: a cave under the slab is stopped at, not walked into
    // ================================================================

    /**
     * The floor under the column ahead is not a hole but the roof of a cave,
     * and that is the case the single-cell fixture above does not cover. The
     * difference is what the cell to fill has to offer as a support: over a
     * cave every neighbour of it is open except one — the block the bot is
     * standing on — and that one cannot be aimed at. Its face toward the cell
     * is vertical, under the bot's own feet, and every ray from an eye above
     * the rim meets the block's <i>top</i> face first, which the placement
     * gate rejects because the direction decides where the block would land
     * ({@code face=east ... hitResult=... (up)} in the log). Three look
     * timeouts later the run stops with {@code could not place the floor}.
     *
     * <p>The stop is all this covers, and it is worth saying what it does
     * <b>not</b>: the bot does not fall here, with or without the fix, so
     * this fixture never reproduced the reported fall. The support is right
     * under the bot, so it is in reach and POSITIONING is never entered —
     * and the {@code approachOccluded} re-approach cannot enter it either,
     * because its arrival test is a sight line to the block's <i>centre</i>,
     * which is clear the whole time; only the face is not. The fall needs a
     * support the bot has to <i>walk</i> to, which is
     * {@code Bot refuses to walk off a ledge} in {@code BotTests} — the
     * defect was in {@code BotController}, so the test that proves it is
     * there too.
     *
     * <p>The run itself is allowed to fail or succeed. Over a cavity wider
     * than one block it still stops, because the support the fill needs is
     * then on the far rim and out of reach; where the gap is narrow enough
     * the controller now bridges it, crouching out past the edge to click the
     * side face of the block underfoot (see the bot module's <i>Bridging</i>).
     * Pinning either outcome here would make the other one look like a
     * regression, so this asserts the only thing that is true of both: the
     * bot is still on the slab.
     */
    @MinecraftTest(name = "Chunk miner stops at a cave instead of walking in",
            timeoutTicks = 3000, order = 23)
    public void stopsAtACaveInsteadOfWalkingIn(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        final BlockPos over = stand.offset(2, 0, 0);
        // Open the floor from the column onward, three cells deep and wide
        // enough on every side that nothing but the rim block is left to build
        // against — a hole with a far wall would be the other test's case.
        fill(ctx, STAND_DX + 2, Y - 3, STAND_DZ - 2, STAND_DX + 4, Y - 1, STAND_DZ + 2, "air");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.waitFor(mc -> mc.level.getBlockState(over.below()).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, false);

        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot walked into the cave: feet reached y=" + lowestFeetY[0]
                    + ", expected to stay at y=" + Y + " and stop at the rim");
        }
        LOGGER.info("Chunk miner cave test passed (feet stayed at y={})", lowestFeetY[0]);
    }

    // --- Run quality ---

    /** Corridor length for the stall test — long enough for a steady state. */
    private static final int TRACE_COLUMNS = 5;
    /** Blocks left standing ahead of the bot in the resume test. */
    private static final int RESUME_REMAINING = 4;
    /**
     * How far to either side the aim test's patch reaches. Three columns out
     * plus one row off the axis is 3.16 blocks away, inside the 4.0 reach, so
     * the bot never has to take a step to finish the patch.
     */
    private static final int AIM_REACH = 3;

    // The numbers below are TARGETS, not calibrations. Earlier revisions set
    // them from what the miner happened to do, which made the suite certify
    // the very slowness it was written to expose — a test that measures the
    // implementation instead of the requirement can only ever agree with it.
    // They are derived from the game's own constants and from the intended
    // design (walk while mining, one aim angle covering both blocks of a
    // column, collect during the break). The miner does not meet them yet;
    // the gap each one currently shows is recorded beside it.

    /**
     * The pace to beat, taken from a person doing the job by hand: <b>16 stone
     * blocks with a pickaxe in 10 seconds</b>, so 200 ticks, 12.5 per block.
     * The budget allows 220 for that same work — a tenth on top.
     *
     * <p>A human reference beats a derived one here because it settles what
     * the game actually permits, and it happens to corroborate the arithmetic:
     * 16 blocks of stone is 96 ticks of pure breaking at {@code 8/1.5/30}, so
     * the other 104 ticks are the overhead a person cannot avoid either —
     * essentially the 5-tick {@code destroyDelay} vanilla sets the moment a
     * block completes, plus the mouse travel that happens <i>during</i> it.
     * That is the whole difference between 12.5 and the bot's 20.8: the bot
     * pays the cooldown inside INTERACTING and then aims separately in
     * LOOKING, where a player holding the button spends the same ticks once.
     */
    private static final int REFERENCE_BLOCKS = 16;
    private static final int REFERENCE_BUDGET_TICKS = 220;
    /**
     * Share of the mining window in which a block is actually losing hardness.
     * At the human pace of 12.5 ticks per block with 5-7 of them real
     * breaking, 40% is what "always mining" can mean once vanilla's post-break
     * cooldown is paid — a person hits about the same share.
     *
     * <p>Currently measured: about 24% — 6.6 ticks of progress inside 27.
     */
    private static final double MIN_DUTY_CYCLE = 0.40;
    /**
     * Share of the same window in which the bot holds left click. Separate
     * from the duty cycle on purpose: this is the one that goes to zero when
     * the swing mirrored from {@code Minecraft.continueAttack} is dropped,
     * which is the regression that started this work, and progress alone would
     * not notice it. Currently measured: 68-77%, the shortfall being the aim
     * and collect stretches where the button is genuinely released.
     */
    private static final double MIN_SWING_CYCLE = 0.90;
    /**
     * A gap longer than this counts as an interruption rather than a beat. The
     * bound is the longest pause the design actually sanctions:
     * {@code randomBreatherTicks} may hold for 25 ticks on 4% of columns, and
     * a reaction delay may add 10. Anything past 35 is not a pause the
     * behaviour chose, it is the bot failing to get back to work.
     *
     * <p>Currently measured: gaps of 16-24 in the corridor, up to 74 on the
     * aim patch.
     */
    private static final int LONG_GAP_TICKS = 35;
    /**
     * How many such interruptions a whole run may contain. Not zero: a drop
     * that landed out of reach has to be walked to, and that is the one break
     * in mining the miner is allowed to take.
     */
    private static final int MAX_LONG_GAPS = 1;
    /**
     * Yaw a block may cost. The design is one aim angle per column that reaches
     * both its blocks, and a serpentine that turns once per row rather than per
     * column — so a column costs one turn to the next column's centre, and a
     * block costs half of that. Forty-five degrees per block allows a full
     * ninety-degree reorientation for every column, which is already the
     * corner case rather than the rule.
     *
     * <p>Currently measured: 71-92 over the corridor, 81-92 over the aim patch.
     * The bound is not a detector for column ordering — {@link #holdsItsAim}
     * carries the measurement that rules that out.
     */
    private static final double MAX_YAW_PER_BLOCK = 45.0;
    /**
     * Ticks a resumed run may spend before its first swing at a block. The
     * block is already in front of the bot, so the honest cost is one reaction
     * delay (10 at most) plus the aim — nothing that justifies a wait measured
     * in hundreds. Currently measured: 9-10.
     */
    private static final int RESUME_FIRST_BREAK_TICKS = 40;
    /**
     * Share of the mined blocks whose drop must be in the inventory when the
     * run ends. Not all of them — a drop that landed out of reach is expressly
     * allowed to be left rather than mined around — but "very rare" is the
     * wording of the requirement, so roughly one in ten is the outer edge of
     * it. Demanding every single one is what makes the corridor test flaky.
     *
     * <p>Currently measured: 16-17 of 18 over the corridor, 4 of 4 over the
     * resume run. The ones that go missing fall into the hole during the
     * descent.
     */
    private static final double MIN_COLLECTED_FRACTION = 0.90;

    private void assertMiningQuality(MiningTrace trace, int blocks) {
        if (trace.swingCycle() < MIN_SWING_CYCLE) {
            throw new AssertionError("Bot did not hold left click: swinging "
                    + percent(trace.swingCycle()) + " of the mining window, required "
                    + percent(MIN_SWING_CYCLE) + " — " + trace);
        }
        if (trace.dutyCycle() < MIN_DUTY_CYCLE) {
            throw new AssertionError("Bot was not mining often enough: blocks were losing "
                    + "hardness on " + percent(trace.dutyCycle()) + " of ticks, required "
                    + percent(MIN_DUTY_CYCLE) + " — " + trace);
        }
        if (trace.longGaps() > MAX_LONG_GAPS) {
            throw new AssertionError("Bot stopped mining too often: " + trace.longGaps()
                    + " gaps over " + LONG_GAP_TICKS + " ticks, allowed " + MAX_LONG_GAPS
                    + " — " + trace);
        }
        double yawPerBlock = trace.yawTravel() / blocks;
        if (yawPerBlock > MAX_YAW_PER_BLOCK) {
            throw new AssertionError("Aim was not steady: " + Math.round(yawPerBlock)
                    + " degrees of yaw per block, allowed " + Math.round(MAX_YAW_PER_BLOCK)
                    + " — " + trace);
        }
        int required = (int) Math.ceil(blocks * MIN_COLLECTED_FRACTION);
        if (trace.collected < required) {
            throw new AssertionError("Bot left its drops behind: collected " + trace.collected
                    + " of " + blocks + " mined, required " + required + " — " + trace);
        }
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /**
     * Start a run and record what the bot does on every tick of it, under a
     * tick budget of the test's own. The annotation timeout is not a budget:
     * {@code TestRunner} scales it by the tick multiplier, so it is ten times
     * looser than it reads and exists to stop a hung suite, not to judge a run.
     */
    private MiningTrace traceChunkMiner(TestContext ctx, boolean ranged, int fromY, int toY,
                                        int blocks) {
        final MiningTrace trace = new MiningTrace();
        final int budget = Math.ceilDiv(blocks * REFERENCE_BUDGET_TICKS, REFERENCE_BLOCKS);
        startChunkMiner(ctx, ranged, fromY, toY);
        try {
            ctx.waitFor(mc -> {
                trace.sample(mc);
                return !BehaviorRunner.isActive();
            }, budget);
        } catch (AssertionError e) {
            // Counted before stopping here too. Reporting collected=0 on a
            // timeout would read as "picked nothing up" when it only means
            // "never got as far as counting".
            trace.collected = ctx.computeOnClient(
                    mc -> countItem(mc, net.minecraft.world.item.Items.COBBLESTONE));
            trace.mined = ctx.computeOnClient(mc -> MinerSetup.chunkMiner().blocksMined());
            ctx.runOnClient(mc -> {
                BehaviorRunner.stop();
                BotController.stop();
            });
            String message = e.getMessage();
            if (message == null || !message.startsWith("Timed out")) {
                throw e;
            }
            throw new AssertionError("Chunk miner managed " + trace.mined + " of " + blocks
                    + " blocks in " + budget + " ticks (" + trace.ticksPerBlock()
                    + " per block) — a person mines " + REFERENCE_BLOCKS + " stone in "
                    + (REFERENCE_BUDGET_TICKS - 20) + " ticks, "
                    + (REFERENCE_BUDGET_TICKS - 20) / REFERENCE_BLOCKS + " per block — " + trace);
        }
        // Counted before the bot is stopped, and never waited for afterwards:
        // a stopped bot cannot walk to a drop, so anything that arrives later
        // arrived by luck. What the run collected by the time it ended is the
        // number the miner is actually accountable for.
        trace.collected = ctx.computeOnClient(
                mc -> countItem(mc, net.minecraft.world.item.Items.COBBLESTONE));
        trace.mined = ctx.computeOnClient(mc -> MinerSetup.chunkMiner().blocksMined());
        ctx.runOnClient(mc -> BotController.stop());
        if (ctx.computeOnClient(mc -> MinerSetup.chunkMiner().failed())) {
            throw new AssertionError("Chunk miner aborted: "
                    + ctx.computeOnClient(mc -> MinerSetup.chunkMiner().statusLine())
                    + " — " + trace);
        }
        return trace;
    }

    /**
     * What the bot did, tick by tick. Sampled on the tick thread and therefore
     * kept to field arithmetic — this is the run's critical path.
     *
     * <p>Gaps are counted only once mining has resumed after them, so the tail
     * of the run — collecting the last drops, the survey that ends it — is not
     * mistaken for a stall. The stretch before the first break is not a gap
     * either; {@link #ticksToFirstBreak()} covers it on its own.
     */
    private static final class MiningTrace {
        private int ticks;
        private int swingTicks;
        /** Swing ticks as of the last progress tick — the window's own count. */
        private int swingTicksInWindow;
        private int firstBreakTick = -1;
        private int lastBreakTick = -1;
        private int gap;
        private int maxGap;
        private int longGaps;
        private double yawTravel;
        private double maxYawStep;
        private float lastYaw;
        private boolean yawSeen;
        private int minFeetY = Integer.MAX_VALUE;
        private int maxFeetY = Integer.MIN_VALUE;
        /** Cobblestone in the inventory when the run ended. Not sampled. */
        private int collected;
        /** Blocks the behaviour reports broken. Not sampled. */
        private int mined;
        /**
         * Ticks spent in each controller phase. This is what makes a budget
         * failure actionable: "too slow" is not a finding, "nineteen of every
         * twenty-five ticks went somewhere other than INTERACTING" is.
         */
        private final int[] phaseTicks = new int[BotController.Phase.values().length];
        /** Ticks a block was actually losing hardness. */
        private int progressTicks;
        /**
         * The two halves of the idle time inside INTERACTING, summed over every
         * visit: {@code lead} runs from entering the phase to the first tick the
         * block loses hardness, {@code tail} from the last such tick to leaving.
         * Six ticks of stone is a game constant and nothing can be won there —
         * these two are the entire cost the phase adds on top of it, and they
         * have separate causes (getting the attack going versus confirming the
         * break), so a single "idle" number would name neither.
         */
        private int leadTicks;
        private int tailTicks;
        private int spans;
        private BotController.Phase prevPhase;
        private int spanTicks;
        private int spanFirst = -1;
        private int spanLast;

        void sample(Minecraft mc) {
            LocalPlayer player = mc.player;
            if (player == null) {
                return;
            }
            ticks++;
            float yaw = player.getYRot();
            if (yawSeen) {
                double step = Math.abs(Mth.degreesDifference(lastYaw, yaw));
                yawTravel += step;
                maxYawStep = Math.max(maxYawStep, step);
            }
            lastYaw = yaw;
            yawSeen = true;
            int feetY = player.blockPosition().getY();
            minFeetY = Math.min(minFeetY, feetY);
            maxFeetY = Math.max(maxFeetY, feetY);
            BotController.Phase phase = BotController.getPhase();
            phaseTicks[phase.ordinal()]++;
            // Only from the first break onwards, and the figure that counts is
            // the one snapshotted at the last break below. Both shares are
            // measured over the same window, so counting swings across the
            // whole run would put ticks in the numerator that the denominator
            // never saw — it read as 109% before this.
            if (player.swinging && firstBreakTick >= 0) {
                swingTicks++;
            }

            // Real progress on a block, which is a different question from
            // whether the bot looks busy. getDestroyStage is
            // `destroyProgress > 0 ? (int)(progress * 10) : -1` with no
            // isDestroying guard, so it is exactly "a break is under way".
            //
            // The distinction is not academic. continueDestroyBlock returns
            // true — and BlockInteractor therefore swings — while
            // destroyDelay is still draining, and vanilla sets that to 5 after
            // every completed block. Measured: 128 swinging ticks against 46
            // making progress. Judging the miner by the swing would score
            // those idle ticks as mining.
            boolean progressing = mc.gameMode != null && mc.gameMode.getDestroyStage() >= 0;
            if (progressing) {
                progressTicks++;
                if (firstBreakTick < 0) {
                    firstBreakTick = ticks;
                } else if (gap > 0) {
                    maxGap = Math.max(maxGap, gap);
                    if (gap > LONG_GAP_TICKS) {
                        longGaps++;
                    }
                }
                lastBreakTick = ticks;
                swingTicksInWindow = swingTicks;
                gap = 0;
            } else if (firstBreakTick >= 0) {
                gap++;
            }

            // Split the phase's idle time at its two ends. A visit that never
            // made progress is left out of both sums rather than counted as
            // one long lead: it is a failed break, a different fault, and
            // averaging it into the lead would hide how the normal ones went.
            if (phase == BotController.Phase.INTERACTING) {
                if (prevPhase != BotController.Phase.INTERACTING) {
                    spanTicks = 0;
                    spanFirst = -1;
                    spanLast = 0;
                }
                spanTicks++;
                if (progressing) {
                    if (spanFirst < 0) {
                        spanFirst = spanTicks;
                    }
                    spanLast = spanTicks;
                }
            } else if (prevPhase == BotController.Phase.INTERACTING && spanFirst >= 0) {
                leadTicks += spanFirst - 1;
                tailTicks += spanTicks - spanLast;
                spans++;
            }
            prevPhase = phase;
        }

        /** Ticks from the run's start to the first tick it spent mining. */
        int ticksToFirstBreak() {
            return firstBreakTick;
        }

        /**
         * Share of the mining window in which a block was actually losing
         * hardness. The window ends at the last such tick, not at the run's
         * end: the closing collect is work the run owes, not time spent idle.
         */
        double dutyCycle() {
            if (firstBreakTick < 0) {
                return 0.0;
            }
            return (double) progressTicks / (lastBreakTick - firstBreakTick + 1);
        }

        /** Ticks the run spent per block actually broken, to one decimal. */
        String ticksPerBlock() {
            if (mined <= 0) {
                return "n/a";
            }
            return String.valueOf(Math.round(ticks * 10.0 / mined) / 10.0);
        }

        /** Share of the same window in which the bot held left click. */
        double swingCycle() {
            if (firstBreakTick < 0) {
                return 0.0;
            }
            return (double) swingTicksInWindow / (lastBreakTick - firstBreakTick + 1);
        }

        int longGaps() {
            return longGaps;
        }

        double yawTravel() {
            return yawTravel;
        }

        int minFeetY() {
            return minFeetY;
        }

        int maxFeetY() {
            return maxFeetY;
        }

        @Override
        public String toString() {
            return "ticks=" + ticks + " breaking=" + progressTicks
                    + " duty=" + percent(dutyCycle())
                    + " swinging=" + swingTicksInWindow + " swingDuty=" + percent(swingCycle())
                    + " firstBreak=" + firstBreakTick + " lastBreak=" + lastBreakTick
                    + " maxGap=" + maxGap + " longGaps=" + longGaps
                    + " yaw=" + Math.round(yawTravel) + "deg"
                    + " maxYawStep=" + Math.round(maxYawStep) + "deg"
                    + " feetY=" + minFeetY + ".." + maxFeetY
                    + " mined=" + mined + " perBlock=" + ticksPerBlock()
                    + " lead=" + leadTicks + " tail=" + tailTicks + " spans=" + spans
                    + " collected=" + collected
                    + " phases=" + phaseHistogram();
        }

        private String phaseHistogram() {
            StringBuilder sb = new StringBuilder("[");
            for (BotController.Phase p : BotController.Phase.values()) {
                int spent = phaseTicks[p.ordinal()];
                if (spent == 0) {
                    continue;
                }
                if (sb.length() > 1) {
                    sb.append(' ');
                }
                sb.append(p).append('=').append(spent);
            }
            return sb.append(']').toString();
        }
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

    // ================================================================
    // Test 14: a hole under the column the snake reaches diagonally
    // ================================================================

    /**
     * Every other fixture in this file is sparse, and that is what let this
     * through. The serpentine keeps each <em>mining</em> step adjacent, so
     * nothing stands between the bot and a column it is about to break — but
     * a floor fill does not get its target from the serpentine. It gets it
     * from {@code findSupport}, which ranks the neighbours of the hole by how
     * directly their face is turned toward the eye and therefore picks the
     * <em>far</em> side of it, two cells out. In a sparse chunk there is
     * nothing in between. In a real one there is a column the sweep has not
     * reached yet.
     *
     * <p>The layout is taken from the run that reported it, column for column.
     * The bot stands at chunk-local (14, 2), which is index 46 of the snake;
     * (15, 2) is the last of that row and holds nothing, so the scan steps
     * past it to (15, 3) — the first column of the next row and
     * <em>diagonal</em> to the bot, exactly the case { nextColumn}'s
     * own notes describe. Opening it exposes the hole under it; the support
     * for that fill is (15, 4), and the ray to its north face crosses (14, 3),
     * the column orthogonally beside the bot, which is still standing. The
     * reported run ended there: {@code could not place the floor}, one block
     * mined.
     *
     * <p>Two blocks deep, not three, so the drop stays inside
     * {@code MAX_SAFE_DROP} and this exercises {@code ensureFloor} rather than
     * {@code ensureSafeDrop} — the path the report came down.
     *
     * <p>Asserts the run finishes and the hole is closed. <em>How</em> is
     * deliberately not asserted: walking clear of the neighbour, clicking the
     * near face instead, and a crouched edge step are all legitimate, and
     * pinning one would make the others look like regressions.
     */
    @MinecraftTest(name = "Chunk miner fills a hole behind a standing neighbour",
            timeoutTicks = 3000, order = 24)
    public void fillsAHoleBehindAStandingNeighbour(TestContext ctx) {
        final int standDx = 14;
        final int standDz = 2;
        prepare(ctx, standDx, standDz);
        final BlockPos hole = new BlockPos(BASE_X + 15, Y - 1, BASE_Z + 3);

        // Solid, which no other fixture here is: the neighbour that ends up in
        // the line of sight only exists because the chunk is not empty.
        fill(ctx, 13, Y, 1, 16, Y + 1, 4, "stone");
        // The bot's own two cells.
        fill(ctx, standDx, Y, standDz, standDx, Y + 1, standDz, "air");
        // The column the snake steps past — nothing to dig — which is what
        // leaves the bot standing diagonally to the one it takes instead.
        fill(ctx, 15, Y, 2, 15, Y + 1, 2, "air");
        // The hole under that one, floored two down so the drop is survivable
        // and the fill is ensureFloor's rather than ensureSafeDrop's.
        fill(ctx, 15, Y - 2, 3, 15, Y - 1, 3, "air");
        fill(ctx, 15, Y - 3, 3, 15, Y - 3, 3, "stone");

        startChunkMiner(ctx, true, Y + 1, Y);
        awaitChunkMiner(ctx, true);

        boolean filled = ctx.computeOnClient(mc -> !mc.level.getBlockState(hole).isAir());
        if (!filled) {
            throw new AssertionError("The floor at " + hole.toShortString()
                    + " is still open: " + ctx.computeOnClient(
                            mc -> mc.level.getBlockState(hole).getBlock()));
        }
        LOGGER.info("Standing-neighbour test passed (floor at {} closed)", hole.toShortString());
    }

    // ================================================================
    // Test 15: a gap on the way to the next column is bridged
    // ================================================================

    /**
     * A trench between the bot and the column it has to mine next. Reported
     * from a real world, slab y=92..93: two columns came out from 3.6 and 3.8
     * blocks away without the bot taking a step, the next one stood at 4.77,
     * and the run died with {@code cannot break -27, 93, -61} after six tasks
     * that each ended where they began — {@code player=(-22.23, 92.00,
     * -62.00)} in every one of them, {@code pathActive=false crouched=false
     * under=stone}. Nothing was wrong with the aim or the target. The bot
     * could not walk there: POSITIONING refuses a step over a cell with
     * nothing under it, and no one filled that cell.
     *
     * <p>The trench is <b>two</b> columns wide, and the width is the point. At
     * one column the far rim is still a neighbour of the cell to fill, and
     * {@code findSupport} ranks it above the block underfoot — the fill then
     * happens as an ordinary placement across the gap and proves nothing about
     * bridging. At two, the first cell's only sturdy neighbour is the block
     * the bot is standing on; its face is horizontal and below the eye, so the
     * placement can be reached from nowhere except past the rim. That is the
     * edge step, and it is why {@code crouchedPastRim} is asserted: a run that
     * closed the trench some other way fails here.
     *
     * <p>Five blocks to the column, deliberately. Past {@code reachDistance}
     * 4.0 so the bot has to walk at all, and inside the 6.5 window where
     * {@code startNextTask} skips pathfinding, so what walks is the raw-key
     * POSITIONING step this is about and not the PathWalker.
     *
     * <p>Three cells deep, so {@code hasFloorWithinOneBlock} finds nothing
     * under either cell — one deep is a step down and not a gap — and across
     * the full width of the floor strip, so there is no way round it.
     */
    @MinecraftTest(name = "Chunk miner bridges a gap on its way",
            timeoutTicks = 3000, order = 25)
    public void bridgesAGapOnItsWay(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 3, STAND_DX + 6, STAND_DZ - 3, STAND_DZ + 3);
        final BlockPos over = stand.offset(5, 0, 0);
        final BlockPos nearGap = stand.offset(1, -1, 0);
        final BlockPos farGap = stand.offset(2, -1, 0);
        fill(ctx, STAND_DX + 1, Y - 3, STAND_DZ - 3,
                STAND_DX + 2, Y - 1, STAND_DZ + 3, "air");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.waitFor(mc -> mc.level.getBlockState(nearGap).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        final boolean[] crouchedPastRim = {false};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            if (mc.player.getX() > stand.getX() + 1.0 && mc.player.isShiftKeyDown()) {
                crouchedPastRim[0] = true;
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertNotAir(ctx, nearGap, "the near half of the trench");
        assertNotAir(ctx, farGap, "the far half of the trench");
        assertAir(ctx, over, "the column beyond the trench");
        assertAir(ctx, over.above(), "the head block of the column beyond the trench");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot went into the trench instead of bridging it:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        if (!crouchedPastRim[0]) {
            throw new AssertionError("The trench was closed without the edge step: the bot"
                    + " never crouched past x=" + (stand.getX() + 1.0)
                    + ", so the first cell was not placed from the rim");
        }
        LOGGER.info("Chunk miner bridge test passed (feet stayed at y={})", lowestFeetY[0]);
    }

    // ================================================================
    // Test 16: a dip on the way is bridged, not stepped down into
    // ================================================================

    /**
     * The same walk as above, but the hole is one block deep instead of three
     * — and that one block is a different bug wearing the same coat.
     * {@code hasFloorWithinOneBlock} calls a one-block drop a step and not a
     * fall, quite rightly: the controller is deciding whether a walk is
     * survivable. So POSITIONING goes down into the dip without complaint, and
     * the bot is then a level below the slab it is clearing, out of line with
     * every column behind it, placing its next block beside its own head.
     * Reported from a real world the first time the sweep was allowed to walk
     * at all: the bot went into the hole and built above itself.
     *
     * <p>The sweep therefore does not borrow the controller's question. It has
     * its own: is this cell the floor I am working from? Only
     * {@code slabFeetY - 1} is, and anything lower gets bridged. That is the
     * whole difference between the two predicates, and it is the reason
     * {@code isSturdyFloor} exists next to a controller method that looks like
     * it would have done.
     *
     * <p>The dip is floored one block down rather than left open, so the
     * failure it pins is the step and nothing else — over an open hole the
     * refusal would have stopped the bot anyway and the test would pass for
     * the wrong reason.
     */
    @MinecraftTest(name = "Chunk miner bridges a dip instead of stepping down",
            timeoutTicks = 3000, order = 26)
    public void bridgesADipInsteadOfSteppingDown(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 3, STAND_DX + 6, STAND_DZ - 3, STAND_DZ + 3);
        final BlockPos over = stand.offset(5, 0, 0);
        final BlockPos nearDip = stand.offset(1, -1, 0);
        final BlockPos farDip = stand.offset(2, -1, 0);
        fill(ctx, STAND_DX + 1, Y - 1, STAND_DZ - 3,
                STAND_DX + 2, Y - 1, STAND_DZ + 3, "air");
        fill(ctx, STAND_DX + 1, Y - 2, STAND_DZ - 3,
                STAND_DX + 2, Y - 2, STAND_DZ + 3, "stone");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.waitFor(mc -> mc.level.getBlockState(nearDip).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertNotAir(ctx, nearDip, "the near half of the dip");
        assertNotAir(ctx, farDip, "the far half of the dip");
        assertAir(ctx, over, "the column beyond the dip");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot stepped down into the dip instead of bridging it:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay on the slab"
                    + " floor at y=" + Y);
        }
        LOGGER.info("Chunk miner dip test passed (feet stayed at y={})", lowestFeetY[0]);
    }

    // ================================================================
    // Test 17: the reported abort — a column one row over, across a pit
    // ================================================================

    /**
     * The world a user reported the run dying in, rebuilt block for block
     * from the save: {@code cannot break -27, 93, -61 (blocks mined: 0)},
     * six position timeouts at one unchanging spot.
     *
     * <p>The two tests above bridge to a column straight ahead. This one
     * differs in the single respect that mattered: the column sits one row
     * <em>over</em>, so the bot's own row and the target's are not the same,
     * and the pit between them is wider in the bot's row than in the
     * column's. Read from the region file at the failing coordinates, with
     * the bot's feet cell as the origin:
     *
     * <pre>
     *   floor (y-1)   dx: -4  -3  -2  -1   0        (0 = under the bot)
     *     row dz=-1        #   #   .   .   #
     *     row dz= 0        #   .   .   .   #        (the bot's own row)
     *     row dz=+1        #   #   .   .   #        (the column's row)
     * </pre>
     *
     * The column stands at dx=-4, dz=+1 — 4.27 blocks off, just past the
     * reach of 4.0, so the run has to walk, and the walk has to cross the
     * pit. Everything else in the chunk is air, so this is the only work the
     * sweep can find and the only column it can pick.
     */
    @MinecraftTest(name = "Chunk miner bridges to a column off its own row",
            timeoutTicks = 3000, order = 27)
    public void bridgesToAColumnOffItsOwnRow(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 5, STAND_DX + 1, STAND_DZ - 1, STAND_DZ + 2);
        final BlockPos over = stand.offset(-4, 0, 1);
        final BlockPos firstStep = stand.offset(-1, -1, 0);
        // The pit: two cells wide in the neighbouring rows, three in the
        // bot's own. Deep, not a dip — the cleared volume below Y-1 is
        // already air, so nothing is within the one-block step the
        // controller tolerates.
        fill(ctx, STAND_DX - 2, Y - 1, STAND_DZ - 1, STAND_DX - 1, Y - 1, STAND_DZ + 1, "air");
        fill(ctx, STAND_DX - 3, Y - 1, STAND_DZ, STAND_DX - 3, Y - 1, STAND_DZ, "air");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.waitFor(mc -> mc.level.getBlockState(firstStep).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        // One cell of bridge is what this crossing needs — from the cell it
        // opens, the column is 3.2 blocks off. How many the sweep lays is
        // its business; that the bot got across without dropping in, and
        // that the run did not abort, is the behaviour under test.
        assertNotAir(ctx, firstStep, "the first cell of the bridge");
        assertAir(ctx, over, "the column across the pit");
        assertAir(ctx, over.above(), "the column's head block");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot dropped into the pit instead of bridging it:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        LOGGER.info("Chunk miner off-row bridge test passed (feet stayed at y={})",
                lowestFeetY[0]);
    }

    // ================================================================
    // Test 18: what it mines across a pit, it also has to pick up
    // ================================================================

    /**
     * Bridging works and the run still gets nowhere, reported from the same
     * world as the test above and rebuilt from the save at those coordinates.
     * The bot bridged west along its own row (`bridging the floor at -24, 91,
     * -63`, then `-25, 91, -63`), and from the two cobble blocks it had just
     * laid it mined the columns at -27 and -28 in the row <em>two over</em>,
     * at 3.2 and 3.8 blocks. Both broke. Both dropped. Neither drop was ever
     * picked up: they lie in the row the bot mined into, the pit still runs
     * between it and them, and COLLECTING has no pathfinder — it walks in
     * view direction, the gap refuses the step, and the phase burns its
     * no-progress budget. Every column costs that, and the cobble piles up on
     * a ledge the bot cannot stand on.
     *
     * <p>Reach was what the sweep bridged for, and reach is not the whole
     * requirement: a column is only worth mining from somewhere the bot can
     * also walk to what falls out of it. Four blocks of reach across a
     * one-block bridge is exactly the case where those two come apart.
     *
     * <p>The fixture is that scene with the cobble bridge taken back out —
     * the bot lays its own — and with the two columns the report managed
     * grown to eight, so the drops pile up instead of proving the point once.
     * The pit runs the full width of the floor: no way round it, and nothing
     * to cross on until the bot builds it.
     *
     * <p>The shape of the wall is the whole trap and is not free to choose.
     * Every column has to be within reach of the spot the bot already stands
     * on, which is a fan two and three rows deep and never wider than the
     * four blocks of reach allow. Put a single column further out and the
     * fixture stops testing this: out of reach makes the bot <em>walk</em>,
     * walking makes the sweep lay a bridge as a matter of course, and once
     * the bot is across, every drop is on ground it can stand on. Two
     * earlier fixtures died of exactly that — one with the wall straight
     * ahead across a chasm, one with it far enough along the row to be out
     * of reach — and both passed with the bug still in.
     *
     * <p>Here nothing ever asks the bot to move: it mines all sixteen blocks
     * from where it was put, and all sixteen drops land on a ledge with a gap
     * in front of it. Reach and footing are two different questions, and this
     * is the smallest world that tells them apart.
     */
    @MinecraftTest(name = "Chunk miner collects what it mines across a pit",
            timeoutTicks = 8000, order = 28)
    public void collectsWhatItMinesAcrossAPit(TestContext ctx) {
        final int standDx = 8;
        final int standDz = 4;
        final BlockPos stand = prepareWithFloor(ctx, standDx, standDz,
                standDx - 4, standDx + 4, standDz, standDz + 3);
        // The pit, the full width of the floor, one row over from the bot.
        fill(ctx, standDx - 4, Y - 1, standDz + 1, standDx + 4, Y - 1, standDz + 1, "air");
        // The wall across it: five columns in the row two over, three in the
        // row behind that. The corners of both are inside four blocks of the
        // bot's eye — 3.0 and 3.4 — so it never has cause to take a step.
        fill(ctx, standDx - 2, Y, standDz + 2, standDx + 2, Y + 1, standDz + 2, "stone");
        fill(ctx, standDx - 1, Y, standDz + 3, standDx + 1, Y + 1, standDz + 3, "stone");
        final BlockPos nearest = stand.offset(0, 0, 2);
        final BlockPos corner = stand.offset(2, 0, 2);
        final BlockPos furthest = stand.offset(-1, 0, 3);
        ctx.waitFor(mc -> !mc.level.getBlockState(furthest).isAir());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertAir(ctx, nearest, "the nearest column across the pit");
        assertAir(ctx, corner, "the corner column of the near row");
        assertAir(ctx, furthest, "the furthest column across the pit");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot dropped into the pit: feet reached y="
                    + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        int left = countItemsAround(ctx, stand);
        if (left > 0) {
            throw new AssertionError("Chunk miner left " + left + " drop(s) lying across the"
                    + " pit — it mined blocks it could not walk to the cobble of");
        }
        LOGGER.info("Chunk miner across-pit collect test passed (feet stayed at y={})",
                lowestFeetY[0]);
    }

    // ================================================================
    // Test 19: how the bot gets to the rim, not just that it gets there
    // ================================================================

    /**
     * Every bridge test above starts with the gap under the bot's nose, so
     * the approach to it is one step long and says nothing. This one puts
     * the gap four blocks along the row, which is what the sweep produces as
     * soon as it walks a stretch of solid floor before finding a hole — and
     * two things the user reported go wrong on exactly that stretch.
     *
     * <p><b>The crouch used to come on at the far end of it.</b>
     * {@code needsEdgeStep} is happy with any support inside reach, and reach
     * is four blocks, so the bot went down into the crouch as soon as the
     * placement was planned and creeped the whole approach at a third of
     * walking speed. The crouch is not the approach, it is the last of it.
     *
     * <p><b>And the gaze used to swing half a turn per block laid.</b>
     * POSITIONING aimed at the support's face, which sits in front of the eye
     * while the bot is short of the plane and behind it the moment the eye
     * clears it: a half-turn on arrival, and another one back at the next
     * placement. What a person does instead is turn their back on the gap
     * once and walk into it backwards, and that is what the bot does now — so
     * the test samples the look direction halfway along the approach, where
     * the two behaviours point opposite ways.
     */
    @MinecraftTest(name = "Chunk miner walks to a rim before it crouches at one",
            timeoutTicks = 3000, order = 29)
    public void walksToARimBeforeCrouchingAtIt(TestContext ctx) {
        final int standDx = 4;
        final int standDz = 7;
        final BlockPos stand = prepareWithFloor(ctx, standDx, standDz,
                standDx - 1, standDx + 8, standDz - 1, standDz + 1);
        fill(ctx, standDx + 4, Y - 1, standDz - 1, standDx + 5, Y - 1, standDz + 1, "air");
        final BlockPos nearGap = stand.offset(4, -1, 0);
        final BlockPos over = stand.offset(6, 0, 0);
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.waitFor(mc -> mc.level.getBlockState(nearGap).isAir());
        ctx.runCommand("give @s cobblestone 64");

        // The plane the bot has to get its eye past: the near face of the
        // first empty cell, four blocks east of where it starts.
        final double rimX = stand.getX() + 4;
        startChunkMiner(ctx, true, Y + 1, Y);
        final boolean[] crouchedEarly = {false};
        final double[] lookAtHalfway = {Double.NaN};
        ctx.waitFor(mc -> {
            if (mc.player.isShiftKeyDown() && mc.player.getX() < rimX - 2.0) {
                crouchedEarly[0] = true;
            }
            if (Double.isNaN(lookAtHalfway[0]) && mc.player.getX() >= rimX - 1.0) {
                lookAtHalfway[0] = mc.player.getLookAngle().x;
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertNotAir(ctx, nearGap, "the near half of the gap");
        assertAir(ctx, over, "the column beyond the gap");
        if (crouchedEarly[0]) {
            throw new AssertionError("Bot crouched more than two blocks short of the rim at x="
                    + rimX + " — the whole approach was creeped, not just the edge step");
        }
        if (Double.isNaN(lookAtHalfway[0])) {
            throw new AssertionError("Bot never got within a block of the rim at x=" + rimX);
        }
        if (lookAtHalfway[0] > 0) {
            throw new AssertionError("Bot walked up to the rim facing it (look.x="
                    + lookAtHalfway[0] + ") — the gaze belongs behind the bridge, so that"
                    + " placing does not cost a half-turn there and another one back");
        }
        LOGGER.info("Chunk miner rim approach test passed (look.x={} one block short of the rim)",
                lookAtHalfway[0]);
    }

    // ================================================================
    // Test 20: the sweep keeps its place when the bot's feet wander off
    // ================================================================

    /**
     * The corridor above, cleared with the bot standing in the row to the
     * north of it — which is where its feet end up on their own, and where
     * the sweep used to lose its place.
     *
     * <p>The bot leaves the row it is clearing every time it collects: it
     * walks after cobble, and vanilla scatters a drop up to half a block off
     * the column it fell from, which is all it takes. A scan anchored on the
     * bot's cell then runs the rest of <i>that</i> row and enters the corridor
     * from the far end, because the snake alternates direction and
     * neighbouring rows are entered from opposite sides. The corridor test
     * caught this about one run in ten — it needs the drops to bounce north
     * and the breather between columns to run long enough for the scan to
     * happen while the bot is over there — and reported {@code cannot break
     * 3019, 41, 3015 (blocks mined: 2)}: the far column handed over with two
     * still standing in front of it, nothing able to walk the bot to it, and
     * three look timeouts.
     *
     * <p>So this puts the feet there instead of waiting for them to go: the
     * bot is moved a row north as soon as the first column falls, and held
     * there until the second one does. Client-side and not by command — the
     * scan happens within a tick or two of the break, which no command
     * round-trip would beat. What is asserted is the order the corridor comes
     * down in, since that is the invariant: each column adjacent to the last,
     * however far the bot has wandered from either.
     */
    @MinecraftTest(name = "Chunk miner keeps its place when the bot steps out of the row",
            timeoutTicks = 3000, order = 30)
    public void keepsItsPlaceWhenTheBotStepsOut(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        final int columns = 4;
        for (int dx = 1; dx <= columns; dx++) {
            setBlock(ctx, stand.offset(dx, 0, 0), "stone");
            setBlock(ctx, stand.offset(dx, 1, 0), "stone");
        }

        startChunkMiner(ctx, true, Y + 1, Y);
        final List<Integer> order = new ArrayList<>();
        ctx.waitFor(mc -> {
            for (int dx = 1; dx <= columns; dx++) {
                if (!order.contains(dx)
                        && mc.level.getBlockState(stand.offset(dx, 0, 0)).isAir()) {
                    order.add(dx);
                }
            }
            if (order.size() == 1) {
                mc.player.setPos(stand.getX() + 1.5, Y, stand.getZ() - 0.5);
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        for (int dx = 1; dx <= columns; dx++) {
            assertAir(ctx, stand.offset(dx, 0, 0), "corridor floor block " + dx);
            assertAir(ctx, stand.offset(dx, 1, 0), "corridor head block " + dx);
        }
        if (!order.equals(List.of(1, 2, 3, 4))) {
            throw new AssertionError("Chunk miner cleared the corridor in the order " + order
                    + " — the sweep followed the bot out of the row instead of holding its"
                    + " own place, and handed over a column with others still standing in"
                    + " front of it");
        }
        LOGGER.info("Chunk miner kept its place across the corridor (order {})", order);
    }

    // ================================================================
    // Test 21: a bridge long enough that the bot has to walk out along it
    // ================================================================

    /**
     * Every bridging fixture above lays its cells from one standing spot: the
     * trench is two cells wide, the bot reaches across it without a step, and
     * every placement in the suite logs the same {@code dist=2.2}. That is the
     * easy half of bridging, and it is the only half under test.
     *
     * <p>The other half is what a long bridge actually is: lay a cell, walk
     * out onto it, lay the next from there. Reported from a real world at
     * chunk [-2, -4] — the first cell went in correctly (support -23, block
     * into -24), and the second never did. The bot crouched, then stood dead
     * still for the whole sixty ticks of POSITIONING (identical coordinates at
     * both timeouts, -23.30/92.00/-62.47), and LOOKING found the top face of
     * the support under every ray it cast — {@code hitResult=-24, 91, -62
     * (up)}, the support seen from above because the eye never got past its
     * side. Two look timeouts, {@code Task failed: Place filler at -25, 91,
     * -62} twice, and the bot fell three blocks and the run aborted.
     *
     * <p>So: a column five cells out and one row over, its own footing under
     * it, and nothing but air in between. Too far to reach from the platform,
     * which leaves the bot no way to it but the one that was never tested.
     */
    @MinecraftTest(name = "Chunk miner walks out along the bridge it lays",
            timeoutTicks = 4000, order = 31)
    public void walksOutAlongItsOwnBridge(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, STAND_DX, STAND_DZ,
                STAND_DX - 1, STAND_DX + 1, STAND_DZ - 1, STAND_DZ + 1);
        final BlockPos over = stand.offset(-5, 0, 1);
        setBlock(ctx, over.below(), "stone");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertAir(ctx, over, "the column across the pit");
        assertAir(ctx, over.above(), "the column's head block");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot dropped off its own bridge instead of walking it:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        LOGGER.info("Chunk miner long-bridge test passed (feet stayed at y={})", lowestFeetY[0]);
    }

    // ================================================================
    // Test 22: the bridge is a staircase, and the bot has to get onto it
    // ================================================================

    /**
     * A column four cells out and four cells over, so the line to it is a true
     * diagonal and the bridge that follows it is a staircase: one cell west,
     * one cell south, one cell west. The bot lays a cell in the row beside the
     * one it is standing in, and the support for the cell after that is then
     * diagonal to its feet.
     *
     * <p>Which is where it used to stop dead. The edge step walked the face's
     * own axis and nothing else, so from the wrong row it tracked west along a
     * row that was never bridged, and vanilla's crouch — rightly — held it on
     * the last sliver of the block behind. Measured in a real world at chunk
     * [-2, -4]: {@code pastFace} -0.71, -0.70, -0.70 at ticks 5, 20 and 40 of
     * the same POSITIONING, feet at -24/92/-64 against a support at
     * -24/91/-63, {@code under=air} with {@code onGround}, and the back key
     * held throughout. Sixty ticks that moved the bot one hundredth of a
     * block, then LOOKING found the support's top face because the eye had
     * never got past its side, and the run died on the placement.
     *
     * <p>The rim the step needs is the far edge of the support, so the walk
     * has to go at the support, not merely along the face — which closes the
     * sideways offset first and becomes the rim step once the bot is over it.
     */
    @MinecraftTest(name = "Chunk miner steps onto its bridge instead of walking beside it",
            timeoutTicks = 4000, order = 32)
    public void stepsOntoItsBridge(TestContext ctx) {
        final int standDx = STAND_DX + 1;
        final int standDz = STAND_DZ - 1;
        final BlockPos stand = prepareWithFloor(ctx, standDx, standDz,
                standDx - 1, standDx + 1, standDz - 1, standDz + 1);
        final BlockPos over = stand.offset(-4, 0, 4);
        setBlock(ctx, over.below(), "stone");
        setBlock(ctx, over, "stone");
        setBlock(ctx, over.above(), "stone");
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final int[] lowestFeetY = {Integer.MAX_VALUE};
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.blockPosition().getY());
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertAir(ctx, over, "the column across the pit");
        assertAir(ctx, over.above(), "the column's head block");
        if (lowestFeetY[0] < Y) {
            throw new AssertionError("Bot walked off its own staircase instead of onto it:"
                    + " feet reached y=" + lowestFeetY[0] + ", expected to stay at y=" + Y);
        }
        LOGGER.info("Chunk miner staircase-bridge test passed (feet stayed at y={})",
                lowestFeetY[0]);
    }

    // ================================================================
    // Test 23: gravel from above the slab is mined once it has landed
    // ================================================================
    /**
     * A column with two gravel stacked on its head block, the way the first
     * layer of a run meets the gravel patches of the world above it. The
     * head block going drops the first gravel into its cell, and the second
     * onto that; each is a block the run has to take before the column is
     * clear. Reported from a real world as the run stalling on the first
     * layer: the fallen gravel was met as a failed break of the head block
     * and retried at the price of a step retry each time, and the run died
     * on the column after two of them. Asserted as the whole column, gravel
     * included, being air at the end of a run that did not fail.
     */
    @MinecraftTest(name = "Chunk miner mines the gravel that falls into a column",
            timeoutTicks = 2000, order = 33)
    public void minesFallenGravel(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        BlockPos lower = stand.offset(1, 0, 0);
        setBlock(ctx, lower, "stone");
        setBlock(ctx, lower.above(), "stone");
        setBlock(ctx, lower.above(2), "gravel");
        setBlock(ctx, lower.above(3), "gravel");
        ctx.runCommand("give @s diamond_shovel");

        runChunkMiner(ctx, Y + 1, Y, true);

        assertAir(ctx, lower.above(3), "the upper gravel's original cell");
        assertAir(ctx, lower.above(2), "the lower gravel's original cell");
        assertAir(ctx, lower.above(), "the head block's cell");
        assertAir(ctx, lower, "the foot block's cell");
        LOGGER.info("Chunk miner fallen gravel test passed");
    }

    // ================================================================
    // Test 24: a spread pool is capped at its source, not across its spread
    // ================================================================
    /**
     * One source on a flat floor and time to spread: the diamond of a hundred
     * and more flowing cells that a single source makes, with the bot on a
     * one-block pedestal in the middle of it. The pedestal is the bot's own
     * column, the descent digs through it, and the water round it is what
     * that column touches — the way a run meets a pool, from inside it. It is
     * also what keeps the bot dry and where it is: flowing water carries a
     * player off at a third of a block a second, and a bot that has drifted
     * out of reach of the source while the world was being built tests the
     * walk, not the cap. Reported from a real world as the bot walling up
     * flowing water block by block: with the survey limit at 16 no pool with
     * its spread ever fit under it, so none was surveyed whole, and each was
     * dammed across whichever flowing cells touched the column — cells that
     * drain by themselves the moment the source is gone.
     *
     * <p>Every filler block that ever stands in the pool has to be the cap on
     * the source, and nothing else: a dam on a flowing cell at any point of
     * the run — in the first plan, or after the cap while the spread is still
     * draining — fails it. The column behind the pool then has to come down,
     * which is the run outlasting the drain instead of walling it up, and
     * the pool has to end dry.
     */
    @MinecraftTest(name = "Chunk miner caps a spread pool at its source",
            timeoutTicks = 3000, order = 34)
    public void capsSpreadPoolAtSource(TestContext ctx) {
        // Floor under the whole diamond a source at chunk-local (7, 7)
        // spreads to: seven cells each way, all of it inside the chunk.
        final BlockPos stand = prepareWithFloor(ctx, 4, STAND_DZ, 0, 14, 0, 14);
        final BlockPos pedestal = stand.offset(1, 0, 0);
        final BlockPos source = stand.offset(3, 0, 0);
        final BlockPos behind = stand.offset(4, 0, 0);
        setBlock(ctx, pedestal, "stone");
        setBlock(ctx, behind, "stone");
        standAt(ctx, pedestal.above());
        setBlock(ctx, source, "water");
        // Let the spread reach its tips before the run starts — north and
        // south, where nothing stands in the water's way.
        final BlockPos northTip = source.offset(0, 0, -7);
        final BlockPos southTip = source.offset(0, 0, 7);
        ctx.waitFor(mc -> !mc.level.getFluidState(northTip).isEmpty()
                && !mc.level.getFluidState(southTip).isEmpty());
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, Y + 1, Y);
        final BlockPos poolMin = source.offset(-7, 0, -7);
        final BlockPos poolMax = source.offset(7, 0, 7);
        final Set<BlockPos> fillers = new LinkedHashSet<>();
        ctx.waitFor(mc -> {
            for (BlockPos cell : BlockPos.betweenClosed(poolMin, poolMax)) {
                if (mc.level.getBlockState(cell).is(Blocks.COBBLESTONE)) {
                    fillers.add(cell.immutable());
                }
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        if (!fillers.remove(source)) {
            throw new AssertionError("The source at " + source.toShortString()
                    + " was never capped; filler blocks stood at " + shortList(fillers));
        }
        if (!fillers.isEmpty()) {
            throw new AssertionError("Filler blocks stood on flowing cells at " + shortList(fillers)
                    + " — a dam across the spread instead of a cap on the source");
        }
        assertAir(ctx, pedestal, "the pedestal the bot descended through");
        assertAir(ctx, behind, "the column behind the pool");
        // The spread drains on its own once the source is capped; give the
        // last of it a moment, the run does not wait for cells it never
        // touches.
        ctx.waitFor(mc -> {
            for (BlockPos cell : BlockPos.betweenClosed(poolMin, poolMax)) {
                if (!mc.level.getFluidState(cell).isEmpty()) {
                    return false;
                }
            }
            return true;
        }, 200);
        LOGGER.info("Chunk miner spread pool test passed");
    }

    // ================================================================
    // Test 25: /miner chunk blacklist add, remove and clear edit the list
    // ================================================================
    /**
     * The command path end to end, for the same reason the bot's ignore
     * list has one: a namespaced id has to get through the parser whole — a
     * string argument stops at the colon, which is how {@code minecraft:chest}
     * once came back as an incomplete command — a bare name has to land as
     * the same entry, a name that is no block has to be refused, and every
     * change has to reach miner.json. The player's own list is set aside
     * first and put back at the end, file included.
     */
    @MinecraftTest(name = "Chunk miner blacklist commands edit the list",
            timeoutTicks = 200, order = 35)
    public void blacklistCommandsEditTheList(TestContext ctx) {
        final String gold = "minecraft:gold_block";
        final String spawner = "minecraft:spawner";
        final List<String> before = ctx.computeOnClient(
                mc -> new ArrayList<>(MinerSetup.CONFIG.chunkMinerBlacklist));
        try {
            ctx.runOnClient(mc -> MinerSetup.CONFIG.chunkMinerBlacklist.clear());

            ctx.runCommand("miner chunk blacklist add minecraft:gold_block");
            assertBlacklist(ctx, List.of(gold), "after adding a namespaced id");
            if (!MinerConfig.load().chunkMinerBlacklist.contains(gold)) {
                throw new AssertionError("The added block did not reach miner.json");
            }
            ctx.runCommand("miner chunk blacklist add gold_block");
            assertBlacklist(ctx, List.of(gold), "after adding the bare name of an entry");
            ctx.runCommand("miner chunk blacklist add minecraft:not_a_block");
            assertBlacklist(ctx, List.of(gold), "after a name that is no block");
            ctx.runCommand("miner chunk blacklist add spawner");
            assertBlacklist(ctx, List.of(gold, spawner), "after adding a bare name");
            ctx.runCommand("miner chunk blacklist remove minecraft:gold_block");
            assertBlacklist(ctx, List.of(spawner), "after removing an entry");
            if (MinerConfig.load().chunkMinerBlacklist.contains(gold)) {
                throw new AssertionError("The removed block is still in miner.json");
            }
            ctx.runCommand("miner chunk blacklist clear");
            assertBlacklist(ctx, List.of(), "after clearing");
            if (!MinerConfig.load().chunkMinerBlacklist.isEmpty()) {
                throw new AssertionError("miner.json still lists blocks after the clear");
            }
            LOGGER.info("Chunk miner blacklist command test passed");
        } finally {
            ctx.runOnClient(mc -> {
                MinerSetup.CONFIG.chunkMinerBlacklist.clear();
                MinerSetup.CONFIG.chunkMinerBlacklist.addAll(before);
                MinerSetup.CONFIG.save();
            });
        }
    }

    private void assertBlacklist(TestContext ctx, List<String> expected, String when) {
        List<String> actual = ctx.computeOnClient(
                mc -> new ArrayList<>(MinerSetup.CONFIG.chunkMinerBlacklist));
        if (!actual.equals(expected)) {
            throw new AssertionError("Blacklist " + when + " is " + actual
                    + ", expected " + expected);
        }
    }

    // ================================================================
    // Test 26: water with no source left is waited out, never dammed
    // ================================================================
    /**
     * The spread of the pool above with its source taken away: what a cap
     * leaves behind, and what a run meets whenever it plans the next column
     * before the last cap's spread has gone. The source goes with the world
     * frozen, so the run begins against the whole spread and not one fluid
     * tick of drain has happened. Flowing water with no source in it is on
     * its way out by itself, and a block on it is a block for nothing: no
     * filler may stand anywhere in the pool at any point of the run, the run
     * has to outlast the drain instead — pedestal and the column behind
     * mined — and the pool has to end dry.
     */
    @MinecraftTest(name = "Chunk miner waits out sourceless water",
            timeoutTicks = 3000, order = 36)
    public void waitsOutSourcelessWater(TestContext ctx) {
        final BlockPos stand = prepareWithFloor(ctx, 4, STAND_DZ, 0, 14, 0, 14);
        final BlockPos pedestal = stand.offset(1, 0, 0);
        final BlockPos source = stand.offset(3, 0, 0);
        final BlockPos behind = stand.offset(4, 0, 0);
        setBlock(ctx, pedestal, "stone");
        setBlock(ctx, behind, "stone");
        standAt(ctx, pedestal.above());
        setBlock(ctx, source, "water");
        final BlockPos northTip = source.offset(0, 0, -7);
        final BlockPos southTip = source.offset(0, 0, 7);
        ctx.waitFor(mc -> !mc.level.getFluidState(northTip).isEmpty()
                && !mc.level.getFluidState(southTip).isEmpty());
        ctx.runCommand("give @s cobblestone 64");

        ctx.runCommand("tick freeze");
        ctx.runCommand("setblock " + source.getX() + " " + source.getY() + " " + source.getZ()
                + " air");
        ctx.waitFor(mc -> mc.level.getFluidState(source).isEmpty());
        startChunkMiner(ctx, true, Y + 1, Y);
        ctx.runCommand("tick unfreeze");

        final BlockPos poolMin = source.offset(-7, 0, -7);
        final BlockPos poolMax = source.offset(7, 0, 7);
        final Set<BlockPos> fillers = new LinkedHashSet<>();
        ctx.waitFor(mc -> {
            for (BlockPos cell : BlockPos.betweenClosed(poolMin, poolMax)) {
                if (mc.level.getBlockState(cell).is(Blocks.COBBLESTONE)) {
                    fillers.add(cell.immutable());
                }
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        if (!fillers.isEmpty()) {
            throw new AssertionError("Filler blocks stood at " + shortList(fillers)
                    + " — a dam on water that was draining by itself");
        }
        assertAir(ctx, pedestal, "the pedestal the bot descended through");
        assertAir(ctx, behind, "the column behind the pool");
        ctx.waitFor(mc -> {
            for (BlockPos cell : BlockPos.betweenClosed(poolMin, poolMax)) {
                if (!mc.level.getFluidState(cell).isEmpty()) {
                    return false;
                }
            }
            return true;
        }, 200);
        LOGGER.info("Chunk miner sourceless water test passed");
    }

    // ================================================================
    // Test 27: the way out of the pit is left standing, and mended
    // ================================================================

    /**
     * Two claims about {@link SpiralStairs} in one run, because one fixture
     * proves both and the second one cannot be set up by hand.
     *
     * <p><b>The ramp is left standing.</b> The top step sits on the layer the
     * bot's feet started above — {@code Y - 1} here — at the chunk's minimum
     * corner, and each following step is one cell further along the north edge
     * and one layer down. So with a range of {@code Y+1 .. Y-2} exactly two
     * cells in the chunk are spared, {@code (0,0)} at {@code Y-1} and
     * {@code (1,0)} at {@code Y-2}, and every other cell of the fixture has to
     * come out. Asserted cell by cell rather than spot-checked: a predicate that
     * claims one cell too many leaves a pillar the sweep reports as finished, and
     * nothing else in this suite would notice.
     *
     * <p><b>A step the descent digs is put back.</b> The bot starts on
     * {@code (1,0)}, and that column's cell at {@code Y-2} is the second step —
     * so digging down into the lower slab takes the bot's own way out with it.
     * Nothing here can make the bot take a sideways pace instead, which is why
     * the descent is allowed to break it and {@code repairStairs} rebuilds it
     * once the sweep has moved the bot off the cell. The fixture is stone and the
     * bot carries cobblestone, so the rebuilt step reads as
     * <b>cobblestone</b> — "still there" would also be satisfied by a step that
     * was never dug, which is the one thing this must not accept.
     *
     * <p>Eight columns wide on purpose. The bot clears everything within reach
     * without stepping, so a short fixture would let it empty the slab from the
     * cell it landed in and never free the step at all.
     */
    @MinecraftTest(name = "Chunk miner leaves a staircase and mends it",
            timeoutTicks = 4000, order = 37)
    public void leavesAndMendsAStaircase(TestContext ctx) {
        prepareWithFloor(ctx, STAIR_Y, 1, 0, 0, 7, 0, 1);
        // The lower slab's foot layer, plus the floor under it. STAIR_Y - 3 is below
        // the range, so it is never mined and is what the rebuilt step is
        // placed against.
        fill(ctx, 0, STAIR_Y - 3, 0, 7, STAIR_Y - 2, 1, "stone");
        ctx.runCommand("give @s cobblestone 64");
        final BlockPos lastFilled = new BlockPos(BASE_X + 7, STAIR_Y - 2, BASE_Z + 1);
        ctx.waitFor(mc -> !mc.level.getBlockState(lastFilled).isAir());

        runChunkMiner(ctx, STAIR_Y + 1, STAIR_Y - 2, true);

        final BlockPos topStep = new BlockPos(BASE_X, STAIR_Y - 1, BASE_Z);
        final BlockPos dugStep = new BlockPos(BASE_X + 1, STAIR_Y - 2, BASE_Z);
        assertNotAir(ctx, topStep, "the staircase's top step");
        boolean rebuilt = ctx.computeOnClient(mc ->
                mc.level.getBlockState(dugStep).is(Blocks.COBBLESTONE));
        if (!rebuilt) {
            String block = ctx.computeOnClient(mc ->
                    mc.level.getBlockState(dugStep).getBlock().toString());
            throw new AssertionError("The step the descent dug at " + dugStep
                    + " was not rebuilt: found " + block + ", expected cobblestone");
        }
        for (int dx = 0; dx <= 7; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = STAIR_Y - 2; y <= STAIR_Y + 1; y++) {
                    BlockPos cell = new BlockPos(BASE_X + dx, y, BASE_Z + dz);
                    if (cell.equals(topStep) || cell.equals(dugStep)) {
                        continue;
                    }
                    assertAir(ctx, cell, "cell beside the staircase");
                }
            }
        }
        LOGGER.info("Chunk miner staircase test passed");
    }

    // ================================================================
    // Test 38: a cell the staircase walls in ends the slab, not the run
    // ================================================================

    /**
     * Two consecutive steps cover two faces of the cell between them — the cell
     * directly under one step, with the next step down standing beside it — and
     * in a slab that is still solid the remaining three are rock. The bot used
     * to climb onto the step and aim at the lid under its own feet until the look
     * timed out three times, then declare the whole run dead: reported from a
     * real world as {@code cannot break -30, 84, -64 (blocks mined: 1334)}.
     *
     * <p>The fixture walls the cell in for good, which is the case that cannot
     * resolve itself as the sweep goes past: bedrock on the inward side (never
     * diggable) and stone outside the chunk on the two outward sides (never this
     * run's work). So the assertion is the whole of the new rule — the slab
     * finishes, the run succeeds, and that one cell is still standing. That the
     * <em>temporarily</em> hidden cell is still mined is what every other test
     * here asserts by clearing its fixture completely.
     */
    @MinecraftTest(name = "Chunk miner finishes past a cell its staircase walls in",
            timeoutTicks = 4000, order = 38)
    public void finishesPastAWalledInCell(TestContext ctx) {
        // Standing inside the chunk, not on its ring: the descent digs its own
        // column, and on a ring column that column holds a step — which would
        // hand the cell under test an open side for as long as the repair takes.
        prepareWithFloor(ctx, STAIR_Y, 4, 1, 0, 7, 0, 1);
        fill(ctx, 0, STAIR_Y - 3, 0, 7, STAIR_Y - 2, 1, "stone");
        // A catch floor one layer under the range — never mined (isMinable caps
        // at toY), never asserted on, and it is what keeps this fixture from
        // being a cliff. The worked strip is two columns wide with nothing
        // around it, so a single step off it used to be a twenty-block fall:
        // "Player578 fell from a high place", the run stopped on damage 25
        // blocks in, and it only happened under the load of a full run. The
        // bedrock below invites a step-up jump, and a jump is airborne, where
        // the crouch that normally holds the bot at a rim does not apply.
        fill(ctx, -2, STAIR_Y - 3, -2, 9, STAIR_Y - 3, 3, "stone");
        final BlockPos walledIn = new BlockPos(BASE_X, STAIR_Y - 2, BASE_Z);
        final BlockPos lid = new BlockPos(BASE_X, STAIR_Y - 1, BASE_Z);
        final BlockPos stepBeside = new BlockPos(BASE_X + 1, STAIR_Y - 2, BASE_Z);
        final BlockPos inward = new BlockPos(BASE_X, STAIR_Y - 2, BASE_Z + 1);
        setBlock(ctx, inward, "bedrock");
        setBlock(ctx, new BlockPos(BASE_X - 1, STAIR_Y - 2, BASE_Z), "stone");
        setBlock(ctx, new BlockPos(BASE_X, STAIR_Y - 2, BASE_Z - 1), "stone");
        ctx.runCommand("give @s cobblestone 64");

        runChunkMiner(ctx, STAIR_Y + 1, STAIR_Y - 2, true);

        assertNotAir(ctx, lid, "the step over the walled-in cell");
        assertNotAir(ctx, stepBeside, "the step beside the walled-in cell");
        assertNotAir(ctx, walledIn, "the walled-in cell");
        for (int dx = 0; dx <= 7; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = STAIR_Y - 2; y <= STAIR_Y + 1; y++) {
                    BlockPos cell = new BlockPos(BASE_X + dx, y, BASE_Z + dz);
                    if (cell.equals(lid) || cell.equals(stepBeside)
                            || cell.equals(walledIn) || cell.equals(inward)) {
                        continue;
                    }
                    assertAir(ctx, cell, "cell the sweep could reach");
                }
            }
        }
        LOGGER.info("Chunk miner walled-in cell test passed");
    }

    // ================================================================
    // Test 39: an opening no body fits through is not a way in
    // ================================================================

    /**
     * The same corner cell as the test above — lid overhead, the next step down
     * beside it — but this time the two faces pointing out of the chunk are
     * <em>open</em>: at a quarry's rim the ground outside stands at its own
     * height, so the slab's level is air out there with rock over it. A ray goes
     * straight through that; a body cannot, and the bot has no business outside
     * the chunk anyway. Counting it as a face is what killed a real run at
     * {@code cannot break -16, 106, -64 (blocks mined: 138)}: the cell was
     * planned on the strength of a hole in the wall, the bot walked up the
     * staircase to get as near as it could, and that put it on the lid, aiming
     * {@code face=up} at the step under its own feet until the look timed out
     * three times.
     *
     * <p>Inward the cell is plain slab stone, not the bedrock of the walled-in
     * fixture, so this one asserts the other half of the rule: the cell is not
     * given up on. It waits for the neighbour the sweep is going to take anyway
     * and is then mined from inside the slab, at eye level — the run finishes
     * with it gone and both steps still standing.
     */
    @MinecraftTest(name = "Chunk miner waits out a cell only a rim slot opens",
            timeoutTicks = 4000, order = 39)
    public void waitsOutACellOnlyARimSlotOpens(TestContext ctx) {
        // Standing inside the chunk, not on its ring, for the same reason as the
        // walled-in fixture: a descent through a ring column digs the step out
        // and would hand the cell a face while the repair is pending.
        prepareWithFloor(ctx, STAIR_Y, 4, 1, 0, 7, 0, 1);
        fill(ctx, 0, STAIR_Y - 3, 0, 7, STAIR_Y - 2, 1, "stone");
        fill(ctx, -2, STAIR_Y - 3, -2, 9, STAIR_Y - 3, 3, "stone");
        final BlockPos underStep = new BlockPos(BASE_X, STAIR_Y - 2, BASE_Z);
        final BlockPos lid = new BlockPos(BASE_X, STAIR_Y - 1, BASE_Z);
        final BlockPos stepBeside = new BlockPos(BASE_X + 1, STAIR_Y - 2, BASE_Z);
        // The rim, on both outward sides: the cell's own level is already air
        // out there (the prepare clears a margin round the chunk), and rock goes
        // over it. Open to a ray, one cell high, outside the chunk. The rock is
        // what makes the fixture falsify: a two-cell opening would let the bot
        // stand out there and mine the cell, and the run would pass with the
        // rule reverted.
        for (BlockPos slot : new BlockPos[] {
                new BlockPos(BASE_X - 1, STAIR_Y - 2, BASE_Z),
                new BlockPos(BASE_X, STAIR_Y - 2, BASE_Z - 1)}) {
            setBlock(ctx, slot.above(), "stone");
            setBlock(ctx, slot.above().above(), "stone");
        }
        ctx.runCommand("give @s cobblestone 64");

        runChunkMiner(ctx, STAIR_Y + 1, STAIR_Y - 2, true);

        assertNotAir(ctx, lid, "the step over the cell");
        assertNotAir(ctx, stepBeside, "the step beside the cell");
        assertAir(ctx, underStep, "the cell under the step");
        for (int dx = 0; dx <= 7; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = STAIR_Y - 2; y <= STAIR_Y + 1; y++) {
                    BlockPos cell = new BlockPos(BASE_X + dx, y, BASE_Z + dz);
                    if (cell.equals(lid) || cell.equals(stepBeside)) {
                        continue;
                    }
                    assertAir(ctx, cell, "cell the sweep could reach");
                }
            }
        }
        LOGGER.info("Chunk miner rim slot test passed");
    }

    // ================================================================
    // Test 40: a second run does not eat the first run's staircase
    // ================================================================

    /**
     * The bug this suite could not see: <b>the ramp moved between runs.</b>
     *
     * <p>Reported as a staircase mined away at every layer, and the log said it
     * plainly — {@code staircase from y=103} on one run and {@code y=101} on the
     * next, in the same shaft. The ring position used to be counted down from a
     * {@code topY} the behavior took from wherever the bot was standing at
     * {@code start()}, so a run that began two blocks lower rotated every step
     * by two: the sweep mined the cells the previous run had spared and spared
     * their neighbours instead, layer after layer, and what was left was not a
     * ramp any more.
     *
     * <p>Two starts, both <b>argument-less</b>, because that is the only shape
     * the bug had: a requested range fixes {@code fromY} and both runs then
     * agree by accident. The second stands the bot two layers down, the way a
     * person restarting a stopped run does — and the way a restock resume does,
     * since resume is {@code start()}. Its slab covers both layers the first run
     * left steps in, so with the ramp keyed to the run those two cells are
     * ordinary work; keyed to the layer they are the same two cells as before.
     *
     * <p>The refill is the dz=1 row only. The second run needs work in that slab
     * or it has no reason to look at those layers at all, and the bot needs the
     * dz=0 row to stand in.
     */
    @MinecraftTest(name = "Chunk miner keeps the staircase when a run starts again lower",
            timeoutTicks = 6000, order = 40)
    public void keepsTheStaircaseAcrossARestart(TestContext ctx) {
        prepareWithFloor(ctx, STAIR_Y, 1, 0, 0, 7, 0, 1);
        fill(ctx, 0, STAIR_Y - 3, 0, 7, STAIR_Y - 2, 1, "stone");
        ctx.runCommand("give @s cobblestone 64");
        final BlockPos lastFilled = new BlockPos(BASE_X + 7, STAIR_Y - 2, BASE_Z + 1);
        ctx.waitFor(mc -> !mc.level.getBlockState(lastFilled).isAir());

        final BlockPos topStep = new BlockPos(BASE_X, STAIR_Y - 1, BASE_Z);
        final BlockPos lowerStep = new BlockPos(BASE_X + 1, STAIR_Y - 2, BASE_Z);

        // Only the bottom is pinned, as in `resumes the layer it stands in`: the
        // top is what has to keep coming from the bot, because that is the whole
        // difference between the two runs.
        final int configuredBottom = MinerSetup.CONFIG.chunkMinerBottomY;
        MinerSetup.CONFIG.chunkMinerBottomY = STAIR_Y - 2;
        try {
            startChunkMiner(ctx, false, 0, 0);
            awaitChunkMiner(ctx, true);
            assertNotAir(ctx, topStep, "the first run's top step");
            assertNotAir(ctx, lowerStep, "the first run's second step");

            fill(ctx, 2, STAIR_Y - 2, 1, 7, STAIR_Y - 2, 1, "stone");
            final BlockPos refilled = new BlockPos(BASE_X + 7, STAIR_Y - 2, BASE_Z + 1);
            ctx.waitFor(mc -> !mc.level.getBlockState(refilled).isAir());
            standAt(ctx, new BlockPos(BASE_X + 4, STAIR_Y - 2, BASE_Z));

            startChunkMiner(ctx, false, 0, 0);
            awaitChunkMiner(ctx, true);
        } finally {
            MinerSetup.CONFIG.chunkMinerBottomY = configuredBottom;
        }

        assertNotAir(ctx, topStep, "the top step, after a run that started two layers lower");
        assertNotAir(ctx, lowerStep, "the second step, after a run that started two layers lower");
        LOGGER.info("Chunk miner staircase-across-a-restart test passed");
    }

    // ================================================================
    // Test 41: a top face is no face to an eye on the slab floor
    // ================================================================

    /**
     * The head cell over the lower of a slab's two steps — the upper step beside
     * it, the lower one under it — is the one cell the ramp can leave with
     * nothing open but its top, and a top face in the head layer is above the
     * eye of a bot standing on the slab floor. It counted as a face anyway, so
     * the sweep handed the cell over as soon as it reached it, and a real run
     * died there: {@code cannot break -1, 103, -61 (blocks mined: 25)},
     * {@code face=up}, three look timeouts from one unchanging spot with the hit
     * result on the upper step.
     *
     * <p>Built the way that run met it. The upper step is chunk-local (15, 2)
     * and the lower (15, 3), on the second slab of the range, whose snake runs
     * its rows from the south: the back pass comes east along row 2 and turns
     * into row 3 at this very cell. The bot descends at (14, 1), off the ring,
     * with nothing to dig ahead of it, so the back pass is the first thing the
     * sweep does. The cell's west and south neighbours are single blocks with
     * air under them — enough to cover both sides, and nothing a bot on the
     * floor can climb to put its eye over the top.
     *
     * <p>Asserts the cell is mined rather than left: its west neighbour is the
     * next column of its own row, so it has a side to wait for. Both steps
     * stay.
     */
    @MinecraftTest(name = "Chunk miner waits out a cell only its top face opens",
            timeoutTicks = 4000, order = 41)
    public void waitsOutACellOnlyItsTopOpens(TestContext ctx) {
        // The first head layer at or above Y whose step is chunk-local (15, 2),
        // derived like STAIR_Y; the layer under it owes its step at (15, 3).
        final int head = Y + Math.floorMod(
                SpiralStairs.ringIndexAt(Y) - SpiralStairs.ringIndex(15, 2),
                SpiralStairs.RING_LENGTH);
        final int feet = head - 1;
        prepareWithFloor(ctx, head + 1, 14, 1, 14, 14, 1, 1);
        fill(ctx, 0, feet - 1, 0, 15, feet - 1, 6, "stone");
        final BlockPos upperStep = new BlockPos(BASE_X + 15, head, BASE_Z + 2);
        final BlockPos lowerStep = new BlockPos(BASE_X + 15, feet, BASE_Z + 3);
        final BlockPos cell = new BlockPos(BASE_X + 15, head, BASE_Z + 3);
        final BlockPos west = new BlockPos(BASE_X + 14, head, BASE_Z + 3);
        final BlockPos south = new BlockPos(BASE_X + 15, head, BASE_Z + 4);
        for (BlockPos pos : new BlockPos[] {upperStep, lowerStep, cell, west, south}) {
            setBlock(ctx, pos, "stone");
        }
        ctx.runCommand("give @s cobblestone 64");

        runChunkMiner(ctx, head + 2, feet, true);

        assertNotAir(ctx, upperStep, "the upper step");
        assertNotAir(ctx, lowerStep, "the lower step");
        assertAir(ctx, cell, "the cell over the lower step");
        assertAir(ctx, west, "the cell's west neighbour");
        assertAir(ctx, south, "the cell's south neighbour");
        LOGGER.info("Chunk miner top-face-only cell test passed");
    }

    // ================================================================
    // Test 42: the column that opens a waiting cell takes it along
    // ================================================================

    /**
     * The cell over the lower step once more, met the way the next real run
     * met it: on a forward pass, with the rest of its row still ahead. The rule
     * above has it wait for a side, and the very next column of its row gives
     * it one — but by then the sweep has scanned past it, and a cell behind the
     * sweep came back only on the back pass, after every column ahead of it in
     * the slab. Reported from that run: the sweep went west along row 3 from
     * (14, 3) and left {@code -1, 103, -61} standing beside the ramp, a slab's
     * worth of work away from being mined.
     *
     * <p>The range is one slab, so its snake runs row 3 from the east: the cell
     * is the row's first column and the upper step's column is the one before
     * it. The bot stands in the cleared row 2 at (12, 2) with row 3 standing
     * from (8, 3) to (14, 3), and a single block south of the cell keeps that
     * side shut the way the unmined row 4 did in the world.
     *
     * <p>Asserts the order: the cell falls before the head block of (12, 3),
     * i.e. with the column that opened it rather than after the row.
     */
    @MinecraftTest(name = "Chunk miner takes a waiting cell with the column that opens it",
            timeoutTicks = 4000, order = 42)
    public void takesAWaitingCellWithItsOpener(TestContext ctx) {
        final int head = Y + Math.floorMod(
                SpiralStairs.ringIndexAt(Y) - SpiralStairs.ringIndex(15, 2),
                SpiralStairs.RING_LENGTH);
        final int feet = head - 1;
        prepareWithFloor(ctx, feet, 12, 2, 0, 15, 0, 6);
        final BlockPos upperStep = new BlockPos(BASE_X + 15, head, BASE_Z + 2);
        final BlockPos lowerStep = new BlockPos(BASE_X + 15, feet, BASE_Z + 3);
        final BlockPos cell = new BlockPos(BASE_X + 15, head, BASE_Z + 3);
        final BlockPos south = new BlockPos(BASE_X + 15, head, BASE_Z + 4);
        for (BlockPos pos : new BlockPos[] {upperStep, lowerStep, cell, south}) {
            setBlock(ctx, pos, "stone");
        }
        for (int dx = 8; dx <= 14; dx++) {
            setBlock(ctx, new BlockPos(BASE_X + dx, feet, BASE_Z + 3), "stone");
            setBlock(ctx, new BlockPos(BASE_X + dx, head, BASE_Z + 3), "stone");
        }
        ctx.runCommand("give @s cobblestone 64");

        startChunkMiner(ctx, true, head, feet);
        final List<String> order = new ArrayList<>();
        ctx.waitFor(mc -> {
            if (!order.contains("cell") && mc.level.getBlockState(cell).isAir()) {
                order.add("cell");
            }
            for (int dx = 14; dx >= 8; dx--) {
                String name = "head " + dx;
                if (!order.contains(name) && mc.level.getBlockState(
                        new BlockPos(BASE_X + dx, head, BASE_Z + 3)).isAir()) {
                    order.add(name);
                }
            }
            return !BehaviorRunner.isActive();
        });
        awaitChunkMiner(ctx, true);

        assertNotAir(ctx, upperStep, "the upper step");
        assertNotAir(ctx, lowerStep, "the lower step");
        assertAir(ctx, cell, "the cell over the lower step");
        final int cellAt = order.indexOf("cell");
        final int laterAt = order.indexOf("head 12");
        if (laterAt < 0 || cellAt > laterAt) {
            throw new AssertionError("Chunk miner took the cell over the lower step in the order "
                    + order + " — it waited for the back pass instead of going with (14, 3),"
                    + " the column that opened it");
        }
        LOGGER.info("Chunk miner took the waiting cell with its opener (order {})", order);
    }

    // ================================================================
    // Test 43: a row turn aims around the block it mines next
    // ================================================================

    /**
     * The turn into the next row. The bot works a column behind the one it
     * mines, so when a row ends it stands one column short of the end, and the
     * new row's first column lies on its diagonal. With that column's head
     * block gone, the floor block under it offers its top face, and the head
     * block of the column beside it hides the half of that face nearer the bot.
     * Reported from a real run, at the start of a resumed slab: look timeout on
     * {@code -16, 102, -60} face=up from {@code (-14.74, 102.00, -60.30)}, the
     * crosshair on {@code -15, 103, -60} — the head block beside it, and the
     * next task — with {@code los=true}. Then the retry queued the block in
     * flight a second time, and the bot stared at the cell it had just emptied
     * for another look timeout. A turn in the middle of a run two days earlier
     * logged the first half of that line for line.
     *
     * <p>The fixture is that world, shifted: row 3 cleared, (0, 4) a floor
     * block only, (1, 4) and (2, 4) whole, and the bot at the reported spot,
     * facing south in (1, 3) pressed against row 4. The spot is part of the
     * fixture, not a detail: from there the head block hides 43% of the top
     * face but not its centre, so the line-of-sight check has nothing to walk
     * for, and the lean toward that same head block moves the aim point into
     * the hidden part. The aim jitter is pinned to zero for the run, because a
     * draw that cancels the lean lets the old aim through — about one run in
     * nine, and this test's first run was one of them.
     *
     * <p>Asserts that no LOOKING visit runs into the look timeout, and that no
     * task looks at a cell that is already empty.
     */
    @MinecraftTest(name = "Chunk miner aims around the next block at a row turn",
            timeoutTicks = 3000, order = 43)
    public void aimsAroundTheNextBlockAtARowTurn(TestContext ctx) {
        prepareWithFloor(ctx, 1, 3, 0, 3, 2, 5);
        final List<BlockPos> blocks = new ArrayList<>();
        blocks.add(new BlockPos(BASE_X, Y, BASE_Z + 4));
        for (int dx = 1; dx <= 2; dx++) {
            blocks.add(new BlockPos(BASE_X + dx, Y + 1, BASE_Z + 4));
            blocks.add(new BlockPos(BASE_X + dx, Y, BASE_Z + 4));
        }
        for (BlockPos pos : blocks) {
            setBlock(ctx, pos, "stone");
        }
        final double standX = BASE_X + 1.26;
        final double standZ = BASE_Z + 3.70;
        ctx.runCommand("tp @s " + standX + " " + Y + " " + standZ + " 0 0");
        ctx.waitFor(mc -> mc.player.onGround()
                && Math.abs(mc.player.getX() - standX) < 0.01
                && Math.abs(mc.player.getZ() - standZ) < 0.01);

        final int lookTimeout = ctx.computeOnClient(mc -> BotController.CONFIG.lookTimeout);
        final Object[] lastTask = {null};
        final int[] looking = {0, 0};
        final Set<String> emptied = new LinkedHashSet<>();
        final double configuredOffsetMin = BotController.CONFIG.aimOffsetMin;
        final double configuredOffsetMax = BotController.CONFIG.aimOffsetMax;
        BotController.CONFIG.aimOffsetMin = 0.0;
        BotController.CONFIG.aimOffsetMax = 0.0;
        try {
            startChunkMiner(ctx, true, Y + 1, Y);
            ctx.waitFor(mc -> {
                var task = BotController.getCurrentTask();
                boolean inLooking = BotController.getPhase() == BotController.Phase.LOOKING;
                looking[0] = !inLooking ? 0 : task == lastTask[0] ? looking[0] + 1 : 1;
                looking[1] = Math.max(looking[1], looking[0]);
                lastTask[0] = task;
                if (inLooking && task instanceof MineBlockTask
                        && mc.level.getBlockState(task.targetPos()).isAir()) {
                    emptied.add(task.targetPos().toShortString());
                }
                return !BehaviorRunner.isActive();
            });
            awaitChunkMiner(ctx, true);
        } finally {
            BotController.CONFIG.aimOffsetMin = configuredOffsetMin;
            BotController.CONFIG.aimOffsetMax = configuredOffsetMax;
        }

        for (BlockPos pos : blocks) {
            assertAir(ctx, pos, "row 4 block");
        }
        if (looking[1] >= lookTimeout || !emptied.isEmpty()) {
            throw new AssertionError("Chunk miner stared: longest LOOKING visit " + looking[1]
                    + " ticks against a look timeout of " + lookTimeout
                    + ", looked at cells already mined: " + (emptied.isEmpty() ? "none" : emptied));
        }
        LOGGER.info("Chunk miner turned the row without a look timeout (longest look {} ticks)",
                looking[1]);
    }

    // ================================================================
    // Test 44: a retry leaves the block under the pick out
    // ================================================================

    /**
     * One block of a batch fails while the next one is under the pick. The
     * sweep tops the queue up mid-break, so the retry for the failed block is
     * decided while the controller is still breaking the next one — and it used
     * to queue that block again as well. The second task came up after the
     * first had emptied the cell, and LOOKING, which cannot hit air, spent two
     * look timeouts on it. Reported from a real run as the second of two pauses
     * at the start of a slab; the first is test 43's. Test 43 used to catch this
     * one too, but only through its own failure, and with the aim fixed nothing
     * fails there any more.
     *
     * <p>The fixture makes the failure certain instead of waiting for one: a
     * single column beside the bot, obsidian over stone. Head goes before feet,
     * so the obsidian is tried first, and the unenchanted diamond pickaxe needs
     * 188 ticks for it against a break budget pinned to 60. The task fails, the
     * controller takes the stone, and the queue is empty while the stone
     * breaks, which is exactly when the sweep verifies its batch. Both config
     * values the case hangs on are pinned, because the suite reads the
     * player's own files: a budget that lets the obsidian break never retries
     * it, and a run without step retries fails on the first verification.
     * With one retry the run ends on the obsidian's second failure.
     *
     * <p>Asserts that the retry was queued while the stone was still being
     * broken, without which this would pass on a fixture that no longer reaches
     * the case; that no task looked at a cell that was already empty; and that
     * the run ended on the obsidian.
     */
    @MinecraftTest(name = "Chunk miner leaves the block under the pick out of a retry",
            timeoutTicks = 3000, order = 44)
    public void leavesTheBlockUnderThePickOutOfARetry(TestContext ctx) {
        final BlockPos stand = prepare(ctx, STAND_DX, STAND_DZ);
        final BlockPos feet = stand.offset(1, 0, 0);
        final BlockPos head = feet.above();
        setBlock(ctx, feet, "stone");
        setBlock(ctx, head, "obsidian");

        final boolean[] retriedMidBreak = {false};
        final Set<String> emptied = new LinkedHashSet<>();
        final int configuredBreakTicks = BotController.CONFIG.maxBreakTicks;
        final int configuredRetries = MinerSetup.CONFIG.maxStepRetries;
        BotController.CONFIG.maxBreakTicks = 60;
        MinerSetup.CONFIG.maxStepRetries = 1;
        try {
            startChunkMiner(ctx, true, Y + 1, Y);
            ctx.waitFor(mc -> {
                var task = BotController.getCurrentTask();
                var queued = BotController.getTaskQueue().peek();
                if (BotController.getPhase() == BotController.Phase.INTERACTING
                        && task != null && task.targetPos().equals(feet)
                        && queued != null && queued.targetPos().equals(head)) {
                    retriedMidBreak[0] = true;
                }
                if (BotController.getPhase() == BotController.Phase.LOOKING
                        && task instanceof MineBlockTask
                        && mc.level.getBlockState(task.targetPos()).isAir()) {
                    emptied.add(task.targetPos().toShortString());
                }
                return !BehaviorRunner.isActive();
            });
            awaitChunkMiner(ctx, false);
        } finally {
            BotController.CONFIG.maxBreakTicks = configuredBreakTicks;
            MinerSetup.CONFIG.maxStepRetries = configuredRetries;
        }

        final String status = ctx.computeOnClient(mc -> MinerSetup.chunkMiner().statusLine());
        if (!retriedMidBreak[0]) {
            throw new AssertionError("The obsidian was never retried while the stone was under"
                    + " the pick, so the fixture no longer reaches the case — run ended: " + status);
        }
        if (!emptied.isEmpty()) {
            throw new AssertionError("Chunk miner looked at cells already mined: " + emptied);
        }
        if (!status.startsWith("cannot break " + head.getX() + ", " + head.getY() + ", " + head.getZ())) {
            throw new AssertionError("Run should have ended on the obsidian at " + head.toShortString()
                    + ", ended: " + status);
        }
        assertAir(ctx, feet, "stone under the obsidian");
        LOGGER.info("Chunk miner retried the obsidian without queuing the stone again ({})", status);
    }

    private static String shortList(Set<BlockPos> cells) {
        return cells.isEmpty() ? "nowhere"
                : String.join("; ", cells.stream().map(BlockPos::toShortString).toList());
    }

    /** Item entities still on the ground anywhere near the working area. */
    private int countItemsAround(TestContext ctx, BlockPos origin) {
        return ctx.computeOnClient(mc -> mc.level.getEntities(
                net.minecraft.world.entity.EntityType.ITEM,
                new net.minecraft.world.phys.AABB(origin).inflate(20.0),
                item -> true).size());
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
        return prepareWithFloor(ctx, Y, standDx, standDz, fromDx, toDx, fromDz, toDz);
    }

    /**
     * The same at a chosen height, for the staircase fixtures — see
     * {@link #STAIR_Y} for why they cannot use {@link #Y}.
     */
    private BlockPos prepareWithFloor(TestContext ctx, int y, int standDx, int standDz,
                                      int fromDx, int toDx, int fromDz, int toDz) {
        final BlockPos stand = new BlockPos(BASE_X + standDx, y, BASE_Z + standDz);
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
        fill(ctx, -1, y - 4, -1, 17, y + 6, 17, "air");
        fill(ctx, fromDx, y - 1, fromDz, toDx, y - 1, toDz, "stone");
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
        startChunkMiner(ctx, true, fromY, toY);
        awaitChunkMiner(ctx, expectSuccess);
    }

    /**
     * Start a run, with or without a requested range. {@code ranged} is what
     * {@code /miner chunk start} passes: without it the behavior derives the
     * range from where the bot stands, which is a different code path and the
     * one a person actually uses.
     */
    private void startChunkMiner(TestContext ctx, boolean ranged, int fromY, int toY) {
        ctx.runOnClient(mc -> {
            MinerSetup.chunkMiner().setRequestedRange(ranged, fromY, toY);
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
        startChunkMiner(ctx, true, Y + 1, Y);
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

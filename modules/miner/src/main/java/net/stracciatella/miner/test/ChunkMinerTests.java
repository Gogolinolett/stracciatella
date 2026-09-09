package net.stracciatella.miner.test;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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

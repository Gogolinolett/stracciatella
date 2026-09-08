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

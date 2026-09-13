package net.stracciatella.miner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.behavior.RestockNeeds;
import net.stracciatella.bot.humanize.HumanBehavior;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.task.BotTask;
import net.stracciatella.bot.task.MineBlockTask;
import net.stracciatella.bot.task.PlaceBlockTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mines out one whole chunk, layer pair by layer pair, the way a person
 * would: walk a 1-wide, 2-high corridor to the far side, step over one, walk
 * back, and when the level is bare dig straight down through your own feet
 * and start the next one.
 *
 * <p>Nothing about the progress is stored. The current slab is derived from
 * the world every time one is needed — the topmost layer pair inside the
 * requested range that still holds a diggable block. Stopping the run (or
 * losing the client) therefore resumes exactly where it left off, and no
 * saved cursor can ever disagree with what is actually still standing.
 *
 * <p>The bot never mines outside the target chunk. It does place outside it
 * where a liquid leaves no choice: to cap a source that would otherwise pour
 * in, and to dam the face a liquid at the chunk border flows through.
 *
 * <p>One thing inside the chunk is left standing too: the spiral staircase (see
 * {@link SpiralStairs}), one cell of every layer, so the bot can walk out of its
 * own pit and back in. {@link #isMinable} is where that happens, and it is the
 * only place it needs to — the sweep, the column scan and the finish condition
 * all ask it.
 */
public class ChunkMinerBehavior implements BotBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkMiner");

    private static final int CHUNK_SIZE = 16;
    private static final int SLAB_HEIGHT = 2;
    /**
     * Cap on a liquid flood fill — enough to tell a pool from an ocean. A
     * body that fits under it has been surveyed whole, sources included; one
     * that does not is dammed without asking where its water comes from. One
     * source on a flat floor spreads into a diamond of 113 cells, so this is
     * sized for a pool of one or two sources with their whole spread; it was
     * 16 for a long time, which no spread pool ever fit under, and every one
     * of them was dammed cell by cell across its flowing edge instead.
     */
    private static final int FLOOD_FILL_LIMIT = 128;
    /** At most this many sources get capped individually; beyond it, dam. */
    private static final int MAX_SEALABLE_SOURCES = 16;
    /**
     * How long flowing water with no source left is given to drain on its
     * own before it is dammed after all. Water recedes one level per
     * scheduled fluid tick, five ticks apart, so a capped pool's spread is
     * gone in well under this; the bound is for water that keeps arriving
     * from somewhere the survey did not reach.
     */
    private static final int RESIDUAL_WATER_TICKS = 100;
    /** Deepest drop the bot is allowed to open under its own feet. */
    private static final int MAX_SAFE_DROP = 2;
    /**
     * How many cells of the way to a column one call may walk. The line the
     * bot walks without pathfinding is at most {@code reachDistance + 2.5}
     * long, and a diagonal costs two grid steps per block of it, so this is
     * the whole of that with room to spare; the cap is there to bound a case
     * nobody has thought of, not to shape this one.
     */
    private static final int MAX_BRIDGE_STEPS = 12;
    /** Ticks to wait for the bot to fall into an opened cell before giving up. */
    private static final int MAX_DROP_WAIT_TICKS = 100;
    /**
     * How far above the current slab the staircase repair looks for missing
     * steps. Two layers would be enough for the usual case — a descent digs
     * exactly the two cells of the new slab in the bot's own column, so a step
     * it took is in the slab now being cleared. Four covers the one cell the
     * repair can legitimately decline: a step in the cell the bot's own body
     * occupies, which is skipped rather than failed and is then two layers
     * above the next slab's floor, out of the way and still in view.
     */
    private static final int STAIR_REPAIR_LOOKBACK = 4;
    /**
     * Filler blocks the run wants to carry. A stack, because a cap, a dam or a
     * bridged floor is one block at a time and a chunk rarely needs dozens —
     * fetching a second stack would only mean more of the inventory unavailable
     * for what is being mined.
     */
    private static final int FILLER_TARGET = 64;
    /** Meals to carry. Enough to keep the food bar up for hours of digging. */
    private static final int MEAL_TARGET = 16;

    private enum Phase {
        SELECT_SLAB,
        DESCEND,
        CLEAR
    }

    private final MinerConfig config;

    private Phase phase = Phase.SELECT_SLAB;
    private ChunkPos chunk;
    private int fromY;
    private int toY;
    private int slabFeetY;
    private int requestedFromY;
    private int requestedToY;
    private boolean rangeRequested;

    // Same execution contract as the diamond miner: one batch at a time, the
    // controller runs it, then the result is verified against the world.
    private final List<BlockPos> plannedBlocks = new ArrayList<>();
    private final ArrayDeque<List<BlockPos>> batchQueue = new ArrayDeque<>();
    // A queued filler placement is verified the same way a break is: without
    // that, a placement the server rejects would be re-planned every tick
    // forever, because the hole it was meant to fill is still a hole.
    private BlockPos pendingPlacement;
    private String placementWhat = "";
    // Column whose groundwork had nothing to be clicked from while the column
    // itself was still standing: a liquid walled in behind it (see
    // handleLiquidsAround), the floor cell under it, or both. Due as soon as
    // the column is open, through the opening it leaves.
    private BlockPos groundworkAfterOpening;
    // Where the sweep is in the snake: the column it took last, which is what
    // the next one is chosen next to. Null until the slab's first column, and
    // again after every descent, where the bot's own cell is the anchor.
    private BlockPos sweepColumn;
    // Steps the repair has already reported as unbuildable. A log latch only —
    // the cell is looked at again every time, because a dam or a floor laid
    // next to it can give it the face it was missing.
    private final Set<BlockPos> stepsWithoutSupport = new HashSet<>();
    // Whether the "no filler to rebuild a step with" line has been said. One per
    // run, like the controller's no-food warning: with nothing to place, every
    // column of the slab would repeat it.
    private boolean stairFillerWarned;
    private int stepRetries;
    private int breatherTicks;

    private int blocksMined;
    private String failReason;
    private int dropWaitTicks;
    // Column at which sourceless water is being waited out, and for how long
    // so far; see handleLiquidsAround.
    private BlockPos residualWaterColumn;
    private int residualWaterTicks;

    public ChunkMinerBehavior(MinerConfig config) {
        this.config = config;
    }

    /**
     * Layer range for the next start. Pass {@code false} to fall back to "from
     * the layer pair the player is standing in down to the configured bottom".
     */
    public void setRequestedRange(boolean requested, int newFromY, int newToY) {
        this.rangeRequested = requested;
        this.requestedFromY = newFromY;
        this.requestedToY = newToY;
    }

    @Override
    public String id() {
        return "chunk_miner";
    }

    /**
     * Every guard the policy layer offers. This behavior runs unattended for
     * a long time in a place where the supervising player is not watching:
     * damage means something found the bot, a swing means a player did, and a
     * full inventory means everything it mines from here on is lost. The two
     * collection flags are what make the drops actually end up in the
     * inventory without the run crawling.
     */
    @Override
    public BotPolicy policy() {
        BotPolicy policy = BotPolicy.none()
                .withDamageStop()
                .withPlayerAttackStop()
                .withOpportunisticCollection()
                .withFastCollectExit()
                // The serpentine already is the order, and it is the one thing
                // about this behaviour that must not be second-guessed: every
                // step of it is adjacent, which is what keeps the bot from
                // aiming at a column the one in front still hides.
                .withOrderedTasks()
                // Which only holds if the order can actually be followed. The
                // bot clears everything within reach without stepping, so at a
                // row's turn it stands two columns short of the end and the
                // corner of the next row is hidden behind its neighbour. This
                // is what lets it walk the last stretch instead of skipping the
                // corner and turning back for it.
                .withApproachOccluded()
                // A chunk is hours of standing in one place, and a bot on an
                // empty food bar stops regenerating without anyone noticing.
                // The meal goes in between two columns, where the hand is off
                // the button anyway.
                .withAutoEat();
        // Unconditional, restock or no restock: the runner asks the manifest
        // first and only falls through to this when the trip cannot happen (no
        // chest configured for this server). So the same threshold reads as
        // "go and empty your pockets" where that is possible and as "stop, from
        // here on everything you mine is lost" where it is not — and a server
        // nobody has configured behaves exactly as it did before restocking
        // existed.
        return policy.withInventoryFullStop(config.chunkMinerMinFreeSlots);
    }

    /**
     * What the run needs in the inventory to carry on: a pickaxe, a shovel,
     * something to place and something to eat, plus room for what it digs.
     *
     * <p>Opting in at all is a claim about this behavior, not a preference: a
     * restock suspends a run with {@code abort()} and resumes it with
     * {@code start()}, so only a behavior that finds its place again by reading
     * the world may declare a manifest. This one stores no progress whatsoever —
     * the slab is re-derived from what is still standing — which is exactly the
     * property that makes a trip to a chest free. The staircase is the other
     * half of it: without a way up its own pit the bot could not take the trip.
     *
     * <p>Both tools are wanted <b>one</b> at a time, and that is deliberate
     * rather than thrifty. {@code target} is read twice — as the trigger and as
     * the amount to fetch — so asking for three pickaxes would also mean a trip
     * to the chest the moment one of the three broke. The run mines until it has
     * nothing that drops the block in front of it, which is the reason the user
     * asked for ("kein Werkzeug ist ein Restock-Grund"), and the execution
     * layer reports that case by name through
     * {@code BotController.consumeMissingTool} before this count ever reaches
     * zero.
     *
     * <p>The filler entry doubles as the deposit rule by complement: everything
     * not on this list is loot and goes in the chest. That is why food is here
     * even though nothing about mining needs it — a bot with {@code autoEat}
     * that deposited its own bread would be hungry for the rest of the session.
     */
    @Override
    public RestockNeeds restockNeeds() {
        if (!config.chunkMinerRestock) {
            return RestockNeeds.none();
        }
        return new RestockNeeds.Builder()
                .need("a pickaxe", stack -> stack.is(ItemTags.PICKAXES), 1)
                .need("a shovel", stack -> stack.is(ItemTags.SHOVELS), 1)
                .need("filler blocks", InventoryHelper.blockIdMatcher(config.fillerBlocks),
                        FILLER_TARGET)
                .need("food", BotController::isMeal, MEAL_TARGET)
                .minFreeSlots(config.chunkMinerMinFreeSlots)
                .build();
    }

    @Override
    public void start(Minecraft client) {
        phase = Phase.SELECT_SLAB;
        chunk = null;
        plannedBlocks.clear();
        batchQueue.clear();
        pendingPlacement = null;
        groundworkAfterOpening = null;
        sweepColumn = null;
        stepsWithoutSupport.clear();
        stairFillerWarned = false;
        stepRetries = 0;
        breatherTicks = 0;
        blocksMined = 0;
        failReason = null;
        dropWaitTicks = 0;
        residualWaterColumn = null;
        residualWaterTicks = 0;

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        chunk = new ChunkPos(player.blockPosition());
        int worldFloor = client.level != null ? client.level.getMinY() : Integer.MIN_VALUE;
        // "From here down" reaches from the bot's head, not from its feet. The
        // top of the range is the *head* layer of the topmost slab — isMinable
        // caps there and tickSelectSlab starts the grid at fromY - 1 — so
        // taking the feet layer anchored the whole grid one level too low: the
        // slab the bot occupies could never be picked, and every start
        // descended before mining anything. On a resumed run that is the whole
        // bug. The bot stands in the slab it was clearing, so starting again
        // walked away from the half-finished level, dug one deeper, and left
        // the rest of that level's head layer above the range for good.
        fromY = rangeRequested ? requestedFromY : player.blockPosition().getY() + 1;
        toY = rangeRequested ? requestedToY : config.chunkMinerBottomY;
        if (toY < worldFloor) {
            toY = worldFloor;
        }
        LOGGER.info("Chunk miner: chunk {} layers {}..{}", chunk, toY, fromY);
    }

    @Override
    public void abort() {
        BotController.stop();
    }

    /** Whether the last run ended on a hazard rather than on finishing. */
    public boolean failed() {
        return failReason != null;
    }

    /**
     * Blocks broken so far this run. The pace tests divide their tick count by
     * it; the collected-item count they used before lags behind the break by
     * however long the drop takes to reach the inventory, which is the very
     * thing being measured.
     */
    public int blocksMined() {
        return blocksMined;
    }

    @Override
    public String statusLine() {
        if (failReason != null) {
            return failReason + " (blocks mined: " + blocksMined + ")";
        }
        if (chunk == null) {
            return "starting";
        }
        return phase + " chunk " + chunk.x + "," + chunk.z
                + " slab y=" + slabFeetY + ", blocks mined: " + blocksMined;
    }

    @Override
    public BehaviorStatus tick(Minecraft client) {
        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null || chunk == null) {
            return BehaviorStatus.RUNNING;
        }
        if (fromY < toY) {
            return fail("empty layer range " + toY + ".." + fromY);
        }
        // Planning runs on through COLLECTING instead of waiting for the
        // controller to go idle. With a block already queued, the moment the
        // last column's drops are in the controller starts the next break
        // instead of standing idle waiting to be handed one — the beat that
        // made the loop read as mine, stand, walk, mine.
        // ... and on through INTERACTING as well, which is the only moment
        // that removes the collect round rather than shortening it.
        // continueSeam — the path that skips both the collect and the reaction
        // beat — looks for a queued task at the tick a break confirms, so a
        // plan that arrives after the controller has left INTERACTING is one
        // phase too late however promptly it comes.
        //
        // This needs BotPolicy.withOrderedTasks and does not work without it:
        // handed a choice mid-break, pollNearest took a block behind the column
        // still standing in front of the bot, and the corridor test failed
        // outright while the stalling test fell from 19.1 to 62.0 ticks per
        // block. Restricted to the corridor sweep with no placement
        // outstanding — verifyPlacement would otherwise see its block still
        // missing and enqueue a second placement on top of the one in flight.
        BotController.Phase controllerPhase = BotController.getPhase();
        boolean collecting = controllerPhase == BotController.Phase.COLLECTING;
        boolean topUp = collecting
                || (controllerPhase == BotController.Phase.INTERACTING
                        && pendingPlacement == null && phase == Phase.CLEAR);
        if (BotController.isPaused() || !BotController.getTaskQueue().isEmpty()
                || (BotController.isActive() && !topUp)) {
            return BehaviorStatus.RUNNING;
        }

        if (pendingPlacement != null) {
            BehaviorStatus placed = verifyPlacement(player, level);
            if (placed != null) {
                return placed;
            }
            if (pendingPlacement != null) {
                return BehaviorStatus.RUNNING;
            }
        }

        if (!plannedBlocks.isEmpty()) {
            BehaviorStatus verified = verifyPlannedBlocks(player, level);
            if (verified != null) {
                return verified;
            }
            if (!plannedBlocks.isEmpty()) {
                return BehaviorStatus.RUNNING;
            }
        }

        // Occasional breather between columns — a few percent of them, not
        // every one. A run this long is where an even cadence would stand
        // out, but a pause on every column *is* an even cadence, and since
        // planning is what releases COLLECTING it sat in the critical path of
        // every column: measured at 3-6 ticks each, on top of the collect.
        if (breatherTicks > 0) {
            breatherTicks--;
            return BehaviorStatus.RUNNING;
        }

        if (!batchQueue.isEmpty()) {
            plan(batchQueue.poll());
            return BehaviorStatus.RUNNING;
        }

        // Only the corridor sweep may run early. Choosing a new slab or
        // digging down are decisions that want the finished world and the
        // bot's final position, and SELECT_SLAB is where the run *ends* —
        // finishing here would stop the controller in mid-collect and leave
        // the last column's drops lying there.
        if (collecting && phase != Phase.CLEAR) {
            return BehaviorStatus.RUNNING;
        }

        return switch (phase) {
            case SELECT_SLAB -> tickSelectSlab(player, level);
            case DESCEND -> tickDescend(player, level);
            case CLEAR -> tickClear(player, level);
        };
    }

    // --- Phases ---

    /**
     * Pick the topmost layer pair that still holds something to dig. This is
     * the resume mechanism: it reads the world, not a saved cursor.
     *
     * <p>Asked from where the bot stands, not from each slab's floor: a slab
     * below the bot is one it looks down on, and that is the only way into a
     * slab nobody has opened yet — see {@link #hasOpenFace}.
     */
    private BehaviorStatus tickSelectSlab(LocalPlayer player, Level level) {
        for (int feetY = fromY - 1; feetY >= toY - 1; feetY -= SLAB_HEIGHT) {
            if (slabHasWork(level, feetY, player.blockPosition().getY())) {
                slabFeetY = feetY;
                sweepColumn = null;
                phase = Phase.DESCEND;
                LOGGER.info("Chunk miner: working slab y={}..{}", feetY, feetY + 1);
                return BehaviorStatus.RUNNING;
            }
        }
        LOGGER.info("Chunk miner finished: chunk {} cleared, {} blocks mined", chunk, blocksMined);
        return BehaviorStatus.SUCCEEDED;
    }

    /**
     * Get the bot's feet down to the slab it is about to clear by digging
     * through its own column — one block per batch, so the bot drops a single
     * level at a time and always lands on something it has already looked at.
     *
     * <p>This is the one thing in the run that may break a step of the
     * staircase, and it is allowed to. The column holding the bot up is
     * sometimes a ring column, and refusing to dig it would leave the bot
     * standing on a step with solid rock on every side and no way down — there
     * is nothing here that can make it take a sideways pace. So the step comes
     * out and {@link #repairStairs} puts it back, which it can do as soon as the
     * sweep's first column has moved the bot off the cell. Both cells a descent
     * opens belong to the slab about to be cleared, so the repair is looking at
     * exactly them.
     */
    private BehaviorStatus tickDescend(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() <= slabFeetY) {
            phase = Phase.CLEAR;
            dropWaitTicks = 0;
            return BehaviorStatus.RUNNING;
        }
        BlockPos under = feet.below();
        if (level.getBlockState(under).isAir() && player.onGround()) {
            // The cell under the bot is open but the bot is not falling. That
            // is not a contradiction: blockPosition() rounds the player's
            // centre while the hitbox is 0.6 wide, so with the centre over the
            // opened cell the box can still rest on the neighbouring column.
            // The wait below then never ends — measured as 150 s in which the
            // whole game logged nothing, not one line even at DEBUG, because
            // no task is ever enqueued. Dig whatever is actually holding the
            // bot up; on a slab being cleared that block is work anyway.
            BlockPos support = standingSupport(player, level, under.getY());
            if (support != null) {
                under = support;
            }
        }
        if (!chunk.equals(new ChunkPos(under))) {
            return fail("standing outside the target chunk at " + shortPos(feet));
        }
        BehaviorStatus liquid = handleLiquidsAround(player, level, under);
        if (liquid != null) {
            return liquid;
        }
        BehaviorStatus footing = ensureSafeDrop(player, level, under);
        if (footing != null) {
            return footing;
        }
        BlockState state = level.getBlockState(under);
        if (state.isAir()) {
            // Already open — the drop happens on its own next tick. Bounded,
            // because "on its own" is an assumption about physics and a wrong
            // one here costs the whole run silently: this branch enqueues no
            // task, so a bot that never drops freezes the behaviour with the
            // game logging nothing at all, at any level. A fall of one layer
            // takes a handful of ticks; anything past this is a state worth
            // reporting rather than sitting in.
            if (++dropWaitTicks > MAX_DROP_WAIT_TICKS) {
                return fail("stuck at " + shortPos(feet) + " with " + shortPos(under)
                        + " open — not dropping into the slab");
            }
            return BehaviorStatus.RUNNING;
        }
        dropWaitTicks = 0;
        if (!isDiggable(state)) {
            return fail("cannot dig down through " + blockName(state) + " at " + shortPos(under));
        }
        plan(List.of(under));
        return BehaviorStatus.RUNNING;
    }

    /**
     * The first non-air block at {@code y} under the player's hitbox, or null
     * if nothing there holds it up. Used to find what the bot is really
     * standing on when that is not the column its centre is over.
     */
    private static BlockPos standingSupport(LocalPlayer player, Level level, int y) {
        AABB box = player.getBoundingBox();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!level.getBlockState(pos).isAir()) {
                    return pos;
                }
            }
        }
        return null;
    }

    /**
     * Clear the current slab one column at a time, in serpentine order.
     * Columns are never batched together: the controller picks the task
     * nearest the player, which on a straight corridor means it can target a
     * block two columns ahead that the near column still hides.
     */
    private BehaviorStatus tickClear(LocalPlayer player, Level level) {
        // Groundwork that had nowhere to be clicked from while the column
        // stood is done now, through the opening that column left: a liquid
        // that was walled in when the column was planned, and the floor cell
        // under the column itself — that opening is the only line of sight
        // there ever is to either. Not while a batch is still running, for the
        // same reason the groundwork below waits — a placement takes the plan
        // with it.
        if (groundworkAfterOpening != null && plannedBlocks.isEmpty()) {
            BehaviorStatus pending = handleLiquidsAround(player, level, groundworkAfterOpening);
            if (pending != null) {
                return pending;
            }
            BehaviorStatus footing = ensureFloor(player, level, groundworkAfterOpening.below());
            if (footing != null) {
                return footing;
            }
            groundworkAfterOpening = null;
        }
        BehaviorStatus stair = repairStairs(player, level);
        if (stair != null) {
            return stair;
        }
        BlockPos column = nextColumn(player, level);
        if (column == null) {
            phase = Phase.SELECT_SLAB;
            return BehaviorStatus.RUNNING;
        }
        // Groundwork is planned one thing at a time and takes the plan with it
        // (planPlacement clears it), so it must not start while the previous
        // batch still has a block under the pick: that block would drop out of
        // the plan while the controller keeps mining it, and nothing would ever
        // see it finish. Wait the one block out — the seam is worth skipping
        // where a cap, a dam or a floor is due anyway. Groundwork already owed
        // on the column just opened holds the sweep the same way: planning past
        // it would take the bot on over a hole that is not filled until the
        // batch has drained.
        if (!plannedBlocks.isEmpty()
                && (groundworkAfterOpening != null || !needsNoGroundwork(level, column))) {
            return BehaviorStatus.RUNNING;
        }
        // Planning mid-break reaches no further than the pickup box either.
        // Past it the bot mines a column whose cobble lands where it is not
        // standing, and nothing comes back for it: the collect round being
        // skipped is the thing that would have. Skipping it all the way down a
        // corridor cost five of fifteen drops and left the corridor test's
        // eight blocks mined with the cobble still on the floor. Letting the
        // round happen here is the whole point — the bot collects, walks, and
        // plans again from where it lands.
        if (!plannedBlocks.isEmpty() && !withinChainDistance(player, column)) {
            return BehaviorStatus.RUNNING;
        }
        // Access before work. A column out of reach means a walk, and a walk
        // over a gap is a step the controller refuses — the task then dies on
        // the position timeout without the bot having moved. Reached only
        // while no batch is running, which is already true here: out of reach
        // is well past CHAIN_DISTANCE, so the guard above has returned.
        BehaviorStatus step = ensureStepToward(player, level, column);
        if (step != null) {
            return step;
        }
        BehaviorStatus liquid = handleLiquidsAround(player, level, column);
        if (liquid != null) {
            return liquid;
        }
        List<BlockPos> blocks = new ArrayList<>();
        addColumn(level, column, blocks);
        if (blocks.isEmpty()) {
            return BehaviorStatus.RUNNING;
        }
        // A hole under the column is noted here and filled once the column is
        // out of the way, never before: the column is the roof over that cell,
        // so nowhere the bot can stand has a line to any face of it, and
        // findSupport hands back the column's own underside — a face a ray
        // from the side never meets, it lands on the wall instead. Three look
        // timeouts later the run died on a hole it could not see. Mining first
        // is what a person does, for the same reason: the hole is not there to
        // be filled until the block above it is gone.
        if (isPassable(level.getBlockState(column.below()))) {
            groundworkAfterOpening = column;
        }
        // Keep the queue stocked past the end of this column. The controller
        // only skips the collect-and-replan round when another task is already
        // queued and in reach (continueSeam), and planning a single column
        // guaranteed it never was: the queue ran dry on every second block. At
        // 5.0 ticks of actual breaking per block that round cost 5.3 in
        // COLLECTING and 1.8 idle, measured at normal tick rate. A person
        // digging a corridor does not stop after each column either — a drop
        // keeps its ten-tick pickup delay whether the bot stands over it or
        // mines on, and vanilla's pickup box takes it on the way past.
        //
        // Only columns needing no groundwork are chained: a cap or a dam has to
        // happen before the cell in front of it opens, and batching one in
        // would run it out of order. The column just planned counts too — with
        // its own floor still owed, a chain would carry the sweep on over a
        // hole that is not filled until the batch has drained.
        BlockPos scannedFrom = scanStart(player);
        sweepColumn = column;
        if (groundworkAfterOpening == null) {
            List<BlockPos> columns = slabColumns(slabFeetY);
            int index = columns.indexOf(column);
            // Only a scan that ran forward went past the column before this one;
            // on a back pass that column is the next one the pass has to take.
            if (index > columnIndex(columns, scannedFrom)) {
                addColumnBefore(level, columns, index, blocks);
            }
            for (int i = index + 1; i > 0 && i < columns.size(); i++) {
                BlockPos next = columns.get(i);
                if (!withinChainDistance(player, next) || !needsNoGroundwork(level, next)) {
                    break;
                }
                addColumn(level, next, blocks);
                addColumnBefore(level, columns, i, blocks);
                // Everything the chain walks over is planned or already empty,
                // so the sweep is past it whether it added anything or not.
                sweepColumn = next;
            }
        }
        plan(blocks);
        breatherTicks = HumanBehavior.randomBreatherTicks(BotController.CONFIG);
        return BehaviorStatus.RUNNING;
    }

    /**
     * How far a column may sit from the bot to join the same plan. Vanilla's
     * pickup box reaches 1.425 blocks on each horizontal axis — {@code
     * Player.touch} inflates the bounding box by 1.0 and the item is 0.25 wide
     * — so cobble from a column further out than that lands where the bot has
     * to walk back for it, which is the collect round the chaining exists to
     * remove.
     *
     * <p>Chaining out to the full reach (4.0) was measured and is worse than
     * this: the bot clears everything it can touch from one standing spot and
     * then walks back over four blocks of loot. It bought 4.1 ticks per block
     * of COLLECTING and gave back 24 ticks of SCANNING and POSITIONING plus a
     * 40-tick break in the mining — the aim test counted a long gap that was
     * not there before, and continuous mining is the point.
     */
    private static final double CHAIN_DISTANCE = 1.5;

    /**
     * Append the diggable, in-range cells of one column. Head before feet:
     * while the head block stands, the foot block's upward face is covered and
     * its side faces are hidden by the corridor wall, so aiming at it first
     * only burns a look timeout. Which is why the head being in this very batch
     * is what makes the foot plannable — see {@link #hasOpenFace}. A column
     * offered a second time, as {@link #addColumnBefore} does, adds only what
     * it did not add the first time.
     */
    private void addColumn(Level level, BlockPos column, List<BlockPos> into) {
        for (BlockPos pos : new BlockPos[] {column.above(), column}) {
            if (isMinable(level, pos) && !plannedBlocks.contains(pos) && !into.contains(pos)
                    && isDiggable(level.getBlockState(pos))
                    && hasOpenFace(level, pos, into, column.getY())) {
                into.add(pos);
            }
        }
    }

    /**
     * Offer the column the snake runs through just before {@code
     * columns[index]} again, once the plan takes that column whole. A cell with
     * no face open when the sweep reached it is not work yet, so the sweep went
     * past it, and the column after it is what opens it. Where the ramp turns
     * into the next row, the cell over the lower step has the upper step on one
     * side and the chunk wall and unmined rock on the others; the cell under the
     * upper step is the same the other way round. Left to {@link #nextColumn},
     * such a cell came back only on the back pass at the end of the slab, and a
     * real run showed what that looks like: {@code -1, 103, -61} still standing
     * beside the ramp while the sweep worked its way west along the row that
     * cell starts. Taken along here, it falls straight after the column that
     * opens it, from the same spot. Its drop lands beside that column's, so it
     * needs no distance bound.
     *
     * <p>Three conditions keep it to that cell. First, the sweep must actually
     * have gone past the column before. The chain always has, and the planned
     * column has only when {@link #nextColumn} reached it scanning forward —
     * the caller asks that. On a back pass the column before is the next one
     * the pass has to take, not one it left behind, and offering it there
     * batches it past {@link #CHAIN_DISTANCE}: the batching that bound exists
     * to prevent. Both earlier forms of this condition missed that. Offered
     * unconditionally, the corridor test's corridor — cleared on a back pass,
     * because it lies behind the bot in snake order — took its next column into
     * the plan from two blocks out. Offered only when that column had nothing
     * the sweep could take on its own, the staircase fixtures, whose bot enters
     * at the snake's end, took whole columns of sealed rock along their back
     * pass, because rock no neighbour has opened has no face of its own either.
     * Second, the column that opens it must fall whole. Only then is the
     * opening one a body stands in, with the eye level with the face; a feet
     * cell beside a head still standing, the gap under a step, leaves a
     * one-high slot a ray has to thread from above. Third, the column must need
     * no groundwork, which is the chain's own condition.
     *
     * <p>What this does not reach is a cell whose way in is not the next column
     * of the snake: the rest of a first row the ramp runs along, or a cell walled
     * in along its row by bedrock or the blacklist. Those still come on the back
     * pass.
     */
    private void addColumnBefore(Level level, List<BlockPos> columns, int index,
            List<BlockPos> into) {
        if (index <= 0) {
            return;
        }
        BlockPos opener = columns.get(index);
        BlockPos before = columns.get(index - 1);
        if (isOpen(level, opener.above(), into) && isOpen(level, opener, into)
                && needsNoGroundwork(level, before)) {
            addColumn(level, before, into);
        }
    }

    /**
     * The faces an eye on or above a two-layer slab can ever put a ray on;
     * whether it is above the top one is {@link #hasOpenFace}'s question.
     */
    private static final Direction[] AIMABLE_FACES = {
        Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * Whether {@code pos} has a face a ray can reach from a bot standing in
     * layer {@code standingY}: one of its top or four sides already open, or
     * open by the time the bot aims at it because this plan takes that
     * neighbour out first. Its underside does not count — inside a two-layer
     * slab the eye is never below the cell, so no standing spot can see it.
     *
     * <p>The head-before-feet order of {@link #addColumn} is the special case
     * this generalises, and the staircase is what made the general rule
     * necessary. A step is never mined, so the cell <em>under</em> a step has a
     * lid that no batch will ever lift; the step one layer down sits on the next
     * ring cell along, which covers a second face; and the remaining two are
     * solid rock until the sweep reaches them. {@code faceTowardPlayer} then
     * does what its own javadoc says it does when every candidate is covered —
     * it hands back the dominant face anyway, "callers clear the blockers
     * first" — and this caller cannot: the blocker is the staircase. The bot
     * climbed onto the step, aimed at the lid under its own feet, and the run
     * died on {@code cannot break -30, 84, -64} after 1334 blocks, with the
     * hit result naming the step every time.
     *
     * <p>Deferring is safe because it is not a retry after a failure — the
     * distinction that matters against the alternative rejected in STR-063.
     * Nothing is given up on: the cell stays work, {@link #slabHasWork} asks the
     * same question so the slab is not declared finished over it, and the
     * column that opens it takes it along ({@link #addColumnBefore}), with the
     * back pass at the end of the slab behind that for a cell no column of the
     * sweep opens in passing. Only if a cell still has no open face when
     * everything else in the slab is mined does the run move on without it, and
     * then it really is sealed — wedged between the staircase and bedrock or a
     * blacklisted seam.
     * Asking the question in {@code slabHasWork} too is what keeps that case
     * from becoming the hang it would otherwise be: a slab that reports work no
     * column will accept loops SELECT_SLAB → CLEAR → SELECT_SLAB forever.
     *
     * <p>A face also has to be one an <b>eye</b> can get in front of, which is
     * not the same as being open, and a real run is what made the difference
     * explicit. At the chunk's own corner the cell under the first step has that
     * step for a lid, the second step for its east neighbour and unmined rock to
     * the south — but west and north it opens into the ground outside the chunk,
     * where the quarry's rim leaves air at the slab's level under standing rock.
     * Both of those counted as faces, so the cell was planned; the bot walked up
     * the staircase to reach the only thing it could get near, which put it on
     * the lid, and the run died on {@code cannot break -16, 106, -64 (blocks
     * mined: 138)} with {@code hitResult} naming the step under its own feet
     * every time. Hence the one extra test on a side neighbour: it has to lie
     * inside the chunk. An in-chunk neighbour needs nothing further asked of
     * it — the sweep takes whole columns, so a cell whose foot is open has an
     * open head as well, and a bot standing in the slab carries its eye in the
     * head layer, level with the face. Requiring that second cell open too was
     * written first and reverted: above the head layer of the <em>topmost</em>
     * slab stands rock the range does not cover, so every head-layer cell of a
     * run started under a low ceiling would have been deferred for good, and
     * {@link #slabHasWork} would have declared that layer finished. Deferred,
     * this cell is mined from the south a few columns later, from inside the
     * slab, at eye level.
     *
     * <p><b>A top face is a face only to an eye above it</b>, which makes it the
     * one part of this that depends on where the bot stands rather than on the
     * cell. On the slab's floor the eye is 1.62 up, inside the head layer: over
     * a foot cell's top — this column's own head cell, the case head-before-feet
     * is about — and under a head cell's, which is the floor of the slab
     * overhead and therefore open on every slab but the first. Counted anyway,
     * it made this rule say yes to the whole head layer, and a real run found
     * the cell where that matters: the one over the lower step, whose neighbour
     * along the ring is the upper step. The sweep turned into its row right
     * there, with the rest of that row and the next one still standing, and the
     * run died on {@code cannot break -1, 103, -61 (blocks mined: 25)} —
     * {@code face=up}, three look timeouts from one unchanging spot, the hit
     * result on the step. Asked from the floor, it now waits for a side the way
     * the cell under a step does, and goes with the next column of its row,
     * which opens one.
     *
     * <p>The sweep asks from the slab's floor and choosing a slab asks from
     * wherever the bot is, and that difference is the point rather than a
     * disagreement. From the slab above, a head cell's top is exactly what the
     * bot looks down on, and a slab nobody has entered yet has no other face
     * open anywhere: asked from its own floor, every untouched slab under the
     * bot would read as finished and the run would end there. For the slab the
     * bot is standing in the two questions are one question, which is what
     * keeps a cell only its top opens from looping SELECT_SLAB → CLEAR →
     * SELECT_SLAB.
     */
    private boolean hasOpenFace(Level level, BlockPos pos, List<BlockPos> batch, int standingY) {
        for (Direction face : AIMABLE_FACES) {
            // A standing eye is 1.62 up: over the top of a cell in its own
            // layer, under the top of one in the layer above.
            if (face == Direction.UP && pos.getY() > standingY) {
                continue;
            }
            BlockPos neighbour = pos.relative(face);
            // A side face is only a face if an eye of this run can get in front
            // of it, and outside the chunk none ever can: those cells are never
            // mined and never walked to, so an opening out there is a hole in
            // the wall rather than a way in. The top face needs no such test —
            // the cell over it is in this column, so in the chunk.
            if (face != Direction.UP && !chunk.equals(new ChunkPos(neighbour))) {
                continue;
            }
            if (isOpen(level, neighbour, batch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether nothing stands in {@code pos} by the time the bot aims past it:
     * open already, or taken out first by {@code batch} or the standing plan.
     */
    private boolean isOpen(Level level, BlockPos pos, List<BlockPos> batch) {
        return isPassable(level.getBlockState(pos)) || batch.contains(pos)
                || plannedBlocks.contains(pos);
    }

    /**
     * Whether a column can simply be mined: a floor already under it and no
     * liquid touching either of its cells. Both are interventions that must
     * run before the cell is opened, so a column needing one never joins
     * another column's plan.
     */
    private boolean needsNoGroundwork(Level level, BlockPos column) {
        return !isPassable(level.getBlockState(column.below()))
                && liquidsTouching(level, column).isEmpty();
    }

    private boolean withinChainDistance(LocalPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                <= CHAIN_DISTANCE * CHAIN_DISTANCE;
    }

    // --- Slab geometry ---

    private boolean slabHasWork(Level level, int feetY, int standingY) {
        for (BlockPos column : slabColumns(feetY)) {
            for (BlockPos pos : new BlockPos[] {column, column.above()}) {
                if (isMinable(level, pos) && isDiggable(level.getBlockState(pos))
                        && hasOpenFace(level, pos, List.of(), standingY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The next column with something to dig, following the snake onward from
     * the one the sweep took last.
     *
     * <p>Starting at the chunk corner instead would break as soon as the
     * columns in between hold nothing — a blacklisted seam, a strip already
     * cleared, the column the bot just descended through. The bot would be
     * sent at a block several columns away that is still inside its reach, so
     * the controller never walks over, but is hidden behind the columns that
     * were skipped: the look times out and the run dies on a block it never
     * had a line to. Resuming where the sweep is keeps every step of it
     * adjacent to the last, which is the whole point of a serpentine.
     *
     * <p>Where the sweep is, not where the bot's feet are — that distinction
     * cost a run. The two agree while the bot is mining and part company while
     * it is collecting: it walks after cobble that vanilla scatters up to half
     * a block off the column it fell from, and half a block is all it takes to
     * put its feet in the row next door. Anchored there, the scan runs the rest
     * of that row, and then enters the row it was actually clearing from the
     * far end — because the snake alternates direction, so neighbouring rows
     * are entered from opposite sides. Logged from the corridor test: the bot
     * cleared 3016 and was handed 3019 with 3017 and 3018 still standing, the
     * ray stopped by 3017, nowhere to walk, and {@code cannot break 3019, 41,
     * 3015 (blocks mined: 2)} three look timeouts later. It came and went with
     * the way the drops happened to bounce. The sweep's own place in the snake
     * does not bounce, so that is what the scan is anchored on; the bot's cell
     * is the fallback for the slab's first column, where the sweep has no place
     * yet and the bot has just descended into one.
     *
     * <p>Which is why the scan never wraps. Wrapping past the end of the snake
     * is the same non-adjacent jump by another name: with the sweep finished
     * ahead of the bot, the modulo sent it back to the far corner and it walked
     * the row forward from there, reaching a column three over while the one
     * right behind it still stood in the line of sight. Instead the scan runs
     * the snake's own direction to the end and only then back over what was
     * left behind, nearest first — which picks that near leftover up.
     *
     * <p>The bot enters a slab wherever it descended, so its own row is the one
     * row the snake cannot hand it whole: running forward leaves a stub behind
     * it, and the back pass only reaches that stub after every other row. Logged
     * on the aim patch with the bot entering at index 120 — it dug 121, 122, 123,
     * crossed into the next row for 132 through 138, and came back for 119, 118,
     * 117 a row later; in a full chunk that stub is up to half a row. Sweeping
     * the row's own leftovers first was tried and reverted: it turns the entry
     * row into one clean there-and-back, but the walk back over the stub cost
     * 0.7-3.4 ticks per block and ~500 degrees of yaw across three runs of the
     * aim test. It is a fixed cost per slab — under 1% of a 256-column slab, a
     * fifth of a 20-column fixture — so the tests cannot settle it, and the
     * order was left as it is rather than spend measured pace on it.
     *
     * <p>Running it forward *to the end* is the part that matters, and it
     * replaced alternating forward and back at each distance. Alternating reads
     * fine while the bot is walking, but a whole group of columns inside reach
     * is dug without a step: {@code start} never moves, so every column dug
     * hands the next turn to the other side and the bot ping-pongs across
     * itself. Measured on a slab with the bot at x=3015, it dug 3016, then
     * 3014, then 3017 — three ~150° head turns in a row, and since the pickaxe
     * is idle for the whole of LOOKING, three gaps in the mining to match. Once
     * one direction is exhausted the bot turns around exactly once, which is
     * what a serpentine is for.
     *
     * <p>A column that is momentarily hidden is <b>no longer skipped</b>. It
     * used to be: the snake stepping past an empty column leaves the bot
     * standing *diagonally* to the next one with the orthogonal neighbour still
     * up (measured in a real world, target -16,110,-6 with the head block
     * -15,111,-6 in the line and the bot 1.75 blocks away), and with nothing
     * able to walk the bot clear, LOOKING timed out twice and the run died on a
     * block that was merely hidden for the moment. Skipping it was the cheap
     * answer and it is what puts a row's corner out of order — the bot digs
     * past the corner and turns back for it, one extra turn per row. The
     * expensive answer is now available instead:
     * {@link BotPolicy#withApproachOccluded} makes the controller walk until
     * the sight line is clear, so the column can simply be handed over in its
     * place. A block that stays hidden is bounded the same way as before, by
     * the position and look timeouts and {@code maxStepRetries}.
     */
    private BlockPos nextColumn(LocalPlayer player, Level level) {
        List<BlockPos> columns = slabColumns(slabFeetY);
        int start = columnIndex(columns, scanStart(player));
        for (int i = 0; i < columns.size(); i++) {
            int ahead = start + i;
            int index = ahead < columns.size()
                    ? ahead
                    : start - 1 - (i - (columns.size() - start));
            if (index < 0 || index >= columns.size()) {
                continue;
            }
            BlockPos column = columns.get(index);
            if (hasWork(level, column)) {
                return column;
            }
        }
        return null;
    }

    /**
     * Whether either cell of a column is work this plan has not taken yet.
     * Head first, matching the order tickClear plans them in: that is the
     * block LOOKING aims at, so that is the one to test. A position already in
     * the plan is not work left to find — the block still under the pick would
     * otherwise be handed back and the sweep would never move past its own
     * column.
     */
    private boolean hasWork(Level level, BlockPos column) {
        for (BlockPos pos : new BlockPos[] {column.above(), column}) {
            if (isMinable(level, pos) && !plannedBlocks.contains(pos)
                    && isDiggable(level.getBlockState(pos))
                    && hasOpenFace(level, pos, List.of(), column.getY())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block standing between the bot's eye and {@code owed}, or null when
     * the line to it is clear. Diagnostics only — nothing is decided on it.
     *
     * <p>A retry has exactly two reasons to be here and they want opposite
     * fixes: the way there is missing its floor, which {@code ensureStepToward}
     * mends, or something is standing in it, which no amount of walking will
     * help because POSITIONING and LOOKING both work in a straight line. The
     * retry line used to report the first as though it were the only one —
     * "nothing to mend on the way there", true and useless, printed three times
     * over while a column two cells ahead sat in the sight line and went
     * unnamed.
     *
     * <p>The same clip the controller's {@code los=} comes from, so the two
     * lines in the log agree instead of each having their own idea of what the
     * bot can see.
     */
    private BlockPos columnInTheWay(LocalPlayer player, Level level, BlockPos owed) {
        BlockHitResult clip = level.clip(new ClipContext(player.getEyePosition(),
                Vec3.atCenterOf(owed), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                player));
        return clip.getType() == HitResult.Type.BLOCK && !clip.getBlockPos().equals(owed)
                ? clip.getBlockPos()
                : null;
    }

    /**
     * Where {@link #nextColumn} starts looking: the column the sweep took last,
     * or the bot's own column while the slab has none yet.
     */
    private BlockPos scanStart(LocalPlayer player) {
        return sweepColumn != null ? sweepColumn : player.blockPosition();
    }

    /** Index of the bot's own column in the sweep, or 0 if it stands outside. */
    private static int columnIndex(List<BlockPos> columns, BlockPos feet) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getX() == feet.getX() && columns.get(i).getZ() == feet.getZ()) {
                return i;
            }
        }
        return 0;
    }

    /**
     * The chunk's 256 columns at the given feet height, in serpentine order:
     * along a row, step over, back along the next. The direction of the first
     * row alternates per slab, so a finished slab hands the next one a start
     * next to where it stopped instead of across the chunk.
     */
    private List<BlockPos> slabColumns(int feetY) {
        int slabIndex = Math.floorDiv(fromY - 1 - feetY, SLAB_HEIGHT);
        List<BlockPos> columns = new ArrayList<>(CHUNK_SIZE * CHUNK_SIZE);
        for (int packed : SerpentinePlan.order(slabIndex)) {
            columns.add(new BlockPos(
                    chunk.getMinBlockX() + (packed % CHUNK_SIZE),
                    feetY,
                    chunk.getMinBlockZ() + (packed / CHUNK_SIZE)));
        }
        return columns;
    }

    // --- Staircase ---

    /**
     * Put back any step of the staircase that is missing from the layers the bot
     * can still reach, or null when the ramp is whole. One placement per call,
     * like every other placement here.
     *
     * <p>A step goes missing two ways. A cave took it, which is the case the
     * user asked for — and the bot has to walk through that cave to get out, so
     * an empty ring cell there is a two-block climb the mesh cannot make. Or the
     * descent took it: {@code tickDescend} digs through whatever is holding the
     * bot up, and that is sometimes a ring column, which is why this runs
     * <em>during</em> the sweep rather than at the end of it. The sweep's first
     * column moves the bot off the cell it landed in, and the step goes back in
     * on the next call — early, while the slab is still being cleared, so a run
     * stopped in the middle of one leaves a ramp that can be climbed.
     *
     * <p>Derived from the world, not remembered: the repair asks
     * {@link SpiralStairs#stepAt} which cell each layer owes and looks. No record
     * of what was dug has to agree with anything.
     *
     * <p>Bounded above by {@link #STAIR_REPAIR_LOOKBACK}, and the bound is the
     * known limit of this: a step further up than that is out of reach from the
     * slab floor, and there is nothing to stand on in between to get to it. The
     * window covers the slab being cleared and the one above it, so a cave up to
     * a slab tall is mended; a taller void leaves the ramp broken, and what
     * reports that is the restock's own walk failing, not a guess made here.
     *
     * <p>A step that <em>cannot</em> be attempted — nothing to build against,
     * nothing in the inventory to build with — is skipped rather than fatal,
     * unlike a floor. A floor is planned because the bot is about to walk
     * somewhere it would otherwise fall; a step is the way out of a hole the bot
     * is not in yet, and ending the run on the spot would turn every cave that
     * happens to cross the chunk's edge into a failure. In a cave the step's
     * every neighbour is gone — the cell below it is in range and mined, the
     * ring cells beside it too, and the one face that is normally there, the
     * chunk wall outside, is what the cave took. A step that <em>is</em>
     * attempted is held to the same bar as every other placement: it goes
     * through {@link #verifyPlacement}, and one that keeps failing ends the run,
     * because then the bot is digging itself into a pit it has been unable to
     * leave a way out of.
     */
    private BehaviorStatus repairStairs(LocalPlayer player, Level level) {
        // A placement takes the plan with it (planPlacement clears it), so the
        // same rule the groundwork follows: never while a block is under the
        // pick.
        if (!plannedBlocks.isEmpty()) {
            return null;
        }
        int top = Math.min(slabFeetY + STAIR_REPAIR_LOOKBACK - 1, fromY);
        for (int y = Math.max(slabFeetY, toY); y <= top; y++) {
            BlockPos step = SpiralStairs.stepAt(chunk, y);
            if (!isPassable(level.getBlockState(step))) {
                continue;
            }
            // Asked rather than re-derived: the sweep and the repair have to
            // mean the same thing by "step", or one builds what the other mines.
            // On a cell already known to be empty this is the ramp's top — no
            // step is owed in the pocket the bot is standing in.
            if (!isStep(level, step)) {
                continue;
            }
            // The cell the bot is standing or breathing in. Nothing can be
            // placed there, and the sweep is about to move it: the lookback
            // window still covers this layer from the next slab down, where the
            // bot is two levels under it.
            if (player.getBoundingBox().intersects(new AABB(step))) {
                continue;
            }
            if (InventoryHelper.countMatching(player, fillerPredicate()) == 0) {
                if (!stairFillerWarned) {
                    stairFillerWarned = true;
                    LOGGER.warn("Chunk miner: the step at {} is missing and there is no filler"
                            + " block to rebuild it with", shortPos(step));
                }
                return null;
            }
            BlockPos support = PlaceBlockTask.findSupport(level, step, player.getEyePosition());
            if (support == null) {
                // Said once per cell, not once per column: this is asked before
                // every column of the slab, and a cave at the chunk edge would
                // otherwise fill the log with the same line a thousand times.
                if (stepsWithoutSupport.add(step)) {
                    LOGGER.warn("Chunk miner: the step at {} is missing and has nothing to build"
                            + " against — the way out has a gap there", shortPos(step));
                }
                continue;
            }
            LOGGER.info("Chunk miner: rebuilding the missing step at {}", shortPos(step));
            return planPlacement(step, support, "step");
        }
        return null;
    }

    // --- Safety ---

    /**
     * Make sure the bot lands on something when the block at {@code pos} is
     * opened up. A hole deeper than a safe drop is filled in from the nearest
     * sturdy neighbour rather than walked into.
     */
    private BehaviorStatus ensureSafeDrop(LocalPlayer player, Level level, BlockPos pos) {
        int open = 0;
        BlockPos below = pos.below();
        while (open <= MAX_SAFE_DROP && isPassable(level.getBlockState(below))) {
            open++;
            below = below.below();
        }
        if (open <= MAX_SAFE_DROP) {
            return null;
        }
        return ensureFloor(player, level, pos.below());
    }

    /**
     * Bridge the way to a column the bot cannot reach from where it stands.
     *
     * <p>Every column is worked from the cell beside it, whether the walk
     * there is one step or five, and that walk is the one thing the slab's
     * own floor does not guarantee. POSITIONING refuses a
     * step over a cell with nothing under it, which is right and is what keeps
     * the bot out of caves, but the refusal leaves the task to die on the
     * position timeout, and the run ends with {@code cannot break} at a column
     * the bot never got near. Reported from a real world at y=92..93: two
     * columns mined from 3.6 and 3.8 blocks away, the next one at 4.77, and
     * the bot sat on the same spot through six tasks without moving a
     * millimetre ({@code pathActive=false crouched=false under=stone}).
     *
     * <p>So fill the gap the way a player bridges: one cell into it, step on,
     * ask again. Nothing has to remember a route — the sweep re-reads the
     * world every tick. The support {@code findSupport} hands back is the
     * floor block beside the gap, whose face is horizontal and below the eye,
     * and that is the placement only the edge step in {@code BotController}
     * can reach: this is the miner's half of the bridging technique.
     *
     * <p>Sampled along the line the bot will actually walk, and only up to the
     * cell beside the column — the one under it belongs to
     * {@code groundworkAfterOpening} and must wait until the column is out of
     * the way; filling it from here would be exactly the ordering bug that
     * case exists to prevent.
     */
    private BehaviorStatus ensureStepToward(LocalPlayer player, Level level, BlockPos column) {
        BlockPos feet = player.blockPosition();
        if (feet.getY() != slabFeetY) {
            return null;
        }
        // The cells the bot will cross, in the order it crosses them. Neither
        // walk that gets it there pathfinds: POSITIONING and COLLECTING both
        // hold the walk key in view direction, so the way to a column is the
        // straight line to it and nothing else. Sampling an L instead — the
        // whole of one axis, then the other — asks about cells round the
        // outside of that line and never about the diagonal ones on it. From a
        // real world: `retrying -29, 93, -61 — nothing to mend on the way
        // there`, the L's row solid the whole way, and the bot standing still
        // in front of -26, 91, -62, which is open and on the line.
        //
        // A grid traversal gives both halves of what this needs. It visits
        // every cell the line passes through, and it still advances one axis
        // at a time, so each cell is orthogonally next to the last one and a
        // placement always has a face to build against.
        double x = player.getX();
        double z = player.getZ();
        double dirX = (column.getX() + 0.5) - x;
        double dirZ = (column.getZ() + 0.5) - z;
        double invX = Math.abs(dirX) < 1.0E-6 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirX);
        double invZ = Math.abs(dirZ) < 1.0E-6 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirZ);
        int stepX = dirX >= 0 ? 1 : -1;
        int stepZ = dirZ >= 0 ? 1 : -1;
        int cx = feet.getX();
        int cz = feet.getZ();
        // Guarded rather than multiplied out: a bot standing exactly on a grid
        // line with no travel along that axis gives infinity times zero, and
        // NaN loses every comparison, which would pick the wrong axis.
        double nextX = invX == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY
                : invX * (stepX > 0 ? cx + 1 - x : x - cx);
        double nextZ = invZ == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY
                : invZ * (stepZ > 0 ? cz + 1 - z : z - cz);
        BlockPos footing = feet.below();
        for (int laid = 0; laid < MAX_BRIDGE_STEPS; laid++) {
            // Beside the column is the end of the way, not within reach of it.
            // Reach is what the break needs; what falls out of the break needs
            // a bot that can walk to where it lands, and COLLECTING has no
            // pathfinder to go round with — it walks this same line and stops
            // where the line stops. Stopping at reach let the bot mine a whole
            // face from one cell of bridge and then abandon every drop of it
            // on the far side of the gap it had just declined to fill.
            if (Math.abs(column.getX() - cx) + Math.abs(column.getZ() - cz) <= 1) {
                return null;
            }
            if (nextX <= nextZ) {
                nextX += invX;
                cx += stepX;
            } else {
                nextZ += invZ;
                cz += stepZ;
            }
            BlockPos floor = new BlockPos(cx, slabFeetY - 1, cz);
            if (!isSturdyFloor(level, floor)) {
                if (!isPassable(level.getBlockState(floor))
                        || !chunk.equals(new ChunkPos(floor))
                        || !isSturdyFloor(level, footing)) {
                    // Nothing to build, or nothing to build it from, or it is
                    // off the chunk. The task fails on the position timeout
                    // exactly as it did before this existed.
                    return null;
                }
                LOGGER.info("Chunk miner: bridging the floor at {}", shortPos(floor));
                return planPlacement(floor, footing, "floor");
            }
            footing = floor;
        }
        return null;
    }

    /** Something the bot can stand on top of — the slab's own floor, or a
     * block just laid to extend it. Deliberately not the controller's
     * {@code hasFloorWithinOneBlock}: that one tolerates a drop of a block,
     * because it is answering whether a step is survivable, and a step down is.
     * The sweep is answering something else — whether a cell is the floor it
     * is working from — and only the slab's own level is. Borrowing the
     * controller's answer let the bot walk down into a one-block dip, out of
     * line with every column behind it, and place its next block beside its
     * own head. */
    private static boolean isSturdyFloor(Level level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
    }

    /** Put a block at {@code pos} if nothing solid is there to stand on. */
    private BehaviorStatus ensureFloor(LocalPlayer player, Level level, BlockPos pos) {
        if (!isPassable(level.getBlockState(pos))) {
            return null;
        }
        if (!chunk.equals(new ChunkPos(pos))) {
            return fail("no floor at " + shortPos(pos) + ", which is outside the chunk");
        }
        // Said out loud, the way capping and damming are. A hole is the one
        // thing the sweep meets that moves the bot somewhere it did not plan
        // to go, and without this line a run that ends up in one leaves no
        // trace of whether the fill was even reached: the placement itself is
        // silent unless it fails.
        LOGGER.info("Chunk miner: filling the floor at {}", shortPos(pos));
        return planPlacement(player, level, pos, "floor");
    }

    // --- Liquids ---

    /**
     * Every fluid cell touching either half of a column, the column's own two
     * cells included. Read-only, which is what lets {@link #needsNoGroundwork}
     * ask the same question without triggering the capping and damming that
     * {@link #handleLiquidsAround} does when the answer is non-empty.
     */
    private Set<BlockPos> liquidsTouching(Level level, BlockPos column) {
        Set<BlockPos> touching = new LinkedHashSet<>();
        for (BlockPos cell : new BlockPos[] {column, column.above()}) {
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = cell.relative(dir);
                if (!level.getFluidState(neighbor).isEmpty()) {
                    touching.add(neighbor);
                }
            }
            if (!level.getFluidState(cell).isEmpty()) {
                touching.add(cell);
            }
        }
        return touching;
    }

    /**
     * Deal with any liquid touching the cell about to be opened, before it is
     * opened. Water that comes from a handful of sources gets each source
     * capped — cheap, permanent, and it leaves the chunk dry. Anything bigger,
     * and all lava, gets dammed at the face it would flow in through: chasing
     * an ocean's sources is endless, and letting lava burn itself out into
     * obsidian costs far more time than a block of cobble.
     *
     * <p>Returns null when there is nothing to do — which includes water that
     * is still walled in: that one is only noted, and dealt with once the wall
     * in front of it is gone.
     */
    private BehaviorStatus handleLiquidsAround(LocalPlayer player, Level level, BlockPos column) {
        Set<BlockPos> touching = liquidsTouching(level, column);
        if (touching.isEmpty()) {
            return null;
        }

        BlockPos start = touching.iterator().next();
        boolean lava = level.getFluidState(start).is(net.minecraft.tags.FluidTags.LAVA);
        List<BlockPos> body = floodFill(level, start);
        // Water still boxed in by solid blocks — the aquifer behind the wall
        // the bot is about to break — cannot be sealed yet. Every face it
        // could be built against lies behind that wall, so aiming at one only
        // burns a look timeout, and that is how a run ended at the first chunk
        // it dug up against water. It cannot flow anywhere either: the wall is
        // the dam until it comes out, and the seal goes in afterwards, through
        // the opening it leaves. Lava gets no such benefit — mining into it
        // costs health and the run, so stopping while it is still walled off
        // is the better answer.
        if (!lava && !touchesOpenSpace(level, body)) {
            groundworkAfterOpening = column;
            return null;
        }
        List<BlockPos> sources = new ArrayList<>();
        for (BlockPos pos : body) {
            if (level.getFluidState(pos).isSource()) {
                sources.add(pos);
            }
        }
        boolean surveyed = body.size() < FLOOD_FILL_LIMIT;

        List<BlockPos> toSeal;
        boolean capping = !lava && surveyed && !sources.isEmpty()
                && sources.size() <= MAX_SEALABLE_SOURCES;
        if (capping) {
            // Pool fully surveyed: cap the sources themselves — an uncapped
            // source next door refills the chunk as fast as it is dug — and
            // only those. The spread drains by itself once they are gone, so
            // a block on a flowing cell is a block for nothing, and that is
            // what every spread pool got while the survey limit was 16: no
            // pool with its spread fits under it, so none was ever surveyed
            // whole, and each was walled up across its flowing edge instead.
            toSeal = sources;
        } else if (!lava && surveyed && sources.isEmpty()) {
            // Flowing water with no source anywhere in it is what a capped
            // pool leaves behind, and it is on its way out — water recedes a
            // level per fluid tick. Let it, for a while, standing still.
            // Water still there past the bound is fed from somewhere the
            // survey did not reach, and gets the dam after all.
            if (!column.equals(residualWaterColumn)) {
                residualWaterColumn = column;
                residualWaterTicks = 0;
                LOGGER.info("Chunk miner: waiting for sourceless water at {} to drain",
                        shortPos(start));
            }
            if (residualWaterTicks++ < RESIDUAL_WATER_TICKS) {
                return BehaviorStatus.RUNNING;
            }
            toSeal = damAcross(touching);
        } else {
            toSeal = damAcross(touching);
        }
        LOGGER.info("Chunk miner: {} {} block(s) against {} at {}",
                capping ? "capping" : "damming", toSeal.size(),
                lava ? "lava" : "water", shortPos(start));
        String what = capping ? "water cap" : (lava ? "lava dam" : "water dam");
        // One placement per call; the next comes round once it is verified.
        // A cell with nothing to build against yet is passed over for one
        // that has: a source in the middle of a pool has only water round it
        // until its neighbours are capped, and a two-deep pool's upper source
        // stands on the lower one. Failing on the first such cell ended runs
        // that the next placement would have made possible.
        BlockPos unsupported = null;
        for (BlockPos pos : toSeal) {
            BlockPos support = PlaceBlockTask.findSupport(level, pos, player.getEyePosition());
            if (support == null) {
                if (unsupported == null) {
                    unsupported = pos;
                }
                continue;
            }
            return planPlacement(pos, support, what);
        }
        if (unsupported != null) {
            return fail("nothing to build the " + what + " at " + shortPos(unsupported) + " against");
        }
        return BehaviorStatus.RUNNING;
    }

    /**
     * Dam: the cells the bot was about to occupy or walk past, wherever they
     * lie. A column on the chunk border has half of them in the neighbouring
     * chunk, and that is the side the water comes from — a dam that stops at
     * the border is no dam at all, and the run died at the first lake it dug
     * past. Cells beyond the chunk go first: a plug inside it is mined out
     * again later and the liquid walks straight back in behind it, so the dam
     * that holds is the one on the far side of the border.
     */
    private List<BlockPos> damAcross(Set<BlockPos> touching) {
        List<BlockPos> toSeal = new ArrayList<>(touching);
        toSeal.sort(Comparator.comparing(pos -> chunk.equals(new ChunkPos(pos))));
        return toSeal;
    }

    /**
     * Connected fluid cells around {@code start}, up to {@link
     * #FLOOD_FILL_LIMIT}. The cap is the point: the question is only "small
     * pool or not", and answering it must not walk an ocean.
     */
    private List<BlockPos> floodFill(Level level, BlockPos start) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && found.size() < FLOOD_FILL_LIMIT) {
            BlockPos pos = queue.poll();
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) {
                continue;
            }
            found.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (seen.add(neighbor) && !level.getFluidState(neighbor).isEmpty()) {
                    queue.add(neighbor);
                }
            }
        }
        return found;
    }

    /**
     * Whether any cell of a liquid body has open space beside it. That is both
     * what makes the body dangerous — one with nowhere to go stays where it is
     * — and what makes it sealable, because a support face the bot has no line
     * of sight to cannot be clicked.
     */
    private static boolean touchesOpenSpace(Level level, List<BlockPos> body) {
        for (BlockPos pos : body) {
            for (Direction dir : Direction.values()) {
                if (level.getBlockState(pos.relative(dir)).isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- Placement ---

    /**
     * Queue a filler block at {@code pos}. Fails the run when the position
     * cannot be built against from any side, or when the bot carries none of
     * the configured filler blocks — both mean the situation cannot be made
     * safe, and carrying on would mean walking into it.
     */
    private BehaviorStatus planPlacement(LocalPlayer player, Level level, BlockPos pos,
                                         String what) {
        BlockPos support = PlaceBlockTask.findSupport(level, pos, player.getEyePosition());
        if (support == null) {
            return fail("nothing to build the " + what + " at " + shortPos(pos) + " against");
        }
        return planPlacement(pos, support, what);
    }

    /**
     * The same, against a support the caller has already chosen.
     *
     * <p>Bridging needs this. {@code findSupport} ranks the faces of a cell by
     * how squarely each one faces the eye, which is the right question for a
     * block the bot is looking at and the wrong one for a block it is standing
     * next to: across a gap it picked the neighbour on the <i>far</i> side and
     * left the bot to walk round to a face it could see — into the hole it
     * was supposed to be bridging over ({@code filling the floor at -24, 91,
     * -61} against {@code -24, 91, -60}, reported from a real world). A bridge
     * is built from the block underfoot outward, never from the far rim, and
     * that is not a preference the ranking can express.
     */
    private BehaviorStatus planPlacement(BlockPos pos, BlockPos support, String what) {
        BotController.enqueueTask(new PlaceBlockTask(pos, support, fillerPredicate(), "filler"));
        pendingPlacement = pos;
        placementWhat = what;
        plannedBlocks.clear();
        stepRetries = 0;
        return BehaviorStatus.RUNNING;
    }

    /**
     * Returns null when the placement is resolved, or a terminal status. A
     * placement that keeps failing is fatal rather than skippable: it was
     * planned because the bot could not safely proceed without it.
     */
    private BehaviorStatus verifyPlacement(LocalPlayer player, Level level) {
        BlockPos pos = pendingPlacement;
        if (!isPassable(level.getBlockState(pos))) {
            pendingPlacement = null;
            stepRetries = 0;
            return null;
        }
        if (stepRetries < config.maxStepRetries) {
            stepRetries++;
            BlockPos support = PlaceBlockTask.findSupport(level, pos, player.getEyePosition());
            if (support != null) {
                BotController.enqueueTask(
                        new PlaceBlockTask(pos, support, fillerPredicate(), "filler"));
                return null;
            }
        }
        pendingPlacement = null;
        // Name what was observed, not a guess at why. This used to read "out
        // of filler blocks?", which sent the reader to a hotbar that was full
        // of them while the real reason — a look timeout on an unaimable
        // support face — sat in the task diagnostics two lines up.
        return fail("could not place the " + placementWhat + " at " + shortPos(pos)
                + " — " + stepRetries + " attempts failed, see the task diagnostics above");
    }

    /**
     * Matches any stack of a configured filler block. The same predicate the
     * restock manifest carries, so what the bot places and what it fetches
     * cannot drift apart.
     */
    private Predicate<ItemStack> fillerPredicate() {
        return InventoryHelper.blockIdMatcher(config.fillerBlocks);
    }

    // --- Execution plumbing ---

    /**
     * Returns null when verification is resolved ({@code plannedBlocks}
     * reflects the outcome), or a terminal status.
     */
    private BehaviorStatus verifyPlannedBlocks(LocalPlayer player, Level level) {
        // The position the controller has under the pick right now has not
        // failed to break — it has not finished being tried. Planning runs
        // during INTERACTING to keep the queue stocked, so verify sees that
        // block still standing on every batch; counting it as a retry would
        // enqueue a second task for the block already being mined and spend a
        // stepRetry each time round.
        //
        // The same holds when another block of the batch did fail: the retry
        // re-queues that one, never the block under the pick. Queued with it,
        // the second task reached a cell its first had emptied, and LOOKING
        // cannot hit air — reported from a real run, where one failed break at
        // a row turn cost a second pair of look timeouts on -15, 102, -60.
        BotTask current = BotController.getCurrentTask();
        BlockPos inFlight = current != null ? current.targetPos() : null;
        List<BlockPos> remaining = new ArrayList<>();
        List<BlockPos> retry = new ArrayList<>();
        for (BlockPos pos : plannedBlocks) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                // Something flowed into the hole; deal with it as a liquid
                // rather than retrying the break into running water.
                plannedBlocks.clear();
                stepRetries = 0;
                return null;
            }
            remaining.add(pos);
            if (!pos.equals(inFlight)) {
                retry.add(pos);
            }
        }
        blocksMined += plannedBlocks.size() - remaining.size();
        if (remaining.isEmpty()) {
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        if (retry.isEmpty()) {
            // Nothing to retry and nothing to give up on: the batch is done
            // but for the block being broken. Keep it in the plan so its break
            // is still counted, and let the caller plan the next batch around
            // it — that plan is the whole point of running this early.
            plannedBlocks.clear();
            plannedBlocks.addAll(remaining);
            return null;
        }
        if (stepRetries < config.maxStepRetries) {
            stepRetries++;
            plannedBlocks.clear();
            plannedBlocks.addAll(remaining);
            // The block is still standing and the bot has just failed to get
            // to it. That failure is worth more than the guess that preceded
            // it: ensureStepToward runs once, on the tick the column is
            // planned, and has to predict a walk that has not happened yet —
            // from a position the bot may still be settling into. Here the
            // prediction is over. The bot is where the attempt left it, at
            // rest and on the ground, and the same question asked from there
            // is answered against the world as it really is.
            //
            // Without this the retry re-queues a task that has just proven
            // itself impossible, twice more, and the run then dies on a hole
            // nobody looked at: six position timeouts at one unchanging spot
            // and `cannot break -27, 93, -61 (blocks mined: 0)`, reported
            // from a real world where the way west was missing its floor.
            BlockPos owed = retry.get(0);
            BehaviorStatus step = ensureStepToward(player, level,
                    new BlockPos(owed.getX(), slabFeetY, owed.getZ()));
            if (step != null) {
                // A placement takes the plan with it; the column is still
                // standing, so the sweep finds it again once the ground is in.
                return step;
            }
            BlockPos blocking = columnInTheWay(player, level, owed);
            LOGGER.info("Chunk miner: retrying {} — {}", shortPos(owed),
                    blocking == null
                            ? "nothing to mend on the way there"
                            : "still standing in the way: " + shortPos(blocking));
            enqueue(retry);
            return null;
        }
        return fail("cannot break " + shortPos(retry.get(0)));
    }

    /**
     * Add a batch to the plan. Appends rather than replaces: the next batch is
     * planned while the previous one's last block is still under the pick, so
     * clearing here would drop a position the controller is still working on
     * and nothing would ever see it finish.
     */
    private void plan(List<BlockPos> blocks) {
        plannedBlocks.addAll(blocks);
        stepRetries = 0;
        enqueue(blocks);
    }

    private void enqueue(List<BlockPos> blocks) {
        for (BlockPos pos : blocks) {
            BotController.enqueueTask(new MineBlockTask(pos));
        }
    }

    private BehaviorStatus fail(String reason) {
        failReason = reason;
        LOGGER.warn("Chunk miner failed: {}", reason);
        BotController.stop();
        return BehaviorStatus.FAILED;
    }

    // --- Block predicates ---

    /**
     * Whether a cell is this run's work: inside the chunk, inside the layer
     * range, and not a step of the staircase.
     *
     * <p>The three callers of this are the whole of the question "may this cell
     * be mined as slab work" — the sweep's plan ({@link #addColumn}), the
     * column scan ({@link #hasWork}) and the finish condition
     * ({@link #slabHasWork}) — which is why the staircase is carved out here and
     * nowhere else. Excluding it in one place makes the sweep walk past a step,
     * the slab read as finished with the step still standing, and the bot never
     * end a slab on a column it is about to be told to mine; excluding it in
     * three would be three chances to disagree.
     *
     * <p>Those same three callers pair this with {@link #hasOpenFace}, and the
     * pairing has to stay: this one answers "is the cell mine to mine", that one
     * "can it be aimed at yet". Drop the second from any one of the three and
     * the disagreement is back in a new shape — a column handed over that the
     * plan then refuses, or a slab left over a cell nothing can reach.
     */
    private boolean isMinable(Level level, BlockPos pos) {
        return pos.getY() >= toY && pos.getY() <= fromY && chunk.equals(new ChunkPos(pos))
                && !isStep(level, pos);
    }

    /**
     * Whether this cell is a step of the ramp — the one thing inside the chunk
     * the sweep leaves standing.
     *
     * <p><em>Which</em> column carries the step is pure geometry, pinned to the
     * world's height grid so that two runs down the same shaft cannot disagree
     * (see {@link SpiralStairs}). This answers the other half: whether this run
     * owes a step at this layer at all.
     *
     * <p>The top two layers of a range do not get one. They are the pocket the
     * bot is standing in — air, both of them, on every argument-less start,
     * because {@code fromY} is taken from the bot's head — so a ramp reaching up
     * into them would have {@link #repairStairs} trying to build a step in
     * mid-air on the first tick of every run. The ramp starts at the floor
     * instead, which is also the level the bot walks off it onto.
     *
     * <p><b>Unless one is already standing there</b>, and that clause is the
     * whole difference between this and the {@code topY} it replaces. A run
     * resumed further down the shaft — a person restarting a stopped one, or a
     * restock resume, since resume is {@code start()} — has its own pocket two
     * layers deep in ground the previous run already cleared, and what is left
     * standing in those two layers is that run's staircase. Reading the world is
     * how the miner finds its place again everywhere else; this is the same
     * question, asked of the ramp.
     */
    private boolean isStep(Level level, BlockPos pos) {
        return SpiralStairs.isStairCell(chunk, pos)
                && (pos.getY() <= fromY - 2 || !isPassable(level.getBlockState(pos)));
    }

    /**
     * Whether the chunk miner is allowed to break this block. Bedrock is
     * excluded unconditionally rather than by blacklist entry — it is not a
     * preference. Fluids are not "diggable": they are handled before the
     * column is opened.
     */
    boolean isDiggable(BlockState state) {
        if (state.isAir() || state.is(Blocks.BEDROCK) || !state.getFluidState().isEmpty()) {
            return false;
        }
        return !config.chunkMinerBlacklist.contains(blockName(state));
    }

    private static boolean isPassable(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

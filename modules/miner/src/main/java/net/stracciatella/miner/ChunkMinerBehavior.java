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
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
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
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.humanize.HumanBehavior;
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
 */
public class ChunkMinerBehavior implements BotBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger("ChunkMiner");

    private static final int CHUNK_SIZE = 16;
    private static final int SLAB_HEIGHT = 2;
    /** Cap on a liquid flood fill — enough to tell a puddle from an ocean. */
    private static final int FLOOD_FILL_LIMIT = 16;
    /** At most this many sources get capped individually; beyond it, dam. */
    private static final int MAX_SEALABLE_SOURCES = 5;
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
    private int stepRetries;
    private int breatherTicks;

    private int blocksMined;
    private String failReason;
    private int dropWaitTicks;

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
        return BotPolicy.none()
                .withDamageStop()
                .withPlayerAttackStop()
                .withInventoryFullStop(config.chunkMinerMinFreeSlots)
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
                .withApproachOccluded();
    }

    @Override
    public void start(Minecraft client) {
        phase = Phase.SELECT_SLAB;
        chunk = null;
        plannedBlocks.clear();
        batchQueue.clear();
        pendingPlacement = null;
        groundworkAfterOpening = null;
        stepRetries = 0;
        breatherTicks = 0;
        blocksMined = 0;
        failReason = null;
        dropWaitTicks = 0;

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        chunk = new ChunkPos(player.blockPosition());
        int worldFloor = client.level != null ? client.level.getMinY() : Integer.MIN_VALUE;
        // "From here down" reaches from the bot's head, not from its feet. The
        // top of the range is the *head* layer of the topmost slab — isInRange
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
            case SELECT_SLAB -> tickSelectSlab(level);
            case DESCEND -> tickDescend(player, level);
            case CLEAR -> tickClear(player, level);
        };
    }

    // --- Phases ---

    /**
     * Pick the topmost layer pair that still holds something to dig. This is
     * the resume mechanism: it reads the world, not a saved cursor.
     */
    private BehaviorStatus tickSelectSlab(Level level) {
        for (int feetY = fromY - 1; feetY >= toY - 1; feetY -= SLAB_HEIGHT) {
            if (slabHasWork(level, feetY)) {
                slabFeetY = feetY;
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
        if (groundworkAfterOpening == null) {
            List<BlockPos> columns = slabColumns(slabFeetY);
            for (int i = columns.indexOf(column) + 1; i > 0 && i < columns.size(); i++) {
                BlockPos next = columns.get(i);
                if (!withinChainDistance(player, next) || !needsNoGroundwork(level, next)) {
                    break;
                }
                addColumn(level, next, blocks);
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
     * only burns a look timeout.
     */
    private void addColumn(Level level, BlockPos column, List<BlockPos> into) {
        for (BlockPos pos : new BlockPos[] {column.above(), column}) {
            if (isInRange(pos) && !plannedBlocks.contains(pos)
                    && isDiggable(level.getBlockState(pos))) {
                into.add(pos);
            }
        }
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

    private boolean slabHasWork(Level level, int feetY) {
        for (BlockPos column : slabColumns(feetY)) {
            for (BlockPos pos : new BlockPos[] {column, column.above()}) {
                if (isInRange(pos) && isDiggable(level.getBlockState(pos))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The next column with something to dig, following the snake onward from
     * the one the bot is standing in and wrapping around.
     *
     * <p>Starting at the chunk corner instead would break as soon as the
     * columns in between hold nothing — a blacklisted seam, a strip already
     * cleared, the column the bot just descended through. The bot would be
     * sent at a block several columns away that is still inside its reach, so
     * the controller never walks over, but is hidden behind the columns that
     * were skipped: the look times out and the run dies on a block it never
     * had a line to. Resuming at the bot keeps every step of the sweep
     * adjacent to the last, which is the whole point of a serpentine.
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
        int start = columnIndex(columns, player.blockPosition());
        for (int i = 0; i < columns.size(); i++) {
            int ahead = start + i;
            int index = ahead < columns.size()
                    ? ahead
                    : start - 1 - (i - (columns.size() - start));
            if (index < 0 || index >= columns.size()) {
                continue;
            }
            BlockPos column = columns.get(index);
            // Head first, matching the order tickClear plans them in: that
            // is the block LOOKING aims at, so that is the one to test.
            for (BlockPos pos : new BlockPos[] {column.above(), column}) {
                // A position already in the plan is not work left to find: the
                // block still under the pick would otherwise be handed back
                // and the sweep would never move past its own column.
                if (isInRange(pos) && !plannedBlocks.contains(pos)
                        && isDiggable(level.getBlockState(pos))) {
                    return column;
                }
            }
        }
        return null;
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

        List<BlockPos> toSeal;
        boolean capping = !lava && !sources.isEmpty() && sources.size() <= MAX_SEALABLE_SOURCES
                && body.size() < FLOOD_FILL_LIMIT;
        if (capping) {
            // Small pool, fully surveyed: cap the sources themselves — an
            // uncapped source next door refills the chunk as fast as it is dug.
            toSeal = sources;
        } else {
            // Dam: the cells the bot was about to occupy or walk past,
            // wherever they lie. A column on the chunk border has half of them
            // in the neighbouring chunk, and that is the side the water comes
            // from — a dam that stops at the border is no dam at all, and the
            // run died at the first lake it dug past.
            toSeal = new ArrayList<>(touching);
            // Cells beyond the chunk go first. A plug inside it is mined out
            // again later and the liquid walks straight back in behind it, so
            // the dam that holds is the one on the far side of the border.
            toSeal.sort(Comparator.comparing(pos -> chunk.equals(new ChunkPos(pos))));
        }
        LOGGER.info("Chunk miner: {} {} block(s) against {} at {}",
                capping ? "capping" : "damming", toSeal.size(),
                lava ? "lava" : "water", shortPos(start));
        String what = capping ? "water cap" : (lava ? "lava dam" : "water dam");
        for (BlockPos pos : toSeal) {
            BehaviorStatus placement = planPlacement(player, level, pos, what);
            if (placement != null) {
                return placement;
            }
        }
        return BehaviorStatus.RUNNING;
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

    /** Matches any stack of a configured filler block. */
    private Predicate<ItemStack> fillerPredicate() {
        return stack -> {
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            String id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
            return config.fillerBlocks.contains(id);
        };
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
        BotTask current = BotController.getCurrentTask();
        BlockPos inFlight = current != null ? current.targetPos() : null;
        List<BlockPos> remaining = new ArrayList<>();
        boolean onlyInFlight = true;
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
                onlyInFlight = false;
            }
        }
        blocksMined += plannedBlocks.size() - remaining.size();
        if (remaining.isEmpty()) {
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        if (onlyInFlight) {
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
            BlockPos owed = remaining.get(0);
            BehaviorStatus step = ensureStepToward(player, level,
                    new BlockPos(owed.getX(), slabFeetY, owed.getZ()));
            if (step != null) {
                // A placement takes the plan with it; the column is still
                // standing, so the sweep finds it again once the ground is in.
                return step;
            }
            LOGGER.info("Chunk miner: retrying {} — nothing to mend on the way there",
                    shortPos(owed));
            enqueue(remaining);
            return null;
        }
        return fail("cannot break " + shortPos(remaining.get(0)));
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

    private boolean isInRange(BlockPos pos) {
        return pos.getY() >= toY && pos.getY() <= fromY && chunk.equals(new ChunkPos(pos));
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

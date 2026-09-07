package net.stracciatella.miner;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import net.minecraft.world.phys.Vec3;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.humanize.HumanBehavior;
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
 * <p>The bot never mines outside the target chunk and never places outside it
 * except to cap a water source that would otherwise pour in.
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
     * the player's feet down to the configured bottom".
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
                .withFastCollectExit();
    }

    @Override
    public void start(Minecraft client) {
        phase = Phase.SELECT_SLAB;
        chunk = null;
        plannedBlocks.clear();
        batchQueue.clear();
        pendingPlacement = null;
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
        fromY = rangeRequested ? requestedFromY : player.blockPosition().getY();
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
        boolean collecting = BotController.getPhase() == BotController.Phase.COLLECTING;
        if (BotController.isPaused() || !BotController.getTaskQueue().isEmpty()
                || (BotController.isActive() && !collecting)) {
            return BehaviorStatus.RUNNING;
        }

        if (pendingPlacement != null) {
            BehaviorStatus placed = verifyPlacement(level);
            if (placed != null) {
                return placed;
            }
            if (pendingPlacement != null) {
                return BehaviorStatus.RUNNING;
            }
        }

        if (!plannedBlocks.isEmpty()) {
            BehaviorStatus verified = verifyPlannedBlocks(level);
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
        BehaviorStatus liquid = handleLiquidsAround(level, under);
        if (liquid != null) {
            return liquid;
        }
        BehaviorStatus footing = ensureSafeDrop(level, under);
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
        BlockPos column = nextColumn(player, level);
        if (column == null) {
            phase = Phase.SELECT_SLAB;
            return BehaviorStatus.RUNNING;
        }
        BehaviorStatus liquid = handleLiquidsAround(level, column);
        if (liquid != null) {
            return liquid;
        }
        BehaviorStatus footing = ensureFloor(level, column.below());
        if (footing != null) {
            return footing;
        }
        // Head before feet: while the head block stands, the foot block's
        // upward face is covered and its side faces are hidden by the corridor
        // wall, so aiming at it first only burns a look timeout.
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos pos : new BlockPos[] {column.above(), column}) {
            if (isInRange(pos) && isDiggable(level.getBlockState(pos))) {
                blocks.add(pos);
            }
        }
        if (blocks.isEmpty()) {
            return BehaviorStatus.RUNNING;
        }
        plan(blocks);
        breatherTicks = HumanBehavior.randomBreatherTicks(BotController.CONFIG);
        return BehaviorStatus.RUNNING;
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
     * <p>A column that is close enough to be occluded is deferred, because the
     * snake stepping past an empty column leaves the bot standing *diagonally*
     * to the next one with the orthogonal neighbour still up: measured in a
     * real world at surface height, target -16,110,-6 with the head block
     * -15,111,-6 in the line and the bot 1.75 blocks away. Nothing walks it
     * clear — the controller only navigates to targets out of reach, and the
     * post-timeout re-approach closes to two blocks, which it already beats —
     * so LOOKING times out twice and the whole run dies on a block that is
     * merely hidden for the moment. Deferring costs nothing: the occluder is
     * itself a column of this sweep and is dug next, and since the miner keeps
     * no cursor and re-derives from the world, the skipped column simply comes
     * back once it is visible. The deferred one is still returned if it is all
     * that is left, so a genuinely unbreakable block fails the run as before.
     */
    private BlockPos nextColumn(LocalPlayer player, Level level) {
        List<BlockPos> columns = slabColumns(slabFeetY);
        int start = columnIndex(columns, player.blockPosition());
        BlockPos deferred = null;
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
                if (!isInRange(pos) || !isDiggable(level.getBlockState(pos))) {
                    continue;
                }
                if (isAimable(player, level, pos)) {
                    return column;
                }
                if (deferred == null) {
                    deferred = column;
                }
                break;
            }
        }
        return deferred;
    }

    /**
     * Whether the bot could aim at {@code pos} from where it stands. Out of
     * reach counts as aimable: the controller walks to those, and where it
     * ends up is not knowable from here.
     */
    private boolean isAimable(LocalPlayer player, Level level, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = Vec3.atCenterOf(pos);
        double reach = BotController.CONFIG.reachDistance;
        if (eye.distanceToSqr(aim) > reach * reach) {
            return true;
        }
        BlockHitResult clip = level.clip(new ClipContext(
                eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return clip.getType() != HitResult.Type.BLOCK || clip.getBlockPos().equals(pos);
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
    private BehaviorStatus ensureSafeDrop(Level level, BlockPos pos) {
        int open = 0;
        BlockPos below = pos.below();
        while (open <= MAX_SAFE_DROP && isPassable(level.getBlockState(below))) {
            open++;
            below = below.below();
        }
        if (open <= MAX_SAFE_DROP) {
            return null;
        }
        return ensureFloor(level, pos.below());
    }

    /** Put a block at {@code pos} if nothing solid is there to stand on. */
    private BehaviorStatus ensureFloor(Level level, BlockPos pos) {
        if (!isPassable(level.getBlockState(pos))) {
            return null;
        }
        if (!chunk.equals(new ChunkPos(pos))) {
            return fail("no floor at " + shortPos(pos) + ", which is outside the chunk");
        }
        return planPlacement(level, pos, "floor");
    }

    // --- Liquids ---

    /**
     * Deal with any liquid touching the cell about to be opened, before it is
     * opened. Water that comes from a handful of sources gets each source
     * capped — cheap, permanent, and it leaves the chunk dry. Anything bigger,
     * and all lava, gets dammed at the face it would flow in through: chasing
     * an ocean's sources is endless, and letting lava burn itself out into
     * obsidian costs far more time than a block of cobble.
     */
    private BehaviorStatus handleLiquidsAround(Level level, BlockPos column) {
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
        if (touching.isEmpty()) {
            return null;
        }

        BlockPos start = touching.iterator().next();
        boolean lava = level.getFluidState(start).is(net.minecraft.tags.FluidTags.LAVA);
        List<BlockPos> body = floodFill(level, start);
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
            // Small pool, fully surveyed: cap the sources themselves. This is
            // the only case that may reach outside the chunk — an uncapped
            // source next door refills the chunk as fast as it is dug.
            toSeal = sources;
        } else {
            // Dam: only the cells the bot was about to occupy or walk past,
            // and only inside the chunk.
            toSeal = new ArrayList<>();
            for (BlockPos pos : touching) {
                if (chunk.equals(new ChunkPos(pos))) {
                    toSeal.add(pos);
                }
            }
            if (toSeal.isEmpty()) {
                return fail((lava ? "lava" : "water") + " at " + shortPos(start)
                        + " can only be dammed from outside the chunk");
            }
        }
        LOGGER.info("Chunk miner: {} {} block(s) against {} at {}",
                capping ? "capping" : "damming", toSeal.size(),
                lava ? "lava" : "water", shortPos(start));
        String what = capping ? "water cap" : (lava ? "lava dam" : "water dam");
        for (BlockPos pos : toSeal) {
            BehaviorStatus placement = planPlacement(level, pos, what);
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

    // --- Placement ---

    /**
     * Queue a filler block at {@code pos}. Fails the run when the position
     * cannot be built against from any side, or when the bot carries none of
     * the configured filler blocks — both mean the situation cannot be made
     * safe, and carrying on would mean walking into it.
     */
    private BehaviorStatus planPlacement(Level level, BlockPos pos, String what) {
        BlockPos support = PlaceBlockTask.findSupport(level, pos);
        if (support == null) {
            return fail("nothing to build the " + what + " at " + shortPos(pos) + " against");
        }
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
    private BehaviorStatus verifyPlacement(Level level) {
        BlockPos pos = pendingPlacement;
        if (!isPassable(level.getBlockState(pos))) {
            pendingPlacement = null;
            stepRetries = 0;
            return null;
        }
        if (stepRetries < config.maxStepRetries) {
            stepRetries++;
            BlockPos support = PlaceBlockTask.findSupport(level, pos);
            if (support != null) {
                BotController.enqueueTask(
                        new PlaceBlockTask(pos, support, fillerPredicate(), "filler"));
                return null;
            }
        }
        pendingPlacement = null;
        return fail("could not place the " + placementWhat + " at " + shortPos(pos)
                + " — out of filler blocks?");
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
    private BehaviorStatus verifyPlannedBlocks(Level level) {
        List<BlockPos> remaining = new ArrayList<>();
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
        }
        blocksMined += plannedBlocks.size() - remaining.size();
        if (remaining.isEmpty()) {
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        if (stepRetries < config.maxStepRetries) {
            stepRetries++;
            plannedBlocks.clear();
            plannedBlocks.addAll(remaining);
            enqueue(remaining);
            return null;
        }
        return fail("cannot break " + shortPos(remaining.get(0)));
    }

    private void plan(List<BlockPos> blocks) {
        plannedBlocks.clear();
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

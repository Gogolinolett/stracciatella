package net.stracciatella.miner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.scan.BlockScanner;
import net.stracciatella.bot.task.MineBlockTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strip-mines for diamonds on the layer {@code floorOffsetAboveBedrock}
 * blocks above the bedrock found below the start position.
 *
 * <p>Strategy state machine (the BotController executes every individual
 * block with its human-like camera/timing/collection mechanics):
 * <ul>
 *   <li><b>LOCATE</b> — find the highest bedrock in the start column, derive
 *       the tunnel floor height, snap the tunnel direction to the player's
 *       facing.
 *   <li><b>DESCEND</b> — dig a 1-wide staircase (3 blocks per step: foot,
 *       head, ceiling clearance) until the cursor reaches tunnel height.
 *   <li><b>TUNNEL</b> — dig 1×2 slices forward. Before each slice, scan for
 *       diamond ore within reach; found ore is mined by first clearing the
 *       blocks on the line of sight (so the hit-result gate can see it),
 *       then the ore itself. The player follows the dig front through the
 *       controller's own collect/positioning movement.
 * </ul>
 *
 * <p>Safety is fail-fast, not fail-safe: liquids ahead/overhead, bedrock in
 * the dig path, a missing floor, or an open cave abort the run with a
 * reason instead of attempting to handle the hazard.
 */
public class DiamondMinerBehavior implements BotBehavior {

    private static final Logger LOGGER = LoggerFactory.getLogger("DiamondMiner");

    public static final Predicate<BlockState> IS_DIAMOND_ORE = state ->
            state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);

    private enum Phase {
        LOCATE,
        DESCEND,
        TUNNEL
    }

    private final MinerConfig config;

    private Phase phase = Phase.LOCATE;
    private Direction dir = Direction.NORTH;
    private int tunnelFeetY;
    // Feet-space block position of the last planned step. The player follows
    // behind via the controller's collect-walk and positioning.
    private BlockPos cursor;
    private int tunnelProgress;
    private int tunnelLength;
    private int requestedTunnelLength;

    // Blocks of the currently executing batch, verified once the controller
    // goes idle. Ore runs additionally remember the ore block for counting
    // and for skip-on-failure.
    private final List<BlockPos> plannedBlocks = new ArrayList<>();
    private BlockPos pendingOre = null;
    // Follow-up batches of the current step, executed strictly one after
    // another (verify + collect between them). Sequencing is what makes
    // every batch visible from where the player then stands: a single big
    // batch gets reordered by the controller's nearest-task selection, and
    // blocks farther down a gallery (or a stair foot under its own head
    // block) end up targeted while still hidden — burning look timeouts.
    private final ArrayDeque<List<BlockPos>> batchQueue = new ArrayDeque<>();
    // Ore belonging to the final queued batch of an ore gallery; failing any
    // gallery batch skips this ore instead of failing the run.
    private BlockPos queuedOre = null;
    private final Set<BlockPos> unreachableOres = new HashSet<>();
    // Every block this run has ever planned to dig. Air at these positions is
    // our own excavation (ore galleries cutting through future tunnel slices,
    // slices overlapping a gallery) — the cave check must not mistake it for
    // a natural cave.
    private final Set<BlockPos> excavated = new HashSet<>();
    private int stepRetries;

    private int diamondsMined;
    private String failReason;

    public DiamondMinerBehavior(MinerConfig config) {
        this.config = config;
    }

    /**
     * Tunnel length for the next start; 0 uses the configured default.
     */
    public void setRequestedTunnelLength(int length) {
        this.requestedTunnelLength = length;
    }

    @Override
    public String id() {
        return "diamond_miner";
    }

    @Override
    public void start(Minecraft client) {
        phase = Phase.LOCATE;
        cursor = null;
        tunnelProgress = 0;
        tunnelLength = requestedTunnelLength > 0 ? requestedTunnelLength : config.defaultTunnelLength;
        plannedBlocks.clear();
        pendingOre = null;
        batchQueue.clear();
        queuedOre = null;
        unreachableOres.clear();
        excavated.clear();
        stepRetries = 0;
        diamondsMined = 0;
        failReason = null;
    }

    @Override
    public void abort() {
        BotController.stop();
    }

    @Override
    public String statusLine() {
        if (failReason != null) {
            return failReason + " (diamonds mined: " + diamondsMined + ")";
        }
        return phase + " " + tunnelProgress + "/" + tunnelLength
                + " blocks, diamonds mined: " + diamondsMined;
    }

    @Override
    public BehaviorStatus tick(Minecraft client) {
        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null) {
            return BehaviorStatus.RUNNING;
        }
        // The controller is the execution engine — plan the next step only
        // once it has fully finished (and collected) the previous one.
        if (BotController.isPaused() || BotController.isActive()
                || !BotController.getTaskQueue().isEmpty()) {
            return BehaviorStatus.RUNNING;
        }

        // Verify the previous batch: every planned block must be gone.
        if (!plannedBlocks.isEmpty()) {
            BehaviorStatus verified = verifyPlannedBlocks(level);
            if (verified != null) {
                return verified;
            }
            if (!plannedBlocks.isEmpty()) {
                return BehaviorStatus.RUNNING;
            }
        }

        // Execute the next queued batch of the current step before planning
        // anything new — the collect-walk between batches is what moves the
        // player into position for the following one.
        if (!batchQueue.isEmpty()) {
            List<BlockPos> next = batchQueue.poll();
            BlockPos ore = batchQueue.isEmpty() ? queuedOre : null;
            if (ore != null) {
                queuedOre = null;
            }
            plan(next, ore);
            return BehaviorStatus.RUNNING;
        }

        return switch (phase) {
            case LOCATE -> tickLocate(player, level);
            case DESCEND -> tickDescend(level);
            case TUNNEL -> tickTunnel(player, level);
        };
    }

    /**
     * Returns null when verification is resolved (success, retry enqueued, or
     * ore skipped — {@code plannedBlocks} reflects the outcome), or a
     * terminal status.
     */
    private BehaviorStatus verifyPlannedBlocks(Level level) {
        List<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : plannedBlocks) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return fail("liquid flooded the dig at " + shortPos(pos));
            }
            remaining.add(pos);
        }
        if (remaining.isEmpty()) {
            if (pendingOre != null) {
                diamondsMined++;
                pendingOre = null;
            }
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        if (stepRetries < config.maxStepRetries) {
            stepRetries++;
            enqueue(remaining);
            return null;
        }
        if (pendingOre != null) {
            // One unreachable ore shouldn't kill the whole run — skip it.
            LOGGER.warn("Skipping unreachable diamond ore at {}", pendingOre);
            unreachableOres.add(pendingOre);
            pendingOre = null;
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        if (queuedOre != null) {
            // A gallery batch failed on the way to an ore — skip that ore
            // and drop the remaining gallery batches.
            LOGGER.warn("Skipping diamond ore at {} — its gallery could not be dug", queuedOre);
            unreachableOres.add(queuedOre);
            queuedOre = null;
            batchQueue.clear();
            plannedBlocks.clear();
            stepRetries = 0;
            return null;
        }
        return fail("cannot break " + shortPos(remaining.get(0)));
    }

    private BehaviorStatus tickLocate(LocalPlayer player, Level level) {
        BlockPos feet = player.blockPosition();
        int bedrockTop = Integer.MIN_VALUE;
        for (int y = level.getMinY(); y <= feet.getY(); y++) {
            if (level.getBlockState(new BlockPos(feet.getX(), y, feet.getZ())).is(Blocks.BEDROCK)) {
                bedrockTop = y;
            }
        }
        if (bedrockTop == Integer.MIN_VALUE) {
            return fail("no bedrock below the start position");
        }
        tunnelFeetY = bedrockTop + config.floorOffsetAboveBedrock;
        if (feet.getY() < tunnelFeetY) {
            return fail("standing below the target level y=" + tunnelFeetY);
        }
        dir = player.getDirection();
        cursor = feet;
        phase = feet.getY() == tunnelFeetY ? Phase.TUNNEL : Phase.DESCEND;
        LOGGER.info("Diamond miner: bedrock top y={}, tunnel feet y={}, direction {}",
                bedrockTop, tunnelFeetY, dir);
        return BehaviorStatus.RUNNING;
    }

    private BehaviorStatus tickDescend(Level level) {
        if (cursor.getY() <= tunnelFeetY) {
            phase = Phase.TUNNEL;
            return BehaviorStatus.RUNNING;
        }
        BlockPos stepColumn = cursor.relative(dir);
        BlockPos foot = stepColumn.below();
        BlockPos head = stepColumn;
        BlockPos ceiling = stepColumn.above();

        if (anyLiquid(level, foot, head, ceiling,
                foot.relative(dir), head.relative(dir), ceiling.relative(dir))) {
            return fail("liquid ahead in the staircase at " + shortPos(foot));
        }
        if (anyBedrock(level, foot, head, ceiling)) {
            return fail("bedrock in the staircase at " + shortPos(foot));
        }
        if (!isSolid(level, foot.below())) {
            return fail("no floor below the next stair step at " + shortPos(foot.below()));
        }
        List<BlockPos> upper = solidBlocks(level, ceiling, head);
        List<BlockPos> lower = solidBlocks(level, foot);
        if (upper.isEmpty() && lower.isEmpty()) {
            if (!excavated.contains(foot) && !excavated.contains(head)) {
                // All three open and not our own dig: descending into a cave
                // — bail out rather than walking blind into unlit space.
                return fail("cave ahead during descent at " + shortPos(foot));
            }
            // Our own excavation — step on down.
        } else {
            // Ceiling/head first, the foot as its own follow-up batch: until
            // the head block is gone, every candidate face of the foot is
            // covered (sideways by the block below the player's feet, on top
            // by the head) — targeting it early just burns a look timeout or
            // a server-rejected break.
            if (!upper.isEmpty()) {
                batchQueue.add(upper);
            }
            if (!lower.isEmpty()) {
                batchQueue.add(lower);
            }
        }
        cursor = foot;
        return BehaviorStatus.RUNNING;
    }

    private BehaviorStatus tickTunnel(LocalPlayer player, Level level) {
        // Ore first: chase every diamond in reach before extending the
        // tunnel — but only while standing at tunnel level. Galleries are
        // planned from the player's column at gallery height; planned from
        // above (e.g. still on the staircase) the gallery ceiling hides
        // every deeper face from the eye and the looks time out. Digging
        // the next slice first pulls the player down via the collect-walk.
        if (player.blockPosition().getY() == tunnelFeetY) {
            List<BlockPos> ores = BlockScanner.scan(level, player.blockPosition(),
                            config.oreScanRadius, IS_DIAMOND_ORE).stream()
                    .filter(pos -> !unreachableOres.contains(pos))
                    .toList();
            if (!ores.isEmpty()) {
                planOreExcavation(player, level, ores.get(0));
                return BehaviorStatus.RUNNING;
            }
        }

        if (tunnelProgress >= tunnelLength) {
            LOGGER.info("Diamond miner finished: {} blocks tunneled, {} diamonds mined",
                    tunnelProgress, diamondsMined);
            return BehaviorStatus.SUCCEEDED;
        }

        BlockPos front = cursor.relative(dir);
        BlockPos foot = new BlockPos(front.getX(), tunnelFeetY, front.getZ());
        BlockPos head = foot.above();

        // Breaking the head block exposes the block above it — lava there
        // pours straight into the tunnel, so it is part of the check.
        if (anyLiquid(level, foot, head, head.above(),
                foot.relative(dir), head.relative(dir))) {
            return fail("liquid ahead in the tunnel at " + shortPos(foot));
        }
        if (anyBedrock(level, foot, head)) {
            return fail("bedrock in the tunnel slice at " + shortPos(foot));
        }
        if (!isSolid(level, foot.below())) {
            return fail("no floor below the tunnel at " + shortPos(foot.below()));
        }
        List<BlockPos> toMine = solidBlocks(level, foot, head);
        if (toMine.isEmpty()) {
            // Open slice: air we excavated ourselves (an ore gallery cutting
            // through the tunnel line) is expected — only unexplained air
            // counts toward the cave check, and only when the slice behind
            // it is unexplained air too.
            boolean ownDig = excavated.contains(foot) || excavated.contains(head);
            BlockPos foot2 = foot.relative(dir);
            BlockPos head2 = head.relative(dir);
            boolean openBehind = level.getBlockState(foot2).isAir()
                    && level.getBlockState(head2).isAir()
                    && !excavated.contains(foot2) && !excavated.contains(head2);
            if (!ownDig && openBehind) {
                return fail("cave ahead at " + shortPos(foot));
            }
            // Single air pocket or our own dig — walk on through.
        } else {
            plan(toMine, null);
        }
        cursor = foot;
        tunnelProgress++;
        return BehaviorStatus.RUNNING;
    }

    /**
     * Plan mining one diamond ore via a walkable 1×2 side gallery: a
     * 4-connected line of columns (foot + head at tunnel height) from the
     * player's column to the ore's column. The controller's collect-walk
     * pulls the player into the gallery after each block, so every following
     * block — and finally the ore — is seen frontally from up close (a thin
     * eye-line exposure fails here: deeper line blocks stay hidden behind
     * the standing foot blocks and the player can't follow). Ores from one
     * below the gallery floor (mined from above) to one above its ceiling
     * (mined from below) are reachable; anything else, or a gallery crossing
     * bedrock/liquid, marks the ore unreachable and skips it.
     */
    private void planOreExcavation(LocalPlayer player, Level level, BlockPos ore) {
        if (ore.getY() < tunnelFeetY - 1 || ore.getY() > tunnelFeetY + 2) {
            LOGGER.warn("Diamond ore at {} is outside the gallery's reachable layers — skipping", ore);
            unreachableOres.add(ore);
            return;
        }
        List<List<BlockPos>> columns = new ArrayList<>();
        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        int stepX = Integer.signum(ore.getX() - x);
        int stepZ = Integer.signum(ore.getZ() - z);
        int remainingX = Math.abs(ore.getX() - x);
        int remainingZ = Math.abs(ore.getZ() - z);
        while (remainingX > 0 || remainingZ > 0) {
            // One axis step per column (4-connected): a diagonal line would
            // leave corner walls the player can't walk through.
            if (remainingX >= remainingZ) {
                x += stepX;
                remainingX--;
            } else {
                z += stepZ;
                remainingZ--;
            }
            BlockPos foot = new BlockPos(x, tunnelFeetY, z);
            BlockPos head = foot.above();
            if (anyLiquid(level, foot, head, head.above())
                    || anyBedrock(level, foot, head)) {
                LOGGER.warn("Gallery to diamond ore at {} is blocked by liquid/bedrock — skipping", ore);
                unreachableOres.add(ore);
                return;
            }
            List<BlockPos> column = new ArrayList<>();
            for (BlockPos pos : solidBlocks(level, foot, head)) {
                if (!pos.equals(ore)) {
                    column.add(pos);
                }
            }
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        if (anyLiquid(level, ore, ore.above())) {
            LOGGER.warn("Diamond ore at {} has liquid next to it — skipping", ore);
            unreachableOres.add(ore);
            return;
        }
        // One batch per column, the ore as the final batch: the collect-walk
        // between batches pulls the player down the gallery, so every next
        // column (and finally the ore) is dug from up close where it is
        // actually visible.
        batchQueue.addAll(columns);
        batchQueue.add(List.of(ore));
        queuedOre = ore;
    }

    private void plan(List<BlockPos> blocks, BlockPos ore) {
        plannedBlocks.clear();
        plannedBlocks.addAll(blocks);
        excavated.addAll(blocks);
        pendingOre = ore;
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
        LOGGER.warn("Diamond miner failed: {}", reason);
        BotController.stop();
        return BehaviorStatus.FAILED;
    }

    // --- Block predicates ---

    private static boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static List<BlockPos> solidBlocks(Level level, BlockPos... positions) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).isAir()) {
                result.add(pos);
            }
        }
        return result;
    }

    private static boolean anyLiquid(Level level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            if (!level.getFluidState(pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyBedrock(Level level, BlockPos... positions) {
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
                return true;
            }
        }
        return false;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

package net.stracciatella.bot;

import java.util.HashMap;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.humanize.HumanBehavior;
import net.stracciatella.bot.interaction.BlockInteractor;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.task.BotTask;
import net.stracciatella.bot.task.TaskQueue;
import net.stracciatella.camera.AngleUtil;
import net.stracciatella.camera.CameraController;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.MeshPathfinder;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.mesh.Mesh;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static tick-driven state machine that orchestrates bot tasks.
 * Coordinates PathWalker for navigation, CameraController for smooth aiming,
 * and BlockInteractor for mining.
 */
public class BotController {

    private static final Logger LOGGER = LoggerFactory.getLogger("BotController");

    public static final BotConfig CONFIG = new BotConfig();
    private static final TaskQueue taskQueue = new TaskQueue();

    // State machine
    private static Phase phase = Phase.IDLE;
    private static BotTask currentTask = null;
    private static int phaseTicks = 0;
    private static int taskTotalTicks = 0;
    private static boolean paused = false;

    // Camera for LOOKING/INTERACTING phases (separate from PathWalker's)
    private static CameraController camera = null;

    // Per-target humanization values (generated once when entering LOOKING)
    private static double aimOffsetX = 0.0;
    private static double aimOffsetY = 0.0;
    private static double aimOffsetZ = 0.0;
    private static int settleDelay = 0;
    private static int settleRemaining = 0;
    private static boolean toolSelected = false;

    // Collection state
    private static boolean walkAfterCollect = false;
    // Position of the last mined block — COLLECTING walks toward this to pick up drops
    private static BlockPos lastMinedPos = null;
    // True once items have been observed in the 8-block AABB this COLLECTING
    // session. Prevents exit during the server→client sync delay after a block
    // breaks but before the drop entity syncs to the client.
    private static boolean itemsSeenThisCollect = false;
    // Last phaseTick on which items were visible in the AABB. COLLECTING only
    // exits once items have been absent for a sustained window (not just a
    // single transient tick between pickup and next spawn/sync).
    private static int lastItemSeenTick = 0;
    // Interaction state — how many consecutive ticks the current target block
    // has been observed as air. We require a sustained window to ensure the
    // server confirmed the break (not just client-side prediction, which gets
    // reverted if the server rejects the break under accelerated ticks).
    private static int airConfirmTicks = 0;

    public enum Phase {
        IDLE,
        SCANNING,
        NAVIGATING,
        POSITIONING,
        LOOKING,
        INTERACTING,
        COLLECTING
    }

    // --- Public API ---

    public static void loadConfig() {
        BotConfig loaded = BotConfig.load();
        CONFIG.applyFrom(loaded);
    }

    public static void enqueueTask(BotTask task) {
        taskQueue.addLast(task);
        if (phase == Phase.IDLE && !paused) {
            startNextTask();
        }
    }

    public static void stop() {
        BlockInteractor.stopInteraction();
        PathWalker.stop();
        currentTask = null;
        phase = Phase.IDLE;
        phaseTicks = 0;
        taskTotalTicks = 0;
        taskQueue.clear();
        lastMinedPos = null;
        itemsSeenThisCollect = false;
        lastItemSeenTick = 0;
        airConfirmTicks = 0;
        walkAfterCollect = false;
        releaseMovementKeys();
        LOGGER.info("Bot stopped");
    }

    public static void pause() {
        paused = true;
        BlockInteractor.stopInteraction();
        releaseMovementKeys();
        LOGGER.info("Bot paused");
    }

    public static void resume() {
        paused = false;
        LOGGER.info("Bot resumed");
        if (phase == Phase.IDLE && !taskQueue.isEmpty()) {
            startNextTask();
        }
    }

    public static boolean isActive() {
        return currentTask != null && phase != Phase.IDLE;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static BotTask getCurrentTask() {
        return currentTask;
    }

    public static TaskQueue getTaskQueue() {
        return taskQueue;
    }

    // --- Tick handlers ---

    /**
     * Called at START_CLIENT_TICK, before handleKeybinds processes input.
     * Drives block interaction by calling startAttack/continueAttack directly.
     */
    public static void tickStart(Minecraft client) {
        BlockInteractor.tickInteraction();
    }

    public static void tick(Minecraft client) {
        if (paused || phase == Phase.IDLE) {
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }

        phaseTicks++;
        taskTotalTicks++;

        switch (phase) {
            case SCANNING -> tickScanning(client, player);
            case NAVIGATING -> tickNavigating(client, player);
            case POSITIONING -> tickPositioning(client, player);
            case LOOKING -> tickLooking(client, player);
            case INTERACTING -> tickInteracting(client, player);
            case COLLECTING -> tickCollecting();
            default -> { }
        }
    }

    // --- Phase implementations ---

    private static void tickNavigating(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.navigateTimeout) {
            failCurrentTask("Navigation timeout");
            return;
        }

        if (!PathWalker.isActive()) {
            // PathWalker finished (success or failure)
            if (isWithinReach(player, currentTask.targetPos())) {
                transitionTo(Phase.LOOKING);
            } else {
                transitionTo(Phase.POSITIONING);
            }
        }
    }

    private static void tickPositioning(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.positionTimeout) {
            // If we're within a generous distance, try looking anyway
            if (isWithinReach(player, currentTask.targetPos())) {
                transitionTo(Phase.LOOKING);
            } else {
                failCurrentTask("Could not get within reach of target");
            }
            return;
        }

        // Check if we're within reach
        if (isWithinReach(player, currentTask.targetPos())) {
            transitionTo(Phase.LOOKING);
            return;
        }

        // Walk toward the target using simple key input
        BlockPos target = currentTask.targetPos();
        double dx = (target.getX() + 0.5) - player.getX();
        double dz = (target.getZ() + 0.5) - player.getZ();
        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        player.setYRot(targetYaw);
        client.options.keyUp.setDown(true);
    }

    private static void tickLooking(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.lookTimeout) {
            failCurrentTask("Look timeout — could not aim at target");
            return;
        }

        if (phaseTicks == 1) {
            // Initialize camera and humanization on first tick
            camera = new CameraController();
            camera.initialize(player.getYRot(), player.getXRot());
            aimOffsetX = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetY = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetZ = HumanBehavior.randomAimOffset(CONFIG);
            settleDelay = HumanBehavior.randomSettleDelay(CONFIG);
            settleRemaining = -1; // not converged yet
            toolSelected = false;
            releaseMovementKeys();
        }

        BlockPos target = currentTask.targetPos();
        double targetX = target.getX() + 0.5 + aimOffsetX;
        double targetY = target.getY() + 0.5 + aimOffsetY;
        double targetZ = target.getZ() + 0.5 + aimOffsetZ;

        double dx = targetX - player.getX();
        double dy = targetY - player.getEyeY();
        double dz = targetZ - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        float targetPitch = (float) (-Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        float yaw = camera.updateYaw(targetYaw);
        float pitch = camera.updatePitch(targetPitch);
        player.setYRot(yaw);
        player.setXRot(pitch);

        boolean facingTarget = AngleUtil.isFacingTarget(yaw, targetYaw, (float) CONFIG.facingTolerance)
                && Math.abs(pitch - targetPitch) < CONFIG.facingTolerance;

        if (facingTarget) {
            if (settleRemaining < 0) {
                settleRemaining = settleDelay;
            }
            settleRemaining--;
            if (settleRemaining <= 0) {
                transitionTo(Phase.INTERACTING);
            }
        }
    }

    private static void tickInteracting(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.maxBreakTicks) {
            BlockInteractor.stopInteraction();
            failCurrentTask("Block break timeout");
            return;
        }

        Level level = client.level;
        BlockPos target = currentTask.targetPos();

        // Select tool on first tick
        if (!toolSelected) {
            toolSelected = true;
            InventoryHelper.selectBestTool(player, level.getBlockState(target));
            airConfirmTicks = 0;
        }

        // Hold off on the first attack for a few ticks so the server has time
        // to process the ServerboundSetCarriedItemPacket sent in selectBestTool.
        // Under accelerated ticks the next block can break within ~20 ticks
        // (~100ms wall), which beats packet round-trip — without this settle
        // the server resolves the break against a stale held slot and the drop
        // is computed with the wrong tool (e.g. iron_ore with bare hand → no drop).
        // Re-send the carried-item packet each settle tick so a dropped or
        // reordered packet doesn't leave the server on a stale held slot.
        if (phaseTicks <= CONFIG.toolSettleTicks) {
            InventoryHelper.resendCarriedItem(player);
            return;
        }

        // Keep aiming at the target (maintain camera position)
        if (camera != null) {
            double targetX = target.getX() + 0.5 + aimOffsetX;
            double targetY = target.getY() + 0.5 + aimOffsetY;
            double targetZ = target.getZ() + 0.5 + aimOffsetZ;

            double dx = targetX - player.getX();
            double dy = targetY - player.getEyeY();
            double dz = targetZ - player.getZ();
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);

            float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
            float targetPitch = (float) (-Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));

            float yaw = camera.updateYaw(targetYaw);
            float pitch = camera.updatePitch(targetPitch);
            player.setYRot(yaw);
            player.setXRot(pitch);
        }

        // Start mining if not already. Pass the explicit target so the
        // destroy packet always lands on the intended block — bypasses the
        // stale-hitResult race on the first tick of INTERACTING.
        if (!BlockInteractor.isInteracting()) {
            BlockInteractor.startInteraction(currentTask.interactionType(), target);
        }

        // Check if block is broken. Under accelerated ticks the client can
        // predict a break faster than the server processes it; the server
        // then reverts the block and no drop spawns.
        //
        // Client-side sequenced-transaction prediction means getBlockState()
        // can return air BEFORE the server has actually broken the block. To
        // avoid exiting INTERACTING on a prediction that the server later
        // reverts, we require two things:
        //   1. The block must remain air for `airConfirmTicks` consecutive
        //      ticks (sustained-air window).
        //   2. A drop entity must have spawned nearby — an authoritative
        //      signal that the server actually completed the break.
        // If the server reverts, either check fails and we keep mining.
        boolean isAir = currentTask.isCurrentTargetComplete(level);
        if (isAir) {
            airConfirmTicks++;
        } else {
            airConfirmTicks = 0;
        }
        boolean dropNearby = false;
        if (airConfirmTicks >= CONFIG.airConfirmTicks) {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    target.getX() - 2, target.getY() - 2, target.getZ() - 2,
                    target.getX() + 3, target.getY() + 3, target.getZ() + 3);
            dropNearby = !client.level.getEntities(
                    net.minecraft.world.entity.EntityType.ITEM, box, e -> true).isEmpty();
        }
        if (airConfirmTicks >= CONFIG.airConfirmTicks && dropNearby) {
            BlockInteractor.stopInteraction();

            if (CONFIG.debugEnabled) {
                LOGGER.info("Block broken at {} in {} ticks", target, phaseTicks);
            }

            // airConfirmTicks resets on re-entry via the toolSelect branch above.

            // Sub-targets remaining in same task (e.g. tree logs) — mine next immediately
            if (currentTask.advanceToNextTarget()) {
                transitionTo(Phase.LOOKING);
                return;
            }

            // Task fully done — decide what to do next based on the queue
            lastMinedPos = target;
            BotTask nextTask = taskQueue.peek();
            if (nextTask != null && isWithinReach(player, nextTask.targetPos())) {
                // Next target is within reach — mine it immediately, no pause
                currentTask = taskQueue.poll();
                taskTotalTicks = 0;
                transitionTo(Phase.LOOKING);
            } else {
                // Need to walk (or nothing left) — collect drops first
                walkAfterCollect = nextTask != null;
                itemsSeenThisCollect = false;
                lastItemSeenTick = 0;
                transitionTo(Phase.COLLECTING);
            }
        }
    }

    private static void tickCollecting() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        boolean itemsNearby = false;

        if (player != null && client.level != null) {
            if (phaseTicks == 1 && camera == null) {
                camera = new CameraController();
                camera.initialize(player.getYRot(), player.getXRot());
            }

            // Find nearby item entities within 8 blocks
            net.minecraft.world.phys.AABB searchBox = player.getBoundingBox().inflate(8.0);
            var items = client.level.getEntities(
                    net.minecraft.world.entity.EntityType.ITEM, searchBox, e -> true);
            itemsNearby = !items.isEmpty();
            if (itemsNearby) {
                itemsSeenThisCollect = true;
                lastItemSeenTick = phaseTicks;
            }

            // Walk toward the nearest item (using horizontal distance only)
            net.minecraft.world.entity.item.ItemEntity nearest = null;
            double nearestHorizDistSq = Double.MAX_VALUE;
            for (var item : items) {
                double dx = item.getX() - player.getX();
                double dz = item.getZ() - player.getZ();
                double horizDistSq = dx * dx + dz * dz;
                if (horizDistSq < nearestHorizDistSq) {
                    nearestHorizDistSq = horizDistSq;
                    nearest = item;
                }
            }

            if (nearest != null && nearestHorizDistSq > 1.0) {
                // Pickup radius ~1.5 blocks; stop at 1.0 to avoid overshoot
                walkToward(client, player, nearest.getX(), nearest.getZ(), nearestHorizDistSq);
            } else if (!itemsSeenThisCollect && lastMinedPos != null) {
                // No items visible yet, but we expect a drop at lastMinedPos.
                // Walk there so the entity enters the AABB as soon as the server
                // syncs its spawn.
                double tx = lastMinedPos.getX() + 0.5;
                double tz = lastMinedPos.getZ() + 0.5;
                double dx = tx - player.getX();
                double dz = tz - player.getZ();
                double horizDistSq = dx * dx + dz * dz;
                if (horizDistSq > 1.0) {
                    walkToward(client, player, tx, tz, horizDistSq);
                } else {
                    client.options.keyUp.setDown(false);
                    client.options.keySprint.setDown(false);
                }
            } else {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
            }
        }

        // Exit COLLECTING once items have been observed and then absent for a
        // sustained window (`itemAbsenceTicks` since last sighting). This closes both:
        //   - the server→client spawn-sync race after a block break, and
        //   - the transient absence between picking up one drop and the next
        //     drop becoming the current nearest (especially when multiple
        //     blocks are mined back-to-back).
        // Fall back to a hard timeout if drops never become reachable.
        boolean doneCollecting = itemsSeenThisCollect && !itemsNearby
                && phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks;
        boolean timedOut = phaseTicks > CONFIG.collectWaitMax;
        if (doneCollecting || timedOut) {
            // Failure-only telemetry: if COLLECTING is exiting with no items
            // ever seen, log the last mined block state + surrounding area.
            // Fires once per exit; no timing impact on the happy path.
            if (!itemsSeenThisCollect && lastMinedPos != null
                    && player != null && client.level != null) {
                var state = client.level.getBlockState(lastMinedPos);
                int around = client.level.getEntities(
                        net.minecraft.world.entity.EntityType.ITEM,
                        player.getBoundingBox().inflate(20.0), e -> true).size();
                boolean creative = player.getAbilities().instabuild;
                LOGGER.warn("COLLECTING exit WITH NO DROP: lastMinedPos={} state={} timedOut={} phaseTicks={} itemsIn20b={} creative={}",
                        lastMinedPos, state.getBlock(), timedOut, phaseTicks, around, creative);
            }
            releaseMovementKeys();
            lastMinedPos = null;
            itemsSeenThisCollect = false;
            lastItemSeenTick = 0;
            currentTask = null;
            if (walkAfterCollect && !taskQueue.isEmpty() && !paused) {
                currentTask = taskQueue.poll();
                taskTotalTicks = 0;
                transitionTo(Phase.SCANNING);
            } else {
                phase = Phase.IDLE;
                phaseTicks = 0;
                if (!taskQueue.isEmpty() && !paused) {
                    startNextTask();
                }
            }
        }
    }

    private static void walkToward(Minecraft client, LocalPlayer player,
                                   double targetX, double targetZ, double horizDistSq) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        // Smooth camera turn to avoid erratic spinning
        if (camera != null) {
            player.setYRot(camera.updateYaw(targetYaw));
        } else {
            player.setYRot(targetYaw);
        }
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(horizDistSq > 4.0);
    }

    private static void tickScanning(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.scanTimeout) {
            // Timeout — start navigation now
            beginNavigation(player);
            return;
        }

        if (phaseTicks == 1) {
            // Initialize camera from current rotation
            camera = new CameraController();
            camera.initialize(player.getYRot(), player.getXRot());
        }

        // Smoothly look toward the next target
        BlockPos target = currentTask.targetPos();
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getEyeY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        float targetPitch = (float) (-Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));

        float yaw = camera.updateYaw(targetYaw);
        float pitch = camera.updatePitch(targetPitch);
        player.setYRot(yaw);
        player.setXRot(pitch);

        // Once roughly facing the target, start walking
        boolean facing = AngleUtil.isFacingTarget(yaw, targetYaw, (float) CONFIG.scanFacingTolerance)
                && Math.abs(pitch - targetPitch) < CONFIG.scanFacingTolerance;
        if (facing) {
            beginNavigation(player);
        }
    }

    /**
     * Start PathWalker navigation for the current task, or fall back to POSITIONING.
     */
    private static void beginNavigation(LocalPlayer player) {
        BlockPos target = currentTask.targetPos();

        if (isWithinReach(player, target)) {
            transitionTo(Phase.LOOKING);
            return;
        }

        MeshNode standoff = findStandoffNode(player, target);
        if (standoff == null) {
            transitionTo(Phase.POSITIONING);
            return;
        }

        MeshNode startNode = findNearestNode(player, player.blockPosition());
        if (startNode == null) {
            transitionTo(Phase.POSITIONING);
            return;
        }

        MeshPathfinder pathfinder = new MeshPathfinder();
        List<MeshNode> path = pathfinder.findPath(startNode, standoff);
        if (path.isEmpty()) {
            transitionTo(Phase.POSITIONING);
            return;
        }

        PathWalker.start(path);
        transitionTo(Phase.NAVIGATING);
    }

    // --- State transitions ---

    private static void transitionTo(Phase newPhase) {
        if (CONFIG.debugEnabled) {
            LOGGER.info("Phase: {} → {} (task: {})", phase, newPhase,
                    currentTask != null ? currentTask.description() : "none");
        }
        phase = newPhase;
        phaseTicks = 0;
    }

    private static void startNextTask() {
        currentTask = taskQueue.poll();
        if (currentTask == null) {
            phase = Phase.IDLE;
            return;
        }

        taskTotalTicks = 0;
        if (CONFIG.debugEnabled) {
            LOGGER.info("Starting task: {}", currentTask.description());
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            failCurrentTask("No player");
            return;
        }

        BlockPos target = currentTask.targetPos();

        // Already within reach — skip navigation
        if (isWithinReach(player, target)) {
            transitionTo(Phase.LOOKING);
            return;
        }

        // Needs walking — look toward it first, then navigate
        transitionTo(Phase.SCANNING);
    }

    private static void completeCurrentTask() {
        if (currentTask != null && CONFIG.debugEnabled) {
            LOGGER.info("Task completed: {} ({} ticks)", currentTask.description(), taskTotalTicks);
        }
        currentTask = null;
        phase = Phase.IDLE;
        phaseTicks = 0;

        // Auto-start next task if queue is not empty
        if (!taskQueue.isEmpty() && !paused) {
            startNextTask();
        }
    }

    private static void failCurrentTask(String reason) {
        if (currentTask != null) {
            LOGGER.warn("Task failed: {} — {}", currentTask.description(), reason);
        }
        BlockInteractor.stopInteraction();
        releaseMovementKeys();
        currentTask = null;
        phase = Phase.IDLE;
        phaseTicks = 0;

        // Try next task
        if (!taskQueue.isEmpty() && !paused) {
            startNextTask();
        }
    }

    // --- Utility methods ---

    private static boolean isWithinReach(LocalPlayer player, BlockPos target) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getEyeY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= CONFIG.reachDistance;
    }

    /**
     * Find the nearest walkable mesh node to the given position within reach
     * of the target block.
     */
    private static MeshNode findStandoffNode(LocalPlayer player, BlockPos target) {
        HashMap<ChunkCoordinate, Mesh> meshesForPlayer = MeshManager.meshes.get(player);
        if (meshesForPlayer == null) {
            return null;
        }

        MeshNode best = null;
        double bestDist = Double.MAX_VALUE;
        double maxReach = CONFIG.reachDistance;

        // Search in chunks around the target
        int chunkX = target.getX() >> 4;
        int chunkZ = target.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Mesh mesh = meshesForPlayer.get(new ChunkCoordinate(chunkX + dx, chunkZ + dz));
                if (mesh == null) {
                    continue;
                }
                for (MeshNode node : mesh.getNodes().values()) {
                    // Check the node is within reach of the target
                    double ndx = (node.getX() + 0.5) - (target.getX() + 0.5);
                    double ndy = (node.getY() + 1.62) - (target.getY() + 0.5); // eye height
                    double ndz = (node.getZ() + 0.5) - (target.getZ() + 0.5);
                    double distToTarget = Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);
                    if (distToTarget > maxReach) {
                        continue;
                    }

                    // Y within reasonable range
                    if (Math.abs(node.getY() - target.getY()) > 3) {
                        continue;
                    }

                    // Pick the node closest to the player
                    double pdx = node.getX() - player.getX();
                    double pdz = node.getZ() - player.getZ();
                    double distToPlayer = pdx * pdx + pdz * pdz;
                    if (distToPlayer < bestDist) {
                        bestDist = distToPlayer;
                        best = node;
                    }
                }
            }
        }

        return best;
    }

    /**
     * Find the nearest mesh node to the given position.
     */
    private static MeshNode findNearestNode(LocalPlayer player, BlockPos pos) {
        HashMap<ChunkCoordinate, Mesh> meshesForPlayer = MeshManager.meshes.get(player);
        if (meshesForPlayer == null) {
            return null;
        }

        ChunkCoordinate chunkCoord = new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4);

        // Try generating mesh if it doesn't exist
        Mesh mesh = meshesForPlayer.get(chunkCoord);
        if (mesh == null) {
            Level level = Minecraft.getInstance().level;
            if (level != null) {
                MeshManager.generateMesh(level.getChunk(pos), player);
                mesh = meshesForPlayer.get(chunkCoord);
            }
        }
        if (mesh == null) {
            return null;
        }

        // Try exact match first
        MeshNode exact = mesh.getNodes().get(pos);
        if (exact != null) {
            return exact;
        }

        // Nearest in chunk
        MeshNode nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (MeshNode node : mesh.getNodes().values()) {
            double dx = node.getX() - pos.getX();
            double dy = node.getY() - pos.getY();
            double dz = node.getZ() - pos.getZ();
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                nearest = node;
            }
        }
        return nearest;
    }

    private static void releaseMovementKeys() {
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keySprint.setDown(false);
        }
    }
}

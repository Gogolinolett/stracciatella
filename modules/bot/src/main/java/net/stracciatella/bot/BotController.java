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

    // Cooldown/collection timer
    private static int waitRemaining = 0;

    public enum Phase {
        IDLE,
        NAVIGATING,
        POSITIONING,
        LOOKING,
        INTERACTING,
        COLLECTING,
        COOLDOWN
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
            case NAVIGATING -> tickNavigating(client, player);
            case POSITIONING -> tickPositioning(client, player);
            case LOOKING -> tickLooking(client, player);
            case INTERACTING -> tickInteracting(client, player);
            case COLLECTING -> tickCollecting();
            case COOLDOWN -> tickCooldown();
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

        // Start mining if not already
        if (!BlockInteractor.isInteracting()) {
            BlockInteractor.startInteraction(currentTask.interactionType());
        }

        // Check if block is broken
        if (currentTask.isCurrentTargetComplete(level)) {
            BlockInteractor.stopInteraction();

            if (CONFIG.debugEnabled) {
                LOGGER.info("Block broken at {} in {} ticks", target, phaseTicks);
            }

            // Check if task has more sub-targets (e.g. tree chopping)
            if (currentTask.advanceToNextTarget()) {
                // More sub-targets — re-enter LOOKING for next block
                transitionTo(Phase.LOOKING);
            } else {
                // Task fully done
                int waitTicks = HumanBehavior.randomPostBreakDelay(CONFIG);
                waitRemaining = waitTicks;
                transitionTo(Phase.COLLECTING);
            }
        }
    }

    private static void tickCollecting() {
        waitRemaining--;
        if (waitRemaining <= 0) {
            int cooldown = HumanBehavior.randomInterTaskDelay(CONFIG);
            waitRemaining = cooldown;
            transitionTo(Phase.COOLDOWN);
        }
    }

    private static void tickCooldown() {
        waitRemaining--;
        if (waitRemaining <= 0) {
            completeCurrentTask();
        }
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

        // Check if we're already within reach — skip navigation
        if (isWithinReach(player, target)) {
            transitionTo(Phase.LOOKING);
            return;
        }

        // Find path to a standoff position near the target
        MeshNode standoff = findStandoffNode(player, target);
        if (standoff == null) {
            // No mesh node found — try to proceed to POSITIONING phase directly
            LOGGER.warn("No standoff node found for target {}, attempting direct positioning", target);
            transitionTo(Phase.POSITIONING);
            return;
        }

        MeshNode startNode = findNearestNode(player, player.blockPosition());
        if (startNode == null) {
            LOGGER.warn("No start node found, attempting direct positioning");
            transitionTo(Phase.POSITIONING);
            return;
        }

        MeshPathfinder pathfinder = new MeshPathfinder();
        List<MeshNode> path = pathfinder.findPath(startNode, standoff);
        if (path.isEmpty()) {
            LOGGER.warn("No path found to standoff node, attempting direct positioning");
            transitionTo(Phase.POSITIONING);
            return;
        }

        PathWalker.start(path);
        transitionTo(Phase.NAVIGATING);
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

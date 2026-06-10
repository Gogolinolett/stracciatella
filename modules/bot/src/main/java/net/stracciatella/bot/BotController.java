package net.stracciatella.bot;

import java.util.HashMap;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.stracciatella.bot.humanize.HumanBehavior;
import net.stracciatella.bot.interaction.BlockInteractor;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.task.BotTask;
import net.stracciatella.bot.task.TaskQueue;
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
    private static boolean toolSelected = false;

    // Downward gaze pitch for COLLECTING (degrees below horizon), rolled once
    // per phase. Looking at drops is capped at this angle — tracking the item
    // point directly would tilt the head ever steeper on approach.
    private static double collectGazePitch = 45.0;

    // Pre-attack hesitation: ticks remaining in the "commit moment" between
    // the LOOKING gates firing and the first startAttack. -1 means inactive.
    private static int preAttackHesitationRemaining = -1;

    // Deferred-action mechanism: when a phase decides to transition, it can
    // request a Gaussian-distributed reaction delay first. During the delay
    // the current phase's tick logic is skipped (the bot "freezes" briefly,
    // as a human pausing between deciding and acting). On expiry the stored
    // Runnable runs.
    private static Runnable deferredAction = null;
    private static int deferredActionDelay = 0;

    // Last world-space point the bot's own camera was aimed at (jitter offset
    // already folded in). While a reaction delay is in flight the camera keeps
    // easing toward this point — a spring mid-swing that freezes for N ticks
    // reads as stop-motion, not as hesitation.
    private static double lastAimX;
    private static double lastAimY;
    private static double lastAimZ;
    private static boolean hasLastAim = false;

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
        // Roll a fresh session skill multiplier so each world join gives the
        // bot a slightly different aim speed / reaction baseline. Stays
        // constant for the lifetime of this session.
        HumanBehavior.rollSessionSkill();
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
        deferredAction = null;
        deferredActionDelay = 0;
        preAttackHesitationRemaining = -1;
        hasLastAim = false;
        releaseMovementKeys();
        LOGGER.info("Bot stopped");
    }

    public static void pause() {
        paused = true;
        BlockInteractor.stopInteraction();
        // Cancel any in-flight reaction delay so the deferred action doesn't
        // fire during the pause. On resume the bot re-enters whatever phase
        // it was in and re-derives the next transition naturally.
        deferredAction = null;
        deferredActionDelay = 0;
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

        // Deferred-action gate: when a reaction delay is in flight, neither
        // the phase tick nor phaseTicks/taskTotalTicks advance. The bot
        // visibly pauses for the configured number of ticks before the
        // queued action runs. The camera is NOT frozen during the pause —
        // it keeps easing toward its last aim point (finishing any in-flight
        // swing, saccading if enabled), because a gaze that halts mid-turn
        // and resumes N ticks later reads as stop-motion.
        if (deferredActionDelay > 0) {
            if (camera != null && hasLastAim) {
                camera.aimAt(player, lastAimX, lastAimY, lastAimZ);
            }
            deferredActionDelay--;
            if (deferredActionDelay == 0) {
                Runnable action = deferredAction;
                deferredAction = null;
                if (action != null) {
                    action.run();
                }
            }
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
                scheduleAction(() -> transitionTo(Phase.LOOKING));
            } else {
                failCurrentTask("Could not get within reach of target");
            }
            return;
        }

        // Check if we're within reach — reaction beat before LOOKING starts.
        if (isWithinReach(player, currentTask.targetPos())) {
            scheduleAction(() -> transitionTo(Phase.LOOKING));
            return;
        }

        if (phaseTicks == 1) {
            // Fresh camera from the player's current rotation: POSITIONING
            // follows NAVIGATING, where PathWalker rotated the player with its
            // own camera — any controller we still hold has stale yaw/pitch.
            camera = new CameraController();
            camera.initialize(player.getYRot(), player.getXRot());
            camera.setLookSpeedMultiplier(HumanBehavior.randomLookSpeedMultiplier(CONFIG));
        }

        // Walk toward the target, looking at it: the camera eases onto the
        // block (yaw and pitch) and the player walks in view direction —
        // a human closes the last few blocks watching the thing they're
        // about to mine, instead of strafing over with a hard-snapped view.
        BlockPos target = currentTask.targetPos();
        aimCameraAt(player, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        client.options.keyUp.setDown(true);
    }

    private static void tickLooking(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.lookTimeout) {
            failCurrentTask("Look timeout — could not aim at target");
            return;
        }

        BlockPos target = currentTask.targetPos();

        if (phaseTicks == 1) {
            // Initialize camera and humanization on first tick. A fresh
            // controller defaults to multiplier=1.0 and saccades off; we
            // immediately roll a per-target look-speed so the turn cadence
            // varies block-to-block.
            camera = new CameraController();
            camera.initialize(player.getYRot(), player.getXRot());
            camera.setLookSpeedMultiplier(HumanBehavior.randomLookSpeedMultiplier(CONFIG));
            aimOffsetX = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetY = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetZ = HumanBehavior.randomAimOffset(CONFIG);
            preAttackHesitationRemaining = -1;
            // Select the tool now so the carried-item (and any inventory-swap)
            // packets travel to the server *in parallel* with the smooth
            // camera turn. By the time the hit-result gate fires, the server
            // has had the entire LOOKING duration to apply them, and
            // INTERACTING can attack on its first tick without a separate
            // tool-settle pause.
            if (client.level != null) {
                InventoryHelper.selectBestTool(player, client.level.getBlockState(target));
            }
            toolSelected = true;
            releaseMovementKeys();
        } else {
            // Re-send carried-item each tick during LOOKING so a dropped or
            // reordered packet doesn't leave the server on a stale slot when
            // INTERACTING starts. Cheap, idempotent.
            InventoryHelper.resendCarriedItem(player);
        }
        // Aim at the center of the face that's most directly visible from the
        // bot's eye, not the block center. The center of a block in the middle
        // of a stack (e.g. top log of a tree) sits behind the next block's
        // face, so a raycast aimed at the center actually lands on the
        // neighbor. Aiming at the exposed face guarantees the raycast clears
        // intermediate blocks and lands on the target.
        net.minecraft.core.Direction face = BlockInteractor.faceTowardPlayer(client, target);
        double tx = target.getX() + 0.5 + face.getStepX() * 0.5;
        double ty = target.getY() + 0.5 + face.getStepY() * 0.5;
        double tz = target.getZ() + 0.5 + face.getStepZ() * 0.5;

        // Apply human-aim jitter only on the two axes perpendicular to the
        // face normal. Adding offset *along* the face normal pushes the aim
        // point off the face plane, which makes the raycast graze just past
        // the block's edge and land on a neighbor (or the platform below) —
        // the hit-result gate then never matches and the bot stares forever.
        double offX = face.getStepX() != 0 ? 0.0 : aimOffsetX;
        double offY = face.getStepY() != 0 ? 0.0 : aimOffsetY;
        double offZ = face.getStepZ() != 0 ? 0.0 : aimOffsetZ;

        aimCameraAt(player, tx + offX, ty + offY, tz + offZ);

        // Single gate before transitioning to INTERACTING: the client's
        // raycast actually lands on the target block. A human starts mining
        // the moment the crosshair touches the block — not once the camera
        // has settled on its ideal aim point — so no angular convergence is
        // required; the camera keeps easing toward the aim point during
        // INTERACTING. The hit-result check is authoritative for "am I
        // looking at it": when an obstacle blocks the line of sight the
        // raycast lands on the obstacle and the gate holds (lookTimeout
        // fails the task cleanly if it never clears).
        boolean aimedHit = isHitResultOnTarget(client, target);
        if (aimedHit) {
            // Pre-attack commit hesitation: between the moment both gates
            // fire and the first startAttack, a human pauses ~50-150 ms (the
            // "I've locked on, now I click" beat). This is distinct from the
            // old settleDelay (a timing buffer for server packet sync) —
            // tool selection already happened during LOOKING's camera turn,
            // so the server is in sync by now. The hesitation here is purely
            // humanness. During it the camera keeps aiming and saccades
            // start (sustained aim is when frozen-gaze is most visible).
            if (preAttackHesitationRemaining < 0) {
                preAttackHesitationRemaining = HumanBehavior.randomPreAttackHesitation(CONFIG);
                camera.setMicroSaccadesEnabled(true);
            }
            if (preAttackHesitationRemaining > 0) {
                preAttackHesitationRemaining--;
                return;
            }
            preAttackHesitationRemaining = -1;
            transitionTo(Phase.INTERACTING);
        }
    }

    /**
     * Verify that the client-side raycast is currently pointing at the target
     * block. Used as the second gate in LOOKING before transitioning to
     * INTERACTING — prevents the bot from starting to attack a block the
     * camera is only angularly close to (but not actually pointing at, e.g.
     * because an obstacle sits in the line of sight).
     */
    private static boolean isHitResultOnTarget(Minecraft client, BlockPos target) {
        HitResult hr = client.hitResult;
        if (!(hr instanceof BlockHitResult bhr)) {
            return false;
        }
        return bhr.getBlockPos().equals(target);
    }

    private static void tickInteracting(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.maxBreakTicks) {
            BlockInteractor.stopInteraction();
            failCurrentTask("Block break timeout");
            return;
        }

        Level level = client.level;
        BlockPos target = currentTask.targetPos();

        // Tool was selected in LOOKING — the carried-item packet has already
        // had the entire camera-convergence window to be applied server-side,
        // so no explicit tool-settle wait is needed here. Reset
        // airConfirmTicks on the first tick (LOOKING may be re-entered for
        // sub-targets like sequential tree logs).
        if (phaseTicks == 1) {
            airConfirmTicks = 0;
            // Defensive re-send in case the packet was dropped while turning.
            InventoryHelper.resendCarriedItem(player);
            // Sustained aim during mining is when "frozen gaze" reads as bot.
            // Saccades may have been enabled by LOOKING's hesitation gate;
            // ensure they're on here as well in case hesitation was 0.
            if (camera != null) {
                camera.setMicroSaccadesEnabled(true);
            }
        }

        // Keep aiming at the target's exposed face (matches LOOKING's aim
        // point — without this the camera would snap back to block center on
        // entering INTERACTING and the raycast could land on a neighbor).
        // Offsets along the face normal are zeroed for the same reason
        // LOOKING does it (see comment there).
        if (camera != null) {
            net.minecraft.core.Direction face = BlockInteractor.faceTowardPlayer(client, target);
            double offX = face.getStepX() != 0 ? 0.0 : aimOffsetX;
            double offY = face.getStepY() != 0 ? 0.0 : aimOffsetY;
            double offZ = face.getStepZ() != 0 ? 0.0 : aimOffsetZ;
            aimCameraAt(player,
                    target.getX() + 0.5 + face.getStepX() * 0.5 + offX,
                    target.getY() + 0.5 + face.getStepY() * 0.5 + offY,
                    target.getZ() + 0.5 + face.getStepZ() * 0.5 + offZ);
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
            //
            // Reaction beat between "the block broke" and the next phase —
            // a human glances at the result for a moment before moving on.
            // For sub-targets and same-reach next-targets we re-enter LOOKING;
            // otherwise we COLLECT drops before walking.

            // Sub-targets remaining in same task (e.g. tree logs) — mine next next
            if (currentTask.advanceToNextTarget()) {
                scheduleAction(() -> transitionTo(Phase.LOOKING));
                return;
            }

            // Task fully done — decide what to do next based on the queue.
            // The "next" task is always the one nearest to the player, not
            // the queue head (see startNextTask).
            lastMinedPos = target;
            BotTask nextTask = taskQueue.peekNearest(player.blockPosition());
            if (nextTask != null && isWithinReach(player, nextTask.targetPos())) {
                // Next target is within reach — mine it next, after the beat
                scheduleAction(() -> {
                    LocalPlayer p = Minecraft.getInstance().player;
                    currentTask = p != null
                            ? taskQueue.pollNearest(p.blockPosition())
                            : taskQueue.poll();
                    taskTotalTicks = 0;
                    transitionTo(Phase.LOOKING);
                });
            } else {
                // Need to walk (or nothing left) — collect drops first.
                // Don't delay here: COLLECTING begins immediately so the bot
                // starts pursuing drops while they're still falling.
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
            if (phaseTicks == 1) {
                if (camera == null) {
                    camera = new CameraController();
                    camera.initialize(player.getYRot(), player.getXRot());
                }
                // The bot watches its own pickup — a perfectly frozen gaze
                // while standing over the drops reads as bot. Saccades are
                // usually already on from INTERACTING; ensure it.
                camera.setMicroSaccadesEnabled(true);
                // Roll this collect's ground-scan angle (~45° with variance).
                collectGazePitch = HumanBehavior.randomCollectGazePitch(CONFIG);
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

            // Pick the nearest item by 3D distance (not just horizontal).
            // Items frequently bounce into the dug-out hole below the bot or
            // onto a step above — a horizontal-only nearest skews toward an
            // item that's actually further away in 3D, and the bot ends up
            // walking past closer drops. Items more than 4 blocks above/below
            // the player are skipped: the bot can't walk up walls or fall
            // safely into deep voids, so chasing them wastes the collect window.
            net.minecraft.world.entity.item.ItemEntity nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            double nearestHorizDistSq = Double.MAX_VALUE;
            for (var item : items) {
                double dx = item.getX() - player.getX();
                double dy = item.getY() - player.getY();
                double dz = item.getZ() - player.getZ();
                if (Math.abs(dy) > 4.0) {
                    continue;
                }
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestHorizDistSq = dx * dx + dz * dz;
                    nearest = item;
                }
            }

            // Vanilla pickup radius is ~1.5 blocks (3D). Stop walking once
            // we're inside that radius — any closer and we'd overshoot.
            // 1.5² = 2.25.
            if (nearest != null && nearestDistSq > 2.25) {
                walkToward(client, player, nearest.getX(), nearest.getY() + 0.2, nearest.getZ(),
                        nearestHorizDistSq);
            } else if (nearest != null) {
                // In pickup range — stand still and watch the drop slide
                // over. Skip the look when the item is almost directly
                // underfoot: the yaw target becomes unstable there (tiny
                // horizontal deltas flip it tick-to-tick) and craning
                // straight down isn't what a player does anyway.
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                if (nearestHorizDistSq > 0.5) {
                    aimCollectGaze(player, nearest.getX(), nearest.getY() + 0.2, nearest.getZ());
                } else if (hasLastAim) {
                    aimCameraAt(player, lastAimX, lastAimY, lastAimZ);
                }
            } else if (!itemsSeenThisCollect && lastMinedPos != null) {
                // No items visible yet, but we expect a drop at lastMinedPos.
                // Walk there so the entity enters the AABB as soon as the server
                // syncs its spawn.
                double tx = lastMinedPos.getX() + 0.5;
                double ty = lastMinedPos.getY() + 0.5;
                double tz = lastMinedPos.getZ() + 0.5;
                double dx = tx - player.getX();
                double dz = tz - player.getZ();
                double horizDistSq = dx * dx + dz * dz;
                if (horizDistSq > 1.0) {
                    walkToward(client, player, tx, ty, tz, horizDistSq);
                } else {
                    client.options.keyUp.setDown(false);
                    client.options.keySprint.setDown(false);
                    // Keep watching the spot where the drop will appear.
                    aimCollectGaze(player, tx, ty, tz);
                }
            } else {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                // Everything picked up, waiting out the absence window —
                // let the camera finish its swing and keep saccading
                // rather than freezing in place.
                if (hasLastAim) {
                    aimCameraAt(player, lastAimX, lastAimY, lastAimZ);
                }
            }
        }

        // Exit COLLECTING once items have been observed and then absent for a
        // sustained window (`itemAbsenceTicks` since last sighting). This closes
        // both the server→client spawn-sync race after a block break and the
        // transient absence between picking up one drop and the next.
        //
        // When a further task is queued that needs walking, exit as soon as
        // items are absent — no absence-tick wait. The bot will then SCAN →
        // NAVIGATE toward the next target, and any straggler drops along the
        // walk path get picked up by the 1.5-block radius. This eliminates the
        // visible "pause after pickup" that made multi-block flows feel choppy.
        boolean awaitingWalk = walkAfterCollect && taskQueue.peek() != null;
        boolean doneCollecting = itemsSeenThisCollect && !itemsNearby
                && (awaitingWalk || phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks);
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
                currentTask = player != null
                        ? taskQueue.pollNearest(player.blockPosition())
                        : taskQueue.poll();
                taskTotalTicks = 0;
                // tickCollecting walks toward the next task while items
                // settle, so by the time we exit we may already be in reach.
                // Skip SCANNING in that case — the human-like "look at next
                // target before moving" cue is unnecessary when we're
                // already standing on it.
                if (player != null && isWithinReach(player, currentTask.targetPos())) {
                    transitionTo(Phase.LOOKING);
                } else {
                    transitionTo(Phase.SCANNING);
                }
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
                                   double targetX, double targetY, double targetZ,
                                   double horizDistSq) {
        // Look at what we're walking to — yaw steers the walk, pitch follows
        // the target but is capped at the ground-scan angle so the head
        // doesn't crane ever steeper as the bot closes in on a drop.
        aimCollectGaze(player, targetX, targetY, targetZ);
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(horizDistSq > 4.0);
    }

    private static void tickScanning(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.scanTimeout) {
            // Timeout — start navigation after a reaction beat
            scheduleAction(BotController::beginNavigationFresh);
            return;
        }

        if (phaseTicks == 1) {
            // Initialize camera from current rotation and roll a per-target
            // look-speed so successive aims don't all turn at the same rate.
            camera = new CameraController();
            camera.initialize(player.getYRot(), player.getXRot());
            camera.setLookSpeedMultiplier(HumanBehavior.randomLookSpeedMultiplier(CONFIG));
        }

        // Smoothly look toward the next target
        BlockPos target = currentTask.targetPos();
        double tx = target.getX() + 0.5;
        double ty = target.getY() + 0.5;
        double tz = target.getZ() + 0.5;

        aimCameraAt(player, tx, ty, tz);

        if (camera.isAimedAt(player, tx, ty, tz, (float) CONFIG.scanFacingTolerance)) {
            // Reaction delay between "I've spotted it" and "I start walking".
            // Occasionally a longer breather — humans don't set off with a
            // machine-constant cadence every single time.
            scheduleAction(BotController::beginNavigationFresh,
                    HumanBehavior.randomTaskSwitchDelayTicks(CONFIG));
        }
    }

    /**
     * Wrapper for {@link #beginNavigation(LocalPlayer)} that re-fetches the
     * player from the client. Used by {@link #scheduleAction} so the captured
     * player reference is not stale across the delay.
     */
    private static void beginNavigationFresh() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && currentTask != null) {
            beginNavigation(p);
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
        preAttackHesitationRemaining = -1;
    }

    /**
     * Queue an action to run after a Gaussian-distributed reaction delay
     * (configured via {@code reactionDelay*} in BotConfig). During the delay
     * the current phase's tick logic is skipped — the bot freezes briefly,
     * mimicking the gap between a human deciding to act and acting. If the
     * configured delay is 0 the action runs immediately, preserving the
     * old behaviour when humanness is disabled.
     */
    private static void scheduleAction(Runnable action) {
        scheduleAction(action, HumanBehavior.randomReactionDelayTicks(CONFIG));
    }

    /**
     * Variant with an explicit delay for callers that draw from a different
     * distribution (e.g. the occasional long "breather" pause at
     * SCANNING→NAVIGATING).
     */
    private static void scheduleAction(Runnable action, int delay) {
        if (delay <= 0) {
            action.run();
            return;
        }
        deferredAction = action;
        deferredActionDelay = delay;
        // Release any held movement input so the bot visibly pauses rather
        // than coasting forward into the delay. Interaction is left alone
        // because INTERACTING calls stopInteraction itself before scheduling.
        releaseMovementKeys();
    }

    private static void startNextTask() {
        LocalPlayer player = Minecraft.getInstance().player;
        // Take the task nearest to where the bot currently stands. A human
        // works an area closest-first from wherever they are — replaying the
        // queue's fixed scan order instead produces visible zigzag routes.
        currentTask = player != null
                ? taskQueue.pollNearest(player.blockPosition())
                : taskQueue.poll();
        if (currentTask == null) {
            phase = Phase.IDLE;
            return;
        }

        taskTotalTicks = 0;
        if (CONFIG.debugEnabled) {
            LOGGER.info("Starting task: {}", currentTask.description());
        }

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
        deferredAction = null;
        deferredActionDelay = 0;
        preAttackHesitationRemaining = -1;

        // Try next task
        if (!taskQueue.isEmpty() && !paused) {
            startNextTask();
        }
    }

    // --- Utility methods ---

    /**
     * Drive the bot's camera one smoothing tick toward the given world-space
     * point (any jitter offset already folded in) and remember the point, so
     * an in-flight reaction delay can keep easing the camera toward it
     * instead of freezing it mid-swing.
     */
    private static void aimCameraAt(LocalPlayer player, double x, double y, double z) {
        camera.aimAt(player, x, y, z);
        lastAimX = x;
        lastAimY = y;
        lastAimZ = z;
        hasLastAim = true;
    }

    /**
     * Aim toward a drop (or expected drop spot) with the downward pitch capped
     * at this collect's {@code collectGazePitch}. Tracking the item point
     * directly makes the head tilt ever steeper as the bot closes in; a player
     * instead holds a ~45° "scanning the ground ahead" angle, so once the
     * direct angle would exceed the cap, the aim point is pushed out along the
     * same horizontal direction (same yaw — the walk steering is unaffected)
     * to the distance where the pitch equals the cap.
     */
    private static void aimCollectGaze(LocalPlayer player, double x, double y, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        double drop = player.getEyeY() - y;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double pitchToTarget = Math.toDegrees(Math.atan2(drop, horizDist));
        if (pitchToTarget > collectGazePitch) {
            double aimDist = drop / Math.tan(Math.toRadians(collectGazePitch));
            if (horizDist > 1.0e-4) {
                double scale = aimDist / horizDist;
                x = player.getX() + dx * scale;
                z = player.getZ() + dz * scale;
            } else {
                // Target directly underfoot — look down at the capped angle
                // in the current view direction.
                double yawRad = Math.toRadians(player.getYRot());
                x = player.getX() - Math.sin(yawRad) * aimDist;
                z = player.getZ() + Math.cos(yawRad) * aimDist;
            }
        }
        aimCameraAt(player, x, y, z);
    }

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

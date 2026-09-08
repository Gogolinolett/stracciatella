package net.stracciatella.bot;

import java.util.HashMap;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.stracciatella.bot.humanize.HumanBehavior;
import net.stracciatella.bot.interaction.BlockInteractor;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.interaction.ServerBlockSync;
import net.stracciatella.bot.task.BotTask;
import net.stracciatella.bot.task.InteractionType;
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

    // What the currently running behavior asked the execution layer to do
    // differently. Pushed in by BehaviorRunner on start and reset on stop;
    // none() for tasks issued straight from /bot commands, which keeps their
    // behaviour byte-identical to before policies existed.
    private static BotPolicy policy = BotPolicy.none();

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

    // One re-approach per target: when LOOKING can't get the raycast onto
    // the target (typically aimed from a spot where another block covers
    // it), walk closer once and retry instead of failing. A human steps up
    // to a block they can't see from where they stand. The decision fires
    // early — once the camera has settled and the crosshair has rested on
    // the same wrong block for a streak of ticks, waiting out the full
    // lookTimeout is just staring. The second failure fails the task.
    // forceApproach makes POSITIONING walk to close range instead of
    // stopping at reach distance.
    private static boolean lookRetryUsed = false;
    private static boolean forceApproach = false;
    private static int wrongHitStreak = 0;
    private static BlockPos lastWrongHit = null;
    private static final double APPROACH_CLOSE_DISTANCE = 2.0;
    private static final int WRONG_HIT_STREAK_TICKS = 8;

    // Opportunistic collection (policy-gated, INTERACTING only). The range is
    // deliberately short: a drop further than this can't be fetched and
    // returned from without the break suffering, and COLLECTING gets it anyway.
    private static final double OPPORTUNISTIC_ITEM_RANGE = 3.0;
    // Vanilla's pickup reach: Player.touch inflates the player box by 1.0
    // horizontally, so a 0.6-wide player takes a 0.25-wide item up to
    // 0.3 + 1.0 + 0.125 blocks out on an axis. Inside it, walking closer only
    // overshoots — but not a hand's breadth further out, which is what the 1.5
    // this used to hold got wrong.
    private static final double OPPORTUNISTIC_STOP_DISTANCE = 1.425;
    // How far ahead a step is projected when testing whether it is safe. One
    // block is about four ticks of walking — far enough that the check sees
    // the hazard before the bot is standing in it.
    private static final double STEP_LOOKAHEAD = 1.0;
    // How far across the face the aim point leans toward the next target, and
    // how much of the face is kept clear of the rim so the raycast still lands
    // on this block rather than its neighbour.
    private static final double AIM_LOOKAHEAD_BIAS = 0.3;
    private static final double AIM_EDGE_MARGIN = 0.15;

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
    // Walk-progress watchdog for the item the bot is currently closing on:
    // its entity id, the closest it has ever been, and how long it has been
    // since that got any closer. See the unreachable-drop note in
    // tickCollecting.
    private static int collectNearestId = -1;
    private static double collectBestDistSq = Double.MAX_VALUE;
    private static int collectNoProgressTicks = 0;
    // Interaction state — how many consecutive ticks the current target has
    // been observed in its completed state (air for mining, a solid block for
    // placing). We require a sustained window to ensure the server confirmed
    // the interaction (not just client-side prediction, which gets reverted
    // if the server rejects it under accelerated ticks). The window length is
    // CONFIG.airConfirmTicks, named for its original mining-only purpose.
    private static int stateConfirmTicks = 0;
    // Set while the bot moves from one block of a seam to the next without
    // stepping — see continueSeam. Cleared when a fresh task starts.
    private static boolean seamContinuation = false;
    // Prediction sequence in effect when the target was first seen finished.
    // Once the server has acknowledged this far, the client's view of the
    // block is the server's view and no waiting window is needed.
    private static int completionSequence = -1;
    // Latched true when a drop entity has been observed near the target at
    // any point during this break. The drop must be polled every tick: when
    // the bot stands right next to the block (gallery mining, re-approach),
    // vanilla pickup inhales the drop before a once-after-air-confirm query
    // would ever see it.
    private static boolean dropSeenThisBreak = false;
    // Total main-inventory item count when the interaction started. A pickup
    // raises it — a server-authoritative break signal for exactly the
    // inhaled-drop case; a placement lowers it by the block consumed, which
    // is the equivalent signal for USE.
    private static int startInventoryCount = 0;
    // TEMPORARY (multiplayer break investigation, remove with the fix).
    // How many times the target went back from finished to standing during
    // one interaction — i.e. how often the server refused the prediction —
    // and the tick it first looked finished. Together with the exit reason
    // they say whether the server rejected the break or merely acked slowly.
    private static int stateReverts = 0;
    private static int firstDoneTick = -1;

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

    /**
     * Install the active behavior's policy. Passing {@code null} restores
     * {@link BotPolicy#none()} — the plain, pre-policy execution.
     */
    public static void setPolicy(BotPolicy newPolicy) {
        policy = newPolicy != null ? newPolicy : BotPolicy.none();
    }

    public static BotPolicy policy() {
        return policy;
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
        stateConfirmTicks = 0;
        completionSequence = -1;
        seamContinuation = false;
        ServerBlockSync.reset();
        deferredAction = null;
        deferredActionDelay = 0;
        preAttackHesitationRemaining = -1;
        hasLastAim = false;
        lookRetryUsed = false;
        forceApproach = false;
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
            forceApproach = false;
            if (isWithinReach(player, currentTask.targetPos())) {
                scheduleAction(() -> transitionTo(Phase.LOOKING));
            } else {
                // Failure-only diagnostics: without them this message says only
                // that the walk did not finish, which is the one thing already
                // known. Where the bot ended up and what it was standing in is
                // what separates "boxed into its own hole" from "path ran out".
                BlockPos t = currentTask.targetPos();
                BlockPos feet = player.blockPosition();
                LOGGER.warn("Position timeout diagnostics: target={} dist={} player=({}, {}, {})"
                        + " feet={} under={} head={} onGround={} pathActive={}",
                        t.toShortString(), String.format("%.2f", Math.sqrt(
                                player.distanceToSqr(Vec3.atCenterOf(t)))),
                        String.format("%.2f", player.getX()), String.format("%.2f", player.getY()),
                        String.format("%.2f", player.getZ()), feet.toShortString(),
                        client.level.getBlockState(feet.below()),
                        client.level.getBlockState(feet.above()), player.onGround(),
                        PathWalker.isActive());
                failCurrentTask("Could not get within reach of target");
            }
            return;
        }

        // Close enough? Reaction beat before LOOKING starts. After a failed
        // look (forceApproach) "close enough" means close range, not reach —
        // the whole point of the re-approach is to change the viewpoint.
        //
        // A behavior that asked for approachOccluded gets the viewpoint stated
        // outright instead of approximated by a distance: walk until the sight
        // line is clear. Two blocks is no use to a bot that is already at 1.4
        // and hidden behind a corner — it arrives before it has moved, which is
        // the whole failure this replaces.
        BlockPos approaching = currentTask.targetPos();
        boolean arrived = forceApproach && policy.approachOccluded()
                ? hasLineOfSight(client, player, approaching)
                : distanceToTarget(player, approaching)
                        <= (forceApproach ? APPROACH_CLOSE_DISTANCE : CONFIG.reachDistance);
        if (arrived) {
            forceApproach = false;
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
            if (!lookRetryUsed) {
                // Couldn't get the raycast onto the target from here — step
                // closer once and try again, the way a human would.
                lookRetryUsed = true;
                forceApproach = true;
                if (CONFIG.debugEnabled) {
                    LOGGER.info("Look timeout — re-approaching {}", currentTask.targetPos());
                }
                transitionTo(Phase.POSITIONING);
                return;
            }
            // Failure-only diagnostics: where the bot stood, which face it
            // aimed for, and what the crosshair raycast actually hit.
            BlockPos t = currentTask.targetPos();
            net.minecraft.core.Direction face = aimFace(client, currentTask);
            HitResult hr = client.hitResult;
            String hit = hr instanceof BlockHitResult bhr
                    ? bhr.getBlockPos().toShortString() + " (" + bhr.getDirection() + ")"
                    : String.valueOf(hr == null ? null : hr.getType());
            LOGGER.warn("Look timeout diagnostics: target={} face={} player=({}, {}, {}) hitResult={}",
                    t.toShortString(), face,
                    String.format(java.util.Locale.US, "%.2f", player.getX()),
                    String.format(java.util.Locale.US, "%.2f", player.getY()),
                    String.format(java.util.Locale.US, "%.2f", player.getZ()), hit);
            failCurrentTask("Look timeout — could not aim at target");
            return;
        }

        BlockPos target = currentTask.targetPos();

        if (phaseTicks == 1) {
            // Initialize camera and humanization on first tick. A fresh
            // controller defaults to multiplier=1.0 and saccades off; we
            // immediately roll a per-target look-speed so the turn cadence
            // varies block-to-block.
            //
            // A seam continuation keeps the camera it already has. Rebuilding
            // it zeroes the spring's angular velocity, so every block of a
            // corridor restarts the sweep from a standstill — a hand already
            // moving does not stop between two blocks of the same seam.
            if (camera == null || !seamContinuation) {
                camera = new CameraController();
                camera.initialize(player.getYRot(), player.getXRot());
            }
            camera.setLookSpeedMultiplier(HumanBehavior.randomLookSpeedMultiplier(CONFIG));
            aimOffsetX = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetY = HumanBehavior.randomAimOffset(CONFIG);
            aimOffsetZ = HumanBehavior.randomAimOffset(CONFIG);
            preAttackHesitationRemaining = -1;
            wrongHitStreak = 0;
            lastWrongHit = null;
            // Select the tool now so the carried-item (and any inventory-swap)
            // packets travel to the server *in parallel* with the smooth
            // camera turn. By the time the hit-result gate fires, the server
            // has had the entire LOOKING duration to apply them, and
            // INTERACTING can attack on its first tick without a separate
            // tool-settle pause. A task that names its own item (placement)
            // gets that instead of the fastest tool for the clicked block.
            if (client.level != null) {
                var required = currentTask.requiredItem();
                if (required != null) {
                    InventoryHelper.selectItem(player, required);
                } else {
                    InventoryHelper.selectBestTool(player, client.level.getBlockState(target));
                }
            }
            toolSelected = true;
            releaseMovementKeys();
        }
        // Aim at the center of the face that's most directly visible from the
        // bot's eye, not the block center. The center of a block in the middle
        // of a stack (e.g. top log of a tree) sits behind the next block's
        // face, so a raycast aimed at the center actually lands on the
        // neighbor. Aiming at the exposed face guarantees the raycast clears
        // intermediate blocks and lands on the target.
        net.minecraft.core.Direction face = aimFace(client, currentTask);
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

        // Then slide the aim point across the face toward wherever the work
        // goes next, so the camera is already leaning that way when the target
        // switches. Mining a 2-high column, the two blocks sit one above the
        // other: aiming at each face's centre swings the head through the
        // whole angle between them, while aiming near their shared edge makes
        // the switch a few degrees. Someone digging a corridor does the same —
        // they look at the seam, not at two separate block centres. The bias
        // stays inside AIM_EDGE_MARGIN of the rim, because the raycast has to
        // keep landing on this block: past the edge it catches the neighbour
        // and the hit-result gate never fires.
        BotTask next = peekNextTask(player);
        if (next != null) {
            BlockPos toward = next.targetPos();
            offX = biasTowardNext(offX, face.getStepX(), toward.getX() - target.getX());
            offY = biasTowardNext(offY, face.getStepY(), toward.getY() - target.getY());
            offZ = biasTowardNext(offZ, face.getStepZ(), toward.getZ() - target.getZ());
        }

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
        boolean aimedHit = isHitResultOnTarget(client, target, currentTask.preferredFace());
        if (!aimedHit) {
            // Something is in the way, and no amount of aiming moves it. The
            // streak below waits for the camera to settle before it believes
            // that, because a crosshair still travelling lands on all sorts of
            // blocks; a clip does not care where the camera points, so a
            // behavior that has asked for the approach gets it on tick one
            // rather than eight ticks of staring later.
            if (policy.approachOccluded() && !lookRetryUsed
                    && !hasLineOfSight(client, player, target)) {
                lookRetryUsed = true;
                forceApproach = true;
                if (CONFIG.debugEnabled) {
                    LOGGER.info("Target {} is hidden — approaching", target);
                }
                transitionTo(Phase.POSITIONING);
                return;
            }
            // Early re-approach: the camera has settled on its aim point but
            // the crosshair keeps resting on the same other block — the
            // geometry won't change by staring, so step closer now instead
            // of waiting out the full lookTimeout.
            boolean aimSettled = camera.isAimedAt(player, tx, ty, tz, offX, offY, offZ, 3.0f);
            BlockPos hitBlock = client.hitResult instanceof BlockHitResult bhr
                    ? bhr.getBlockPos() : null;
            if (aimSettled && hitBlock != null && hitBlock.equals(lastWrongHit)) {
                wrongHitStreak++;
            } else {
                wrongHitStreak = aimSettled && hitBlock != null ? 1 : 0;
            }
            lastWrongHit = hitBlock;
            if (wrongHitStreak >= WRONG_HIT_STREAK_TICKS && !lookRetryUsed) {
                lookRetryUsed = true;
                forceApproach = true;
                wrongHitStreak = 0;
                if (CONFIG.debugEnabled) {
                    LOGGER.info("Crosshair stuck on {} — re-approaching {}", hitBlock, target);
                }
                transitionTo(Phase.POSITIONING);
                return;
            }
        } else {
            wrongHitStreak = 0;
        }
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
                preAttackHesitationRemaining = seamContinuation
                        ? 0 : HumanBehavior.randomPreAttackHesitation(CONFIG);
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
     * <p>
     * When {@code requiredFace} is non-null the raycast must also land on that
     * face. Mining passes null (any face of the right block will break it),
     * placement passes the face it must build off — clicking the wrong side of
     * the support block would put the new block somewhere else entirely.
     */
    private static boolean isHitResultOnTarget(Minecraft client, BlockPos target,
                                               net.minecraft.core.Direction requiredFace) {
        HitResult hr = client.hitResult;
        if (!(hr instanceof BlockHitResult bhr)) {
            return false;
        }
        if (!bhr.getBlockPos().equals(target)) {
            return false;
        }
        return requiredFace == null || bhr.getDirection() == requiredFace;
    }

    /**
     * Whether anything stands between the bot's eye and the target block.
     *
     * <p>Geometry, not aim: this is a clip from the eye to the block's centre
     * and says nothing about where the camera currently points, which is what
     * makes it usable the moment LOOKING starts instead of after the camera has
     * settled on the wrong block for {@link #WRONG_HIT_STREAK_TICKS} ticks. A
     * ray that ends on the target itself counts as clear — clip stops at the
     * target's own outline on the way to its centre.
     */
    private static boolean hasLineOfSight(Minecraft client, LocalPlayer player, BlockPos target) {
        BlockHitResult clip = client.level.clip(new ClipContext(player.getEyePosition(),
                Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return clip.getType() != HitResult.Type.BLOCK || clip.getBlockPos().equals(target);
    }

    /**
     * The face of the task's target block to aim at and interact with: the
     * task's own choice when it has one (placement dictates its face), else
     * the face most directly visible from the bot's eye.
     */
    private static net.minecraft.core.Direction aimFace(Minecraft client, BotTask task) {
        net.minecraft.core.Direction preferred = task.preferredFace();
        return preferred != null ? preferred : BlockInteractor.faceTowardPlayer(client, task.targetPos());
    }

    private static void tickInteracting(Minecraft client, LocalPlayer player) {
        if (phaseTicks > CONFIG.maxBreakTicks) {
            BlockInteractor.stopInteraction();
            releaseMovementKeys();
            // Failure-only diagnostics. The three ways a break can burn the
            // full timeout look identical from the message alone: the server
            // rejecting it out of reach, the wrong item in hand, or the block
            // never having been breakable. All three are in here.
            BlockPos t = currentTask.targetPos();
            HitResult hr = client.hitResult;
            LOGGER.warn("Interaction timeout diagnostics: target={} state={} dist={}"
                    // TEMPORARY (multiplayer break investigation): the same
                    // three numbers the BREAKWIRE line carries, so a timeout
                    // says whether the server kept reverting the prediction.
                    + " firstDone={} reverts={} seq={} acked={}"
                    + " player=({}, {}, {}) held={} hitResult={}",
                    t.toShortString(), client.level.getBlockState(t),
                    String.format("%.2f", Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(t)))),
                    firstDoneTick, stateReverts, completionSequence, ServerBlockSync.lastAcked(),
                    String.format("%.2f", player.getX()), String.format("%.2f", player.getY()),
                    String.format("%.2f", player.getZ()), player.getMainHandItem(),
                    hr instanceof BlockHitResult bhr ? bhr.getBlockPos().toShortString() : hr);
            failCurrentTask(currentTask.interactionType() == InteractionType.USE
                    ? "Block place timeout" : "Block break timeout");
            return;
        }

        Level level = client.level;
        BlockPos target = currentTask.targetPos();
        boolean placing = currentTask.interactionType() == InteractionType.USE;
        boolean targetDone = currentTask.isCurrentTargetComplete(level);

        // Tool was selected in LOOKING — the carried-item packet has already
        // had the entire camera-convergence window to be applied server-side,
        // so no explicit tool-settle wait is needed here. Reset
        // airConfirmTicks on the first tick (LOOKING may be re-entered for
        // sub-targets like sequential tree logs).
        if (phaseTicks == 1) {
            stateConfirmTicks = 0;
            completionSequence = -1;
            dropSeenThisBreak = false;
            stateReverts = 0;
            firstDoneTick = -1;
            startInventoryCount = countMainInventory(player);
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
        //
        // Once the client has already removed the block, aim at whatever comes
        // next instead. As far as the arm is concerned the break is over —
        // vanilla's continueDestroyBlock returns false on air, so nothing
        // swings — and what is left is waiting for the server to settle the
        // prediction, which no camera angle can influence. A person's mouse is
        // travelling by then; standing in the finished hole until the ack lands
        // pays LOOKING's turn *after* the wait instead of through it. Only for
        // a task with nothing left of its own: a tree's next log is a
        // sub-target, not a queue entry, and turning to a queued task there
        // would be a wrong turn rather than an early one.
        BotTask aimTask = currentTask;
        if (!placing && targetDone && currentTask.isFullyComplete()) {
            BotTask ahead = peekNextTask(player);
            if (ahead != null && ahead.interactionType() == InteractionType.ATTACK
                    && isWithinReach(player, ahead.targetPos())) {
                aimTask = ahead;
            }
        }
        if (camera != null) {
            BlockPos aimPos = aimTask.targetPos();
            net.minecraft.core.Direction face = aimFace(client, aimTask);
            double offX = face.getStepX() != 0 ? 0.0 : aimOffsetX;
            double offY = face.getStepY() != 0 ? 0.0 : aimOffsetY;
            double offZ = face.getStepZ() != 0 ? 0.0 : aimOffsetZ;
            aimCameraAt(player,
                    aimPos.getX() + 0.5 + face.getStepX() * 0.5 + offX,
                    aimPos.getY() + 0.5 + face.getStepY() * 0.5 + offY,
                    aimPos.getZ() + 0.5 + face.getStepZ() * 0.5 + offZ);
        }

        // Step toward drops without interrupting the break — the camera above
        // has already been committed to the target this tick, so this can only
        // move the body. Placement is excluded: there is nothing to collect and
        // a placement's aim is pinned to one face, which a step would spoil.
        if (policy.opportunisticCollection() && !placing) {
            tickOpportunisticCollection(client, player, target);
        }

        // Start interacting if not already. Pass the explicit target so the
        // packet always lands on the intended block — bypasses the
        // stale-hitResult race on the first tick of INTERACTING. The face is
        // null for mining (re-derived per tick as the bot moves) and pinned
        // for placement.
        if (!BlockInteractor.isInteracting()) {
            BlockInteractor.startInteraction(currentTask.interactionType(), target,
                    currentTask.preferredFace());
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
        //   2. A server-authoritative break artifact: a drop entity observed
        //      near the target at ANY point since the break started
        //      (latched, polled every tick — when the bot stands right next
        //      to the block, vanilla pickup inhales the drop within a tick),
        //      OR the main inventory grew since the break started (the
        //      pickup itself, also server-driven via slot sync). Without the
        //      latch+inventory path the bot kept attacking the already-broken
        //      block for the full maxBreakTicks — "punching air".
        // If the server reverts, the state check fails and we keep going.
        //
        // Placement needs the same two signals, mirrored: the destination
        // holds a solid block for a sustained window, and the main inventory
        // has *shrunk* by the block that was consumed. A client-predicted
        // placement the server rejects never moves the item count.
        if (targetDone) {
            if (completionSequence < 0) {
                // First tick the target looks finished, so the prediction that
                // finished it is this sequence or an earlier one. Waiting for
                // this one settles ours too: the ack retires everything up to
                // the sequence it names.
                completionSequence = ServerBlockSync.currentSequence(client.level);
                firstDoneTick = phaseTicks;
            }
            stateConfirmTicks++;
        } else {
            // The target standing again after it had looked finished is the
            // server refusing the prediction — the ack reverted it. Counted
            // because it is the one signal that separates "the server said no"
            // from "the server has not answered yet".
            if (stateConfirmTicks > 0) {
                stateReverts++;
            }
            completionSequence = -1;
            stateConfirmTicks = 0;
        }
        boolean artifact;
        if (placing) {
            // Creative never consumes the block, so the item count can't be
            // the signal there — the sustained-state window alone confirms it.
            artifact = player.getAbilities().instabuild
                    || countMainInventory(player) < startInventoryCount;
        } else {
            if (!dropSeenThisBreak) {
                net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                        target.getX() - 2, target.getY() - 2, target.getZ() - 2,
                        target.getX() + 3, target.getY() + 3, target.getZ() + 3);
                dropSeenThisBreak = !client.level.getEntities(
                        net.minecraft.world.entity.EntityType.ITEM, box, e -> true).isEmpty();
            }
            artifact = dropSeenThisBreak || countMainInventory(player) > startInventoryCount;
        }
        // The server settling the prediction is the real answer, and it beats
        // the window below by roughly the whole window: a client that has the
        // ack knows the block is gone, where the sustained-state count is only
        // waiting to become confident about a guess. The window and its
        // artifact stay as the fallback for a connection that never acks —
        // slower, but the bot keeps working instead of burning maxBreakTicks.
        boolean serverSettled = ServerBlockSync.isSettled(completionSequence);
        if (serverSettled || (stateConfirmTicks >= CONFIG.airConfirmTicks && artifact)) {
            // Not a plain stop: the break is done but vanilla's five-tick
            // post-break delay is not, and it only runs down while something
            // keeps driving the game mode. Draining it through the aim is what
            // a player holding the button gets for free.
            BlockInteractor.releaseAfterBreak();
            // A step from opportunistic collection must not survive the phase
            // change — transitionTo does not touch the movement keys.
            releaseMovementKeys();

            // TEMPORARY (multiplayer break investigation, remove with the fix).
            // One line per interaction, unconditional so a run on a server
            // needs no configuration. exit=ack means the server settled the
            // prediction; exit=window means the bot proceeded on the sustained
            // state plus an artifact, with nothing from the server.
            LOGGER.warn("BREAKWIRE {} {} ticks={} firstDone={} reverts={} seq={} acked={}"
                    + " exit={} drop={} invDelta={} dist={} held={}",
                    placing ? "place" : "break", target.toShortString(), phaseTicks,
                    firstDoneTick, stateReverts, completionSequence, ServerBlockSync.lastAcked(),
                    serverSettled ? "ack" : "window", dropSeenThisBreak,
                    countMainInventory(player) - startInventoryCount,
                    String.format("%.2f", distanceToTarget(player, target)),
                    player.getMainHandItem());

            if (CONFIG.debugEnabled) {
                LOGGER.info("{} at {} in {} ticks", placing ? "Block placed" : "Block broken",
                        target, phaseTicks);
            }

            // stateConfirmTicks resets on re-entry via the phaseTicks==1 branch above.
            //
            // Reaction beat between "the block broke" and the next phase —
            // a human glances at the result for a moment before moving on.
            // For sub-targets and same-reach next-targets we re-enter LOOKING;
            // otherwise we COLLECT drops before walking.

            // Sub-targets remaining in same task (e.g. tree logs) — mine next next
            if (currentTask.advanceToNextTarget()) {
                lookRetryUsed = false;
                continueSeam();
                return;
            }

            // A placement drops nothing, so there is nothing to collect —
            // take the next task straight after the reaction beat.
            if (placing) {
                scheduleAction(BotController::completeCurrentTask);
                return;
            }

            // Task fully done — decide what to do next based on the queue.
            // The "next" task is always the one nearest to the player, not
            // the queue head (see startNextTask).
            lastMinedPos = target;
            BotTask nextTask = peekNextTask(player);
            if (nextTask != null && isWithinReach(player, nextTask.targetPos())) {
                // Next target is within reach — mine it next, no beat
                currentTask = pollNextTask(player);
                taskTotalTicks = 0;
                lookRetryUsed = false;
                continueSeam();
            } else {
                // Need to walk (or nothing left) — collect drops first.
                // Don't delay here: COLLECTING begins immediately so the bot
                // starts pursuing drops while they're still falling.
                itemsSeenThisCollect = false;
                lastItemSeenTick = 0;
                transitionTo(Phase.COLLECTING);
            }
        }
    }

    /**
     * Walk toward a drop while the break continues, without letting the camera
     * or the break itself suffer for it. A human mining a corridor scoops up
     * what fell next to them mid-swing rather than stopping, walking over and
     * coming back — with the drop in the corner of the eye, so it comes out as
     * a strafe rather than a turn.
     * <p>
     * The camera is committed to the mining target by the caller, so the walk
     * direction is expressed in the 8 view-relative key directions (the same
     * quantization {@code PathWalker.applyCounterBrake} uses to brake without
     * turning). All four horizontal keys are driven every tick so a step
     * decided last tick cannot leak into a tick that decided against moving.
     * <p>
     * Three things have to hold before a foot moves, because a break in
     * progress is worth more than one dropped item:
     * <ol>
     *   <li>the step must keep the target inside reach and still leave a clear
     *       line to the face being mined — otherwise the server rejects the
     *       break packets and the task times out;
     *   <li>the destination must be a cell the bot can stand in, floor
     *       included: stepping into the hole it just dug is exactly the fall
     *       the bot is supposed to avoid;
     *   <li>the drop must be close enough to reach within the break
     *       ({@code OPPORTUNISTIC_ITEM_RANGE}) and not already inside the
     *       vanilla pickup radius, which collects it without moving at all.
     * </ol>
     * COLLECTING still runs afterwards and picks up whatever this declined.
     */
    private static void tickOpportunisticCollection(Minecraft client, LocalPlayer player,
                                                    BlockPos target) {
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        var box = player.getBoundingBox().inflate(OPPORTUNISTIC_ITEM_RANGE);
        for (var item : client.level.getEntities(
                net.minecraft.world.entity.EntityType.ITEM, box, e -> true)) {
            double dx = item.getX() - player.getX();
            double dy = item.getY() - player.getY();
            double dz = item.getZ() - player.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;
            // Already inside the pickup radius: vanilla will inhale it, and
            // walking further would only overshoot.
            if (distSq <= OPPORTUNISTIC_STOP_DISTANCE * OPPORTUNISTIC_STOP_DISTANCE) {
                continue;
            }
            if (distSq > OPPORTUNISTIC_ITEM_RANGE * OPPORTUNISTIC_ITEM_RANGE) {
                continue;
            }
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = item;
            }
        }
        if (nearest == null) {
            releaseMovementKeys();
            return;
        }

        // Quantize the direction to the drop into the 8 key directions,
        // relative to the view the camera is holding on the mined block.
        float moveYaw = (float) Math.toDegrees(
                Math.atan2(-(nearest.getX() - player.getX()), nearest.getZ() - player.getZ()));
        float rel = AngleUtil.wrapDegrees(moveYaw - player.getYRot());
        float a = Math.abs(rel);
        boolean forward = a <= 67.5f;
        boolean backward = a >= 112.5f;
        int strafeDir = (a > 22.5f && a < 157.5f) ? (rel > 0 ? 1 : -1) : 0;

        // Where that key combination actually pushes the body, which is the
        // quantized direction — not the direction of the item. Forward for yaw
        // θ is (−sin θ, cos θ); the player's right, which strafeDir > 0 means,
        // is that turned 90° clockwise: (−cos θ, −sin θ).
        double yawRad = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double pushX = (forward ? fx : backward ? -fx : 0.0) - strafeDir * fz;
        double pushZ = (forward ? fz : backward ? -fz : 0.0) + strafeDir * fx;
        double pushLen = Math.sqrt(pushX * pushX + pushZ * pushZ);
        if (pushLen < 1.0e-6) {
            releaseMovementKeys();
            return;
        }
        double stepX = player.getX() + pushX / pushLen * STEP_LOOKAHEAD;
        double stepZ = player.getZ() + pushZ / pushLen * STEP_LOOKAHEAD;

        if (!isStepSafe(client, player, target, stepX, stepZ)) {
            releaseMovementKeys();
            return;
        }

        client.options.keyUp.setDown(forward);
        client.options.keyDown.setDown(backward);
        client.options.keyLeft.setDown(strafeDir < 0);
        client.options.keyRight.setDown(strafeDir > 0);
        client.options.keySprint.setDown(false);
    }

    /**
     * Shift one axis of the aim offset toward the next target. Axes along the
     * face normal stay untouched (that would leave the face plane), and the
     * result is clamped so the aim point keeps a margin to the rim.
     */
    private static double biasTowardNext(double offset, int faceStep, int delta) {
        if (faceStep != 0 || delta == 0) {
            return offset;
        }
        double biased = offset + Math.signum(delta) * AIM_LOOKAHEAD_BIAS;
        double limit = 0.5 - AIM_EDGE_MARGIN;
        return Math.max(-limit, Math.min(limit, biased));
    }

    /**
     * Whether a step to {@code (stepX, stepZ)} keeps the bot able to finish the
     * break and lands it somewhere it can stand.
     */
    private static boolean isStepSafe(Minecraft client, LocalPlayer player, BlockPos target,
                                      double stepX, double stepZ) {
        double dx = stepX - (target.getX() + 0.5);
        double dy = player.getY() - (target.getY() + 0.5);
        double dz = stepZ - (target.getZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz > CONFIG.reachDistance * CONFIG.reachDistance) {
            return false;
        }

        // Line of sight from where the eye would be. The break packets are
        // reach- and visibility-checked server-side; stepping behind a pillar
        // stalls the task until maxBreakTicks with no visible cause.
        Vec3 eye = new Vec3(stepX, player.getEyeY(), stepZ);
        Vec3 aim = Vec3.atCenterOf(target);
        BlockHitResult clip = client.level.clip(new ClipContext(
                eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (clip.getType() != HitResult.Type.BLOCK || !clip.getBlockPos().equals(target)) {
            return false;
        }

        BlockPos feet = BlockPos.containing(stepX, player.getY(), stepZ);
        return isStandable(client.level, feet);
    }

    /**
     * Whether the bot can stand at {@code feet}: a sturdy floor under it and
     * two fluid-free, collision-free cells for the body. No drop is tolerated
     * at all — this is a single sideways step during a break, and even a
     * one-block fall pulls the target out of the aim the camera is holding.
     */
    private static boolean isStandable(Level level, BlockPos feet) {
        BlockPos below = feet.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) {
            return false;
        }
        for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
            var state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a body standing with its feet at {@code feet} would have ground
     * under it, allowing one block of drop. Deliberately weaker than
     * {@link #isStandable}: this answers "would the bot fall", which is the
     * only thing COLLECTING's raw-key walk has to avoid, and says nothing
     * about whether the cell is free to occupy.
     */
    private static boolean hasFloorWithinOneBlock(Level level, BlockPos feet) {
        for (BlockPos pos : new BlockPos[] {feet.below(), feet.below().below()}) {
            if (level.getBlockState(pos).isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)) {
                return true;
            }
        }
        return false;
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
                collectNearestId = -1;
                collectBestDistSq = Double.MAX_VALUE;
                collectNoProgressTicks = 0;
            }

            // Find nearby item entities within 8 blocks
            net.minecraft.world.phys.AABB searchBox = player.getBoundingBox().inflate(8.0);
            var items = client.level.getEntities(
                    net.minecraft.world.entity.EntityType.ITEM, searchBox, e -> true);

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

            // Same stall, horizontal geometry: a drop can land behind a block
            // the bot is not going to mine (a blacklisted block, bedrock, the
            // far side of a dammed liquid). It is inside the AABB and inside
            // the walk filter, but outside the vanilla pickup box and
            // walled off, so walkToward pushes into the obstruction and the
            // distance never shrinks. Measured: six of fourteen collects in the
            // chunk suite burned the full collectWaitMax that way, all of them
            // a drop two blocks out with one standing block in between.
            // COLLECTING deliberately does not pathfind, so the answer is to
            // notice the lack of progress and leave it: itemAbsenceTicks of
            // walking without getting a single 0.05 closer is nothing a
            // reachable drop looks like.
            if (nearest == null || nearest.getId() != collectNearestId) {
                collectNearestId = nearest == null ? -1 : nearest.getId();
                collectBestDistSq = nearestDistSq;
                collectNoProgressTicks = 0;
            } else if (nearestDistSq < collectBestDistSq - 0.05) {
                collectBestDistSq = nearestDistSq;
                collectNoProgressTicks = 0;
            } else {
                collectNoProgressTicks++;
            }
            // Dropping it here rather than only in the exit gate matters: the
            // walk below would otherwise keep pushing into the obstruction all
            // the way through the exit tick, leaving the movement keys down.
            if (collectNoProgressTicks > CONFIG.itemAbsenceTicks) {
                nearest = null;
            }

            // Presence is measured on the same set the walk above uses. An
            // item the loop rejected (>4 blocks up or down, or given up on as
            // unreachable) is one the bot has decided it will never approach —
            // counting it as "nearby" pins itemsNearby true forever, the exit
            // gate can never fire, and the phase burns the full collectWaitMax.
            //
            // This used to hold only for behaviors that opted in via
            // fastCollectExit, and everything else kept the unfiltered test.
            // That left the stall in place for plain /bot tasks, where it is
            // just as wrong and, at 1200 ticks, reads as the bot having quit:
            // it stands still, mines nothing, and eventually carries on. The
            // give-up needs no opt-in — sixty ticks of walking without getting
            // any closer means the item is not reachable from here, whoever
            // queued the task.
            itemsNearby = nearest != null;
            if (itemsNearby) {
                itemsSeenThisCollect = true;
                lastItemSeenTick = phaseTicks;
            }

            // Other half of the same stall: when the bot stands on top of the
            // block, vanilla pickup can inhale the drop before any tick
            // observes it, so itemsSeenThisCollect — which the exit gate
            // requires — never becomes true. Inventory growth since the break
            // started is the same server-authoritative proof the break gate
            // already accepts, and it also covers a pickup during INTERACTING.
            if (policy.fastCollectExit() && !itemsSeenThisCollect
                    && countMainInventory(player) > startInventoryCount) {
                itemsSeenThisCollect = true;
            }

            // Vanilla pickup is a box, not a radius: Player.touch queries
            // getBoundingBox().inflate(1.0, 0.5, 1.0) and takes every item
            // whose own box intersects it — with a 0.6-wide player and a
            // 0.25-wide item that is 1.425 centre-to-centre on each horizontal
            // axis. Stopping at 1.5 parked the bot *outside* it on a
            // straight-ahead approach: whether the drop got picked up came
            // down to how far the walk's momentum carried past the threshold,
            // and when it didn't, the bot stood over an item it could not
            // reach for the whole collectWaitMax (the ore-vein test leaving
            // all three drops on the ground). One block is inside the box on
            // every axis with room for the server seeing the walk a tick
            // later than the client does.
            if (nearest != null && nearestDistSq > 1.0) {
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
        // walk path get picked up by the vanilla pickup box. This eliminates the
        // visible "pause after pickup" that made multi-block flows feel choppy.
        //
        // A behavior that plans one block at a time never has a task queued at
        // this point — it only plans the next one once the controller is idle,
        // so the queue is empty on every exit and the window above is paid for
        // every single block. Measured on the chunk miner: ~20 ticks of actual
        // work per block against ~90 elapsed, the difference being almost
        // entirely this wait with the drop already in the inventory. Behaviors
        // that opted into fastCollectExit therefore skip the window outright;
        // the straggler argument is the same one the queued-walk path already
        // makes, and the next block is an adjacent column the bot is standing
        // on by then.
        //
        // There is deliberately no second, earlier exit that hands the walk off
        // to the next break. One was tried: with a task queued it left
        // COLLECTING outright, on the promise that opportunisticCollection
        // would step onto the drop while mining the next block. That promise
        // only holds for a drop the next break can actually reach — the
        // opportunistic step is a nudge, not a walk, gated on reach, line of
        // sight and a safe destination, and instrumenting the corridor showed
        // it closing 1.9 blocks to 1.6 over an entire break, barely inside the
        // 1.425 pickup box. When the nudge fell short nothing ever came back
        // for the drop: the bot mined on down the corridor and the cobblestone
        // stayed on the ground. It also bought nothing, because the case it
        // was meant to speed up is the one doneCollecting already leaves on
        // tick 1 — vanilla pickup inhales the drop during the break, so the
        // inventory has grown and no item is left nearby.
        boolean queued = taskQueue.peek() != null;
        boolean doneCollecting = itemsSeenThisCollect && !itemsNearby
                && (queued || policy.fastCollectExit()
                        || phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks);
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
            transitionTo(Phase.IDLE);
            // startNextTask already skips SCANNING for a target in reach —
            // tickCollecting walks toward the next task while items settle, so
            // by the time we exit the bot may be standing on it, and the
            // human-like "look at the next target before moving" cue would be
            // cueing a walk that isn't happening.
            if (!taskQueue.isEmpty() && !paused) {
                startNextTask();
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

        // Refuse a step into thin air. This drives the body forward —
        // sprinting, once the drop is more than two blocks out — on the gaze
        // direction alone, and a drop lies wherever it rolled: over the lip of
        // the shaft the bot just dug as readily as on the floor in front of
        // it. Nothing else stops it, because COLLECTING has no pathfinder
        // under it; it is the one phase that walks on raw key presses. That is
        // how the bot sprinted off its own platform and died of the fall.
        //
        // Only the floor is tested, not whether the cell is standable: walking
        // into a wall costs nothing (the bot simply does not move), and
        // requiring head room made it refuse to collect a drop lying against
        // the face it had just mined — it then stood still for the whole
        // collect window and the single-block test timed out. One block down
        // is a normal step and stays allowed; deeper is the hole, and a drop
        // down there is worth less than the run.
        double yawRad = Math.toRadians(player.getYRot());
        BlockPos ahead = BlockPos.containing(
                player.getX() - Math.sin(yawRad) * STEP_LOOKAHEAD,
                player.getY(),
                player.getZ() + Math.cos(yawRad) * STEP_LOOKAHEAD);
        if (!hasFloorWithinOneBlock(client.level, ahead)) {
            releaseMovementKeys();
            return;
        }

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

        // Just-out-of-reach targets need a step or two, not a pathfinding
        // ceremony: POSITIONING walks straight at the target while looking
        // at it and hands over to LOOKING the moment it's in reach — which
        // is exactly what a player does for the last couple of blocks.
        if (distanceToTarget(player, target) <= CONFIG.reachDistance + 2.5) {
            transitionTo(Phase.POSITIONING);
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

    /**
     * Go straight to the next block of a seam the bot is already working: the
     * next sub-target of this task, or a queued task whose block is in reach
     * from where the bot stands. No reaction beat, no fresh camera, and
     * LOOKING skips the pre-attack hesitation.
     *
     * <p>Those three exist to model a person noticing a result, deciding, and
     * committing — the beats between separate acts. Digging a corridor is one
     * act: the hand stays on the button and sweeps the crosshair over, and
     * pausing 4 ticks before every block of a 4096-block chunk is not what a
     * human doing repetitive work looks like, it is what a machine imitating
     * one looks like. The beats stay on every path that *is* a separate act —
     * after a walk, after a scan, after collecting. Both call sites here are
     * the ones the module doc has always described as "straight to LOOKING,
     * no pause"; the reaction delay had drifted in against that.
     */
    private static void continueSeam() {
        seamContinuation = true;
        transitionTo(Phase.LOOKING);
    }

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

    /**
     * The task to work next, without taking it. Nearest to where the bot
     * stands by default — a human works an area closest-first from wherever
     * they are, and replaying the queue's fixed scan order produces visible
     * zigzag routes. A behavior that has already decided the order says so
     * with {@link BotPolicy#orderedTasks()} and gets the queue's order back;
     * see that method for what nearest-first costs a sweep.
     */
    private static BotTask peekNextTask(LocalPlayer player) {
        return policy.orderedTasks() || player == null
                ? taskQueue.peek()
                : taskQueue.peekNearest(player.blockPosition());
    }

    /** {@link #peekNextTask}, and take it. */
    private static BotTask pollNextTask(LocalPlayer player) {
        return policy.orderedTasks() || player == null
                ? taskQueue.poll()
                : taskQueue.pollNearest(player.blockPosition());
    }

    private static void startNextTask() {
        LocalPlayer player = Minecraft.getInstance().player;
        currentTask = pollNextTask(player);
        if (currentTask == null) {
            phase = Phase.IDLE;
            return;
        }

        taskTotalTicks = 0;
        lookRetryUsed = false;
        forceApproach = false;
        seamContinuation = false;
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
        lookRetryUsed = false;
        forceApproach = false;

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
        return distanceToTarget(player, target) <= CONFIG.reachDistance;
    }

    /**
     * Total item count across the 36 main inventory slots. Used as a break
     * artifact: a pickup between break start and now proves the server
     * completed a break even when the drop entity was inhaled before any
     * tick could observe it.
     */
    private static int countMainInventory(LocalPlayer player) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            count += player.getInventory().getItem(slot).getCount();
        }
        return count;
    }

    private static double distanceToTarget(LocalPlayer player, BlockPos target) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getEyeY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Find a walkable mesh node within reach of the target block to stand on.
     * Scored by walk distance PLUS horizontal node→target distance: by the
     * triangle inequality that prefers nodes ON the straight player→target
     * line. Picking purely the player-nearest node (the old rule) regularly
     * chose a node one block to the side, so the walk went straight at the
     * target and then visibly dog-legged sideways for the last two blocks.
     * A human walks the straight line and stops in front of the target.
     */
    private static MeshNode findStandoffNode(LocalPlayer player, BlockPos target) {
        HashMap<ChunkCoordinate, Mesh> meshesForPlayer = MeshManager.meshes.get(player);
        if (meshesForPlayer == null) {
            return null;
        }

        MeshNode best = null;
        double bestScore = Double.MAX_VALUE;
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

                    double pdx = (node.getX() + 0.5) - player.getX();
                    double pdz = (node.getZ() + 0.5) - player.getZ();
                    double distToPlayer = Math.sqrt(pdx * pdx + pdz * pdz);
                    double horizToTarget = Math.sqrt(ndx * ndx + ndz * ndz);
                    // Slight walk-distance bias: every node on the straight
                    // line has the same sum, so without it the winner among
                    // line nodes would be hash-order — with it, it's the
                    // first in-reach node on the line (a human stops as soon
                    // as they're close enough).
                    double score = 1.05 * distToPlayer + horizToTarget;
                    if (score < bestScore) {
                        bestScore = score;
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

package net.stracciatella.camera;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.player.LocalPlayer;

/**
 * Controls camera yaw and pitch with human-like smoothing.
 *
 * <p>Both yaw and pitch use spring-damper physics. Yaw is critically damped for
 * responsive turns; pitch uses gentler constants so vertical tracking stays
 * calm relative to horizontal turns.
 *
 * <p>Optional humanness features:
 * <ul>
 *   <li>{@link #setLookSpeedMultiplier(double)} — scales spring acceleration
 *       per target so successive aims have varying turn speeds.
 *   <li>{@link #setMicroSaccadesEnabled(boolean)} — once aim has settled,
 *       inject tiny per-tick target perturbations to mimic involuntary gaze
 *       corrections during sustained aim.
 * </ul>
 *
 * <p>Instance-based so different systems can maintain independent camera state.
 */
public class CameraController {

    // Spring physics for yaw rotation (critically damped at default multiplier=1.0)
    private static final float TURN_ACCEL = 0.8f;
    private static final float TURN_FRICTION = 0.8f;

    // Spring physics for pitch rotation (gentler — vertical tracking should feel calmer)
    private static final float PITCH_ACCEL = 0.5f;
    private static final float PITCH_FRICTION = 0.7f;
    // Snap window: when target is within this many degrees and velocity is small,
    // stop computing. Avoids endless micro-oscillation around the target.
    private static final float PITCH_SNAP_DEG = 0.5f;
    private static final float PITCH_SNAP_VEL = 0.05f;

    // Micro-saccade parameters (only applied when enabled)
    private static final float SACCADE_YAW_MAX_DEG = 0.5f;
    private static final float SACCADE_PITCH_MAX_DEG = 0.3f;
    private static final int SACCADE_REFRESH_MEAN_TICKS = 12;
    private static final int SACCADE_REFRESH_SIGMA_TICKS = 4;
    private static final int SACCADE_REFRESH_MIN_TICKS = 6;
    private static final int SACCADE_REFRESH_MAX_TICKS = 22;

    // Facing tolerance: how close yaw must be to target before a jump fires
    public static final float JUMP_FACING_TOLERANCE_DEG = 18.0f;
    // Extra tolerance added for gap >= 3 jumps (total = JUMP_FACING_TOLERANCE + EXTRA)
    public static final float JUMP_FACING_EXTRA_GAP_DEG = 18.0f;

    // Turn behavior thresholds
    public static final float WALK_TURN_THRESHOLD_DEG = 25.0f;
    public static final float SHARP_TURN_DEG = 60.0f;
    public static final float TURN_STOP_THRESHOLD_DEG = 12.0f;
    public static final float WALK_TURN_MAX_DEG = 75.0f;

    // Random yaw offset range during jump preparation
    public static final float JUMP_AIM_YAW_MIN_DEG = 1.5f;
    public static final float JUMP_AIM_YAW_MAX_DEG = 4.0f;

    private float yaw;
    private float yawVelocity;
    private float yawAccel;
    private float pitch;
    private float pitchVelocity;

    // Humanness state
    private double lookSpeedMultiplier = 1.0;
    private boolean microSaccadesEnabled = false;
    private float saccadeYawOffset = 0.0f;
    private float saccadePitchOffset = 0.0f;
    private int saccadeRefreshCountdown = 0;

    /**
     * Sets the initial yaw and pitch, zeroing all velocity and acceleration.
     * Call this when starting camera control (e.g. at the beginning of a path walk).
     * Also resets the humanness multiplier and saccade state to defaults so a
     * fresh controller doesn't inherit stale settings.
     */
    public void initialize(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.yawVelocity = 0.0f;
        this.yawAccel = 0.0f;
        this.pitchVelocity = 0.0f;
        this.lookSpeedMultiplier = 1.0;
        this.microSaccadesEnabled = false;
        this.saccadeYawOffset = 0.0f;
        this.saccadePitchOffset = 0.0f;
        this.saccadeRefreshCountdown = 0;
    }

    /**
     * Scales the spring acceleration of both yaw and pitch by {@code m}.
     * Friction is held constant — multipliers > 1 introduce mild overshoot,
     * multipliers < 1 produce slower convergence; both feel human. Default 1.0
     * is critically damped (no overshoot, fastest stable convergence).
     *
     * <p>Callers typically reset this per new target (e.g. each block the bot
     * aims at).
     */
    public void setLookSpeedMultiplier(double m) {
        this.lookSpeedMultiplier = m;
    }

    /**
     * Enables or disables micro-saccades — small involuntary target perturbations
     * (±0.5° yaw, ±0.3° pitch) that refresh every ~12 ticks. Used during sustained
     * aim (e.g. while mining) to avoid the "perfectly frozen gaze" look. Disable
     * when fine pointing accuracy matters (jump-aim, navigation).
     */
    public void setMicroSaccadesEnabled(boolean enabled) {
        this.microSaccadesEnabled = enabled;
        if (!enabled) {
            this.saccadeYawOffset = 0.0f;
            this.saccadePitchOffset = 0.0f;
            this.saccadeRefreshCountdown = 0;
        }
    }

    /**
     * Updates yaw toward the target using spring-damper physics.
     * Returns the new yaw value.
     */
    public float updateYaw(float targetYaw) {
        float effectiveTarget = targetYaw + (microSaccadesEnabled ? saccadeYawOffset : 0.0f);
        float delta = AngleUtil.wrapDegrees(effectiveTarget - yaw);
        float accel = (float) (TURN_ACCEL * lookSpeedMultiplier);
        yawAccel = delta * accel - yawVelocity * TURN_FRICTION;
        yawVelocity += yawAccel;
        yaw += yawVelocity;
        return yaw;
    }

    /**
     * Updates pitch toward the target using spring-damper physics.
     * Snaps to target when within {@link #PITCH_SNAP_DEG} and velocity is small,
     * to avoid micro-oscillation noise.
     * Returns the new pitch value.
     */
    public float updatePitch(float targetPitch) {
        float effectiveTarget = targetPitch + (microSaccadesEnabled ? saccadePitchOffset : 0.0f);
        float delta = effectiveTarget - pitch;
        if (Math.abs(delta) < PITCH_SNAP_DEG && Math.abs(pitchVelocity) < PITCH_SNAP_VEL) {
            pitch = effectiveTarget;
            pitchVelocity = 0.0f;
        } else {
            float accel = (float) (PITCH_ACCEL * lookSpeedMultiplier);
            float pitchAccel = delta * accel - pitchVelocity * PITCH_FRICTION;
            pitchVelocity += pitchAccel;
            pitch += pitchVelocity;
        }
        // Clamp to valid Minecraft range
        if (pitch > 90.0f) {
            pitch = 90.0f;
            pitchVelocity = 0.0f;
        } else if (pitch < -90.0f) {
            pitch = -90.0f;
            pitchVelocity = 0.0f;
        }
        return pitch;
    }

    /**
     * Instantly sets yaw to the given value and zeroes velocity/acceleration.
     * Used for snapping camera direction (e.g. during landing brake).
     */
    public void snapYaw(float yaw) {
        this.yaw = yaw;
        this.yawVelocity = 0;
        this.yawAccel = 0;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getYawVelocity() {
        return yawVelocity;
    }

    /**
     * Smoothly aims the player's camera at the world-space point
     * {@code (tx + offsetX, ty + offsetY, tz + offsetZ)}. Updates the camera's
     * internal yaw/pitch state with one tick of smoothing and writes the result
     * to the player's rotation.
     *
     * <p>The offsets let callers add per-target jitter (e.g. aiming at a random
     * point inside a block face) without recomputing the base target.
     */
    public void aimAt(LocalPlayer player, double tx, double ty, double tz,
                      double offsetX, double offsetY, double offsetZ) {
        tickSaccades();
        float[] angles = computeTargetAngles(player, tx, ty, tz, offsetX, offsetY, offsetZ);
        float newYaw = updateYaw(angles[0]);
        float newPitch = updatePitch(angles[1]);
        player.setYRot(newYaw);
        player.setXRot(newPitch);
    }

    /**
     * Convenience overload for {@link #aimAt(LocalPlayer, double, double, double, double, double, double)}
     * with zero offsets.
     */
    public void aimAt(LocalPlayer player, double tx, double ty, double tz) {
        aimAt(player, tx, ty, tz, 0.0, 0.0, 0.0);
    }

    /**
     * Returns true when the smoothed yaw and pitch are both within
     * {@code toleranceDeg} of the angles required to look at the offset target.
     * Uses the camera's current (smoothed) state, so callers typically call this
     * after {@link #aimAt}.
     *
     * <p>The saccade offset is excluded from this check — saccades are noise on
     * top of a converged aim, not a moving target.
     */
    public boolean isAimedAt(LocalPlayer player, double tx, double ty, double tz,
                             double offsetX, double offsetY, double offsetZ,
                             float toleranceDeg) {
        float[] angles = computeTargetAngles(player, tx, ty, tz, offsetX, offsetY, offsetZ);
        return AngleUtil.isFacingTarget(yaw, angles[0], toleranceDeg)
                && Math.abs(pitch - angles[1]) < toleranceDeg;
    }

    /**
     * Convenience overload for {@link #isAimedAt(LocalPlayer, double, double, double, double, double, double, float)}
     * with zero offsets.
     */
    public boolean isAimedAt(LocalPlayer player, double tx, double ty, double tz, float toleranceDeg) {
        return isAimedAt(player, tx, ty, tz, 0.0, 0.0, 0.0, toleranceDeg);
    }

    /**
     * Advances saccade state by one tick. Picks a fresh offset when the refresh
     * counter expires. Called from {@link #aimAt} (the high-level entry point);
     * low-level {@link #updateYaw}/{@link #updatePitch} callers that want
     * saccades must call {@link #tickSaccades()} themselves each tick.
     */
    public void tickSaccades() {
        if (!microSaccadesEnabled) {
            return;
        }
        if (saccadeRefreshCountdown <= 0) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            // Symmetric uniform offsets — small enough that spring-damper absorbs
            // them without visible jerks.
            saccadeYawOffset = (float) (r.nextDouble(-SACCADE_YAW_MAX_DEG, SACCADE_YAW_MAX_DEG));
            saccadePitchOffset = (float) (r.nextDouble(-SACCADE_PITCH_MAX_DEG, SACCADE_PITCH_MAX_DEG));
            double g = r.nextGaussian() * SACCADE_REFRESH_SIGMA_TICKS + SACCADE_REFRESH_MEAN_TICKS;
            int next = (int) Math.round(g);
            if (next < SACCADE_REFRESH_MIN_TICKS) next = SACCADE_REFRESH_MIN_TICKS;
            if (next > SACCADE_REFRESH_MAX_TICKS) next = SACCADE_REFRESH_MAX_TICKS;
            saccadeRefreshCountdown = next;
        } else {
            saccadeRefreshCountdown--;
        }
    }

    private static float[] computeTargetAngles(LocalPlayer player,
                                               double tx, double ty, double tz,
                                               double offsetX, double offsetY, double offsetZ) {
        double ax = tx + offsetX;
        double ay = ty + offsetY;
        double az = tz + offsetZ;
        double dx = ax - player.getX();
        double dy = ay - player.getEyeY();
        double dz = az - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
        float targetPitch = (float) (-Math.atan2(dy, horizontalDist) * (180.0 / Math.PI));
        return new float[]{targetYaw, targetPitch};
    }
}

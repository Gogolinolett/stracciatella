package net.stracciatella.camera;

import net.minecraft.client.player.LocalPlayer;

/**
 * Controls camera yaw and pitch with human-like smoothing.
 *
 * <p>Yaw uses spring-damper physics (critically damped for responsive, non-oscillating turns).
 * Pitch uses exponential smoothing for gentle vertical tracking.
 *
 * <p>Instance-based so different systems can maintain independent camera state.
 */
public class CameraController {

    // Spring physics for yaw rotation
    private static final float TURN_ACCEL = 0.8f;
    private static final float TURN_FRICTION = 0.8f;

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

    /**
     * Sets the initial yaw and pitch, zeroing all velocity and acceleration.
     * Call this when starting camera control (e.g. at the beginning of a path walk).
     */
    public void initialize(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.yawVelocity = 0.0f;
        this.yawAccel = 0.0f;
    }

    /**
     * Updates yaw toward the target using spring-damper physics.
     * Returns the new yaw value.
     */
    public float updateYaw(float targetYaw) {
        float delta = AngleUtil.wrapDegrees(targetYaw - yaw);
        yawAccel = delta * TURN_ACCEL - yawVelocity * TURN_FRICTION;
        yawVelocity += yawAccel;
        yaw += yawVelocity;
        return yaw;
    }

    /**
     * Updates pitch toward the target using exponential smoothing.
     * Snaps to target when within 0.5 degrees to avoid micro-adjustments.
     * Returns the new pitch value.
     */
    public float updatePitch(float targetPitch) {
        float delta = targetPitch - pitch;
        if (Math.abs(delta) < 0.5f) {
            pitch = targetPitch;
            return pitch;
        }
        // Move a fraction of the distance each tick (exponential smoothing)
        pitch += delta * 0.15f;
        // Clamp to valid Minecraft range
        pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
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

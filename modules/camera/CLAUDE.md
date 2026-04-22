# Camera Module

Human-like camera movement for Minecraft automation. Provides smooth yaw/pitch control that avoids robotic snapping to targets.

## Architecture

```
net.stracciatella.camera
├── CameraModule.java       # Entry point (empty — pure utility module)
├── CameraController.java   # Instance-based yaw/pitch smoothing (spring physics + exponential smoothing)
└── AngleUtil.java           # Static angle utilities (wrapDegrees, isFacingTarget, computeDesiredPitch)
```

## Key Classes

### CameraController
- **Yaw**: Spring-damper physics (hardcoded `TURN_ACCEL=0.8`, `TURN_FRICTION=0.8`).
- **Pitch**: Exponential smoothing (15% per tick), snaps within 0.5 degrees.
- Instance-based — each consumer creates its own controller.
- `initialize(yaw, pitch)` resets state. `snapYaw(yaw)` for instant direction changes.

#### APIs

Low-level (for callers that derive yaw/pitch externally, e.g. PathWalker feeding a velocity-derived yaw):
- `updateYaw(targetYaw)` / `updatePitch(targetPitch)` — tick the smoother toward the given angle, return new smoothed value.

High-level world-space aim (for callers aiming at a point, e.g. the bot):
- `aimAt(player, tx, ty, tz[, offsetX, offsetY, offsetZ])` — derives yaw/pitch from target point (plus optional jitter offset), applies one tick of smoothing, writes the result to the player's rotation.
- `isAimedAt(player, tx, ty, tz[, offsetX, offsetY, offsetZ], toleranceDeg)` — true when the camera's current smoothed yaw/pitch are both within tolerance of the angles required to look at the (offset) target. Typically called after `aimAt` in the same tick.

### AngleUtil
- `wrapDegrees(float)` — normalize to [-180, 180)
- `isFacingTarget(float currentYaw, float targetYaw, float toleranceDeg)` — tolerance-based facing check
- `computeDesiredPitch(double dy, double distance)` — pitch from vertical delta, clamped to [-15, 15] degrees

## Dependencies

- `loader` (compileOnly) — for the `Module` interface

## Consumers

- **Pathfinding module** (`PathWalker.java`) — uses the low-level `updateYaw`/`updatePitch`/`snapYaw` APIs because its yaw targets are derived from movement physics (velocity yaw vs. path yaw, jump-facing tolerance), not from a single world-space point.
- **Bot module** (`BotController.java`) — uses the high-level `aimAt`/`isAimedAt` APIs in SCANNING/LOOKING/INTERACTING phases to aim at block centers plus per-target jitter offsets.

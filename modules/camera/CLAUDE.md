# Camera Module

Human-like camera movement for Minecraft automation. Provides smooth yaw/pitch control that avoids robotic snapping to targets.

## Architecture

```
net.stracciatella.camera
├── CameraModule.java       # Entry point (empty — pure utility module)
├── CameraController.java   # Instance-based yaw/pitch smoothing (spring physics)
└── AngleUtil.java           # Static angle utilities (wrapDegrees, isFacingTarget, computeDesiredPitch)
```

## Key Classes

### CameraController
- **Yaw**: Spring-damper physics (hardcoded `TURN_ACCEL=0.8`, `TURN_FRICTION=0.8`). Critically damped at default look-speed. Angular velocity is hard-capped at 35°/tick (scaled by the look-speed multiplier) — without the cap, the spring's first tick after a large target change covers `0.8 × delta`, so a 180° flip became a near-instant 144° snap.
- **Pitch**: Spring-damper physics with gentler constants (`PITCH_ACCEL=0.5`, `PITCH_FRICTION=0.7`). Slightly underdamped — mild overshoot is human-like. Snaps when within 0.5° **and** velocity below 0.05 to avoid micro-oscillation.
- Instance-based — each consumer creates its own controller.
- `initialize(yaw, pitch)` resets all state including look-speed and saccade settings.
- `snapYaw(yaw)` for instant direction changes.

#### Humanness controls

- `setLookSpeedMultiplier(double m)` — scales spring acceleration on **both** yaw and pitch by `m`. Friction stays constant, so `m > 1` introduces mild overshoot, `m < 1` slows convergence — both feel human. Default `1.0` is critically-damped yaw / mildly-underdamped pitch. Callers typically reset this per new target (e.g. each new block the bot aims at).
- `setMicroSaccadesEnabled(boolean)` — when enabled, the controller injects tiny target perturbations (±0.5° yaw, ±0.3° pitch) refreshed every 6–22 ticks (Gaussian, mean 12). Used during sustained aim (e.g. while mining) to avoid the "frozen gaze" look. Disabled when fine pointing accuracy matters (jump-aim, navigation). The saccade offset is **excluded** from `isAimedAt` checks — saccades are noise on top of a converged aim, not a moving target.
- `tickSaccades()` — advance saccade state by one tick. Automatically invoked by `aimAt`. Low-level (`updateYaw`/`updatePitch`) callers that want saccades must call this themselves once per tick.

#### APIs

Low-level (for callers that derive yaw/pitch externally, e.g. PathWalker feeding a velocity-derived yaw):
- `updateYaw(targetYaw)` / `updatePitch(targetPitch)` — tick the smoother toward the given angle, return new smoothed value.

High-level world-space aim (for callers aiming at a point, e.g. the bot):
- `aimAt(player, tx, ty, tz[, offsetX, offsetY, offsetZ])` — derives yaw/pitch from target point (plus optional jitter offset), applies one tick of smoothing (including any saccade), writes the result to the player's rotation.
- `isAimedAt(player, tx, ty, tz[, offsetX, offsetY, offsetZ], toleranceDeg)` — true when the camera's current smoothed yaw/pitch are both within tolerance of the **un-saccaded** angles required to look at the (offset) target. Typically called after `aimAt` in the same tick.

### AngleUtil
- `wrapDegrees(float)` — normalize to [-180, 180)
- `isFacingTarget(float currentYaw, float targetYaw, float toleranceDeg)` — tolerance-based facing check
- `computeDesiredPitch(double dy, double distance)` — pitch from vertical delta, clamped to [-15, 15] degrees

## Dependencies

- `loader` (compileOnly) — for the `Module` interface

## Consumers

- **Pathfinding module** (`PathWalker.java`) — uses the low-level `updateYaw`/`updatePitch` APIs because its yaw targets are derived from movement physics (velocity yaw vs. path yaw, jump-facing tolerance), not from a single world-space point. Keeps default `lookSpeedMultiplier = 1.0` and saccades disabled — its physics is tuned for the default constants. `snapYaw` is currently uncalled (the landing brake used to snap to velocity direction; it now counter-brakes with movement keys while turning smoothly).
- **Bot module** (`BotController.java`) — uses the high-level `aimAt`/`isAimedAt` APIs in SCANNING/LOOKING/INTERACTING phases to aim at block centers plus per-target jitter offsets. Sets a randomised `lookSpeedMultiplier` per new target so successive aims have varying turn speeds. Enables saccades during sustained aim (INTERACTING) and disables them when transitioning to a new target.

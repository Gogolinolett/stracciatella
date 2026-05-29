# Camera Module — Design Decisions

## aimAt / isAimedAt vs. forcing all consumers onto world-space API

**Decision**: Expose two API tiers on `CameraController` — low-level (`updateYaw`, `updatePitch`, `snapYaw`) and high-level (`aimAt(player, x, y, z)`, `isAimedAt(player, x, y, z, tol)`). Pathfinding stays on the low-level API; bot uses the high-level one.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Two-tier API (chosen) | Each consumer uses the tier that fits its data flow; no camera-module knowledge of movement physics | Slightly larger surface area |
| Only high-level `aimAt` | Smallest surface; forces all yaw/pitch through one path | PathWalker's yaw targets come from movement physics (velocity yaw during retreat, velocity-facing during landing brake, path-direction yaw otherwise), not a world point — forcing an `aimAt` would require pushing jump-decision / retreat / brake logic into the camera module, or splatting it back out via extra variants |
| Only low-level `updateYaw`/`updatePitch` | Clean separation | Duplicates the "derive yaw/pitch from world point + write to player" pattern across every consumer that aims at a block (bot had this in 3 phases) |

Chose the two-tier API because the duplication eliminated in the bot (3 phases × ~8 lines each) has a real cost, while forcing pathfinding onto `aimAt` would require the camera module to know about movement-physics concepts it should not.

## Camera physics hardcoded as constants

**Decision**: Spring physics parameters (`TURN_ACCEL=0.8`, `TURN_FRICTION=0.8`, `PITCH_ACCEL=0.5`, `PITCH_FRICTION=0.7`) are hardcoded constants in `CameraController`, not configurable at runtime.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Hardcoded constants | Simple, no config files, values are proven stable | Requires code change to tune |
| Config file (camera.json) | Tunable without recompile | Extra complexity, another config to manage |
| Passed from consumer config | Flexible per-consumer | Couples camera module to consumer's config |

Chose hardcoded constants because the values are well-tuned from extensive testing. Runtime tuning via config commands was removed to simplify the system. Per-target variation comes from the look-speed multiplier (see "Per-target look-speed multiplier") rather than tunable constants — multipliers are unit-less and easier to reason about than re-tuning the physics directly.

## Instance-based CameraController vs static utility

**Decision**: CameraController is instance-based (non-static).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Instance-based | Multiple independent camera controllers, testable, no shared state | Requires field to hold instance |
| Static (like PathWalker) | Simple access, no instantiation | Only one camera at a time, hard to test, couples consumers |

Chose instance-based because the goal is to support multiple automation systems (pathfinding, combat, mining) each with independent camera state.

## isFacingTarget tolerance as parameter vs config

**Decision**: `AngleUtil.isFacingTarget` takes tolerance as a parameter instead of reading from a config.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Tolerance as parameter | Flexible, no coupling to any config, reusable | Caller must pass value |
| Read from CameraController config | Encapsulated | Ties utility to controller instance, less reusable |
| Read from global config | Simple | Couples to specific config, unusable by other modules |

Chose parameter approach because different contexts need different tolerances (gap=2 uses 18 degrees, gap>=3 uses 36 degrees, step-ups use 45 degrees).

## Micro-saccades during sustained aim

**Decision**: `CameraController` exposes `setMicroSaccadesEnabled(boolean)`. When enabled, it injects tiny target perturbations (±0.5° yaw, ±0.3° pitch) refreshed every 6–22 ticks (Gaussian, mean 12). The perturbation is excluded from `isAimedAt` so saccades never break the convergence gate.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Per-tick offset inside controller (chosen) | Spring-damper absorbs the jitter naturally; one toggle per consumer; no caller code change | Adds saccade-state fields to the controller even when unused |
| Caller computes saccade and passes via offsets | Controller stays simple | Every consumer would duplicate the saccade state machine; saccades would also affect raycasts (offset target moves) when callers like the bot already pass jitter offsets for face aim |
| Random walk on `yaw` directly between updates | No target change needed | Fights the spring physics — every direct write resets velocity and causes a visible jerk |

Chose the per-tick offset inside the controller because the spring-damper *is* the right place to absorb noise: feeding a perturbed target produces a smoothly-tracked perturbation, while writing directly to `yaw` would always conflict with the spring. Excluding saccades from `isAimedAt` matters because the bot's hit-result gate already separately validates whether the raycast lands on the target — letting saccades make `isAimedAt` flicker would cause spurious gate reopens.

## Per-target look-speed multiplier

**Decision**: `CameraController.setLookSpeedMultiplier(double m)` scales the spring acceleration on **both** yaw and pitch. Friction is held constant. Default is `1.0` (critical damping on yaw).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Scale acceleration only (chosen) | Simple knob; `m > 1` introduces mild overshoot, `m < 1` slows convergence — both feel human; preserves the "soft" feel of the damper | Underdamping at high `m` could in theory oscillate, but the multiplier range (~0.7–1.3) keeps it well within stable bounds |
| Scale both acceleration and friction | Stays critically damped at every multiplier | Removes the variance of behaviour — every aim feels the same shape, only faster/slower. Loses the "sometimes the camera overshoots a hair" texture that reads as human |
| Replace constants per-target | Most flexibility | Couples consumers to physics; consumers would have to know which values are stable |

Chose scale-acceleration-only because the overshoot at `m > 1` is exactly the human-like artefact we want — a perfectly damped camera at every speed still feels like a machine, just at different speeds. Bot rolls `m` from `lookSpeedMin/Max` (default `0.7..1.3`) per target.

## Symmetric spring-damper pitch vs exponential decay

**Decision**: Both yaw and pitch use spring-damper physics. Pitch uses gentler constants (`PITCH_ACCEL=0.5`, `PITCH_FRICTION=0.7`) than yaw (`TURN_ACCEL=0.8`, `TURN_FRICTION=0.8`).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Spring-damper on both (chosen) | One physics model — same feel on both axes; supports look-speed multiplier and saccades uniformly; mild overshoot reads as human | Pitch can ring slightly more than the old exponential at large deltas |
| Exponential on both | Trivially stable, no oscillation | Loses momentum feel; can't overshoot, can't carry a saccade; asymmetric with yaw |
| Keep asymmetric (old: spring yaw, exponential pitch) | What we had | Different "feel" on each axis is visible — a viewer can tell yaw is heavier than pitch; multiplier/saccade plumbing would need duplicate code paths |

Chose symmetric spring-damper because (1) a single physics model simplifies the look-speed multiplier and saccade implementations to one branch instead of two, (2) the asymmetry was visible on diagonal jumps where pitch tracked instantly while yaw still swept, (3) PathWalker only consumes the smoothed pitch via `setXRot` (cosmetic) — its jump correctness depends solely on yaw, so a slightly different pitch profile cannot break path tests. The gentler pitch constants keep vertical tracking calm so the camera doesn't feel "jumpy" on every small terrain step.

# Camera Module — Design Decisions

## Instance-based CameraController vs static utility

**Decision**: CameraController is instance-based (non-static).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Instance-based | Multiple independent camera controllers, testable, no shared state | Requires field to hold instance |
| Static (like PathWalker) | Simple access, no instantiation | Only one camera at a time, hard to test, couples consumers |

Chose instance-based because the goal is to support multiple automation systems (pathfinding, combat, mining) each with independent camera state.

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

**Decision**: Spring physics parameters (`TURN_ACCEL=0.8`, `TURN_FRICTION=0.8`) are hardcoded constants in `CameraController`, not configurable at runtime.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Hardcoded constants | Simple, no config files, values are proven stable | Requires code change to tune |
| Config file (camera.json) | Tunable without recompile | Extra complexity, another config to manage |
| Passed from consumer config | Flexible per-consumer | Couples camera module to consumer's config |

Chose hardcoded constants because the values are well-tuned from extensive testing. Runtime tuning via config commands was removed to simplify the system.

## isFacingTarget tolerance as parameter vs config

**Decision**: `AngleUtil.isFacingTarget` takes tolerance as a parameter instead of reading from a config.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Tolerance as parameter | Flexible, no coupling to any config, reusable | Caller must pass value |
| Read from CameraController config | Encapsulated | Ties utility to controller instance, less reusable |
| Read from global config | Simple | Couples to specific config, unusable by other modules |

Chose parameter approach because different contexts need different tolerances (gap=2 uses 18 degrees, gap>=3 uses 36 degrees, step-ups use 45 degrees).

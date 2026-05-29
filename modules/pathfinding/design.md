# Pathfinding Module — Design Decisions

## A* heuristic scale factor

**Decision**: Use scale factor 9.0 for Euclidean heuristic.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Scale 10.0 (original) | Faster search, closer to actual cost | Inadmissible for diagonal moves (10*sqrt(2)=14.14 > 13), non-optimal paths |
| Scale 9.0 (chosen) | Admissible (9*sqrt(2)=12.73 < 13), guarantees optimal paths | Slightly more nodes explored |
| Octile distance | Perfectly tight admissible bound | More complex to implement, gains marginal |

Chose 9.0 because it's the largest integer scale that remains admissible given the cheapest diagonal move cost (10 base + 3 diagonal = 13). Simple change with guaranteed optimality.

## A* open-set management

**Decision**: Use lazy deletion with a closed set instead of open-set tracking.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Open-set tracker (original) | No duplicate records | Can't update priority for nodes already in queue; misses better paths |
| Lazy deletion + closed set (chosen) | Always uses best fCost; handles re-discovery correctly | Slightly larger priority queue from duplicates |
| Decrease-key with indexed PQ | Optimal queue size | Java PriorityQueue doesn't support decrease-key; requires custom PQ |

Chose lazy deletion because it fixes the correctness issue (stale priorities) with minimal code change. The closed set prevents reprocessing. Standard pattern for A* in Java.

## MeshNode equality semantics

**Decision**: Implement `equals()`/`hashCode()` based on `(x, y, z)` coordinates.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Reference identity (original) | No implementation needed | Breaks HashMap lookups if different MeshNode instances share coordinates; fragile assumption |
| Coordinate equality (chosen) | Correct HashMap behavior; safe to create MeshNode instances anywhere | Must ensure coordinates uniquely identify a node within a mesh |

Chose coordinate equality because MeshNode is used as a HashMap key in MeshPathfinder (gScore, cameFrom) and compared via `.equals()` in tests and path reconstruction. Reference identity worked by accident but is a latent bug.

## Diagonal reachability distance limits

**Decision**: Check drop distance (4.5 limit) before same-level/step-up distance (4.0 limit).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Check `dy >= -1` first (original) | Simpler order | `dy > 0` check at 4.5 was dead code — drops were capped at 4.0 |
| Check `dy > 0` first (chosen) | Both limits apply correctly: 4.5 for drops, 4.0 for flat/step-up | Slightly less obvious order |

Chose drops-first because it matches the original intent (drops should allow 4.5 diagonal distance). The more specific condition must be checked before the more general one.

## Mesh invalidation: full chunk regen, filtered

**Decision**: `LevelChunkMixin.setBlockState` invalidates the mesh for the chunk via `MeshManager.invalidateMesh(chunkCoord)` whenever the change flips air↔solid, the chunk's level is the client's, and at least one entity has a mesh for that chunk.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Empty mixin / no invalidation (original) | Simple, no perf cost | Mining a wall leaves the mesh thinking the wall is still solid. The next A* search finds a path that ignores the hole the bot just dug. PathWalker then drives the bot back through the no-longer-walkable section it already cleared. |
| Per-block slice update (compute which nodes are affected and edit just those) | Cheap per update | Tricky to get right: a single block change can affect not just the column it sits in but every neighbor's reachability (line-of-sight checks span ±5 blocks). Bug surface is high; full regen is correctness-first. |
| Full chunk regen on every block update, no filter | Trivially correct | Every redstone tick, every leaf decay, every waterlogged toggle would re-walk the entire 16×16 chunk. Tests would slow to a crawl. |
| Full chunk regen, filtered to air↔solid flips + active-mesh + client-side (chosen) | Three short conditions, all cheap to check, prune the expensive call to the cases where it actually matters. The air↔solid filter catches the cases that change walkability; non-mesh chunks pay nothing; the level filter prevents the double-fire in single-player. | Sub-state edits that DO affect walkability (e.g. stair direction change altering the upper face's slope) aren't currently detected. Not a problem in practice — bots don't navigate stairs as a special case yet. |
| Lazy "dirty" flag + regen on next access | Avoids work for chunks that aren't currently being queried | Adds a flag to track and a check on every `findOrBuildNearestNode` call. The current implementation's filters already prune enough to make the eager regen affordable. |

Chose filtered full regen because the filters reduce the firing rate by 100×+ (most `setBlockState` calls are sub-state edits or in chunks no bot is using). When it does fire, a 16×16 walk is fast enough — sub-millisecond on modern hardware — and the resulting mesh is guaranteed consistent with what the client sees. The lazy-flag option remains available if profiling later shows the eager regen is a bottleneck.

## Micro-strafing on safe corridors

**Decision**: PathWalker injects brief (1–2 tick) sideways key presses (left/right) on path segments where the current and next-two nodes are same-Y gap=1 (a "corridor"). Cooldown 40–80 ticks between strafes. Disabled on any non-corridor segment.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No strafing (state before this change) | Most predictable movement | Reads as bot: every walking step is on the perfect line between nodes, no lateral drift like a human's hand-on-keyboard imperfection |
| Continuous low-amplitude strafing on all segments | Most humanness | Single-block-platform jumps need precise alignment — a strafe in flight pushes the player off the landing block. Tests fail. |
| Strafe only on safe corridors, brief windows, with cooldown (chosen) | Drifts the bot off-line by ~0.1 blocks on corridors where the alignment-hold + spring camera absorb it; never touches jump-prep or in-air segments | Adds keyLeft/keyRight to applyMovement's surface; calls during retreat/brake must explicitly pass strafeDir=0 |
| Strafe via random target-offset perturbation | No new key state | The existing target offset (0.05–0.25 blocks) already provides micro-variance on the *target*; strafing instead varies the *input*, which is what reads as human "imperfect keyboard hand" |

Chose gated strafing because the "safe corridor" predicate (`isSafeCorridor`) reliably excludes the failure modes — jumps, sharp turns, retreats — while still firing on the segments where a viewer is most likely to notice the lack of drift. `applyMovement` now explicitly drives all six movement keys so any in-flight strafe is cleared whenever a non-strafing path (jump, brake, retreat) calls it with `strafeDir=0`.

## Pitch micro-variance during straight walks

**Decision**: While `straightWalkTicks > 20`, the bot is on the ground, not approaching a jump, and the target is > 1.5 blocks away, add a slowly-refreshing offset (±2.5° Gaussian, refreshed every 20–40 ticks) to the desired pitch passed into `CameraController.updatePitch`. Outside those conditions, the offset is zeroed so the next walk starts clean.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No pitch variance (state before this change) | Most predictable; pitch is always near horizontal | Frozen-gaze: the bot's vertical aim is identical every tick, every walk. Reads as bot |
| Continuous pitch noise | Most lively | When approaching a jump, pitch is part of the camera-derived facing target; noisy pitch makes the angular jump-tolerance check (`JUMP_FACING_TOLERANCE_DEG`) flutter and can delay a jump fire by 1–2 ticks. Manifests as occasional "stuck at the edge" |
| Gated low-frequency offset (chosen) | The 20-tick straight-walk gate guarantees the bot is well past any jump prep; the 1.5-block target distance gate excludes the precise approach. Offset is part of the *desired* pitch, so the spring-damper does the smoothing — output is a slow drift, not jitter | Offset must be zeroed on exit; failure to do so would leave the next jump approaching with a stale tilt |

Chose the gated offset because the pitch-variance behaviour is a "scanning the path" cue, which only makes sense during sustained walking. The straight-walk counter is also used by micro-strafing for the same reason — once the bot is committed to a long straight run, both humanness effects activate together.

## Pre-jump hesitation on max-range jumps

**Decision**: When entering the gap≥5 retreat branch from a standstill (`forwardVel ≤ 0.14`), pause for a Gaussian 4–12 tick window (mean 7, σ=2) before starting the retreat. Camera continues aiming at the target during the pause; no movement keys are pressed. If the bot enters with sprint-speed velocity (`forwardVel > 0.14` — meaning the standard skip-retreat branch applies), the hesitation is also skipped.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No hesitation (state before this change) | Fastest jump execution | Long jumps fire instantly when conditions align — reads as a script. A human pauses to "look at" a 4-air-block gap before committing |
| Hesitation on all jumps (gap ≥ 2) | Most consistent humanness | Adds 250–500 ms to every step-up and short jump. Chain-jumps over single-block platforms get visibly choppy; tests that rely on a continuous gap=2 chain regress |
| Hesitation on gap ≥ 5 only, gated to standstill (chosen) | Targets exactly the situation a viewer notices — the bot facing a wide gap and stopping to pre-aim. Mid-stride entries (already sprinting → skip retreat) don't pause, which preserves smooth gap-2 chains | Adds ~250–500 ms per max-range jump (gap ≥ 5 only) |
| Look-then-pause via camera-only state | Hesitation visible without halting movement | The retreat phase needs the bot still — entering retreat at speed risks falling off the back edge. Stopping is the *correct* mechanic regardless of humanness |

Chose the gap≥5 + standstill gate because (1) it matches the visual cue a human gives at a long jump, and (2) it's mechanically aligned with the retreat phase which already requires the bot to stop. The hesitation is realised in the same `if (maxJumpPhase == 0)` branch, sharing its state.

## EnderPearl test cooldown gating

**Decision**: `EnderPearlTests` waits on the client-side `ItemCooldowns` (synced from the server via `ClientboundCooldownPacket`) and then adds a 30-tick safety margin before calling `EnderPearlTravelMethod.start()`; the travel method itself does not check or clear the cooldown.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Fixed `waitTicks(N)` larger than 20 only | Simple | Non-deterministic under accelerated ticks; different `-PtickSpeed` values need different N. |
| Poll `!player.getCooldowns().isOnCooldown(stack)` in test setup + 30-tick safety margin (chosen) | Deterministic on the client side, authoritative (server-synced via packet), scales with any tick rate. Safety margin absorbs client/server tick-rate drift under accelerated ticks. | Tests must know to poll — couples test to cooldown mechanic |
| Check cooldown inside `EnderPearlTravelMethod.tickThrowing` | Encapsulated | Compensating wrapper around a test-harness ordering issue; the method should not silently delay callers who asked for a throw |

Chose the test-level poll because the travel method correctly fails when a throw is ignored; the test's setup phase is the right place to establish preconditions. The 30-tick safety margin handles client/server-tick drift: client's `ItemCooldowns` can clear ahead of the server's under `-PtickSpeed>1` when the frame rate outpaces `/tick rate`, and a client-side use press during that gap is silently rejected by the server.

## EnderPearlTravelMethod THROWING hold window

**Decision**: Hold `keyUse` for 20 accelerated ticks before releasing in `tickThrowing`.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Hold for 2 ticks (original) | Minimal | A single use is rejected if `rightClickDelay>0` from a prior use. |
| Hold for 6 ticks | Allows one retry after `rightClickDelay` expires | Still fails under accelerated ticks where client/server drift puts the first two attempts inside a lingering server-side cooldown window. |
| Hold for 20 ticks (chosen) | Gives 4–5 retry attempts, covers server-side cooldown expiring mid-hold under accelerated ticks | Very slightly wastes ticks when the throw succeeds on the first attempt |

Chose 20 because the server-side cooldown is 20 ticks and the client-side cooldown can clear up to that window before the server's at high `-PtickSpeed` values. Holding for 20 ticks guarantees at least one attempt hits a cooldown-clear server state. In practice only the first successful attempt throws a pearl (subsequent presses are silently absorbed by the server's fresh cooldown), so the hold doesn't multiply pearls.

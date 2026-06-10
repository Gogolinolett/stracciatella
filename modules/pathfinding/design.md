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

## Final-node arrival: stop where you land, don't re-center

**Decision**: The final path node counts as reached when the player is on the ground within `FINAL_ARRIVAL_RADIUS` (0.5) of the **true** block center — the per-node random target offset is excluded — and horizontal speed is at most `FINAL_ARRIVAL_MAX_SPEED` (0.12 b/t). Intermediate nodes keep the existing tight sphere (0.18, against the offset target) OR in-bounds box check.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Tight sphere only (0.18 vs. offset target, original) | Guarantees a centered stop, comfortably inside the tests' 0.6 arrival radius | The bot lands off-center (jumps always do; the walk target itself sits up to 0.25 off-center from the random offset) and then walks the last fraction of a block to the point. At tiny remaining distances the desired yaw flips sign whenever the player oversteps the point, and the overshoot/moving-away logic spins him back — the reported "turning around the block center after landing on the target block". A human stops where they land. |
| Box check for the final node too (bounds + 0.15 margin) | Simplest unification | Triggers up to 0.65 from center — outside the downstream 0.6 test radius, and standing half-off the block edge isn't a finished-looking stop either. |
| 0.5 true-center radius + low-speed gate (chosen) | Fires the moment the bot stands securely on the block — covering every normal landing and the offset walk target, so no re-centering walk ever starts. The speed gate keeps a sprint landing braking (normal brake logic, S-key) until the post-release friction slide stays inside the block; overshooting the center is now irrelevant because the whole 0.5 disc is "arrived" — the yaw-flip pirouette cannot occur. | The bot's final resting point varies by up to ~0.5 from center instead of being centered — which is exactly the human-looking outcome, and all downstream checks (tests 0.6, WalkTravelMethod 2 blocks, bot reach 4.0) tolerate it. |
| Velocity-projected arrival (predict resting point, stop early) | Theoretically earliest stop | Predicting the friction slide duplicates physics the brake logic already handles; the speed gate achieves the same with two constants. |

Chose the true-center disc + speed gate because the pirouette is purely a product of chasing a sub-0.25-block point: widen "arrived" to the area a human would consider "I'm on the block" and the pathological geometry disappears, while the speed gate preserves the only thing the tight stop was actually protecting — not sliding off the far edge.

**Companion change — final-node settling while the speed gate is closed**: the disc + gate alone did NOT fully remove the pirouette. Per-tick camera traces showed that during the 1–4 ticks between landing (speed 0.15–0.26) and the gate opening (≤ 0.12), the normal movement logic kept steering toward the walk target: standing next to the point, the desired yaw orbited it at ~7°/tick (`desiredYaw -150.7 → -143.5 → -136.5 → -125.7` over four ticks at constant ~0.2 speed) and the camera followed — the S-only `shouldBrake` path pushes opposite the *view* (which points at the target), so the sideways momentum component was never killed and the orbit persisted. The fix: when on the ground inside the arrival disc with the gate still closed, skip target steering entirely — hold the camera yaw (`updateYaw(getYaw())`, spring just settles) and run the same 8-way `applyCounterBrake` the landing brake uses, until the gate opens and arrival fires. Verified by trace: zero remaining ticks of target-steering inside the disc; the camera yaw stays constant through landing and stop.

## Landing brake: counter-brake with movement keys instead of snapping to velocity direction

**Decision**: During the active landing brake (speed > 0.1 b/t after a jump landing), the camera keeps turning smoothly toward the next path target (`camera.updateYaw`, no snap). The residual momentum is countered with movement keys: the momentum direction relative to the current view yaw is quantized to the 8 key directions (max 22.5° error) and the opposing combo is pressed — S when the momentum runs forward, A/D counter-strafe when it runs sideways, W when it runs backward, diagonals combined.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| `snapYaw(velocityYaw)` + press S (original) | Brake force exactly antiparallel to momentum — fastest possible deceleration | The camera visibly whips around to face the travel direction just to brake, then has to turn back to the next target. Reported as the bot "turning around to brake instead of just pressing S" — an instant yaw snap plus an unmotivated turn is a double bot-tell. |
| Keep view on target, always press S | Single-key simplicity, no snap | S pushes opposite the *view*, not opposite the *momentum*. After diagonal jumps or while mid-turn toward a sharp corner the momentum can run sideways to the view — S then barely decelerates (cos ≈ 0 at 90°) and adds a sideways push that can shove the player off a single-block platform. |
| 8-way key counter-brake, smooth view (chosen) | Reads exactly like a player braking (S or counter-strafe, eyes already on the next target). Worst-case key misalignment 22.5°, so ≥ 92% (cos 22.5°) of the push still decelerates; the camera is already converging on the next target when the brake ends. | Slightly weaker than a perfect antiparallel brake; up to sin 22.5° ≈ 38% of the push acts sideways for a few ticks. Verified against the single-block corner tests, which gate exactly this overshoot risk. |
| 4-way quantization (W/A/S/D only) | Simpler sector logic | Max misalignment 45°: only ~71% of the push decelerates and up to 71% acts sideways — measurably worse on single-block landings for no readability gain over the diagonals humans use anyway. |

Chose the 8-way counter-brake because the brake's purpose (kill momentum before the next segment) survives quantization, while the camera behavior — the only part the user actually sees — becomes indistinguishable from a player: gaze on the next target, fingers doing the braking.

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

## Desired-yaw freeze at sub-half-block target distance

**Decision**: `stableDesiredYaw(aimDx, aimDz)` returns the atan2 yaw toward the aim vector, but freezes at its last stable value while the horizontal distance is below 0.5 blocks. Used for the main movement desired-yaw and the landing-brake retarget; reset on `start()`.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Raw atan2 every tick (original) | No state | Within ~0.5 blocks of a node, stepping past the point flips the yaw target by up to 180° between ticks. The camera whips after every flip (with the velocity cap: a fast sweep; without: a snap). Happens at overflown intermediate nodes (airborne advance denied at sharp turns), during landing-brake slides toward a close node, and was the engine of the post-landing pirouette. |
| Freeze below 0.5 blocks (chosen) | The gaze keeps its last meaningful direction through the unstable zone; node advance or genuine separation resets it naturally. One float + one boolean of state. | While frozen, the gaze can be slightly stale if the player drifts sideways — irrelevant at sub-half-block range where any direction reads as "looking at the node". |
| Slerp/low-pass the desired yaw | Smooths flips too | Adds lag to *all* target changes including legitimate course corrections; the instability is strictly a near-field problem, so a distance gate is the precise tool. |

Chose the freeze because the yaw target is simply not meaningful information at sub-half-block range — any human looks "at the block underfoot" without re-aiming at its mathematical center point.

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

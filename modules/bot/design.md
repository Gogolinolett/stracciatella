# Bot Module — Design Decisions

## Camera behavior during reaction delays: ease out, don't freeze

**Decision**: While a deferred-action reaction delay is in flight, the bot's camera keeps easing toward its last aim point (`aimCameraAt` records the point; the deferred gate replays it each tick). Movement keys stay released, but the gaze finishes its swing and keeps saccading.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Freeze everything during the delay (original) | Simplest; the pause is clearly visible | The spring-damper camera can be mid-swing when the delay starts — SCANNING's 15° facing tolerance fires while the camera still has high angular velocity. Halting the turn for 1–10 ticks and then resuming reads as stop-motion, the opposite of the humanness the delay is for. |
| Ease toward last aim point during the delay (chosen) | The body pauses (movement released) while the gaze settles naturally — exactly what a human pausing between deciding and acting looks like. Saccades stay alive. | One extra static field set (`lastAim*`) on every camera-driving tick. |
| Only delay transitions where the camera has converged | No mid-swing freeze possible | Couples delay placement to camera state; SCANNING→NAVIGATING (tolerance 15°) is precisely the transition where the beat is most visible and most wanted. |

Chose easing because the reaction delay models a *decision* pause, not a motor freeze — humans stop walking while they think, but their eyes finish settling on the target.

## COLLECTING phase exit gate

**Decision**: Exit COLLECTING only when items have been observed and then absent for a sustained window — `itemsSeenThisCollect && !itemsNearby && phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks` (default 60) — and walk toward `lastMinedPos` while no items are visible yet.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Original `!itemsNearby && phaseTicks > 40` | Simple | Exits on the first tick where items haven't yet synced after block break. Fails `walkAndMine` where the bot stood several blocks from the drop. |
| Items-seen gate with single-tick absence (`!itemsNearby` once) | Simple | Can fire on a transient absence tick between picking up one drop and seeing the next, truncating collection when multiple drops exist. |
| Items-seen gate with sustained-absence window (`lastItemSeenTick + itemAbsenceTicks`, chosen) | Deterministic: exits only after drops have genuinely been gone for the configured window, tolerating transient client-side absence between pickups. Actively walks toward expected drop site before the first sighting. | Adds a small fixed wait after the last pickup before bot goes idle. |
| Poll player inventory for expected drop item | Most accurate pickup check | Tasks don't carry expected-drop metadata (stone→cobblestone, iron_ore→raw_iron). Would couple `BotTask` to drop tables. |

Chose the items-seen + sustained-absence gate because it closes three races deterministically: (1) the server→client spawn-sync delay after a block break, (2) the transient absence between sequentially picking up drops, and (3) missed drops when the bot is far from the mined block. No per-task metadata, all tick constants in accelerated units, exit self-contained. The earlier `phaseTicks > 40` floor was dropped: `itemsSeenThisCollect` already gates entry, so `lastItemSeenTick + itemAbsenceTicks >= 60 > 40` makes the floor unreachable.

## COLLECTING exit when more work is queued: skip the absence-tick wait

**Decision**: When `walkAfterCollect && taskQueue.peek() != null`, COLLECTING exits as soon as items are absent — bypassing the standard `itemAbsenceTicks` (60-tick) sustained-absence window. When no further task is queued, the original sustained-absence window applies.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Always wait `itemAbsenceTicks` (original) | Tolerates transient absence between sequentially-spawning drops | Adds a visible 60-tick pause after every block break in a multi-target flow. The bot just stands still after pickup before turning toward the next target. Reported as "kein flüssiger übergang zwischen aufgaben". |
| Walk toward the next task during COLLECTING (first attempt) | Removes the pause; bot keeps moving | The walk is a dumb horizontal pursuit, not PathWalker. Bot ends up bumping into the next target from the wrong angle (e.g. directly against the side of a tree trunk), and LOOKING then can't establish line-of-sight — chop-tree task fails with "Look timeout". Replacing `NAVIGATING`'s standoff logic with a direct walk is wrong. |
| Reduce `itemAbsenceTicks` globally | One-line change | Trades smoothness in multi-task flows against pickup reliability in single-task flows where multiple drops arrive in waves. The 60-tick wait is the right answer for the single-task case. |
| Skip the wait only when more work is queued (chosen) | Single condition, surgical: queued-task flows get the immediate transition, single-task flows keep their robustness. Straggler drops on the walk path are picked up by the 1.5-block vanilla radius during `NAVIGATING`. | The next task's `SCANNING → NAVIGATING` sequence does the smooth transition rather than continuing the walk; the visual still has the brief camera turn cue, which actually matches the human "look where you're going" pattern. |

Chose the conditional skip because the `itemAbsenceTicks` wait is solving a different problem (multi-drop pickup race) than the smoothness complaint addresses (queue-driven multi-task flow). One window can't satisfy both, and the queue gives a deterministic signal to switch.

**Companion change — direct-to-LOOKING when in reach after COLLECTING**: if the bot ends up within reach of the next task by the time COLLECTING exits (e.g. the next ore in a vein was right next to the one just mined), skip `SCANNING` and transition straight to `LOOKING`. The aim-cue is unnecessary when there's no movement to do.

## COLLECTING gaze: watch the drops, not the horizon

**Decision**: During COLLECTING the camera aims at what the bot is doing: the nearest item while walking to it (`walkToward` takes the full 3D target and drives `aimAt`), the item while standing in pickup range (unless it is nearly underfoot, horizontal dist² ≤ 0.5), `lastMinedPos` while waiting for the drop to sync, and the last aim point (still saccading) while waiting out the absence window. Micro-saccades are enabled for the whole phase.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Smooth yaw to walk direction only (original) | Steers the walk correctly | Pitch stays frozen at whatever INTERACTING left (eye-level at the mined block); the bot walks "through" its drops staring ahead, and stands dead-still with a locked gaze during the 60-tick absence window. Both read as bot. |
| Aim at the item / expected drop position (chosen) | The gaze tells the story a human's would: watch the drop, walk to it, watch it slide over. Yaw still steers the walk since the look target is the walk target. Saccades keep the gaze alive while standing. | Aiming at an item almost directly underfoot makes the yaw target unstable (tiny horizontal deltas flip it 180°) — guarded by skipping the look inside 0.5 horizontal dist² and easing toward the last aim point instead. |
| Look ahead to the next queued target while collecting | Saves a later camera turn | Wrong story: humans look at what they're picking up; the next-target glance belongs to SCANNING, which already does it. |

Chose item-following gaze because COLLECTING was the last phase where the camera was effectively unmanaged, and a frozen pitch over moving feet is one of the most recognizable bot tells.

## INTERACTING break confirmation: sustained air + drop-entity proof

**Decision**: INTERACTING requires two authoritative signals before transitioning out:
1. Sustained-air window of `CONFIG.airConfirmTicks` consecutive ticks (default 8).
2. A drop entity exists within a 5×5×5 AABB around the target.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Single-tick check `isCurrentTargetComplete(level)` (original) | Minimal | Under accelerated ticks the client's break prediction races ahead of the server's. Client shows air, bot transitions, but server rejects and restores the block — no drop spawns. |
| Sustained-air window only | Zero new infrastructure | Client-side sequenced-transaction prediction can keep the predicted-air state for a long time before the server's revert ack arrives. Under `tickSpeed=20`, observed `lastMinedPos state=iron_ore` at COLLECTING exit: the block had reverted on the client, proving the server never broke it — yet airConfirmTicks had fired. No fixed window size is safe. |
| Air + drop-entity (chosen) | The drop entity is authoritative: the server only spawns it after actually completing the break. If the client's air is a mere prediction the server later reverts, no drop entity ever appears and the bot keeps mining until `maxBreakTicks`. Works independently of tick-rate jitter. | Adds a per-tick AABB query once `airConfirmTicks` is satisfied (cheap, 5×5×5 region). For blocks that break with no drop (wrong-tool breaks), INTERACTING runs to `maxBreakTicks` and fails the task — correct behavior since no drop is useless anyway. |
| Listen for `ClientboundBlockUpdatePacket` via mixin | Authoritative | Requires packet-pipeline mixin. The drop-entity check uses an existing entity query and is equivalent signal-quality for our purposes. |
| Higher `/tick rate` on server to outpace client | Eliminates the race at the source | Can starve other server work; not all dev machines support it |

Chose "air + drop-entity" because it's the first approach that's **load-independent**. All the earlier attempts at tuning windows (3 → 8 → 16 → 32 → 48 ticks for `airConfirmTicks` or `toolSettleTicks`) passed in some runs and flaked in others depending on system load. The drop-entity check relies on an actual server-side artifact, not a timing assumption — the block broke iff the drop exists.

## LOOKING → INTERACTING gate: angular + hit-result

**Decision**: LOOKING transitions to INTERACTING only when both conditions hold:
1. `CameraController.isAimedAt(target, facingTolerance)` — camera vector within angular tolerance of target.
2. `mc.hitResult instanceof BlockHitResult` AND `((BlockHitResult) mc.hitResult).getBlockPos().equals(target)` — the client's raycast actually points at the target block.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Angular tolerance only (original) | Simple | When an obstacle sits in the line of sight, the camera can be aimed within `facingTolerance` (default 5°) of the target vector while the raycast lands on the obstacle. The bot then transitions to INTERACTING and visibly starts attacking the wrong block — even though `BlockInteractor` uses an explicit target position, the **visual feedback** to the user is that the bot is mining incorrectly until the camera converges further. Reported as "wird angefangen abzubauen bevor der richtige Block anvisiert wird". |
| Tighten `facingTolerance` to 1–2° | One-line change | Doesn't address obstacles in the line of sight. Also fights the human-like aim-offset (block-face jitter) which is intentionally up to 0.25 blocks — at typical reach (4 blocks) that's already ~3.5° of angular spread before the obstacle. |
| Angular + hit-result (chosen) | Two gates compose naturally: the angular check throttles unnecessary hit-result polling; the hit-result check is authoritative. If line of sight is permanently blocked the existing `lookTimeout` (40 ticks) fails the task cleanly without timing constants. | One additional check per LOOKING tick (cheap — `mc.hitResult` is already maintained by the renderer each frame). One-tick lag between `aimAt` updating yaw/pitch and `hitResult` reflecting the new aim, so the gate effectively delays the transition by ~1 tick — negligible. |
| Server-side reach check | Authoritative | The server already rejects out-of-reach or line-of-sight-blocked breaks, but the rejection is silent: bot sees client-side block prediction → air → `airConfirmTicks` may fire → server reverts. Until the revert, the bot has visibly attacked the wrong block. Doesn't address the user-reported visual symptom. |

Chose angular + hit-result because the hit-result gate is **directly the signal the user observes**: the bot only "looks at the right block" when `mc.hitResult` says so. This eliminates the visual misalignment without invasive changes to the interaction pipeline.

**Companion change — aim at the visible face, not the block center**: with hit-result gating in place, aiming at the block center became unworkable for blocks in the middle of a stack (e.g. mining a tree top-to-bottom). The center of the top log sits *behind* the lower log's near face from the bot's perspective; a raycast aimed at the center lands on the lower log and the hit-result gate locks the bot in LOOKING until `lookTimeout`. The fix is to aim at the center of the face most directly visible from the bot's eye, computed via `BlockInteractor.faceTowardPlayer` (which already picks the same dominant-axis face used in the destroy packet). The aim point shifts by ±0.5 along the face-normal axis, so the raycast clears intermediate blocks and lands on the target.

**Companion change — clamp aim jitter to the face plane**: human-aim offsets are still applied, but only on the two axes perpendicular to the face normal. Adding offset *along* the face normal pushes the aim point off the face plane; the raycast then exits the block's face range by the offset amount and just barely grazes a neighbor (or the platform below a single block). The hit-result gate sees the neighbor and the bot stares forever — reported as "the bot doesn't start mining even when it looks at the right block". Restricting the jitter to the in-face axes keeps the visual humanization while guaranteeing the aim ray crosses the target's face rather than skirting it.

**Companion change — no settle delay, no tool-settle**: once the hit-result gate fires, INTERACTING starts the same tick — there is no cosmetic settle countdown after aim. The tool-settle wait that used to live at the start of INTERACTING is also gone, replaced by selecting the tool during LOOKING. The carried-item packet (and any inventory-swap packet from B1) travels to the server in parallel with the smooth camera turn, so by the time aim is achieved the server has already had the entire LOOKING duration to apply the slot change. INTERACTING calls `resendCarriedItem` once on tick 1 as a dropped-packet safety net, then proceeds straight to `startDestroyBlock`. Reported feedback: "adjust → mine" instead of "adjust → start mining" — the transition now reads as one motion rather than two.

## Long-pause patterns: occasional breather at SCANNING→NAVIGATING

**Decision**: The reaction delay at the SCANNING→NAVIGATING transition is drawn via `HumanBehavior.randomTaskSwitchDelayTicks`: with probability `longPauseChance` (default 0.04) a uniform "breather" pause of `[longPauseMinTicks, longPauseMaxTicks]` (default 12–25 ticks) replaces the standard Gaussian reaction delay. Other transitions keep the standard delay.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No long pauses (original) | No test-budget impact | Every pause the bot makes is 1–10 ticks — over minutes of watching, the cadence is recognizably machine-constant. The module description has promised "pause patterns" since the start; only the per-transition Gaussian existed. |
| Long-pause chance on every scheduleAction | Most variety | Multiplies the flake risk in tick-budgeted tests (the within-reach vein flow alone rolls 2+ times per run) and a mid-vein stall reads less natural than a pre-walk one. |
| Long-pause chance only at SCANNING→NAVIGATING (chosen) | The "spotted it, about to walk over" moment is where humans genuinely idle (re-grip mouse, glance at inventory); walk-flow tests have the largest timeout budgets (200–280 ticks), so a rare extra ≤25 ticks fits. | The breather never occurs while standing in a vein — acceptable, the pre-attack hesitation and reaction delays already vary that cadence. |
| Idle fidgeting (random look-arounds while IDLE) | Strongest idle realism | IDLE means no task — out of scope for task execution humanness; can be layered later. |

Chose the single-transition application to get a visible cadence-breaker with bounded, testable timing risk. Defaults are deliberately conservative (4% × ≤25 ticks); both knobs are config-exposed.

## Nearest-task selection: route like a human, not like the scanner

**Decision**: Whenever the bot takes the next task from the queue (`startNextTask`, the within-reach continuation in `tickInteracting`, and the walk-flow exit in `tickCollecting`), it takes the task whose target is nearest to the player's current position (`TaskQueue.pollNearest`/`peekNearest`, ties keep insertion order) instead of the queue head. Explicit single commands still run in submission order (a single-element queue is unaffected).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| FIFO in scan order (original) | Deterministic, trivial | `BlockScanner` sorts by distance to the *scan center*, so equidistant targets on opposite sides interleave — the bot visibly zigzags across the area, which no human does. The order also ignores where the bot ends up after each task. |
| Re-sort the whole queue after every task | Same routing result | Mutates global order on every poll for no benefit over picking one element; harder to reason about with concurrent enqueues. |
| Greedy nearest-from-current-position (chosen) | Matches how a player works an area: finish here, do the closest thing next. O(n) scan per poll over a small queue. Ties preserve FIFO so single-task flows and equal-distance tests stay deterministic. | Greedy is not the globally optimal route — which is fine, humans aren't optimal either. |
| Nearest with random second-choice ("imperfect human") | Models human suboptimality | Deliberately re-introduces the zigzag the change removes; randomness in *route choice* (vs. timing/aim) reads as erratic, not human. |

Chose greedy nearest because the visible failure mode was routing, and nearest-from-here is both the simplest fix and the most human-plausible heuristic.

## Tool search scope: full main inventory with hotbar swap

**Decision**: `InventoryHelper.selectBestTool` scans all 36 main-inventory slots (hotbar + 27 storage). When the best tool sits in storage (`bestSlot ≥ 9`), it is swapped into the currently selected hotbar slot via `mc.gameMode.handleInventoryMouseClick(containerId, bestSlot, hotbarSlot, ClickType.SWAP, player)` before sending the carried-item packet.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Hotbar only (slots 0–8, original) | Minimal, one tight loop | Silently fails when the human-style "carry tools in a row" inventory has the pickaxe in storage. Bot mines with bare hand → no ore drop → task fails on `maxBreakTicks` timeout. Looked like a robustness bug rather than a setup issue. |
| Full inventory + SWAP packet (chosen) | Matches what a human player does: notice tool in storage, swap into hotbar, mine. Server-side hotbar held-slot ends up with the right tool. | One extra container-click packet per tool-select that needs swapping. The packet is small and idempotent. The SWAP relies on `inventoryMenu.containerId` which is always valid for the player's own inventory; no separate "open inventory" packet needed. |
| Move tool via direct `items.set(...)` | No packet | Client-only — server's inventory diverges, the swapped item appears server-side in the original slot, mining resolves against wrong tool. |
| Force-give the tool to hotbar each tick | Robust if creative | Doesn't work in survival; alters inventory state outside the test's expectations. |

Chose full-inventory + SWAP because it's the cheapest correct answer: one packet that the server natively understands, no inventory-state tricks, no creative-only assumptions. Slots 36–40 (armor + offhand) are intentionally skipped — they're not tool slots in any practical sense. The chosen hotbar destination is the currently-selected slot, so after the swap the carried-item packet doesn't need a slot change.

## Pre-attack hesitation vs removed settle delay

**Decision**: After both LOOKING gates fire (angular + hit-result) but before the first `startAttack` call, hold for a short uniform `preAttackHesitation` window (default 1–3 ticks ≈ 50–150 ms). During the window the camera keeps aiming and micro-saccades are enabled. This is a **distinct concept** from the removed `settleDelay`: it's humanness, not a timing buffer for packet sync.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No pause at all (state after commit `67eea42`) | Fastest transition; reads as "adjust → mine" in one motion | Loses the visible human "commit moment" between locking on and clicking. Combined with the spring-damper camera, the transition feels too eager — particularly visible block-to-block in a vein where the bot is otherwise standing still |
| Re-add old `settleDelay` (2–5 ticks) | Restores some humanness | The old delay was framed as a timing buffer for the server's carried-item packet — that buffer is no longer needed (tool selection now happens during the LOOKING camera turn). Keeping the same name and timing conflates two different reasons for the same pause |
| Pre-attack hesitation (1–3 ticks, distinct name, chosen) | Narrower window than the old settle, clearly labelled as humanness; saccades start here so the bot's gaze "trembles" during the commit moment | Adds 50–150 ms per block break — within the user-authorised "small reliability degradation" budget |

Chose the narrower, renamed concept because the cleanup commit (`67eea42`) was right to remove the old delay (it was solving a problem that no longer existed), but removing it entirely also removed the bit of humanness that pause was inadvertently providing. The new field reintroduces that humanness with half the duration and a clear semantic separation: tool selection sync is done by parallel packet sending during LOOKING; the hesitation here is purely the human "I see it / I click" beat.

## Reaction delays at phase transitions vs LOOKING→INTERACTING gate

**Decision**: A Gaussian-distributed reaction delay (mean 4 ticks, σ=2, clamped to [1, 10]) is inserted at three transitions where the bot currently reacts instantly: `SCANNING→NAVIGATING`, `POSITIONING→LOOKING`, and `INTERACTING-broken→{LOOKING|next-LOOKING}`. The delay is **not** applied on the `LOOKING→INTERACTING` gate — that boundary already uses the more authoritative pre-attack hesitation (see "Pre-attack hesitation vs removed settle delay") and the hit-result gate prevents the gate from firing too early.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Apply reaction delay on every transition | Most consistent humanness | The `LOOKING→INTERACTING` gate is the latency-sensitive one (block-break timing in accelerated tests). Adding a second uncorrelated random delay on top of pre-attack hesitation doubles the variance for no readability gain — viewers can't tell two short delays from one slightly longer one |
| No reaction delay anywhere; rely on camera smoothing only | Simplest | Camera smoothing only hides reaction time *during* a turn — between phases (e.g. when the bot has already aimed and now decides to walk) there's nothing to mask the instant transition |
| Reaction delay only at top-of-task (`SCANNING` entry) | One place to tune | Misses the most visible cue — the pause after a block breaks. A bot that drops a log and instantly turns to the next log reads as a script; the pause is exactly the "human notices the result" moment |
| Reaction delay at the three chosen transitions (chosen) | Captures the cues a viewer actually notices: "decided to walk", "decided to attack the next block", "spotted the next block in reach". Avoids stacking with pre-attack hesitation | Mechanism (deferred Runnable + delay counter) is one extra state in the tick loop |

Chose the three-transition application because those are the moments where the bot's *decision* changes — a viewer's eye is drawn to phase changes, and a millisecond pause at each one is what reads as "a person is doing this." `LOOKING→INTERACTING` is excluded because pre-attack hesitation already covers that boundary with the right semantics (commit moment, not phase change).

**Implementation note**: the delay is realised by storing a `Runnable` and a tick countdown on `BotController`; while the countdown is non-zero, the main tick early-returns without running the current phase's logic or incrementing `phaseTicks` — so per-phase timeouts (`navigateTimeout`, `lookTimeout`, etc.) are not eaten by the delay. `releaseMovementKeys()` is called when scheduling so the bot visibly stops moving during the beat instead of coasting.

## Server-side held-item sync in tool selection

**Decision**: `InventoryHelper.selectBestTool` sends `ServerboundSetCarriedItemPacket` every time it selects a slot, even if client-side `selectedSlot` is already at that index.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| `setSelectedSlot` only (original) | Minimal | Client-only change; server's held-item state may diverge and break/drop calculations use the wrong tool. |
| Send packet only when `bestSlot != currentSlot` | Slight bandwidth saving | Requires trusting that client and server are in sync — which they're not always, especially across test tests. |
| Send packet always (chosen) | Cheap, idempotent, guarantees server agreement | One extra packet per interaction start (negligible) |

Chose to always send because the packet is tiny, idempotent, and a stale server-side slot is a silent-failure mode (drops computed with wrong tool). Worth the trivial cost.

## Gamemode-sync wait in test setup

**Decision**: `BotTests.switchToSurvivalAt` blocks on `!mc.player.getAbilities().instabuild` after issuing `/gamemode survival`, before the test enqueues its mining task.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No wait (original) | Simple | Racy under load. When the server hasn't processed `/gamemode survival` yet, the player is still creative server-side. Block breaks are instant and drop nothing client-receives an air update and our `airConfirmTicks` fires, bot moves on, no drop ever appeared. Presented as "tool-select race" for hours — misdiagnosed. |
| Large `toolSettleTicks` + `airConfirmTicks` (tried) | Brute-forces timing margins | Doesn't fix the root cause. Every time system load changed, the minimum safe value changed with it — 8 → 16 → 24 → 48 all flaked eventually. |
| Wait on ability-sync flag (chosen) | Directly observes the server→client state we actually need. No timing constants. Failure class eliminated, not masked. | Adds a small wait (usually < 1 tick wall) only in test setup. |
| Hook `ClientboundPlayerAbilitiesPacket` via mixin | Equivalent authoritative signal | Adds mixin surface for a check the existing `Abilities` field already exposes. |

Chose the ability-flag wait because it's the minimal, direct sync on the actual condition we care about. Added after confirming the block-break-with-no-drop pattern fired in `walkMineWalkChop`'s first mined position too, ruling out the tool-race as the sole cause.

## Item nearest-selection: 3D distance + vertical filter

**Decision**: `tickCollecting` selects the next item to walk toward by 3D distance from the player (not horizontal-only), skips items whose `|dy|` exceeds 4 blocks, and stops walking when 3D distance drops below 1.5 (vanilla pickup radius).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Horizontal-only nearest + 1.0-block walk stop (original) | Simple | An item 1 horizontal block away with `dy=2` (in the dug-out hole the bot just stood on) wins the nearest tie over an item 2 horizontal blocks away on the same Y. The bot walks to the in-hole item but its 3D distance is √5 ≈ 2.24 — outside the 1.5 vanilla pickup radius — and never gets close enough. Reported as "gedroppte items werden nicht immer eingesammelt". |
| 3D nearest + `|dy| ≤ 4` filter + 1.5 walk stop (chosen) | Picks the item the bot can actually reach by horizontal walk. The Y filter rejects items 4+ blocks above (unreachable without climbing) or below (drops past safe-fall range; bot would take fall damage chasing them). The 1.5 stop matches the server-side pickup radius so the bot stops exactly when vanilla pickup will fire. | Doesn't help items truly stuck behind walls or in water — those need A* to a reachable standoff, addressed separately. |
| A* path to a mesh node near the item | Handles every reachable item including around walls | Requires per-tick A* and a mesh covering the item's neighborhood; the mesh may not exist for the immediate drop area. Heavier than needed for the common case. |
| Drop tracking with predicted final position | Simulates the drop's bounce and walks to its predicted resting place | Server bounces drops in ways the client can't always predict; precision wasted. |

Chose 3D + filter because it closes the common failure case (drop bouncing into the bot's own dug hole) with three small one-line changes, and leaves the door open to layer A* on top later for truly hard cases (item behind wall, item in water).

## Item-entity search radius (8 blocks)

**Decision**: `player.getBoundingBox().inflate(8.0)` for the item-entity AABB query in `tickCollecting`.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| 4 blocks (= reachDistance) | Matches mining reach | Drops can bounce 2–3 blocks from the mined block, so a 4-block AABB from the standoff position can miss drops 5–6 blocks away. |
| 8 blocks (chosen) | Covers bounce radius | In a real session may match unrelated drops from another player |
| 16 blocks | Always finds every drop | Larger scan cost, more false matches from nearby mobs dropping items |

Chose 8 blocks because it covers the worst-case bounce while keeping the scan cheap. The `lastMinedPos` walk ensures we actively move toward our own drop rather than waiting passively.

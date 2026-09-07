# Bot Module

Automation bot that uses pathfinding to navigate and perform tasks like mining ores, chopping trees, and gathering resources — all through simulated input for human-like behavior.

## Layering

Two layers, strictly separated:

1. **Task layer** (`BotController` + `BotTask` queue) — executes one block interaction at a time with human-like camera movement, timing, tool selection and drop collection.
2. **Behavior layer** (`behavior/`) — long-running strategies (strip mining, future farming/building) that decide *what to work on next*. A `BotBehavior` plans by enqueuing tasks and waiting for the controller to go idle; it must never simulate input itself. Registered via `BehaviorRunner.register(...)` (typically from a specialized module's init, e.g. `modules/miner`), started by id, at most one active. `BehaviorRunner.tick` runs before `BotController.tick` so a plan made this tick executes this tick. Stopping flows top-down: `/bot stop` stops the runner (which aborts the behavior) and then the controller — never the other way around.

## Architecture

```
net.stracciatella.bot
├── BotModule.java                  # Entry point (@Task STARTED), registers tick/commands/tests
├── BotController.java              # Static tick-driven state machine (like PathWalker)
├── BotConfig.java                  # GSON-persisted config (bot.json), humanization knobs
├── BotPolicy.java                  # Record: per-behavior safety/collection opt-ins
├── BotCommands.java                # /bot subcommands
├── task/
│   ├── BotTask.java                # Interface: targetPos, interactionType, isComplete
│   ├── TaskQueue.java              # ArrayDeque<BotTask>; pollNearest/peekNearest for human-like routing
│   ├── TaskResult.java             # Record: success, reason, ticksElapsed
│   ├── InteractionType.java        # Enum: ATTACK, USE
│   ├── MineBlockTask.java          # Mine single block (complete when → air)
│   ├── ChopTreeTask.java           # Mine tree logs top-to-bottom (multi-target)
│   ├── PlaceBlockTask.java         # Place a block against a support face (USE)
│   └── GatherAreaTask.java         # Meta-task: scan + enqueue mine/chop subtasks
├── behavior/
│   ├── BotBehavior.java            # Interface for long-running strategies (id, policy, start, tick, abort, statusLine)
│   ├── BehaviorStatus.java         # Enum: RUNNING, SUCCEEDED, FAILED
│   └── BehaviorRunner.java         # Static registry + executor, one active behavior, owns the policy + stop guards
├── safety/
│   └── BotAlarm.java               # Latches damage / player-attack events for the runner
├── mixin/
│   └── ClientPacketListenerMixin.java  # Damage + attack-sound packets → BotAlarm
├── interaction/
│   ├── BlockInteractor.java        # Simulates attack/use key hold
│   └── InventoryHelper.java        # Reads hotbar, selects best tool, counts free slots
├── scan/
│   ├── BlockScanner.java           # Finds blocks by predicate within radius
│   ├── TreeDetector.java           # Detects tree structures (base, trunk, height)
│   └── TreeInfo.java               # Record: basePos, logs (top-to-bottom), height
├── humanize/
│   └── HumanBehavior.java          # Random delays, aim jitter, pause patterns
└── test/
    └── BotTests.java               # In-game integration tests
```

## State Machine (BotController)

BotController is **static** and **tick-driven** via `ClientTickEvents.END_CLIENT_TICK`, same pattern as PathWalker.

```
IDLE → SCANNING → NAVIGATING → POSITIONING → LOOKING → INTERACTING → (decision)
                                                                         ↓
                                                next sub-target? → LOOKING
                                                next in reach?   → LOOKING (no pause)
                                                next needs walk? → COLLECTING → SCANNING → NAVIGATING
                                                nothing left?    → COLLECTING → IDLE
```

### Phases

| Phase | Behavior | Timeout |
|-------|----------|---------|
| **IDLE** | No active task, poll queue when new task enqueued | — |
| **SCANNING** | `CameraController.aimAt` toward distant target, exit when `isAimedAt(scanFacingTolerance)` | `scanTimeout` |
| **NAVIGATING** | PathWalker controls movement, bot monitors `isActive()` | `navigateTimeout` |
| **POSITIONING** | Fine-tune position if not within reach after navigation; walks while the camera (re-initialized from current rotation — PathWalker may have rotated the player) smoothly eases onto the target block. After a failed look (`forceApproach`) it walks to close range (2.0) instead of reach distance — the re-approach exists to change the viewpoint | `positionTimeout` |
| **LOOKING** | `CameraController.aimAt` toward target block face + offset; on tick 1 also `selectBestTool` (carried-item packet runs in parallel with the camera turn) and roll a per-target look-speed. Exit the moment the client's `hitResult` is a `BlockHitResult` whose `getBlockPos()` equals the target — the crosshair touching the block is when a human clicks; no angular convergence required (the camera keeps easing toward its aim point during INTERACTING). A short `preAttackHesitation` (1–3 ticks) is held between the gate firing and the transition; during it the camera keeps aiming and micro-saccades are enabled. | `lookTimeout` |
| **INTERACTING** | Calls startAttack/continueAttack directly, polls `isAir()`, maintains camera via `aimAt` | `maxBreakTicks` |
| **COLLECTING** | Walk toward visible drops or `lastMinedPos`, gaze following the drop at a capped ground-scan pitch (~38–52°, rolled per phase via `aimCollectGaze`; saccades on; look skipped when the item is nearly underfoot — unstable yaw target); exit once items have been observed and are all picked up | `collectWaitMax` |

### Smart transitions after block break

- **More sub-targets** (tree logs): straight to LOOKING, no pause
- **Next task within reach**: straight to LOOKING with new task, no pause
- **Next task needs walking**: COLLECTING → SCANNING → NAVIGATING
- **No more tasks**: COLLECTING → IDLE

### Task selection: nearest from current position

Every place that takes the next task from the queue (`startNextTask`, the within-reach continuation after a break, the COLLECTING walk-flow exit) uses `TaskQueue.pollNearest(player.blockPosition())` — the task whose target is closest to where the bot currently stands, ties keeping insertion order. A human works an area closest-first from wherever they are; replaying the scanner's fixed order produces visible zigzag routes. Single explicitly-issued commands are unaffected (one-element queue).

### Reaction delays don't freeze the camera

While a deferred-action reaction delay is in flight, movement keys are released but the camera keeps easing toward its last aim point (recorded by `aimCameraAt`) and keeps saccading. A spring-damper camera frozen mid-swing for 1–10 ticks reads as stop-motion — most visible at SCANNING→NAVIGATING, where the 15° tolerance fires while the camera still has angular velocity.

### COLLECTING exit conditions

Exit gates depend on whether another walked task is queued:

- **Single task / nothing queued** — three conditions must all hold:
  1. `itemsSeenThisCollect` — at least one tick observed items in the 8-block AABB. Closes the server→client spawn-sync race where the block has broken but the drop entity hasn't synced.
  2. `!itemsNearby` — currently no visible drops.
  3. `phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks` (default 60) — sustained absence window. Avoids exiting on a transient absence tick between sequentially picking up multiple drops.
- **Walked task queued** (`walkAfterCollect && taskQueue.peek() != null`) — only conditions 1 and 2 are required; the sustained-absence wait is skipped. Straggler drops are picked up via the 1.5-block vanilla radius during the `NAVIGATING` walk toward the next target. Eliminates the visible "pause after pickup" in multi-task flows.
- **`fastCollectExit` opted in** — likewise conditions 1 and 2 only. A behavior that plans one block at a time never has a task queued at this point (it plans the next one only once the controller is idle), so the clause above can never fire for it and the 60-tick window is paid for *every block*: measured on the chunk miner, ~20 ticks of work per block against ~90 elapsed.

While no items are visible yet, the bot walks toward `lastMinedPos` so the drop enters the AABB query as soon as the server syncs it. `collectWaitMax` (400 accel ticks) is the hard timeout for drops that never become reachable.

If COLLECTING exits while the queue has more work, and the bot has ended up within reach of the next target (e.g. the next ore in a vein), the controller transitions directly to `LOOKING` and skips `SCANNING` — there's no movement to cue.

### Key integration points

- **PathWalker**: Bot calls `PathWalker.start(path)` for navigation, monitors `PathWalker.isActive()`. Creates its own `CameraController` instance for aiming (separate from PathWalker's camera), and drives it via the high-level `aimAt` / `isAimedAt` APIs so no yaw/pitch math lives in the bot. Targets within `reachDistance + 2.5` skip the pipeline entirely — POSITIONING walks the last couple of blocks directly (no pathfinding ceremony for two steps).
- **MeshManager**: Bot queries `MeshManager.meshes` to find walkable standoff nodes near targets. Standoff scoring is line-biased (`1.05 × distToPlayer + horizDistToTarget`) so the chosen node lies on the straight player→target line — the player-nearest rule produced a visible sideways dog-leg right before the target.
- **MeshPathfinder**: Bot uses A* to find paths from player to standoff positions.

## Block Interaction

### LOOKING → INTERACTING gate

A single condition gates the transition: `mc.hitResult instanceof BlockHitResult` AND `bhr.getBlockPos().equals(target)` — the client's raycast actually lands on the target block. Mining starts the moment the crosshair touches the block (the way a human clicks), not once the camera has converged on its ideal aim point; the camera keeps easing toward the aim point during INTERACTING. The hit-result check carries the obstacle correctness: when something blocks the line of sight the raycast lands on the obstacle and the gate holds.

On the **first** look timeout per target the bot does not fail — it re-approaches: POSITIONING walks to close range (2.0 blocks, ignoring the reach-distance early exit) and LOOKING retries, the way a human steps up to a block they can't see from where they stand. The **second** timeout fails the task with diagnostics (player position, chosen face, actual raycast hit).

The aim point is the center of the face most directly visible from the bot's eye (via `BlockInteractor.faceTowardPlayer`), not the block center — otherwise a raycast aimed at the center of a block sitting in the middle of a stack (e.g. the top log of a tree) lands on the neighbor and the hit-result gate never satisfies. Human-aim jitter is applied only on the two axes perpendicular to the face normal; jitter along the face normal would push the aim point off the face plane and cause the ray to graze a neighbor block instead.

### Mining

Mining uses vanilla input pipeline — `options.keyAttack.setDown(true)` while camera aims at block. No direct `gameMode` calls.

Break detection requires TWO authoritative signals before considering the block broken:

1. **Sustained-air window**: `level.getBlockState(pos).isAir()` for `CONFIG.airConfirmTicks` consecutive ticks (default 8). `getBlockState` returns the client's view which can be a sequenced-transaction prediction; the sustained window tolerates normal server-confirmation delay.
2. **A break artifact** — either a drop entity observed within a 5×5×5 AABB around the target at any point since the break started (polled every tick and **latched**: standing next to the block, vanilla pickup inhales the drop within a tick of spawning), or growth of the main-inventory item count since break start (the pickup itself, server-driven via slot sync). Both only exist if the server completed the break.

**Why the artifact check is necessary**: the client's block prediction can remain "air" long enough to pass the sustained-air window even when the server ultimately never broke the block (it reverts the client's prediction later). We observed `lastMinedPos state=iron_ore` at COLLECTING exit — the block had reverted to iron_ore on the client, proving the server never actually broke it. Without the artifact check, the bot falsely "confirms" breaks on predictions the server rejects, resulting in missing drops. The inventory-growth path exists because a once-after-air query missed instantly-inhaled drops and left the bot punching the already-broken block until `maxBreakTicks`.

`airConfirmTicks` resets to 0 whenever the block is observed non-air. `maxBreakTicks` (default 400) caps INTERACTING — covers the slowest legit break + drop-spawn sync under load.

`InventoryHelper.selectBestTool` scans the full main inventory (slots 0–35, i.e. hotbar + storage rows). If the best tool sits in storage (slot ≥ 9) it is swapped into the currently selected hotbar slot via a `ClickType.SWAP` container-click packet — a human player would do the same. A hotbar-only search would silently fall back to bare hand and drop nothing from ores.

It then sends `ServerboundSetCarriedItemPacket` after `setSelectedSlot` so the server's held-item state matches the client's. Without the packet sync, server-side drops are computed with the wrong tool (e.g. iron_ore mined with server-side bare hand drops nothing).

Tool selection now happens in LOOKING (tick 1), not INTERACTING. The carried-item (and any inventory-swap) packet travels to the server during the smooth camera turn — by the time the hit-result gate fires, the server has had the full LOOKING duration to apply the slot change. INTERACTING then attacks on its first tick after a single defensive `resendCarriedItem` call, with no tool-settle wait. `LOOKING` re-resends the carried-item packet each subsequent tick as a dropped-packet safety net.

A short `preAttackHesitation` (default 1–3 ticks) is held between the LOOKING gate firing and the transition to INTERACTING. This is **not** the old `settleDelay` (a timing buffer for the server) — packet sync is already done by parallel tool selection above. The hesitation is purely humanness: the visible "I see it, I click" beat between locking on and clicking. Micro-saccades enable at the start of the hesitation so the gaze trembles slightly during the commit moment.

### Placing (`PlaceBlockTask`, `InteractionType.USE`)

Placement is **not** the mirror image of mining. You cannot aim at the position where the block should go — a raycast passes straight through it — so a block is placed on the side of an existing block you click. `PlaceBlockTask` therefore takes a **support** block adjacent to the destination, and `targetPos()` returns the *support*, not the destination. The entire LOOKING pipeline (aim, hit-result gate, re-approach, timeouts) then works unchanged; `placePos()` exposes the destination for callers. `PlaceBlockTask.findSupport(level, pos)` picks a neighbour whose face toward `pos` is sturdy (`BlockState.isFaceSturdy`), returning null when the position is unreachable from any angle — the caller should then skip it instead of enqueuing a task that can only time out.

Two `BotTask` default methods carry the USE-specific data, so no existing task changed:

- `requiredItem()` — the item to hold. LOOKING calls `InventoryHelper.selectItem` (largest matching stack, same hotbar-swap + carried-item packet as `selectBestTool`) instead of picking the fastest tool, which would be nonsense for a placement.
- `preferredFace()` — the face to click. Mining returns null and re-derives the most visible face every tick as the bot moves; placement pins it, because the face decides where the block ends up. When non-null the LOOKING hit-result gate **also** requires `bhr.getDirection()` to match — clicking the wrong side of the support would put the block somewhere else entirely.

`BlockInteractor` sends one `useItemOn` against that face and then retries every `USE_RETRY_TICKS` (15) until the controller confirms or the task times out. There is no "continue" packet for placement the way there is for mining: a use either places or it doesn't, and a human who clicks and sees nothing happen clicks again. The retry also covers a dropped use packet.

Confirmation mirrors the break gate exactly: the destination must hold a solid, fluid-free block for `airConfirmTicks` consecutive ticks **and** the main inventory must have *shrunk* by the consumed block. A client-predicted placement the server rejects never moves the item count. In creative the block is never consumed, so `instabuild` substitutes for the inventory signal — without that clause every creative placement would silently burn the full interaction timeout.

A placement drops nothing, so the post-interaction path skips COLLECTING entirely and takes the next task after the usual reaction beat.

The counter formerly called `airConfirmTicks` is now `stateConfirmTicks` — it counts ticks in the *completed* state, which is air for mining and a solid block for placing. The config knob keeps its original name (`CONFIG.airConfirmTicks`) because it is persisted in `bot.json`.

## Policy layer (`BotPolicy`)

Safety and collection behaviour is **opted into per behavior**, never configured globally. `BotBehavior.policy()` is abstract on purpose: whether a strategy should abort on damage or keep digging is a decision its author has to make, and a default would let a new behavior inherit "no safety at all" by omission. `DiamondMinerBehavior` returns `BotPolicy.none()` in one line — its timing was validated without any of this, and mob *defence* rather than stopping is the right answer for a strip miner.

`BehaviorRunner` reads the policy once at start, pushes it into `BotController`, enforces the stop guards itself, and resets both on stop. Tasks issued straight from `/bot` commands run under `none()`, i.e. exactly the pre-policy execution. There is no `/bot safety` command and no global setting.

| Flag | Effect |
|------|--------|
| `stopOnDamage` | Any health decrease ends the run |
| `stopOnPlayerAttack` | A player swinging at the bot ends the run, damage or not |
| `stopWhenInventoryFull` + `minFreeSlots` | Ends the run once fewer than N main slots are empty |
| `opportunisticCollection` | INTERACTING steps toward nearby drops without dropping the break |
| `fastCollectExit` | Closes the two COLLECTING stalls below |

### Detection: packets, not polling

Two `ClientPacketListener` injections feed `BotAlarm`; the runner consumes both latches every tick and applies only the ones its policy asked for (a trigger nobody wanted must not stay set and fire at the start of the next run).

- `handleDamageEvent` where `entityId == mc.player.getId()` — the bot took damage.
- `handleSoundEvent` where the sound is `PLAYER_ATTACK_NODAMAGE`. **A zero-damage hit produces no damage event at all** — this sound is the only signal that reaches the bot's client. It is broadcast at the *attacker's* position, so proximity stands in for "aimed at me": within 5 blocks counts, within 0.5 does not, because `Player.attack` broadcasts with a `null` source player and the bot would otherwise stop itself the first time it swung at anything. A neighbouring whiff at an unrelated target is an accepted false positive — stopping too often is cheap, missing a hit is not.

Both inject at **TAIL, not HEAD**: these handlers open with `PacketUtils.ensureRunningOnSameThread`, which runs once on the netty thread (throwing to reschedule) and once on the client thread, so a HEAD injection would read `Minecraft.player` off-thread.

### Stopping and the alert

Every abnormal stop — a guard firing or the behavior returning `FAILED` — plays three `NOTE_BLOCK_PLING` beeps (spaced 5 ticks via `SoundManager.playDelayed`, so they read as three beeps rather than one) and prints a red chat line naming the reason plus the behavior's own `statusLine()` summary. A clean `SUCCEEDED` gets one soft `NOTE_BLOCK_BELL` and a grey line. The supervising player may be chunks away, so "it's done" and "it gave up" must be distinguishable without watching chat. Note `SimpleSoundInstance.forUI` takes **(pitch, volume)**, not the usual (volume, pitch).

### Opportunistic collection

With the flag on, INTERACTING walks toward a drop *while the break continues* — a human mining a corridor scoops up what fell next to them mid-swing. The camera is already committed to the mined block by the time this runs, so the walk direction is expressed in the 8 view-relative key directions (the quantization `PathWalker.applyCounterBrake` uses to brake without turning); the result is a strafe, not a turn. All four horizontal keys are driven every tick, and INTERACTING releases them on both its exits — `transitionTo` does not.

Three guardrails, because a break in progress is worth more than one dropped item:

1. The projected step must keep the target inside `reachDistance` **and** leave a clear `level.clip` line from the would-be eye to the block. The server reach/visibility-checks the break packets; stepping behind a pillar stalls the task until `maxBreakTicks` with no visible cause.
2. The destination must be standable — sturdy floor, two fluid- and collision-free cells. No drop is tolerated at all: even a one-block fall pulls the target out of the aim.
3. Only drops between 1.5 and 3 blocks away. Inside 1.5 vanilla pickup handles it; beyond 3 the trip costs more than the item.

COLLECTING still runs afterwards and picks up whatever this declined.

### The COLLECTING stalls (`fastCollectExit`)

All pre-existing, all burning the full `collectWaitMax`. They are fixed behind a flag rather than globally because `/bot mine` and the diamond miner were tuned around today's timing.

1. **Inhaled drop.** Standing on top of the block, vanilla pickup can take the drop before any tick observes it, so `itemsSeenThisCollect` — which the exit gate requires — never becomes true. Fix: inventory growth since the break started also counts, the same server-authoritative proof the break gate already accepts (and it covers a pickup during INTERACTING too).
2. **Unreachable drop, vertically.** `itemsNearby` was computed from the *unfiltered* entity list while the walk loop skips anything more than 4 blocks above or below. An item the bot has explicitly decided never to approach kept `itemsNearby` true forever, so `doneCollecting` could never fire. Fix: measure presence on the same filtered set the walk uses.
3. **Unreachable drop, horizontally.** The same thing one axis over, and not covered by that filter: a drop landing behind a block the bot will never mine — a blacklisted block, bedrock, the far side of a dammed liquid — sits inside the AABB and inside the walk filter, but outside the 1.5-block pickup radius and behind a wall, so `walkToward` pushes into the obstruction and the distance never shrinks. Six of fourteen collects in one chunk-suite run burned `collectWaitMax` this way. Fix: a walk-progress watchdog (nearest item's entity id, best distance reached, ticks since it improved) drops the item after `itemAbsenceTicks` without closing 0.05 blocks. It nulls `nearest` rather than only clearing `itemsNearby`, so the walk stops too — otherwise the movement keys are still down on the exit tick, and `transitionTo` does not release them. A `level.clip` line-of-sight test was rejected: it would abandon drops behind a corner that are perfectly reachable by walking around.

## Test setup: waiting on gamemode sync

A separate race used to produce the same "block broken, no drop" symptom: the test runs `/gamemode survival` and then immediately enqueues a mining task on the client. On the heavily-loaded accelerated-tick server, `/gamemode` can be queued behind other command packets. When the bot starts attacking, the server still has the player in creative — block breaks are instant client-side and drop nothing. `BotTests.switchToSurvivalAt` now waits on `!mc.player.getAbilities().instabuild` (the server→client ack of the mode change) before starting the bot, which eliminates the whole class of failure without any timing tuning.

The held-slot-sync race is handled separately: tool selection in LOOKING tick 1 lets the carried-item packet travel during the whole camera-turn window, so by the time INTERACTING fires the server is already on the right slot. The `preAttackHesitation` does add a small (1–3 tick) wait between the gate firing and the attack, but its purpose is humanness — not packet timing — and disabling it must not regress drop reliability.

## Human-Like Behavior

- Spring-damper camera smoothing (5–15 ticks to converge), with a randomised look-speed multiplier per target so successive aims don't all turn at the same rate
- Random aim offset within block face (Gaussian, clustered near center, clamped to ±aimOffsetMax)
- Gaussian reaction delay between phase decisions — applied at `SCANNING→NAVIGATING`, `POSITIONING→LOOKING`, and the block-broken transition; mean 4 ticks (~200 ms), σ=2. During the delay the body pauses but the camera keeps easing toward its last aim point (no stop-motion freeze)
- Occasional long "breather" pause at `SCANNING→NAVIGATING`: with `longPauseChance` (default 4%) the standard reaction delay is replaced by a 12–25 tick pause — breaks the otherwise machine-constant cadence
- Pre-attack commit hesitation: a 1–3 tick "I see it, I click" beat between both LOOKING gates firing and the first `startAttack`. Distinct from the old settle delay — tool sync already happens during the LOOKING camera turn, this is purely humanness
- Micro-saccades during sustained aim (INTERACTING and COLLECTING): ±0.5° yaw, ±0.3° pitch perturbations refreshed every ~12 ticks. Avoid the "frozen gaze" look while mining and while standing over drops
- Gaze follows the work: POSITIONING walks while smoothly looking at the target block (no hard yaw snap); COLLECTING looks at the drop being collected / the expected drop position, with the downward pitch capped at a per-phase ground-scan angle (~45° with variance) — tracking the item point directly would crane the head ever steeper on approach
- Nearest-task routing: the next task is always the one closest to the bot's current position, not the scanner's fixed order — no zigzag routes across a gather area
- Tool selection done in LOOKING tick 1 (carried-item packet travels in parallel with the camera turn, no separate tool-settle wait in INTERACTING)
- Per-session "skill jitter": at `loadConfig` (world join), `HumanBehavior.rollSessionSkill` draws a Gaussian multiplier in [0.85, 1.15]. Multiplied into look-speed and divided out of reaction-delay so every session has slightly different baseline cadence — sometimes the bot is a touch faster, sometimes a touch slower

## Commands (`/bot`)

| Command | Description |
|---------|-------------|
| `/bot mine` | Mine looked-at block |
| `/bot mine <x> <y> <z>` | Mine block at coordinates |
| `/bot chop` | Chop looked-at tree |
| `/bot gather ores [radius]` | Mine all ores in radius |
| `/bot gather logs [radius]` | Chop all trees in radius |
| `/bot stop` | Stop behavior layer, then controller; clears the queue |
| `/bot pause` / `/bot resume` | Pause/resume |
| `/bot status` | Show phase, task, queue, active behavior |
| `/bot debug on\|off` | Toggle debug logging |

## Config

Persisted to `stracciatella/bot.json`. Key parameters:

Humanness:
- `aimOffsetMin/Max` — block-face aim jitter magnitude (Gaussian, σ = max/2)
- `lookSpeedMin/Max` — spring-acceleration multiplier rolled per new target (default 0.7–1.3)
- `reactionDelayMeanTicks` / `reactionDelaySigmaTicks` / `reactionDelayMinTicks` / `reactionDelayMaxTicks` — clamped Gaussian for inter-phase reaction delay (default mean 4, σ=2, range [1, 10] ≈ 50–500 ms). Set mean and σ to 0 to disable.
- `preAttackHesitationMin/Max` — uniform pre-attack hesitation ticks between LOOKING gate firing and first `startAttack` (default 1–3)
- `longPauseChance` / `longPauseMinTicks` / `longPauseMaxTicks` — chance (default 0.04) that the SCANNING→NAVIGATING reaction delay becomes a longer uniform "breather" pause (default 12–25 ticks). Set chance to 0 to disable.
- `collectGazePitchMinDeg/MaxDeg` — cap on the downward pitch while looking at drops in COLLECTING (uniform per phase, default 38–52°); steeper aims become a "scan the ground ahead" look in the item's direction

Phases / timing:
- `collectWaitMax` — hard timeout for COLLECTING if drops never become reachable (default 1200, covers delayed item-entity sync at 20x tickSpeed)
- `airConfirmTicks` — consecutive air-observation ticks required to confirm a break (default 8)
- `itemAbsenceTicks` — ticks items must be absent after a sighting before COLLECTING exits (default 60)
- `scanTimeout` — max ticks to look toward next target before walking (30)
- `scanFacingTolerance` — degrees tolerance for scan convergence (15.0)
- `scanRadius` — block scan radius
- `reachDistance` — max mining reach (4.0)
- `maxBreakTicks` — interaction timeout (default 400, must cover mine + drop-spawn wait)
- `navigateTimeout` / `positionTimeout` / `lookTimeout` — per-phase deadlines

## Dependencies

- `loader` (compileOnly) — Module interface
- `camera` (compileOnly) — CameraController, AngleUtil
- `pathfinding` (compileOnly) — PathWalker, MeshManager, MeshPathfinder
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

Tests in `test/BotTests.java`, registered via `TestRunner.instance().registerSuite(BotTests.class)`.
Run via `./gradlew runMinecraftTests`. Each test builds its environment with `/fill` + `/setblock`.

Test cases: single block mine, tool selection, tree chop, camera smoothness, walk-and-mine, multi-task queue, walk→mine→walk→chop, ore vein, out-of-reach failure, place block, place-needs-support, policy damage stop, policy inventory-full stop, policy fast collect exit, policy opportunistic collection.

The four policy tests drive a `PolicyProbeBehavior` defined inside `BotTests` — the policy layer is only reachable through a behavior, so the guards need one to be testable at all.

- **fast collect exit** summons a `NoGravity` item 6 blocks up (inside the 8-block collect query, outside the 4-block walk filter) and asserts the run finishes in fewer than `collectWaitMax` ticks with the decoy still present, the block actually mined, and the cobblestone in the inventory. Without the flag the decoy's mere presence pins the phase until the timeout; the last two assertions exist so "exits sooner" can't quietly become "exits without collecting".
- **opportunistic collection** puts a drop 2.5 blocks to the bot's *side* — perpendicular to the block being mined, so reaching it is a pure strafe — and asserts the bot closes at least 0.5 blocks **while still in INTERACTING**. This is what pins down the view-relative sign convention, which has no other coverage. It mines **obsidian with a diamond pickaxe**: ~187 ticks of INTERACTING, long enough to observe the walk, and it still drops. Mining bare-handed for a slow break does not work — stone without a pickaxe drops nothing, so the break never produces an artifact, never confirms, and ends in a `maxBreakTicks` timeout instead of the phase the test needs to watch.

**The player-attack path has no in-game test** — it needs a second player swinging at the bot. Its two real failure modes (never firing, firing on the bot's own swing) are covered by plain JUnit in `src/test/.../safety/BotAlarmTest.java`, which is why `BotAlarm.isAttackerInRange` takes bare doubles instead of Minecraft types. Run with `./gradlew :modules:bot:test`.

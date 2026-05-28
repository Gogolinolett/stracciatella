# Bot Module

Automation bot that uses pathfinding to navigate and perform tasks like mining ores, chopping trees, and gathering resources — all through simulated input for human-like behavior.

## Architecture

```
net.stracciatella.bot
├── BotModule.java                  # Entry point (@Task STARTED), registers tick/commands/tests
├── BotController.java              # Static tick-driven state machine (like PathWalker)
├── BotConfig.java                  # GSON-persisted config (bot.json), humanization knobs
├── BotCommands.java                # /bot subcommands
├── task/
│   ├── BotTask.java                # Interface: targetPos, interactionType, isComplete
│   ├── TaskQueue.java              # ArrayDeque<BotTask> with offer/poll/clear
│   ├── TaskResult.java             # Record: success, reason, ticksElapsed
│   ├── InteractionType.java        # Enum: ATTACK, USE
│   ├── MineBlockTask.java          # Mine single block (complete when → air)
│   ├── ChopTreeTask.java           # Mine tree logs top-to-bottom (multi-target)
│   └── GatherAreaTask.java         # Meta-task: scan + enqueue mine/chop subtasks
├── interaction/
│   ├── BlockInteractor.java        # Simulates attack/use key hold
│   └── InventoryHelper.java        # Reads hotbar, selects best tool
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
| **POSITIONING** | Fine-tune position if not within reach after navigation | `positionTimeout` |
| **LOOKING** | `CameraController.aimAt` toward target block face + offset, exit when both the angular `isAimedAt(facingTolerance)` check passes **and** the client's `hitResult` is a `BlockHitResult` whose `getBlockPos()` equals the target, then settle delay elapses | `lookTimeout` |
| **INTERACTING** | Calls startAttack/continueAttack directly, polls `isAir()`, maintains camera via `aimAt` | `maxBreakTicks` |
| **COLLECTING** | Walk toward visible drops or `lastMinedPos`; exit once items have been observed and are all picked up | `collectWaitMax` |

### Smart transitions after block break

- **More sub-targets** (tree logs): straight to LOOKING, no pause
- **Next task within reach**: straight to LOOKING with new task, no pause
- **Next task needs walking**: COLLECTING → SCANNING → NAVIGATING
- **No more tasks**: COLLECTING → IDLE

### COLLECTING exit conditions

Exit gates depend on whether another walked task is queued:

- **Single task / nothing queued** — three conditions must all hold:
  1. `itemsSeenThisCollect` — at least one tick observed items in the 8-block AABB. Closes the server→client spawn-sync race where the block has broken but the drop entity hasn't synced.
  2. `!itemsNearby` — currently no visible drops.
  3. `phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks` (default 60) — sustained absence window. Avoids exiting on a transient absence tick between sequentially picking up multiple drops.
- **Walked task queued** (`walkAfterCollect && taskQueue.peek() != null`) — only conditions 1 and 2 are required; the sustained-absence wait is skipped. Straggler drops are picked up via the 1.5-block vanilla radius during the `NAVIGATING` walk toward the next target. Eliminates the visible "pause after pickup" in multi-task flows.

While no items are visible yet, the bot walks toward `lastMinedPos` so the drop enters the AABB query as soon as the server syncs it. `collectWaitMax` (400 accel ticks) is the hard timeout for drops that never become reachable.

If COLLECTING exits while the queue has more work, and the bot has ended up within reach of the next target (e.g. the next ore in a vein), the controller transitions directly to `LOOKING` and skips `SCANNING` — there's no movement to cue.

### Key integration points

- **PathWalker**: Bot calls `PathWalker.start(path)` for navigation, monitors `PathWalker.isActive()`. Creates its own `CameraController` instance for aiming (separate from PathWalker's camera), and drives it via the high-level `aimAt` / `isAimedAt` APIs so no yaw/pitch math lives in the bot.
- **MeshManager**: Bot queries `MeshManager.meshes` to find walkable standoff nodes near targets.
- **MeshPathfinder**: Bot uses A* to find paths from player to standoff positions.

## Block Interaction

### LOOKING → INTERACTING gate

Two conditions must both hold before LOOKING transitions to INTERACTING:

1. **Angular**: `CameraController.isAimedAt(..., facingTolerance)` — camera vector within tolerance of target vector.
2. **HitResult**: `mc.hitResult instanceof BlockHitResult` AND `bhr.getBlockPos().equals(target)` — the client's raycast actually lands on the target block.

The angular check alone is insufficient: when an obstacle stands in the line of sight, the camera can be aimed within `facingTolerance` of the target vector while the raycast still hits the obstacle (visible to the player as the bot starting to attack the wrong block before correcting). Requiring both gates means the settle countdown only progresses once the bot can actually see the target. If line-of-sight is permanently blocked, `lookTimeout` fails the task cleanly.

### Mining

Mining uses vanilla input pipeline — `options.keyAttack.setDown(true)` while camera aims at block. No direct `gameMode` calls.

Break detection requires TWO authoritative signals before considering the block broken:

1. **Sustained-air window**: `level.getBlockState(pos).isAir()` for `CONFIG.airConfirmTicks` consecutive ticks (default 8). `getBlockState` returns the client's view which can be a sequenced-transaction prediction; the sustained window tolerates normal server-confirmation delay.
2. **Drop-entity proof**: at least one item entity must have spawned within a 5×5×5 AABB around the target block. This is the authoritative server-side signal — a drop entity only exists if the server completed the break.

**Why the drop-entity check is necessary**: the client's block prediction can remain "air" long enough to pass the sustained-air window even when the server ultimately never broke the block (it reverts the client's prediction later). We observed `lastMinedPos state=iron_ore` at COLLECTING exit — the block had reverted to iron_ore on the client, proving the server never actually broke it. Without the drop check, the bot falsely "confirms" breaks on predictions the server rejects, resulting in missing drops.

`airConfirmTicks` resets to 0 whenever the block is observed non-air. `maxBreakTicks` (default 400) caps INTERACTING — covers the slowest legit break + drop-spawn sync under load.

`InventoryHelper.selectBestTool` scans the full main inventory (slots 0–35, i.e. hotbar + storage rows). If the best tool sits in storage (slot ≥ 9) it is swapped into the currently selected hotbar slot via a `ClickType.SWAP` container-click packet — a human player would do the same. A hotbar-only search would silently fall back to bare hand and drop nothing from ores.

It then sends `ServerboundSetCarriedItemPacket` after `setSelectedSlot` so the server's held-item state matches the client's. Without the packet sync, server-side drops are computed with the wrong tool (e.g. iron_ore mined with server-side bare hand drops nothing).

After selecting the tool, INTERACTING holds off on the first attack for `CONFIG.toolSettleTicks` ticks (default 8), re-sending the carried-item packet each tick via `InventoryHelper.resendCarriedItem`. This closes a fast-break race where the attack beats the carried-item packet to the server.

## Test setup: waiting on gamemode sync

A separate race used to produce the same "block broken, no drop" symptom: the test runs `/gamemode survival` and then immediately enqueues a mining task on the client. On the heavily-loaded accelerated-tick server, `/gamemode` can be queued behind other command packets. When the bot starts attacking, the server still has the player in creative — block breaks are instant client-side and drop nothing. `BotTests.switchToSurvivalAt` now waits on `!mc.player.getAbilities().instabuild` (the server→client ack of the mode change) before starting the bot, which eliminates the whole class of failure without any timing tuning. Under accelerated ticks (`-PtickSpeed>1`) a fast block can break within ~20 ticks of entering INTERACTING — less wall time than the packet round-trip — so the break resolves against a stale server-side held slot. The settle delay lets the server apply the carried-item packet before any attack packets arrive. This affects only the first attack per target; subsequent ticks call `continueAttack` normally.

## Human-Like Behavior

- Spring-damper camera smoothing (5-15 ticks to converge)
- Random aim offset within block face (not dead center)
- Settle delay after aim convergence (2-5 ticks)
- Tool selection before mining starts

## Commands (`/bot`)

| Command | Description |
|---------|-------------|
| `/bot mine` | Mine looked-at block |
| `/bot mine <x> <y> <z>` | Mine block at coordinates |
| `/bot chop` | Chop looked-at tree |
| `/bot gather ores [radius]` | Mine all ores in radius |
| `/bot gather logs [radius]` | Chop all trees in radius |
| `/bot stop` | Stop and clear queue |
| `/bot pause` / `/bot resume` | Pause/resume |
| `/bot status` | Show phase, task, queue |
| `/bot debug on\|off` | Toggle debug logging |

## Config

Persisted to `stracciatella/bot.json`. Key parameters:

- `aimOffsetMin/Max` — block face aim jitter
- `settleDelayMin/Max` — ticks after aim converges
- `collectWaitMax` — hard timeout for COLLECTING if drops never become reachable (default 1200, covers delayed item-entity sync at 20x tickSpeed)
- `airConfirmTicks` — consecutive air-observation ticks required to confirm a break (default 8)
- `toolSettleTicks` — ticks to wait after tool select before first attack (default 8). Carried-item packet is re-sent each tick during the window.
- `itemAbsenceTicks` — ticks items must be absent after a sighting before COLLECTING exits (default 60)
- `scanTimeout` — max ticks to look toward next target before walking (30)
- `scanFacingTolerance` — degrees tolerance for scan convergence (15.0)
- `scanRadius` — block scan radius
- `reachDistance` — max mining reach (4.0)
- `maxBreakTicks` — interaction timeout (default 400, must cover settle + mine + drop-spawn wait)

## Dependencies

- `loader` (compileOnly) — Module interface
- `camera` (compileOnly) — CameraController, AngleUtil
- `pathfinding` (compileOnly) — PathWalker, MeshManager, MeshPathfinder
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

Tests in `test/BotTests.java`, registered via `TestRunner.instance().registerSuite(BotTests.class)`.
Run via `./gradlew runMinecraftTests`. Each test builds its environment with `/fill` + `/setblock`.

Test cases: single block mine, tool selection, tree chop, camera smoothness, walk-and-mine, multi-task queue, walk→mine→walk→chop, ore vein, out-of-reach failure.

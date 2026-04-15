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
| **SCANNING** | Smoothly look toward distant target before walking (CameraController) | `scanTimeout` |
| **NAVIGATING** | PathWalker controls movement, bot monitors `isActive()` | `navigateTimeout` |
| **POSITIONING** | Fine-tune position if not within reach after navigation | `positionTimeout` |
| **LOOKING** | CameraController smoothly rotates to target block face + settle delay | `lookTimeout` |
| **INTERACTING** | Calls startAttack/continueAttack directly, polls `isAir()`, maintains camera | `maxBreakTicks` |
| **COLLECTING** | Brief wait for item drops before walking away or going idle | `collectWaitMin/Max` |

### Smart transitions after block break

- **More sub-targets** (tree logs): straight to LOOKING, no pause
- **Next task within reach**: straight to LOOKING with new task, no pause
- **Next task needs walking**: COLLECTING → SCANNING → NAVIGATING
- **No more tasks**: COLLECTING → IDLE

### Key integration points

- **PathWalker**: Bot calls `PathWalker.start(path)` for navigation, monitors `PathWalker.isActive()`. Creates its own `CameraController` instance for aiming (separate from PathWalker's camera).
- **MeshManager**: Bot queries `MeshManager.meshes` to find walkable standoff nodes near targets.
- **MeshPathfinder**: Bot uses A* to find paths from player to standoff positions.

## Block Interaction

Mining uses vanilla input pipeline — `options.keyAttack.setDown(true)` while camera aims at block. No direct `gameMode` calls. Break detection via `level.getBlockState(pos).isAir()`.

## Human-Like Behavior

- Spring-damper camera smoothing (5-15 ticks to converge)
- Random aim offset within block face (not dead center)
- Settle delay after aim convergence (2-5 ticks)
- Random inter-task cooldowns (2-30 ticks, occasional long pauses)
- Post-break delays (1-5 ticks)
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
- `collectWaitMin/Max` — ticks to wait for item drops before walking away
- `scanTimeout` — max ticks to look toward next target before walking (30)
- `scanFacingTolerance` — degrees tolerance for scan convergence (15.0)
- `scanRadius` — block scan radius
- `reachDistance` — max mining reach (4.0)
- `maxBreakTicks` — interaction timeout

## Dependencies

- `loader` (compileOnly) — Module interface
- `camera` (compileOnly) — CameraController, AngleUtil
- `pathfinding` (compileOnly) — PathWalker, MeshManager, MeshPathfinder
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

Tests in `test/BotTests.java`, registered via `TestRunner.instance().registerSuite(BotTests.class)`.
Run via `./gradlew runMinecraftTests`. Each test builds its environment with `/fill` + `/setblock`.

Test cases: single block mine, tool selection, tree chop, camera smoothness, out-of-reach failure.

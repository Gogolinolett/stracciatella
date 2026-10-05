# Bot Module

Automation bot that uses pathfinding to navigate and perform tasks like mining ores, chopping trees, and gathering resources — all through simulated input for human-like behavior.

## Layering

Two layers, strictly separated:

1. **Task layer** (`BotController` + `BotTask` queue) — executes one block interaction at a time with human-like camera movement, timing, tool selection and drop collection.
2. **Behavior layer** (`behavior/`) — long-running strategies (strip mining, future farming/building) that decide *what to work on next*. A `BotBehavior` plans by enqueuing tasks and waiting for the controller to go idle; it must never simulate input itself. Registered via `BehaviorRunner.register(...)` (typically from a specialized module's init, e.g. `modules/miner`), started by id, at most one *running* — with one exception, a behavior suspended under a restock (see *Restocking*). `BehaviorRunner.tick` runs before `BotController.tick` so a plan made this tick executes this tick. Stopping flows top-down: `/bot stop` stops the runner (which aborts the behavior) and then the controller — never the other way around.

## Architecture

```
net.stracciatella.bot
├── BotModule.java                  # Entry point (trivial — see BotSetup)
├── BotSetup.java                   # Wiring: config, commands, tick hooks, GUI pages, placer, tests
├── BotController.java              # Static tick-driven state machine (like PathWalker)
├── BotConfig.java                  # GSON-persisted config (bot.json), humanization knobs
├── BotPolicy.java                  # Record: per-behavior safety/collection opt-ins
├── BotCommands.java                # /bot subcommands
├── StorageCommands.java            # /bot storage and /bot server — everything that edits servers.json
├── task/
│   ├── BotTask.java                # Interface: targetPos, interactionType, isComplete
│   ├── TaskQueue.java              # ArrayDeque<BotTask>; pollNearest/peekNearest for human-like routing
│   ├── TaskResult.java             # Record: success, reason, ticksElapsed
│   ├── InteractionType.java        # Enum: ATTACK, USE
│   ├── MineBlockTask.java          # Mine single block (complete when → air)
│   ├── ChopTreeTask.java           # Mine tree logs top-to-bottom (multi-target)
│   ├── PlaceBlockTask.java         # Place a block against a support face (USE)
│   ├── OpenContainerTask.java      # Right-click a chest/barrel and wait for its screen (USE)
│   └── GatherAreaTask.java         # Meta-task: scan + enqueue mine/chop subtasks
├── behavior/
│   ├── BotBehavior.java            # Interface for long-running strategies (id, policy, restockNeeds, start, tick, abort, statusLine)
│   ├── BehaviorStatus.java         # Enum: RUNNING, SUCCEEDED, FAILED
│   ├── BehaviorRunner.java         # Static registry + executor, two slots, owns the policy + stop guards
│   ├── RestockNeeds.java           # Record: a behavior's manifest — trigger and shopping list in one
│   ├── RestockBehavior.java        # BotBehavior: LEAVE_SITE → TO_STORAGE → OPEN → TRANSFER → CLOSE → RETURN
│   └── ContainerTransfer.java      # Decides and clicks one stack at a time in an open container
├── server/
│   ├── ServerSettings.java         # What the bot may do on one server; every default the tame one
│   ├── ServerSettingsStore.java    # stracciatella/servers.json, keyed by server identity
│   └── StorageSite.java            # Record: one storage block, with its dimension
├── gui/
│   ├── IgnoredItemsPage.java       # GuiPage contributed to the gui module: edits ignoredItems on its IdListScreen
│   ├── StoragePage.java            # Which block kinds count as storage, on the same IdListScreen
│   └── ServerPage.java             # This server's exit strategy and placement permission, on SettingsScreen
├── safety/
│   └── BotAlarm.java               # Latches damage / player-attack / teleport events for the runner
├── mixin/
│   ├── ClientPacketListenerMixin.java  # Damage + attack-sound + teleport + block-ack packets
│   └── ClientLevelAccessor.java    # @Invoker for the package-private prediction handler
├── interaction/
│   ├── BlockInteractor.java        # Drives destroy/use via gameMode with an explicit target
│   ├── InventoryHelper.java        # Reads hotbar, selects best tool, counts free slots
│   ├── RoutePlacer.java            # The pathfinder's BlockPlacer, implemented as a PlaceBlockTask
│   └── ServerBlockSync.java        # Highest block-prediction sequence the server has settled
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
| **POSITIONING** | Fine-tune position if not within reach after navigation; walks while the camera (re-initialized from current rotation — PathWalker may have rotated the player) smoothly eases onto the target block. Arrives at `WORK_DISTANCE` (2.0), not at reach — see *The working distance* below — unless the walk stops closing, and after a failed look (`forceApproach`) with no such fallback, because that re-approach exists to change the viewpoint | `positionTimeout` |
| **LOOKING** | `CameraController.aimAt` toward target block face + offset; on tick 1 also `selectBestTool` (carried-item packet runs in parallel with the camera turn) and roll a per-target look-speed. Exit the moment the client's `hitResult` is a `BlockHitResult` whose `getBlockPos()` equals the target — the crosshair touching the block is when a human clicks; no angular convergence required (the camera keeps easing toward its aim point during INTERACTING). A short `preAttackHesitation` (1–3 ticks) is held between the gate firing and the transition; during it the camera keeps aiming and micro-saccades are enabled. Continuing a seam skips the hesitation and keeps the previous camera. | `lookTimeout` |
| **INTERACTING** | Calls `gameMode.startDestroyBlock`/`continueDestroyBlock` on the explicit target and swings the arm each tick like vanilla, polls `isAir()`, maintains camera via `aimAt` — switching the aim to the next queued block once the client already sees this one gone, so the wait for the server ack is spent turning; a fall due into the cell (gravel or sand on the target, or already on its way down its column) holds the phase after the confirmed break until it has landed, and the landed block is the task's next sub-target (see *Falling blocks*) | `maxBreakTicks`, the fall wait bounded on its own (`FALL_WAIT_TICKS`) |
| **COLLECTING** | Walk toward visible drops, gaze following the drop at a capped ground-scan pitch (~38–52°, rolled per phase via `aimCollectGaze`; saccades on; look skipped when the item is nearly underfoot — unstable yaw target); drops more than a block above or below the feet are not chased (`COLLECT_REACH_VERTICAL`), a step down to one is walked at the middle of its cell and a one-block rise on the way back is jumped (`stepUpIfBlocked`); exit once items have been observed and are all picked up | `collectWaitMax` |
| **EATING** | Only under `BotPolicy.autoEat`, and only at a task boundary (`startNextTask`, `continueSeam`): the biggest edible stack in hand, `keyUse` held until the food bar goes up, camera easing on toward its last aim point; then the interrupted transition runs | 60 ticks (`EAT_TIMEOUT_TICKS`) |

**COLLECTING and POSITIONING walk on raw key presses** — no PathWalker under them, unlike NAVIGATING, and no reach/line-of-sight gate, unlike the opportunistic step during INTERACTING. COLLECTING's `walkToward` points the gaze at the drop, holds `keyUp`, and adds `keySprint` past two blocks; direction comes from the gaze and nothing else. A drop lies wherever it rolled, including over the lip of the shaft the bot just dug, so it needs its own floor check: `hasFloorWithinOneBlock` looks `STEP_LOOKAHEAD` (1.0) ahead along the gaze and releases the keys when there is no sturdy face under that cell or one below it. Without it the bot sprinted off its own platform and died of the fall. It is deliberately weaker than `isStandable`, which the opportunistic step uses: requiring head room and a collision-free cell made the bot refuse a drop lying against the face it had just mined, and it then stood still for the whole collect window. Walking into a wall costs nothing — only falling does.

**The drop the walk goes for is the one it can reach on foot, measured per axis.** Vanilla's pickup box is the player box inflated by 1.0 sideways and 0.5 up and down, so a drop is in range only inside it on every axis — `PICKUP_BOX_BELOW` (−0.5) to `PICKUP_BOX_ABOVE` (2.0) relative to the feet, both trimmed by a quarter block the way the horizontal 1.0 is against 1.425 for the server seeing the walk a tick late. The 3-D distance that used to decide this said "walk" for a drop a block below the feet whether there was anywhere to walk to or not: a drop straight down a shaft got the same walk as one in a dip beside the bot — into the shaft wall, with the gaze swinging on the near-zero horizontal offset. A drop with no horizontal offset at all is one the walk has no direction for, so the bot stands and lets the watchdog decide. Drops more than a block above or below the feet are not chased at all (`COLLECT_REACH_VERTICAL` 1.5, down from 4): a step down and a step-up jump back are what a raw-key walk can manage, and every farther drop cost the walk-progress watchdog its full window. Stepping down walks at the **middle of the drop's cell**, not at the drop — a one-wide hole takes a 0.6-wide body only through the middle 0.4 of it, and walked at an item lying 0.2 off centre the body caught the rim of the block next door and stood over the hole, out of reach of everything below and outside the drop's box (the diamond miner's staircase did that three steps in a row and then failed its descent from the lip) — while the gaze stays on the drop, because whether the gaze is held is a question about the drop, and asking it about the cell's middle turned the head at a dip the bot had been walking into without looking. `aimCollectGaze` holds the last aim point once the drop is within about 0.7 blocks horizontally: the yaw to a point almost underfoot flips with every few centimetres the body moves, and the walk keys are expressed against the yaw, so a gaze that swings with every step swings the keys with it, the body turns, the yaw moves again — the spin reported after a drop fell into the shaft beside the bot. With the gaze held, the walk goes by geometry (`walkDirection`, the walk half of `walkToward`).

POSITIONING needs the same refusal, and for a while did not have it. It walks whenever a target is out of reach and pathfinding is skipped — `startNextTask` hands it anything inside `reachDistance + 2.5`, and anything at all when `findStandoffNode` comes back null, which is what a large cavity produces. It holds `keyUp` in view direction, so a target *below* the floor points the walk down into the hole. That is how the chunk miner walked into the cave it was trying to floor: `findSupport` had only the far rim to offer, the rim sat 5.4 blocks out across the cavity, and the bot set off for it in a straight line. `hasFloorAhead` is now asked before `keyUp` goes down in both phases — same lookahead, same tolerance for a one-block step — but the two answer it differently, and that difference cost a run before it was found. COLLECTING refuses the step, because a drop over a hole is worth less than the walk. POSITIONING must not: it is walking because a sight line has to be cleared or a target reached, and standing still fails the task outright. The refusal put the bot through sixty ticks of an occluded approach at the exact position it started — the gaze pointed across the hole it was trying to fill, while the only movement actually available was a slide along a wall onto solid ground. A re-approach crouches instead (below), which is both safer than the refusal and the thing that gets the work done; a walk that is merely out of reach keeps the refusal, because creeping at a third speed cannot close four blocks either. Stopping at the rim leaves the task to fail on the position timeout, which is honest only where nothing can be built: the bot would have to bridge a whole cavity to reach a support on its far side, and bridging reaches one block (below). Where the gap *is* one block, standing still is not honest but useless, and the fix does not belong here — the controller owns the walk and has no blocks of its own. The behavior that owns placements fills the cell before it hands over the task that would walk over it; the chunk miner's `ensureStepToward` does that. It deliberately does **not** borrow `hasFloorWithinOneBlock`, and the reason is worth stating because the first attempt did. That predicate tolerates a one-block drop, correctly: it is answering whether a *step* is survivable. A sweep asking whether a cell is the floor it is *working from* wants a different answer, and taking this one let the bot walk down into a dip, out of line with the slab and every column behind it. Two questions that look alike; only one of them is this one.

**One block up is a jump.** Vanilla's auto-step is 0.6 blocks, so a raw-key walk that met a one-block rise stood against it until the position timeout — after collecting a drop from a block lower down, the bot never came back up. `stepUpIfBlocked` is asked by every raw-key walk except the crouched bridging step (a crouch-jump off a rim is the fall the crouch is there to prevent) and the opportunistic step during a break (which has to keep the target in reach): when the cell at foot height `STEP_UP_LOOKAHEAD` (0.6) ahead is solid, the two above it are free and there is head room over the bot, `keyJump` goes down for exactly one tick — released at the top of the next tick whatever that tick does, so it can never be left down — and the walk, still pressed, carries the body onto the ledge the way vanilla's auto-jump does. From further out the jump lands short of the top, and by the time the body is against the wall the cell ahead at foot height is the wall itself; `STEP_UP_COOLDOWN_TICKS` (10) keeps the decision from re-firing while the jump it just made is still in the air. POSITIONING's walk now goes through the same eight-direction key quantisation as every other walk (`walkToward`), which is also what stops a backwards edge-step key still held from cancelling it into standing still. Measured by `Bot steps up out of a dip` and `Bot collects from a dip and climbs back`.

**The task's own target is never the ledge.** From beside it, a floor block with its head cell open passes every one of those checks, and the chunk miner hands over exactly that at each row turn: the corner's head block has just gone, the floor block under it is the next task, and the occluded approach presses the bot into the corner diagonally short of it until the cell ahead is that block. The bot hopped onto it after the first block of every corner and mined it from on top — logged in two chunk-suite fixtures as a step-up in POSITIONING with the target itself as the cell ahead, and neither fixture asserted anything about it. The walk is going *to* that block, so climbing it is never the way there. `Bot walks up to a corner instead of climbing onto it` stands the bot on the logged spot instead of letting it walk there, because a tenth of a block further along the face is already in sight and nothing walks; against the old step-up it fails with `left the floor by 0.75 blocks`.

### The working distance: reach is a limit, not a place to stand

`reachDistance` (4.0) used to answer two different questions — *can the bot touch
this block* and *where should the bot stand to mine it* — and it is only an honest
answer to the first. Arriving at 3.9 means the next cell of a sweep is at 4.9, out
of reach, so every single block costs a fresh POSITIONING entry with its reaction
beat instead of continuing the seam. `WORK_DISTANCE` (2.0) is the second answer,
and POSITIONING walks to it; from there the next two cells are still in reach and
the seam runs.

**Nothing pulled the bot in before except an accident**, which is why this went
unnoticed for so long: the COLLECTING walk to the drop it had just made ended up
at the working face, so the bot was dragged to where it should have been standing
anyway. Put cobblestone and dirt on `ignoredItems` and that accident stops
happening — the list quite correctly stops the bot chasing drops, and with it the
only thing that was ever closing the distance. Measured over 292 breaks of a real
chunk-miner run (`BREAKWIRE` lines carry the distance): a block broken from under
2.5 blocks was followed by the next break after **0.48 s**, one broken from over
3.0 after **1.70 s** — the same sweep, the same rows, 3.5× the time, with every
long gap showing `dist=3,5…3,8` and `invDelta=0`, and only a third of all drops
ever landing in the vanilla pickup box.

`closingIn` is the other half, and without it the change would be a regression
rather than a fix. Some targets cannot be approached at all — one across a gap the
floor check refuses to step into (correctly: the alternative is a fall), one
straight overhead, where no heading shortens the distance — and for those the edge
of reach really is the best place on offer. When the distance stops falling for
`CLOSING_STALL_TICKS` (10), reach is accepted. Told only to close to 2.0, the phase
would hold such a target for the whole `positionTimeout` (60) and then mine it
anyway, so getting this wrong costs ticks on every such block rather than a
failure — the kind of bug that hides.

The state behind it is armed in `transitionTo`, not lazily on first use, and that
distinction cost a run: `closingIn` is only *called* once the bot is in reach,
which is never the phase's first tick, so a reset keyed on `phaseTicks` never ran.
The counter then arrived carrying whatever the previous task had left in it and
answered "stop trying" before the walk had taken a step — the bot started breaking
from 3.67 blocks, the exact distance it was supposed to have stopped using, and the
new test caught it.

**The suite cannot show the gain, and that is worth knowing before trying to
measure it there.** Every fixture collects its drops, so the bot was already being
dragged to the face in all of them; the chunk miner's pace numbers are unchanged by
this (16.5 / 13.8 / 13.8 per block, the same as before). The gain only exists where
drops are *not* collected, and the suite had no such case — which is also why the
two tests for this assert the **distance at the break** rather than a pace.

**The same walk, while the break runs.** POSITIONING happens *before* a break, so
a target that was already in reach when the task started never goes through it at
all — that is every continuation of a seam and every `isWithinReach` hand-off
straight to LOOKING — and the bot mined those from wherever it happened to be
standing. `stepTowardWork` closes that: during INTERACTING the bot walks at the
target column until `CONTACT_DISTANCE` (1.0), using the same 8-direction
view-relative quantization the opportunistic strafe uses, so the body moves
without the camera leaving the block. It is the last of three claimants on the
movement keys in a tick — never while placing, then opportunistic collection (a
drop beside the bot is worth more than half a block of approach), then this;
whichever declines hands the tick on, and if all three decline the keys are
released. The next block of a sweep is then already closer than reach when its
task starts, which is the whole point: the time the bot is *not* mining is the
time it spends arriving.

**Walking up and stopping dead is not what a person does.** This walk ended at
`WORK_DISTANCE` for a while, on the argument that closer bought nothing — and
that is exactly what looked mechanical from outside: the bot arrived, stopped at
a distance nobody would have picked, and swung at arm's length. Someone mining
who has nothing to pick up simply keeps walking into the block. `CONTACT_DISTANCE`
is not a standing distance and is not tuned to be one: a 0.6-wide body flush
against a face stands 0.8 from its centre, so the walk is still pressed at 1.0,
and what actually ends the approach is `isStepSafe` refusing the step that would
put the feet in the target's own cell. It only keeps out the degenerate case of a
block underfoot, where the `atan2` of a few centimetres is an arbitrary heading —
the same geometry `aimPoint` has to special-case. `WORK_DISTANCE` (2.0) is
untouched and still POSITIONING's arrival: that phase walks *before* the break
and has no face to stand against yet.

**Measured horizontally, in both places.** The 3-D eye→centre distance is the
right answer to *can I reach this* and the wrong one to *should I walk*: mining
the block under its own feet, the bot read 3.12 — nearly all of it the 1.1-block
eye offset — stepped off on an `atan2` of a few centimetres of horizontal offset
and wandered 3008.71 → 3008.57 → 3008.48 with its raycast on the block beneath
it, which is how the chunk miner's staircase test started failing with `cannot
break`. `horizontalDistanceTo` measures the only distance a walk can change. The
3-D measure stays for `forceApproach`, where the question really is reach.

### Bridging: the placement that can only be made from the edge

A block is placed on the side of an existing block, and one case of that cannot be aimed at from anywhere the bot would normally stand: a **vertical support face below the eye**. Every ray from an eye still horizontally over the block meets the block's own *top* face first, so the crosshair lands on the right block with the wrong `getDirection()` and the placement gate holds until `lookTimeout` — `face=east … hitResult=… (up)` in the log. It is the block the bot is standing on hiding its own side, and no re-approach fixes it: `approachOccluded` clips to the support block itself, and a ray that ends on the target counts as clear however the bot is standing on it. Which is why `needsEdgeStep` is asked *before* the occlusion rule in POSITIONING, not after.

The move that works is the one a player bridges with, and the bot now makes it: **turn your back on the gap, crouch, walk backwards until the edge of the block underfoot comes into view, place**. The crouch is both halves of it. Vanilla's `maybeBackOffFromEdge` clips a crouched walk to positions where the bounding box still has support under it, so the bot cannot step off — and the same clip lets its centre, hence its eye, travel about 0.3 blocks *past* the rim, which is exactly where the side face stops being hidden. `EDGE_STEP_CLEARANCE` (0.1) is how far past the plane counts as arrived, well inside what the clip allows.

Three pieces, all in `BotController`:

- `needsEdgeStep` decides it, on geometry alone: a horizontal `preferredFace()`, an eye above the support's top, the eye still on the near side of the face plane, and the support already in reach. Mining never asks: `preferredFace()` is null unless a task pins one, and only a placement does. The same predicate answers "is the step still owed" and "has the step arrived", so POSITIONING has no second threshold to keep in sync.
- POSITIONING aims at the **face**, not the block centre, whenever the task pins one — everywhere except the edge step itself, where it aims *behind* the bot instead and walks it backwards into the gap. That is what a player does, and it is not decoration: the face centre is in front of the eye while the bot is short of the plane and behind it the moment the eye clears it, so aiming at the face swings the camera a half-turn on arrival and the next placement swings it back. Two half-turns per block laid, reported as a bot that turns towards its next target after every one. The gaze parked behind the bot (`BRIDGE_LOOK_BACK`) is already the yaw the placement needs and does not move again until the bridge changes direction. The walk that follows from it is a backwards one, so it goes through `walkToward` — the same eight key directions the opportunistic collect quantizes into — and the floor check has to be asked about the gap rather than the bridge behind, which is what `hasFloorToward` is for. The crouch stands in for that check while it is held: it is the stronger refusal, and the only one that also gets the work done. **And it aims at the support's own column, not along the face axis** — the same distinction the miner's sweep had to learn between reach and footing. The rim this step needs is the far edge of the support itself, and a bot one row off that column has no such edge underfoot: walking the face axis from there tracks along a row that was never bridged, and vanilla's crouch — correctly — parks the body on the last sliver of whatever it is standing on. It then holds that spot for the whole of POSITIONING, because the walk it is being given cannot reach the plane from that row at all. Logged from a real world with the bot one row off the bridge it had just laid: `pastFace` frozen at −0.71, −0.70, −0.70 at ticks 5, 20 and 40, feet at −24/92/−64 against a support at −24/91/−63, `under=air` with `onGround`, and the back key held the entire time. Aiming at the support's centre plus `EDGE_STEP_OVERHANG` (0.8 — past the face plane by the same margin the crouch allows a body to overhang) closes the sideways offset first and turns into the rim step itself once the bot is over the column.
- `updateBridging` holds the key, ahead of the phase and pause gates because a bot on a lip has to keep crouching whatever the state machine is doing. It goes down when the **rim is close** — `atEdgeStepRim`, an edge step owed *and* the eye within `EDGE_STEP_APPROACH` (1.5) of the face plane — not merely when one is owed. Those were one question for a while, and reach is four blocks, so the bot went into the crouch four blocks out and creeped the whole approach at a third of walking speed. The crouch is not the approach, it is the last of it. 1.5 is not a taste: the floor check refuses the step from one block out (`STEP_LOOKAHEAD`), so the crouch is always already down by the time the ground runs out. It comes back up **only once the bot has ground under it again**, not when the task ends: standing over the hole it just tried to fill, a released crouch plus the last of the walk's momentum is the fall the crouch was there to prevent. A successful placement clears it by definition — the gap it was standing over is now floor. `sneakKeyHeld` mirrors the last write so the key is only ever touched on a change; driving it false every tick would take crouching away from the player for as long as the module is loaded.

Not behind a policy flag, unlike `approachOccluded` and the rest. Those are trade-offs — a short walk against a turn, safety against pace — and their owner has to choose. This one has no other side: without it the placement is not slower or riskier, it is impossible, and the alternative is a task that can only time out. Measured: `Bot bridges into a gap` crouches out to x=1801.16 over a four-deep cavity and places; on the same fixture without it the bot stops at x=1800.74, never crouches, and the cell stays air.

**The suite could not have caught that offset, and now can.** Every bridging fixture in the suite places from a spot the bot never leaves — measured across the whole of it, the support sat 2.2 blocks away in *every* bridging placement, always dead ahead on the face axis, so the perpendicular offset the bug lives in was never once non-zero. Catching it takes a bridge long enough that the bot has to walk out along its own: the miner's `Chunk miner steps onto its bridge instead of walking beside it` puts the target column diagonally across a pit, and reverted to the face-axis walk it fails with `could not place the floor at 3013, 39, 3015 — 2 attempts failed`.

### Smart transitions after block break

- **More sub-targets** (tree logs): straight to LOOKING, no pause
- **Next task within reach**: straight to LOOKING with new task, no pause

Those first two go through `continueSeam()`, and "no pause" is meant literally: no reaction beat, no pre-attack hesitation, and LOOKING keeps the camera it already has instead of building a fresh one. All three model a person noticing a result, deciding, and committing — the beats *between* separate acts. Digging a corridor is one act: the hand stays on the button and sweeps the crosshair over. Rebuilding the camera is the same mistake in the other direction, since it zeroes the spring's angular velocity and restarts every sweep from a standstill.

For a long time the code did not match this: both paths went through `scheduleAction`, which drew the ~4-tick reaction delay. It was invisible in every phase measurement, because `phaseTicks` does not advance while a deferred action is pending. Measured on the chunk miner's corridor, removing the three (with the miner's per-column breather, below) took a two-block column from ~50 ticks to ~37 and raised the share of time actually spent breaking from 60% to 81%. The beats stay on every path that *is* a separate act — after a walk, a scan, or a collect.
- **Next task needs walking**: COLLECTING → SCANNING → NAVIGATING
- **No more tasks**: COLLECTING → IDLE

COLLECTING always leaves through IDLE and lets `startNextTask` take it from there — it already polls the nearest task, resets the per-task state (`forceApproach` included) and skips SCANNING for a target in reach. The queued-task branch that used to sit in the exit was the same logic written out a second time, minus that reset.

### Task selection: nearest from current position

Every place that takes the next task from the queue (`startNextTask`, the within-reach continuation after a break, the COLLECTING walk-flow exit) uses `TaskQueue.pollNearest(player.blockPosition())` — the task whose target is closest to where the bot currently stands, ties keeping insertion order. A human works an area closest-first from wherever they are; replaying the scanner's fixed order produces visible zigzag routes. Single explicitly-issued commands are unaffected (one-element queue).

### Reaction delays don't freeze the camera

While a deferred-action reaction delay is in flight, movement keys are released but the camera keeps easing toward its last aim point (recorded by `aimCameraAt`) and keeps saccading. A spring-damper camera frozen mid-swing for 1–10 ticks reads as stop-motion — most visible at SCANNING→NAVIGATING, where the 15° tolerance fires while the camera still has angular velocity.

### COLLECTING exit conditions

Exit gates depend on whether another walked task is queued:

- **Single task / nothing queued** — three conditions must all hold:
  1. `itemsSeenThisCollect` — at least one tick observed items in the 8-block AABB, or the main inventory has grown since the break started (the drop was inhaled before any tick could see it). Closes the server→client spawn-sync race where the block has broken but the drop entity hasn't synced.
  2. `!itemsNearby` — currently no visible drops.
  3. `phaseTicks > lastItemSeenTick + CONFIG.itemAbsenceTicks` (default 60) — sustained absence window. Avoids exiting on a transient absence tick between sequentially picking up multiple drops.
- **Another task queued** — only conditions 1 and 2 are required; the sustained-absence wait is skipped. Straggler drops are picked up via the vanilla pickup box during the `NAVIGATING` walk toward the next target. Eliminates the visible "pause after pickup" in multi-task flows.
- **`fastCollectExit` opted in** — likewise conditions 1 and 2 only. A behavior that plans one block at a time never has a task queued at this point (it plans the next one only once the controller is idle), so the clause above can never fire for it and the 60-tick window is paid for *every block*: measured on the chunk miner, ~20 ticks of work per block against ~90 elapsed.
There is no fourth exit that leaves before conditions 1 and 2 with drops still on the ground. One existed — a hand-off, taken when `fastCollectExit` and `opportunisticCollection` were both set and a task was queued — on the promise that the opportunistic strafe would collect those drops during the next break. It is gone: the strafe is a nudge, not a walk (measured closing 1.9 blocks to 1.6 over an entire break, against a 1.425 pickup box), and when it fell short nothing ever came back for the drop. It also bought nothing, because the case it was meant to speed up is the one condition 2 already leaves on tick 1 — vanilla pickup inhales the drop during the break, so the inventory has grown and no item is left nearby.

`itemsNearby` is measured on the same filtered set the walk uses, not on the raw 8-block query: an item more than 4 blocks above or below, or one the bot has walked at for `itemAbsenceTicks` without getting closer, is one it has decided never to approach. Counting those as "nearby" pins the flag true forever and the phase burns the full `collectWaitMax` — 1200 ticks of the bot standing still, which reads as it having quit. The give-up takes no opt-in: it applies to plain `/bot` tasks as much as to behaviors, because sixty ticks of walking without getting closer means the same thing whoever queued the task.

Nothing is walked at before it has been seen. A blind walk toward `lastMinedPos` used to run while no drop had synced yet, so the bot would be inside the pickup box the moment the spawn arrived (STR-026). The working distance removed its premise — the break now ends about two blocks from the block, and the walk above closes that in roughly three ticks — and what was left was the one walk in this phase with no entity to measure, no progress watchdog and no step safety, which under load covered a full block of ground and carried the bot into the pickup box of a drop on the ignore list. `collectWaitMax` (400 accel ticks) is the hard timeout for drops that never become reachable.

**Ignored drops** (`ignoredItems` in the config, edited by `/bot ignore` or the gui page) are drops the bot neither walks to nor waits for. The candidate loop skips them, so they are never `nearest` and never count as nearby — but a sighting still sets `itemsSeenThisCollect`, because the break did produce its drop and that is all the exit gate's first condition asks. The phase therefore ends the way a clean pickup would: at once with a task queued or `fastCollectExit`, after the absence window otherwise. The list is global, not per behavior: which drops are junk is a property of the world the player is in, not of the strategy. It decides only where the bot goes, never what it keeps — a drop that lands inside the vanilla pickup box is taken in passing, and the opportunistic strafe skips the same list. The break confirmation keeps counting every drop: an ignored drop is still proof that the block broke.

**Standing still is not out of range**, and the working distance is what makes the difference visible. The bot now finishes its break about 1.7 blocks from the block it broke, and a drop is not a point on that block: it spawns up to a quarter block off centre and then pops and slides. Measured on one fixture over ten runs — a stone block mined from three blocks out, cobblestone ignored, the bot never walking at it — the final bot-to-drop distance ranged from **1.62 to 4.41 blocks**, so the drift alone spans more than the 1.425-block pickup box. Whether vanilla inhales an ignored drop is therefore not something the client decides; only *where the bot goes* is. The test follows: it summons a `NoGravity` cobblestone four blocks off on the bot's other side — inside the collect query, inside the vertical walk filter, and collectable, so the list is the only reason it is still lying there at the end — and asserts on *that*. The mined stone's own cobblestone still carries the other half, that an ignored drop in plain sight must not pin `itemsNearby` and hold the phase to `collectWaitMax`; whether vanilla inhaled that one in passing is deliberately not asserted.

**The walk stops at 1.0 blocks, not 1.5.** Vanilla pickup is a box, not a radius: `Player.touch` queries `getBoundingBox().inflate(1.0, 0.5, 1.0)` and takes every item whose own box intersects it — 1.425 centre-to-centre on each horizontal axis for a 0.6-wide player and a 0.25-wide item. Stopping at 1.5 parked the bot *outside* that on a straight-ahead approach, so whether a drop was collected came down to how far the walk's momentum carried past the threshold. When it didn't, the bot stood over an item it could not reach for the whole `collectWaitMax`: that is the `Bot mine ore vein` timeout, which leaves all three drops on the ground and predates the behaviour layer (same fingerprint in archived runs from June).

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

On top of the jitter the aim point slides across the face toward the **next** queued target (`taskQueue.peekNearest`), by `AIM_LOOKAHEAD_BIAS` (0.3) per perpendicular axis. Mining a 2-high column the two blocks sit one above the other, and aiming at each face's centre swings the head through the whole angle between them; aiming near their shared edge makes the switch a few degrees, which is what someone digging a corridor actually does — they look at the seam. The bias is clamped to keep `AIM_EDGE_MARGIN` (0.15) of face between the aim point and the rim, because the raycast still has to land on *this* block: past the edge it catches the neighbour and the hit-result gate never fires. Note this buys **no measurable time** — LOOKING ends on raycast contact, not on camera convergence, and measured at 4.9 ticks mean with and without. It is a humanness/camera-motion change, not a throughput one.

**A face pointing up or down with the eye over it is the exception** (`aimPoint`). There the horizontal offset from the eye to the aim point is nothing but the jitter, and the atan2 of a few centimetres is a yaw in an arbitrary compass direction — rolled afresh for every block, so a bot digging straight down turned to a new random heading per block, up to a half turn at 35°/tick. Reported as wild spinning on the way down. Any yaw looks at a block underfoot as long as the pitch is steep enough, so the aim point is laid out from the eye along the yaw the bot already has; the jitter keeps its magnitude and moves into the pitch, shortened where that line would leave the face's margin and never below `aimOffsetMin`, which the margin has room for. From beside the block the geometry is the ordinary one and the ordinary rule applies. Measured by `Bot digs down without turning`.

**A point on the face is not a point in sight** (`visibleAimPoint`). `aimPoint` is geometry on the face alone. At the chunk miner's row turns the floor block of the new row's first column shows its top face with the head block beside it hiding the half nearer the bot — and that head block is the next task, so the lean moves the aim point into exactly that half, and the jitter alone does so more than a third of the time from where the bot stands there. The camera settled on the head block, the line-of-sight check to the face centre said clear, and the task spent two look timeouts and failed: reported as the bot pausing for seconds at the start of a run. LOOKING therefore keeps the humanized point only while it, and `AIM_CLEARANCE` (0.1) of face around it, are in sight; otherwise it aims at the nearest of the face centre and the eight points `AIM_EDGE_MARGIN` in from the rim that is. A point in sight without that margin is taken when nothing better is, and when nothing is in sight at all the humanized point stands and the occlusion approach and the look timeout deal with it as before. Only LOOKING asks — `hasLineOfSight` still clips to the face centre, so when the controller approaches is unchanged. Measured by the miner's `Chunk miner aims around the next block at a row turn`.

### Mining

Mining does **not** hold `options.keyAttack`. `BlockInteractor` calls `mc.gameMode.startDestroyBlock` / `continueDestroyBlock` directly with the task's target position, from a START_CLIENT_TICK hook. That is deliberate: `Minecraft.continueAttack` mines whatever `mc.hitResult` points at, so a held key would chew through everything the crosshair crosses during a camera sweep — instant-break blocks and blacklisted ones included — and a single tick of the crosshair leaving the block calls `stopDestroyBlock()`, which wipes the break progress. An explicit target has neither failure mode.

The catch is that `continueAttack` is also where vanilla does everything *besides* the game-mode call, and driving the game mode alone reproduced none of it: `player.swing(MAIN_HAND)` and `level.addBreakingBlockEffect(pos, face)` live only there, and `LocalPlayer.swing` is what sends `ServerboundSwingPacket`. The bot broke blocks with a motionless arm and without one swing packet — to the server and to anyone watching, a player whose blocks dissolve while he stands still. `BlockInteractor` now swings on every tick the game-mode call returns true, which is vanilla's own guard; vanilla really does send 20 swing packets a second while mining.

The particle call is deliberately **not** mirrored. It only adds the chips flying off the face — the crack overlay that reads as "being mined" comes from `continueDestroyBlock` via `destroyBlockProgress` — and it is local cosmetics that reach neither the server nor another player, at the price of a shape query and a particle allocation on every tick of every break. That price is real: with it in, the client fell far enough behind that item spawns synced too late for COLLECTING and the chunk miner left the corridor's cobblestone on the ground. Measured, not guessed — the corridor test failed the full suite with it and passed without, everything else unchanged.

**The bot's packets have to be a player's packets**, and `Minecraft.continueAttack(boolean)` is where that is won or lost. With the button held it drives `continueDestroyBlock`; with the button up it falls through to `stopDestroyBlock()`, which sends an `ABORT_DESTROY_BLOCK` for whatever is being destroyed and zeroes the client's own progress. The bot drives the game mode itself with no key down, so vanilla aborted every break on the tick after it started — the wire read START → ABORT → silence → STOP where a player sends START → silence → STOP, one packet no player ever sends, on every block. `MinecraftMixin` suppresses `continueAttack` while `BlockInteractor.isMining()`: the bot *is* the held button. Redirecting to `continueAttack(true)` instead was rejected — that branch breaks whatever `Minecraft.hitResult` names, which is the stale-aim problem the explicit target exists to avoid. Measured across the bot and chunk suites: 105 aborts down to 3–5, and the survivors are player-equivalent (vanilla's own abort-then-restart inside `startDestroyBlock`, and `stopInteraction` on a cancelled task, which is what releasing the button does).

The same rule killed the carried-item spam. `InventoryHelper.equip` used to send `ServerboundSetCarriedItemPacket` unconditionally on every tool selection — "small and idempotent" — which is one packet per mined block against a player's one per actual scroll; `BotController` additionally re-sent it every tick of LOOKING and again at INTERACTING entry, both as insurance against a "dropped" packet, which TCP does not do. All three are gone: vanilla's `ensureHasSentCarriedItem` sits at the head of `continueDestroyBlock`, `useItemOn`, `useItem` and `attack` and sends exactly when the slot differs from the one last sent, landing on the break's second tick — the same tick a player who scrolls and then clicks gets it on. Measured: 119 of these packets across 100 breaks, down to 8, the number of real tool switches.

Per block the wire now reads START → n ticks of silence → STOP. The silence is normal — survival sends no continue packets. **Before adding any packet here, check what a player's client sends in the same situation.** The tracer that established all of the above — `BlockWireTrace` plus a `ClientCommonPacketListenerImpl.send` mixin, one WARN per outgoing action, swing, block update and ack — is deliberately **not** in the tree: it logs on every packet of every break, which buries a run log and slows the accelerated suite. It is in git history (`git log --diff-filter=D -- '*BlockWireTrace.java'`) and is meant to be brought back for the length of an investigation and taken out again with the fix.

`sameDestroyTarget` is also why **the attack branch stops driving the game mode the moment the client's own world says the target is air**. It compares position *and* the held stack with `ItemStack.isSameItemSameComponents`, and durability is a data component — so the damage the server syncs back for the block just broken fails the comparison, and `continueDestroyBlock` then routes into `startDestroyBlock`, which puts a fresh `START_DESTROY_BLOCK` on the wire with no air check in front of it. Locally that packet dies in the server's own air check, which is why it hid for so long. Over a network, against a server that still has the block standing, `START` resets `destroyProgressStart` unconditionally, the following `STOP` therefore misses the 0.7 progress gate, and a destroy still in progress is answered with the block's real state — reverting the client's prediction. Measured on a live server: eight reverts inside one interaction and the block reported unbreakable. Measured in the suite: 56 STARTs against 39 STOPs before, 105 against 102 after, and the "START right after an air state for the same block" signature from 14 down to none.

The five delay ticks still have to be spent, so `airDelayTicks` counts them down over the confirmation wait and then leaves the game mode alone; `releaseAfterBreak` carries the remainder rather than arming a fresh five, which would spend the delay twice and land the second five past the early-out on the same fall-through. The counter has three states on purpose — "break still running" (-1), "ticks owed", "spent" — because two cannot tell *spent* from *not armed yet*, and a first attempt that reused `cooldownPos` for it re-armed every sixth tick and changed nothing at all.

Break detection has a fast path and a fallback. The fast path is the **server acknowledgement**: breaking is a sequenced client prediction, and `ClientboundBlockChangedAckPacket` carries the sequence the server has settled — after which `endPredictionsUpTo` has reverted anything the server disagreed with, so the client's view of that block *is* the server's view. `ServerBlockSync` (fed by the packet mixin) tracks the highest acked sequence; INTERACTING samples `BlockStatePredictionHandler.currentSequence()` the first tick the target looks finished and exits as soon as that sequence is settled. `ClientLevel.getBlockStatePredictionHandler()` is package-private, hence the `ClientLevelAccessor` `@Invoker`.

That replaces an eight-tick wait on **every** block — roughly a third of the whole mining loop, measured on the chunk suite (INTERACTING 15.7 ticks per block, ~8 of them this window). The window below is what the ack made unnecessary: it was an approximation of exactly the fact the ack states outright. It stays as the fallback for a connection that never acks, so the bot keeps working instead of burning `maxBreakTicks`.

Whatever is left of that wait is spent **aiming at the next block, not at the hole**. From the tick the client's own world says the target is gone, INTERACTING points the camera at the next queued target instead of the finished one. Nothing is lost by it: `continueDestroyBlock` returns false on air so the arm has already stopped, and the confirmation reads block state, the drop entity, the inventory and the ack sequence — no camera angle enters into it. A person's mouse is travelling while the block pops; standing in the finished hole until the ack lands pays LOOKING's turn *after* the wait instead of through it. Gated on `isFullyComplete()` so a multi-target task keeps its aim: a tree's next log is a sub-target, not a queue entry, and turning to a queued task there would be a wrong turn rather than an early one. Also gated on the next target being an ATTACK within reach — the same condition `continueSeam` uses, so the bot only pre-aims at a block it is actually about to take. Measured on the chunk suite at 1x: LOOKING 3.5 → 1.5 ticks per block, `mines without stalling` 20.7 → 15.5, and `holds its aim` finished all 20 blocks where it had been managing 16-18.

The fallback requires TWO authoritative signals before considering the block broken:

1. **Sustained-air window**: `level.getBlockState(pos).isAir()` for `CONFIG.airConfirmTicks` consecutive ticks (default 8). `getBlockState` returns the client's view which can be a sequenced-transaction prediction; the sustained window tolerates normal server-confirmation delay.
2. **A break artifact** — either a drop entity observed within a 5×5×5 AABB around the target at any point since the break started (polled every tick and **latched**: standing next to the block, vanilla pickup inhales the drop within a tick of spawning), or growth of the main-inventory item count since break start (the pickup itself, server-driven via slot sync). Both only exist if the server completed the break.

**Why the artifact check is necessary**: the client's block prediction can remain "air" long enough to pass the sustained-air window even when the server ultimately never broke the block (it reverts the client's prediction later). We observed `lastMinedPos state=iron_ore` at COLLECTING exit — the block had reverted to iron_ore on the client, proving the server never actually broke it. Without the artifact check, the bot falsely "confirms" breaks on predictions the server rejects, resulting in missing drops. The inventory-growth path exists because a once-after-air query missed instantly-inhaled drops and left the bot punching the already-broken block until `maxBreakTicks`.

`airConfirmTicks` resets to 0 whenever the block is observed non-air. `maxBreakTicks` (default 100 = 5 s) caps INTERACTING. It deliberately does **not** cover the slowest legitimate break: a break costs `30 × hardness / tool speed` ticks, so obsidian with a bare diamond pickaxe needs 188 and ancient debris 113 — those only fit with Efficiency on the pickaxe, and without it they time out. The bar is set for reporting a refused break fast rather than for the hardest block in the game. **No test covers that trade.** `Bot policy opportunistic collection` is the only test that mines obsidian, and it asserts on the walk *during* the break and then stops the behavior — the break never completes, so the suite stayed 25/25 green when the cap went from 400 to 100. Lowering it further is therefore unguarded: the suite cannot tell a fail-fast from a bot that can no longer mine.

`InventoryHelper.selectBestTool` scans the full main inventory (slots 0–35, i.e. hotbar + storage rows). If the best tool sits in storage (slot ≥ 9) it is swapped into the currently selected hotbar slot via a `ClickType.SWAP` container-click packet — a human player would do the same. A hotbar-only search would silently fall back to bare hand and drop nothing from ores.

It then sends `ServerboundSetCarriedItemPacket` after `setSelectedSlot` so the server's held-item state matches the client's. Without the packet sync, server-side drops are computed with the wrong tool (e.g. iron_ore mined with server-side bare hand drops nothing).

Tool selection now happens in LOOKING (tick 1), not INTERACTING. The carried-item (and any inventory-swap) packet travels to the server during the smooth camera turn — by the time the hit-result gate fires, the server has had the full LOOKING duration to apply the slot change. INTERACTING then attacks on its first tick after a single defensive `resendCarriedItem` call, with no tool-settle wait. `LOOKING` re-resends the carried-item packet each subsequent tick as a dropped-packet safety net.

A short `preAttackHesitation` (default 1–3 ticks) is held between the LOOKING gate firing and the transition to INTERACTING. This is **not** the old `settleDelay` (a timing buffer for the server) — packet sync is already done by parallel tool selection above. The hesitation is purely humanness: the visible "I see it, I click" beat between locking on and clicking. Micro-saccades enable at the start of the hesitation so the gaze trembles slightly during the commit moment.

### Falling blocks

A block that falls — gravel or sand resting on the target, concrete powder, an anvil — drops into the cell the moment the server opens it, and a task that had latched "air" at that point walked away from a cell that was full again two ticks later. The chunk miner's first layer met exactly that, gravel from the world above the slab, and handled the fallen block as a failed break of the head block — a step retry each time, and the run died on the column after two of them. So `MineBlockTask` is not "mine the block that is there" but "make this position free": a block standing in the cell again after a confirmed break is the task's *next sub-target*, the way the next log is for a tree, and the controller re-aims, picks the tool for it and breaks it with a fresh budget. `MAX_FALLS` (32, taller than any natural column) stops a cell that refills for some other reason from being broken forever; the task then reports neither a next target nor completion, and the controller fails it (`Cell keeps refilling`).

The wait is the controller's. `fallIncoming` is polled from the first INTERACTING tick and latched in `fallExpected`, because the block above turns into the entity and the entity into the block below inside single ticks, and a check that looks only once lands between two of them. Only the column directly above matters — a falling block never moves sideways — and only the block resting on the cell can start a fall, since it is the loss of support that triggers one; falling-block entities are searched `FALL_COLUMN_HEIGHT` (32) up the column, anything starting higher lands long after the wait has given up. Once the break is confirmed with a fall expected, the interaction is released and the phase idles with the camera on the cell (`awaitingFall`): `FALL_GRACE_TICKS` (4) are spent regardless of what is visible, because the fall is scheduled two server ticks after the break, then the wait goes on while something is still coming down, up to `FALL_WAIT_TICKS` (40) for a column that fell straight through because the cell below was open too. The break budget does not run during the wait — a slow but finished break must not turn into a timeout. `finishBreak` then takes the settled cell where the inline code used to take the air, and a fall expected also holds the early aim switch to the next block, which would otherwise look away from a cell that is about to need looking at.

### Placing (`PlaceBlockTask`, `InteractionType.USE`)

Placement is **not** the mirror image of mining. You cannot aim at the position where the block should go — a raycast passes straight through it — so a block is placed on the side of an existing block you click. `PlaceBlockTask` therefore takes a **support** block adjacent to the destination, and `targetPos()` returns the *support*, not the destination. The entire LOOKING pipeline (aim, hit-result gate, re-approach, timeouts) then works unchanged; `placePos()` exposes the destination for callers. `PlaceBlockTask.findSupport(level, pos, eye)` picks a neighbour whose face toward `pos` is sturdy (`BlockState.isFaceSturdy`), returning null only when nothing adjacent is sturdy at all — the caller should then skip it instead of enqueuing a task that can only time out.

**Sturdiness alone is not enough, and taking the first sturdy face was a bug.** `Direction.values()` starts DOWN, UP, so a gap in the floor almost always resolved to the block *above* it, whose face toward the gap is its underside. A bot standing beside the gap can never see an underside: the raycast lands on that block's side face instead, the placement gate additionally requires `getDirection()` to match, and the task burns its look timeout. Three of those and the chunk miner stopped with `could not place the floor at … — out of filler blocks?` while the hotbar was full of them (`face=down … hitResult=… (east)` in the log is the fingerprint). `findSupport` now ranks candidates by how directly the placement face points at the eye, which produces what a person picks: the top of the block *under* the gap, otherwise the side face of the block on the *far* side of it, turned back toward the bot. Visibility ranks the candidates but does not filter them — a face invisible from here may be visible two steps later, and LOOKING's re-approach exists for that. The miner threads the player through `ensureFloor`/`ensureSafeDrop`/`handleLiquidsAround`/`planPlacement`/`verifyPlacement` to supply the eye.

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
| `fastCollectExit` | Closes the COLLECTING stalls below: leaves as soon as the ground is clear, without waiting out the absence window |
| `orderedTasks` | Take queued tasks in insertion order instead of nearest-first |
| `approachOccluded` | Step closer to a target that is in reach but hidden, instead of staring until the look times out |
| `autoEat` | Eat between two tasks once the food bar is under `eatBelowFoodLevel` (below) |

### `autoEat`: a meal between tasks

A behavior that runs unattended for an hour stops regenerating on an empty food bar and, at the bottom of it, stops sprinting — with nobody watching. With the flag on, every task boundary asks whether the bar is under `eatBelowFoodLevel` (default 14, the level above which health still regenerates): `startNextTask` before the task it has just taken begins, and `continueSeam` before the next block of a seam. Never inside a task — not mid-break, not mid-walk — because that is where a person's hand is on the button; a seam is still a boundary between two blocks and the hand is off the button there anyway. The task is already taken while the bot eats, so `isActive()` stays true and a behavior waiting for the controller waits through the meal.

The meal is the one a player has: the biggest stack of anything with a food component is selected the way a filler is (`InventoryHelper.selectItem`), except for a fixed list that is edible but not a meal — raw chicken, rotten flesh, pufferfish, spider eye, poisonous potato, chorus fruit, golden and enchanted golden apples, suspicious stew — and `keyUse` is **held**, not clicked. Vanilla's `handleKeybinds` starts a use for a held button and releases the item on the tick the button comes up, so the key is the whole mechanism and every packet on the wire is a player's; a direct `gameMode.useItem` would need its own release handling and would be the second path to the same thing. The phase ends when the food bar goes up, the server's word that the meal happened: the client never finishes a use itself (completion is server-only), and while the button is held vanilla re-arms the next bite in the tick the synced stop flag lands, so the hand never shows a gap an end-of-tick hook could catch — the first version waited for exactly that gap and ran every meal into its timeout. Letting go on the bar cuts the second bite short; bounded at 60 ticks (a meal is 32, a stew 40). Movement keys are released; the camera keeps easing toward its last aim point with saccades on, because a gaze frozen for the length of a meal reads as a bot. `pause` and `stop` let go of the key — the meal is abandoned, and resumed from the start after a pause.

Nothing to eat is not a reason to stop: the run carries on hungry and says so **once per run**, a yellow chat line and one low `NOTE_BLOCK_BASS` at pitch 0.5, deliberately nothing like the plings that mean the run has ended. A meal that never started within the bound is remembered the same way for the rest of the run, so a bot that cannot eat does not stand with the button held at every task. Both latches reset in `setPolicy`, i.e. when a behavior starts.

Opt-in like the rest: a plain `/bot mine` or the diamond miner must not start eating the player's food. The chunk miner asks for it.

### `orderedTasks`: when nearest-first is wrong

Nearest-first is the right default and stays it — a person works an area closest-first from wherever they stand, and replaying a scanner's fixed order produces visible zigzag routes. It is wrong for a behavior that has *already decided* the order, and a sweep is exactly that. Handed a choice between the column in front of the bot and one behind it, `pollNearest` can take the far one while the near column still hides it: the raycast lands on the near block, the hit-result gate never fires, and the task burns a look timeout and a re-approach. Measured on the chunk miner the moment more than one column was queued at a time — 19.1 ticks per block became **62.0**, with 136 of 248 ticks in LOOKING and 71 in POSITIONING, and the corridor test failed outright.

It also restores head-before-feet within a column, which the chunk miner plans for and nearest-first quietly undid: standing on the floor, the foot block is 1.0 away and the head block 1.41, so nearest-first always took the one whose upward face is still covered.

`BotController` routes every queue read through `peekNextTask`/`pollNextTask`, which pick `peek`/`poll` or `peekNearest`/`pollNearest` from this flag. Three call sites use them: the aim look-ahead bias in LOOKING, the within-reach continuation after a break, and `startNextTask`.

### `approachOccluded`: the move the controller did not have

The controller walks to a target only when it is **out of reach** — where the bot stands is otherwise the behavior's business. A block inside `reachDistance` with something in front of it therefore had no move at all: the raycast lands on the obstacle, the hit-result gate holds, and LOOKING burns its timeout. The one thing that looked like a fix does not cover it — the post-timeout re-approach closes to `APPROACH_CLOSE_DISTANCE` (2.0), and a bot two columns short of a corner is already at 1.4, so it "arrives" without moving and the second timeout kills the task.

With the flag on, two things change. LOOKING detects the obstruction **geometrically** — `hasLineOfSight`, a clip from the eye to the target — which does not care where the camera points and therefore fires on tick one instead of after `WRONG_HIT_STREAK_TICKS` of the crosshair resting on the wrong block. And POSITIONING then walks until *that line is clear* rather than until a distance is met: the sight line is the actual requirement, and the distance was only ever standing in for it.

**The clip goes to the face, and that is load-bearing.** It used to go to the block's centre while LOOKING aimed at a face — `aimPoint` on the face `faceTowardPlayer` picked — and the two rays do not answer the same question: the face centre is half a block nearer, so the ray to it is half a block steeper and clips obstacles the centre ray passes over. The flag was then worse than useless on exactly the geometry it was written for. POSITIONING's arrival test said "clear", so the re-approach arrived without a step, LOOKING spent its whole budget on a ray the obstacle stopped, and the task died — reported from a real chunk-miner run as three attempts from a position identical to two decimals, with `los=true` printed in the diagnostics of every one of them and the run ending on `cannot break -29, 82, -64`. Whatever the approach measures has to be the line the aim will use, or walking cannot clear it. The humanized aim point is deliberately left out of the clip: it would make the same question get a slightly different answer on every call, and LOOKING moves it onto a visible part of the face by itself (see *A point on the face is not a point in sight*). *Bot walks until the face it aims at is in sight* pins it by asserting the **step** rather than the break — with the bug the bot broke nothing from 0.00 blocks along, and a lucky offset occasionally lifts the ray over the corner on its own, which would make an outcome-only assertion report the weather.

Still bounded exactly as before — `lookRetryUsed` allows one approach per task, and a target that stays hidden runs out the position timeout, returns to LOOKING and fails there.

Written for the chunk miner's row turns, where it let the sweep hand over a hidden column in its place instead of skipping it and turning back: yaw per run fell from 1067–1107 to 865–884 at unchanged pace (20/20 blocks at 13.8 ticks per block). Opt-in because it trades a short walk for the turn, and that trade is only obviously right for a behavior whose order is already decided.

### Detection: packets, not polling

Three `ClientPacketListener` injections feed `BotAlarm`; the runner consumes every latch each tick and applies only the ones that apply (a trigger nobody wanted must not stay set and fire at the start of the next run).

- `handleDamageEvent` where `entityId == mc.player.getId()` — the bot took damage.
- `handleSoundEvent` where the sound is `PLAYER_ATTACK_NODAMAGE`. **A zero-damage hit produces no damage event at all** — this sound is the only signal that reaches the bot's client. It is broadcast at the *attacker's* position, so proximity stands in for "aimed at me": within 5 blocks counts, within 0.5 does not, because `Player.attack` broadcasts with a `null` source player and the bot would otherwise stop itself the first time it swung at anything. A neighbouring whiff at an unrelated target is an accepted false positive — stopping too often is cheap, missing a hit is not.
- `handleMovePlayer` — **someone moved the bot**, see below.

All three inject at **TAIL, not HEAD**: these handlers open with `PacketUtils.ensureRunningOnSameThread`, which runs once on the netty thread (throwing to reschedule) and once on the client thread, so a HEAD injection would read `Minecraft.player` off-thread.

### The teleport guard: not a policy flag

A teleport ends whatever is running, and it does so for **every** behavior and for hand-queued `/bot` work alike — it is the one stop that is not opt-in. Every other flag in the table above is a trade-off whose owner has to choose; this one has no other side. Wherever the bot has been put is not the place it planned its work for — not the queued blocks, not the route, not the site a restock means to come back to — and carrying on regardless is also the most conspicuous thing a bot can do while the staff member who just teleported it stands there watching. A `stopOnTeleport` flag would only have offered new behaviors a way to inherit that by omission.

`ClientboundPlayerPositionPacket` is the detection. On the server side `ServerGamePacketListenerImpl` has exactly one method that sends it, so `/tp`, a plugin warp, a portal and a correction all arrive through this one handler — no guessing from a position that jumped.

**The packet alone is not the signal.** The same handler carries vanilla's own re-placements, and a stack trace taken on the integrated server named them: `ServerGamePacketListenerImpl.handleMovePlayer` re-sends a position it is still waiting to have acknowledged — three times per `/tp` under the test suite's accelerated ticks — and it puts a player whose move it refused back on the last position it did accept, which a bot digging the floor out from under itself provokes constantly. Taken at face value that tripped the whole suite: `Bot digs down without turning` failed on the fall, `Bot out-of-reach failure` stopped on the echo of its own setup teleport, and the chunk miner stopped mid-descent.

**A distance band cannot separate the two**, and the attempt is recorded because it looked obvious and passed the first suite it was tried on. Measured on real runs those corrections come in at 0.078 blocks (the grid nudge), 0.768 (a fall wound back) and **1.41** (a whole diagonal block) — while the event the guard exists for, staff teleporting the bot *to themselves* in order to watch it, moves it about 0.6, because two players cannot stand closer than their own width. The ranges overlap, so no threshold sits between them. A 0.25-block band survived `-Psuites=bot` and only died on the full run.

**The trail decides instead** (`BotAlarm.recordPosition` / `isNewPlace`, `TRAIL_TICKS` 40, `SAME_SPOT` 0.05). What tells the two apart is not how far the position moved the bot but where it points: a correction always names somewhere the client itself reported a moment ago, a teleport names somewhere it has never been. `BehaviorRunner.tick` therefore notes the player's position every tick — the one hook in the module that runs unconditionally whether or not anything is going, which is why the errand lives there rather than in the safety layer — and the handler raises the latch only for a position matching none of the last two seconds' worth. Two seconds outlasts the widest correction seen (1.41 blocks, about seven ticks of walking) and stays short of the point where the bot's own route would start hiding real teleports. `Bot stops when it is teleported` asserts a **one-block hop**, backwards off the row so the destination is not somewhere the bot has just walked, under `BotPolicy.none()` to pin the other half.

All three injections therefore sit at **TAIL**, and this one wants it for a second reason: once the handler has run the player is standing on the answer, so nothing has to work out whether the packet's coordinates were relative or whether it was one of the rotation-only variants. Verified over a full test run — every position packet left the player exactly on the coordinates it carried, so nothing interpolates in between. A HEAD version that read the packet through `PositionMoveRotation.calculateAbsolute` and turned the netty-thread pass away itself was written first, and went out with the distance band.

The one exception is the behavior that teleports itself. `BotBehavior.expectsTeleport()` defaults to false and `RestockBehavior` overrides it with `phase == LEAVE_SITE && commandsSent` — the exact stretch between its exit command going out and the jump arriving. Narrow on purpose: outside that window a restock wants the guard as much as anything else does, and the arrival test itself is unchanged (still the 32-block jump).

### Stopping and the alert

Every abnormal stop — a guard firing or the behavior returning `FAILED` — plays three `NOTE_BLOCK_PLING` beeps (spaced 5 ticks via `SoundManager.playDelayed`, so they read as three beeps rather than one) and prints a red chat line naming the reason plus the behavior's own `statusLine()` summary. A clean `SUCCEEDED` gets one soft `NOTE_BLOCK_BELL` and a grey line. The supervising player may be chunks away, so "it's done" and "it gave up" must be distinguishable without watching chat. Note `SimpleSoundInstance.forUI` takes **(pitch, volume)**, not the usual (volume, pitch).

### Opportunistic collection

With the flag on, INTERACTING walks toward a drop *while the break continues* — a human mining a corridor scoops up what fell next to them mid-swing. The camera is already committed to the mined block by the time this runs, so the walk direction is expressed in the 8 view-relative key directions (the quantization `PathWalker.applyCounterBrake` uses to brake without turning); the result is a strafe, not a turn. All four horizontal keys are driven every tick, and INTERACTING releases them on both its exits — `transitionTo` does not.

Three guardrails, because a break in progress is worth more than one dropped item:

1. The projected step must keep the target inside `reachDistance` **and** leave a clear `level.clip` line from the would-be eye to the block. The server reach/visibility-checks the break packets; stepping behind a pillar stalls the task until `maxBreakTicks` with no visible cause.
2. The destination must be standable — sturdy floor, two fluid- and collision-free cells. No drop is tolerated at all: even a one-block fall pulls the target out of the aim.
3. Only drops between 1.425 and 3 blocks away. Inside that, vanilla pickup handles it; beyond 3 the trip costs more than the item.

COLLECTING still runs afterwards and picks up whatever this declined.

### The COLLECTING stalls (`fastCollectExit`)

All pre-existing, all burning the full `collectWaitMax`. Only the absence-window skip is still behind the flag; the three fixes below take no opt-in, because none of them changes the timing of a collect that works — each only turns a phase that could never exit into one that does.

1. **Inhaled drop.** Standing on top of the block, vanilla pickup can take the drop before any tick observes it, so `itemsSeenThisCollect` — which the exit gate requires — never becomes true. Fix: inventory growth since the break started also counts, the same server-authoritative proof the break gate already accepts (and it covers a pickup during INTERACTING too). This one was flag-gated for a while on the "tuned timing" argument, which does not hold: where the drop is observed the signal changes nothing, and where it is not the alternative is the full timeout. Measured on a plain three-block dig-down with no policy: 1201 ticks standing over the finished shaft with all three drops already in the inventory.
2. **Unreachable drop, vertically.** `itemsNearby` was computed from the *unfiltered* entity list while the walk loop skips anything more than 4 blocks above or below. An item the bot has explicitly decided never to approach kept `itemsNearby` true forever, so `doneCollecting` could never fire. Fix: measure presence on the same filtered set the walk uses.
3. **Unreachable drop, horizontally.** The same thing one axis over, and not covered by that filter: a drop landing behind a block the bot will never mine — a blacklisted block, bedrock, the far side of a dammed liquid — sits inside the AABB and inside the walk filter, but outside the vanilla pickup box and behind a wall, so `walkToward` pushes into the obstruction and the distance never shrinks. Six of fourteen collects in one chunk-suite run burned `collectWaitMax` this way. Fix: a walk-progress watchdog (nearest item's entity id, best distance reached, ticks since it improved) drops the item after `itemAbsenceTicks` without closing 0.05 blocks. It nulls `nearest` rather than only clearing `itemsNearby`, so the walk stops too — otherwise the movement keys are still down on the exit tick, and `transitionTo` does not release them. A `level.clip` line-of-sight test was rejected: it would abandon drops behind a corner that are perfectly reachable by walking around.

## Restocking (`RestockBehavior`, `RestockNeeds`, `ContainerTransfer`)

Running out of pickaxes, filler blocks or empty slots is not a reason to end a
run — it is a reason to walk to a chest and come back. So it is a `BotBehavior`
like any other rather than a mode inside one: the chunk miner needed it first,
but nothing in it knows what mining is.

### Two slots, not a stack

`BehaviorRunner` holds **two** slots: the behavior being ticked, and one
suspended underneath it. A shortfall against the active behavior's
`restockNeeds()` moves it into the lower slot and puts `RestockBehavior` in the
upper one; when that succeeds the lower one is started again. Two rather than a
`Deque` of N because a restock cannot itself need a restock, and anything deeper
would be a bug rather than a use case — a fixed pair says so in the type.

**Suspend is `abort()` and resume is `start()`.** No new interface method, no
saved state. That is a real constraint on who may opt in, and it is stated on
`BotBehavior.restockNeeds()`: a behavior that cannot find its place again by
reading the world must keep the default and never declare a manifest. The chunk
miner can, because it already resumes by reading the world rather than saving a
cursor. A `suspend`/`resume` pair on the interface was the alternative and was
rejected: every behavior would then implement two more methods to say "I do not
do that", and a wrong implementation of them fails exactly like a wrong `start`
— with no compiler help either way.

### One manifest, both directions

`RestockNeeds` is a list of `Need(label, matcher, target)` plus a `minFreeSlots`,
declared beside `policy()`. It does two jobs, and that is why it is one type.
It is the **trigger**: `shortfall(player)` phrases the first unmet entry for the
supervising player ("out of a pickaxe", "inventory full (1 empty slots left)").
It is also the **shopping list** at the chest: the trip brings every entry up to
its target and nothing else. A separate config list for "what to fetch" would be
a second place to edit and would drift from the first one the moment somebody
changed only one of them.

The deposit rule then falls out by complement: anything the manifest does not
want is loot — and so is the *excess* of anything it does. A miner whose manifest
asks for 64 cobblestone is carrying the other 300 as loot, and a rule that
simply kept everything matching the manifest would come away from the chest with
an inventory as full as it arrived. Excess counts as loot only when **every**
need that matches the stack would still be satisfied without it, so a stack two
needs both claim is kept while either still wants it. A hand-written deposit
list was the alternative and is worse: every ore nobody thought of while writing
it stays in the inventory, and the bot is full again two slabs later.

### Supplies before the supply guard

The order inside `BehaviorRunner.tick` is load-bearing and was wrong once:

0. **Teleport** — ahead of everything, including the question of whether a
   behavior is running at all, because it also stops hand-queued `/bot` work.
1. **Safety** — damage and player-attack. A bot that is being hit stops; it does
   not go shopping.
2. **Restock** — `restockReason`, skipped entirely while a trip is in flight
   (that is what a non-empty lower slot means).
3. **`stopWhenInventoryFull`** — and only now. A full inventory is a reason to
   walk to a chest where there is one, and only a reason to stop where there is
   not, so asking it *after* the restock lets one threshold serve both. Asked up
   with the safety checks it pre-empted the trip: the run ended on the very tick
   a trip should have started. The fix was deliberately not a conditional
   policy — `withInventoryFullStop` stays unconditional, and the behavior needs no
   second number to say what it already said once.

`restockReason` also folds in the execution layer's `consumeMissingTool()` — the
name of a block `BotController` just tried to mine while carrying nothing that
would drop it. The controller records it and does not act on it, because whether
that is worth a trip is the behavior layer's call and a plain `/bot mine` must
still mine what it was told to. "No tool" means no *correct* tool for a block
that needs one; there is deliberately **no durability threshold**, which would
need a number nobody can defend and would send the bot home with a pickaxe that
had plenty of work left in it.

### A shortfall nobody can serve is not a reason to stop

`restockReason` asks `RestockBehavior.hasStorage` before it suspends anybody, and
returns null with a **one-per-run warning** when this server and dimension have
no chest on record. Without it, a manifest that is on by default turns the first
`/miner chunk start` on a fresh install into an instant failure — a bot that has
just been handed a pickaxe is short of everything else on its list, and
`RestockBehavior.start` would fail with "no storage configured". With it the run
carries on and ends on its own terms: the inventory-full guard, or a block it has
nothing to break with. This is what makes `chunkMinerRestock` safe to default to
on, and it is the same question `start` asks when it picks a chest — one answer
in one place, because the two disagreeing would mean a run suspended for a trip
that then reports it cannot happen.

`endRestock` carries the other guard: a trip that comes back **still** short
fails the run instead of resuming. Resuming into the same shortfall would suspend
again on the next tick and the bot would shuttle to the chest forever — a bot
that looks busy and never mines a block.

### The trip

| Phase | Behavior |
|-------|----------|
| **LEAVE_SITE** | Nothing at all under `STAIRCASE`. Under `COMMAND`: stop everything, hold the spot until it has actually stopped moving (`SETTLE_TICKS`, 10), send `exitCommands` at once, then wait for the position to **jump** (`TELEPORT_JUMP_DISTANCE`, 32 blocks), bounded by `teleportWaitTicks`. Movement matters only *before* the send, where it means the bot is still sliding; after it, movement is the very thing being waited for — see the two halves below. This is also the one window in which `expectsTeleport()` is true, so the teleport guard does not read the bot's own exit command as somebody moving it. |
| **TO_STORAGE** | `Journey.start(...)` on the current stop of the route — the pathfinding module's layer for a target that may not even be loaded yet. |
| **OPEN** | One `OpenContainerTask`. A block that is no longer a storage block, or a task that ran without a screen appearing, is walked past rather than failed (below). |
| **TRANSFER** | One shift-click per `restockClickDelay`: deposit first, then withdraw. Deposit before withdraw because a withdrawal needs somewhere to land, and the loot is what is filling the slots. Decides, while the menu is still open, whether the trip has work left. |
| **CLOSE** | `closeContainer()`. `abort()` does it too — a chest screen left open holds the bot's hands for the rest of the session, and the next behavior's clicks go into the container instead of into the world. Then either the next stop or home. |
| **RETURN** | `Journey` back to the anchor, which is simply where the bot stood when the shortfall was noticed: the runner suspends on that tick, so nothing has moved yet and nobody has to remember to record it earlier. |

**A camp is a row of barrels, and one of them is full.** The trip therefore walks
a **route**, not a chest: `sortedStorages` puts every storage of this dimension in
distance order from the work site at `start()`, and `stop` indexes it. Three things
move the trip on to the next one, and all three used to end it instead — measured
on a real server with 33 barrels, where the first one was full and the bot came
home with every block it had set out with, `Transfer stalled` in the log and
nothing in any barrel:

- the container **would not take** another stack (`TRANSFER_STALL_TICKS`, below) —
  there is work left by construction, since the planner had just named the click;
- the manifest is **still short** when the menu holds nothing it wants
  (`shortfall` re-asked at the end of `TRANSFER`) — another barrel may have the
  pickaxe;
- the site is **not a container any more**, or its screen never opened — someone
  broke the barrel, which is a fact about that stop and not about the trip.

All three go through `moveOn`, which walks past with a warning while a stop is
left and fails with the original reason only at the end of the list. A site is
never struck off `servers.json`: the bot is not the authority on what the player's
camp looks like, and a barrel that is full today is the right barrel tomorrow.
Whether work is left is decided in `TRANSFER` while the menu is still open, where
the answer is cheap and certain, and read by `CLOSE` — which is also why a trip
that fills up the first barrel and needs nothing back goes straight home rather
than touring the camp. Sorting by distance is `RestockBehavior`'s own business
rather than the store's, because "nearest" is nearest *to the work site*, and only
the trip knows where that is. Pinned by *Bot moves on when the nearest storage is
full*, which puts a deliberately-full barrel nearest and registers the route
far-first so the ordering is under test too.

**The send is early and the jump is the arrival** — two halves of `LEAVE_SITE`
that both went wrong in a real run, and both for the same reason: the server's
standstill rule was modelled in the wrong place. The server starts its count when
the command *arrives*, so holding still for 240 ticks first spent those seconds
twice — measured as twelve seconds between "Restocking" and the command reaching
the wire, with the teleport landing in the same second it finally did. And the
branch that restarted the count on any movement sat in front of the jump check,
so it swallowed the one event the phase exists to notice: the jump **is**
movement. The bot held its new spot for another count, sent the command again —
teleporting it to where it already stood, leaving not even a position change to
see — and gave up with "exit commands did not teleport the bot" after a log that
shows the server teleporting it twice. So: before the send, movement means the
bot is still sliding and the settle restarts; after the send, nothing reacts to
movement at all and `standstillFrom` never moves, which is also what lets a nudge
from a mob be survivable — the jump is still measured from where the command went
out. Both halves are pinned by *Bot sends its exit command at once and reads the
teleport*, which fails with a tick budget for the first and a status line for the
second.

**The way out is per server; the way back is always on foot.** Which exit exists
is a fact about the server, not a preference — `/t spawn` and its standstill rule
either exist or they do not. And there is no `/back`: the command exists on some
servers and returns the player to where they teleported *from*, which after a
deposit is the camp, not the pit, so a run would resume at the wrong
coordinates. Nothing is *built* here either: a staircase laid by the restock
would be a second implementation of the one the miner already leaves standing,
and the two would disagree.

`/bot restock` asks for a trip by hand. With a behavior running it is the
ordinary interruption and the run resumes afterwards; with nothing running there
is no suspended manifest, so the trip deposits the whole inventory and brings
nothing back — "go and empty your pockets", which is a useful thing to be able
to ask for.

### `ContainerTransfer`: whole stacks, and which slots are the bot's

Every move is a shift-click (`ClickType.QUICK_MOVE`), one per cooldown, which is
what a person actually does at a chest. Moving exact counts would need
pick-up/place-half/put-down sequences nobody performs thirty times in a row, and
the precision buys nothing: a restock that comes back with 64 cobblestone instead
of 37 is not a worse restock. `move` then checks that the slot actually changed —
a chest with no room answers a shift-click by doing nothing at all, and the same
slot would come back from the planner forever; `TRANSFER_STALL_TICKS` (60) closes
up and takes the trip to the next storage instead. The check reads the slot
straight after the click, i.e. it reads the
client's own prediction, which is exactly what is wanted: a click the client
could not satisfy is one the server will not satisfy either.

**The carry-slot test must use `Slot.getContainerSlot()`, not `Slot.index`.** In
modern Minecraft `Slot.index` is the slot's position in the *menu*, assigned by
`addSlot`; the container index is the private field behind `getContainerSlot()`.
`ChestMenu` lays out 27 chest slots and then hands the player's own `Inventory` to
`addStandardInventorySlots`, so the bot's 36 carrying slots sit at menu positions
27–62 while their container indices are 9–35 and then 0–8. Measured against
`index`, a `>= 0 && < 36` bound accepted nine of the thirty-six — the first
storage row — so a bot whose loot sat in its hotbar deposited **nothing** and came
home with the chest's contents on top of a full inventory, while the withdraw half
worked perfectly (that one tests container identity only). The bound itself is not
decoration either: armour, the offhand and the saddle belong to the same
`Inventory` container at indices 36 and up, so the container test alone would
shift-click a chestplate into the chest, no manifest mentioning armour.
`isContainerSlot` is deliberately *not* the negation of `isCarrySlot` — "not a
carry slot" would make the bot's own boots look like stock.

### Opening a container is a task

`OpenContainerTask` exists because the hard part of clicking a block is what the
task layer already does: turning the head toward it at a human rate, waiting until
the crosshair really lands on *that* block, stepping closer when something is in
the way, failing with a diagnostic instead of hanging. A restock that opened
containers itself would either duplicate all of that or snap its gaze and click
through a wall. Its `requiredItem()` matches nothing, which leaves the hand
alone — returning null would have the controller put an axe in the bot's hand to
open a chest, and run the missing-tool check against a block nobody is mining.
Completion is `containerMenu` being something other than `InventoryMenu`: the
client's own state, set by the server's open packet, so it is the server's answer
and not a prediction.

### `servers.json`: per server, tame by default

`ServerSettingsStore` keeps one `ServerSettings` per server in
`stracciatella/servers.json`, keyed by the server address or
`singleplayer/<world name>` — the two identities the client actually has, decided
in `keyFor` and nowhere else, so a key that turns out wrong is wrong in one spot.
A map in one file rather than a file per server, because the point is to see at a
glance which servers the bot may build on. An unconfigured server is not an error
and writes no entry: `current()` answers with a fresh tame `ServerSettings`.

Every field is a fact about the server rather than a preference, and **every
default is the tame one, and they are tame together**: no storages, no exit
commands, `STAIRCASE` rather than a teleport, and no permission to place blocks.
Joining an unknown server therefore gets a bot that neither types into a
stranger's chat nor reshapes their terrain, and one that cannot start a restock at
all until somebody points it at a chest. The opposite arrangement — useful
defaults, with the player expected to lock them down — fails in the direction that
gets someone banned. These defaults are what make `chunkMinerRestock` default to
**on** safe (see the miner module): the manifest is declared, the trip is simply
unserved until a chest exists. Loosening one of the two without the other is what
the pairing exists to prevent.

A `StorageSite` carries its dimension, because a barrel in the nether is not
somewhere an overworld run can walk to and a position alone cannot say which it
is. It is a record for its equality: `/bot storage scan` has to drop the second
half of every double chest, and "have I got this position already" is the whole of
that check. `/bot storage scan` reads **loaded chunks only** — on the client
`level.getBlockState` in a chunk it does not have answers air rather than saying
so, so a scan past the loaded edge would quietly find nothing and report success;
it asks `MeshManager.isChunkLoaded` per column and counts what it skipped. The
radius cap (`MAX_SCAN_RADIUS`, 32) is a guard against a typo, not a taste: the
cost is the cube of it.

### `RoutePlacer`: the pathfinder's placement, done by the bot

`PathPlacement` declares a `BlockPlacer` in the pathfinding module and this module
registers the implementation — one class whose `place` enqueues an ordinary
`PlaceBlockTask`. That one line is the entire reason the interface lives in the
other module: everything that makes a placement work against a real server (the
pinned face, the crouch out past the rim, the inventory-shrink confirmation, the
retry on a dropped use packet) already lives in `BotController`. Busy-ness is
tracked with the task it issued rather than by asking whether the controller is
doing *something*, because the controller is shared and a behavior's own mining
task would otherwise read as this placement still being in flight. It draws from
its own `routeBlocks` list, and it only ever runs where the current server permits
placement at all.

## Test setup: waiting on gamemode sync

A separate race used to produce the same "block broken, no drop" symptom: the test runs `/gamemode survival` and then immediately enqueues a mining task on the client. On the heavily-loaded accelerated-tick server, `/gamemode` can be queued behind other command packets. When the bot starts attacking, the server still has the player in creative — block breaks are instant client-side and drop nothing. `BotTests.switchToSurvivalAt` now waits on `!mc.player.getAbilities().instabuild` (the server→client ack of the mode change) before starting the bot, which eliminates the whole class of failure without any timing tuning.

The held-slot-sync race is handled separately: tool selection in LOOKING tick 1 lets the carried-item packet travel during the whole camera-turn window, so by the time INTERACTING fires the server is already on the right slot. The `preAttackHesitation` does add a small (1–3 tick) wait between the gate firing and the attack, but its purpose is humanness — not packet timing — and disabling it must not regress drop reliability.

## Human-Like Behavior

- Spring-damper camera smoothing (5–15 ticks to converge), with a randomised look-speed multiplier per target so successive aims don't all turn at the same rate
- Random aim offset within block face (Gaussian, clustered near center, clamped to ±aimOffsetMax)
- Gaussian reaction delay between phase decisions — applied at `SCANNING→NAVIGATING`, `POSITIONING→LOOKING`, and the block-broken transition *except* when it continues a seam (see `continueSeam`); mean 4 ticks (~200 ms), σ=2. During the delay the body pauses but the camera keeps easing toward its last aim point (no stop-motion freeze)
- Occasional long "breather" pause at `SCANNING→NAVIGATING`: with `longPauseChance` (default 4%) the standard reaction delay is replaced by a 12–25 tick pause — breaks the otherwise machine-constant cadence
- Pre-attack commit hesitation: a 1–3 tick "I see it, I click" beat between both LOOKING gates firing and the first `startAttack`, skipped on a seam continuation. Distinct from the old settle delay — tool sync already happens during the LOOKING camera turn, this is purely humanness
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
| `/bot ignore add\|remove <item>` | Edit the ignored-drops list; a bare name is accepted and `minecraft:` filled in |
| `/bot ignore list\|clear` | Show or empty the ignored-drops list |
| `/bot restock` | Walk to a chest now: deposit the loot, take back what the manifest wants, come back |
| `/bot storage add` / `/bot storage remove` | Add or remove the block in the crosshair as a storage for this server and dimension |
| `/bot storage list` / `/bot storage clear` | Show or empty this server's storage list |
| `/bot storage scan <radius>` | Add every storage block in loaded chunks within the radius (cap 32), one half per double chest |
| `/bot server` | Print what the bot may do here: key, dimension, exit strategy, exit commands, placement, storages |
| `/bot server exit staircase\|command` | Choose how the bot leaves a pit on this server |
| `/bot server exit command <command>` | Same, and say which command to send — `/bot server exit command tp 1 1 1`, a leading slash accepted and stripped |
| `/bot server placement on\|off` | Whether the pathfinder may place blocks here |

The `ignore` arguments are `IdentifierArgument`s, not strings — Brigadier's unquoted string stops at the colon, so `minecraft:cobblestone` would parse as `minecraft` with `:cobblestone` left over (the miner's blacklist command had exactly that bug). The list is also a page in the gui module (`/gui ignored_items`, or the root menu) with an "add item in hand" button.

The exit command is a **greedy** string argument for the same family of reasons:
`tp 1 1 1` is four tokens, and a single-word argument keeps only the first and
then fails the parse on the rest, so the command silently does nothing. It is
optional — `/bot server exit command` on its own still switches the strategy and
leaves what was configured — and a leading slash is stripped, because a player
types the command the way they type it in chat while
`ClientPacketListener.sendCommand` wants it without one. Giving a command
**replaces** what was there rather than appending: typing it twice means "no,
that one", and a second command would in any case arrive after the first had
already teleported the bot away. A server that genuinely needs a sequence still
has one — the field is a list and `servers.json` takes as many entries as it
likes.

`/bot storage` and `/bot server` are commands rather than menu pages for the acts
that need the world: which chest the bot should use is answered by *looking at
it*, and a coordinate typed into a text box is the same answer with three chances
to get a digit wrong. The exit command belongs with them because it is a fact
about the server that has to be *typed*, which a cycling settings row cannot do.
What is a standing preference does live in the menu — which *kinds* of block
count as storage (`/gui storage_blocks`, on the shared `IdListScreen`) and this
server's two switches (`/gui bot_server`, on the shared `SettingsScreen`).

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
- `ignoredItems` — item ids the bot neither walks to nor waits for (default empty; see *Ignored drops* under the COLLECTING exit conditions)
- `eatBelowFoodLevel` — under this food level a behavior with `autoEat` eats at the next task boundary (default 14)
- `scanTimeout` — max ticks to look toward next target before walking (30)
- `scanFacingTolerance` — degrees tolerance for scan convergence (15.0)
- `scanRadius` — block scan radius
- `reachDistance` — max mining reach (4.0)
- `maxBreakTicks` — interaction timeout (default 100 = 5 s; fail-fast, below the unenchanted break time of obsidian and ancient debris — see *Mining*)
- `navigateTimeout` / `positionTimeout` / `lookTimeout` — per-phase deadlines

Restocking (see *Restocking*; where a chest **is** is per server in `servers.json`, not here):
- `storageBlocks` — block ids a restock may open (default chest, trapped chest, barrel). Ids rather than "any block entity that is a `Container`": that would also catch hoppers, droppers and furnaces, and a bot tipping its diamonds into a hopper is a bug report
- `routeBlocks` — what `RoutePlacer` bridges a route with, first one carried wins. Deliberately its own list and not the miner's `fillerBlocks`: same contents by default, different owner, different question, and only this one is governed by a per-server permission
- `restockClickDelayMin` / `restockClickDelayMax` — uniform gap between two stack moves in an open container (default 2–5). Shift-clicking thirty stacks in one tick is not a person
- `teleportWaitTicks` — bound on believing in the teleport after the commands went out (default 400). Arrival is the position jumping, not this running out. It has to cover the server's **own** standstill count, because that count starts when the command arrives: there used to be a second setting for holding still *before* sending, which spent the same seconds a second time and bought nothing

## Dependencies

- `loader` (compileOnly) — Module interface
- `camera` (compileOnly) — CameraController, AngleUtil
- `pathfinding` (compileOnly) — PathWalker, MeshManager, MeshPathfinder, plus `travel/Journey` and `place/PathPlacement` for the restock trip
- `gui` (compileOnly) — GuiPage/GuiRegistry, IdListScreen for the ignored-items and storage-blocks pages, SettingsScreen for the server page
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

Tests in `test/BotTests.java`, registered via `TestRunner.instance().registerSuite(BotTests.class)`.
Run via `./gradlew runMinecraftTests`. Each test builds its environment with `/fill` + `/setblock`.

Test cases: single block mine, tool selection, tree chop, camera smoothness, walk-and-mine, multi-task queue, walk→mine→walk→chop, ore vein, out-of-reach failure, place block, place-needs-support, policy damage stop, policy inventory-full stop, policy fast collect exit, policy opportunistic collection, ledge refusal, bridging, digging down without turning, stepping up out of a dip, collecting from a dip, a block falling into the cell, an ignored drop, a meal between tasks, hungry with nothing to eat, hungry with only what the meal list rules out, the `/bot ignore` commands against bot.json, the `/bot storage` commands against servers.json, a restock that interrupts and resumes a run, a shortfall with no chest to serve it, a restock whose nearest barrel is full, the `/bot server exit command` argument against servers.json, an exit command driven against a real teleport, an occluded approach that has to walk before it may break, a corner walked up to rather than climbed onto, a block walked up to instead of reached for, one closed in on while the break is already running, one across a trench that has to be mined from where the bot stands, and a one-block teleport that has to end the run.

**The suite reads the player's live `stracciatella/bot.json`**, and that is a trap worth naming: with `ignoredItems` set to cobblestone and dirt — a perfectly reasonable thing for a player to want — thirteen tests fail, because they assert that the bot collects the cobblestone it mines and the config forbids exactly that. Measured both ways in one sitting: 16 failures with that list, 4 with it empty, nothing else changed. Measured again later on `-Psuites=bot` alone, with cobblestone and dirt on the list: **6 failures, and 0 with the list emptied** — all six were collect assertions. A suite whose result depends on what was last typed in-game cannot tell a regression from a setting, so set the file aside before reading a run as a verdict, and put it back afterwards. The same file is why a test that edits `ignoredItems` in memory must **put the whole list back**, not undo its own edit: `Bot ignore commands edit the list` saves whatever it finds later in the same run, so anything left over is written to the player's config and is there again at the next start. Undoing the edit was tried and is the subtler bug — `ignoredItems` is a `List`, so adding an entry the player already had is *not* a no-op, it appends a second copy, and a removal skipped on the grounds that the entry was already there leaves that copy behind. One duplicate per run, silently: a real `bot.json` had accumulated twelve `minecraft:cobblestone`. Snapshot with `new ArrayList<>(...)` up front and `clear` + `addAll` in the `finally`; there is then nothing to reason about and no case to get wrong.

**A fixture that places a drop has to place the bot too**, and the contact walk is
what made that explicit. `Bot collects from a dip and climbs back` put its drop
0.7 blocks off the bot's centre, which is just inside the half-block radius that
holds the gaze — the code the test exists for — on the unstated assumption that
the bot would still be standing where it started when the collect began. With
the walk during the break it is not: mining a block two out, it ends the break a
block nearer, the drop is 1.1 away and behind it, the hold never engages and
turning round for it costs 187 degrees of perfectly reasonable yaw against a bar
of 120. The block under test moved inside `CONTACT_DISTANCE` so the bot mines it
from where it stands; the drop, the bar and the assertion are untouched. Worth
knowing before reading such a failure as a regression — and before placing a drop
relative to a start position in a new one.

The policy tests drive a `PolicyProbeBehavior` defined inside `BotTests` — the policy layer is only reachable through a behavior, so the guards need one to be testable at all. The three eating tests run the food bar down with the hunger effect at amplifier 255 (a food point every three ticks or so) and clear it again before the run, so the bar is low and stays put — on Easy, switched for the test and back to Peaceful in a `finally`, because the test world is Peaceful and Peaceful never takes a food point (`FoodData.tick` drains saturation only there).

- **fast collect exit** summons a `NoGravity` item 6 blocks up (inside the 8-block collect query, outside the 4-block walk filter) and asserts the run finishes in fewer than `collectWaitMax` ticks with the decoy still present, the block actually mined, and the cobblestone in the inventory. Without the flag the decoy's mere presence pins the phase until the timeout; the last two assertions exist so "exits sooner" can't quietly become "exits without collecting".
- **opportunistic collection** puts a drop 2.5 blocks to the bot's *side* — perpendicular to the block being mined, so reaching it is a pure strafe — and asserts the bot closes at least 0.5 blocks **while still in INTERACTING**. This is what pins down the view-relative sign convention, which has no other coverage. It mines **obsidian with a diamond pickaxe**: ~187 ticks of INTERACTING, long enough to observe the walk, and it still drops. Mining bare-handed for a slow break does not work — stone without a pickaxe drops nothing, so the break never produces an artifact, never confirms, and ends in a `maxBreakTicks` timeout instead of the phase the test needs to watch.

The restock tests drive a `RestockProbeBehavior`, also defined inside
`BotTests`: a manifest of one diamond pickaxe, `BotPolicy.none()`, and a `tick`
that counts its own starts and succeeds on the second. Counting starts is how
"suspended and resumed" is observable at all, given that resume *is* `start()`.

- **storage commands edit servers.json** — `/bot storage add`, `remove`, `list`,
  `clear` and `scan` end to end against the real store, with the player aimed at
  the chest by teleport-and-wait (`lookAtBlock` computes the yaw and pitch, then
  waits until the client's own raycast reports that block, because the commands
  read `hitResult` and nothing else). The server's storage list is set aside and
  restored in a `finally`, file included.
- **restocks and resumes what it interrupted** — a chest six blocks away holding
  a diamond pickaxe, a bot carrying 64 cobblestone and no pickaxe. Asserts the
  probe started **twice**, that the pickaxe came back, that the cobblestone did
  not, that the chest now holds all 64, and that the bot resumed within 2.5
  blocks of where it was suspended. The chest's contents are read from the
  **singleplayer server**, submitted to its own thread and joined on the test
  thread: a client-side `ChestBlockEntity` never holds its items (`getUpdateTag`
  sends none), so asking `mc.level` reads empty however the deposit went — which
  is exactly what it did while `isCarrySlot` was broken, and the reason that
  assertion had to move rather than be dropped.
- **carries on when a restock has nowhere to go** — the same probe and the same
  shortfall with no chest configured anywhere. Asserts the run is **not** ended
  by it: this is the test that makes a manifest safe to have on by default, and
  it is the one that would have caught `chunkMinerRestock = true` turning a fresh
  install's first run into an instant failure.
- **moves on when the nearest storage is full** — two barrels, the near one
  packed with 27 stacks of dirt and the far one holding the pickaxe the manifest
  wants, registered **far-first** so the distance ordering is under test rather
  than assumed from the file order. Asserts the probe started twice, that the
  pickaxe came back, that no cobblestone did, and — the two that separate "moved
  on" from "gave up" — that the full barrel holds **no** cobblestone and the
  spare holds all 64. Both counts are read from the singleplayer server for the
  reason above. Falsified by making the stall end the trip: the bot then comes
  home with the cobblestone and the barrels are as they started.

**exit command reaches servers.json** covers the one thing about
`/bot server exit command` that is invisible until a restock actually tries to
leave a pit: nothing reads `exitCommands` until then, so a command that was
mangled on the way in looks fine in chat and fails hours later. It asserts a
four-token command survives whole (a non-greedy argument leaves the list empty —
verified by making it one), that a leading slash is stripped (verified by
removing the strip: `[/t spawn]`), that a second command replaces the first, and
that switching to `staircase` and back keeps it. The reload from disk is the
assertion for all of them.

**sends its exit command at once and reads the teleport** is the same feature
driven against a real teleport rather than against the file: `exitCommands` is a
`tp` to a landing pad 48 blocks off, past the 32 that separate a teleport from a
nudge. Two assertions, one per half of the bug (both measured in a real run): the
command has to have worked inside `SEND_BUDGET_TICKS` (60 — it takes 20, and used
to take 250), and 20 ticks after the jump the runner's status line has to read
"walking to", because the jump is the arrival. Nothing walks the way back here;
the round trip is the previous test's job.

**walks until the face it aims at is in sight** is the `approachOccluded` clip,
with one stone beside the bot and the target three along: the ray to the target's
middle passes over that stone, the ray to the face the bot will aim at clips its
corner. It asserts **where the bot stands when the pick goes in** — at least
`MIN_APPROACH_STEP` (0.2) off the start, where the bug managed 0.00 — and not
merely that the block fell, because the ±0.3 aim offset lifts the ray over the
corner often enough to make an outcome-only assertion flaky. The position is read
in INTERACTING, since the collect walk afterwards moves the bot whatever the
approach did.

**The player-attack path has no in-game test** — it needs a second player swinging at the bot. Its two real failure modes (never firing, firing on the bot's own swing) are covered by plain JUnit in `src/test/.../safety/BotAlarmTest.java`, which is why `BotAlarm.isAttackerInRange` takes bare doubles instead of Minecraft types. The teleport trail (`recordPosition` / `isNewPlace`) is pinned there for a sharper version of the same reason: its hard case is the server correcting a move, which the in-game test can only produce by accident and never on demand, so the walk-then-get-put-back sequence is played out in doubles instead. Run with `./gradlew :modules:bot:test`.

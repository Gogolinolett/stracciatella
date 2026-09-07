# Miner Module

Specialized mining behaviors built on the bot module's behavior layer. Two of them: a diamond strip miner that digs a staircase down to the layer 3 blocks above bedrock and tunnels forward chasing ore, and a chunk miner that empties a whole chunk layer pair by layer pair.

## Architecture

```
net.stracciatella.miner
├── MinerModule.java            # Entry point (trivial — see MinerSetup)
├── MinerSetup.java             # Wiring: config, behaviors, commands, GUI page, tests
├── MinerConfig.java            # GSON-persisted config (miner.json)
├── MinerCommands.java          # /miner subcommands
├── DiamondMinerBehavior.java   # BotBehavior: LOCATE → DESCEND → TUNNEL strategy
├── ChunkMinerBehavior.java     # BotBehavior: SELECT_SLAB → DESCEND → CLEAR strategy
├── SerpentinePlan.java         # Pure column ordering, unit-testable without a game
├── gui/
│   ├── BlacklistPage.java      # GuiPage contributed to the gui module
│   └── BlacklistScreen.java    # Its screen
└── test/
    ├── MinerTests.java         # In-game tests for the diamond miner
    └── ChunkMinerTests.java    # In-game tests for the chunk miner
```

The module never touches input or the camera. `DiamondMinerBehavior` plans steps as batches of `MineBlockTask`s on the bot module's `BotController`, waits until the controller is idle (task executed, drops collected), verifies the result, and plans the next step. All human-like mechanics (camera, timing, tool selection, collection) come from the task layer for free.

`ChunkMinerBehavior` waits for idle too, with one exception: the corridor sweep also plans while the controller is **COLLECTING**. Waiting for idle there means the controller sees an empty queue at the end of every column, reads that as "nothing left to do" and collects to completion before anyone can hand it the next block — the loop the user sees as *mine, stand, walk, mine*. Planning early puts a block in the queue, so the moment the drops are in the controller starts the next break instead of standing idle waiting to be handed one. Only `CLEAR` runs early: `SELECT_SLAB` is where a run ends, and finishing there would stop the controller mid-collect and leave the last column's drops on the ground; `DESCEND` wants the bot's final position.

The per-column breather draws from `HumanBehavior.randomBreatherTicks`, not `randomTaskSwitchDelayTicks`: the occasional long pause only, nothing the rest of the time. Pausing on *every* column is itself the machine-constant cadence a breather exists to break — and because planning is what releases COLLECTING, it sat in the critical path of every column, measured at 3–6 ticks on top of the collect.

`policy()` returns `BotPolicy.none()`: the strip miner's timing and collection were tuned without any of the bot module's policy opt-ins, and its own fail-fast hazard checks already abort before most of what those guards would catch. Mob *defence* — fighting back rather than stopping — is the right answer for this behavior and is not implemented yet.

## DiamondMinerBehavior

State machine, advanced once per planned-and-verified step:

| Phase | Behavior |
|-------|----------|
| **LOCATE** | Find the highest bedrock in the start column → `tunnelFeetY = bedrockTop + floorOffsetAboveBedrock` (default 3). Tunnel direction = player's horizontal facing at start. Fails if no bedrock below or the player stands below the target level. |
| **DESCEND** | Dig a 1-wide staircase toward the tunnel direction: 3 blocks per step (foot, head, ceiling clearance), one step down per batch, until the cursor reaches `tunnelFeetY`. The player follows the dig front through the controller's own collect-walk and positioning. |
| **TUNNEL** | Before each slice — and only while the player stands at tunnel level (from above, e.g. still on the staircase, the gallery ceiling hides every face from the eye; digging the next slice first pulls him down via the collect-walk) — scan `oreScanRadius` (default 4 = mining reach) around the player for diamond ore. Found ore is mined first via a walkable 1×2 **side gallery** (4-connected columns at tunnel height from the player's column to the ore's column, then the ore — reachable from one below the gallery floor to one above its ceiling; the collect-walk pulls the player in so each next block is frontally visible). Ore veins resolve naturally through the repeated scan. Then dig the next 1×2 slice and advance, until `tunnelLength` slices are done → SUCCEEDED. Air that the run excavated itself (galleries crossing future slices) is tracked in an `excavated` set and never mistaken for a cave. |

### Batch sequencing and verification

A step can consist of several **sequential batches** (`batchQueue`): a staircase step digs ceiling+head first and the foot as a follow-up batch (until the head is gone, every face of the foot is covered and targeting it burns a look timeout or a server-rejected break); an ore gallery digs one column per batch with the ore as the final batch (the collect-walk between batches pulls the player down the gallery so each next column is visible from up close). A single big batch does NOT work: the controller's nearest-task selection reorders it and targets blocks that are still hidden.

After every batch the behavior re-checks all planned blocks. Survivors are re-enqueued up to `maxStepRetries` times (covers task failures and gravel falling into the cleared space). A persistently unbreakable tunnel/staircase block fails the run; a persistently unreachable **ore** (its own task or any gallery batch failing) is skipped, logged, and remembered in a skip-set — one bad ore must not kill the session.

### Fail-fast safety (documented limitations)

Hazards abort the run with a reason instead of being handled:

- **Liquids** ahead of the dig front, behind the next slice, or above the head block (breaking it would pour lava into the tunnel) → FAILED; likewise if a verified block turns out flooded.
- **Bedrock** in the staircase or tunnel slice → FAILED (possible in the jagged bedrock zone; ores behind bedrock are skipped instead).
- **Open cave** ahead (whole slice + the slice behind it air, or an all-air staircase step — own excavation excluded via the `excavated` set) → FAILED — the bot does not walk blind into unlit caves. A single 1-slice air pocket is tunneled through.
- **Out-of-layer or blocked ore** (outside `[tunnelFeetY−1, tunnelFeetY+2]`, gallery crossing bedrock/liquid, or repeatedly failing its tasks) → skipped and remembered, never a run failure.
- **Missing floor** below the next staircase step or tunnel slice → FAILED (no falling into holes).
- No torch placement, no inventory-full handling, no tool-durability handling yet.

## ChunkMinerBehavior

Empties one chunk between two layers, the way a person would: walk a 1-wide, 2-high corridor to the far side, step over one, walk back, and when the level is bare dig straight down through your own feet into the next one.

| Phase | Behavior |
|-------|----------|
| **SELECT_SLAB** | Pick the topmost layer pair inside the range that still holds a diggable block. No progress is stored anywhere — see below. |
| **DESCEND** | Get the feet down to that slab by digging through the bot's own column, one block per batch, so it drops a single level at a time. "Its own column" is the one holding the bot up, not the one its centre is over: `blockPosition()` rounds the centre while the hitbox is 0.6 wide, so with the cell below already open the box can still rest on a neighbour. Waiting for a drop that then never comes froze the run silently — no task queued, so the whole game logged nothing. The wait is bounded and fails loudly. |
| **CLEAR** | Walk the slab's 256 columns in serpentine order, one column per batch (head block before foot block). |

**Resume is the absence of state.** The current slab is derived from the world every time one is needed, so stopping a run — deliberately, by a policy guard, or by losing the client — resumes exactly where it left off, and no saved cursor can ever disagree with what is actually still standing.

`SerpentinePlan` orders the columns: along a row, step over, back along the next, with the first row alternating per slab so a finished slab hands the next one a start next to where it stopped. It is deliberately free of Minecraft types so its two load-bearing properties (every column visited once; consecutive columns adjacent) are unit-testable.

Columns are never batched together. The controller runs the task nearest the player, which in a straight corridor means it can target a block two columns ahead that the near column still hides — the same lesson the diamond miner's batch sequencing encodes.

`nextColumn` guards the same hazard for the sweep itself: it resumes at the column the bot stands in, runs the snake's direction *to the end*, and only then walks back over what was left behind, nearest first. It never wraps. A forward-only scan with wraparound looks like it keeps the sweep adjacent, but once the work ahead is done the modulo throws the bot to the far corner and it walks back — reaching a column three over while the one right behind it still blocks the line of sight, and the run dies on a look timeout.

Running forward *to the end* is the part that matters, and it replaced trying the snake's direction first **at each distance** (`start+i` then `start-i`). Alternating like that is invisible while the bot walks, but a whole group of columns inside `reachDistance` (~4.5 blocks — a 2-high corridor is dug several columns deep without a step) is mined from a standstill: `start` never moves, so every column dug hands the next turn to the other side and the bot ping-pongs across itself. Measured with the bot at x=3015, it dug 3016, then 3014, then 3017 — three ~150° head turns in a row. LOOKING is the phase where the pickaxe is idle, so that is also three gaps in the mining; both symptoms were reported as one. Any ordering heuristic derived from the bot's position has to ask whether the bot actually moves.

It also defers a column that is **in reach and occluded**. Skipping an empty column leaves the bot standing diagonally to the next one with the orthogonal neighbour still up, and nothing walks it clear: the controller only navigates to targets out of reach, and the post-timeout re-approach closes to two blocks, which the bot already beats. The occluder is itself a column of this sweep, so digging it first opens the sight line, and since the miner keeps no cursor the deferred column simply comes back. Out of reach counts as visible — the controller walks to those, and where it ends up is not knowable at planning time. If only the occluded column is left it is returned anyway, so a genuinely unbreakable block still fails the run.

### Policy

Unlike the diamond miner, the chunk miner opts into **every** guard the bot module's policy layer offers: stop on damage, stop on a player's swing, stop when the inventory is nearly full, plus opportunistic collection and the fast collect exit. It runs unattended for a long time somewhere the supervising player is not watching, so damage means something found the bot and a full inventory means everything mined from then on is lost. The two collection flags are what keep the drops arriving without the run crawling.

### Safety

- **Never outside the chunk.** Nothing is mined outside it, and nothing is placed outside it either — with one exception: a water source just beyond the border that would otherwise refill the chunk as fast as it is dug.
- **Never a fall.** Before a cell is opened, the drop below it is measured; anything deeper than two blocks gets a filler placed first. Missing floor under a column being cleared is filled the same way.
- **Bedrock and blacklist** are skipped, never fatal. Bedrock is unconditional and does not belong on the blacklist — it is not a preference.
- **Water** is flood-filled (capped at 16 cells, because the only question is "small pool or not"). Up to 5 sources are capped individually, which leaves the chunk dry for good. Anything bigger is dammed at the face it would flow in through.
- **Lava is always dammed.** Waiting for it to turn to obsidian costs far more time than a block of cobble.
- A filler placement is verified like a break and is **fatal** when it keeps failing — it was planned because the bot could not safely proceed without it.

## Commands (`/miner`)

| Command | Description |
|---------|-------------|
| `/miner diamond start [tunnelLength]` | Start the diamond miner (default length from config) |
| `/miner chunk start [fromY] [toY]` | Mine out the chunk the player stands in; no arguments means from the player's feet down to `chunkMinerBottomY` |
| `/miner chunk blacklist add\|remove <block>` | Edit the blacklist; a bare name is accepted and `minecraft:` filled in |
| `/miner chunk blacklist list\|clear` | Show or empty the blacklist |
| `/miner stop` | Stop the behavior (equivalent to the behavior part of `/bot stop`) |
| `/miner status` | Show the active behavior status line |

The blacklist is also a page in the gui module (`/gui chunk_blacklist`, or the root menu), with an "add the block you're looking at" button. That reads `Minecraft.hitResult`, which still holds whatever the crosshair was on when the menu opened — a block-selection mode with no click handling of its own and no mode the player can get stuck in.

## Config

Persisted to `stracciatella/miner.json`:

- `floorOffsetAboveBedrock` — tunnel feet height above the highest bedrock in the start column (default 3)
- `defaultTunnelLength` — tunnel length when `/miner diamond start` has no argument (default 32)
- `oreScanRadius` — diamond scan radius around the player (default 4 ≈ mining reach, so found ore never requires navigation away from the tunnel)
- `maxStepRetries` — re-enqueues per step before giving up (default 2)
- `chunkMinerBlacklist` — block ids the chunk miner leaves standing. **Ids only, no block states**: a blacklist the player edits by name is worth more than one that can tell a facing apart, and no realistic entry needs the distinction.
- `fillerBlocks` — what the chunk miner places to seal or dam, in order of preference; the first one it actually carries is used
- `chunkMinerBottomY` — lowest layer for an argument-less `/miner chunk start` (default -59, the first layer that is never bedrock in a vanilla overworld; clamped to the world floor)
- `chunkMinerMinFreeSlots` — the run stops below this many empty slots (default 2)

## Dependencies

- `loader` (compileOnly) — Module interface
- `bot` (compileOnly) — BehaviorRunner/BotBehavior, BotPolicy, BotController, MineBlockTask, PlaceBlockTask, BlockScanner, HumanBehavior
- `gui` (compileOnly) — GuiPage/GuiRegistry for the blacklist page
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

`./gradlew runMinecraftTests -Psuites=miner,chunk` for the in-game suites, `./gradlew :modules:miner:test` for the unit tests.

**Miner Tests** build a deterministic deep-mining sandbox (bedrock floor at -64, solid deepslate above, air on top):

- **Miner tunnels at depth** — player starts in a pocket at tunnel height; tunnel 5 slices, one ore in the path; expects 1 diamond and a fully dug tunnel.
- **Miner descends and mines diamonds** — player starts 3 levels above tunnel height; staircase descent, 6 slices, one path ore plus one side-wall ore (mined via the ore scan, not the slices); expects 2 diamonds.

**Chunk Miner Tests** run against a chunk that is empty apart from the few blocks each test cares about. The behavior skips columns with nothing to dig, so a sparse chunk exercises the same code as a full one in a fraction of the ticks; the layer range is pinned to one slab (two for the descent test) so a run has a defined end instead of eating its way to bedrock. Covers: clearing a slab, blacklist, bedrock, the chunk boundary, descending, capping a water source, damming lava, a corridor, and three that measure the run rather than its result.

Those three — **mines without stalling**, **resumes the layer it stands in**, **holds its aim** — exist because the other eight cannot fail on a bot that breaks one block, stares at a wall for a thousand ticks and breaks the next. `TestRunner` multiplies `timeoutTicks` by the tick multiplier, so the corridor test's nominal 3000 ticks is a 30000-tick budget for eight blocks. They therefore carry **their own budget** in game ticks, `blocks * TICK_BUDGET_PER_BLOCK` passed to `waitFor`, and sample the tick thread once per tick through the `waitFor` predicate (which is evaluated there anyway — per-tick *logging* perturbs the corridor test enough to flip it, so the sampler stays field arithmetic).

The mining sensor is `player.swinging`, not `MultiPlayerGameMode.isDestroying()`. The latter reads like the direct one and is useless: measured over 482 ticks of a passing run it was never once set. `handleKeybinds` calls `continueAttack(false)` every tick — no key is down in a test client — which calls `stopDestroyBlock` and clears the flag, and only `startDestroyBlock` ever sets it, never `continueDestroyBlock`. It survives one tick per block. The swing is what `BlockInteractor` mirrors from `Minecraft.continueAttack`, and it is emitted only on a tick where `continueDestroyBlock` reports the break still running.

Measured on a healthy run and used to set the thresholds: 25 ticks and 71-92 degrees of yaw per block, 67-70% duty cycle, largest gap 16-24 ticks, 16 of 18 drops collected by the time the run ends. Falsified against the pre-fix code with the session's three fixes stashed: `duty 0%, swinging=0` fails both the stall and the resume test, while **all eight older tests stayed green** — which is the measurement that justifies these three existing.

`holds its aim` is a coarse guard, not a regression test for column ordering, and its javadoc says so. Two geometries were measured against the ping-pong scan: a ring of eight columns costs a full turn in any order (61 deg/block on both broken and fixed), and a flat patch reaching three columns either side gives 98 broken against 81-92 fixed — overlapping ranges at an 11% run-to-run spread, so no threshold separates them. It does not assert the gap or duty thresholds either: with twenty columns in reach its collect pattern differs (56% duty, gaps to 74 ticks).

The corridor test is the only one that is more than one column wide, and that width is the point: with a single column there is never a next column to plan, so the controller collects to completion and the steady-state loop — planning that overlaps collecting — never runs. It digs four columns and asserts the **cobblestone count**, not just that the blocks are gone: everything that makes the loop fast trades against picking the drops up at all, and nothing else in the suite would notice a corridor mined clean with the cobblestone still lying in it. Measured at the COLLECTING exit gate: the first two columns take 10 and 11 ticks with a real collect, the last two leave on tick 1 because vanilla pickup already inhaled the drop during the break, and every exit reports clear ground.

This test is **timing-marginal at accelerated tick rates** and is the first thing to look at after any change to the collect path. It depends on the drop having synced to the client before COLLECTING decides, so anything that adds client work per tick can flip it — it flipped on a single per-tick particle spawn in `BlockInteractor`, and only in the full 187-test run; the module suites stayed green throughout. A single failure is not proof of a regression on its own: re-run it alone, and compare against a baseline run with the change stashed. Observed rate across eight archived runs: five green, three red.

The root cause is now measured, and it is the *success condition*, not the mining. A run can end with drops still on the ground — `mines without stalling` records 16 of 18 collected on a healthy run, the two missing ones falling into the hole during the descent — and `awaitChunkMiner` stops the bot before the corridor test starts waiting. From there its 1200-tick wait for 8 of 8 cobblestone can only be satisfied by luck, because a stopped bot cannot walk to a drop. Either the demand for every drop is wrong, or the miner should clear the ground before a run reports success; the gate at `ChunkMinerBehavior:249` already holds SELECT_SLAB while collecting, so the intent is there and does not go far enough. Left as is deliberately — that is a behaviour decision, and lowering a test's bar to match the code is not one to take quietly.

The two liquid tests place their source and start the run inside a `tick freeze` / `tick unfreeze` bracket, with one cleared corridor cell between bot and liquid. Setup commands are wall-clock bound while the world runs at ten times speed, so an unfrozen gap is worth hundreds of fluid ticks — enough for the lava to reach the bot before its first tick, and for a pool to outgrow the flood-fill cap that decides capping against damming.

**ChunkMinerSerpentineTest** (JUnit) asserts the column plan visits all 256 columns exactly once, never jumps between non-adjacent columns, and starts each slab where the previous one ended.

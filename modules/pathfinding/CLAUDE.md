# Pathfinding Module

Autonomous player movement and pathfinding for Minecraft. Generates navigation meshes from terrain, finds paths with A*, and walks them with realistic physics-based controls.

## Architecture

```
net.stracciatella.pathfinding
├── PathfindingModule.java            # Entry point. Loads configs, registers commands/ticks/tests/travel methods
├── ChunkCoordinate.java              # 2D chunk coordinate wrapper (x, z)
├── commands/
│   ├── PathCommands.java             # All /path subcommands (find, walk, config, calibrate, etc.)
│   └── NavigateCommands.java         # /navigate commands (to, stop, methods)
├── display/
│   └── PathDisplay.java              # In-game mesh/path rendering (custom no-depth-test lines)
├── logic/
│   ├── PathWalker.java               # Core autonomous movement controller (static, tick-driven)
│   ├── ChunkMeshBuilder.java         # Converts chunks into walkable node graphs
│   ├── MeshManager.java              # Stores meshes per entity per chunk, band coverage (ensureMesh/ensureArea), cross-chunk linking, node lookup, batched invalidation
│   ├── MeshPathfinder.java           # A* algorithm with admissible horizontal heuristic (scale=9.8), closed set
│   ├── Terrain.java                  # The shared block predicates: isPassable, isStandable, hasCollision, isStepUp
│   └── mesh/
│       ├── Mesh.java                 # HashMap<BlockPos, MeshNode> container for one chunk + the Y band it was built over
│       ├── MeshNode.java             # Graph vertex: x, y, z + List<Neighbor>. Has equals/hashCode on (x,y,z)
│       ├── Neighbor.java             # Weighted edge: target node + cost
│       └── IMeshProvider.java        # Interface for mesh sources
├── place/
│   ├── BlockPlacer.java              # Interface: "put a block here, against that" — implemented by the bot
│   └── PathPlacement.java            # Static hand-off + the per-server permission gate
├── travel/
│   ├── TravelMethod.java             # Interface: pluggable movement method (id, canUse, cost, start, tick, abort)
│   ├── TravelStatus.java             # Enum: IN_PROGRESS, SUCCEEDED, FAILED
│   ├── Navigator.java                # Static coordinator: auto-selects best method, manages fallback chain
│   ├── Journey.java                  # Long-distance travel in legs, re-planning after each one
│   ├── WalkTravelMethod.java         # Wraps PathWalker for mesh-based walking
│   └── EnderPearlTravelMethod.java   # Ender pearl throw with trajectory simulation
├── mixin/
│   └── LevelChunkMixin.java          # Triggers mesh generation on chunk load
└── test/
    ├── PathWalkerTests.java          # In-game tests: straight-line + L-shaped path walking
    ├── JourneyTests.java             # In-game tests: a 400-block bridge, an unloaded target, and a way walled off mid-journey
    ├── MeshTests.java                # In-game tests: grass, a two-high corridor, an open diagonal, a chunk corner, a staircase journey
    └── EnderPearlTests.java          # In-game tests: ender pearl throwing at various distances/elevations
```

## Key data flow

1. **Mesh generation**: on demand — `MeshManager.ensureMesh/ensureArea/findOrBuildNearestNode` (Journey, BotController, `/path`) → `ChunkMeshBuilder` scans blocks, creates MeshNodes where a player can stand (`Terrain.isStandable` floor + 2 `Terrain.isPassable` cells above), connects neighbors via reachability checks → stores in `MeshManager.meshes` → relinks border nodes with adjacent chunks
2. **Pathfinding**: `/path find` → `MeshPathfinder.findPath(start, end)` → A* search → returns `List<MeshNode>`
3. **Path walking**: `PathWalker.start(path)` → each tick: get target node, calculate jump decision, update aim/rotation, apply movement keys → when node reached, advance index → when path done, `stop()`
4. **Navigation**: `/navigate to <x> <y> <z>` → `Navigator.navigate(target)` → evaluates all registered `TravelMethod`s via `canUse()`/`cost()` → picks cheapest → `start()` → ticks until `SUCCEEDED`/`FAILED` → on failure, tries next fallback method

## PathWalker — the core component

PathWalker is entirely **static**. It simulates keyboard input (forward, sprint, jump keys) — it does NOT teleport or set position directly. Camera control (yaw/pitch smoothing) is delegated to `CameraController` from the **camera** module.

### Important concepts

- **`gap`** = `Math.max(abs(target.X - playerX), abs(target.Z - playerZ))` — includes both endpoints. gap=5 means 4 air blocks ("4-block jump" in parkour terms)
- **`longRangeJump`** = effectiveGap ≥ 5. Uses collision-based edge detection instead of simulation. Gap=4 uses simulation (collision-edge overshoots single-block platforms at that distance)
- **Node arrival**: sphere check (distance < 0.18) OR box check within block bounds with 0.15 margin. The **final node** instead counts as arrived when standing within 0.5 of the true block center (target offset excluded) at low residual speed (≤ 0.12 b/t) — no walk-to-center after landing; re-centering made the bot visibly pirouette around the center point
- **Final-node settling**: while standing inside the final arrival disc but still above the 0.12 speed gate, target steering is skipped entirely — the camera yaw is held (spring settles, no turn) and the 8-way counter-brake kills the momentum until arrival fires. Without this, the desired yaw orbits the walk target during the brake-out ticks and the camera follows (the post-landing pirouette)
- **Desired-yaw freeze near nodes**: below 0.5 blocks horizontal distance the atan2 yaw target flips ~180° when stepping past the point — `stableDesiredYaw` freezes the target at its last stable value through that zone (movement aim + landing-brake retarget). Combined with the camera module's 35°/tick angular-velocity cap, this removes the remaining one-tick gaze snaps
- **Target offset**: random X/Z offset (0.05–0.25) added for natural-looking movement, suppressed for long jumps

### Jump decision system (`shouldJumpNow()`)

Returns a `JumpDecision` record: `(jump, gap, holdBeforeJump, reason)`

Decision paths in priority order:
1. **Same block** (gap=0): jump if target ≥0.5 blocks higher
2. **No direction**: skip if stepX==0 && stepZ==0
3. **Landing validation**: check forward air and landing block solidity
4. **Drop check**: no jump for small drops (gap ≤ 1, target lower)
5. **Step-up** (gap ≤ 1): jump if target higher or block in front, only when close
6. **Long-range** (effectiveGap ≥ 5): collision-based edge detection with direction-aware AABB shrinking, preserves sprint
7. **Simulation** (short-range, effectiveGap < 5): physics simulation to predict landing
8. **Edge-distance**: fractional block position checks for when to fire/hold
9. **Fallback**: block-edge position check

**Whether a jump is owed is a question about the ground and about the step the
mesh planned, never about how far the target still is.** `gap` is the Chebyshev
distance from the block the player is *standing in* to the target node's block,
and for a long time it was enough on its own: `needsJump` fired on `gap > 1`.
But a node counts as reached anywhere within 0.65 of its centre (the
intermediate box check, `ARRIVAL_MARGIN`), which is wider than the node's own
block — so a node ticked off from the near edge of that box leaves the player
one block short of the node it has just left behind, and the *next* node then
reads as gap=2 with nothing at all in between: flat ground, solid the whole
way, and the bot hops over it. The planned step decides instead (`nodeGap > 1`).
Nothing else moves: a hole to cross is `forwardAir`'s question, a rise is
`feetToTargetDy`'s and `blockInFront`'s, and all three still fire on their own.

Reported as a bot that "sometimes jumps at corners", and a corner is where it
shows most — braking into a turn is what leaves the bot furthest from a centre
when arrival fires — but it is not only corners. Measured on the dead-straight
corridor of `Bot walk and mine`: the bot hopped at z=998.03 heading for a target
at z=996, `forwardAir=false`, the target at its own feet level, `nodeGap=1`.
Across one bot+pathfinding run 4 of 259 jumps were this, every one of them with
`nodeGap` below `gap`; afterwards **every** jump in the run had `gap == nodeGap`
and the parkour counts were unchanged to the jump (62 three-gaps, 53 four-gaps,
53 long-range).

Note the position-based `gap` is still what the *caller* reads out of the
returned `JumpDecision` to decide `stabilizeForJump`, and still what classifies
a jump once one is owed (`effectiveGap = max(gap, nodeGap)`, so a gap that has
shrunk on the approach cannot demote a long jump).

### Key mechanics

- **Retreat phase** (gap ≥ 5): Player walks backward to back edge of block to maximize sprint runway. Uses a fixed origin reference (recorded when retreat starts) to prevent backProgress from resetting when crossing block boundaries on single-block platforms
- **Sprint suppression** (gap = 2): Sprint set to false on jump tick to prevent overshooting single-block platforms. Exception: any diagonal gap=2 (both axes non-zero) enables sprint because the euclidean distance (≥ sqrt(5) ≈ 2.24) exceeds non-sprint jump range (~1.8 blocks).
- **Hold movement block** (gap ≤ 2): When simulation/edge-distance says "hold", forward movement is blocked to prevent walking past the edge. Exception: any diagonal gap=2 skips this block — the simulation's air acceleration model (10x too low) can't predict diagonal sprint-jump landings, so its hold would deadlock the player. The edge-distance fallback handles diagonal timing instead. Note: "10x too low" was the code default (`physicsAirAccelFactor` 0.02); the `pathwalker.json` the suite runs with has long held the calibrated 0.2, and the default is now 0.2 too. Whether the exception is still needed with the correct value has not been re-measured.
- **Landing brake**: Activates after any jump landing (prevSegGap ≥ 2) on an intermediate platform when more path follows. The camera keeps turning smoothly toward the next target (never snaps); at high speed (>0.1 b/t) the momentum is countered with movement keys — the momentum direction relative to the view is quantized to the 8 key directions (S, S+A/D, A/D, W+A/D, W; max 22.5° off) and the opposing combo is pressed, like a human braking with S or a counter-strafe. At low speed (≤0.1 b/t), releases all keys and lets friction handle it. Targets: gap≤1→0.03, gap≤2→0.04, gap≥3→0.08
- **Post-brake air release**: After a landing brake + gap=2 jump, releases forward key within 1.0 blocks of target to prevent air acceleration overshoot
- **Pre-landing air deceleration**: When airborne approaching an intermediate platform from a gap≥3 jump with a sharp turn (≥60°) ahead, releases forward key within 1.0 blocks to reduce landing speed
- **Jump facing tolerance**: Gap=2 uses tighter tolerance (18°) than gap≥3 (36°) because single-block landing platforms have no margin for angular error
- **Direction-aware collision-edge**: Only shrinks AABB in the gap axis direction, preventing false triggers from perpendicular drift on single-block platforms
- **Skip-retreat threshold**: 0.14 — below this speed, the player retreats; above, they sprint straight through. Prevents players from attempting collision-edge jumps without enough speed

### Timing model

All PathWalker logic is **tick-based** — no wall-clock time dependencies. This means behavior is identical regardless of tick rate (e.g. during accelerated tests at 200 TPS). The only `System.currentTimeMillis()` usage is for debug output throttling (cosmetic, not functional).

- **Alignment hold** (`alignmentHoldTicks`, default 5): After camera aligns with the target direction, holds forward movement enabled for this many ticks even if angle drifts slightly. Prevents stop-start jitter on turns. Configurable via `/path walkconfig alignhold <ticks>`.

### Human-Like Movement

PathWalker adds several humanness behaviours on top of the core movement physics. All are gated to *safe* segments so they cannot push the player off a narrow platform.

- **Target offset**: random X/Z offset (0.05–0.25) added to each node's centre so arrival points cluster naturally around target, not on it. Suppressed for long-range jumps (gap ≥ 5) where precision matters.
- **Jump aim yaw offset**: 1.5–4.0° random yaw jitter applied during jump preparation. Scales down for longer gaps (0.5–1.5° for gap=4, 0° for gap ≥ 5) where any angular deviation costs forward velocity.
- **Pre-jump hesitation** (gap ≥ 5 only, standstill entry only): 4–12 tick Gaussian pause (mean 7, σ=2 — ~200–600 ms) at the start of the retreat phase. Camera holds aim at the landing target during the pause. Skipped if the player is already sprinting into the jump (the existing skip-retreat branch).
- **Pitch micro-variance** during sustained straight walks (`straightWalkTicks > 20`, on ground, target > 1.5 blocks away, no jump pending): ±2.5° Gaussian offset added to the desired pitch, refreshed every 20–40 ticks. Spring-damper absorbs it as a slow gaze drift. Zeroed outside the safe window.
- **Micro-strafing** on safe corridors (current + next 2 nodes all same-Y gap=1): 1–2 tick sideways key press every 40–80 ticks. Adds ~0.05–0.1 blocks of lateral drift, well within the 0.18-block arrival radius. Disabled on any segment containing a jump or sharp turn.

The `applyMovement` master variant explicitly drives all six movement keys (forward/backward/left/right/jump/sprint) so any in-flight strafe is cleared whenever a non-strafing path (jump, brake, retreat) calls `applyMovement` with `strafeDir = 0`. This prevents leaked key state between segments.

### Config system

- Stored in `pathwalker.json`, loaded/saved via `PathWalker.loadConfig()`/`saveConfig()`
- `PathWalker.CONFIG` is the public static Config object
- All parameters tunable via `/path walkconfig` commands
- Key parameter groups: edge jump thresholds, jump simulation, physics, off-course detection

### Learning and calibration

- **Learning** (`/path walklearn on`): records manual jump thresholds while pathwalking is inactive, applies learned edge ranges to config
- **Calibration** (`/path walkcalibrate start`): measures actual physics (gravity, drag, jump velocity, acceleration) from gameplay, updates config physics values

### Debug output

PathWalker has extensive debug logging. **Always read the logs when editing PathWalker.** Enable with `/path walkdebug on`. Outputs: distance, angle, facing, jump reason, fall diagnostics. Updates every 200ms.

## ChunkMeshBuilder — mesh generation

### Node creation rules
- `Terrain.isStandable` floor at Y, `Terrain.isPassable` at Y+1 and Y+2 → walkable node at (X, Y, Z)
- **Passable** = no collision, no fluid, not a hazard (fire, cobweb, berry bush, wither rose, powder snow). Grass, flowers, torches, rails, carpets are passable; water and lava are not (no swimming)
- **Standable** = full top face, or a collision top of at least 0.8 covering the whole block (dirt path, farmland, soul sand, mud); never magma. Slabs and stairs are not — a node stands a whole block above its floor, so a half-block floor would plan half-block-wrong steps
- Edge limits: horizontal reach 5 (`EDGE_REACH`), up 1, drop 3, diagonal reach 4.0 (4.5 when dropping), no diagonal step-ups

### Neighbor connection
- Brute-force search within reach for each node, against a **node lookup** (position → node in whichever mesh holds it), never against a pair of meshes
- `isBlockReachable()`: height and diagonal limits, then a Bresenham line at the source's height with **2** free cells per column for a walk and **3** for a jump (gap ≥ 2 or a step up), side columns on diagonal steps; a drop also needs the target column free from the source's head height down
- `movementCost()`: `round(10 × horizontal distance)` + jump surcharge by gap (0, 0, 4, 12, 30, 55; a step up counts as a jump, at least 4) + height (5 per block up, 2 per block down). Every jump costs more than walking the same ground

### Cross-chunk connectivity
- `MeshManager.connectAdjacentMeshes()` collects the new chunk's border nodes and the loaded neighbours' border nodes facing it, and relinks each once against all of the entity's meshes
- `evictBeyond()` relinks the remaining borders facing an evicted chunk, so nothing keeps links into dropped meshes

### Mesh bands
- A `Mesh` remembers the Y band it was built over. `MeshManager.ensureMesh` rebuilds a mesh over the union when a caller needs layers it does not cover; `findOrBuildNearestNode` meshes a missing chunk's whole column and only widens an existing band to reach the position
- `BotController.beginNavigation` meshes the rectangle of bot and target ± 1 chunk over a band 16 above/below both (`ensureArea`) before its standoff search

### Mesh invalidation on block changes

`LevelChunkMixin.setBlockState` marks the chunk dirty via `MeshManager.invalidateMesh` when:
1. The chunk's level is the client's level (filters out the server-thread fire in single-player so we don't regenerate twice against stale data).
2. Air-ness, fluid presence or collision emptiness changed (sub-state edits like growth stages don't affect walkability — skipped).
3. At least one entity already has a mesh for that chunk (no point burning CPU on chunks no bot uses).

`MeshManager.flushInvalidations` (START_CLIENT_TICK) rebuilds each dirty chunk once per tick over its own band, so a `/fill` costs one rebuild per chunk, not per block. Without invalidation, mining a wall would leave the mesh thinking the wall is still solid, and subsequent A* searches route around the hole the bot just dug. Meshes are still built on-demand (chunk-load is intentionally a no-op).

## Commands (`/path`)

| Command | Description |
|---------|-------------|
| `path pos start\|end` | Set start/end positions |
| `path find` | Calculate and display path |
| `path walk` | Walk the calculated path |
| `path walk stop` | Stop pathwalking |
| `path walkdebug on\|off` | Toggle debug logging |
| `path walklearn on\|off` | Toggle jump learning |
| `path walkcalibrate start\|stop\|status` | Physics calibration |
| `path walkconfig menu` | Interactive config menu |
| `path walkconfig <param> <values>` | Tune individual parameters |
| `generateMesh` | Generate mesh for current chunk |
| `displayConnections` | Toggle mesh visualization |
| `path connections all\|path\|none` | Connection display mode |

## Travel Framework

Extensible system for navigating between locations using different movement methods. The `Navigator` auto-selects the cheapest usable method and falls back to alternatives on failure.

### TravelMethod interface

```java
public interface TravelMethod {
    String id();
    boolean canUse(Minecraft client, BlockPos from, BlockPos to);
    double cost(Minecraft client, BlockPos from, BlockPos to);  // lower = preferred, roughly in ticks
    void start(Minecraft client, BlockPos from, BlockPos to);
    TravelStatus tick(Minecraft client);                        // returns IN_PROGRESS, SUCCEEDED, or FAILED
    void abort();
}
```

### Adding a new travel method

1. Create a class implementing `TravelMethod` in the `travel/` package
2. Register it in `PathfindingModule.init()`: `Navigator.register(new MyTravelMethod())`
3. The Navigator's auto-selector will consider it based on `canUse()` and `cost()`

### Built-in methods

| Method | id | Cost model | Requirements |
|--------|----|-----------|-------------|
| EnderPearl | `ender_pearl` | ~60 ticks | Pearl in hotbar, distance 5-40 blocks |
| Walk | `walk` | distance/0.215 | Mesh available, A* path exists |

### Navigator

Static coordinator (like PathWalker). Registered on `ClientTickEvents.END_CLIENT_TICK`. Supports single target or waypoint lists. On each leg:
1. Evaluates all methods via `canUse()` + `cost()`
2. Sorts by cost, starts cheapest
3. On failure, tries next fallback
4. Sends chat messages on method transitions

### Commands (`/navigate`)

| Command | Description |
|---------|-------------|
| `navigate to <x> <y> <z>` | Navigate to coordinates using best method |
| `navigate stop` | Stop navigation |
| `navigate methods` | List registered methods and availability |

## Journey — travel that does not know the way yet

`Navigator` plans one path and walks it. That is the wrong shape for a destination
hundreds of blocks away: **a route into an unloaded chunk cannot be planned at
all.** The client holds no block data out there, so the mesh has no nodes — not a
matter of search effort. `Journey` therefore walks in **legs**: aim at the best
node the mesh currently has in the target's direction
(`MeshPathfinder.findPathTowards`, the partial-answer variant of the same A*),
walk it, mesh whatever streamed in on the way, ask again.

Two things the module was missing fall out of that for free. **Re-planning**:
`PathWalker` keeps the node list it was handed whatever happens to the world — the
chunk mixin invalidates the mesh on a block change and nobody ever planned again.
Every leg does. **A verdict**: `PathWalker.isActive()` going false means the walk
ended, not that it worked, so every caller measured the distance itself;
`Journey.status()` is `RUNNING` / `ARRIVED` / `FAILED` with a `failReason()`.

`status()` is initialised to `ARRIVED` — the honest answer to "are you
travelling" — so it must not be read as "have I arrived" before `start()`. Both of
`RestockBehavior`'s travel phases completed instantly on that, and each caller now
keeps its own "has it been started" flag.

Bounds, all of them because an unattended bot must not walk forever: `CHUNKS_PER_TICK`
(2) meshes under a per-tick budget so a long route is not paid for in one frame,
`BAND_ABOVE`/`BAND_BELOW` (16/32) mesh only the Y band a route uses — from below the
lower of feet and target to above the higher one, and a chunk whose mesh does not
cover that band is rebuilt (`MeshManager.ensureMesh`); the band is a **parameter**
of mesh generation, not a new default, so ordinary `/path` meshing is unchanged —
`CORRIDOR_PAD` (1) chunk either side of the straight line, widened to the whole
`KEEP_RADIUS` window when a leg stalls or the narrow corridor yields no path at all
(and narrowed again on progress), `KEEP_RADIUS` (8) evicts meshes outside a window around the player,
`LEG_TIMEOUT_TICKS` (600), `TOTAL_TIMEOUT_TICKS` (12000), and `STALLED_LEGS_LIMIT`
(2): a leg that ends no closer than it started is a stall, and the **second** one
ends the journey — the first is what triggers an attempt to mend the way ahead,
and that attempt deserves a leg to prove itself.

**Standing still is not walking, and the leg timeout is the wrong bound for it.**
`PathWalker.isActive()` stays true with the body pressed against a wall, so a leg
whose path runs into one spends its full 600 ticks holding the forward key — and
with three such legs in a row, a journey that had already failed took 90 seconds
to say so. `STILL_TICKS_LIMIT` (100, five seconds — longer than any legitimate
pause a walk has: a pre-jump hesitation is 12 ticks, a retreat a handful) is
measured against `PROGRESS_EPSILON` from the last position the bot actually moved
from, and ends the leg as a stall. Which is what makes the mend reachable at all:
`STALLED_LEGS_LIMIT` promises the first stall triggers `tryBridge`, but the only
path into it used to be A* returning an *empty* path — a bot wedged against a
one-block step got the promise and never the attempt. The diagnostics go out on
the failure path only (player position, the leg's own target and node count,
distance left, best distance reached, whether the walker was still active): a WARN
per leg would be noise on a route that works, and the ninety seconds of silence
produced exactly three identical lines with nothing in them to tell a wall from a
timeout. The fail message now names where the bot actually stopped and what the
last leg was aiming at, because "no way towards X" on its own does not say whether
the bot ever left. Pinned by *Journey gives up quickly when the way is walled off*,
which walls off a 48-block walkway in front of a journey already under way and
asserts it gives up within 400 ticks — 520 with the watchdog disabled, 70 with it.

### Placement: the pathfinder may build, if it is allowed to

`BlockPlacer` is declared *here* and implemented in the bot module (`RoutePlacer`),
registered through `PathPlacement.register` — the same inversion as
`Navigator.register` and `BehaviorRunner.register`, and the reason it is that way
round is that the dependency already points bot → pathfinding and must not
turn around. Knowing *how* to place a block like a person is the bot's knowledge;
knowing *where* a route needs one is the pathfinder's.

`PathPlacement.setAllowed(boolean)` gates it and **defaults to off**. Reshaping
terrain on a server the player has not configured is not something a pathfinder
should start doing on its own; the bot module drives the flag from its per-server
settings before each journey. `isAvailable()` is checked *before* a route that
depends on placement is planned, so a forbidden placement shows up as "no path"
rather than as a half-built bridge. `MAX_BRIDGE_CELLS` is 2, deliberately tiny:
the equivalent logic in the chunk miner took five rounds to get right and every
wrong version walked the bot somewhere it should not have gone.

This governs the **pathfinder** placing blocks to open a route and nothing else.
The chunk miner's own placements — damming water, bridging a gap in its slab,
rebuilding a step — are a different permission and do not pass through here.

## PathDisplay — rendering

- Custom `RenderType` with no depth test (visible through blocks)
- Yellow outlines for nodes, colored lines for connections
- Orange/yellow for highlighted path, light blue for others
- Config stored in `pathdisplay.json`
- Access widener used for `RenderPipelines.DEBUG_LINE_STRIP` and `LINES_SNIPPET`

## Build configuration

- Dependencies: loader (compileOnly), camera module (compileOnly), testing module (compileOnly), sodium (modCompileOnly, optional)
- Mixin config: `pathfinding.mixins.json` (client: LevelChunkMixin)
- Access widener: `pathfinding.accesswidener`

## Minecraft physics reference

- Y is height, Z is forward/back
- Post-drag sprint speed: ~0.153 b/t, pre-drag: ~0.28 b/t
- Ground friction factor: 0.546
- Sprint jump boost: +0.2 horizontal velocity (pre-drag)
- Max sprint jump: ~4.5 blocks flat, ~5 blocks with 1-block drop
- Player standing on block at y=-55 has feet at y=-54
- `feetToTargetDy = target.getY() - player.getY()`; negative = target block is lower

## Testing

- In-game tests in `test/PathWalkerTests.java` (registered by PathfindingModule)
- In-game tests in `test/EnderPearlTests.java` (registered by PathfindingModule) — tests EnderPearlTravelMethod at 10/20/30 block flat, uphill, and downhill distances
- In-game tests in `test/MeshTests.java` — the mesh on real terrain: grass and flowers, a two-high corridor, an open diagonal (no planned jumps), every walking link across a chunk corner, and a journey up a staircase above its starting band
- JUnit tests in `src/test/.../MeshPathfinderTest.java` (A* algorithm verification)
- Run in-game tests: `/stracciatella-test` after joining a world

### One course has a floor

Every course in `PathWalkerTests` is parkour — single blocks with air between —
so a jump is the right answer everywhere in them, and none of them could ever
ask whether the walker jumps where it should not. *Flat corner without a hop* is
the exception: ten blocks, a right-angle turn, ten more, all solid and all at
one height, and the player's Y may not leave it (`FLOOR_HOP_TOLERANCE`, 0.3,
against a jump that clears a whole block). It is the same `runMultiCourse` with
adjacent waypoints — a contiguous chain of them *is* a floor, and the node list
of a mesh path across one — plus a flag that turns leaving the ground into a
failure. The turn is the point: arrival is accepted up to 0.65 from a node's
centre, and a bot braking into a corner is the one that ticks a node off from
the far edge of that.

### A course has to be checked, not just placed

`buildMultiCourse` places its blocks and then confirms they arrived. A
`setblock` into a chunk the server has not loaded yet is refused outright —
*That position is not loaded* in chat, no block, and no error the caller can
see — while the client's own `hasChunkAt`, which the setup waits on, answers
yes before the server agrees. A course reaching into the next chunk therefore
came out with holes in it and the start teleport dropped the player through
one, so the walk failed as `Player fell` a hundred blocks under a course it had
never stood on. First repeat only, every time: by the second the blocks of the
first are still standing. `BUILD_ATTEMPTS` (5) rounds of placing back whatever
is still air, then the fixture says so rather than the walk taking the blame.

### EnderPearl cooldown gating in tests

`EnderPearlTests.runPearlTest` polls `!mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))` after giving pearls but before calling `EnderPearlTravelMethod.start()`. Vanilla applies a 20-tick per-throw cooldown. Under accelerated ticks, the inter-test interval can be shorter than this cooldown, and a throw during an active cooldown is silently discarded by the server (key press ignored). The client `ItemCooldowns` mirrors the server via `ClientboundCooldownPacket`, so this poll is authoritative.

`EnderPearlTravelMethod` itself does not check the cooldown — callers are expected to throw only when a throw is possible. Adding cooldown handling inside the method would be a compensating wrapper around a test-harness ordering issue.

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
│   ├── MeshManager.java              # Stores meshes per entity per chunk, handles cross-chunk linking, node lookup
│   ├── MeshPathfinder.java           # A* algorithm with admissible Euclidean heuristic (scale=9), closed set
│   └── mesh/
│       ├── Mesh.java                 # HashMap<BlockPos, MeshNode> container for one chunk
│       ├── MeshNode.java             # Graph vertex: x, y, z + List<Neighbor>. Has equals/hashCode on (x,y,z)
│       ├── Neighbor.java             # Weighted edge: target node + cost
│       └── IMeshProvider.java        # Interface for mesh sources
├── travel/
│   ├── TravelMethod.java             # Interface: pluggable movement method (id, canUse, cost, start, tick, abort)
│   ├── TravelStatus.java             # Enum: IN_PROGRESS, SUCCEEDED, FAILED
│   ├── Navigator.java                # Static coordinator: auto-selects best method, manages fallback chain
│   ├── WalkTravelMethod.java         # Wraps PathWalker for mesh-based walking
│   └── EnderPearlTravelMethod.java   # Ender pearl throw with trajectory simulation
├── mixin/
│   └── LevelChunkMixin.java          # Triggers mesh generation on chunk load
└── test/
    ├── PathWalkerTests.java          # In-game tests: straight-line + L-shaped path walking
    └── EnderPearlTests.java          # In-game tests: ender pearl throwing at various distances/elevations
```

## Key data flow

1. **Mesh generation**: Chunk loads → `LevelChunkMixin` → `MeshManager.generateMesh()` → `ChunkMeshBuilder` scans blocks, creates MeshNodes where player can stand (solid block + 2 air above), connects neighbors via reachability checks → stores in `MeshManager.meshes` → reconnects border nodes with adjacent chunks
2. **Pathfinding**: `/path find` → `MeshPathfinder.findPath(start, end)` → A* search → returns `List<MeshNode>`
3. **Path walking**: `PathWalker.start(path)` → each tick: get target node, calculate jump decision, update aim/rotation, apply movement keys → when node reached, advance index → when path done, `stop()`
4. **Navigation**: `/navigate to <x> <y> <z>` → `Navigator.navigate(target)` → evaluates all registered `TravelMethod`s via `canUse()`/`cost()` → picks cheapest → `start()` → ticks until `SUCCEEDED`/`FAILED` → on failure, tries next fallback method

## PathWalker — the core component

PathWalker is entirely **static**. It simulates keyboard input (forward, sprint, jump keys) — it does NOT teleport or set position directly. Camera control (yaw/pitch smoothing) is delegated to `CameraController` from the **camera** module.

### Important concepts

- **`gap`** = `Math.max(abs(target.X - playerX), abs(target.Z - playerZ))` — includes both endpoints. gap=5 means 4 air blocks ("4-block jump" in parkour terms)
- **`longRangeJump`** = effectiveGap ≥ 5. Uses collision-based edge detection instead of simulation. Gap=4 uses simulation (collision-edge overshoots single-block platforms at that distance)
- **Node arrival**: sphere check (distance < 0.18) OR box check within block bounds with 0.15 margin
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

### Key mechanics

- **Retreat phase** (gap ≥ 5): Player walks backward to back edge of block to maximize sprint runway. Uses a fixed origin reference (recorded when retreat starts) to prevent backProgress from resetting when crossing block boundaries on single-block platforms
- **Sprint suppression** (gap = 2): Sprint set to false on jump tick to prevent overshooting single-block platforms. Exception: any diagonal gap=2 (both axes non-zero) enables sprint because the euclidean distance (≥ sqrt(5) ≈ 2.24) exceeds non-sprint jump range (~1.8 blocks).
- **Hold movement block** (gap ≤ 2): When simulation/edge-distance says "hold", forward movement is blocked to prevent walking past the edge. Exception: any diagonal gap=2 skips this block — the simulation's air acceleration model (10x too low) can't predict diagonal sprint-jump landings, so its hold would deadlock the player. The edge-distance fallback handles diagonal timing instead.
- **Landing brake**: Activates after any jump landing (prevSegGap ≥ 2) on an intermediate platform when more path follows. At high speed (>0.1 b/t), faces velocity direction and presses backward to actively decelerate. At low speed (≤0.1 b/t), releases all keys and lets friction handle it. Targets: gap≤1→0.03, gap≤2→0.04, gap≥3→0.08
- **Post-brake air release**: After a landing brake + gap=2 jump, releases forward key within 1.0 blocks of target to prevent air acceleration overshoot
- **Pre-landing air deceleration**: When airborne approaching an intermediate platform from a gap≥3 jump with a sharp turn (≥60°) ahead, releases forward key within 1.0 blocks to reduce landing speed
- **Jump facing tolerance**: Gap=2 uses tighter tolerance (18°) than gap≥3 (36°) because single-block landing platforms have no margin for angular error
- **Direction-aware collision-edge**: Only shrinks AABB in the gap axis direction, preventing false triggers from perpendicular drift on single-block platforms
- **Skip-retreat threshold**: 0.14 — below this speed, the player retreats; above, they sprint straight through. Prevents players from attempting collision-edge jumps without enough speed

### Timing model

All PathWalker logic is **tick-based** — no wall-clock time dependencies. This means behavior is identical regardless of tick rate (e.g. during accelerated tests at 200 TPS). The only `System.currentTimeMillis()` usage is for debug output throttling (cosmetic, not functional).

- **Alignment hold** (`alignmentHoldTicks`, default 5): After camera aligns with the target direction, holds forward movement enabled for this many ticks even if angle drifts slightly. Prevents stop-start jitter on turns. Configurable via `/path walkconfig alignhold <ticks>`.

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
- Solid block at Y, air at Y+1, air at Y+2 → walkable node at (X, Y, Z)
- Search limits: horizontal=5, up=3, down=5, max safe drop=3, diagonal=5

### Neighbor connection
- Brute-force search within radius for each node
- `isBlockReachable()`: validates height constraints, diagonal limits, line-of-sight (Bresenham)
- `movementCost()`: gap=1→10, gap=2→22, gap=3→40, gap=4→65, gap>4→100, plus height/diagonal penalties

### Cross-chunk connectivity
- `MeshManager.connectAdjacentMeshes()` links border nodes when neighbor chunks exist
- `reconnectBorderNodes()` rebuilds edges for nodes on chunk boundaries

### Mesh invalidation on block changes

`LevelChunkMixin.setBlockState` triggers `MeshManager.invalidateMesh` when:
1. The state change is an air↔solid flip (sub-state edits like waterlogged or growth stages don't affect walkability — skipped).
2. The chunk's level is the client's level (filters out the server-thread fire in single-player so we don't regenerate twice against stale data).
3. At least one entity already has a mesh for that chunk (no point burning CPU on chunks no bot uses).

Without this, mining a wall would leave the mesh thinking the wall is still solid, and subsequent A* searches route around the hole the bot just dug. Meshes are still built on-demand (chunk-load is intentionally a no-op).

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
- JUnit tests in `src/test/.../MeshPathfinderTest.java` (A* algorithm verification)
- Run in-game tests: `/stracciatella-test` after joining a world

### EnderPearl cooldown gating in tests

`EnderPearlTests.runPearlTest` polls `!mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))` after giving pearls but before calling `EnderPearlTravelMethod.start()`. Vanilla applies a 20-tick per-throw cooldown. Under accelerated ticks, the inter-test interval can be shorter than this cooldown, and a throw during an active cooldown is silently discarded by the server (key press ignored). The client `ItemCooldowns` mirrors the server via `ClientboundCooldownPacket`, so this poll is authoritative.

`EnderPearlTravelMethod` itself does not check the cooldown — callers are expected to throw only when a throw is possible. Adding cooldown handling inside the method would be a compensating wrapper around a test-harness ordering issue.

# Miner Module

Specialized mining behaviors built on the bot module's behavior layer. The first behavior is a diamond strip miner that digs a staircase down to the layer 3 blocks above bedrock and tunnels forward, chasing every diamond ore that comes into reach.

## Architecture

```
net.stracciatella.miner
├── MinerModule.java            # Entry point: loads config, registers behavior/commands/tests
├── MinerConfig.java            # GSON-persisted config (miner.json)
├── MinerCommands.java          # /miner subcommands
├── DiamondMinerBehavior.java   # BotBehavior: LOCATE → DESCEND → TUNNEL strategy
└── test/
    └── MinerTests.java         # In-game integration tests
```

The module never touches input or the camera. `DiamondMinerBehavior` plans steps as batches of `MineBlockTask`s on the bot module's `BotController`, waits until the controller is idle (task executed, drops collected), verifies the result, and plans the next step. All human-like mechanics (camera, timing, tool selection, collection) come from the task layer for free.

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

## Commands (`/miner`)

| Command | Description |
|---------|-------------|
| `/miner start [tunnelLength]` | Start the diamond miner (default length from config) |
| `/miner stop` | Stop the behavior (equivalent to the behavior part of `/bot stop`) |
| `/miner status` | Show the active behavior status line |

## Config

Persisted to `stracciatella/miner.json`:

- `floorOffsetAboveBedrock` — tunnel feet height above the highest bedrock in the start column (default 3)
- `defaultTunnelLength` — tunnel length when `/miner start` has no argument (default 32)
- `oreScanRadius` — diamond scan radius around the player (default 4 ≈ mining reach, so found ore never requires navigation away from the tunnel)
- `maxStepRetries` — re-enqueues per step before giving up (default 2)

## Dependencies

- `loader` (compileOnly) — Module interface
- `bot` (compileOnly) — BehaviorRunner/BotBehavior, BotController, MineBlockTask, BlockScanner
- `testing` (compileOnly) — TestRunner, test annotations

## Testing

Tests in `test/MinerTests.java`, run via `./gradlew runMinecraftTests`. Both build a deterministic deep-mining sandbox (bedrock floor at -64, solid deepslate above, air on top):

- **Miner tunnels at depth** — player starts in a pocket at tunnel height; tunnel 5 slices, one ore in the path; expects 1 diamond and a fully dug tunnel.
- **Miner descends and mines diamonds** — player starts 3 levels above tunnel height; staircase descent, 6 slices, one path ore plus one side-wall ore (mined via the ore scan, not the slices); expects 2 diamonds.

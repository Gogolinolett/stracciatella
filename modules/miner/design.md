# Miner Module — Design Decisions

## Advancing the player: ride the controller's collect-walk instead of a MoveTo task

**Decision**: The behavior never explicitly moves the player. It plans the next dig batch and relies on the existing task-layer movement: drops from the freshly dug step pull the player forward via COLLECTING's walk-to-drop, and any target that ends up out of reach is closed by the controller's SCANNING/POSITIONING when its task starts.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| New `MoveToTask` task type | Explicit control over the player's position | The whole task pipeline (LOOKING/INTERACTING gates, break confirmation, collect) is built around block interaction — a movement-only task would need special-casing in nearly every controller phase. |
| Behavior presses movement keys directly | No bot-module change | Breaks the layering contract: two systems driving input simultaneously is exactly the failure mode the behavior layer exists to prevent. |
| Ride collect-walk + positioning (chosen) | Zero new mechanics; the forward drift is the natural "step into the space you just dug to pick up the drops" a human shows. POSITIONING already covers the out-of-reach fallback, mesh or not. | Advancement is indirect — if a slice yields no drops the player only catches up when the next task goes out of reach (1–2 slices later). Acceptable: reach is 4, slices advance 1. |

Chose riding the existing movement because every alternative adds a parallel movement path that can fight the human-like one already tuned in the task layer.

## Fail-fast hazards instead of hazard handling

**Decision**: Liquids ahead/overhead, bedrock in the dig path, missing floor, and open caves abort the run with a reason (`FAILED` + status line). Only two soft cases exist: a single-slice air pocket is tunneled through, and an unreachable/blocked **ore** is skipped via a remembered skip-set.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Handle hazards (place blocks against lava, bridge holes, light caves) | Autonomous long sessions | Requires block *placement*, inventory management and a USE interaction path — none of which the task layer has yet. Each is its own project; bolting naive versions on risks drowning the bot in lava on the first edge case. |
| Ignore hazards | Simplest | The bot walks into lava at diamond level — the single most common way to lose a session. |
| Fail fast with a reason (chosen) | The dangerous cases become clean, explainable stops; the user repositions and restarts. Matches the project's "authoritative state over hope" testing philosophy. | Runs end early in hazardous terrain. |
| Skip-and-reroute around hazards | Keeps mining | Re-planning the tunnel line around obstacles is real pathfinding through unmined volume — out of scope for v1, and partially covered anyway by restarting in a new direction. |

Chose fail-fast because the cost of a wrong hazard guess (lava) is total, while the cost of a clean stop is one chat message. Ore-skip is the exception: a single unreachable ore is local, harmless, and shouldn't kill an hour-long run.

## Ore acquisition: walkable 1×2 side gallery, no navigation

**Decision**: `oreScanRadius` defaults to mining reach (4). A found ore is acquired by digging a 4-connected line of 1×2 columns (foot + head at tunnel height) from the player's column to the ore's column, then the ore itself. Ores from one below the gallery floor (mined from above) to one above its ceiling (mined from below) are reachable; out-of-layer ores and galleries crossing bedrock/liquid go to the skip-set. Every planned block is recorded in an `excavated` set so the tunnel's cave check never mistakes the run's own galleries for natural caves.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Thin eye→ore line-of-sight exposure (first attempt) | Fewest blocks | Failed in practice: the line is computed from the planning position, but deeper line blocks stay hidden *behind* the still-solid foot blocks (the hit-result gate raycast lands on the blocker → 18× "Look timeout" in one test run), and the player cannot follow a non-walkable pinhole. Also its diggings turned future tunnel slices to air, which the cave check then mis-read as "cave ahead" and failed the run. |
| Large scan radius + navigate to ore | Finds more diamonds per tunnel | Navigation needs mesh nodes, which don't exist inside solid rock; reaching distant ore means excavating access tunnels — a planning problem far beyond v1. Also drags the bot away from the protected tunnel into unchecked terrain. |
| Walkable 1×2 gallery + excavated-set (chosen) | Each column is frontally visible from the previous one, and the collect-walk pulls the player in after every block — so the hit-result gate always sees its target up close. 4-connected (one axis step per column) because a diagonal line leaves corner walls the player can't pass. The excavated set cleanly separates "our air" from "cave air". | Roughly 2× the blocks of a thin line; misses ore more than ~4 blocks off the tunnel and outside the 4 reachable layers — by design, strip-mining density comes from tunnel length. |
| Mine a 1×1 access shaft to each ore | Reaches anything | Leaves the floor/ceiling riddled with holes the collect-walk can fall into; not walkable, same visibility problem as the thin line. |

Chose the gallery because the thin-line variant demonstrably fails on the two mechanics everything else relies on (hit-result visibility and collect-walk advancement), while the gallery rides exactly those mechanics: it is the shape a human digs to an ore they spotted in the wall.

**Companion change — sequential batches instead of one bulk plan**: enqueuing the whole gallery (or a whole staircase step) as one batch failed in testing: the controller's nearest-task selection reorders the tasks by distance, which targets blocks farther down the gallery while they are still hidden behind unmined columns (9 look-timeouts on a single head block in one run; stair feet additionally triggered 400-tick server-rejected break timeouts when aimed at their covered top face). Steps therefore queue **follow-up batches** (`batchQueue`) that execute strictly one after another with verification and collect-walk in between: gallery = one column per batch + the ore as final batch; staircase step = ceiling+head, then the foot. The pause between batches is the same "dig a bit, step in, dig on" rhythm a human shows.

**Companion change — ore chasing only from tunnel level**: per-failure look diagnostics (`player=(.., -59.00, 1002.30) hitResult=(2300,-59,1004)`) showed the remaining failure mode: with the player still on the staircase, every gallery face lies below his eye line and the gallery *ceiling* blocks the ray — galleries are only workable while standing at `tunnelFeetY`. The scan-and-chase step is therefore gated on `player.blockPosition().getY() == tunnelFeetY`; until then the behavior digs the next tunnel slice, whose drops pull the player down to level via the collect-walk. This is also just what strip mining is: you work veins from your tunnel, not hanging off the staircase.

## Staircase descent geometry: 3-block steps, fail on all-air

**Decision**: DESCEND digs 1 step per batch: foot, head, and ceiling clearance (3 blocks) at one-forward-one-down from the cursor, requiring a solid block below the new foot. A step that is already fully open (all three air) fails the run as "cave ahead during descent".

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| 2-block steps (foot + head only) | Fewer blocks | The ceiling block at the old head height clips the player's head as they walk down the 1-block drop — they get stuck on the lip. 3 high is the minimum for walking down a 1-deep step. |
| Vertical shaft + pillar down | Fastest descent | Digging straight down is the canonical way to die; also the player can't follow a vertical shaft via collect-walk. |
| Staircase, tolerate all-air steps (walk down through caves) | Keeps going | Descending into an unlit cave without combat/lighting support is a session-loss generator; fail-fast matches the hazard policy. Partial-air steps (1–2 blocks already open) are still dug normally. |

Chose the 3-block staircase because it is the shape a careful human digs, every block of it is verified solid-or-cleared before the player walks it, and the collect-walk naturally pulls the player down each finished step.

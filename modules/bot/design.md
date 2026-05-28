# Bot Module — Design Decisions

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

## Server-side held-item sync in tool selection

**Decision**: `InventoryHelper.selectBestTool` sends `ServerboundSetCarriedItemPacket` every time it selects a slot, even if client-side `selectedSlot` is already at that index.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| `setSelectedSlot` only (original) | Minimal | Client-only change; server's held-item state may diverge and break/drop calculations use the wrong tool. |
| Send packet only when `bestSlot != currentSlot` | Slight bandwidth saving | Requires trusting that client and server are in sync — which they're not always, especially across test tests. |
| Send packet always (chosen) | Cheap, idempotent, guarantees server agreement | One extra packet per interaction start (negligible) |

Chose to always send because the packet is tiny, idempotent, and a stale server-side slot is a silent-failure mode (drops computed with wrong tool). Worth the trivial cost.

## First-attack settle after tool select

**Decision**: In INTERACTING, after `InventoryHelper.selectBestTool` runs, hold off on the first attack for `CONFIG.toolSettleTicks` ticks (default 4) before letting `BlockInteractor` start.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| No delay (original) | Minimal | Under accelerated ticks, a fast block can break within ~20 ticks of entering INTERACTING. That's ~100ms wall time at 10x — less than packet round-trip. The server's held-slot may still be the previous value when the break resolves, producing the wrong drop (or none at all). Reproduced in `mineQueue` at 2/5 failure rate. |
| Fixed `toolSettleTicks` wait + per-tick packet re-send (chosen) | Deterministic. 8 ticks at 10x ≈ 40ms wall + per-tick carried-item re-send defends against the server processing the attack before the carried-item packet. Applies only to the first attack per target. Cheap and robust. | Adds a small constant delay per block and ~8 tiny packets per settle |
| Wait on observed server ack (e.g. hook `ClientboundSetCarriedItemPacket`) | Authoritative — no guessing | Extra mixin surface; the echo from a `ServerboundSetCarriedItemPacket` isn't always re-sent to the client |
| Retry if the drop doesn't appear | Lazy | Requires per-task expected-drop metadata, which `BotTask` doesn't carry |

Chose the fixed settle because it's a one-line change gated on the existing `toolSelected` first-tick branch, costs a negligible amount of wall time, and matches the same pattern used by `airConfirmTicks` for the other end of the break.

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

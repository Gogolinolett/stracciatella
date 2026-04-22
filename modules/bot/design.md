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

## Item-entity search radius (8 blocks)

**Decision**: `player.getBoundingBox().inflate(8.0)` for the item-entity AABB query in `tickCollecting`.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| 4 blocks (= reachDistance) | Matches mining reach | Drops can bounce 2–3 blocks from the mined block, so a 4-block AABB from the standoff position can miss drops 5–6 blocks away. |
| 8 blocks (chosen) | Covers bounce radius | In a real session may match unrelated drops from another player |
| 16 blocks | Always finds every drop | Larger scan cost, more false matches from nearby mobs dropping items |

Chose 8 blocks because it covers the worst-case bounce while keeping the scan cheap. The `lastMinedPos` walk ensures we actively move toward our own drop rather than waiting passively.

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

## INTERACTING break confirmation window

**Decision**: INTERACTING requires the target block to be observed as air for `CONFIG.airConfirmTicks` consecutive ticks (default 8) before transitioning out. Non-air observations reset the counter.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Single-tick check `isCurrentTargetComplete(level)` (original) | Minimal | Under accelerated ticks the client's break prediction can race ahead of the server's. Client shows air, bot transitions, but server rejects the break and restores the block — no drop spawns. Failed `mineQueue` consistently. |
| 8-tick sustained-air window (chosen) | Ensures the server confirmed the break (if the server had rejected, the block would have been restored within 8 ticks and the counter would reset) | Adds ~40ms of real time per block break at 10x |
| Inspect server confirmation explicitly (listen for `ClientboundBlockUpdatePacket`) | Authoritative | Requires hooking into the packet pipeline — larger mixin surface area |
| Higher `/tick rate` on server to outpace client | Eliminates the race at the source | Can starve other server work; not all dev machines support it |

Chose the 8-tick window because it fixes the race with zero new infrastructure and the 40ms wait is negligible compared to the block-break time itself. 8 was chosen empirically: 3 was insufficient (mineQueue still failed), 15 was too long (other tests timed out). The counter reset on revert is load-bearing — it makes the bot automatically retry if the server rejects the break.

## Server-side held-item sync in tool selection

**Decision**: `InventoryHelper.selectBestTool` sends `ServerboundSetCarriedItemPacket` every time it selects a slot, even if client-side `selectedSlot` is already at that index.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| `setSelectedSlot` only (original) | Minimal | Client-only change; server's held-item state may diverge and break/drop calculations use the wrong tool. |
| Send packet only when `bestSlot != currentSlot` | Slight bandwidth saving | Requires trusting that client and server are in sync — which they're not always, especially across test tests. |
| Send packet always (chosen) | Cheap, idempotent, guarantees server agreement | One extra packet per interaction start (negligible) |

Chose to always send because the packet is tiny, idempotent, and a stale server-side slot is a silent-failure mode (drops computed with wrong tool). Worth the trivial cost.

## Item-entity search radius (8 blocks)

**Decision**: `player.getBoundingBox().inflate(8.0)` for the item-entity AABB query in `tickCollecting`.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| 4 blocks (= reachDistance) | Matches mining reach | Drops can bounce 2–3 blocks from the mined block, so a 4-block AABB from the standoff position can miss drops 5–6 blocks away. |
| 8 blocks (chosen) | Covers bounce radius | In a real session may match unrelated drops from another player |
| 16 blocks | Always finds every drop | Larger scan cost, more false matches from nearby mobs dropping items |

Chose 8 blocks because it covers the worst-case bounce while keeping the scan cheap. The `lastMinedPos` walk ensures we actively move toward our own drop rather than waiting passively.

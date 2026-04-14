# Pathfinding Module — Design Decisions

## A* heuristic scale factor

**Decision**: Use scale factor 9.0 for Euclidean heuristic.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Scale 10.0 (original) | Faster search, closer to actual cost | Inadmissible for diagonal moves (10*sqrt(2)=14.14 > 13), non-optimal paths |
| Scale 9.0 (chosen) | Admissible (9*sqrt(2)=12.73 < 13), guarantees optimal paths | Slightly more nodes explored |
| Octile distance | Perfectly tight admissible bound | More complex to implement, gains marginal |

Chose 9.0 because it's the largest integer scale that remains admissible given the cheapest diagonal move cost (10 base + 3 diagonal = 13). Simple change with guaranteed optimality.

## A* open-set management

**Decision**: Use lazy deletion with a closed set instead of open-set tracking.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Open-set tracker (original) | No duplicate records | Can't update priority for nodes already in queue; misses better paths |
| Lazy deletion + closed set (chosen) | Always uses best fCost; handles re-discovery correctly | Slightly larger priority queue from duplicates |
| Decrease-key with indexed PQ | Optimal queue size | Java PriorityQueue doesn't support decrease-key; requires custom PQ |

Chose lazy deletion because it fixes the correctness issue (stale priorities) with minimal code change. The closed set prevents reprocessing. Standard pattern for A* in Java.

## MeshNode equality semantics

**Decision**: Implement `equals()`/`hashCode()` based on `(x, y, z)` coordinates.

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Reference identity (original) | No implementation needed | Breaks HashMap lookups if different MeshNode instances share coordinates; fragile assumption |
| Coordinate equality (chosen) | Correct HashMap behavior; safe to create MeshNode instances anywhere | Must ensure coordinates uniquely identify a node within a mesh |

Chose coordinate equality because MeshNode is used as a HashMap key in MeshPathfinder (gScore, cameFrom) and compared via `.equals()` in tests and path reconstruction. Reference identity worked by accident but is a latent bug.

## Diagonal reachability distance limits

**Decision**: Check drop distance (4.5 limit) before same-level/step-up distance (4.0 limit).

### Alternatives
| Approach | Pros | Cons |
|----------|------|------|
| Check `dy >= -1` first (original) | Simpler order | `dy > 0` check at 4.5 was dead code — drops were capped at 4.0 |
| Check `dy > 0` first (chosen) | Both limits apply correctly: 4.5 for drops, 4.0 for flat/step-up | Slightly less obvious order |

Chose drops-first because it matches the original intent (drops should allow 4.5 diagonal distance). The more specific condition must be checked before the more general one.

package net.stracciatella.pathfinding.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.mesh.Mesh;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import net.stracciatella.pathfinding.logic.mesh.Neighbor;


public class ChunkMeshBuilder {

    /**
     * Horizontal reach of an edge. It is also how far into a neighbouring chunk
     * a node's links can point, so it bounds which nodes need relinking when a
     * neighbour's mesh changes.
     */
    public static final int EDGE_REACH = 5;
    /** A jump clears one block up; nothing higher is an edge. */
    private static final int MAX_UP = 1;
    private static final int MAX_DROP = 3;
    /**
     * Free cells above the floor a body needs to walk through a column — the
     * same two a node needs to exist. Asking three of a walk, as this once did,
     * left every two-high corridor with nodes and not a single edge between them.
     */
    private static final int WALK_HEADROOM = 2;
    /** And to jump through it: the head rises about a block on the arc. */
    private static final int JUMP_HEADROOM = 3;
    /**
     * Added to the distance of an edge, by Chebyshev gap. Walking costs its
     * distance and nothing more; a jump costs extra on top, growing steeply
     * with the gap so that A* strongly prefers walking over short jumps over
     * long parkour. Every jump therefore costs more than walking the same
     * ground, which is what keeps a planned route on flat ground free of hops.
     */
    private static final int[] JUMP_SURCHARGE = {0, 0, 4, 12, 30, 55};

    public Mesh generatePathfindingMesh(ChunkAccess chunk, Entity entity) {
        return generatePathfindingMesh(chunk, entity, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Same, but only scanning layers inside {@code [bandMinY, bandMaxY]}.
     *
     * <p>The full-column variant above reads the entire build height — 16x16x384
     * block states per chunk, roughly 98,000 of them, plus the neighbour pass.
     * That is the right default and stays the default: a caller that does not
     * say otherwise gets every walkable layer, and the diamond miner pathing far
     * underground depends on exactly that.
     *
     * <p>It is the wrong price for a long overland route, where the only layers
     * anyone will walk sit in a band around the traveller's own height. Hence a
     * parameter rather than a narrower default — a globally capped band would
     * silently take nodes away from callers that never asked for a band, which
     * is a regression in a place that has nothing to do with travelling. The
     * mesh remembers its band, so a caller that needs more of the column gets
     * it rebuilt instead of silently missing layers (see
     * {@link MeshManager#ensureMesh}).
     */
    public Mesh generatePathfindingMesh(ChunkAccess chunk, Entity entity, int bandMinY, int bandMaxY) {
        Mesh newMesh = new Mesh(bandMinY, bandMaxY);
        List<MeshNode> nodes = new ArrayList<>();

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        int maxY = Math.min(chunk.getMaxY(), bandMaxY);
        int minY = Math.max(chunk.getMinY(), bandMinY);

        BlockPos.MutableBlockPos baseBlock = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY - 2; y++) {
                    baseBlock.set(minX + x, y, minZ + z);
                    if (Terrain.isStandable(chunk, baseBlock, entity)
                            && Terrain.isPassable(chunk, above.setWithOffset(baseBlock, 0, 1, 0))
                            && Terrain.isPassable(chunk, above.setWithOffset(baseBlock, 0, 2, 0))) {
                        MeshNode node = new MeshNode(baseBlock.getX(), baseBlock.getY(), baseBlock.getZ());
                        nodes.add(node);
                        newMesh.getNodes().put(baseBlock.immutable(), node);
                    }
                }
            }
        }

        // Links inside the chunk only. The ones that cross into a neighbour are
        // MeshManager's to make, because only it knows which neighbours exist.
        rebuildNeighbors(chunk, newMesh.getNodes()::get, nodes);

        return newMesh;
    }

    /**
     * Nodes of {@code mesh} close enough to the side or corner of {@code chunk}
     * facing {@code (dx, dz)} to have links into the chunk over there.
     */
    public void collectBorderNodes(Mesh mesh, ChunkCoordinate chunk, int dx, int dz, Collection<MeshNode> into) {
        int minX = chunk.x() * 16;
        int maxX = minX + 15;
        int minZ = chunk.z() * 16;
        int maxZ = minZ + 15;
        for (MeshNode node : mesh.getNodes().values()) {
            boolean inX = true;
            boolean inZ = true;
            if (dx > 0) {
                inX = node.getX() >= maxX - EDGE_REACH;
            } else if (dx < 0) {
                inX = node.getX() <= minX + EDGE_REACH;
            }
            if (dz > 0) {
                inZ = node.getZ() >= maxZ - EDGE_REACH;
            } else if (dz < 0) {
                inZ = node.getZ() <= minZ + EDGE_REACH;
            }
            if (inX && inZ) {
                into.add(node);
            }
        }
    }

    /**
     * Recompute every link of {@code toRebuild} from scratch.
     *
     * <p>{@code nodes} resolves a position to its node wherever that node lives.
     * A node's links are always computed against everything that resolves, never
     * against a pair of meshes: the list is replaced, not merged, so a link left
     * out of the lookup is a link deleted. Relinking chunk borders one neighbour
     * at a time did exactly that — a node near a chunk corner kept only the links
     * into whichever neighbour came last, and which one that was depended on the
     * order the chunks had been meshed in.
     */
    public void rebuildNeighbors(BlockGetter level, Function<BlockPos, MeshNode> nodes, Collection<MeshNode> toRebuild) {
        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();
        for (MeshNode node : toRebuild) {
            List<Neighbor> neighbors = new ArrayList<>();
            BlockPos source = node.getBlockPos();

            for (int dy = -MAX_DROP; dy <= MAX_UP; dy++) {
                for (int dx = -EDGE_REACH; dx <= EDGE_REACH; dx++) {
                    for (int dz = -EDGE_REACH; dz <= EDGE_REACH; dz++) {
                        if (dx == 0 && dz == 0) continue;

                        targetPos.set(node.getX() + dx, node.getY() + dy, node.getZ() + dz);
                        MeshNode candidate = nodes.apply(targetPos);
                        if (candidate != null && isBlockReachable(level, source, targetPos)) {
                            neighbors.add(new Neighbor(candidate, movementCost(dx, dz, dy)));
                        }
                    }
                }
            }

            node.setNeighbors(neighbors);
        }
    }

    private boolean isBlockReachable(BlockGetter level, BlockPos source, BlockPos target) {
        int dx = Math.abs(target.getX() - source.getX());
        int dz = Math.abs(target.getZ() - source.getZ());
        // > 0: the target is lower (a drop); < 0: it is higher (a jump up).
        int drop = source.getY() - target.getY();

        if (drop < -MAX_UP || drop > MAX_DROP) {
            return false;
        }

        if (dx > 0 && dz > 0) {
            // The walker's step-up fires on its distance to the target block's
            // centre, and a diagonal approach meets the block's corner first and
            // slides along it. No diagonal step-ups, of any length.
            if (drop < 0) {
                return false;
            }
            // Realistic diagonal jump reach: a sprint jump covers about 4.0 on
            // the level and a little more when it lands lower (STR-005).
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDistance > (drop > 0 ? 4.5 : 4.0)) {
                return false;
            }
        }

        boolean jump = Math.max(dx, dz) > 1 || drop < 0;
        if (!isLineClear(level, source, target, jump ? JUMP_HEADROOM : WALK_HEADROOM)) {
            return false;
        }

        if (drop > 0) {
            // Stepping off an edge, the body enters the target column at its own
            // height and falls from there, so that column has to be free from
            // head height down to the target's own feet. The target node only
            // vouches for the two cells above its floor.
            BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
            for (int y = target.getY() + 1; y <= source.getY() + WALK_HEADROOM; y++) {
                if (!Terrain.isPassable(level, cell.set(target.getX(), y, target.getZ()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a body can pass from {@code source}'s column towards
     * {@code target}'s at the source's height, with {@code headroom} free cells:
     * the source column itself and every column the straight line crosses on
     * the way, plus both side columns wherever the line steps diagonally, so a
     * route cannot cut a corner. The target column is not asked — its node
     * vouches for it, and for a step up its floor sits at the source's head
     * height by definition.
     */
    private boolean isLineClear(BlockGetter level, BlockPos source, BlockPos target, int headroom) {
        int x0 = source.getX();
        int z0 = source.getZ();
        int x1 = target.getX();
        int z1 = target.getZ();
        int y = source.getY();

        if (!isColumnClear(level, x0, y, z0, headroom)) {
            return false;
        }

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;

        int err = dx - dz;
        int x = x0;
        int z = z0;

        while (x != x1 || z != z1) {
            int prevX = x;
            int prevZ = z;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }

            if (x != prevX && z != prevZ
                    && (!isColumnClear(level, x, y, prevZ, headroom) || !isColumnClear(level, prevX, y, z, headroom))) {
                return false;
            }
            if ((x != x1 || z != z1) && !isColumnClear(level, x, y, z, headroom)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Edge cost: its horizontal distance (10 per block), a jump surcharge by
     * gap, and a height term — {@code dy > 0} is a jump up and costs more than
     * the same drop. A step up is a jump too, and is surcharged at least like
     * the shortest real one: priced as a walk plus a little height, it made
     * climbing over a single block cheaper than two diagonal steps round it,
     * and the bot went over every barrel and furnace in its way. The distance
     * part is what the A* heuristic is a lower bound of, see
     * {@link MeshPathfinder}.
     */
    private static int movementCost(int dx, int dz, int dy) {
        int gap = Math.max(Math.abs(dx), Math.abs(dz));
        int distance = (int) Math.round(10.0 * Math.sqrt(dx * dx + dz * dz));
        int heightCost = dy > 0 ? dy * 5 : Math.abs(dy) * 2;
        int surcharge = JUMP_SURCHARGE[dy > 0 ? Math.max(gap, 2) : gap];
        return distance + surcharge + heightCost;
    }

    private boolean isColumnClear(BlockGetter level, int x, int y, int z, int headroom) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= headroom; i++) {
            if (!Terrain.isPassable(level, cursor.set(x, y + i, z))) {
                return false;
            }
        }
        return true;
    }
}

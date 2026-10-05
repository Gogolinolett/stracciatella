package net.stracciatella.pathfinding.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.stracciatella.pathfinding.ChunkCoordinate;
import net.stracciatella.pathfinding.logic.mesh.Mesh;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;

public class MeshManager {

    public static HashMap<Entity, HashMap<ChunkCoordinate, Mesh>> meshes = new HashMap<>();
    static ChunkMeshBuilder meshBuilder = new ChunkMeshBuilder();
    /**
     * How far around a position {@link #findOrBuildNearestNode} widens an existing
     * banded mesh that does not reach it — enough for the node under the
     * position and a step either way.
     */
    private static final int NEAREST_BAND = 4;
    /** Chunks whose blocks changed since the last {@link #flushInvalidations}. */
    private static final Set<ChunkCoordinate> dirty = new LinkedHashSet<>();

    /**
     * Note that a chunk's blocks changed. The rebuild waits for the next
     * {@link #flushInvalidations}, so a {@code /fill} or an explosion costs one
     * rebuild per chunk instead of one per block.
     */
    public static void invalidateMesh(ChunkCoordinate chunkCoordinate) {
        dirty.add(chunkCoordinate);
    }

    /**
     * Rebuild every chunk noted by {@link #invalidateMesh}, for every entity that
     * has a mesh of it, over the band it was built with. Runs at the start of
     * each client tick, ahead of everything that plans on the meshes.
     */
    public static void flushInvalidations(Minecraft client) {
        if (dirty.isEmpty()) {
            return;
        }
        Level level = client.level;
        if (level != null) {
            for (ChunkCoordinate chunkCoordinate : dirty) {
                meshes.forEach((entity, forEntity) -> {
                    Mesh old = forEntity.get(chunkCoordinate);
                    if (old != null) {
                        generateMesh(level.getChunk(chunkCoordinate.x(), chunkCoordinate.z()), entity,
                                old.bandMinY(), old.bandMaxY());
                    }
                });
            }
        }
        dirty.clear();
    }

    public static void generateMesh(ChunkAccess chunk, Entity entity) {
        generateMesh(chunk, entity, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Same, but only scanning layers inside {@code [bandMinY, bandMaxY]} — see
     * {@link ChunkMeshBuilder#generatePathfindingMesh(ChunkAccess, Entity, int, int)}
     * for why the band is a parameter and not a cheaper default.
     */
    public static void generateMesh(ChunkAccess chunk, Entity entity, int bandMinY, int bandMaxY) {

        Mesh mesh = meshBuilder.generatePathfindingMesh(chunk, entity, bandMinY, bandMaxY);
        if (!meshes.containsKey(entity)) {
            meshes.put(entity, new HashMap<>());
        }

        ChunkCoordinate chunkCoordinate = new ChunkCoordinate(chunk);
        meshes.get(entity).put(chunkCoordinate, mesh);
        connectAdjacentMeshes(entity, chunkCoordinate);

    }

    /**
     * Make sure the chunk has a mesh covering {@code [bandMinY, bandMaxY]}. A
     * mesh that exists but was built for another band is rebuilt over both, so
     * a caller never plans on layers that were simply never scanned. Unloaded
     * chunks are left alone (see {@link #isChunkLoaded}).
     *
     * @return whether a mesh was built
     */
    public static boolean ensureMesh(Level level, Entity entity, ChunkCoordinate chunkCoordinate,
                                     int bandMinY, int bandMaxY) {
        Mesh existing = mesh(entity, chunkCoordinate);
        if (existing != null && existing.covers(bandMinY, bandMaxY)) {
            return false;
        }
        if (!isChunkLoaded(level, chunkCoordinate)) {
            return false;
        }
        int minY = existing == null ? bandMinY : Math.min(existing.bandMinY(), bandMinY);
        int maxY = existing == null ? bandMaxY : Math.max(existing.bandMaxY(), bandMaxY);
        generateMesh(level.getChunk(chunkCoordinate.x(), chunkCoordinate.z()), entity, minY, maxY);
        return true;
    }

    /**
     * {@link #ensureMesh} for every chunk of the rectangle spanned by {@code a}
     * and {@code b}, widened by {@code padChunks} on each side so a route has
     * room to go around what stands in the way.
     */
    public static void ensureArea(Level level, Entity entity, BlockPos a, BlockPos b, int padChunks,
                                  int bandMinY, int bandMaxY) {
        int minX = (Math.min(a.getX(), b.getX()) >> 4) - padChunks;
        int maxX = (Math.max(a.getX(), b.getX()) >> 4) + padChunks;
        int minZ = (Math.min(a.getZ(), b.getZ()) >> 4) - padChunks;
        int maxZ = (Math.max(a.getZ(), b.getZ()) >> 4) + padChunks;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ensureMesh(level, entity, new ChunkCoordinate(x, z), bandMinY, bandMaxY);
            }
        }
    }

    /** Whether a mesh for this chunk is already built and cached. */
    public static boolean hasMesh(Entity entity, ChunkCoordinate chunkCoordinate) {
        return mesh(entity, chunkCoordinate) != null;
    }

    /**
     * Whether the client actually holds block data for this chunk.
     *
     * <p>Outside render distance it does not, and {@code level.getChunk} hands
     * out an empty chunk instead of saying so. Building a mesh from that does not
     * merely produce a useless mesh for the probed chunk — it <em>destroys chunk
     * (0,0)</em>. The client keeps exactly one placeholder, created in
     * {@code ClientChunkCache} as {@code new EmptyLevelChunk(level, new
     * ChunkPos(0, 0), PLAINS)} and handed back for every out-of-range request, so
     * the mesh {@link #generateMesh} keys by the chunk's own position lands on
     * (0,0), empty, over whatever was there — and then reconnects the neighbours'
     * border nodes to the hole. Nothing ever rebuilds it, since chunk load
     * deliberately does not invalidate meshes. One probe of a far-away target
     * therefore poisons the cache for the rest of the session, and probing a
     * far-away target is the first thing a leg-by-leg journey does. Every build
     * site asks this first.
     */
    public static boolean isChunkLoaded(Level level, ChunkCoordinate chunkCoordinate) {
        if (level == null) {
            return false;
        }
        // Explicitly FULL and explicitly "do not load". The obvious spelling,
        // level.hasChunk, is not usable here: on the client it answers yes for a
        // chunk the client does not have, because the client's chunk source hands
        // out a shared empty chunk as a placeholder. Measured — a probe 8000
        // blocks away reported loaded, and reading block states out of it returned
        // air for solid stone, which is exactly the silent wrong answer this
        // method exists to prevent.
        return level.getChunk(chunkCoordinate.x(), chunkCoordinate.z(), ChunkStatus.FULL, false) != null;
    }

    /**
     * Drop cached chunk meshes further than {@code radius} chunks from
     * {@code center}. Safe while a walk is in flight: {@link PathWalker} holds
     * the {@link MeshNode} objects of its path in a list of its own, so the nodes
     * outlive the map they came from — dropping a mesh costs a rebuild on the
     * next plan, never a broken path.
     *
     * <p>Needed because {@code meshes} was never pruned anywhere. That is
     * harmless as long as nobody paths outside their own work site; a run that
     * travels to storage every few minutes would otherwise grow the map for the
     * whole session.
     *
     * <p>Dropping the map entry is only half of it. The meshes that stay still
     * hold links into the dropped ones, and through those every mesh ever built
     * stayed reachable — for the garbage collector, which freed nothing, and for
     * A*, which searched the whole session's stale graph behind the player. So
     * the remaining borders facing a dropped chunk are relinked too.
     *
     * @return how many meshes were dropped
     */
    public static int evictBeyond(Entity entity, ChunkCoordinate center, int radius) {
        var meshesForEntity = meshes.get(entity);
        if (meshesForEntity == null) {
            return 0;
        }
        List<ChunkCoordinate> dropped = new ArrayList<>();
        for (ChunkCoordinate coord : meshesForEntity.keySet()) {
            if (Math.abs(coord.x() - center.x()) > radius || Math.abs(coord.z() - center.z()) > radius) {
                dropped.add(coord);
            }
        }
        dropped.forEach(meshesForEntity::remove);

        Level level = Minecraft.getInstance().level;
        if (!dropped.isEmpty() && level != null) {
            Set<MeshNode> facing = new LinkedHashSet<>();
            for (ChunkCoordinate gone : dropped) {
                collectFacing(meshesForEntity, gone, facing);
            }
            meshBuilder.rebuildNeighbors(level, nodeLookup(meshesForEntity), facing);
        }
        return dropped.size();
    }

    /**
     * The mesh node for {@code pos}, building the chunk's mesh if there is none.
     *
     * <p>Callers mostly hand in where somebody stands, and that is the feet
     * block — air by definition, never a node. The node is the floor under it,
     * so that is asked second, before falling back to the nearest node of the
     * chunk. Going straight to "nearest" made the floor tie with any step beside
     * the player, both one block away, and the HashMap's order picked the start.
     */
    public static MeshNode findOrBuildNearestNode(Level level, Entity entity, BlockPos pos) {
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4);
        // A chunk nobody has meshed gets its whole column, as it always did. One
        // that was meshed for a band elsewhere is only widened to reach pos.
        boolean missing = !hasMesh(entity, chunkCoordinate);
        ensureMesh(level, entity, chunkCoordinate,
                missing ? Integer.MIN_VALUE : pos.getY() - NEAREST_BAND,
                missing ? Integer.MAX_VALUE : pos.getY() + NEAREST_BAND);
        var mesh = mesh(entity, chunkCoordinate);
        if (mesh == null) {
            return null;
        }
        MeshNode exact = mesh.getNodes().get(pos);
        if (exact != null) {
            return exact;
        }
        MeshNode floor = mesh.getNodes().get(pos.below());
        if (floor != null) {
            return floor;
        }
        MeshNode nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (MeshNode node : mesh.getNodes().values()) {
            double dx = node.getX() - pos.getX();
            double dy = node.getY() - pos.getY();
            double dz = node.getZ() - pos.getZ();
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                nearest = node;
            }
        }
        return nearest;
    }

    private static Mesh mesh(Entity entity, ChunkCoordinate chunkCoordinate) {
        var meshesForEntity = meshes.get(entity);
        return meshesForEntity == null ? null : meshesForEntity.get(chunkCoordinate);
    }

    /**
     * Link a freshly built chunk to its loaded neighbours: its own border nodes
     * and the neighbours' border nodes facing it, each relinked once against
     * every mesh the entity has.
     */
    private static void connectAdjacentMeshes(Entity entity, ChunkCoordinate chunkCoordinate) {
        var meshesForEntity = meshes.get(entity);
        if (meshesForEntity == null) {
            return;
        }
        Mesh center = meshesForEntity.get(chunkCoordinate);
        if (center == null) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Set<MeshNode> border = new LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (meshesForEntity.containsKey(new ChunkCoordinate(chunkCoordinate.x() + dx, chunkCoordinate.z() + dz))) {
                    meshBuilder.collectBorderNodes(center, chunkCoordinate, dx, dz, border);
                }
            }
        }
        collectFacing(meshesForEntity, chunkCoordinate, border);
        meshBuilder.rebuildNeighbors(level, nodeLookup(meshesForEntity), border);
    }

    /** Border nodes of the loaded neighbours of {@code chunk} that face it. */
    private static void collectFacing(Map<ChunkCoordinate, Mesh> meshesForEntity, ChunkCoordinate chunk,
                                      Set<MeshNode> into) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkCoordinate neighborCoord = new ChunkCoordinate(chunk.x() + dx, chunk.z() + dz);
                Mesh neighbor = meshesForEntity.get(neighborCoord);
                if (neighbor != null) {
                    meshBuilder.collectBorderNodes(neighbor, neighborCoord, -dx, -dz, into);
                }
            }
        }
    }

    /** Resolves a position to its node in whichever of these meshes holds it. */
    private static Function<BlockPos, MeshNode> nodeLookup(Map<ChunkCoordinate, Mesh> meshesForEntity) {
        return pos -> {
            Mesh mesh = meshesForEntity.get(new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4));
            return mesh == null ? null : mesh.getNodes().get(pos);
        };
    }

}

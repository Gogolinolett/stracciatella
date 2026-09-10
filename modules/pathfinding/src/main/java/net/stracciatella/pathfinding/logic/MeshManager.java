package net.stracciatella.pathfinding.logic;

import java.util.HashMap;

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

    public static void invalidateMesh(ChunkCoordinate chunkCoordinate) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        meshes.forEach((entity, meshes) -> {
            Mesh mesh = meshBuilder.generatePathfindingMesh(Minecraft.getInstance().level.getChunk(chunkCoordinate.x(), chunkCoordinate.z()), entity);
            meshes.put(chunkCoordinate, mesh);
            connectAdjacentMeshes(entity, chunkCoordinate);
        });
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

    /** Whether a mesh for this chunk is already built and cached. */
    public static boolean hasMesh(Entity entity, ChunkCoordinate chunkCoordinate) {
        var meshesForEntity = meshes.get(entity);
        return meshesForEntity != null && meshesForEntity.containsKey(chunkCoordinate);
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
     * @return how many meshes were dropped
     */
    public static int evictBeyond(Entity entity, ChunkCoordinate center, int radius) {
        var meshesForEntity = meshes.get(entity);
        if (meshesForEntity == null) {
            return 0;
        }
        int before = meshesForEntity.size();
        meshesForEntity.keySet().removeIf(coord ->
                Math.abs(coord.x() - center.x()) > radius
                        || Math.abs(coord.z() - center.z()) > radius);
        return before - meshesForEntity.size();
    }

    public static MeshNode findOrBuildNearestNode(Level level, Entity entity, BlockPos pos) {
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4);
        var meshesForEntity = meshes.get(entity);
        if (meshesForEntity == null || !meshesForEntity.containsKey(chunkCoordinate)) {
            if (!isChunkLoaded(level, chunkCoordinate)) {
                return null;
            }
            generateMesh(level.getChunk(pos), entity);
        }
        var mesh = meshes.get(entity).get(chunkCoordinate);
        if (mesh == null) {
            return null;
        }
        MeshNode exact = mesh.getNodes().get(pos);
        if (exact != null) {
            return exact;
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
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkCoordinate neighborCoord = new ChunkCoordinate(chunkCoordinate.x() + dx, chunkCoordinate.z() + dz);
                Mesh neighbor = meshesForEntity.get(neighborCoord);
                if (neighbor != null) {
                    meshBuilder.reconnectBorderNodes(level, chunkCoordinate, center, neighborCoord, neighbor);
                }
            }
        }
    }

}



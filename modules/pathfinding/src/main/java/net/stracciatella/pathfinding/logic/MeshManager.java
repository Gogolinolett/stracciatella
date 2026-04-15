package net.stracciatella.pathfinding.logic;

import java.util.HashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
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

        Mesh mesh = meshBuilder.generatePathfindingMesh(chunk, entity);
        if (!meshes.containsKey(entity)) {
            meshes.put(entity, new HashMap<>());
        }

        ChunkCoordinate chunkCoordinate = new ChunkCoordinate(chunk);
        meshes.get(entity).put(chunkCoordinate, mesh);
        connectAdjacentMeshes(entity, chunkCoordinate);

    }

    public static MeshNode findOrBuildNearestNode(Level level, Entity entity, BlockPos pos) {
        ChunkCoordinate chunkCoordinate = new ChunkCoordinate(pos.getX() >> 4, pos.getZ() >> 4);
        var meshesForEntity = meshes.get(entity);
        if (meshesForEntity == null || !meshesForEntity.containsKey(chunkCoordinate)) {
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



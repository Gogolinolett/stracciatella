package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.stracciatella.pathfinding.display.PathDisplay;
import net.stracciatella.pathfinding.logic.MeshManager;
import net.stracciatella.pathfinding.logic.MeshPathfinder;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.logic.mesh.MeshNode;

import java.util.List;

public class WalkTravelMethod implements TravelMethod {

    private static final double WALK_SPEED = 0.215; // blocks per tick (sprint average)
    private static final double SUCCESS_RADIUS_SQ = 4.0; // 2 blocks

    private BlockPos target;
    private boolean started;

    @Override
    public String id() {
        return "walk";
    }

    @Override
    public boolean canUse(Minecraft client, BlockPos from, BlockPos to) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return false;
        }
        MeshNode startNode = MeshManager.findOrBuildNearestNode(client.level, player, from);
        MeshNode endNode = MeshManager.findOrBuildNearestNode(client.level, player, to);
        if (startNode == null || endNode == null) {
            return false;
        }
        MeshPathfinder pathfinder = new MeshPathfinder();
        List<MeshNode> path = pathfinder.findPath(startNode, endNode);
        return !path.isEmpty();
    }

    @Override
    public double cost(Minecraft client, BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance / WALK_SPEED;
    }

    @Override
    public void start(Minecraft client, BlockPos from, BlockPos to) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        this.target = to;
        this.started = false;

        MeshNode startNode = MeshManager.findOrBuildNearestNode(client.level, player, from);
        MeshNode endNode = MeshManager.findOrBuildNearestNode(client.level, player, to);
        if (startNode == null || endNode == null) {
            return;
        }

        MeshPathfinder pathfinder = new MeshPathfinder();
        List<MeshNode> path = pathfinder.findPath(startNode, endNode);
        if (path.isEmpty()) {
            return;
        }

        PathDisplay.setHighlightedPath(path);
        PathWalker.start(path);
        this.started = true;
    }

    @Override
    public TravelStatus tick(Minecraft client) {
        if (!started) {
            return TravelStatus.FAILED;
        }
        if (PathWalker.isActive()) {
            return TravelStatus.IN_PROGRESS;
        }
        // PathWalker stopped — check if we're near the target
        LocalPlayer player = client.player;
        if (player != null && player.blockPosition().distSqr(target) <= SUCCESS_RADIUS_SQ) {
            return TravelStatus.SUCCEEDED;
        }
        return TravelStatus.FAILED;
    }

    @Override
    public void abort() {
        PathWalker.stop();
        started = false;
    }
}

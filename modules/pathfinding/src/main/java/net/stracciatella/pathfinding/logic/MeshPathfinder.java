package net.stracciatella.pathfinding.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import net.stracciatella.pathfinding.logic.mesh.Neighbor;

public class MeshPathfinder {

    public List<MeshNode> findPath(MeshNode start, MeshNode end) {
        return search(start, end, false);
    }

    /**
     * The path to {@code end} if it is reachable, otherwise the path to the node
     * closest to {@code end} among everything reachable from {@code start}.
     *
     * <p>This is what lets a journey head for a target whose chunk the client has
     * not been sent yet. There is nothing to plan through out there — the mesh
     * has no nodes at all for an unloaded chunk — so the leg aims at the best
     * node the mesh <em>does</em> have, walks it, and asks again once the chunks
     * that streamed in on the way have been meshed.
     *
     * <p>It costs nothing extra: an unreachable target makes {@link #findPath}
     * drain the open set anyway, visiting exactly the same nodes. The only
     * addition is remembering the best one while passing it.
     *
     * <p>Returns empty when even that is nothing — no node but the start itself
     * was reachable. Callers need that distinction: it is the difference between
     * "walk a stretch and reconsider" and "there is no way out of here".
     */
    public List<MeshNode> findPathTowards(MeshNode start, MeshNode end) {
        return search(start, end, true);
    }

    private List<MeshNode> search(MeshNode start, MeshNode end, boolean allowPartial) {
        // Return empty if invalid inputs
        if (start == null || end == null) return Collections.emptyList();

        // 1. OpenSet: Nodes to be evaluated, sorted by fCost (lowest first)
        PriorityQueue<NodeRecord> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        openSet.add(new NodeRecord(start, 0.0, heuristic(start, end)));

        // 2. Trackers for path reconstruction and costs
        Map<MeshNode, MeshNode> cameFrom = new HashMap<>();
        Map<MeshNode, Double> gScore = new HashMap<>(); // Cost from start to current node

        // Initialize start node
        gScore.put(start, 0.0);

        Set<MeshNode> closedSet = new HashSet<>();

        // Closest approach seen so far, for the partial answer. Seeded with the
        // start so the comparison needs no null case; a result that is still the
        // start at the end means nothing better was reachable.
        MeshNode bestTowards = start;
        double bestTowardsDistance = heuristic(start, end);

        while (!openSet.isEmpty()) {
            // Get node with lowest F score
            MeshNode current = openSet.poll().node;

            // Lazy deletion: skip nodes that were re-added with a better cost
            if (closedSet.contains(current)) {
                continue;
            }
            closedSet.add(current);

            if (allowPartial) {
                double distance = heuristic(current, end);
                if (distance < bestTowardsDistance) {
                    bestTowardsDistance = distance;
                    bestTowards = current;
                }
            }

            // Reached destination?
            if (current.equals(end)) {
                return reconstructPath(cameFrom, current);
            }

            // Process neighbors
            if (current.getNeighbors() != null) {
                for (Neighbor neighborObj : current.getNeighbors()) {
                    MeshNode neighborNode = neighborObj.getNode();
                    if (closedSet.contains(neighborNode)) {
                        continue;
                    }

                    int edgeWeight = neighborObj.getCost();
                    double tentativeG = gScore.getOrDefault(current, Double.MAX_VALUE) + edgeWeight;

                    if (tentativeG < gScore.getOrDefault(neighborNode, Double.MAX_VALUE)) {
                        cameFrom.put(neighborNode, current);
                        gScore.put(neighborNode, tentativeG);
                        double f = tentativeG + heuristic(neighborNode, end);
                        openSet.add(new NodeRecord(neighborNode, tentativeG, f));
                    }
                }
            }
        }

        // No path found. For a partial answer, hand back the closest approach —
        // unless that is still the start, which means there was nowhere to go.
        if (allowPartial && !bestTowards.equals(start)) {
            return reconstructPath(cameFrom, bestTowards);
        }
        return Collections.emptyList();
    }

    private List<MeshNode> reconstructPath(Map<MeshNode, MeshNode> cameFrom, MeshNode current) {
        List<MeshNode> path = new ArrayList<>();
        path.add(current);
        while (cameFrom.containsKey(current)) {
            current = cameFrom.get(current);
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    // Euclidean distance heuristic scaled to stay admissible.
    // Cheapest diagonal move: gap=1 diagonal = cost 13 (base 10 + diagonal 3).
    // Euclidean distance for diagonal: sqrt(2) ≈ 1.414.
    // Scale must satisfy: scale * sqrt(2) <= 13, so scale <= 9.19. Use 9.0.
    private double heuristic(MeshNode a, MeshNode b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return 9.0 * Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Helper class for the PriorityQueue
    private static class NodeRecord {
        MeshNode node;
        double gCost; // Cost from start
        double fCost; // Total estimated cost (g + h)

        public NodeRecord(MeshNode node, double gCost, double fCost) {
            this.node = node;
            this.gCost = gCost;
            this.fCost = fCost;
        }
    }
}

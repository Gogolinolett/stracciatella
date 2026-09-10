package net.stracciatella.pathfinding.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.stracciatella.pathfinding.logic.mesh.MeshNode;
import net.stracciatella.pathfinding.logic.mesh.Neighbor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MeshPathfinderTest {
    @Test
    public void testPathfindingWithVisualization() {
        // 1. SETUP: Create a 10x10 Grid Mesh
        // Map key is "x,z" string for easy lookup during graph building
        Map<String, MeshNode> grid = new HashMap<>();
        int width = 10;
        int depth = 10;

        // Create Nodes
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                // Assuming you added a constructor: new MeshNode(x, y, z)
                // If not, use setters here.
                MeshNode node = new MeshNode(x, 0, z);
                grid.put(x + "," + z, node);
            }
        }

        // Create "Walls" by removing specific nodes from the grid
        // This forces the pathfinder to go around.
        // Wall at x=5, from z=0 to z=7
        for (int z = 0; z <= 7; z++) {
            grid.remove("5," + z);
        }

        //manually added walls:
        Set<TestPos> walls = Set.of(new TestPos(4, 4), new TestPos(3, 4), new TestPos(2, 4));

        walls.forEach(pos -> grid.remove(pos.x + "," + pos.y));

        // Link Neighbors (Grid style: Up, Down, Left, Right)
        for (MeshNode node : grid.values()) {
            List<Neighbor> neighbors = new ArrayList<>();
            int[][] directions = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

            for (int[] dir : directions) {
                int nx = node.getX() + dir[0];
                int nz = node.getZ() + dir[1];
                MeshNode neighborNode = grid.get(nx + "," + nz);

                if (neighborNode != null) {
                    // Assuming constructor: new Neighbor(node, cost)
                    // standard movement cost = 10
                    neighbors.add(new Neighbor(neighborNode, 10));
                }
            }
            // Assuming setter: node.setNeighbors(neighbors)
            node.setNeighbors(neighbors);
        }

        // 2. RUN: Pathfinding
        MeshNode start = grid.get("1,1");
        MeshNode end = grid.get("8,1");

        System.out.println("Finding path from (1,1) to (8,1)...");
        System.out.println("A wall exists at x=5 (z=0 to z=7)\n");

        MeshPathfinder pathfinder = new MeshPathfinder();
        List<MeshNode> path = pathfinder.findPath(start, end);

        // 3. VISUALIZE
        printGrid(width, depth, grid, path, start, end);

        // Assertions (JUnit)
        assert !path.isEmpty() : "Path should not be empty";
        assert path.get(0).equals(start) : "Path must start at Start Node";
        assert path.get(path.size() - 1).equals(end) : "Path must end at End Node";
    }

    /**
     * A reachable goal must make findPathTowards behave exactly like findPath —
     * the partial answer is a fallback, not a different algorithm.
     */
    @Test
    public void towardsReachableGoalIsTheFullPath() {
        Map<String, MeshNode> grid = buildGrid(10, 10, (x, z) -> false);
        MeshNode start = grid.get("1,1");
        MeshNode end = grid.get("8,1");

        List<MeshNode> full = new MeshPathfinder().findPath(start, end);
        List<MeshNode> towards = new MeshPathfinder().findPathTowards(start, end);

        Assertions.assertEquals(full, towards,
                "a reachable goal must give the same path either way");
        Assertions.assertEquals(end, towards.get(towards.size() - 1));
    }

    /**
     * The case a journey to an unloaded chunk lives on: the goal cannot be
     * reached, and the answer must still be a path — to the closest approach.
     * Wall spans the whole grid, so nothing east of x=4 is reachable and (4,1) is
     * the node nearest the goal at (8,1).
     */
    @Test
    public void towardsUnreachableGoalEndsAtTheClosestApproach() {
        Map<String, MeshNode> grid = buildGrid(10, 10, (x, z) -> x == 5);
        MeshNode start = grid.get("1,1");
        MeshNode goal = grid.get("8,1");

        Assertions.assertTrue(new MeshPathfinder().findPath(start, goal).isEmpty(),
                "fixture is wrong if the goal is reachable");

        List<MeshNode> towards = new MeshPathfinder().findPathTowards(start, goal);

        Assertions.assertFalse(towards.isEmpty(), "an unreachable goal must still yield a leg");
        Assertions.assertEquals(start, towards.get(0), "a leg starts where the bot stands");
        MeshNode last = towards.get(towards.size() - 1);
        Assertions.assertEquals(4, last.getX(), "closest approach sits against the wall");
        Assertions.assertEquals(1, last.getZ(), "and on the goal's own row");
    }

    /**
     * Nothing reachable but the start itself must come back empty, not as a
     * one-node path. Callers need that distinction: it separates "walk a stretch
     * and reconsider" from "there is no way out of here".
     */
    @Test
    public void towardsNowhereIsEmpty() {
        MeshNode island = new MeshNode(0, 0, 0);
        island.setNeighbors(List.of());
        MeshNode goal = new MeshNode(20, 0, 0);
        goal.setNeighbors(List.of());

        Assertions.assertTrue(new MeshPathfinder().findPathTowards(island, goal).isEmpty(),
                "an isolated start has nowhere to go");
    }

    /**
     * Four-connected grid of walkable nodes, minus whatever {@code wall} rejects.
     */
    private Map<String, MeshNode> buildGrid(int width, int depth, WallAt wall) {
        Map<String, MeshNode> grid = new HashMap<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (wall.test(x, z)) {
                    continue;
                }
                grid.put(x + "," + z, new MeshNode(x, 0, z));
            }
        }
        int[][] directions = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (MeshNode node : grid.values()) {
            List<Neighbor> neighbors = new ArrayList<>();
            for (int[] dir : directions) {
                MeshNode neighbor = grid.get((node.getX() + dir[0]) + "," + (node.getZ() + dir[1]));
                if (neighbor != null) {
                    neighbors.add(new Neighbor(neighbor, 10));
                }
            }
            node.setNeighbors(neighbors);
        }
        return grid;
    }

    private interface WallAt {
        boolean test(int x, int z);
    }

    private void printGrid(int width, int depth, Map<String, MeshNode> grid, List<MeshNode> path, MeshNode start, MeshNode end) {
        System.out.println("   --- MAP VISUALIZATION ---");
        System.out.print("  ");
        for (int x = 0; x < width; x++) System.out.print(x + " ");
        System.out.println();

        for (int z = 0; z < depth; z++) {
            System.out.print(z + " ");
            for (int x = 0; x < width; x++) {
                MeshNode node = grid.get(x + "," + z);

                if (node == null) {
                    System.out.print("# "); // Wall / Void
                } else if (node.equals(start)) {
                    System.out.print("S "); // Start
                } else if (node.equals(end)) {
                    System.out.print("E "); // End
                } else if (path.contains(node)) {
                    System.out.print("* "); // Path
                } else {
                    System.out.print(". "); // Empty Node
                }
            }
            System.out.println();
        }
        System.out.println("\nLegend: [S]tart, [E]nd, [*] Path, [.] Node, [#] Wall");
    }

    record TestPos(int x, int y) {}
}

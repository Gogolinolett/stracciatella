package net.stracciatella.pathfinding.logic.mesh;

import java.util.HashMap;

import net.minecraft.core.BlockPos;

public class Mesh {
    HashMap<BlockPos, MeshNode> nodes = new HashMap<>();
    /**
     * The layers this mesh was built from, as asked for — {@link Integer#MIN_VALUE}
     * and {@link Integer#MAX_VALUE} for the whole column. Kept so that a mesh
     * built for one band is never mistaken for one that covers another: a band
     * chosen around the feet at one end of a route used to hide every layer of
     * a hill further along, and nothing ever built them.
     */
    private final int bandMinY;
    private final int bandMaxY;

    public Mesh(int bandMinY, int bandMaxY) {
        this.bandMinY = bandMinY;
        this.bandMaxY = bandMaxY;
    }

    public HashMap<BlockPos, MeshNode> getNodes() {
        return nodes;
    }

    public int bandMinY() {
        return bandMinY;
    }

    public int bandMaxY() {
        return bandMaxY;
    }

    /** Whether every layer of {@code [minY, maxY]} was part of the build. */
    public boolean covers(int minY, int maxY) {
        return bandMinY <= minY && bandMaxY >= maxY;
    }
}

package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.stracciatella.pathfinding.logic.MeshManager;

/**
 * Walking, as a travel method. A thin shell over {@link Journey}, which does the
 * actual work of planning leg by leg and re-planning after each one.
 *
 * <p>It used to plan a single path up front and hand it to the walker. That only
 * ever worked inside the chunks whose meshes happened to exist — roughly the
 * 3x3 around the bot — and gave no verdict beyond "the walker stopped". Both of
 * those belong to the journey now, so there is nothing left here but the
 * {@code TravelMethod} shape Navigator needs.
 */
public class WalkTravelMethod implements TravelMethod {

    private static final double WALK_SPEED = 0.215; // blocks per tick (sprint average)

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
        // Deliberately only "can I start": a journey walks before it can know
        // whether the far end is reachable, because the terrain out there has not
        // been sent to the client yet. Answering the old question — is there a
        // complete path right now — would rule out every destination worth a
        // journey, and it ran a full A* on every fallback check to do it.
        return MeshManager.findOrBuildNearestNode(client.level, player, from) != null;
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
        Journey.start(to);
    }

    @Override
    public TravelStatus tick(Minecraft client) {
        // Journey is ticked by the module, the same way PathWalker is; this only
        // reads the verdict.
        return switch (Journey.status()) {
            case RUNNING -> TravelStatus.IN_PROGRESS;
            case ARRIVED -> TravelStatus.SUCCEEDED;
            case FAILED -> TravelStatus.FAILED;
        };
    }

    @Override
    public void abort() {
        Journey.stop();
    }
}

package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

public class CommandTeleportTravelMethod implements TravelMethod {

    private static final int TIMEOUT_TICKS = 20;
    private static final double SUCCESS_RADIUS_SQ = 4.0; // 2 blocks

    private BlockPos target;
    private int ticksElapsed;

    @Override
    public String id() {
        return "tp";
    }

    @Override
    public boolean canUse(Minecraft client, BlockPos from, BlockPos to) {
        // Optimistic: assume /tp is available. If the player lacks permissions,
        // tick() will detect no position change and return FAILED.
        return client.player != null;
    }

    @Override
    public double cost(Minecraft client, BlockPos from, BlockPos to) {
        return 1.0; // Instant teleport, essentially free
    }

    @Override
    public void start(Minecraft client, BlockPos from, BlockPos to) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        this.target = to;
        this.ticksElapsed = 0;
        player.connection.sendCommand(
                "tp @s " + to.getX() + " " + to.getY() + " " + to.getZ());
    }

    @Override
    public TravelStatus tick(Minecraft client) {
        ticksElapsed++;
        LocalPlayer player = client.player;
        if (player == null) {
            return TravelStatus.FAILED;
        }
        if (player.blockPosition().distSqr(target) <= SUCCESS_RADIUS_SQ) {
            return TravelStatus.SUCCEEDED;
        }
        if (ticksElapsed >= TIMEOUT_TICKS) {
            return TravelStatus.FAILED;
        }
        return TravelStatus.IN_PROGRESS;
    }

    @Override
    public void abort() {
        // Teleport is instant, nothing to clean up
    }
}

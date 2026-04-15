package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.stracciatella.camera.AngleUtil;
import net.stracciatella.camera.CameraController;

public class EnderPearlTravelMethod implements TravelMethod {

    private static final double MAX_RANGE = 40.0;
    private static final double MIN_RANGE = 5.0;
    private static final double SUCCESS_RADIUS_SQ = 9.0; // 3 blocks
    private static final int WAITING_TIMEOUT_TICKS = 100;
    private static final float FACING_TOLERANCE = 5.0f;
    private static final double EYE_HEIGHT = 1.62;
    private static final double PEARL_SPEED = 1.5;
    private static final double PEARL_GRAVITY = 0.03;
    private static final double PEARL_DRAG = 0.99;

    private enum Phase { AIMING, THROWING, WAITING }

    private Phase phase;
    private BlockPos target;
    private CameraController camera;
    private float targetYaw;
    private float targetPitch;
    private int ticksInPhase;
    private boolean active;

    @Override
    public String id() {
        return "ender_pearl";
    }

    @Override
    public boolean canUse(Minecraft client, BlockPos from, BlockPos to) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < MIN_RANGE || horizontalDist > MAX_RANGE) {
            return false;
        }
        return findPearlSlot(player) >= 0;
    }

    @Override
    public double cost(Minecraft client, BlockPos from, BlockPos to) {
        return 60.0; // ~3 seconds for aim + throw + flight
    }

    @Override
    public void start(Minecraft client, BlockPos from, BlockPos to) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        this.target = to;
        this.active = true;

        // Select the ender pearl slot
        int slot = findPearlSlot(player);
        if (slot >= 0) {
            player.getInventory().setSelectedSlot(slot);
        }

        // Compute aim angles
        double dx = to.getX() + 0.5 - player.getX();
        double dz = to.getZ() + 0.5 - player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double dy = to.getY() - player.getY();

        this.targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        this.targetPitch = computeLaunchPitch(horizontalDist, dy);

        this.camera = new CameraController();
        this.camera.initialize(player.getYRot(), player.getXRot());
        this.phase = Phase.AIMING;
        this.ticksInPhase = 0;
    }

    @Override
    public TravelStatus tick(Minecraft client) {
        if (!active) {
            return TravelStatus.FAILED;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            return TravelStatus.FAILED;
        }
        ticksInPhase++;

        return switch (phase) {
            case AIMING -> tickAiming(client, player);
            case THROWING -> tickThrowing(client, player);
            case WAITING -> tickWaiting(client, player);
        };
    }

    @Override
    public void abort() {
        active = false;
        Minecraft client = Minecraft.getInstance();
        client.options.keyUse.setDown(false);
    }

    private TravelStatus tickAiming(Minecraft client, LocalPlayer player) {
        float newYaw = camera.updateYaw(targetYaw);
        float newPitch = camera.updatePitch(targetPitch);
        player.setYRot(newYaw);
        player.setXRot(newPitch);

        if (AngleUtil.isFacingTarget(newYaw, targetYaw, FACING_TOLERANCE)
                && Math.abs(newPitch - targetPitch) < FACING_TOLERANCE) {
            phase = Phase.THROWING;
            ticksInPhase = 0;
        }

        if (ticksInPhase > 40) {
            // Taking too long to aim, snap
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
            phase = Phase.THROWING;
            ticksInPhase = 0;
        }

        return TravelStatus.IN_PROGRESS;
    }

    private TravelStatus tickThrowing(Minecraft client, LocalPlayer player) {
        if (ticksInPhase == 1) {
            client.options.keyUse.setDown(true);
        } else if (ticksInPhase == 2) {
            client.options.keyUse.setDown(false);
            phase = Phase.WAITING;
            ticksInPhase = 0;
        }
        return TravelStatus.IN_PROGRESS;
    }

    private TravelStatus tickWaiting(Minecraft client, LocalPlayer player) {
        if (player.blockPosition().distSqr(target) <= SUCCESS_RADIUS_SQ) {
            return TravelStatus.SUCCEEDED;
        }
        if (ticksInPhase >= WAITING_TIMEOUT_TICKS) {
            return TravelStatus.FAILED;
        }
        return TravelStatus.IN_PROGRESS;
    }

    private static int findPearlSlot(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Binary-search a simulated pearl trajectory to find the pitch that
     * lands closest to the desired horizontal distance.
     */
    private float computeLaunchPitch(double horizontalDist, double dy) {
        float bestPitch = -30f;
        double bestError = Double.MAX_VALUE;

        for (float pitch = -80f; pitch <= 20f; pitch += 0.5f) {
            double landDist = simulatePearlRange(pitch, dy);
            double error = Math.abs(landDist - horizontalDist);
            if (error < bestError) {
                bestError = error;
                bestPitch = pitch;
            }
        }
        return bestPitch;
    }

    /**
     * Simulates a pearl trajectory at the given Minecraft pitch angle and returns
     * the horizontal distance at which it crosses the target height.
     */
    private double simulatePearlRange(float pitchDeg, double targetDy) {
        // MC pitch: negative = up, positive = down
        double pitchRad = Math.toRadians(-pitchDeg);
        double vx = PEARL_SPEED * Math.cos(pitchRad);
        double vy = PEARL_SPEED * Math.sin(pitchRad);

        double x = 0;
        double y = EYE_HEIGHT;
        double targetY = targetDy + EYE_HEIGHT;

        for (int t = 0; t < 200; t++) {
            x += vx;
            y += vy;
            vy -= PEARL_GRAVITY;
            vx *= PEARL_DRAG;
            vy *= PEARL_DRAG;

            // Pearl has crossed below target height (landing)
            if (y <= targetY && t > 0) {
                return x;
            }
        }
        return x;
    }
}

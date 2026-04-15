package net.stracciatella.pathfinding.travel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.stracciatella.pathfinding.logic.PathWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Navigator {

    private static final Logger LOGGER = LoggerFactory.getLogger(Navigator.class);

    private static final List<TravelMethod> methods = new ArrayList<>();
    private static final List<BlockPos> waypoints = new ArrayList<>();
    private static int waypointIndex = 0;
    private static TravelMethod activeMethod;
    private static List<TravelMethod> fallbackChain = new ArrayList<>();
    private static boolean active = false;

    public static void register(TravelMethod method) {
        methods.add(method);
        LOGGER.info("Registered travel method: {}", method.id());
    }

    public static void navigate(BlockPos target) {
        navigate(List.of(target));
    }

    public static void navigate(List<BlockPos> targets) {
        if (targets.isEmpty()) {
            return;
        }
        stop();
        waypoints.clear();
        waypoints.addAll(targets);
        waypointIndex = 0;
        active = true;
        startNextLeg();
    }

    public static void stop() {
        if (activeMethod != null) {
            activeMethod.abort();
            activeMethod = null;
        }
        fallbackChain.clear();
        active = false;
        waypoints.clear();
        waypointIndex = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static List<TravelMethod> getMethods() {
        return methods;
    }

    public static void tick(Minecraft client) {
        if (!active || activeMethod == null) {
            return;
        }
        TravelStatus status = activeMethod.tick(client);
        switch (status) {
            case SUCCEEDED -> {
                sendFeedback(client, "Arrived via " + activeMethod.id());
                activeMethod = null;
                waypointIndex++;
                if (waypointIndex < waypoints.size()) {
                    startNextLeg();
                } else {
                    active = false;
                    sendFeedback(client, "Navigation complete");
                }
            }
            case FAILED -> {
                LOGGER.warn("Travel method {} failed", activeMethod.id());
                activeMethod = null;
                if (tryNextFallback(client)) {
                    sendFeedback(client, "Trying fallback: " + activeMethod.id());
                } else {
                    active = false;
                    sendFeedback(client, "Navigation failed — no method available");
                }
            }
            case IN_PROGRESS -> {
                // continue
            }
        }
    }

    private static void startNextLeg() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            active = false;
            return;
        }

        // Stop PathWalker if it's active from external usage to avoid conflicts
        if (PathWalker.isActive()) {
            PathWalker.stop();
        }

        BlockPos from = player.blockPosition();
        BlockPos to = waypoints.get(waypointIndex);

        // Build sorted fallback chain by cost
        fallbackChain = new ArrayList<>();
        for (TravelMethod method : methods) {
            if (method.canUse(client, from, to)) {
                fallbackChain.add(method);
            }
        }
        fallbackChain.sort(Comparator.comparingDouble(m -> m.cost(client, from, to)));

        if (fallbackChain.isEmpty()) {
            active = false;
            sendFeedback(client, "No travel method available for this leg");
            return;
        }

        activeMethod = fallbackChain.remove(0);
        sendFeedback(client, "Navigating via " + activeMethod.id() + " to " +
                to.getX() + " " + to.getY() + " " + to.getZ());
        activeMethod.start(client, from, to);
    }

    private static boolean tryNextFallback(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        BlockPos from = player.blockPosition();
        BlockPos to = waypoints.get(waypointIndex);

        while (!fallbackChain.isEmpty()) {
            TravelMethod next = fallbackChain.remove(0);
            if (next.canUse(client, from, to)) {
                activeMethod = next;
                activeMethod.start(client, from, to);
                return true;
            }
        }
        return false;
    }

    private static void sendFeedback(Minecraft client, String message) {
        LocalPlayer player = client.player;
        if (player != null) {
            player.displayClientMessage(Component.literal("[Navigator] " + message), false);
        }
        LOGGER.info("[Navigator] {}", message);
    }
}

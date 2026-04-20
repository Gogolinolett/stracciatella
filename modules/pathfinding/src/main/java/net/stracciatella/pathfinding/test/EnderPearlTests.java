package net.stracciatella.pathfinding.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.travel.EnderPearlTravelMethod;
import net.stracciatella.pathfinding.travel.Navigator;
import net.stracciatella.pathfinding.travel.TravelStatus;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for the EnderPearlTravelMethod. Each test places a start
 * block and a landing platform, gives the player ender pearls, then runs the
 * method's aim-throw-wait state machine.
 * <p>
 * Requires survival mode (creative can't throw pearls), so the helper switches
 * gamemode and restores it in a finally block.
 */
@TestSuite(name = "EnderPearl Tests")
public class EnderPearlTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnderPearlTests");

    // Extra ticks after the client cooldown clears, to let the server's
    // ItemCooldowns catch up under accelerated `/tick rate` where client and
    // server cooldown counters can drift.
    private static final int COOLDOWN_SAFETY_TICKS = 30;

    @MinecraftTest(name = "EnderPearl flat 10", timeoutTicks = 300, order = -50, repeat = 3)
    public void flat_10(TestContext ctx) {
        runPearlTest(ctx, new BlockPos(1000, 30, 1000), 10, 0);
    }

    @MinecraftTest(name = "EnderPearl flat 20", timeoutTicks = 300, order = -49, repeat = 3)
    public void flat_20(TestContext ctx) {
        runPearlTest(ctx, new BlockPos(1050, 30, 1000), 20, 0);
    }

    @MinecraftTest(name = "EnderPearl flat 30", timeoutTicks = 300, order = -48, repeat = 3)
    public void flat_30(TestContext ctx) {
        runPearlTest(ctx, new BlockPos(1100, 30, 1000), 30, 0);
    }

    private void runPearlTest(TestContext ctx, BlockPos origin, int horizontalDist, int dy) {
        // Phase 0: Ensure clean state regardless of previous test outcome.
        // Wait for any pearl cooldown from previous tests to expire (~20 ticks in vanilla).
        ctx.runOnClient(mc -> {
            Navigator.stop();
            PathWalker.stop();
            mc.options.keyUse.setDown(false);
        });
        ctx.runCommand("gamemode creative @s");
        ctx.runCommand("effect clear @s");
        ctx.waitTicks(30);

        BlockPos targetPos = origin.offset(0, dy, -horizontalDist);
        LOGGER.info("EnderPearl test: origin={}, target={}, dist={}, dy={}", origin, targetPos, horizontalDist, dy);

        // Phase 1: Teleport to area, wait for chunks
        BlockPos center = new BlockPos(
                (origin.getX() + targetPos.getX()) / 2,
                Math.max(origin.getY(), targetPos.getY()) + 10,
                (origin.getZ() + targetPos.getZ()) / 2);
        ctx.runCommand("tp @s " + (center.getX() + 0.5) + " " + center.getY() + " " + (center.getZ() + 0.5));
        ctx.waitFor(mc -> mc.level.hasChunkAt(origin) && mc.level.hasChunkAt(targetPos));

        // Phase 2: Build course (still in creative — player floats safely)
        int minX = Math.min(origin.getX(), targetPos.getX()) - 5;
        int maxX = Math.max(origin.getX(), targetPos.getX()) + 5;
        int minZ = Math.min(origin.getZ(), targetPos.getZ()) - 5;
        int maxZ = Math.max(origin.getZ(), targetPos.getZ()) + 5;
        int minY = Math.min(origin.getY(), targetPos.getY()) - 2;
        int maxY = Math.max(origin.getY(), targetPos.getY()) + 25;
        ctx.runCommand("fill " + minX + " " + minY + " " + minZ + " "
                + maxX + " " + maxY + " " + maxZ + " air");
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " stone");
        // Large stone floor at target Y catches any wayward pearl trajectory (covers full cleared footprint)
        ctx.runCommand("fill " + minX + " " + targetPos.getY() + " " + minZ + " "
                + maxX + " " + targetPos.getY() + " " + maxZ + " stone");

        // Phase 3: Position player on start block WHILE STILL IN CREATIVE (safe from falling).
        // runOnClient barrier forces a round-trip to the client thread, ensuring the tp
        // command has been processed by the server before Phase 4 begins — waitTicks alone
        // is insufficient because commands are sent async but waitTicks doesn't block on
        // client-side packet processing.
        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + (origin.getY() + 1) + " " + (origin.getZ() + 0.5));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {});

        try {
            // Phase 4: Switch to survival mode and equip pearls.
            ctx.runCommand("gamemode survival @s");
            ctx.runCommand("difficulty easy");
            ctx.runCommand("effect give @s minecraft:resistance 60 4");
            ctx.runCommand("effect give @s minecraft:saturation 60 4");
            ctx.runCommand("clear @s");
            ctx.runCommand("give @s minecraft:ender_pearl 16");
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> {});

            // Wait for server-side pearl cooldown from any previous test to clear.
            // Vanilla applies a 20-tick cooldown per throw. Client and server tick
            // independently under the test tick-multiplier, and the client can
            // finish its cooldown countdown while the server's ItemCooldowns still
            // holds the cooldown — a client use-press in that window is silently
            // rejected by the server. Poll the client cooldown, then wait a safety
            // margin to let the server catch up.
            ctx.waitFor(mc -> !mc.player.getCooldowns()
                    .isOnCooldown(new ItemStack(Items.ENDER_PEARL)));
            ctx.waitTicks(COOLDOWN_SAFETY_TICKS);

            // Phase 5: Run EnderPearlTravelMethod
            EnderPearlTravelMethod pearl = new EnderPearlTravelMethod();

            boolean canUse = ctx.computeOnClient(mc -> pearl.canUse(mc, origin, targetPos));
            if (!canUse) {
                ctx.fail("canUse() returned false for dist=" + horizontalDist + ", dy=" + dy);
            }

            ctx.runOnClient(mc -> pearl.start(mc, origin, targetPos));
            LOGGER.info("EnderPearlTravelMethod started, aiming at {}", targetPos);

            ctx.waitFor(mc -> {
                TravelStatus status = pearl.tick(mc);
                if (status == TravelStatus.SUCCEEDED) {
                    LOGGER.info("Pearl landed successfully at {}, target was {}",
                            mc.player.blockPosition(), targetPos);
                    return true;
                }
                if (status == TravelStatus.FAILED) {
                    throw new AssertionError("EnderPearlTravelMethod FAILED at "
                            + mc.player.blockPosition() + ", target=" + targetPos
                            + ", distSq=" + mc.player.blockPosition().distSqr(targetPos));
                }
                return false;
            });
        } finally {
            ctx.runCommand("gamemode creative @s");
            ctx.runCommand("difficulty peaceful");
            ctx.runCommand("effect clear @s");
            ctx.runOnClient(mc -> mc.options.keyUse.setDown(false));
        }
    }
}

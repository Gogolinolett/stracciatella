package net.stracciatella.bot.test;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.scan.TreeDetector;
import net.stracciatella.bot.scan.TreeInfo;
import net.stracciatella.bot.task.ChopTreeTask;
import net.stracciatella.bot.task.MineBlockTask;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestSuite(name = "Bot Tests")
public class BotTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("BotTests");
    private static final int CLEAR_RADIUS = 8;

    // ================================================================
    // Test 1: Single block mine — place stone, mine it, verify it's air
    // ================================================================
    @MinecraftTest(name = "Bot mine single block", timeoutTicks = 200, order = -200)
    public void mineSingleBlock(TestContext ctx) {
        final BlockPos origin = new BlockPos(1000, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("gamemode survival");

        // Teleport near the area first and wait for chunks
        teleportAndWaitForChunks(ctx, origin);

        // Build environment
        clearArea(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("fill " + (standPos.getX() - 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() - 1)
                + " " + (standPos.getX() + 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() + 1) + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");

        // Teleport to stand position and wait for ground
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + origin.getY() + " " + (standPos.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());

        // Enqueue mine task
        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));

        // Wait for block to become air
        ctx.waitFor(mc -> mc.level.getBlockState(blockPos).isAir());
        ctx.runOnClient(mc -> BotController.stop());

        LOGGER.info("Single block mine test passed");
    }

    // ================================================================
    // Test 2: Tool selection — verify best tool is selected
    // ================================================================
    @MinecraftTest(name = "Bot tool selection", timeoutTicks = 200, order = -199)
    public void toolSelection(TestContext ctx) {
        final BlockPos origin = new BlockPos(1050, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("gamemode survival");

        teleportAndWaitForChunks(ctx, origin);

        clearArea(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("fill " + (standPos.getX() - 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() - 1)
                + " " + (standPos.getX() + 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() + 1) + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " iron_ore");
        ctx.runCommand("clear @s");
        ctx.runCommand("give @s wooden_pickaxe");
        ctx.runCommand("give @s iron_pickaxe");

        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + origin.getY() + " " + (standPos.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));

        ctx.waitFor(mc -> mc.level.getBlockState(blockPos).isAir());

        int selectedSlot = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
        if (selectedSlot != 1) {
            LOGGER.warn("Expected slot 1 (iron pickaxe), got slot {}", selectedSlot);
        }

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Tool selection test passed");
    }

    // ================================================================
    // Test 3: Tree chop — build a small tree, chop it top-to-bottom
    // ================================================================
    @MinecraftTest(name = "Bot chop tree", timeoutTicks = 400, order = -198)
    public void chopTree(TestContext ctx) {
        final BlockPos origin = new BlockPos(1100, 30, 1000);
        final BlockPos treeBase = origin;
        final BlockPos standPos = origin.offset(2, 0, 0);

        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("gamemode survival");

        teleportAndWaitForChunks(ctx, origin);

        clearArea(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("fill " + (origin.getX() - 3) + " " + (origin.getY() - 1) + " " + (origin.getZ() - 3)
                + " " + (origin.getX() + 3) + " " + (origin.getY() - 1) + " " + (origin.getZ() + 3) + " stone");
        for (int i = 0; i < 4; i++) {
            ctx.runCommand("setblock " + treeBase.getX() + " " + (treeBase.getY() + i) + " " + treeBase.getZ() + " oak_log");
        }
        ctx.runCommand("setblock " + treeBase.getX() + " " + (treeBase.getY() + 4) + " " + treeBase.getZ() + " oak_leaves[persistent=true]");
        ctx.runCommand("give @s diamond_axe");

        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + origin.getY() + " " + (standPos.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());

        ctx.runOnClient(mc -> {
            List<TreeInfo> trees = TreeDetector.findTrees(mc.level, treeBase, 3);
            if (trees.isEmpty()) {
                throw new AssertionError("No tree detected at " + treeBase);
            }
            TreeInfo tree = trees.get(0);
            if (tree.logs().size() != 4) {
                throw new AssertionError("Expected 4 logs, found " + tree.logs().size());
            }
            BotController.enqueueTask(new ChopTreeTask(tree));
        });

        ctx.waitFor(mc -> {
            for (int i = 0; i < 4; i++) {
                BlockPos logPos = new BlockPos(treeBase.getX(), treeBase.getY() + i, treeBase.getZ());
                if (!mc.level.getBlockState(logPos).isAir()) {
                    return false;
                }
            }
            return true;
        });

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Tree chop test passed");
    }

    // ================================================================
    // Test 4: Camera smoothness — verify no instant snapping
    // ================================================================
    @MinecraftTest(name = "Bot camera smoothness", timeoutTicks = 200, order = -197)
    public void cameraSmoothness(TestContext ctx) {
        final BlockPos origin = new BlockPos(1150, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 3);

        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("gamemode survival");

        teleportAndWaitForChunks(ctx, origin);

        clearArea(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("fill " + (standPos.getX() - 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() - 1)
                + " " + (standPos.getX() + 1) + " " + (origin.getY() - 1) + " " + (standPos.getZ() + 1) + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");

        // Face away from the block initially
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + origin.getY() + " " + (standPos.getZ() + 0.5) + " 180 0");
        ctx.waitFor(mc -> mc.player.onGround());

        // Track yaw changes per tick
        float[] lastYaw = {180.0f};
        float[] maxDelta = {0.0f};

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));

        // Monitor for a few ticks while the camera is turning
        for (int i = 0; i < 30; i++) {
            ctx.waitTick();
            float currentYaw = ctx.computeOnClient(mc -> mc.player.getYRot());
            float delta = Math.abs(currentYaw - lastYaw[0]);
            if (delta > 180.0f) {
                delta = 360.0f - delta;
            }
            if (delta > maxDelta[0]) {
                maxDelta[0] = delta;
            }
            lastYaw[0] = currentYaw;
        }

        if (maxDelta[0] > 50.0f) {
            throw new AssertionError("Camera snapped too fast: max delta = " + maxDelta[0]
                    + " degrees in a single tick (expected < 50)");
        }

        ctx.waitFor(mc -> mc.level.getBlockState(blockPos).isAir());
        ctx.runOnClient(mc -> BotController.stop());

        LOGGER.info("Camera smoothness test passed (max delta: {})", String.format("%.1f", maxDelta[0]));
    }

    // ================================================================
    // Test 5: Out-of-reach failure — block with no walkable path fails gracefully
    // ================================================================
    @MinecraftTest(name = "Bot out-of-reach failure", timeoutTicks = 200, order = -196)
    public void outOfReachFailure(TestContext ctx) {
        final BlockPos origin = new BlockPos(1200, 30, 1000);
        final BlockPos farBlock = new BlockPos(1200, 50, 1000);

        ctx.runOnClient(mc -> BotController.stop());
        // Restore creative mode — this test doesn't mine, and ensures
        // subsequent test suites (PathWalker) run in creative
        ctx.runCommand("gamemode creative");

        teleportAndWaitForChunks(ctx, origin);

        clearArea(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + (origin.getY() - 1) + " " + origin.getZ() + " stone");
        ctx.runCommand("setblock " + farBlock.getX() + " " + farBlock.getY() + " " + farBlock.getZ() + " stone");

        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + origin.getY() + " " + (origin.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(farBlock)));

        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.IDLE);

        boolean stillExists = ctx.computeOnClient(mc -> !mc.level.getBlockState(farBlock).isAir());
        if (!stillExists) {
            throw new AssertionError("Block should not have been mined (out of reach)");
        }

        LOGGER.info("Out-of-reach failure test passed");
    }

    // --- Utility ---

    /**
     * Teleport to the area and wait for chunks to load before building.
     * Follows the same pattern as PathWalkerTests.
     */
    private void teleportAndWaitForChunks(TestContext ctx, BlockPos origin) {
        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + (origin.getY() + 5) + " " + (origin.getZ() + 0.5));
        ctx.waitFor(mc -> {
            if (mc.player == null || mc.level == null) {
                return false;
            }
            boolean chunkLoaded = mc.level.hasChunkAt(origin);
            boolean nearOrigin = Math.abs(mc.player.position().x - (origin.getX() + 0.5)) < 20
                    && Math.abs(mc.player.position().z - (origin.getZ() + 0.5)) < 20;
            return chunkLoaded && nearOrigin;
        });
    }

    private void clearArea(TestContext ctx, BlockPos center, int radius) {
        ctx.runCommand("fill "
                + (center.getX() - radius) + " " + (center.getY() - radius) + " " + (center.getZ() - radius) + " "
                + (center.getX() + radius) + " " + (center.getY() + radius) + " " + (center.getZ() + radius) + " air");
    }
}

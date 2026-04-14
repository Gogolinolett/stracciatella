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
    // Test 1: Single block mine
    // ================================================================
    @MinecraftTest(name = "Bot mine single block", timeoutTicks = 60, order = -200)
    public void mineSingleBlock(TestContext ctx) {
        final BlockPos origin = new BlockPos(1000, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        setupTest(ctx, origin);
        clearArea(ctx, origin, CLEAR_RADIUS);
        buildStandPlatform(ctx, standPos, origin.getY());
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));
        ctx.waitFor(mc -> mc.level.getBlockState(blockPos).isAir());
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Single block mine test passed");
    }

    // ================================================================
    // Test 2: Tool selection
    // ================================================================
    @MinecraftTest(name = "Bot tool selection", timeoutTicks = 60, order = -199)
    public void toolSelection(TestContext ctx) {
        final BlockPos origin = new BlockPos(1050, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        setupTest(ctx, origin);
        clearArea(ctx, origin, CLEAR_RADIUS);
        buildStandPlatform(ctx, standPos, origin.getY());
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " iron_ore");
        ctx.runCommand("give @s wooden_pickaxe");
        ctx.runCommand("give @s iron_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

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
    // Test 3: Tree chop
    // ================================================================
    @MinecraftTest(name = "Bot chop tree", timeoutTicks = 120, order = -198)
    public void chopTree(TestContext ctx) {
        final BlockPos origin = new BlockPos(1100, 30, 1000);
        final BlockPos treeBase = origin;
        final BlockPos standPos = origin.offset(2, 0, 0);

        setupTest(ctx, origin);
        clearArea(ctx, origin, CLEAR_RADIUS);
        buildPlatform(ctx, origin, 5);
        for (int i = 0; i < 4; i++) {
            ctx.runCommand("setblock " + treeBase.getX() + " " + (treeBase.getY() + i) + " " + treeBase.getZ() + " oak_log");
        }
        ctx.runCommand("setblock " + treeBase.getX() + " " + (treeBase.getY() + 4) + " " + treeBase.getZ() + " oak_leaves[persistent=true]");
        ctx.runCommand("give @s diamond_axe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

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
                if (!mc.level.getBlockState(treeBase.offset(0, i, 0)).isAir()) {
                    return false;
                }
            }
            return true;
        });

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Tree chop test passed");
    }

    // ================================================================
    // Test 4: Camera smoothness
    // ================================================================
    @MinecraftTest(name = "Bot camera smoothness", timeoutTicks = 80, order = -197)
    public void cameraSmoothness(TestContext ctx) {
        final BlockPos origin = new BlockPos(1150, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 3);

        setupTest(ctx, origin);
        clearArea(ctx, origin, CLEAR_RADIUS);
        buildStandPlatform(ctx, standPos, origin.getY());
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");

        // Face away from the block initially
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + (origin.getY()) + " " + (standPos.getZ() + 0.5) + " 180 0");
        ctx.waitFor(mc -> mc.player.onGround());
        ctx.runCommand("gamemode survival");

        float[] lastYaw = {180.0f};
        float[] maxDelta = {0.0f};

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));

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
    // Test 5: Walk to distant block and mine it
    // ================================================================
    @MinecraftTest(name = "Bot walk and mine", timeoutTicks = 120, order = -196)
    public void walkAndMine(TestContext ctx) {
        final BlockPos origin = new BlockPos(1300, 30, 1000);
        final BlockPos standPos = origin;
        final BlockPos blockPos = origin.offset(0, 0, -8);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, 12);
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        generateMesh(ctx, origin);

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));

        // Verify the bot actually walks (enters NAVIGATING or beyond)
        ctx.waitFor(mc -> BotController.getPhase() != BotController.Phase.IDLE);

        ctx.waitFor(mc -> mc.level.getBlockState(blockPos).isAir());
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Walk and mine test passed");
    }

    // ================================================================
    // Test 6: Task queue — mine two blocks at different locations
    // ================================================================
    @MinecraftTest(name = "Bot mine queue", timeoutTicks = 150, order = -195)
    public void mineQueue(TestContext ctx) {
        final BlockPos origin = new BlockPos(1350, 30, 1000);
        final BlockPos block1 = origin.offset(3, 0, 0);
        final BlockPos block2 = origin.offset(-3, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, 10);
        ctx.runCommand("setblock " + block1.getX() + " " + block1.getY() + " " + block1.getZ() + " stone");
        ctx.runCommand("setblock " + block2.getX() + " " + block2.getY() + " " + block2.getZ() + " iron_ore");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        generateMesh(ctx, origin);

        ctx.runOnClient(mc -> {
            BotController.enqueueTask(new MineBlockTask(block1));
            BotController.enqueueTask(new MineBlockTask(block2));
        });

        ctx.waitFor(mc -> mc.level.getBlockState(block1).isAir());
        LOGGER.info("First block mined");
        ctx.waitFor(mc -> mc.level.getBlockState(block2).isAir());

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Mine queue test passed");
    }

    // ================================================================
    // Test 7: Walk → mine stone → walk → chop tree (full sequence)
    // ================================================================
    @MinecraftTest(name = "Bot walk mine walk chop", timeoutTicks = 200, order = -194)
    public void walkMineWalkChop(TestContext ctx) {
        final BlockPos origin = new BlockPos(1400, 30, 1000);
        final BlockPos stonePos = origin.offset(4, 0, 0);
        final BlockPos treeBase = origin.offset(-4, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, 12);
        ctx.runCommand("setblock " + stonePos.getX() + " " + stonePos.getY() + " " + stonePos.getZ() + " stone");
        for (int i = 0; i < 3; i++) {
            ctx.runCommand("setblock " + treeBase.getX() + " " + (treeBase.getY() + i) + " " + treeBase.getZ() + " oak_log");
        }
        ctx.runCommand("give @s diamond_pickaxe");
        ctx.runCommand("give @s diamond_axe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        generateMesh(ctx, origin);

        ctx.runOnClient(mc -> {
            BotController.enqueueTask(new MineBlockTask(stonePos));
            List<TreeInfo> trees = TreeDetector.findTrees(mc.level, treeBase, 3);
            if (trees.isEmpty()) {
                throw new AssertionError("No tree detected at " + treeBase);
            }
            BotController.enqueueTask(new ChopTreeTask(trees.get(0)));
        });

        ctx.waitFor(mc -> mc.level.getBlockState(stonePos).isAir());
        LOGGER.info("Stone mined, now chopping tree");

        ctx.waitFor(mc -> {
            for (int i = 0; i < 3; i++) {
                if (!mc.level.getBlockState(treeBase.offset(0, i, 0)).isAir()) {
                    return false;
                }
            }
            return true;
        });

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Walk mine walk chop test passed");
    }

    // ================================================================
    // Test 8: Mine ore vein — 3 adjacent ore blocks
    // ================================================================
    @MinecraftTest(name = "Bot mine ore vein", timeoutTicks = 120, order = -193)
    public void mineOreVein(TestContext ctx) {
        final BlockPos origin = new BlockPos(1450, 30, 1000);
        final BlockPos ore1 = origin.offset(2, 0, 0);
        final BlockPos ore2 = origin.offset(2, 1, 0);
        final BlockPos ore3 = origin.offset(2, 0, 1);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, 8);
        ctx.runCommand("setblock " + ore1.getX() + " " + ore1.getY() + " " + ore1.getZ() + " iron_ore");
        ctx.runCommand("setblock " + ore2.getX() + " " + ore2.getY() + " " + ore2.getZ() + " iron_ore");
        ctx.runCommand("setblock " + ore3.getX() + " " + ore3.getY() + " " + ore3.getZ() + " iron_ore");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        ctx.runOnClient(mc -> {
            BotController.enqueueTask(new MineBlockTask(ore1));
            BotController.enqueueTask(new MineBlockTask(ore2));
            BotController.enqueueTask(new MineBlockTask(ore3));
        });

        ctx.waitFor(mc ->
                mc.level.getBlockState(ore1).isAir()
                        && mc.level.getBlockState(ore2).isAir()
                        && mc.level.getBlockState(ore3).isAir());

        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Mine ore vein test passed");
    }

    // ================================================================
    // Test 9: Out-of-reach failure
    // ================================================================
    @MinecraftTest(name = "Bot out-of-reach failure", timeoutTicks = 60, order = -192)
    public void outOfReachFailure(TestContext ctx) {
        final BlockPos origin = new BlockPos(1200, 30, 1000);
        final BlockPos farBlock = new BlockPos(1200, 50, 1000);

        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("clear @s");
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

    // --- Shared helpers ---

    /**
     * Common setup: stop bot, clear inventory, switch to creative,
     * teleport and wait for chunks. Does NOT switch to survival yet —
     * call switchToSurvivalAt() after building terrain.
     */
    private void setupTest(TestContext ctx, BlockPos origin) {
        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("clear @s");
        ctx.runCommand("gamemode creative");
        teleportAndWaitForChunks(ctx, origin);
    }

    /**
     * Switch to survival and teleport to the stand position on the built ground.
     */
    private void switchToSurvivalAt(TestContext ctx, BlockPos standPos, int groundY) {
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + (groundY) + " " + (standPos.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());
        ctx.runCommand("gamemode survival");
    }

    private void teleportAndWaitForChunks(TestContext ctx, BlockPos origin) {
        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + (origin.getY() + 5) + " " + (origin.getZ() + 0.5));
        ctx.waitFor(mc -> {
            if (mc.player == null || mc.level == null) {
                return false;
            }
            return mc.level.hasChunkAt(origin)
                    && Math.abs(mc.player.position().x - (origin.getX() + 0.5)) < 20
                    && Math.abs(mc.player.position().z - (origin.getZ() + 0.5)) < 20;
        });
    }

    private void clearArea(TestContext ctx, BlockPos center, int radius) {
        ctx.runCommand("fill "
                + (center.getX() - radius) + " " + (center.getY() - radius) + " " + (center.getZ() - radius) + " "
                + (center.getX() + radius) + " " + (center.getY() + radius) + " " + (center.getZ() + radius) + " air");
    }

    /** Build a 3x3 stone stand platform at groundY-1. */
    private void buildStandPlatform(TestContext ctx, BlockPos standPos, int groundY) {
        ctx.runCommand("fill " + (standPos.getX() - 1) + " " + (groundY - 1) + " " + (standPos.getZ() - 1)
                + " " + (standPos.getX() + 1) + " " + (groundY - 1) + " " + (standPos.getZ() + 1) + " stone");
    }

    /** Build a flat stone platform and clear air above it. */
    private void buildPlatform(TestContext ctx, BlockPos center, int radius) {
        clearArea(ctx, center, radius);
        ctx.runCommand("fill "
                + (center.getX() - radius) + " " + (center.getY() - 1) + " " + (center.getZ() - radius) + " "
                + (center.getX() + radius) + " " + (center.getY() - 1) + " " + (center.getZ() + radius) + " stone");
    }

    /** Generate pathfinding mesh for chunks around the position. */
    private void generateMesh(TestContext ctx, BlockPos center) {
        ctx.runOnClient(mc -> {
            if (mc.player == null || mc.level == null) {
                return;
            }
            int cx = center.getX() >> 4;
            int cz = center.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int chunkX = cx + dx;
                    int chunkZ = cz + dz;
                    if (mc.level.hasChunk(chunkX, chunkZ)) {
                        net.stracciatella.pathfinding.logic.MeshManager.generateMesh(
                                mc.level.getChunk(chunkX, chunkZ), mc.player);
                    }
                }
            }
        });
    }
}

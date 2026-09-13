package net.stracciatella.bot.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.behavior.RestockBehavior;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.scan.TreeDetector;
import net.stracciatella.bot.scan.TreeInfo;
import net.stracciatella.bot.server.ServerSettings;
import net.stracciatella.bot.server.ServerSettingsStore;
import net.stracciatella.bot.server.StorageSite;
import net.stracciatella.bot.task.ChopTreeTask;
import net.stracciatella.bot.task.MineBlockTask;
import net.stracciatella.bot.task.PlaceBlockTask;
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
    // Test 1: Single block mine — collect cobblestone
    // ================================================================
    @MinecraftTest(name = "Bot mine single block", timeoutTicks = 120, order = -200)
    public void mineSingleBlock(TestContext ctx) {
        final BlockPos origin = new BlockPos(1000, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));
        waitForBotIdle(ctx);
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        LOGGER.info("Single block mine test passed");
    }

    // ================================================================
    // Test 2: Tool selection — collect raw iron
    // ================================================================
    @MinecraftTest(name = "Bot tool selection", timeoutTicks = 120, order = -199)
    public void toolSelection(TestContext ctx) {
        final BlockPos origin = new BlockPos(1050, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " iron_ore");
        ctx.runCommand("give @s wooden_pickaxe");
        ctx.runCommand("give @s iron_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));
        waitForBotIdle(ctx);
        waitForItem(ctx, Items.RAW_IRON, 1, "raw iron");
        LOGGER.info("Tool selection test passed");
    }

    // ================================================================
    // Test 3: Tree chop — all logs mined, collect at least some
    // ================================================================
    @MinecraftTest(name = "Bot chop tree", timeoutTicks = 140, order = -198)
    public void chopTree(TestContext ctx) {
        final BlockPos origin = new BlockPos(1100, 30, 1000);
        final BlockPos treeBase = origin;
        final BlockPos standPos = origin.offset(2, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
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

        // Wait for all blocks to be mined
        ctx.waitFor(mc -> {
            for (int i = 0; i < 4; i++) {
                if (!mc.level.getBlockState(treeBase.offset(0, i, 0)).isAir()) {
                    return false;
                }
            }
            return true;
        });
        waitForBotIdle(ctx);
        // The test only ends when EVERY felled log has been picked up —
        // 4 logs mined, 4 logs in the inventory.
        waitForItem(ctx, Items.OAK_LOG, 4, "oak logs");
        LOGGER.info("Tree chop test passed (collected {} oak logs)", countItem(ctx, Items.OAK_LOG));
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
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");

        switchToSurvivalAt(ctx, standPos, origin.getY());
        // Face away from the block
        ctx.runOnClient(mc -> {
            mc.player.setYRot(180.0f);
            mc.player.setXRot(0.0f);
        });

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

        waitForBotIdle(ctx);
        LOGGER.info("Camera smoothness test passed (max delta: {})", String.format("%.1f", maxDelta[0]));
    }

    // ================================================================
    // Test 5: Walk to distant block, mine it, collect cobblestone
    // ================================================================
    @MinecraftTest(name = "Bot walk and mine", timeoutTicks = 200, order = -196)
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
        ctx.waitFor(mc -> BotController.getPhase() != BotController.Phase.IDLE);
        waitForBotIdle(ctx);
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        LOGGER.info("Walk and mine test passed");
    }

    // ================================================================
    // Test 6: Task queue — mine two blocks, verify blocks mined + items
    // ================================================================
    @MinecraftTest(name = "Bot mine queue", timeoutTicks = 250, order = -195, repeat = 5)
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

        // Wait for both blocks to be mined
        ctx.waitFor(mc -> mc.level.getBlockState(block1).isAir()
                && mc.level.getBlockState(block2).isAir());
        waitForBotIdle(ctx);
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        waitForItem(ctx, Items.RAW_IRON, 1, "raw iron");
        LOGGER.info("Mine queue test passed");
    }

    // ================================================================
    // Test 7: Walk → mine stone → walk → chop tree, verify all mined
    // ================================================================
    @MinecraftTest(name = "Bot walk mine walk chop", timeoutTicks = 280, order = -194)
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

        // Wait for stone + all logs to be mined
        ctx.waitFor(mc -> {
            if (!mc.level.getBlockState(stonePos).isAir()) {
                return false;
            }
            for (int i = 0; i < 3; i++) {
                if (!mc.level.getBlockState(treeBase.offset(0, i, 0)).isAir()) {
                    return false;
                }
            }
            return true;
        });
        waitForBotIdle(ctx);
        // All resources must be collected: 1 stone block and all 3 felled logs.
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        waitForItem(ctx, Items.OAK_LOG, 3, "oak logs");
        LOGGER.info("Walk mine walk chop test passed");
    }

    // ================================================================
    // Test 8: Mine ore vein — 3 adjacent ore blocks
    // ================================================================
    @MinecraftTest(name = "Bot mine ore vein", timeoutTicks = 250, order = -193, repeat = 5)
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

        ctx.waitFor(mc -> mc.level.getBlockState(ore1).isAir()
                && mc.level.getBlockState(ore2).isAir()
                && mc.level.getBlockState(ore3).isAir());
        waitForBotIdle(ctx);
        // All 3 ores mined from same spot — items drop right at the player
        waitForItem(ctx, Items.RAW_IRON, 3, "raw iron");
        LOGGER.info("Mine ore vein test passed");
    }

    // ================================================================
    // Test: Tool in storage row — bot must swap it into hotbar
    // ================================================================
    @MinecraftTest(name = "Bot tool in inventory slot", timeoutTicks = 140, order = -190)
    public void toolInInventorySlot(TestContext ctx) {
        final BlockPos origin = new BlockPos(1550, 30, 1000);
        final BlockPos blockPos = origin;
        final BlockPos standPos = origin.offset(0, 0, 2);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + blockPos.getX() + " " + (blockPos.getY() - 1) + " " + blockPos.getZ() + " stone");
        ctx.runCommand("setblock " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ() + " iron_ore");
        // Place pickaxe in storage row (slot 18 = row 2, column 1) instead of
        // the hotbar (slots 0-8). Pre-B1 selectBestTool only scanned the
        // hotbar and silently fell back to bare hand, dropping nothing.
        ctx.runCommand("item replace entity @s inventory.18 with minecraft:diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(blockPos)));
        waitForBotIdle(ctx);
        waitForItem(ctx, Items.RAW_IRON, 1, "raw iron");
        LOGGER.info("Tool in inventory slot test passed");
    }

    // ================================================================
    // Test: Block behind obstacle — bot must NOT enter INTERACTING
    // ================================================================
    @MinecraftTest(name = "Bot mine blocked by obstacle", timeoutTicks = 100, order = -191)
    public void mineBlockBehindObstacle(TestContext ctx) {
        final BlockPos origin = new BlockPos(1500, 30, 1000);
        final BlockPos target = origin.offset(2, 0, 0);
        final BlockPos obstacleLower = origin.offset(1, 0, 0);
        final BlockPos obstacleUpper = obstacleLower.above();

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " stone");
        ctx.runCommand("setblock " + obstacleLower.getX() + " " + obstacleLower.getY() + " " + obstacleLower.getZ() + " stone");
        ctx.runCommand("setblock " + obstacleUpper.getX() + " " + obstacleUpper.getY() + " " + obstacleUpper.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(target)));

        // Poll for ~50 ticks: bot must never enter INTERACTING because the
        // raycast hits the obstacle, never the target. Without the hit-result
        // gate the bot would transition on angular tolerance alone and start
        // attacking the explicit target — letting the server reject it. With
        // the gate the bot stays in LOOKING until line of sight is clear, then
        // times out (the obstacle blocks it permanently).
        boolean sawInteracting = false;
        for (int i = 0; i < 50; i++) {
            ctx.waitTick();
            BotController.Phase p = BotController.getPhase();
            if (p == BotController.Phase.INTERACTING) {
                sawInteracting = true;
                break;
            }
            if (p == BotController.Phase.IDLE) {
                break;
            }
        }

        ctx.runOnClient(mc -> BotController.stop());

        if (sawInteracting) {
            throw new AssertionError("Bot transitioned to INTERACTING despite obstacle blocking line of sight");
        }

        boolean obstacleIntact = ctx.computeOnClient(mc -> !mc.level.getBlockState(obstacleLower).isAir()
                && !mc.level.getBlockState(obstacleUpper).isAir());
        boolean targetIntact = ctx.computeOnClient(mc -> !mc.level.getBlockState(target).isAir());
        if (!obstacleIntact) {
            throw new AssertionError("Bot attacked the obstacle block instead of waiting for line of sight");
        }
        if (!targetIntact) {
            throw new AssertionError("Target was mined through obstacle");
        }
        LOGGER.info("Mine block behind obstacle test passed");
    }

    // ================================================================
    // Test: Place a block — USE task against a support face
    // ================================================================
    @MinecraftTest(name = "Bot place block", timeoutTicks = 140, order = -189)
    public void placeBlock(TestContext ctx) {
        final BlockPos origin = new BlockPos(1600, 30, 1000);
        final BlockPos placePos = origin.offset(2, 0, 0);
        final BlockPos supportPos = placePos.below();

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("give @s cobblestone 8");
        // Survival matters here: the placement is confirmed by the block
        // being consumed from the inventory, and creative never consumes it.
        switchToSurvivalAt(ctx, origin, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new PlaceBlockTask(
                placePos, supportPos, stack -> stack.is(Items.COBBLESTONE), "cobblestone")));
        waitForBotIdle(ctx);

        boolean placed = ctx.computeOnClient(mc -> mc.level.getBlockState(placePos).is(Blocks.COBBLESTONE));
        if (!placed) {
            throw new AssertionError("Expected cobblestone at " + placePos.toShortString()
                    + " but found " + ctx.computeOnClient(mc -> mc.level.getBlockState(placePos).getBlock()));
        }
        int remaining = countItem(ctx, Items.COBBLESTONE);
        if (remaining != 7) {
            throw new AssertionError("Expected 7 cobblestone left after placing one, found " + remaining);
        }
        LOGGER.info("Place block test passed");
    }

    // ================================================================
    // Test: Support finder — no sturdy neighbour means no task
    // ================================================================
    @MinecraftTest(name = "Bot place needs support", timeoutTicks = 80, order = -188)
    public void placeNeedsSupport(TestContext ctx) {
        final BlockPos origin = new BlockPos(1650, 30, 1000);
        // Two blocks above the platform, so every neighbour is air.
        final BlockPos floating = origin.offset(2, 2, 0);
        final BlockPos onFloor = origin.offset(2, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        switchToSurvivalAt(ctx, origin, origin.getY());

        BlockPos floatingSupport = ctx.computeOnClient(
                mc -> PlaceBlockTask.findSupport(mc.level, floating, mc.player.getEyePosition()));
        if (floatingSupport != null) {
            throw new AssertionError("findSupport returned " + floatingSupport.toShortString()
                    + " for a position surrounded by air");
        }
        BlockPos floorSupport = ctx.computeOnClient(
                mc -> PlaceBlockTask.findSupport(mc.level, onFloor, mc.player.getEyePosition()));
        if (!onFloor.below().equals(floorSupport)) {
            throw new AssertionError("Expected the platform below " + onFloor.toShortString()
                    + " as support, got " + floorSupport);
        }
        LOGGER.info("Place needs support test passed");
    }

    // ================================================================
    // Test 10: Damage stops a behavior that asked for it
    // ================================================================
    @MinecraftTest(name = "Bot policy damage stop", timeoutTicks = 200, order = -187)
    public void policyDamageStop(TestContext ctx) {
        final BlockPos origin = new BlockPos(1700, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        // Four blocks so the run is still going when the damage lands.
        final List<BlockPos> blocks = List.of(
                origin, origin.offset(1, 0, 0), origin.offset(2, 0, 0), origin.offset(3, 0, 0));

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        for (BlockPos block : blocks) {
            ctx.runCommand("setblock " + block.getX() + " " + block.getY() + " " + block.getZ() + " stone");
        }
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        startProbe(ctx, BotPolicy.none().withDamageStop(), blocks);
        // Only meaningful once the bot is actually working.
        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING);

        ctx.runCommand("damage @s 1");
        ctx.waitFor(mc -> !BehaviorRunner.isActive());

        int remaining = ctx.computeOnClient(mc -> {
            int count = 0;
            for (BlockPos block : blocks) {
                if (!mc.level.getBlockState(block).isAir()) {
                    count++;
                }
            }
            return count;
        });
        if (remaining == 0) {
            throw new AssertionError("Run mined every block — the damage stop never fired");
        }
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Damage stop test passed ({} of {} blocks left)", remaining, blocks.size());
    }

    // ================================================================
    // Test 11: A full inventory stops the run before it starts
    // ================================================================
    @MinecraftTest(name = "Bot policy inventory full stop", timeoutTicks = 200, order = -186)
    public void policyInventoryFullStop(TestContext ctx) {
        final BlockPos origin = new BlockPos(1750, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        final List<BlockPos> blocks = List.of(origin);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " stone");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        // Leave exactly one empty slot, below the policy's minimum of two.
        for (int slot = 0; slot < 35; slot++) {
            ctx.runCommand("item replace entity @s container." + slot + " with minecraft:dirt 1");
        }
        int free = ctx.computeOnClient(mc -> InventoryHelper.freeSlots(mc.player));
        if (free != 1) {
            throw new AssertionError("Expected 1 free slot after filling the inventory, got " + free);
        }

        startProbe(ctx, BotPolicy.none().withInventoryFullStop(2), blocks);
        ctx.waitFor(mc -> !BehaviorRunner.isActive());

        boolean stillThere = ctx.computeOnClient(mc -> !mc.level.getBlockState(origin).isAir());
        if (!stillThere) {
            throw new AssertionError("Block was mined although the inventory was full");
        }
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Inventory full stop test passed");
    }

    // ================================================================
    // Test 12: fastCollectExit — an unreachable drop must not pin COLLECTING
    // ================================================================
    @MinecraftTest(name = "Bot policy fast collect exit", timeoutTicks = 200, order = -185)
    public void policyFastCollectExit(TestContext ctx) {
        final BlockPos origin = new BlockPos(1800, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        final List<BlockPos> blocks = List.of(origin);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        // A drop the bot has decided it will never walk to: inside the
        // 8-block collect query, outside the 4-block vertical walk filter.
        // NoGravity keeps it up there; without the policy flag its mere
        // presence holds `itemsNearby` true until collectWaitMax expires.
        ctx.runCommand("summon item " + (standPos.getX() + 0.5) + " " + (origin.getY() + 6)
                + " " + (standPos.getZ() + 0.5)
                + " {Item:{id:\"minecraft:dirt\",count:1},NoGravity:1b,PickupDelay:32767s}");
        ctx.waitFor(mc -> countDecoysAbove(ctx, origin) == 1);

        long startTick = ctx.computeOnClient(mc -> mc.level.getGameTime());
        startProbe(ctx, BotPolicy.none().withFastCollectExit(), blocks);
        ctx.waitFor(mc -> !BehaviorRunner.isActive());
        final long elapsed = ctx.computeOnClient(mc -> mc.level.getGameTime()) - startTick;

        if (countDecoysAbove(ctx, origin) != 1) {
            throw new AssertionError("The decoy drop is gone — the test proved nothing");
        }
        if (ctx.computeOnClient(mc -> !mc.level.getBlockState(origin).isAir())) {
            throw new AssertionError("Block was never mined — the run failed rather than finished");
        }
        // Exiting COLLECTING sooner must not mean exiting before collecting:
        // the drop from the mined block still has to end up in the inventory.
        if (countItem(ctx, Items.COBBLESTONE) < 1) {
            throw new AssertionError("Left COLLECTING without picking up the drop");
        }
        // Mining plus collecting a single block is a few hundred ticks. Taking
        // longer than the collect timeout alone means COLLECTING sat there
        // until it expired, which is exactly the stall this flag removes.
        if (elapsed >= BotController.CONFIG.collectWaitMax) {
            throw new AssertionError("COLLECTING stalled: run took " + elapsed
                    + " ticks, collectWaitMax is " + BotController.CONFIG.collectWaitMax);
        }
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Fast collect exit test passed ({} ticks, budget {})",
                elapsed, BotController.CONFIG.collectWaitMax);
    }

    // ================================================================
    // Test 13: opportunisticCollection — strafe toward a drop mid-break
    // ================================================================
    @MinecraftTest(name = "Bot policy opportunistic collection", timeoutTicks = 200, order = -184)
    public void policyOpportunisticCollection(TestContext ctx) {
        final BlockPos origin = new BlockPos(1850, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        // Sideways from the bot, perpendicular to the block it will be mining,
        // so reaching it is a pure strafe — the case the direction math exists
        // for, and the one a turn would break.
        final double itemX = standPos.getX() + 0.5 + 2.5;
        final double itemZ = standPos.getZ() + 0.5;

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        // Obsidian with a diamond pickaxe: ~187 ticks of INTERACTING, long
        // enough for the walk to be observable, and it still drops — a break
        // that produces no drop never confirms and would end in a timeout
        // rather than in the phase this test needs to watch.
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " obsidian");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        // PickupDelay keeps the drop on the ground, so the measurement is of
        // the bot closing the distance rather than of the item vanishing.
        ctx.runCommand("summon item " + itemX + " " + (origin.getY() + 0.2) + " " + itemZ
                + " {Item:{id:\"minecraft:dirt\",count:1},NoGravity:1b,PickupDelay:32767s}");

        // Baseline from where the test parked the bot, taken before the run.
        // Sampling it after INTERACTING is reached races the walk itself: the
        // bot steps on the first INTERACTING tick and stops at
        // OPPORTUNISTIC_STOP_DISTANCE, so a baseline read a few ticks in is
        // already most of the way down and the remaining travel is smaller
        // than any margin worth asserting on.
        double startDist = ctx.computeOnClient(mc -> horizDistTo(mc, itemX, itemZ));

        startProbe(ctx, BotPolicy.none().withOpportunisticCollection(), List.of(origin));
        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING);

        // Closing the gap has to happen *while the break runs* — that is the
        // whole feature. Checked in one predicate rather than as a separate
        // assertion afterwards, which would race the end of the break.
        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING
                && horizDistTo(mc, itemX, itemZ) < startDist - 0.5);

        double endDist = ctx.computeOnClient(mc -> horizDistTo(mc, itemX, itemZ));
        // This is the only policy test that ends with the behavior still
        // running, so stopping goes top-down: the runner aborts the behavior,
        // which stops the controller — never the other way around.
        ctx.runOnClient(mc -> BehaviorRunner.stop());
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Opportunistic collection test passed (closed {} → {} blocks while mining)",
                String.format(java.util.Locale.US, "%.2f", startDist),
                String.format(java.util.Locale.US, "%.2f", endDist));
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
        ctx.runCommand("gamemode creative");

        teleportAndWaitForChunks(ctx, origin);

        buildPlatform(ctx, origin, CLEAR_RADIUS);
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

    // ================================================================
    // Test 16: POSITIONING must not walk into a hole to reach a support
    // ================================================================
    /**
     * A placement whose support block sits on the far side of a cavity. The
     * chunk miner produces exactly this over a cave: the cell it wants to
     * floor has lost every neighbour except the far rim, so
     * {@link PlaceBlockTask#findSupport} can only return that rim — and at
     * 5.4 blocks from the eye it lands in the window where
     * {@code startNextTask} skips pathfinding and hands the walk to
     * POSITIONING, which drives {@code keyUp} directly. The camera is aimed
     * at a block below the bot's feet, the walk follows the gaze, and the
     * hole is on the way.
     *
     * <p>The task is expected to fail: the support is out of reach, and
     * bridging reaches one block, not a cavity. What must not happen is the
     * bot arriving at the bottom of it. This is the walk that keeps the plain
     * refusal — no crouch, because creeping at a third speed cannot close
     * five blocks either, and the position timeout is then the honest answer.
     * The crouch is for the re-approach, which only has to move the viewpoint.
     */
    @MinecraftTest(name = "Bot refuses to walk off a ledge", timeoutTicks = 200, order = -183)
    public void refusesToWalkOffALedge(TestContext ctx) {
        final BlockPos origin = new BlockPos(1750, 30, 1000);
        final BlockPos placePos = origin.offset(4, -1, 0);
        final BlockPos supportPos = origin.offset(5, -1, 0);
        final int floorY = origin.getY() - 1;
        final int caveFloorY = origin.getY() - 5;

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        // The cavity: floor gone two to four blocks east of the bot, four
        // blocks deep, with a stone bottom so a fall is measurable instead
        // of endless. The rim at x+5 stays, and is the only sturdy face left
        // anywhere around placePos.
        ctx.runCommand("fill " + (origin.getX() + 2) + " " + (caveFloorY + 1) + " " + (origin.getZ() - 3)
                + " " + (origin.getX() + 4) + " " + floorY + " " + (origin.getZ() + 3) + " air");
        ctx.runCommand("fill " + (origin.getX() + 2) + " " + caveFloorY + " " + (origin.getZ() - 3)
                + " " + (origin.getX() + 4) + " " + caveFloorY + " " + (origin.getZ() + 3) + " stone");
        ctx.runCommand("give @s cobblestone 8");
        switchToSurvivalAt(ctx, origin, origin.getY());

        BlockPos support = ctx.computeOnClient(
                mc -> PlaceBlockTask.findSupport(mc.level, placePos, mc.player.getEyePosition()));
        if (!supportPos.equals(support)) {
            throw new AssertionError("Fixture broken: expected the far rim " + supportPos.toShortString()
                    + " as the only support for " + placePos.toShortString() + ", got " + support);
        }

        final double[] lowestFeetY = { origin.getY() };
        ctx.runOnClient(mc -> BotController.enqueueTask(new PlaceBlockTask(
                placePos, supportPos, stack -> stack.is(Items.COBBLESTONE), "cobblestone")));
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.getY());
            return BotController.getPhase() == BotController.Phase.IDLE;
        });
        ctx.runOnClient(mc -> BotController.stop());

        if (lowestFeetY[0] < origin.getY() - 0.5) {
            throw new AssertionError("Bot walked into the cavity: feet reached y="
                    + String.format("%.2f", lowestFeetY[0]) + ", stands on y=" + origin.getY());
        }
        LOGGER.info("Ledge test passed (feet stayed at y={})", String.format("%.2f", lowestFeetY[0]));
    }

    // ================================================================
    // Test 17: bridging — the placement that can only be made from the edge
    // ================================================================
    /**
     * The gap beside the bot has lost every neighbour except the block under
     * its own feet, so {@link PlaceBlockTask#findSupport} can only return
     * that one and the face to click is its vertical side. Every ray from an
     * eye still over the block meets the block's top face first, which is why
     * this placement used to be unreachable from anywhere: the bot cannot
     * step back far enough to see a face that its own footing hides.
     *
     * <p>What works is what a player does to bridge — crouch, walk out until
     * the edge of the block underfoot comes into view, place. The crouch is
     * both halves of it: vanilla will not let a crouched walk leave the block
     * it stands on, and the point it stops at is roughly a third of a block
     * past the rim, which is exactly where the side face becomes visible.
     *
     * <p>Asserts both halves: the block lands, and the bot is still standing
     * on the slab when it does.
     */
    @MinecraftTest(name = "Bot bridges into a gap", timeoutTicks = 300, order = -182)
    public void bridgesIntoAGap(TestContext ctx) {
        final BlockPos origin = new BlockPos(1800, 30, 1000);
        final BlockPos placePos = origin.offset(1, -1, 0);
        final BlockPos supportPos = origin.below();
        final int caveFloorY = origin.getY() - 5;

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        // Everything east of the bot is gone, four blocks deep, so the only
        // sturdy face anywhere around placePos is the one under its feet.
        ctx.runCommand("fill " + (origin.getX() + 1) + " " + (caveFloorY + 1) + " "
                + (origin.getZ() - 3) + " " + (origin.getX() + 5) + " " + (origin.getY() - 1)
                + " " + (origin.getZ() + 3) + " air");
        ctx.runCommand("fill " + (origin.getX() + 1) + " " + caveFloorY + " "
                + (origin.getZ() - 3) + " " + (origin.getX() + 5) + " " + caveFloorY
                + " " + (origin.getZ() + 3) + " stone");
        ctx.runCommand("give @s cobblestone 8");
        switchToSurvivalAt(ctx, origin, origin.getY());

        BlockPos support = ctx.computeOnClient(
                mc -> PlaceBlockTask.findSupport(mc.level, placePos, mc.player.getEyePosition()));
        if (!supportPos.equals(support)) {
            throw new AssertionError("Fixture broken: expected the block underfoot "
                    + supportPos.toShortString() + " as the only support for "
                    + placePos.toShortString() + ", got " + support);
        }

        // Sampled per tick: how far out the bot got, whether it was crouching
        // while it was out there, and whether it ever left the slab. The
        // crouch is not decoration — it is what stops the walk at the rim, and
        // without it the only thing between the bot and the drop is the
        // arrival check firing in time.
        final double[] lowestFeetY = { origin.getY() };
        final double[] furthestX = { origin.getX() + 0.5 };
        final boolean[] crouchedPastRim = { false };
        ctx.runOnClient(mc -> BotController.enqueueTask(new PlaceBlockTask(
                placePos, supportPos, stack -> stack.is(Items.COBBLESTONE), "cobblestone")));
        ctx.waitFor(mc -> {
            lowestFeetY[0] = Math.min(lowestFeetY[0], mc.player.getY());
            furthestX[0] = Math.max(furthestX[0], mc.player.getX());
            if (mc.player.getX() > origin.getX() + 1.0 && mc.player.isShiftKeyDown()) {
                crouchedPastRim[0] = true;
            }
            return BotController.getPhase() == BotController.Phase.IDLE;
        });
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Bridge run: furthestX={} crouchedPastRim={} lowestFeetY={}",
                String.format("%.2f", furthestX[0]), crouchedPastRim[0],
                String.format("%.2f", lowestFeetY[0]));

        boolean placed = ctx.computeOnClient(
                mc -> mc.level.getBlockState(placePos).is(Blocks.COBBLESTONE));
        if (!placed) {
            throw new AssertionError("Expected cobblestone bridged into "
                    + placePos.toShortString() + " but found "
                    + ctx.computeOnClient(mc -> mc.level.getBlockState(placePos).getBlock()));
        }
        if (lowestFeetY[0] < origin.getY() - 0.5) {
            throw new AssertionError("Bot fell while bridging: feet reached y="
                    + String.format("%.2f", lowestFeetY[0]));
        }
        if (!crouchedPastRim[0]) {
            throw new AssertionError("Bot placed the block without crouching past the rim"
                    + " (furthest x=" + String.format("%.2f", furthestX[0])
                    + ", rim at x=" + (origin.getX() + 1) + ") — it stayed up only because"
                    + " the walk happened to stop in time");
        }
        LOGGER.info("Bridge test passed (feet stayed at y={}, crouched out to x={})",
                String.format("%.2f", lowestFeetY[0]), String.format("%.2f", furthestX[0]));
    }

    // ================================================================
    // Test 18: digging straight down keeps the heading
    // ================================================================
    /**
     * Three blocks mined one under the other, each from the hole the last
     * one left. The face aimed at is the top of the block underfoot, and the
     * horizontal offset from the eye to any point on it is a few centimetres
     * — the atan2 of that is a yaw in an arbitrary direction, re-rolled with
     * the jitter for every block. The bot turned to a new random heading per
     * block, up to half a turn, reported as wild spinning on the way down.
     * The aim point is laid along the heading the bot already has instead;
     * asserted as the total yaw travelled over the whole descent.
     */
    @MinecraftTest(name = "Bot digs down without turning", timeoutTicks = 300, order = -181)
    public void digsDownWithoutTurning(TestContext ctx) {
        final BlockPos origin = new BlockPos(1900, 30, 1000);
        final List<BlockPos> column = List.of(origin.below(), origin.below(2), origin.below(3));

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        // Stone three deep under the platform's two layers, so the shaft has
        // walls all the way down and a floor under the last block.
        ctx.runCommand("fill " + (origin.getX() - 1) + " " + (origin.getY() - 5) + " " + (origin.getZ() - 1)
                + " " + (origin.getX() + 1) + " " + (origin.getY() - 3) + " " + (origin.getZ() + 1) + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());
        ctx.runOnClient(mc -> {
            mc.player.setYRot(90.0f);
            mc.player.setXRot(0.0f);
        });

        final float[] lastYaw = {90.0f};
        final double[] yawTravel = {0.0};
        ctx.runOnClient(mc -> {
            for (BlockPos block : column) {
                BotController.enqueueTask(new MineBlockTask(block));
            }
        });
        ctx.waitFor(mc -> {
            yawTravel[0] += yawStep(lastYaw, mc.player.getYRot());
            return BotController.getPhase() == BotController.Phase.IDLE;
        });
        ctx.runOnClient(mc -> BotController.stop());

        for (BlockPos block : column) {
            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(block).isAir())) {
                throw new AssertionError("Block " + block.toShortString() + " was not mined");
            }
        }
        waitForItem(ctx, Items.COBBLESTONE, 3, "cobblestone");
        if (yawTravel[0] > 45.0) {
            throw new AssertionError("Bot turned " + String.format("%.0f", yawTravel[0])
                    + " degrees digging three blocks straight down (expected under 45)");
        }
        LOGGER.info("Dig down test passed (yaw travel {} degrees)", String.format("%.1f", yawTravel[0]));
    }

    // ================================================================
    // Test 19: a one-block rise on the way is jumped
    // ================================================================
    /**
     * The bot starts one block down, in a dip in the platform, with its
     * target five blocks off at platform level — close enough that the walk
     * is POSITIONING's raw keys, not a path. Vanilla's auto-step is 0.6
     * blocks, so without a jump the walk stands against the rim until the
     * position timeout fails the task; that is how the bot got stuck after
     * collecting a drop from a block lower down. Asserted as the block being
     * mined, from the platform.
     */
    @MinecraftTest(name = "Bot steps up out of a dip", timeoutTicks = 300, order = -180)
    public void stepsUpOutOfADip(TestContext ctx) {
        final BlockPos origin = new BlockPos(1950, 30, 1000);
        final BlockPos dip = origin.below();
        final BlockPos target = origin.offset(5, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, 10);
        ctx.runCommand("setblock " + dip.getX() + " " + dip.getY() + " " + dip.getZ() + " air");
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, dip.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(target)));
        waitForBotIdle(ctx);

        if (ctx.computeOnClient(mc -> !mc.level.getBlockState(target).isAir())) {
            throw new AssertionError("Target was not mined — the bot never got out of the dip");
        }
        double feetY = ctx.computeOnClient(mc -> mc.player.getY());
        if (feetY < origin.getY() - 0.01) {
            throw new AssertionError("Bot is still down at y=" + String.format("%.2f", feetY));
        }
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        LOGGER.info("Step-up test passed");
    }

    // ================================================================
    // Test 20: a drop beside the bot, one block down: collect, come back
    // ================================================================
    /**
     * A drop lies in a dip right beside the bot, well under a block off its
     * centre and a block down — inside the horizontal pickup box, outside
     * the vertical one. The 3-D distance that used to decide the walk said
     * "walk", and the gaze aimed at a point that close swung with every
     * step, the view-relative keys swung with it, and the bot spun into the
     * wall of its own shaft. With the gaze held and the walk by geometry it
     * steps down; the next task is then out of reach from the dip, so it has
     * to jump the rim to get on with it. Asserts both pickups, the second
     * block mined from platform level, and a collect well short of a spin.
     *
     * <p>The first block sits inside the controller's {@code CONTACT_DISTANCE} on
     * purpose, and that is a property of the fixture rather than of the feature:
     * the bot mines it from where it stands, so "0.7 blocks off its centre"
     * still describes the drop when the collect begins, and the drop is inside
     * the radius that holds the gaze — the code this test exists for. Two
     * blocks out the bot walks into the block while breaking, ends the break a
     * block nearer it, and the drop is then 1.1 away and behind: the hold never
     * engages, nothing under test runs, and turning round for it costs a
     * perfectly reasonable 187 degrees. A fixture that pins where the drop is
     * has to pin where the bot is standing too.
     */
    @MinecraftTest(name = "Bot collects from a dip and climbs back", timeoutTicks = 400, order = -179)
    public void collectsFromADipAndClimbsBack(TestContext ctx) {
        final BlockPos origin = new BlockPos(2000, 30, 1000);
        final BlockPos first = origin.offset(0, 0, -1);
        final BlockPos second = origin.offset(-4, 0, 0);
        final BlockPos dip = origin.offset(1, -1, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + first.getX() + " " + first.getY() + " " + first.getZ() + " stone");
        ctx.runCommand("setblock " + second.getX() + " " + second.getY() + " " + second.getZ() + " stone");
        ctx.runCommand("setblock " + dip.getX() + " " + dip.getY() + " " + dip.getZ() + " air");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());
        // 0.7 blocks east of the bot's centre: inside the half-block radius
        // that holds the gaze, and clear of the platform block's edge.
        ctx.runCommand("summon item " + (origin.getX() + 1.2) + " " + (dip.getY() + 0.2) + " "
                + (origin.getZ() + 0.5) + " {Item:{id:\"minecraft:dirt\",count:1}}");
        ctx.waitFor(mc -> !mc.level.getEntities(net.minecraft.world.entity.EntityType.ITEM,
                new net.minecraft.world.phys.AABB(dip), e -> true).isEmpty());

        final float[] lastYaw = {0.0f};
        final double[] collectYawTravel = {0.0};
        final boolean[] wentDown = {false};
        ctx.runOnClient(mc -> {
            lastYaw[0] = mc.player.getYRot();
            BotController.enqueueTask(new MineBlockTask(first));
            BotController.enqueueTask(new MineBlockTask(second));
        });
        ctx.waitFor(mc -> {
            double step = yawStep(lastYaw, mc.player.getYRot());
            if (BotController.getPhase() == BotController.Phase.COLLECTING) {
                collectYawTravel[0] += step;
            }
            if (mc.player.getY() < origin.getY() - 0.5) {
                wentDown[0] = true;
            }
            return BotController.getPhase() == BotController.Phase.IDLE;
        });
        ctx.runOnClient(mc -> BotController.stop());

        if (!wentDown[0]) {
            throw new AssertionError("Bot never stepped down into the dip");
        }
        waitForItem(ctx, Items.DIRT, 1, "dirt from the dip");
        if (ctx.computeOnClient(mc -> !mc.level.getBlockState(second).isAir())) {
            throw new AssertionError("Second block was not mined — the bot never climbed back out");
        }
        waitForItem(ctx, Items.COBBLESTONE, 2, "cobblestone");
        if (collectYawTravel[0] > 120.0) {
            throw new AssertionError("Bot turned " + String.format("%.0f", collectYawTravel[0])
                    + " degrees while collecting (expected under 120)");
        }
        LOGGER.info("Dip collect test passed (collect yaw travel {} degrees)",
                String.format("%.1f", collectYawTravel[0]));
    }

    // ================================================================
    // Test 21: what falls into the cell is mined as well
    // ================================================================
    /**
     * Stone two blocks ahead at feet level with two gravel stacked on it.
     * The stone going drops the first gravel into its cell a few ticks
     * later, and the second onto that. A task that latched "air" the moment
     * the stone went walked away from a cell that was full again — the
     * chunk miner's first layer under a gravel patch — and every fallen
     * block was then handled as a failed break. One task, asserted as the
     * cell and the two above it being air at the end, with both gravel drops
     * (gravel, or the flint it sometimes drops) collected.
     */
    @MinecraftTest(name = "Bot mines what falls into the cell", timeoutTicks = 400, order = -178)
    public void minesWhatFallsIntoTheCell(TestContext ctx) {
        final BlockPos origin = new BlockPos(2050, 30, 1000);
        final BlockPos target = origin.offset(2, 0, 0);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " stone");
        ctx.runCommand("setblock " + target.getX() + " " + (target.getY() + 1) + " " + target.getZ() + " gravel");
        ctx.runCommand("setblock " + target.getX() + " " + (target.getY() + 2) + " " + target.getZ() + " gravel");
        ctx.runCommand("give @s diamond_pickaxe");
        ctx.runCommand("give @s diamond_shovel");
        switchToSurvivalAt(ctx, origin, origin.getY());

        ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(target)));
        waitForBotIdle(ctx);

        for (int dy = 0; dy <= 2; dy++) {
            final BlockPos cell = target.above(dy);
            String standing = ctx.computeOnClient(mc -> {
                var state = mc.level.getBlockState(cell);
                return state.isAir() ? null : state.getBlock().getName().getString();
            });
            if (standing != null) {
                throw new AssertionError("Cell " + cell.toShortString() + " still holds " + standing);
            }
        }
        waitForItem(ctx, Items.COBBLESTONE, 1, "cobblestone");
        int gravelDrops = countItem(ctx, Items.GRAVEL) + countItem(ctx, Items.FLINT);
        if (gravelDrops < 2) {
            throw new AssertionError("Expected the drops of 2 gravel (gravel or flint), got " + gravelDrops);
        }
        LOGGER.info("Falling block test passed ({} gravel drops collected)", gravelDrops);
    }

    // ================================================================
    // Test 22: an ignored drop is neither walked to nor waited for
    // ================================================================
    /**
     * A drop on the ignore list is one the bot neither walks to nor waits
     * for, and both halves are asserted here on a drop it could perfectly
     * well have fetched: a cobblestone item standing four blocks off on the
     * bot's other side, inside the 8-block collect query and inside the
     * vertical walk filter, so the list is the only reason it is still there
     * at the end. It is summoned {@code NoGravity} and collectable — an
     * infinite {@code PickupDelay} like the fast-collect decoy's would make
     * the assertion hold with or without the list, and prove nothing.
     *
     * <p>The bot's own drop cannot carry this test any more, which is worth
     * knowing before someone writes it that way again. Since the working
     * distance the break ends about 1.7 blocks from the block; a drop spawns
     * up to a quarter block off centre and then pops and slides, and across
     * eleven runs of the fixture that asserted on the mined block's own
     * cobblestone the final bot-to-drop distance came out anywhere between
     * 1.62 and 4.41 blocks. The vanilla pickup box is 1.425 and vanilla
     * pickup is server-side, so "the drop is still on the ground" was a coin
     * toss about the drift, not a statement about the bot.
     *
     * <p>The mined stone still carries the waiting half: its cobblestone is
     * on the same list, so an ignored drop lying in plain sight must not pin
     * {@code itemsNearby} — the phase has to end inside the absence window
     * instead of at {@code collectWaitMax}, which is what an uncollected
     * drop in sight used to cost. Whether vanilla inhaled that one in
     * passing is deliberately not asserted.
     *
     * <p>The list is edited in place and put back whole, never saved: the
     * test must not leave a real config behind that it changed, and a later
     * test in the same run does save whatever it finds.
     */
    @MinecraftTest(name = "Bot ignores a listed drop", timeoutTicks = 400, order = -177)
    public void ignoresListedDrop(TestContext ctx) {
        final BlockPos origin = new BlockPos(2100, 30, 1000);
        final BlockPos target = origin.offset(3, 0, 0);
        final BlockPos bait = origin.offset(-4, 0, 0);
        final String cobblestone = "minecraft:cobblestone";

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        // The whole list is set aside and put back, not the one entry. Adding
        // an entry the list already holds is not a no-op — it appends a second
        // copy — so a removal skipped on the grounds that the entry was
        // already there, which is what stood here, leaves that copy behind on
        // every run of a player who ignores cobblestone themselves, and
        // `Bot ignore commands edit the list` writes the list to disk later in
        // the same run. Twelve copies had piled up in a real bot.json.
        final List<String> before = ctx.computeOnClient(
                mc -> new ArrayList<>(BotController.CONFIG.ignoredItems));
        ctx.runOnClient(mc -> BotController.CONFIG.ignoredItems.add(cobblestone));
        try {
            ctx.runCommand("summon item " + (bait.getX() + 0.5) + " " + bait.getY()
                    + " " + (bait.getZ() + 0.5)
                    + " {Item:{id:\"minecraft:cobblestone\",count:1},NoGravity:1b}");
            ctx.waitFor(mc -> countBaitAt(ctx, bait) == 1);

            long startTick = ctx.computeOnClient(mc -> mc.level.getGameTime());
            ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(target)));
            waitForBotIdle(ctx);
            final long elapsed = ctx.computeOnClient(mc -> mc.level.getGameTime()) - startTick;

            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(target).isAir())) {
                throw new AssertionError("Block was never mined");
            }
            if (countBaitAt(ctx, bait) != 1) {
                throw new AssertionError("The ignored cobblestone was collected");
            }
            // The bait starts at exactly four blocks and the work is in the
            // other direction, so the distance can only grow. Any approach at
            // all is the regression; 3.5 leaves room for the collect walk to
            // the mined block's own drop and nothing more.
            double toBait = ctx.computeOnClient(mc ->
                    horizDistTo(mc, bait.getX() + 0.5, bait.getZ() + 0.5));
            if (toBait < 3.5) {
                throw new AssertionError(String.format(
                        "Bot walked at the ignored drop (%.2f blocks from it, started at 4.00)",
                        toBait));
            }
            if (elapsed >= BotController.CONFIG.collectWaitMax) {
                throw new AssertionError("COLLECTING waited for the ignored drops: " + elapsed
                        + " ticks, collectWaitMax is " + BotController.CONFIG.collectWaitMax);
            }
            LOGGER.info("Ignored drop test passed ({} ticks, {} blocks from the bait)",
                    elapsed, String.format("%.2f", toBait));
        } finally {
            ctx.runOnClient(mc -> {
                BotController.CONFIG.ignoredItems.clear();
                BotController.CONFIG.ignoredItems.addAll(before);
            });
        }
    }

    // ================================================================
    // Test 23: a hungry bot eats between tasks, and before anything else
    // ================================================================
    /**
     * The food bar is run down under {@code eatBelowFoodLevel} with the
     * hunger effect and the effect cleared again, so the run starts from a
     * known, low level with bread in the inventory. The probe behavior asks
     * for auto-eat, and the meal has to come at the task boundary — EATING
     * before the first INTERACTING tick — cost exactly one bread, raise the
     * bar, and leave the block mined afterwards.
     */
    @MinecraftTest(name = "Bot eats between tasks", timeoutTicks = 400, order = -176)
    public void eatsBetweenTasks(TestContext ctx) {
        final BlockPos origin = new BlockPos(2150, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        final List<BlockPos> blocks = List.of(origin);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        ctx.runCommand("give @s bread 4");
        switchToSurvivalAt(ctx, standPos, origin.getY());
        try {
            final int hungry = runDownFoodBar(ctx);

            final boolean[] sawEating = {false};
            final boolean[] sawMining = {false};
            final boolean[] ateFirst = {false};
            startProbe(ctx, BotPolicy.none().withAutoEat(), blocks);
            ctx.waitFor(mc -> {
                BotController.Phase phase = BotController.getPhase();
                if (phase == BotController.Phase.EATING) {
                    sawEating[0] = true;
                    ateFirst[0] |= !sawMining[0];
                } else if (phase == BotController.Phase.INTERACTING) {
                    sawMining[0] = true;
                }
                return !BehaviorRunner.isActive();
            });

            if (!sawEating[0]) {
                throw new AssertionError("Bot never ate at food level " + hungry);
            }
            if (!ateFirst[0]) {
                throw new AssertionError("Bot started mining before it ate");
            }
            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(origin).isAir())) {
                throw new AssertionError("Block was never mined after the meal");
            }
            int bread = countItem(ctx, Items.BREAD);
            if (bread != 3) {
                throw new AssertionError("Expected one bread eaten, " + (4 - bread) + " gone");
            }
            int foodLevel = ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
            if (foodLevel <= hungry) {
                throw new AssertionError("Food level did not rise: " + hungry + " -> " + foodLevel);
            }
            ctx.runOnClient(mc -> BotController.stop());
            LOGGER.info("Auto-eat test passed (food level {} -> {})", hungry, foodLevel);
        } finally {
            ctx.runCommand("difficulty peaceful");
        }
    }

    // ================================================================
    // Test 24: hungry with nothing to eat — carry on, do not stand there
    // ================================================================
    /**
     * The same bar, no bread. The run must not enter EATING at all — there
     * is nothing to select, and holding the button on a pickaxe is a task
     * timeout waiting to happen — and the block still has to come out: a
     * missing meal is a warning to the player, not a reason to stop.
     */
    @MinecraftTest(name = "Bot works on hungry without food", timeoutTicks = 400, order = -175)
    public void worksOnHungryWithoutFood(TestContext ctx) {
        final BlockPos origin = new BlockPos(2200, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        final List<BlockPos> blocks = List.of(origin);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());
        try {
            final int hungry = runDownFoodBar(ctx);

            final boolean[] sawEating = {false};
            startProbe(ctx, BotPolicy.none().withAutoEat(), blocks);
            ctx.waitFor(mc -> {
                sawEating[0] |= BotController.getPhase() == BotController.Phase.EATING;
                return !BehaviorRunner.isActive();
            });

            if (sawEating[0]) {
                throw new AssertionError("Bot tried to eat with nothing to eat");
            }
            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(origin).isAir())) {
                throw new AssertionError("Block was never mined — the run stopped for want of food");
            }
            ctx.runOnClient(mc -> BotController.stop());
            LOGGER.info("Hungry-without-food test passed (food level {})", hungry);
        } finally {
            ctx.runCommand("difficulty peaceful");
        }
    }

    // ================================================================
    // Test 25: hungry with only what the meal list rules out — carry on
    // ================================================================
    /**
     * The exclusion list is the difference between food and a meal. Rotten
     * flesh and a golden apple both carry a food component, and both have
     * to stay where they are — the one for its hunger effect, the other for
     * being worth more than the stone it would buy — so the run must look
     * exactly like the one with no food at all: no EATING phase, both stacks
     * untouched, the block mined regardless.
     */
    @MinecraftTest(name = "Bot leaves what is not a meal alone", timeoutTicks = 400, order = -174)
    public void leavesNonMealsAlone(TestContext ctx) {
        final BlockPos origin = new BlockPos(2250, 30, 1000);
        final BlockPos standPos = origin.offset(0, 0, 2);
        final List<BlockPos> blocks = List.of(origin);

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + origin.getX() + " " + origin.getY() + " " + origin.getZ()
                + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        ctx.runCommand("give @s rotten_flesh 4");
        ctx.runCommand("give @s golden_apple");
        switchToSurvivalAt(ctx, standPos, origin.getY());
        try {
            final int hungry = runDownFoodBar(ctx);

            final boolean[] sawEating = {false};
            startProbe(ctx, BotPolicy.none().withAutoEat(), blocks);
            ctx.waitFor(mc -> {
                sawEating[0] |= BotController.getPhase() == BotController.Phase.EATING;
                return !BehaviorRunner.isActive();
            });

            if (sawEating[0]) {
                throw new AssertionError("Bot tried to eat what the meal list rules out");
            }
            int flesh = countItem(ctx, Items.ROTTEN_FLESH);
            int apples = countItem(ctx, Items.GOLDEN_APPLE);
            if (flesh != 4 || apples != 1) {
                throw new AssertionError("Inventory changed: " + flesh + " rotten flesh and "
                        + apples + " golden apple(s) left");
            }
            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(origin).isAir())) {
                throw new AssertionError("Block was never mined");
            }
            ctx.runOnClient(mc -> BotController.stop());
            LOGGER.info("Not-a-meal test passed (food level {})", hungry);
        } finally {
            ctx.runCommand("difficulty peaceful");
        }
    }

    // ================================================================
    // Test 26: /bot ignore add, remove and clear edit the list and save it
    // ================================================================
    /**
     * The command path end to end. A namespaced id has to get through the
     * parser whole — a string argument stops at the colon, which is how
     * {@code minecraft:cobblestone} once came back as an incomplete command
     * — a bare name has to land as the same entry, a name that is no item
     * has to be refused, and every change has to reach bot.json, where the
     * list is read from at the next start. The player's own list is set
     * aside first and put back at the end, file included.
     */
    @MinecraftTest(name = "Bot ignore commands edit the list", timeoutTicks = 200, order = -173)
    public void ignoreCommandsEditTheList(TestContext ctx) {
        final String cobblestone = "minecraft:cobblestone";
        final String stick = "minecraft:stick";
        final List<String> before = ctx.computeOnClient(
                mc -> new ArrayList<>(BotController.CONFIG.ignoredItems));
        try {
            ctx.runOnClient(mc -> BotController.CONFIG.ignoredItems.clear());

            ctx.runCommand("bot ignore add minecraft:cobblestone");
            assertIgnored(ctx, List.of(cobblestone), "after adding a namespaced id");
            if (!BotConfig.load().ignoredItems.contains(cobblestone)) {
                throw new AssertionError("The added item did not reach bot.json");
            }
            ctx.runCommand("bot ignore add cobblestone");
            assertIgnored(ctx, List.of(cobblestone), "after adding the bare name of an entry");
            ctx.runCommand("bot ignore add minecraft:not_an_item");
            assertIgnored(ctx, List.of(cobblestone), "after a name that is no item");
            ctx.runCommand("bot ignore add stick");
            assertIgnored(ctx, List.of(cobblestone, stick), "after adding a bare name");
            ctx.runCommand("bot ignore remove minecraft:cobblestone");
            assertIgnored(ctx, List.of(stick), "after removing an entry");
            if (BotConfig.load().ignoredItems.contains(cobblestone)) {
                throw new AssertionError("The removed item is still in bot.json");
            }
            ctx.runCommand("bot ignore clear");
            assertIgnored(ctx, List.of(), "after clearing");
            if (!BotConfig.load().ignoredItems.isEmpty()) {
                throw new AssertionError("bot.json still lists items after the clear");
            }
            LOGGER.info("Ignore command test passed");
        } finally {
            ctx.runOnClient(mc -> {
                BotController.CONFIG.ignoredItems.clear();
                BotController.CONFIG.ignoredItems.addAll(before);
                BotController.CONFIG.save();
            });
        }
    }

    // ================================================================
    // Test 25: /bot storage writes the chest into servers.json
    // ================================================================

    /**
     * The commands that decide where a restock goes, end to end against the real
     * store and the real file.
     *
     * <p>Pointing at the chest is the part worth testing rather than asserted
     * away: {@code add} and {@code remove} read a live raycast, so the test has
     * to aim the player the way a person does and a command that stopped reading
     * the crosshair would come back "Not looking at a block". The scan is here
     * because it is the one path that reads the world in bulk, and the reload is
     * here because the list is only worth anything if it survives the session
     * that wrote it.
     *
     * <p>The player's own storages for this world are set aside and put back,
     * file included — these tests run against whatever world is lying about.
     */
    @MinecraftTest(name = "Bot storage commands edit servers.json",
            timeoutTicks = 400, order = -172)
    public void storageCommandsEditTheStore(TestContext ctx) {
        final BlockPos origin = new BlockPos(1400, 30, 1400);
        final BlockPos chest = origin.offset(2, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                + " chest");
        ctx.waitFor(mc -> mc.level.getBlockState(chest).is(Blocks.CHEST));

        final List<StorageSite> before = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().storages));
        try {
            ctx.runOnClient(mc -> ServerSettingsStore.current().storages.clear());
            lookAtBlock(ctx, origin, chest);

            ctx.runCommand("bot storage add");
            assertStorages(ctx, List.of(chest), "after adding the chest in the crosshair");
            ctx.runCommand("bot storage add");
            assertStorages(ctx, List.of(chest), "after adding the same chest twice");

            ctx.runCommand("bot storage remove");
            assertStorages(ctx, List.of(), "after removing it again");

            ctx.runCommand("bot storage scan 4");
            assertStorages(ctx, List.of(chest), "after a scan of the area");

            // The reload is the assertion: it throws the in-memory map away and
            // reads the file, so a list that survives it is a list that was
            // actually written.
            ctx.runOnClient(mc -> ServerSettingsStore.load());
            assertStorages(ctx, List.of(chest), "after reloading servers.json from disk");

            ctx.runCommand("bot storage clear");
            assertStorages(ctx, List.of(), "after clearing");
            ctx.runOnClient(mc -> ServerSettingsStore.load());
            assertStorages(ctx, List.of(), "after reloading a cleared list");
            LOGGER.info("Storage command test passed");
        } finally {
            ctx.runOnClient(mc -> {
                ServerSettingsStore.current().storages.clear();
                ServerSettingsStore.current().storages.addAll(before);
                ServerSettingsStore.save();
            });
        }
    }

    // ================================================================
    // Test 26: a shortfall sends the bot to the chest and back
    // ================================================================

    /**
     * The whole restock, from the tick the manifest is short to the tick the
     * interrupted behavior is running again.
     *
     * <p>The probe asks for one diamond pickaxe and is given none, so the
     * shortfall is there on its first tick; the pickaxe is in the chest and a
     * stack of cobblestone is in the bot's pockets, which the manifest does not
     * mention and which is therefore loot. That covers both halves of
     * {@link net.stracciatella.bot.behavior.ContainerTransfer} in one trip — one
     * stack out, one stack in — and it covers
     * {@link net.stracciatella.bot.task.OpenContainerTask}, because neither half
     * happens without the screen.
     *
     * <p><b>{@code starts == 2} is the assertion that matters.</b> Suspend is
     * {@code abort()} and resume is {@code start()}, so the probe counting its own
     * starts is the only direct evidence that the two-slot stack put it back; and
     * {@code BehaviorRunner} only resumes a behavior whose shortfall is gone, so
     * a second start also proves the withdrawal worked. A restock that failed
     * anywhere leaves the count at one.
     */
    @MinecraftTest(name = "Bot restocks and resumes what it interrupted",
            timeoutTicks = 1200, order = -171)
    public void restockInterruptsAndResumes(TestContext ctx) {
        final BlockPos origin = new BlockPos(1440, 30, 1440);
        final BlockPos chest = origin.offset(6, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                + " chest");
        ctx.waitFor(mc -> mc.level.getBlockState(chest).is(Blocks.CHEST));
        ctx.runCommand("item replace block " + chest.getX() + " " + chest.getY() + " "
                + chest.getZ() + " container.0 with minecraft:diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());
        ctx.runCommand("give @s cobblestone 64");
        ctx.waitFor(mc -> countItems(mc.player.getInventory(), Items.COBBLESTONE) >= 64);
        generateMesh(ctx, origin);

        final List<StorageSite> before = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().storages));
        final RestockProbeBehavior probe = new RestockProbeBehavior();
        try {
            ctx.runOnClient(mc -> {
                ServerSettings settings = ServerSettingsStore.current();
                settings.storages.clear();
                settings.exitStrategy = ServerSettings.ExitStrategy.STAIRCASE;
                settings.storages.add(
                        StorageSite.of(ServerSettingsStore.dimensionOf(mc), chest));
                BehaviorRunner.register(probe);
                BehaviorRunner.start(RestockProbeBehavior.ID);
            });

            ctx.waitFor(mc -> !BehaviorRunner.isActive());
            ctx.runOnClient(mc -> BotController.stop());

            if (probe.starts != 2) {
                throw new AssertionError("The probe was started " + probe.starts
                        + " times, expected 2 (start, then resume after the restock)");
            }
            if (countItem(ctx, Items.DIAMOND_PICKAXE) < 1) {
                throw new AssertionError("The bot came back from the chest without the pickaxe"
                        + " its manifest asked for");
            }
            if (countItem(ctx, Items.COBBLESTONE) > 0) {
                throw new AssertionError("The bot kept " + countItem(ctx, Items.COBBLESTONE)
                        + " cobblestone — loot the manifest does not mention belongs in the chest");
            }
            int inChest = countInContainer(ctx, chest, Items.COBBLESTONE);
            if (inChest < 64) {
                throw new AssertionError("The chest holds " + inChest
                        + " cobblestone, expected the whole deposited stack of 64");
            }
            double home = ctx.computeOnClient(mc -> horizDistTo(mc, origin.getX() + 0.5,
                    origin.getZ() + 0.5));
            if (home > 2.5) {
                throw new AssertionError("The bot resumed " + String.format("%.2f", home)
                        + " blocks from the work site; the run has to carry on where it stopped");
            }
            LOGGER.info("Restock test passed: probe started {} times, back within {} blocks",
                    probe.starts, String.format("%.2f", home));
        } finally {
            ctx.runOnClient(mc -> {
                BehaviorRunner.stop();
                BotController.stop();
                ServerSettingsStore.current().storages.clear();
                ServerSettingsStore.current().storages.addAll(before);
                ServerSettingsStore.save();
            });
        }
    }

    // ================================================================
    // Test 27: a shortfall nobody can serve is not a reason to stop
    // ================================================================

    /**
     * The same probe with the same shortfall and no chest configured anywhere.
     *
     * <p>This is what makes a restock safe to have on by default. The manifest is
     * short from the first tick, and if that alone ended the run, the first
     * {@code /miner chunk start} on a server nobody has configured would fail
     * instead of mining — a bot handed a pickaxe is short of everything else it
     * asks for. So the runner asks whether a trip is possible before it suspends
     * anybody, says so once, and lets the behavior carry on and end on its own
     * terms.
     */
    @MinecraftTest(name = "Bot carries on when a restock has nowhere to go",
            timeoutTicks = 400, order = -170)
    public void restockWithoutStorageDoesNotStopTheRun(TestContext ctx) {
        final BlockPos origin = new BlockPos(1480, 30, 1480);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        switchToSurvivalAt(ctx, origin, origin.getY());

        final List<StorageSite> before = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().storages));
        final RestockProbeBehavior probe = new RestockProbeBehavior();
        try {
            ctx.runOnClient(mc -> {
                ServerSettingsStore.current().storages.clear();
                BehaviorRunner.register(probe);
                BehaviorRunner.start(RestockProbeBehavior.ID);
            });

            // Long enough for a restock to have been attempted and failed: the
            // trip's own first failure (no storage) lands on its first tick.
            ctx.waitTicks(60);
            boolean running = ctx.computeOnClient(mc -> BehaviorRunner.isActive());
            String active = ctx.computeOnClient(mc -> BehaviorRunner.activeId());
            if (!running || !RestockProbeBehavior.ID.equals(active)) {
                throw new AssertionError("The run ended over a restock that could not happen:"
                        + " active behavior is " + active);
            }
            if (probe.starts != 1) {
                throw new AssertionError("The probe was started " + probe.starts
                        + " times; with no chest to go to nothing should have interrupted it");
            }
            LOGGER.info("Unserved-restock test passed: the probe is still running");
        } finally {
            ctx.runOnClient(mc -> {
                BehaviorRunner.stop();
                BotController.stop();
                ServerSettingsStore.current().storages.clear();
                ServerSettingsStore.current().storages.addAll(before);
                ServerSettingsStore.save();
            });
        }
    }

    // ================================================================
    // Test 29: a full barrel is not the end of the trip
    // ================================================================

    /**
     * A camp is a row of barrels, and the nearest one is full.
     *
     * <p>Reported from a real run: thirty-three barrels on record, the bot opened
     * the nearest, found no room, logged {@code Transfer stalled after 0 in, 0
     * out} and walked home with everything it had arrived with — and the run then
     * stopped on the same full inventory that had sent it. One container is
     * therefore never the trip: a stall means <em>this</em> one cannot help, and
     * the answer is the next one.
     *
     * <p>Barrels rather than chests on purpose. It is what the report came from,
     * and it pins the block id list doing its job — {@code storageBlocks} has to
     * accept a barrel both when the site is chosen and when {@code tickOpen}
     * checks that the block is still a container.
     */
    @MinecraftTest(name = "Bot moves on when the nearest storage is full",
            timeoutTicks = 1200, order = -168)
    public void restockWalksPastAFullStorage(TestContext ctx) {
        final BlockPos origin = new BlockPos(1500, 30, 1500);
        final BlockPos full = origin.offset(2, 0, 0);
        final BlockPos spare = origin.offset(6, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + full.getX() + " " + full.getY() + " " + full.getZ()
                + " barrel");
        ctx.runCommand("setblock " + spare.getX() + " " + spare.getY() + " " + spare.getZ()
                + " barrel");
        ctx.waitFor(mc -> mc.level.getBlockState(full).is(Blocks.BARREL)
                && mc.level.getBlockState(spare).is(Blocks.BARREL));
        // Every one of the twenty-seven slots, because a shift-click only has to
        // find one gap. Dirt, which no manifest here mentions and no stack of
        // cobblestone can merge into.
        for (int slot = 0; slot < 27; slot++) {
            ctx.runCommand("item replace block " + full.getX() + " " + full.getY() + " "
                    + full.getZ() + " container." + slot + " with minecraft:dirt 64");
        }
        ctx.runCommand("item replace block " + spare.getX() + " " + spare.getY() + " "
                + spare.getZ() + " container.0 with minecraft:diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());
        ctx.runCommand("give @s cobblestone 64");
        ctx.waitFor(mc -> countItems(mc.player.getInventory(), Items.COBBLESTONE) >= 64);
        generateMesh(ctx, origin);

        final List<StorageSite> before = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().storages));
        final RestockProbeBehavior probe = new RestockProbeBehavior();
        try {
            ctx.runOnClient(mc -> {
                ServerSettings settings = ServerSettingsStore.current();
                settings.storages.clear();
                settings.exitStrategy = ServerSettings.ExitStrategy.STAIRCASE;
                String dimension = ServerSettingsStore.dimensionOf(mc);
                // Added nearest last, so the ordering under test is the one the
                // behavior computes and not the one the list happens to carry.
                settings.storages.add(StorageSite.of(dimension, spare));
                settings.storages.add(StorageSite.of(dimension, full));
                BehaviorRunner.register(probe);
                BehaviorRunner.start(RestockProbeBehavior.ID);
            });

            ctx.waitFor(mc -> !BehaviorRunner.isActive());
            ctx.runOnClient(mc -> BotController.stop());

            if (probe.starts != 2) {
                throw new AssertionError("The probe was started " + probe.starts
                        + " times, expected 2 (start, then resume after the restock)");
            }
            if (countItem(ctx, Items.DIAMOND_PICKAXE) < 1) {
                throw new AssertionError("The bot came home without the pickaxe; it lay in the"
                        + " second barrel, which is the one the trip never reached");
            }
            int leftBehind = countItem(ctx, Items.COBBLESTONE);
            if (leftBehind > 0) {
                throw new AssertionError("The bot kept " + leftBehind + " cobblestone — a full"
                        + " barrel is a reason to walk to the next one, not to take the loot"
                        + " home");
            }
            int inFull = countInContainer(ctx, full, Items.COBBLESTONE);
            if (inFull > 0) {
                throw new AssertionError("The full barrel gained " + inFull + " cobblestone,"
                        + " so the fixture was not full and nothing was tested");
            }
            int inSpare = countInContainer(ctx, spare, Items.COBBLESTONE);
            if (inSpare < 64) {
                throw new AssertionError("The second barrel holds " + inSpare + " cobblestone,"
                        + " expected the whole stack of 64");
            }
            LOGGER.info("Full-storage test passed: 64 cobblestone went into the second barrel");
        } finally {
            ctx.runOnClient(mc -> {
                BehaviorRunner.stop();
                BotController.stop();
                ServerSettingsStore.current().storages.clear();
                ServerSettingsStore.current().storages.addAll(before);
                ServerSettingsStore.save();
            });
        }
    }

    /**
     * How many of {@code item} the container at {@code pos} holds, asked of the
     * server.
     *
     * <p>Not of {@code mc.level}: a client-side container block entity never holds
     * its contents — {@code getUpdateTag} sends none — so the client's copy reads
     * empty however the deposit went. Submitted to the server thread and joined on
     * the test thread, so the read is both authoritative and not a torn
     * off-thread peek. Returns -1 when there is no container there at all, which
     * no assertion should ever accept quietly.
     */
    private int countInContainer(TestContext ctx, BlockPos pos, net.minecraft.world.item.Item item) {
        final MinecraftServer server = ctx.computeOnClient(mc -> mc.getSingleplayerServer());
        return server.submit(() -> {
            if (!(server.overworld().getBlockEntity(pos)
                    instanceof net.minecraft.world.Container box)) {
                return -1;
            }
            int found = 0;
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                if (box.getItem(slot).is(item)) {
                    found += box.getItem(slot).getCount();
                }
            }
            return found;
        }).join();
    }

    // ================================================================
    // Test 28: /bot server exit command writes the command it was given
    // ================================================================

    /**
     * The exit command end to end. Three things can go wrong and none of them
     * is visible until a restock actually tries to leave a pit: the command is
     * several tokens and a plain string argument would keep only the first, a
     * player types it with the leading slash that {@code sendCommand} must not
     * see, and a second one has to <em>replace</em> the first rather than queue
     * behind it. The reload is the assertion for all three — it throws the
     * in-memory settings away and reads servers.json back.
     */
    @MinecraftTest(name = "Bot exit command reaches servers.json",
            timeoutTicks = 200, order = -169)
    public void exitCommandReachesTheStore(TestContext ctx) {
        final ServerSettings.ExitStrategy beforeStrategy = ctx.computeOnClient(
                mc -> ServerSettingsStore.current().exitStrategy);
        final List<String> before = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().exitCommands));
        try {
            ctx.runOnClient(mc -> {
                ServerSettingsStore.current().exitCommands.clear();
                ServerSettingsStore.current().exitStrategy =
                        ServerSettings.ExitStrategy.STAIRCASE;
            });

            ctx.runCommand("bot server exit command tp 1 1 1");
            assertExitCommands(ctx, List.of("tp 1 1 1"), "after a command of several words");
            assertExitStrategy(ctx, ServerSettings.ExitStrategy.COMMAND,
                    "after naming a command");

            ctx.runOnClient(mc -> ServerSettingsStore.load());
            assertExitCommands(ctx, List.of("tp 1 1 1"), "after reloading servers.json");
            assertExitStrategy(ctx, ServerSettings.ExitStrategy.COMMAND,
                    "after reloading servers.json");

            ctx.runCommand("bot server exit command /t spawn");
            assertExitCommands(ctx, List.of("t spawn"),
                    "after a command typed with its leading slash");

            // Switching away and back must not lose it: the command is a fact
            // about the server and the strategy is the choice to use it.
            ctx.runCommand("bot server exit staircase");
            assertExitStrategy(ctx, ServerSettings.ExitStrategy.STAIRCASE,
                    "after switching back to the staircase");
            assertExitCommands(ctx, List.of("t spawn"), "after switching back to the staircase");
            LOGGER.info("Exit command test passed");
        } finally {
            ctx.runOnClient(mc -> {
                ServerSettingsStore.current().exitCommands.clear();
                ServerSettingsStore.current().exitCommands.addAll(before);
                ServerSettingsStore.current().exitStrategy = beforeStrategy;
                ServerSettingsStore.save();
            });
        }
    }

    // ================================================================
    // Test 29: the exit command goes out at once, and the jump is the arrival
    // ================================================================

    /**
     * The {@code COMMAND} way out of a pit, driven end to end against a real
     * teleport: the command has to go on the wire as soon as the bot has stopped
     * walking, and the position jumping afterwards has to be read as the arrival
     * it is.
     *
     * <p>Both halves failed in a real run and the log shows them one after the
     * other. The command went out twelve seconds after the restock began,
     * because the bot first stood still for {@code teleportStandStillTicks} —
     * a count the server starts when the command ARRIVES, so spending it first
     * bought nothing. Then the teleport landed, was swallowed by a branch that
     * restarted the whole wait on any movement, and the restock finally gave up
     * with "exit commands did not teleport the bot" after a log that shows the
     * server teleporting the bot twice.
     *
     * <p>So the two assertions are a tick budget and the phase after
     * LEAVE_SITE. The camp is 48 blocks off, comfortably past the 32 that
     * separate a teleport from a nudge, and nothing here walks the way back:
     * the round trip is the previous test's job.
     */
    @MinecraftTest(name = "Bot sends its exit command at once and reads the teleport",
            timeoutTicks = 600, order = -168)
    public void exitCommandTeleportsAndIsNoticed(TestContext ctx) {
        final BlockPos origin = new BlockPos(1500, 30, 1500);
        final BlockPos chest = origin.offset(6, 0, 0);
        final BlockPos camp = origin.offset(0, 0, -48);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        buildPlatform(ctx, camp, 3);
        ctx.runCommand("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ()
                + " chest");
        ctx.waitFor(mc -> mc.level.getBlockState(chest).is(Blocks.CHEST));
        switchToSurvivalAt(ctx, origin, origin.getY());

        final ServerSettings.ExitStrategy beforeStrategy = ctx.computeOnClient(
                mc -> ServerSettingsStore.current().exitStrategy);
        final List<String> beforeCommands = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().exitCommands));
        final List<StorageSite> beforeStorages = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().storages));
        final RestockProbeBehavior probe = new RestockProbeBehavior();
        try {
            ctx.runOnClient(mc -> {
                ServerSettings settings = ServerSettingsStore.current();
                settings.storages.clear();
                settings.storages.add(
                        StorageSite.of(ServerSettingsStore.dimensionOf(mc), chest));
                settings.exitStrategy = ServerSettings.ExitStrategy.COMMAND;
                settings.exitCommands.clear();
                settings.exitCommands.add("tp " + (camp.getX() + 0.5) + " " + camp.getY()
                        + " " + (camp.getZ() + 0.5));
                BehaviorRunner.register(probe);
                BehaviorRunner.start(RestockProbeBehavior.ID);
            });

            ctx.waitFor(mc -> RestockBehavior.ID.equals(BehaviorRunner.activeId()));
            int ticks = ctx.waitFor(mc -> horizDistTo(mc, camp.getX() + 0.5,
                    camp.getZ() + 0.5) < 2.0, 400);
            if (ticks > SEND_BUDGET_TICKS) {
                throw new AssertionError("The exit command only teleported the bot after "
                        + ticks + " ticks; a command the server itself counts from has to go"
                        + " out as soon as the bot has stopped (budget " + SEND_BUDGET_TICKS
                        + ")");
            }

            // The jump is the arrival, so the phase has to have moved on. Given
            // a breath to do it in: the walk that follows takes far longer than
            // this to get anywhere, so a status line still reading "leaving" is
            // the old bug and nothing else.
            ctx.waitTicks(20);
            String status = ctx.computeOnClient(mc -> BehaviorRunner.statusLine());
            if (!status.contains("walking to")) {
                throw new AssertionError("After the teleport the restock reports \"" + status
                        + "\"; the position jumping is what it was waiting for");
            }
            LOGGER.info("Exit teleport test passed: command landed after {} ticks, {}",
                    ticks, status);
        } finally {
            ctx.runOnClient(mc -> {
                BehaviorRunner.stop();
                BotController.stop();
                ServerSettings settings = ServerSettingsStore.current();
                settings.exitCommands.clear();
                settings.exitCommands.addAll(beforeCommands);
                settings.exitStrategy = beforeStrategy;
                settings.storages.clear();
                settings.storages.addAll(beforeStorages);
                ServerSettingsStore.save();
            });
        }
    }

    // ================================================================
    // Test 30: the approach has to measure the ray the aim will use
    // ================================================================

    /**
     * A target whose <em>centre</em> is in plain sight while the face the bot is
     * going to aim at is not. One block of stone beside the bot does it: the eye
     * looks down the row, the ray to the block's middle passes over that stone,
     * and the ray to the near face — half a block closer, so half a block
     * steeper — clips its top corner.
     *
     * <p>Which is the difference between an approach that works and one that
     * cannot. {@code approachOccluded} walks until the sight line is clear, and
     * the clip that judged "clear" used to go to the centre while LOOKING aimed
     * at the face: POSITIONING reported arrival without a step taken, LOOKING
     * spent its budget on a ray the stone stopped, and the task died. Out of a
     * real chunk-miner run, with a staircase step in the role of the stone:
     * three attempts from a position identical to two decimals, {@code los=true}
     * in the diagnostics of every one of them, and the run dead on
     * {@code cannot break -29, 82, -64}.
     *
     * <p>So the assertion is the step: where the bot stands when it starts
     * breaking has to be somewhere else than where it was told to mine from.
     * Asserting only that the block falls would not catch it — the aim carries a
     * humanized offset of up to 0.3, which now and then lifts the ray over the
     * corner on its own, and a test that passes on that is a test that reports
     * the weather.
     */
    @MinecraftTest(name = "Bot walks until the face it aims at is in sight",
            timeoutTicks = 400, order = -167)
    public void approachesUntilTheAimedFaceIsVisible(TestContext ctx) {
        final BlockPos origin = new BlockPos(1560, 30, 1560);
        final BlockPos occluder = origin.offset(1, 0, 0);
        final BlockPos target = origin.offset(3, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + occluder.getX() + " " + occluder.getY() + " "
                + occluder.getZ() + " stone");
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " "
                + target.getZ() + " stone");
        ctx.waitFor(mc -> !mc.level.getBlockState(occluder).isAir()
                && !mc.level.getBlockState(target).isAir());
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        final double startX = ctx.computeOnClient(mc -> mc.player.getX());
        try {
            ctx.runOnClient(mc -> {
                BotController.setPolicy(BotPolicy.none().withApproachOccluded());
                BotController.enqueueTask(new MineBlockTask(target));
            });

            // Where it stands when the pick goes in, not where it ends up: the
            // collect walk afterwards moves the bot whatever the approach did.
            ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING, 300);
            double movedBy = ctx.computeOnClient(mc -> mc.player.getX()) - startX;
            if (movedBy < MIN_APPROACH_STEP) {
                throw new AssertionError("The bot started breaking from "
                        + String.format(java.util.Locale.US, "%.2f", movedBy)
                        + " blocks off its starting spot; with the face it aims at behind a"
                        + " block, the approach has to walk until that face is in sight");
            }

            waitForBotIdle(ctx);
            boolean mined = ctx.computeOnClient(mc -> mc.level.getBlockState(target).isAir());
            if (!mined) {
                throw new AssertionError("The bot walked but never broke " + target);
            }
            LOGGER.info("Occluded approach test passed: broke it from {} blocks along",
                    String.format(java.util.Locale.US, "%.2f", movedBy));
        } finally {
            ctx.runOnClient(mc -> {
                BotController.stop();
                BotController.setPolicy(null);
            });
        }
    }

    /**
     * The bot must walk up to the block, not stop at the far edge of its reach.
     *
     * <p>Nothing about one block is slow either way — what this pins down is the
     * block <em>after</em> it. Mining from 3.9 blocks puts the next cell of a
     * sweep at 4.9, out of reach, so it costs a whole POSITIONING entry with its
     * reaction beat instead of continuing the seam; mining from 2.0 leaves the
     * next two in reach. Measured over 292 breaks of a real run: 0.48 s to the
     * next break from under 2.5 blocks, 1.70 s from over 3.0.
     *
     * <p>Asserted as a distance rather than as a pace, because the distance is
     * the mechanism and a pace assertion on a single block would be measuring the
     * reaction-delay roll.
     */
    @MinecraftTest(name = "Bot walks up to the block instead of reaching for it",
            timeoutTicks = 400, order = -166)
    public void walksUpToTheBlock(TestContext ctx) {
        final BlockPos origin = new BlockPos(1620, 30, 1620);
        // Out of reach (about 5.1 blocks) but inside reach + 2.5, which is the
        // band POSITIONING walks without a pathfinding ceremony — the same band
        // every block of a sweep falls into.
        final BlockPos target = origin.offset(5, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " "
                + target.getZ() + " stone");
        ctx.waitFor(mc -> !mc.level.getBlockState(target).isAir());
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        try {
            ctx.runOnClient(mc -> {
                BotController.setPolicy(BotPolicy.none());
                BotController.enqueueTask(new MineBlockTask(target));
            });

            // Where it stands when the pick goes in: the collect walk afterwards
            // closes the distance whatever the approach did, so reading it later
            // would report the pickup instead of the approach.
            ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING, 300);
            double distance = ctx.computeOnClient(mc -> distanceToBlock(mc.player, target));
            if (distance > MAX_WORK_DISTANCE) {
                throw new AssertionError("The bot started breaking from "
                        + String.format(java.util.Locale.US, "%.2f", distance)
                        + " blocks away; it has to walk up to the block, because the"
                        + " next one of a sweep is only in reach from close range");
            }

            waitForBotIdle(ctx);
            if (!ctx.computeOnClient(mc -> mc.level.getBlockState(target).isAir())) {
                throw new AssertionError("The bot walked up to " + target + " but never broke it");
            }
            LOGGER.info("Working-distance test passed: broke it from {} blocks",
                    String.format(java.util.Locale.US, "%.2f", distance));
        } finally {
            ctx.runOnClient(mc -> {
                BotController.stop();
                BotController.setPolicy(null);
            });
        }
    }

    /**
     * And it must walk while it mines, not instead of mining.
     *
     * <p>A target already inside reach is handed straight to LOOKING with no walk
     * at all, which is right — but it leaves the bot working from wherever it
     * happened to be. Breaking is the one thing the bot is going to spend ticks
     * on regardless, so the approach belongs inside it: by the time the block
     * falls the body is at the face and the next cell of the row is a seam rather
     * than another POSITIONING entry. Reported from a real run as a bot that does
     * not mine and walk at the same time.
     *
     * <p>Obsidian with a diamond pickaxe for the same reason the opportunistic
     * collection test uses it: ~187 ticks of INTERACTING is long enough to watch
     * the body move, and a fast block would measure the momentum of the last
     * phase instead.
     *
     * <p>Measured horizontally, against {@link #MAX_CONTACT_DISTANCE}. The step
     * has no standing distance of its own: {@code isStepSafe} ends it by refusing
     * to put the feet in the target's cell, so the bot comes to rest against the
     * face the way a body does, and the bar sits where a fixed early stop could
     * not reach however the ticks fell. It used to stop at 2.0 and swing from
     * there, which is what the run looked mechanical for. Falsified by putting that
     * stop back: the wait runs out at 250 ticks, the bot never having got under
     * 1.5 — a 2.0 stop plus the bounded coast cannot.
     */
    @MinecraftTest(name = "Bot closes in while it is breaking",
            timeoutTicks = 300, order = -164)
    public void closesInWhileBreaking(TestContext ctx) {
        final BlockPos origin = new BlockPos(1700, 30, 1700);
        // Three along: inside reach (about 3.2 blocks), so the task goes straight
        // to LOOKING and the break starts from there. That is the geometry the
        // in-break step exists for — nothing else will ever close this gap.
        final BlockPos target = origin.offset(3, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " "
                + target.getZ() + " obsidian");
        ctx.waitFor(mc -> !mc.level.getBlockState(target).isAir());
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        final double[] closest = {Double.MAX_VALUE};
        final double[] atStart = {-1.0};
        try {
            ctx.runOnClient(mc -> {
                // Deliberately without opportunisticCollection: the drop branch
                // would be a second explanation for any movement seen here.
                BotController.setPolicy(BotPolicy.none());
                BotController.enqueueTask(new MineBlockTask(target));
            });

            ctx.waitFor(mc -> {
                if (BotController.getPhase() != BotController.Phase.INTERACTING) {
                    return false;
                }
                double distance = horizDistTo(mc, target.getX() + 0.5, target.getZ() + 0.5);
                if (atStart[0] < 0) {
                    atStart[0] = distance;
                }
                closest[0] = Math.min(closest[0], distance);
                return closest[0] <= MAX_CONTACT_DISTANCE;
            }, 250);

            if (atStart[0] <= MAX_CONTACT_DISTANCE) {
                throw new AssertionError("The bot already started breaking from "
                        + String.format(java.util.Locale.US, "%.2f", atStart[0])
                        + " blocks, so this fixture had no gap to close — the test would"
                        + " pass whatever the bot did during the break");
            }
            LOGGER.info("In-break step test passed: started at {} blocks, closed to {}",
                    String.format(java.util.Locale.US, "%.2f", atStart[0]),
                    String.format(java.util.Locale.US, "%.2f", closest[0]));
        } finally {
            ctx.runOnClient(mc -> {
                BotController.stop();
                BotController.setPolicy(null);
            });
        }
    }

    /**
     * And it must give up walking when walking cannot help.
     *
     * <p>The other half of the working distance, and the half that keeps it from
     * being a regression: a target across a trench is in reach and will never be
     * any nearer, because the floor check refuses the step — correctly, the
     * alternative is a fall. Told only to close to 2.0 the phase would hold that
     * target for the full {@code positionTimeout} and mine it afterwards anyway,
     * so the cost of getting this wrong is paid in ticks on every such block
     * rather than in a failure, which is exactly the kind of bug that hides.
     */
    @MinecraftTest(name = "Bot stops closing in when the way is a trench",
            timeoutTicks = 400, order = -165)
    public void settlesForReachWhenItCannotCloseIn(TestContext ctx) {
        final BlockPos origin = new BlockPos(1660, 30, 1660);
        final BlockPos target = origin.offset(4, 0, 0);
        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        // Two cells of floor gone between the bot and the block. It can stand at
        // x+1 and reach the target from 3.2 blocks, and it can never stand nearer.
        ctx.runCommand("fill " + (origin.getX() + 2) + " " + (origin.getY() - 2) + " "
                + (origin.getZ() - 1) + " " + (origin.getX() + 3) + " " + (origin.getY() - 1)
                + " " + (origin.getZ() + 1) + " air");
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " "
                + target.getZ() + " stone");
        ctx.waitFor(mc -> !mc.level.getBlockState(target).isAir()
                && mc.level.getBlockState(origin.offset(2, -1, 0)).isAir());
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        final int[] positioningTicks = {0};
        try {
            ctx.runOnClient(mc -> {
                BotController.setPolicy(BotPolicy.none());
                BotController.enqueueTask(new MineBlockTask(target));
            });

            ctx.waitFor(mc -> {
                if (BotController.getPhase() == BotController.Phase.POSITIONING) {
                    positioningTicks[0]++;
                }
                return BotController.getPhase() == BotController.Phase.INTERACTING;
            }, 300);
            if (positioningTicks[0] > MAX_BLOCKED_POSITION_TICKS) {
                throw new AssertionError("The bot spent " + positioningTicks[0]
                        + " ticks trying to get closer to a block across a trench;"
                        + " a walk that is not closing the distance has to be given up on");
            }

            waitForBotIdle(ctx);
            if (!ctx.computeOnClient(mc -> mc.level.getBlockState(target).isAir())) {
                throw new AssertionError("The bot never broke " + target
                        + " across the trench");
            }
            LOGGER.info("Blocked-approach test passed: {} ticks in POSITIONING, then it mined"
                    + " from where it stood", positioningTicks[0]);
        } finally {
            ctx.runOnClient(mc -> {
                BotController.stop();
                BotController.setPolicy(null);
            });
        }
    }

    /**
     * Being moved ends the run, whoever was running it and however short the hop.
     *
     * <p>Two claims in one, and both are the ones that were wrong before. The
     * probe asks for {@link BotPolicy#none()}, so a guard that had been hung off
     * a policy flag would sit this out — a teleport is not a preference. And the
     * bot is teleported <b>one block</b>, because the case that matters is a
     * staff member putting the bot where they are standing, and they are
     * standing next to it when they do; anything that waited for a jump would
     * pass a test written with a long throw and still miss the real thing.
     */
    @MinecraftTest(name = "Bot stops when it is teleported", timeoutTicks = 300, order = -164)
    public void teleportStopsTheRun(TestContext ctx) {
        final BlockPos origin = new BlockPos(1740, 30, 1740);
        final BlockPos standPos = origin.offset(0, 0, 2);
        // Four blocks, so the run still has work left when the teleport lands.
        final List<BlockPos> blocks = List.of(
                origin, origin.offset(1, 0, 0), origin.offset(2, 0, 0), origin.offset(3, 0, 0));

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        for (BlockPos block : blocks) {
            ctx.runCommand("setblock " + block.getX() + " " + block.getY() + " "
                    + block.getZ() + " stone");
        }
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, standPos, origin.getY());

        startProbe(ctx, BotPolicy.none(), blocks);
        // Only meaningful once the bot is actually working.
        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.INTERACTING);

        // One block back, away from the row. Sideways would be along the line
        // the bot walks to reach the next block, and a spot it has just stood on
        // is one the alarm is meant to forgive.
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + origin.getY() + " "
                + (standPos.getZ() + 1.5));
        ctx.waitFor(mc -> !BehaviorRunner.isActive());

        int remaining = ctx.computeOnClient(mc -> {
            int count = 0;
            for (BlockPos block : blocks) {
                if (!mc.level.getBlockState(block).isAir()) {
                    count++;
                }
            }
            return count;
        });
        if (remaining == 0) {
            throw new AssertionError("The run mined every block — the teleport stop never fired");
        }
        if (ctx.computeOnClient(mc -> BotController.isActive())) {
            throw new AssertionError("The behavior ended but the task layer carried on mining");
        }
        ctx.runOnClient(mc -> BotController.stop());
        LOGGER.info("Teleport stop test passed ({} of {} blocks left)", remaining, blocks.size());
    }

    /** Eye to block centre, the distance {@code BotController} positions by. */
    private static double distanceToBlock(net.minecraft.client.player.LocalPlayer player,
                                          BlockPos target) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dy = (target.getY() + 0.5) - player.getEyeY();
        double dz = (target.getZ() + 0.5) - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How far the occluded approach has to carry the bot before it may start
     * breaking. Deliberately small: walking closer lifts the ray over the
     * corner, and measured here that takes 0.46 blocks, not a whole one. The
     * line to beat is zero — the bug stood perfectly still, three attempts at a
     * position identical to two decimals — so anything that cannot be drift is
     * the right threshold, and a larger one would only assert that this
     * particular geometry needs a particular number of blocks.
     */
    private static final double MIN_APPROACH_STEP = 0.2;

    /**
     * How far from the block the bot may still be when it starts breaking it.
     *
     * <p>{@code WORK_DISTANCE} (2.0) is horizontal and this is measured to the
     * block centre, so a block at foot level sits a further 1.1 blocks below the
     * eye: 2.3 is what a correct arrival actually reads, and 2.5 leaves room for
     * the reaction beat and the walk's own momentum. The line to beat is the
     * reach distance — arriving there puts the bot at about 3.9, which is what
     * this test measured before POSITIONING had a working distance of its own, so
     * the bar separates the two by a wide margin despite looking tight against
     * 2.3.
     */
    private static final double MAX_WORK_DISTANCE = 2.5;

    /**
     * How close the in-break step has to bring the body, measured <b>horizontally</b>
     * — the only distance a walk can change, and the one the production step uses.
     *
     * <p>Provably out of reach of the behaviour this replaced, which is what makes
     * the bar worth having: that step stopped pressing at {@code WORK_DISTANCE}
     * (2.0) and the body then coasted, and a coast is bounded — walking speed is
     * about 0.13 blocks a tick against ground friction of 0.6, so it carries at
     * most 0.13 / (1 − 0.6) ≈ 0.33 blocks. An early stop therefore cannot end
     * nearer than about 1.67 however the ticks fall. Letting {@code isStepSafe}
     * decide instead — it refuses the step that would put the feet in the target's
     * own cell — stops the press around 1.5 and the same coast lands near 1.2.
     */
    private static final double MAX_CONTACT_DISTANCE = 1.5;

    /**
     * Ticks POSITIONING may spend on a target it cannot get any closer to.
     *
     * <p>Between {@code CLOSING_STALL_TICKS} (10, plus the walk that got the bot
     * into reach) and {@code positionTimeout} (60), which is what the phase costs
     * when nothing tells it to stop trying. Not scaled by the tick multiplier —
     * this counts client ticks the phase was observed in, not wall time.
     */
    private static final int MAX_BLOCKED_POSITION_TICKS = 35;

    /**
     * Ticks the exit command may take to reach the server and work. It covers
     * {@code RestockBehavior.SETTLE_TICKS} plus the round trip, and is far
     * enough under the 240-tick standstill that used to come first that the
     * difference cannot be read as jitter.
     */
    private static final int SEND_BUDGET_TICKS = 60;

    private void assertExitCommands(TestContext ctx, List<String> expected, String when) {
        List<String> actual = ctx.computeOnClient(
                mc -> new ArrayList<>(ServerSettingsStore.current().exitCommands));
        if (!actual.equals(expected)) {
            throw new AssertionError("Exit commands " + when + " are " + actual
                    + ", expected " + expected);
        }
    }

    private void assertExitStrategy(TestContext ctx, ServerSettings.ExitStrategy expected,
                                    String when) {
        ServerSettings.ExitStrategy actual = ctx.computeOnClient(
                mc -> ServerSettingsStore.current().exitStrategy);
        if (actual != expected) {
            throw new AssertionError("Exit strategy " + when + " is " + actual
                    + ", expected " + expected);
        }
    }

    /**
     * A behavior that wants one diamond pickaxe and does nothing else. It counts
     * its own starts, because that is the only thing that tells a resumed run
     * from a run that was never interrupted — the runner suspends with
     * {@code abort()} and resumes with {@code start()}, and neither leaves any
     * other trace.
     */
    private static final class RestockProbeBehavior implements BotBehavior {

        private static final String ID = "restock_probe";

        private int starts;

        @Override
        public String id() {
            return ID;
        }

        @Override
        public BotPolicy policy() {
            // No guards at all: the point is the manifest, and an inventory-full
            // or damage stop would end the run before the trip could.
            return BotPolicy.none();
        }

        @Override
        public net.stracciatella.bot.behavior.RestockNeeds restockNeeds() {
            return new net.stracciatella.bot.behavior.RestockNeeds.Builder()
                    .need("a pickaxe", stack -> stack.is(Items.DIAMOND_PICKAXE), 1)
                    .build();
        }

        @Override
        public void start(net.minecraft.client.Minecraft client) {
            starts++;
        }

        @Override
        public void abort() {
            BotController.stop();
        }

        @Override
        public String statusLine() {
            return "started " + starts + " times";
        }

        @Override
        public BehaviorStatus tick(net.minecraft.client.Minecraft client) {
            // Finished once it has been put back on its feet. Before that it
            // simply waits, so the shortfall is what drives the whole test.
            return starts >= 2 ? BehaviorStatus.SUCCEEDED : BehaviorStatus.RUNNING;
        }
    }

    /**
     * Aim the player at a block from {@code standPos} and wait until its own
     * raycast agrees. The commands under test read a live raycast, not
     * {@code Minecraft.hitResult}, so the rotation has to be real — and waiting
     * for the raycast rather than for a tick count keeps the test off the
     * rotation-sync timing.
     */
    private void lookAtBlock(TestContext ctx, BlockPos standPos, BlockPos target) {
        double eyeX = standPos.getX() + 0.5;
        double eyeY = standPos.getY() + 1.62;
        double eyeZ = standPos.getZ() + 0.5;
        double dx = target.getX() + 0.5 - eyeX;
        double dy = target.getY() + 0.5 - eyeY;
        double dz = target.getZ() + 0.5 - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        ctx.runCommand("tp @s " + eyeX + " " + standPos.getY() + " " + eyeZ
                + " " + yaw + " " + pitch);
        ctx.waitFor(mc -> {
            var hit = mc.player.raycastHitResult(0, mc.player);
            return hit instanceof net.minecraft.world.phys.BlockHitResult block
                    && block.getBlockPos().equals(target);
        });
    }

    private void assertStorages(TestContext ctx, List<BlockPos> expected, String when) {
        List<BlockPos> actual = ctx.computeOnClient(mc -> {
            List<BlockPos> positions = new ArrayList<>();
            for (StorageSite site : ServerSettingsStore.current().storages) {
                positions.add(site.pos());
            }
            return positions;
        });
        if (!actual.equals(expected)) {
            throw new AssertionError("Storage list " + when + " is " + actual
                    + ", expected " + expected);
        }
    }

    private void assertIgnored(TestContext ctx, List<String> expected, String when) {
        List<String> actual = ctx.computeOnClient(
                mc -> new ArrayList<>(BotController.CONFIG.ignoredItems));
        if (!actual.equals(expected)) {
            throw new AssertionError("Ignore list " + when + " is " + actual
                    + ", expected " + expected);
        }
    }

    /**
     * Run the food bar under {@code eatBelowFoodLevel} with the hunger
     * effect, clear the effect, and return the level the bar was left at.
     * Amplifier 255 is 1.28 exhaustion a tick — a food point every three
     * ticks or so once the saturation is gone — so the bar is down within a
     * few dozen ticks and the clear lands before it is empty.
     * The test world is Peaceful, and Peaceful never takes a food point
     * ({@code FoodData.tick} only drains saturation there), so the bar is
     * run down on Easy; the caller restores Peaceful in a {@code finally}.
     */
    private int runDownFoodBar(TestContext ctx) {
        final int threshold = BotController.CONFIG.eatBelowFoodLevel;
        ctx.runCommand("difficulty easy");
        ctx.runCommand("effect give @s minecraft:hunger 30 255");
        ctx.waitFor(mc -> mc.player.getFoodData().getFoodLevel() < threshold);
        ctx.runCommand("effect clear @s minecraft:hunger");
        ctx.waitFor(mc -> !mc.player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER));
        int level = ctx.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
        LOGGER.info("Food bar run down to {} (threshold {})", level, threshold);
        return level;
    }

    // --- Shared helpers ---

    /** Shortest-way yaw change since the last sample; the sample is kept. */
    private static double yawStep(float[] last, float yaw) {
        double delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - last[0]));
        last[0] = yaw;
        return delta;
    }

    /**
     * Register and start a behavior that mines {@code blocks} under the given
     * policy. The policy layer is only reachable through a behavior — that is
     * the whole point of it — so the guards need one to be tested at all.
     */
    private void startProbe(TestContext ctx, BotPolicy policy, List<BlockPos> blocks) {
        ctx.runOnClient(mc -> {
            BehaviorRunner.register(new PolicyProbeBehavior(policy, blocks));
            BehaviorRunner.start(PolicyProbeBehavior.ID);
        });
    }

    /** Horizontal distance from the player to a world point. */
    private static double horizDistTo(net.minecraft.client.Minecraft mc, double x, double z) {
        double dx = mc.player.getX() - x;
        double dz = mc.player.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Item entities more than 4 blocks above {@code origin} — the decoys. */
    private int countDecoysAbove(TestContext ctx, BlockPos origin) {
        return ctx.computeOnClient(mc -> mc.level.getEntities(
                net.minecraft.world.entity.EntityType.ITEM,
                new net.minecraft.world.phys.AABB(origin).inflate(12.0),
                item -> item.getY() > origin.getY() + 4).size());
    }

    /**
     * Item entities lying where the bait was summoned. Tight enough that the
     * mined block's own drop, seven blocks the other way, can never be
     * mistaken for it.
     */
    private int countBaitAt(TestContext ctx, BlockPos at) {
        return ctx.computeOnClient(mc -> mc.level.getEntities(
                net.minecraft.world.entity.EntityType.ITEM,
                new net.minecraft.world.phys.AABB(at).inflate(1.5),
                item -> true).size());
    }

    /**
     * The smallest thing that is still a behavior: enqueue the blocks once,
     * then report RUNNING until the controller has worked through them. Exists
     * so {@code BehaviorRunner}'s policy handling can be tested without
     * depending on a real strategy from another module.
     */
    private static final class PolicyProbeBehavior implements BotBehavior {

        private static final String ID = "policy_probe";

        private final BotPolicy policy;
        private final List<BlockPos> blocks;
        private boolean planned;

        private PolicyProbeBehavior(BotPolicy policy, List<BlockPos> blocks) {
            this.policy = policy;
            this.blocks = blocks;
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public BotPolicy policy() {
            return policy;
        }

        @Override
        public void start(net.minecraft.client.Minecraft client) {
            planned = false;
        }

        @Override
        public void abort() {
            BotController.stop();
        }

        @Override
        public String statusLine() {
            return planned ? "mining " + blocks.size() + " blocks" : "starting";
        }

        @Override
        public BehaviorStatus tick(net.minecraft.client.Minecraft client) {
            if (!planned) {
                for (BlockPos block : blocks) {
                    BotController.enqueueTask(new MineBlockTask(block));
                }
                planned = true;
                return BehaviorStatus.RUNNING;
            }
            if (BotController.isActive() || !BotController.getTaskQueue().isEmpty()) {
                return BehaviorStatus.RUNNING;
            }
            return BehaviorStatus.SUCCEEDED;
        }
    }

    private void setupTest(TestContext ctx, BlockPos origin) {
        ctx.runOnClient(mc -> BotController.stop());
        ctx.runCommand("clear @s");
        ctx.runCommand("kill @e[type=item]");
        ctx.runCommand("gamemode creative");
        teleportAndWaitForChunks(ctx, origin);
    }

    private void switchToSurvivalAt(TestContext ctx, BlockPos standPos, int groundY) {
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + (groundY) + " " + (standPos.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround()
                && Math.abs(mc.player.getX() - (standPos.getX() + 0.5)) < 1.0
                && Math.abs(mc.player.getZ() - (standPos.getZ() + 0.5)) < 1.0);
        ctx.runCommand("gamemode survival");
        // Wait for the server→client ability sync to land. Without this, the
        // bot can start attacking while the player is still in creative
        // server-side — which instant-breaks the block with no drop.
        ctx.waitFor(mc -> !mc.player.getAbilities().instabuild);
    }

    private void waitForBotIdle(TestContext ctx) {
        ctx.waitFor(mc -> BotController.getPhase() == BotController.Phase.IDLE);
        ctx.runOnClient(mc -> BotController.stop());
    }

    /**
     * Wait until the player has at least {@code minCount} of the given item.
     * Uses a short explicit timeout so a missing-drop failure is visible
     * within ~3 seconds instead of burning the full 14s test budget.
     */
    private void waitForItem(TestContext ctx, Item item, int minCount, String itemName) {
        // ~6s wall at 10x, ~3s at 20x (120 × tickMultiplier) — must be larger
        // than CONFIG.collectWaitMax so a legitimate late-spawning drop (heavy
        // server load can delay item-entity sync by several seconds) has time
        // to land before the test gives up.
        int timeout = 120 * net.stracciatella.testing.runner.TestRunner.getTickMultiplier();
        ctx.waitFor(mc -> countItems(mc.player.getInventory(), item) >= minCount, timeout);
        LOGGER.info("Collected {} {}", countItem(ctx, item), itemName);
    }

    private int countItem(TestContext ctx, Item item) {
        return ctx.computeOnClient(mc -> countItems(mc.player.getInventory(), item));
    }

    private static int countItems(net.minecraft.world.entity.player.Inventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void teleportAndWaitForChunks(TestContext ctx, BlockPos origin) {
        // Teleport to load the chunk
        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + (origin.getY()) + " " + (origin.getZ() + 0.5));
        ctx.waitFor(mc -> {
            if (mc.player == null || mc.level == null) {
                return false;
            }
            return mc.level.hasChunkAt(origin)
                    && Math.abs(mc.player.position().x - (origin.getX() + 0.5)) < 20
                    && Math.abs(mc.player.position().z - (origin.getZ() + 0.5)) < 20;
        });
        // Place safety block and re-teleport so the player lands on it
        // instead of floating/falling in air
        ctx.runCommand("setblock " + origin.getX() + " " + (origin.getY() - 1) + " " + origin.getZ() + " stone");
        ctx.runCommand("tp @s " + (origin.getX() + 0.5) + " " + (origin.getY()) + " " + (origin.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());
    }

    private void buildPlatform(TestContext ctx, BlockPos center, int radius) {
        // Build 2-block-thick platform so items don't fall through
        ctx.runCommand("fill "
                + (center.getX() - radius) + " " + (center.getY() - 2) + " " + (center.getZ() - radius) + " "
                + (center.getX() + radius) + " " + (center.getY() - 1) + " " + (center.getZ() + radius) + " stone");
        // Clear air above
        ctx.runCommand("fill "
                + (center.getX() - radius) + " " + center.getY() + " " + (center.getZ() - radius) + " "
                + (center.getX() + radius) + " " + (center.getY() + radius) + " " + (center.getZ() + radius) + " air");
    }

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

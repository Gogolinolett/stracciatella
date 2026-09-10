package net.stracciatella.bot.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.behavior.BehaviorStatus;
import net.stracciatella.bot.behavior.BotBehavior;
import net.stracciatella.bot.interaction.InventoryHelper;
import net.stracciatella.bot.scan.TreeDetector;
import net.stracciatella.bot.scan.TreeInfo;
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
     */
    @MinecraftTest(name = "Bot collects from a dip and climbs back", timeoutTicks = 400, order = -179)
    public void collectsFromADipAndClimbsBack(TestContext ctx) {
        final BlockPos origin = new BlockPos(2000, 30, 1000);
        final BlockPos first = origin.offset(0, 0, -2);
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
    // Test 22: an ignored drop is left where it fell, without a wait
    // ================================================================
    /**
     * A drop on the ignore list is one the bot neither walks to nor waits
     * for. The block is mined from three blocks out — in reach, and far
     * enough that its cobblestone lands outside the vanilla pickup box, so
     * only a walk could collect it. With cobblestone ignored the run has to
     * end with the drop still on the ground, the bot still where it stood,
     * and COLLECTING over inside the absence window rather than at
     * {@code collectWaitMax}, which is what an uncollected drop in sight used
     * to cost. The list is edited in place and restored, never saved: the
     * test must not leave a real config behind with cobblestone on it.
     */
    @MinecraftTest(name = "Bot ignores a listed drop", timeoutTicks = 400, order = -177)
    public void ignoresListedDrop(TestContext ctx) {
        final BlockPos origin = new BlockPos(2100, 30, 1000);
        final BlockPos target = origin.offset(3, 0, 0);
        final String cobblestone = "minecraft:cobblestone";

        setupTest(ctx, origin);
        buildPlatform(ctx, origin, CLEAR_RADIUS);
        ctx.runCommand("setblock " + target.getX() + " " + target.getY() + " " + target.getZ() + " stone");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, origin, origin.getY());

        ctx.runOnClient(mc -> BotController.CONFIG.ignoredItems.add(cobblestone));
        try {
            long startTick = ctx.computeOnClient(mc -> mc.level.getGameTime());
            ctx.runOnClient(mc -> BotController.enqueueTask(new MineBlockTask(target)));
            waitForBotIdle(ctx);
            final long elapsed = ctx.computeOnClient(mc -> mc.level.getGameTime()) - startTick;

            if (ctx.computeOnClient(mc -> !mc.level.getBlockState(target).isAir())) {
                throw new AssertionError("Block was never mined");
            }
            if (countItem(ctx, Items.COBBLESTONE) > 0) {
                throw new AssertionError("The ignored cobblestone was collected");
            }
            int onGround = ctx.computeOnClient(mc -> mc.level.getEntities(
                    net.minecraft.world.entity.EntityType.ITEM,
                    new net.minecraft.world.phys.AABB(target).inflate(4.0), item -> true).size());
            if (onGround != 1) {
                throw new AssertionError("Expected the cobblestone lying at the block, found "
                        + onGround + " drops there");
            }
            // The bot stood its ground: a collect walk ends inside the pickup
            // box, one block from the drop. Only the box counts as having
            // walked there: until the spawn of the drop reaches the client
            // the phase walks at the mined block whatever is going to lie
            // there, and under load that sync has taken long enough for a
            // whole block of it (1.97 blocks left of 3, once, in a full run).
            double toDrop = ctx.computeOnClient(mc -> {
                var drop = mc.level.getEntities(net.minecraft.world.entity.EntityType.ITEM,
                        new net.minecraft.world.phys.AABB(target).inflate(4.0), item -> true).get(0);
                return horizDistTo(mc, drop.getX(), drop.getZ());
            });
            if (toDrop < 1.5) {
                throw new AssertionError(String.format(
                        "Bot walked to the ignored drop (%.2f blocks from it)", toDrop));
            }
            if (elapsed >= BotController.CONFIG.collectWaitMax) {
                throw new AssertionError("COLLECTING waited for the ignored drop: " + elapsed
                        + " ticks, collectWaitMax is " + BotController.CONFIG.collectWaitMax);
            }
            LOGGER.info("Ignored drop test passed ({} ticks, {} blocks from the drop)",
                    elapsed, String.format("%.2f", toDrop));
        } finally {
            ctx.runOnClient(mc -> BotController.CONFIG.ignoredItems.remove(cobblestone));
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

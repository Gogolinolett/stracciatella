package net.stracciatella.bot.test;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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

    // --- Shared helpers ---

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

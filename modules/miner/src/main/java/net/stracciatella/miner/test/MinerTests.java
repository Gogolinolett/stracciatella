package net.stracciatella.miner.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.miner.MinerSetup;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestSuite(name = "Miner Tests")
public class MinerTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("MinerTests");

    // ================================================================
    // Test 1: Already at depth — tunnel forward, expose + mine one ore
    // ================================================================
    @MinecraftTest(name = "Miner tunnels at depth", timeoutTicks = 2000, order = -100)
    public void tunnelsAtDepth(TestContext ctx) {
        final int x = 2200;
        final int z = 1000;
        final BlockPos start = new BlockPos(x, -61, z);

        setupTest(ctx, new BlockPos(x, -50, z));
        buildDeepslateField(ctx, x, z, -58);
        // Pocket for the player at tunnel height (feet -61, head -60).
        ctx.runCommand("setblock " + x + " -61 " + z + " air");
        ctx.runCommand("setblock " + x + " -60 " + z + " air");
        // One diamond ore directly in the tunnel path.
        ctx.runCommand("setblock " + x + " -61 " + (z + 3) + " deepslate_diamond_ore");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, start);

        ctx.runOnClient(mc -> {
            MinerSetup.diamondMiner().setRequestedTunnelLength(5);
            BehaviorRunner.start(MinerSetup.diamondMiner().id());
        });

        ctx.waitFor(mc -> !BehaviorRunner.isActive());
        waitForItem(ctx, Items.DIAMOND, 1, "diamonds");
        boolean tunnelOpen = ctx.computeOnClient(mc ->
                mc.level.getBlockState(new BlockPos(x, -61, z + 5)).isAir()
                        && mc.level.getBlockState(new BlockPos(x, -60, z + 5)).isAir());
        if (!tunnelOpen) {
            throw new AssertionError("Tunnel was not dug to its full length");
        }
        LOGGER.info("Miner tunnel-at-depth test passed");
    }

    // ================================================================
    // Test 2: Full run — locate, descend a staircase, tunnel, collect
    // both a path ore and a side ore behind one wall block
    // ================================================================
    @MinecraftTest(name = "Miner descends and mines diamonds", timeoutTicks = 3500, order = -99)
    public void descendsAndMines(TestContext ctx) {
        final int x = 2300;
        final int z = 1000;
        // Feet on -58 (standing on the deepslate top layer at -59):
        // three staircase steps down to tunnel height -61.
        final BlockPos start = new BlockPos(x, -58, z);

        setupTest(ctx, new BlockPos(x, -50, z));
        buildDeepslateField(ctx, x, z, -59);
        // One ore in the tunnel path, one in the side wall at foot level —
        // the second sits outside the tunnel slices and is only mined via
        // the ore scan (and possible line-of-sight exposure).
        ctx.runCommand("setblock " + x + " -61 " + (z + 5) + " deepslate_diamond_ore");
        ctx.runCommand("setblock " + (x + 1) + " -61 " + (z + 6) + " deepslate_diamond_ore");
        ctx.runCommand("give @s diamond_pickaxe");
        switchToSurvivalAt(ctx, start);

        ctx.runOnClient(mc -> {
            MinerSetup.diamondMiner().setRequestedTunnelLength(6);
            BehaviorRunner.start(MinerSetup.diamondMiner().id());
        });

        ctx.waitFor(mc -> !BehaviorRunner.isActive());
        waitForItem(ctx, Items.DIAMOND, 2, "diamonds");
        LOGGER.info("Miner descend-and-mine test passed");
    }

    // --- Shared helpers (mirrors the BotTests setup patterns) ---

    private void setupTest(TestContext ctx, BlockPos chunkAnchor) {
        ctx.runOnClient(mc -> {
            BehaviorRunner.stop();
            BotController.stop();
        });
        ctx.runCommand("clear @s");
        ctx.runCommand("kill @e[type=item]");
        ctx.runCommand("gamemode creative");
        teleportAndWaitForChunks(ctx, chunkAnchor);
    }

    /**
     * Bedrock floor at -64 with solid deepslate from -63 up to {@code topY},
     * air above — a deterministic deep-mining sandbox around (x, z).
     */
    private void buildDeepslateField(TestContext ctx, int x, int z, int topY) {
        int r = 15;
        ctx.runCommand("fill " + (x - r) + " -64 " + (z - r) + " " + (x + r) + " -64 " + (z + r) + " bedrock");
        ctx.runCommand("fill " + (x - r) + " -63 " + (z - r) + " " + (x + r) + " " + topY + " " + (z + r) + " deepslate");
        ctx.runCommand("fill " + (x - r) + " " + (topY + 1) + " " + (z - r) + " " + (x + r) + " -45 " + (z + r) + " air");
    }

    private void switchToSurvivalAt(TestContext ctx, BlockPos standPos) {
        // Face south (+Z, yaw 0) so the tunnel direction is deterministic.
        ctx.runCommand("tp @s " + (standPos.getX() + 0.5) + " " + standPos.getY() + " "
                + (standPos.getZ() + 0.5) + " 0 0");
        ctx.waitFor(mc -> mc.player.onGround()
                && Math.abs(mc.player.getX() - (standPos.getX() + 0.5)) < 1.0
                && Math.abs(mc.player.getZ() - (standPos.getZ() + 0.5)) < 1.0);
        ctx.runCommand("gamemode survival");
        // Wait for the server→client ability sync (see BotTests) — without it
        // the first breaks happen in server-side creative and drop nothing.
        ctx.waitFor(mc -> !mc.player.getAbilities().instabuild);
    }

    private void teleportAndWaitForChunks(TestContext ctx, BlockPos anchor) {
        ctx.runCommand("tp @s " + (anchor.getX() + 0.5) + " " + anchor.getY() + " " + (anchor.getZ() + 0.5));
        ctx.waitFor(mc -> {
            if (mc.player == null || mc.level == null) {
                return false;
            }
            return mc.level.hasChunkAt(anchor)
                    && Math.abs(mc.player.position().x - (anchor.getX() + 0.5)) < 20
                    && Math.abs(mc.player.position().z - (anchor.getZ() + 0.5)) < 20;
        });
        // Park the player on a safety block so setup commands run from a
        // stable position instead of mid-fall.
        ctx.runCommand("setblock " + anchor.getX() + " " + (anchor.getY() - 1) + " " + anchor.getZ() + " stone");
        ctx.runCommand("tp @s " + (anchor.getX() + 0.5) + " " + anchor.getY() + " " + (anchor.getZ() + 0.5));
        ctx.waitFor(mc -> mc.player.onGround());
    }

    private void waitForItem(TestContext ctx, Item item, int minCount, String itemName) {
        int timeout = 200 * net.stracciatella.testing.runner.TestRunner.getTickMultiplier();
        ctx.waitFor(mc -> countItems(mc.player.getInventory(), item) >= minCount, timeout);
        LOGGER.info("Collected {} {}", ctx.computeOnClient(mc -> countItems(mc.player.getInventory(), item)), itemName);
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
}

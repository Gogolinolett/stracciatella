package net.stracciatella.gui.test;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.GuiRegistry;
import net.stracciatella.gui.screen.GuiRootScreen;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke tests for the GUI framework: the command opens the root menu, and a
 * registered page is reachable by id. Both go through the real deferred-open
 * path, which is the part that silently does nothing if it regresses — a
 * screen opened straight from a command is discarded when the chat screen
 * closes itself afterwards.
 */
@TestSuite(name = "Gui Tests")
public class GuiTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("GuiTests");
    private static final String PROBE_ID = "gui_probe";

    // ================================================================
    // Test 1: /gui opens the root menu
    // ================================================================
    @MinecraftTest(name = "Gui command opens root menu", timeoutTicks = 100, order = 100)
    public void guiCommandOpensRootMenu(TestContext ctx) {
        closeAnyScreen(ctx);

        ctx.runCommand("gui");
        ctx.waitFor(mc -> mc.screen instanceof GuiRootScreen);

        closeAnyScreen(ctx);
        LOGGER.info("Root menu opened via /gui");
    }

    // ================================================================
    // Test 2: a registered page is reachable by id
    // ================================================================
    @MinecraftTest(name = "Gui page reachable by id", timeoutTicks = 100, order = 101)
    public void registeredPageIsReachableById(TestContext ctx) {
        closeAnyScreen(ctx);
        ctx.runOnClient(mc -> GuiRegistry.register(new ProbePage()));

        boolean registered = ctx.computeOnClient(mc -> GuiRegistry.get(PROBE_ID) != null);
        if (!registered) {
            throw new AssertionError("Probe page did not land in the registry");
        }

        ctx.runCommand("gui " + PROBE_ID);
        ctx.waitFor(mc -> mc.screen instanceof ProbeScreen);

        closeAnyScreen(ctx);
        LOGGER.info("Registered page opened via /gui {}", PROBE_ID);
    }

    private void closeAnyScreen(TestContext ctx) {
        ctx.runOnClient(mc -> mc.setScreen(null));
        ctx.waitFor(mc -> mc.screen == null);
    }

    /** Stand-in for a feature module's page — nothing but an identity to find. */
    private static final class ProbePage implements GuiPage {

        @Override
        public String id() {
            return PROBE_ID;
        }

        @Override
        public Component title() {
            return Component.literal("Probe");
        }

        @Override
        public Screen createScreen(Screen parent) {
            return new ProbeScreen();
        }
    }

    private static final class ProbeScreen extends Screen {

        private ProbeScreen() {
            super(Component.literal("Probe"));
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}

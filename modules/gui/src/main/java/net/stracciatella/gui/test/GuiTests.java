package net.stracciatella.gui.test;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.GuiRegistry;
import net.stracciatella.gui.screen.GuiRootScreen;
import net.stracciatella.gui.screen.IdListScreen;
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

    // ================================================================
    // Test 3: the shared id list screen edits the list it is given
    // ================================================================
    /**
     * The list editor both feature pages are built on, driven the way a
     * player drives it — text box and Add, the context button, a row's
     * Remove, Done — against a spec that stands in for a page. Every
     * accepted id is saved the moment it lands, a rejected one and a
     * duplicate leave list and file alone, and Done hands back to the parent.
     */
    @MinecraftTest(name = "Id list screen edits its list", timeoutTicks = 200, order = 102)
    public void idListScreenEditsItsList(TestContext ctx) {
        closeAnyScreen(ctx);
        final List<String> entries = new ArrayList<>(List.of("probe:one"));
        final int[] saves = {0};
        final IdListScreen.Spec spec = new IdListScreen.Spec(
                "Probe list", entries,
                GuiTests::probeId,
                "is not a probe", "Nothing listed", "probe:id",
                () -> saves[0]++,
                "Add from context", () -> "probe:context", "No context");
        ctx.runOnClient(mc -> mc.setScreen(new IdListScreen(null, spec)));
        ctx.waitFor(mc -> mc.screen instanceof IdListScreen);

        typeAndAdd(ctx, "two");
        final List<String> two = List.of("probe:one", "probe:two");
        assertList(ctx, entries, two, saves, 1, "after adding an id");
        typeAndAdd(ctx, "bad one");
        assertList(ctx, entries, two, saves, 1, "after a rejected id");
        typeAndAdd(ctx, "two");
        assertList(ctx, entries, two, saves, 1, "after a duplicate");
        press(ctx, "Add from context");
        assertList(ctx, entries, List.of("probe:one", "probe:two", "probe:context"), saves, 2,
                "after the context button");
        pressRemove(ctx, "probe:one");
        assertList(ctx, entries, List.of("probe:two", "probe:context"), saves, 3,
                "after removing a row");
        press(ctx, "Done");
        ctx.waitFor(mc -> mc.screen == null);
        LOGGER.info("Id list screen edited its list through its widgets");
    }

    /** Put {@code text} in the screen's text box and press Add. */
    private void typeAndAdd(TestContext ctx, String text) {
        ctx.runOnClient(mc -> {
            for (var child : mc.screen.children()) {
                if (child instanceof EditBox box) {
                    box.setValue(text);
                }
            }
        });
        press(ctx, "Add");
    }

    /** Press the button with the given label. */
    private void press(TestContext ctx, String label) {
        ctx.runOnClient(mc -> button(mc.screen, label, -1).onPress(new MouseButtonInfo(0, 0)));
    }

    /** Press the Remove button on the row that shows {@code id}. */
    private void pressRemove(TestContext ctx, String id) {
        ctx.runOnClient(mc -> {
            int rowY = button(mc.screen, id, -1).getY();
            button(mc.screen, "Remove", rowY).onPress(new MouseButtonInfo(0, 0));
        });
    }

    /** The button with the given label, on the given row when {@code y} is not -1. */
    private static Button button(Screen screen, String label, int y) {
        for (var child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(label)
                    && (y < 0 || button.getY() == y)) {
                return button;
            }
        }
        throw new AssertionError("No '" + label + "' button on the screen");
    }

    private void assertList(TestContext ctx, List<String> entries, List<String> expected,
                            int[] saves, int expectedSaves, String when) {
        List<String> actual = ctx.computeOnClient(mc -> new ArrayList<>(entries));
        int saved = ctx.computeOnClient(mc -> saves[0]);
        if (!actual.equals(expected)) {
            throw new AssertionError("List " + when + " is " + actual + ", expected " + expected);
        }
        if (saved != expectedSaves) {
            throw new AssertionError("Saved " + saved + " time(s) " + when + ", expected "
                    + expectedSaves);
        }
    }

    /**
     * Stand-in for a page's normalize: a canonical id comes back as it is,
     * the way the real ones return a namespaced id unchanged — the context
     * button's id goes through it too — a bare name gets the namespace, and
     * a name starting with "bad" names nothing.
     */
    private static String probeId(String raw) {
        if (raw.startsWith("bad")) {
            return null;
        }
        return raw.startsWith("probe:") ? raw : "probe:" + raw;
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

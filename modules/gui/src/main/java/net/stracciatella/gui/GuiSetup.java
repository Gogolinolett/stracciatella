package net.stracciatella.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.stracciatella.gui.screen.GuiRootScreen;
import net.stracciatella.gui.test.GuiTests;
import net.stracciatella.testing.runner.TestRunner;
import org.lwjgl.glfw.GLFW;

/**
 * Holds the module's singletons and performs the actual startup wiring.
 *
 * <p>Separate from {@link GuiModule} for the reason described there: the
 * module main class is verified and loaded before other modules' class
 * loaders are guaranteed to be registered.
 */
public final class GuiSetup {

    private static final Identifier CATEGORY_ID =
            Identifier.fromNamespaceAndPath("stracciatella", "main");

    private static KeyMapping openKey;
    private static Screen pendingScreen;

    private GuiSetup() {
    }

    /**
     * Open a screen at the start of the next client tick instead of right
     * now. A command runs while the chat screen is still up, and the chat
     * screen closes itself with {@code setScreen(null)} straight afterwards —
     * a screen opened inside the command would be thrown away again.
     */
    static void requestOpen(Screen screen) {
        pendingScreen = screen;
    }

    static void init() {
        // Right shift: unbound in vanilla, reachable without leaving the
        // movement hand, and rebindable in Controls like any other key.
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.stracciatella.gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyMapping.Category.register(CATEGORY_ID)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingScreen != null) {
                Screen screen = pendingScreen;
                pendingScreen = null;
                client.setScreen(screen);
                return;
            }
            // consumeClick drains the press queue, so a key held down opens
            // the screen once rather than every tick.
            boolean pressed = false;
            while (openKey.consumeClick()) {
                pressed = true;
            }
            if (pressed && client.screen == null) {
                client.setScreen(new GuiRootScreen(null));
            }
        });

        GuiCommands.register();
        TestRunner.instance().registerSuite(GuiTests.class);
    }
}

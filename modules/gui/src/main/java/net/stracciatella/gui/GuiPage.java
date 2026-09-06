package net.stracciatella.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One entry in the mod's GUI: a feature module contributes a page, the root
 * menu lists it, and opening it hands control to the page's own screen.
 *
 * <p>The page owns its screen rather than describing its contents in some
 * widget-description language. A description language would have to grow a
 * case for every control a future page needs, while a {@link Screen} can
 * already do anything vanilla can — and every page here is written against
 * the same vanilla widgets anyway.
 */
public interface GuiPage {

    /** Stable identifier, used by {@code /gui <id>}. Lowercase, no spaces. */
    String id();

    /** Label shown on the root menu's button. */
    Component title();

    /**
     * Build the page's screen.
     *
     * @param parent the screen to return to when the page is closed — pass it
     *               to the {@code Screen} so ESC goes back to the root menu
     *               instead of straight to the game
     */
    Screen createScreen(Screen parent);
}

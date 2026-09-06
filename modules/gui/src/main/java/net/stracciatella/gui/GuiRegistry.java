package net.stracciatella.gui;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static registry of the pages the root menu offers — the same pattern as the
 * bot module's BehaviorRunner registry, so a feature module wires itself up in
 * its own setup class instead of the GUI module having to know about it.
 *
 * <p>Insertion-ordered: the menu lists pages in the order the modules
 * registered them, which is stable for a given build and keeps a page from
 * jumping around the menu between sessions.
 */
public final class GuiRegistry {

    private static final Map<String, GuiPage> pages = new LinkedHashMap<>();

    private GuiRegistry() {
    }

    public static void register(GuiPage page) {
        pages.put(page.id(), page);
    }

    /** The page with that id, or {@code null} if nothing registered it. */
    public static GuiPage get(String id) {
        return pages.get(id);
    }

    public static Collection<GuiPage> pages() {
        return pages.values();
    }
}

package net.stracciatella.bot.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.stracciatella.bot.BotCommands;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.screen.IdListScreen;

/**
 * The drops the bot leaves lying — {@code ignoredItems} in the bot config —
 * as a page in the shared GUI, on the gui module's list editor. Registered
 * from {@code BotSetup}; the gui module never learns that the bot exists.
 */
public class IgnoredItemsPage implements GuiPage {

    private final BotConfig config;

    public IgnoredItemsPage(BotConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "ignored_items";
    }

    @Override
    public Component title() {
        return Component.literal("Ignored items");
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new IdListScreen(parent, new IdListScreen.Spec(
                "Ignored items",
                config.ignoredItems,
                BotCommands::normalizeItem,
                "is not an item",
                "Nothing ignored — the bot walks to every drop",
                "minecraft:cobblestone",
                config::save,
                "Add item in hand",
                IgnoredItemsPage::itemInHand,
                "Hold an item before opening this menu"));
    }

    /** The id of the item in the main hand, or null when the hand is empty. */
    private static String itemInHand() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getMainHandItem().isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
    }
}

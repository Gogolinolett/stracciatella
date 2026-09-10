package net.stracciatella.bot.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.stracciatella.bot.server.ServerSettings;
import net.stracciatella.bot.server.ServerSettingsStore;
import net.stracciatella.gui.GuiPage;
import net.stracciatella.gui.screen.SettingsScreen;

/**
 * What the bot may do on the server the player is currently on, on the gui
 * module's settings screen.
 *
 * <p>Every row here is read and written through {@link ServerSettingsStore#current()}
 * rather than captured when the page is built. A player who joins a different
 * server and opens this menu must see that server's answers, and a page holding
 * a {@link ServerSettings} from whenever it was constructed would quietly edit
 * the wrong one.
 *
 * <p>The chest <em>positions</em> are not here — see {@link StoragePage} for why
 * pointing at a chest beats typing its coordinates.
 */
public class ServerPage implements GuiPage {

    @Override
    public String id() {
        return "bot_server";
    }

    @Override
    public Component title() {
        return Component.literal("Server permissions");
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new SettingsScreen(parent, new SettingsScreen.Spec(
                "Server permissions — " + ServerSettingsStore.keyFor(Minecraft.getInstance()),
                List.of(
                        new SettingsScreen.Setting(
                                "Exit strategy",
                                "How the bot leaves a pit it has dug: STAIRCASE walks out the way "
                                        + "the digger left, COMMAND stands still and sends this "
                                        + "server's teleport command — say which one with "
                                        + "/bot server exit command <command>.",
                                () -> ServerSettingsStore.current().exitStrategy.name(),
                                ServerPage::cycleExitStrategy),
                        new SettingsScreen.Setting(
                                "Route placement",
                                "Whether the pathfinder may place a block to mend a route it "
                                        + "cannot walk around. Off by default — reshaping terrain "
                                        + "on a server nobody configured is how people get banned.",
                                () -> ServerSettingsStore.current().allowPathPlacement ? "on" : "off",
                                ServerPage::togglePlacement),
                        new SettingsScreen.Setting(
                                "Storages here",
                                "How many chests this server has configured. Read-only: add one by "
                                        + "looking at it and running /bot storage add.",
                                () -> String.valueOf(ServerSettingsStore.current().storages.size()),
                                () -> { })),
                ServerSettingsStore::save));
    }

    private static void cycleExitStrategy() {
        ServerSettings settings = ServerSettingsStore.current();
        ServerSettings.ExitStrategy[] options = ServerSettings.ExitStrategy.values();
        settings.exitStrategy = options[(settings.exitStrategy.ordinal() + 1) % options.length];
    }

    private static void togglePlacement() {
        ServerSettings settings = ServerSettingsStore.current();
        settings.allowPathPlacement = !settings.allowPathPlacement;
    }
}

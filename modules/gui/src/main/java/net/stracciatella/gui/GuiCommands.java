package net.stracciatella.gui;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.stracciatella.gui.screen.GuiRootScreen;

/**
 * {@code /gui} — the keyboard-free way in, and the way a page is reached
 * directly without clicking through the root menu. There is no {@code list}
 * subcommand: the root menu is the list, and a literal alongside a free-form
 * page argument only makes the grammar ambiguous.
 */
public class GuiCommands {

    private static final SuggestionProvider<FabricClientCommandSource> PAGE_IDS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    GuiRegistry.pages().stream().map(GuiPage::id).toList(), builder);

    public static void register() {
        var command = ClientCommandManager.literal("gui")
                // /gui — root menu
                .executes(context -> {
                    GuiSetup.requestOpen(new GuiRootScreen(null));
                    return 1;
                })
                // /gui <page>
                .then(argument("page", StringArgumentType.word())
                        .suggests(PAGE_IDS)
                        .executes(context -> openPage(
                                StringArgumentType.getString(context, "page"),
                                context.getSource())));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(command);
        });
    }

    private static int openPage(String id, FabricClientCommandSource source) {
        GuiPage page = GuiRegistry.get(id);
        if (page == null) {
            source.sendError(Component.literal("No GUI page with id '" + id + "'"));
            return 0;
        }
        // Parent null: opened by id, so closing goes back to the game rather
        // than to a root menu the player never passed through.
        GuiSetup.requestOpen(page.createScreen(null));
        return 1;
    }
}

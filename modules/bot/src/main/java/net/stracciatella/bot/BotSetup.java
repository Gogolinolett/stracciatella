package net.stracciatella.bot;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.gui.IgnoredItemsPage;
import net.stracciatella.bot.test.BotTests;
import net.stracciatella.gui.GuiRegistry;
import net.stracciatella.testing.runner.TestRunner;

/**
 * The module's startup wiring, kept out of {@link BotModule} on purpose: the
 * module main class is loaded with verification while other modules' class
 * loaders may not be registered yet, and the verifier resolves cross-module
 * types for any subtype check in its bytecode — passing an
 * {@link IgnoredItemsPage} to {@code GuiRegistry.register(GuiPage)} is one.
 * This class is only loaded when {@code init()} runs, in the lifecycle
 * phase, after every module is present.
 */
final class BotSetup {

    private BotSetup() {
    }

    static void init() {
        BotController.loadConfig();
        BotCommands.register();
        // Drive block interaction before handleKeybinds processes input.
        // Calls startAttack/continueAttack directly to avoid GLFW key reset issues.
        ClientTickEvents.START_CLIENT_TICK.register(BotController::tickStart);
        // Behavior layer plans first, controller executes in the same tick.
        ClientTickEvents.END_CLIENT_TICK.register(BehaviorRunner::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BotController::tick);
        GuiRegistry.register(new IgnoredItemsPage(BotController.CONFIG));
        TestRunner.instance().registerSuite(BotTests.class);
    }
}

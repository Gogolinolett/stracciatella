package net.stracciatella.bot;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.bot.test.BotTests;
import net.stracciatella.module.Module;
import net.stracciatella.testing.runner.TestRunner;

public class BotModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        BotController.loadConfig();
        BotCommands.register();
        // Drive block interaction before handleKeybinds processes input.
        // Calls startAttack/continueAttack directly to avoid GLFW key reset issues.
        ClientTickEvents.START_CLIENT_TICK.register(BotController::tickStart);
        // Behavior layer plans first, controller executes in the same tick.
        ClientTickEvents.END_CLIENT_TICK.register(BehaviorRunner::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BotController::tick);
        TestRunner.instance().registerSuite(BotTests.class);
    }
}

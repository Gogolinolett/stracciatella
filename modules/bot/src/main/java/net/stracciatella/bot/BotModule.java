package net.stracciatella.bot;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.stracciatella.bot.test.BotTests;
import net.stracciatella.module.Module;
import net.stracciatella.testing.runner.TestRunner;

public class BotModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        BotController.loadConfig();
        BotCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(BotController::tick);
        TestRunner.instance().registerSuite(BotTests.class);
    }
}

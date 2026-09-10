package net.stracciatella.bot;

import net.stracciatella.module.Module;

/**
 * Entry point. Trivial on purpose — the wiring lives in {@link BotSetup},
 * which is only loaded once {@code init()} runs and every module is present.
 */
public class BotModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        BotSetup.init();
    }
}

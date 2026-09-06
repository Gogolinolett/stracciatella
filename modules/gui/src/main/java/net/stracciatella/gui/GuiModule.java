package net.stracciatella.gui;

import net.stracciatella.module.Module;

/**
 * Entry point. Deliberately trivial: the module main class is loaded with
 * bytecode verification before other modules' class loaders are guaranteed
 * to be registered, so it must not reference any type that pulls in
 * cross-module classes — all wiring lives in {@link GuiSetup}, which is only
 * loaded when init() runs (lifecycle phase, all modules present).
 */
public class GuiModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        GuiSetup.init();
    }
}

package net.stracciatella.miner;

import net.stracciatella.module.Module;

/**
 * Entry point. Deliberately trivial: the module main class is loaded with
 * bytecode verification before other modules' class loaders are guaranteed
 * to be registered, so it must not reference any type that pulls in
 * cross-module classes — all wiring and singletons live in
 * {@link MinerSetup}, which is only loaded when init() runs (lifecycle
 * phase, all modules present).
 */
public class MinerModule implements Module {

    @Task(lifeCycle = LifeCycle.STARTED)
    public void init() {
        MinerSetup.init();
    }
}

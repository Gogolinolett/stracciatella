package net.stracciatella.miner;

import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.miner.test.MinerTests;
import net.stracciatella.testing.runner.TestRunner;

/**
 * Holds the module's singletons and performs the actual startup wiring.
 *
 * <p>Separate from {@link MinerModule} on purpose: the module main class is
 * loaded (with verification) while other modules' class loaders may not be
 * registered yet, and the verifier resolves cross-module types for any
 * subtype check in its bytecode (e.g. passing a {@code DiamondMinerBehavior}
 * to {@code BehaviorRunner.register(BotBehavior)}). This class is only
 * loaded when {@code init()} is first called — during the lifecycle phase,
 * after every module is loaded — so all cross-module references here are
 * safe.
 */
public final class MinerSetup {

    public static final MinerConfig CONFIG = new MinerConfig();
    private static DiamondMinerBehavior diamondMiner;

    private MinerSetup() {
    }

    public static DiamondMinerBehavior diamondMiner() {
        return diamondMiner;
    }

    static void init() {
        CONFIG.applyFrom(MinerConfig.load());
        diamondMiner = new DiamondMinerBehavior(CONFIG);
        BehaviorRunner.register(diamondMiner);
        MinerCommands.register();
        TestRunner.instance().registerSuite(MinerTests.class);
    }
}

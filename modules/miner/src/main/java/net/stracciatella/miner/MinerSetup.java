package net.stracciatella.miner;

import net.stracciatella.bot.behavior.BehaviorRunner;
import net.stracciatella.gui.GuiRegistry;
import net.stracciatella.miner.gui.BlacklistPage;
import net.stracciatella.miner.test.ChunkMinerTests;
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
    private static ChunkMinerBehavior chunkMiner;

    private MinerSetup() {
    }

    public static DiamondMinerBehavior diamondMiner() {
        return diamondMiner;
    }

    public static ChunkMinerBehavior chunkMiner() {
        return chunkMiner;
    }

    static void init() {
        CONFIG.applyFrom(MinerConfig.load());
        diamondMiner = new DiamondMinerBehavior(CONFIG);
        chunkMiner = new ChunkMinerBehavior(CONFIG);
        BehaviorRunner.register(diamondMiner);
        BehaviorRunner.register(chunkMiner);
        MinerCommands.register();
        GuiRegistry.register(new BlacklistPage(CONFIG));
        TestRunner.instance().registerSuite(MinerTests.class);
        TestRunner.instance().registerSuite(ChunkMinerTests.class);
    }
}

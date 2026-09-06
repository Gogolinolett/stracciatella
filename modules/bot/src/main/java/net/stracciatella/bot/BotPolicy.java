package net.stracciatella.bot;

/**
 * Opt-in behaviour a {@link net.stracciatella.bot.behavior.BotBehavior}
 * requests from the execution layer.
 *
 * <p>Every behavior declares its own policy; there is no global bot setting
 * and no command to change one. {@code BehaviorRunner} enforces the stop
 * guards and hands the policy to {@link BotController}, which reads the
 * collection flags — so a behavior that asks for nothing (the default
 * {@link #none()}) executes exactly as it did before this type existed.
 * That is deliberate: safety guards and collection changes must be available
 * to new behaviors without being forced on {@code /bot mine} or the diamond
 * miner, whose tuning was validated without them.
 *
 * <p>Lives in this package rather than {@code behavior} so the dependency
 * runs behavior → core and never the other way: {@code BotController} must
 * not import from the layer above it.
 */
public record BotPolicy(
        boolean stopOnDamage,
        boolean stopOnPlayerAttack,
        boolean stopWhenInventoryFull,
        int minFreeSlots,
        boolean opportunisticCollection,
        boolean fastCollectExit) {

    private static final BotPolicy NONE =
            new BotPolicy(false, false, false, 0, false, false);

    /**
     * Everything off — the execution layer behaves exactly as it did before
     * policies existed. The default for any behavior that has not opted in.
     */
    public static BotPolicy none() {
        return NONE;
    }

    /** Stop the run on any health decrease. */
    public BotPolicy withDamageStop() {
        return new BotPolicy(true, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, fastCollectExit);
    }

    /** Stop the run when a player attacks the bot, even for zero damage. */
    public BotPolicy withPlayerAttackStop() {
        return new BotPolicy(stopOnDamage, true, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, fastCollectExit);
    }

    /** Stop the run once fewer than {@code minFreeSlots} main slots are empty. */
    public BotPolicy withInventoryFullStop(int minFreeSlots) {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, true, minFreeSlots,
                opportunisticCollection, fastCollectExit);
    }

    /**
     * Walk to nearby drops while still mining, keeping the target block in
     * view. See {@code BotController.tickInteracting}.
     */
    public BotPolicy withOpportunisticCollection() {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                true, fastCollectExit);
    }

    /**
     * Leave COLLECTING as soon as nothing reachable is left, instead of
     * waiting out {@code collectWaitMax} whenever no drop was ever observed.
     * See {@code BotController.tickCollecting} for the two stalls this closes.
     */
    public BotPolicy withFastCollectExit() {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, true);
    }
}

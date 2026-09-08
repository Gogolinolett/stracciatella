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
        boolean fastCollectExit,
        boolean orderedTasks) {

    private static final BotPolicy NONE =
            new BotPolicy(false, false, false, 0, false, false, false);

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
                opportunisticCollection, fastCollectExit, orderedTasks);
    }

    /** Stop the run when a player attacks the bot, even for zero damage. */
    public BotPolicy withPlayerAttackStop() {
        return new BotPolicy(stopOnDamage, true, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, fastCollectExit, orderedTasks);
    }

    /** Stop the run once fewer than {@code minFreeSlots} main slots are empty. */
    public BotPolicy withInventoryFullStop(int minFreeSlots) {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, true, minFreeSlots,
                opportunisticCollection, fastCollectExit, orderedTasks);
    }

    /**
     * Walk to nearby drops while still mining, keeping the target block in
     * view. See {@code BotController.tickInteracting}.
     */
    public BotPolicy withOpportunisticCollection() {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                true, fastCollectExit, orderedTasks);
    }

    /**
     * Leave COLLECTING as soon as nothing reachable is left, instead of
     * waiting out {@code collectWaitMax} whenever no drop was ever observed.
     * See {@code BotController.tickCollecting} for the two stalls this closes.
     */
    public BotPolicy withFastCollectExit() {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, true, orderedTasks);
    }

    /**
     * Take queued tasks in the order they were enqueued instead of nearest
     * first.
     *
     * <p>Nearest-first is the right default: a person works an area closest
     * first from wherever they stand, and replaying a scanner's fixed order
     * produces visible zigzag routes. It is wrong for a behavior that has
     * already decided the order, and a sweep is exactly that. Handed a choice
     * between the column in front of the bot and one behind it, nearest-first
     * can take the far one while the near column still hides it — the raycast
     * lands on the near block, LOOKING's hit-result gate never fires, and the
     * task burns a look timeout and a re-approach. Measured on the chunk
     * miner: 19.1 ticks per block became 62.0, with 136 of 248 ticks in
     * LOOKING, the moment more than one column was queued at a time.
     *
     * <p>It also restores head-before-feet within a column, which the miner
     * plans for and nearest-first quietly undid: standing on the floor, the
     * foot block is one block away and the head block 1.41, so the nearer of
     * the two is always the one whose upward face is still covered.
     */
    public BotPolicy withOrderedTasks() {
        return new BotPolicy(stopOnDamage, stopOnPlayerAttack, stopWhenInventoryFull, minFreeSlots,
                opportunisticCollection, fastCollectExit, true);
    }
}

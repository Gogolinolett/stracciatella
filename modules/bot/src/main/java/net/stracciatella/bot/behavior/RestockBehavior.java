package net.stracciatella.bot.behavior;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.stracciatella.bot.BotConfig;
import net.stracciatella.bot.BotController;
import net.stracciatella.bot.BotPolicy;
import net.stracciatella.bot.humanize.HumanBehavior;
import net.stracciatella.bot.humanize.TravelClicks;
import net.stracciatella.bot.server.ServerSettings;
import net.stracciatella.bot.server.ServerSettingsStore;
import net.stracciatella.bot.server.StorageSite;
import net.stracciatella.bot.task.OpenContainerTask;
import net.stracciatella.pathfinding.logic.PathWalker;
import net.stracciatella.pathfinding.place.PathPlacement;
import net.stracciatella.pathfinding.travel.Journey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks to a storage block, empties the loot into it, takes back what the
 * interrupted behavior is short of, and walks home — on to the next storage
 * whenever one will not take any more or has nothing to give, because a camp is
 * a row of barrels and one of them is always full.
 *
 * <p>It is a {@link BotBehavior} like any other rather than a mode inside one,
 * and that is what makes it reusable: the chunk miner needed it first, but
 * nothing here knows what mining is. {@link BehaviorRunner} suspends whoever was
 * running, starts this, and starts them again afterwards — see the two-slot
 * stack there for why suspend is {@code abort()}.
 *
 * <p>The shopping list comes from the suspended behavior's
 * {@link RestockNeeds}, read through {@link BehaviorRunner#suspendedNeeds()}.
 * This class never decides <em>what</em> a run needs; it only decides how to get
 * it.
 *
 * <h2>Getting out and getting back</h2>
 *
 * <p>A bot that has been mining is usually standing at the bottom of something,
 * and a mesh edge climbs at most one block. Two ways out, chosen per server
 * because which one exists is a fact about the server:
 * {@link ServerSettings.ExitStrategy#COMMAND} comes to a stop and sends the
 * server's own teleport command from there, and
 * {@link ServerSettings.ExitStrategy#STAIRCASE} simply walks — which works
 * because whoever dug the hole left a way up it (the chunk miner's spiral) and
 * because the pathfinder may mend a gap when the server allows it. Nothing is
 * built here: a staircase laid by the restock would be a second implementation
 * of the one the miner already leaves standing, and the two would disagree.
 *
 * <p>The way back is always on foot. No {@code /back}: the command exists on
 * some servers and returns the player to where they teleported <em>from</em>,
 * which after a deposit is the camp, not the pit — and a restock that silently
 * ends up somewhere else resumes a mining run at the wrong coordinates.
 */
public class RestockBehavior implements BotBehavior {

    public static final String ID = "restock";

    private static final Logger LOGGER = LoggerFactory.getLogger("RestockBehavior");

    /**
     * How far the player has to move in one go for the exit command to count as
     * having worked. Teleports on these servers go to a camp or a spawn, which
     * is never a few blocks away; a drift of a block or two while standing on an
     * edge must not read as an arrival.
     */
    private static final double TELEPORT_JUMP_DISTANCE = 32.0;

    /**
     * Clicks in a row that may move nothing before the container is given up
     * on. Whether a stack fits is asked before clicking
     * ({@code ContainerTransfer.nextDeposit}), so a refused click is the
     * exception — a container that will not take the item — and one retry is
     * all a person gives it. This used to be sixty, with a click every other
     * tick, and it was how every full barrel was found out: six seconds each.
     */
    private static final int TRANSFER_STALL_CLICKS = 1;

    /**
     * Ticks the bot holds one block before the exit command goes out. Not the
     * server's standstill count — that one starts when the command arrives — but
     * the time a walk that has just been told to stop needs to have stopped, so
     * the command is not refused for movement the bot is no longer making.
     */
    private static final int SETTLE_TICKS = 10;

    private enum Phase {
        /** Getting out of whatever the bot has dug itself into. */
        LEAVE_SITE,
        /** Travelling to the chosen storage. */
        TO_STORAGE,
        /** Clicking it open. */
        OPEN,
        /** Shift-clicking stacks, one at a time. */
        TRANSFER,
        /** Letting go of the screen. */
        CLOSE,
        /** Travelling back to where the interrupted run was standing. */
        RETURN
    }

    private final BotConfig config;

    private Phase phase = Phase.LEAVE_SITE;
    private RestockNeeds needs = RestockNeeds.none();
    private BlockPos anchor;
    /** Every storage in this dimension, nearest first — see {@link #sortedStorages}. */
    private List<StorageSite> route = List.of();
    /** Which one of {@link #route} the bot is at or heading for. */
    private int stop;
    private String failure;

    private int phaseTicks;
    /** Whether the current phase has already handed its destination to {@link Journey}. */
    private boolean journeyStarted;
    private int clickCooldown;
    private int stalledClicks;
    private int deposited;
    private int withdrawn;
    /**
     * Whether this trip still has something to do when the current container is
     * let go of. Decided in {@link #tickTransfer} while the menu is still open,
     * because that is where the answer is cheap and certain, and read by
     * {@link #tickClose} to choose between the next storage and going home.
     */
    private boolean workLeft;

    /** Where the bot stood when the exit command went out, to spot the jump. */
    private BlockPos standstillFrom;
    private int standstillTicks;
    private boolean commandsSent;

    public RestockBehavior(BotConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public BotPolicy policy() {
        // Nothing else. The bot is carrying a full load of ore across open
        // ground, so being hit is worth stopping for — but inventory-full is the
        // condition this run exists to fix, and the collection tweaks are about
        // mining, which it does not do.
        return BotPolicy.none().withDamageStop().withPlayerAttackStop();
    }

    @Override
    public boolean expectsTeleport() {
        // Exactly the stretch between the exit command going out and the jump
        // being seen. Before it there is nothing to expect, and after it a
        // second move is somebody else's doing and ends the trip like any other.
        return phase == Phase.LEAVE_SITE && commandsSent;
    }

    @Override
    public void start(Minecraft client) {
        phase = Phase.LEAVE_SITE;
        phaseTicks = 0;
        journeyStarted = false;
        clickCooldown = 0;
        stalledClicks = 0;
        deposited = 0;
        withdrawn = 0;
        stop = 0;
        workLeft = false;
        standstillFrom = null;
        standstillTicks = 0;
        commandsSent = false;
        failure = null;
        needs = BehaviorRunner.suspendedNeeds();

        LocalPlayer player = client.player;
        if (player == null) {
            failure = "no player";
            return;
        }
        // The work site is simply where the bot is standing: the runner suspends
        // on the tick the shortfall is noticed, so this runs before anything has
        // moved. Nobody has to remember to record it earlier.
        anchor = player.blockPosition();

        ServerSettings settings = ServerSettingsStore.current();
        String dimension = ServerSettingsStore.dimensionOf(client);
        route = sortedStorages(settings.storagesIn(dimension), anchor);
        if (route.isEmpty()) {
            failure = "no storage configured for " + ServerSettingsStore.keyFor(client)
                    + " in " + dimension + " — point the bot at one with /bot storage add";
            return;
        }
        LOGGER.info("Restock from {}: {} storages, nearest {}, needs {} entries",
                shortPos(anchor), route.size(), storage(), needs.needs().size());
    }

    @Override
    public BehaviorStatus tick(Minecraft client) {
        if (failure != null) {
            return fail(failure);
        }
        LocalPlayer player = client.player;
        Level level = client.level;
        if (player == null || level == null) {
            return fail("no player");
        }
        phaseTicks++;

        return switch (phase) {
            case LEAVE_SITE -> tickLeaveSite(client, player);
            case TO_STORAGE -> tickTravel(storage().pos(), Phase.OPEN, "to the storage");
            case OPEN -> tickOpen(player, level);
            case TRANSFER -> tickTransfer(player);
            case CLOSE -> tickClose(player);
            case RETURN -> tickTravel(anchor, null, "back to the work site");
        };
    }

    /**
     * Get clear of the work site. With the staircase strategy there is nothing
     * to do — the walk in {@link Phase#TO_STORAGE} climbs whatever way out the
     * digger left, and a route the pathfinder has to mend is mended there.
     */
    private BehaviorStatus tickLeaveSite(Minecraft client, LocalPlayer player) {
        if (ServerSettingsStore.current().exitStrategy != ServerSettings.ExitStrategy.COMMAND) {
            return enter(Phase.TO_STORAGE);
        }
        List<String> commands = ServerSettingsStore.current().exitCommands;
        if (commands.isEmpty()) {
            // Configured to teleport but given nothing to send. Walking instead
            // would be a silent substitution of one strategy for another, and
            // the walk out of a deep pit is exactly what the teleport was chosen
            // to avoid.
            return fail("exit strategy is COMMAND but no exit commands are configured");
        }

        if (!commandsSent) {
            // Standing still means standing still: whatever was walking stops,
            // and the bot holds the spot. But it holds it only long enough to
            // have actually stopped — the server's own count starts when the
            // command arrives, so waiting that count out before sending spends
            // it twice and the first spending buys nothing. Measured in a real
            // run: twelve seconds between "Restocking" and the command going on
            // the wire, and the teleport landing in the same second it finally
            // did.
            if (standstillFrom == null) {
                PathWalker.stop();
                BotController.stop();
                standstillFrom = player.blockPosition();
                standstillTicks = 0;
            }
            if (!player.blockPosition().equals(standstillFrom)) {
                // Pushed, or still sliding off a rim. Let the slide finish
                // rather than send a command the server is about to refuse.
                standstillFrom = player.blockPosition();
                standstillTicks = 0;
                return BehaviorStatus.RUNNING;
            }
            if (++standstillTicks < SETTLE_TICKS) {
                return BehaviorStatus.RUNNING;
            }
            for (String command : commands) {
                player.connection.sendCommand(command);
            }
            commandsSent = true;
            standstillTicks = 0;
            LOGGER.info("Exit commands sent from {}, waiting for the teleport",
                    shortPos(standstillFrom));
            return BehaviorStatus.RUNNING;
        }

        // Arrival is the position jumping, not a timer running out — the wait is
        // only a bound on how long to believe in it.
        //
        // Nothing past the send reacts to ordinary movement, and that is the
        // repair rather than an omission. A branch that restarted the count
        // whenever the position changed sat in front of this one and swallowed
        // the single event it was waiting for: the jump IS movement. The bot
        // then held its new spot for another count, sent the command again —
        // which teleported it to where it already stood, so not even a position
        // change was left to see — and failed with "did not teleport" after a
        // log that shows the teleport twice. A nudge from a mob survives the
        // same way: standstillFrom stays where the command went out, so the jump
        // is still measured from the place it has to be measured from.
        standstillTicks++;
        if (Math.sqrt(player.blockPosition().distSqr(standstillFrom)) > TELEPORT_JUMP_DISTANCE) {
            LOGGER.info("Teleported to {}", shortPos(player.blockPosition()));
            return enter(Phase.TO_STORAGE);
        }
        if (standstillTicks > config.teleportWaitTicks) {
            return fail("exit commands did not teleport the bot");
        }
        return BehaviorStatus.RUNNING;
    }

    /**
     * Walk to {@code target} with the {@link Journey} layer, which handles the
     * chunks that are not loaded yet, the legs in between, and the mending of a
     * route when the server permits it.
     *
     * @param next the phase to enter on arrival, or {@code null} when arriving
     *             finishes the whole restock
     */
    private BehaviorStatus tickTravel(BlockPos target, Phase next, String what) {
        // A flag rather than reading Journey's own state to decide whether to
        // start: Journey reports ARRIVED when it has never run at all, which is
        // the honest answer to "are you travelling" and a trap for anyone using
        // it as "have I arrived". Asked that way, both travel phases completed
        // instantly without a step being taken.
        if (!journeyStarted) {
            PathPlacement.setAllowed(ServerSettingsStore.current().allowPathPlacement);
            Journey.start(target, new Journey.Quirks(
                    HumanBehavior.blunderChance(config.travelDetourChance),
                    HumanBehavior.blunderChance(config.travelPauseChance),
                    config.zoneOutMinTicks, config.zoneOutMaxTicks));
            journeyStarted = true;
            return BehaviorStatus.RUNNING;
        }
        if (Journey.status() == Journey.Status.RUNNING) {
            TravelClicks.tick(Minecraft.getInstance(), config);
            return BehaviorStatus.RUNNING;
        }
        TravelClicks.stop();
        return Journey.status() == Journey.Status.ARRIVED
                ? next == null ? BehaviorStatus.SUCCEEDED : enter(next)
                : fail("could not walk " + what + ": " + Journey.failReason());
    }

    private BehaviorStatus tickOpen(LocalPlayer player, Level level) {
        if (OpenContainerTask.isContainerOpen()) {
            // Each container starts its own stall count. Carrying the previous
            // one's over would have the next storage closed on its first
            // unsatisfied click — the count is a statement about one container,
            // not about the trip.
            stalledClicks = 0;
            // A glance at what is in there before the first click.
            clickCooldown = HumanBehavior.randomRestockClickDelay(config);
            return enter(Phase.TRANSFER);
        }
        if (!isStorageBlock(level, storage().pos())) {
            // Reported and walked past, never struck off the list: an unloaded
            // chunk looks exactly like a chest somebody mined, and a list that
            // edits itself on that evidence quietly forgets a whole camp.
            return moveOn("no " + String.join(" or ", config.storageBlocks) + " at "
                    + storage() + " any more — remove it with /bot storage remove");
        }
        if (!BotController.isActive()) {
            if (phaseTicks > 1) {
                // The task ran and the screen never opened. Saying so beats
                // clicking the same block until the behavior times out.
                return moveOn("could not open the storage at " + storage());
            }
            BotController.enqueueTask(new OpenContainerTask(storage().pos()));
        }
        return BehaviorStatus.RUNNING;
    }

    /**
     * One shift-click per cooldown: loot out first, then whatever the manifest is
     * short of. Deposit before withdraw, because a withdrawal needs somewhere to
     * land and the loot is what is filling the slots.
     */
    private BehaviorStatus tickTransfer(LocalPlayer player) {
        if (!OpenContainerTask.isContainerOpen()) {
            // The server closed it — out of range, or the chest was broken.
            return fail("the storage screen closed mid-transfer");
        }
        if (clickCooldown > 0) {
            clickCooldown--;
            return BehaviorStatus.RUNNING;
        }

        int depositSlot = ContainerTransfer.nextDeposit(player, needs);
        if (depositSlot >= 0) {
            return move(player, depositSlot, true);
        }
        int withdrawSlot = ContainerTransfer.nextWithdraw(player, needs);
        if (withdrawSlot >= 0) {
            return move(player, withdrawSlot, false);
        }
        // Nothing more that fits in, and nothing here the manifest wants or the
        // bot has room for. The trip goes on if loot is left over that did not
        // fit — this storage is full — or the manifest is still short of
        // something another storage may hold.
        boolean full = ContainerTransfer.hasLoot(player, needs);
        workLeft = full || needs.shortfall(player) != null;
        if (full) {
            LOGGER.info("Transfer stalled at {} after {} in, {} out — nothing more fits here",
                    storage(), deposited, withdrawn);
        } else {
            LOGGER.info("Transfer done at {}: {} stacks in, {} stacks out, manifest {}",
                    storage(), deposited, withdrawn,
                    workLeft ? "still short" : "satisfied");
        }
        // Shut at once: the gap after the last click, or the glance on
        // opening when nothing fitted at all, was the beat a person takes to
        // see there is nothing more to do — well inside half a second.
        return enter(Phase.CLOSE);
    }

    /**
     * Click one stack across and watch that it actually went. A chest with no
     * room left, or one holding a stack the bot has no room for, answers a
     * shift-click by doing nothing at all — and the same slot comes back from
     * the planner on the next tick, forever.
     */
    private BehaviorStatus move(LocalPlayer player, int menuSlot, boolean depositing) {
        ItemStack before = player.containerMenu.slots.get(menuSlot).getItem().copy();
        ContainerTransfer.quickMove(player, menuSlot);
        ItemStack after = player.containerMenu.slots.get(menuSlot).getItem();
        if (unchanged(before, after)) {
            stalledClicks++;
            if (stalledClicks > TRANSFER_STALL_CLICKS) {
                // A click the planner wanted and the container would not take,
                // although the room check said it would: something about this
                // container refuses the item. There is work left by
                // construction — the planner had just named it — so the next
                // storage gets the trip, and the one after that, until the
                // list runs out.
                workLeft = true;
                LOGGER.info("Transfer stalled at {} after {} in, {} out —"
                        + " the container refused a stack", storage(), deposited, withdrawn);
                return enter(Phase.CLOSE);
            }
            // Not a hard failure: the other direction may still have work, and
            // the planner is asked again next tick.
            clickCooldown = 1;
            return BehaviorStatus.RUNNING;
        }
        stalledClicks = 0;
        if (depositing) {
            deposited++;
        } else {
            withdrawn++;
        }
        clickCooldown = HumanBehavior.randomRestockClickDelay(config);
        return BehaviorStatus.RUNNING;
    }

    /**
     * Whether a shift-click moved nothing out of the slot it was aimed at. The
     * client applies the move locally before the server answers, so reading the
     * slot straight after the click is reading the prediction — which is exactly
     * what is wanted here: a click the client itself could not satisfy is one the
     * server will not satisfy either.
     */
    private static boolean unchanged(ItemStack before, ItemStack after) {
        return before.getCount() == after.getCount()
                && ItemStack.isSameItemSameComponents(before, after);
    }

    private BehaviorStatus tickClose(LocalPlayer player) {
        if (OpenContainerTask.isContainerOpen()) {
            player.closeContainer();
            return BehaviorStatus.RUNNING;
        }
        if (workLeft && stop + 1 < route.size()) {
            return nextStop();
        }
        return enter(Phase.RETURN);
    }

    /**
     * Walk on to the next storage on the route.
     *
     * <p>A camp is a row of barrels, not one chest, and any single one of them is
     * full sooner or later — a trip that commits to the nearest and gives up when
     * it will not take another stack is a trip that deposits nothing. Reported
     * from a real run: one barrel of thirty-three on record, {@code Transfer
     * stalled after 0 in, 0 out}, and the bot walked home with everything it
     * arrived with.
     *
     * <p>No roles, deliberately: a barrel that holds a pickaxe <em>is</em> the
     * tool barrel, and the bot finds that out by opening it. The list is only
     * ever ordered, never labelled, so nothing here needs the player to keep
     * bookkeeping up to date.
     */
    private BehaviorStatus nextStop() {
        stop++;
        LOGGER.info("On to storage {} of {}: {}", stop + 1, route.size(), storage());
        return enter(Phase.TO_STORAGE);
    }

    /**
     * Say what is wrong with this storage and try the next one, or fail when it
     * was the last.
     */
    private BehaviorStatus moveOn(String reason) {
        if (stop + 1 >= route.size()) {
            return fail(reason);
        }
        LOGGER.warn("Restock: {}", reason);
        return nextStop();
    }

    /** The storage the bot is at or heading for. */
    private StorageSite storage() {
        return route.get(stop);
    }

    @Override
    public void abort() {
        TravelClicks.stop();
        Journey.stop();
        PathWalker.stop();
        BotController.stop();
        var player = Minecraft.getInstance().player;
        if (player != null && OpenContainerTask.isContainerOpen()) {
            // Leaving a chest screen open would hold the bot's hands for the
            // rest of the session — the next behavior's clicks go into the
            // container, not the world.
            player.closeContainer();
        }
    }

    @Override
    public String statusLine() {
        return switch (phase) {
            case LEAVE_SITE -> "leaving the work site";
            case TO_STORAGE -> "walking to " + storage()
                    + " (" + (stop + 1) + " of " + route.size() + ")";
            case OPEN -> "opening " + storage();
            case TRANSFER -> "sorting (" + deposited + " in, " + withdrawn + " out)";
            case CLOSE -> "closing up";
            case RETURN -> "walking back to " + shortPos(anchor);
        };
    }

    /**
     * Whether a trip is possible at all right now: this server, this dimension,
     * at least one chest on record.
     *
     * <p>Asked by {@link BehaviorRunner} <em>before</em> it suspends anybody, so
     * that a manifest nobody can serve is not a reason to end a run. The same
     * question {@link #start} asks when it picks a chest — one answer, one place,
     * because the two disagreeing would mean a run suspended for a trip that then
     * reports it cannot happen.
     */
    public static boolean hasStorage(Minecraft client) {
        return !ServerSettingsStore.current()
                .storagesIn(ServerSettingsStore.dimensionOf(client)).isEmpty();
    }

    /**
     * {@code candidates} ordered by distance from {@code from}, nearest first.
     *
     * <p>Sorted once, at the start, rather than re-picking the nearest unvisited
     * one at every stop. The two orders barely differ for a row of barrels a
     * block apart, and this one is a plan the bot can be asked about: stop three
     * of thirty-three is a sentence, "the nearest one I have not tried yet" is
     * not.
     */
    private static List<StorageSite> sortedStorages(List<StorageSite> candidates, BlockPos from) {
        List<StorageSite> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(site -> site.pos().distSqr(from)));
        return sorted;
    }

    /** Whether the block there is still one of the configured storage kinds. */
    private boolean isStorageBlock(Level level, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        return config.storageBlocks.contains(id);
    }

    private BehaviorStatus enter(Phase next) {
        phase = next;
        phaseTicks = 0;
        journeyStarted = false;
        LOGGER.info("Restock phase: {}", next);
        return BehaviorStatus.RUNNING;
    }

    private BehaviorStatus fail(String reason) {
        LOGGER.warn("Restock failed: {}", reason);
        failure = reason;
        abort();
        return BehaviorStatus.FAILED;
    }

    private static String shortPos(BlockPos pos) {
        return pos == null ? "?" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}

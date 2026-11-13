package core.amazake.stress.races;

import core.amazake.stress.LoadModule;
import core.amazake.stress.Stage;
import core.amazake.stress.StressContext;
import core.amazake.stress.StressPlugin;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.EventExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Load module "races": a plugin-like workload that corrupts its own state if its code ever runs on two threads
 * at once. It is the canary for multithreaded servers: Amazake keeps non-thread-safe plugins safe with a global
 * plugin lock, and this module proves it (0 violations), while costing about what a typical listener-heavy
 * plugin costs.
 *
 * <ul>
 *   <li>Listeners on frequent sync events in stress_world ({@link EntityMoveEvent}, {@link BlockPhysicsEvent},
 *       {@link EntityTargetEvent}, {@link PlayerMoveEvent}, each switchable): LOW stashes the event, NORMAL counts
 *       it in plain HashMap counters and an ArrayList and moves money in the bank, MONITOR checks the stash.</li>
 *   <li>{@code sync-tasks} main-thread scheduler tasks: fire {@link RaceProbeEvent}s (some nested), make bank
 *       transfers and walk the event log.</li>
 *   <li>Every {@code check-interval-ticks}, on every stage change and on stop: {@link RaceLedger#check()}.</li>
 * </ul>
 * Asynchronous events are never counted ({@link Event#isAsynchronous()}): they run off-main on every server.
 * Sync events seen off the primary thread are counted separately ({@code offMainEvents}); on servers other
 * than Amazake they do not touch the ledger (see {@link #foreignThread(boolean)}).
 * Only the Bukkit/Paper API is used, so it runs unchanged on Paper and Leaf (single thread: 0 violations).
 */
public final class RacesModule implements LoadModule {

    private final StressPlugin plugin;
    private @Nullable StressContext ctx;
    private @Nullable RaceLedger ledger;
    private final Listener listener = new Listener() {
    };
    private World world;
    private boolean amazake;

    // settings
    private int syncTasks;
    private int probesPerTask;
    private int transfersPerTask;
    private int checkInterval;
    private final List<String> listened = new ArrayList<>();

    // plain on purpose: part of the non-thread-safe "plugin" state
    private int cursor;
    private int probeSeq;
    private long scanned;

    // thread-safe information
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong probes = new AtomicLong();
    private final AtomicLong offMainEvents = new AtomicLong();
    private final Set<String> offMainThreads = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    private boolean finalChecked;

    public RacesModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "races";
    }

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.world = ctx.world();
        this.amazake = "Amazake".equalsIgnoreCase(Bukkit.getName());
        final ConfigurationSection cfg = ctx.config();
        this.syncTasks = clamp(cfg.getInt("sync-tasks", 2), 0, 64);
        this.probesPerTask = clamp(cfg.getInt("probe-events-per-task", 10), 0, 10_000);
        this.transfersPerTask = clamp(cfg.getInt("transfers-per-task", 20), 0, 100_000);
        this.checkInterval = clamp(cfg.getInt("check-interval-ticks", 20), 1, 72_000);
        this.ledger = new RaceLedger(clamp(cfg.getInt("accounts", 64), 2, 100_000), 1_000_000L,
            clamp(cfg.getInt("log-cap", 4096), 1, 1_000_000));

        if (cfg.getBoolean("listen.entity-move", true)) {
            this.listen(EntityMoveEvent.class, "entity-move", e -> e.getEntity().getWorld() == this.world ? e.getEntity().getType().name() : null);
        }
        if (cfg.getBoolean("listen.block-physics", true)) {
            this.listen(BlockPhysicsEvent.class, "block-physics", e -> e.getBlock().getWorld() == this.world ? e.getChangedType().name() : null);
        }
        if (cfg.getBoolean("listen.entity-target", true)) {
            this.listen(EntityTargetEvent.class, "entity-target", e -> e.getEntity().getWorld() == this.world ? e.getReason().name() : null);
        }
        if (cfg.getBoolean("listen.player-move", true)) {
            this.listen(PlayerMoveEvent.class, "player-move", e -> e.getPlayer().getWorld() == this.world ? "player" : null);
        }
        this.listen(RaceProbeEvent.class, "probe", e -> "depth-" + e.depth());
        ctx.onStop(() -> HandlerList.unregisterAll(this.listener));

        for (int i = 0; i < this.syncTasks; i++) {
            ctx.runTimer(this::syncTask, 1L + i, 1L);
        }
    }

    /**
     * Registers LOW / NORMAL / MONITOR executors for {@code type}. {@code detail} returns null for events outside
     * stress_world (decided once, at LOW; MONITOR ignores events that were not stashed).
     */
    private <E extends Event> void listen(Class<E> type, String kind, Function<E, @Nullable String> detail) {
        this.listened.add(kind);
        final RaceLedger ledger = this.ledger;
        final EventExecutor low = (l, event) -> {
            if (!type.isInstance(event) || event.isAsynchronous() || this.stopped || this.foreignThread(false)) {
                return;
            }
            try {
                if (detail.apply(type.cast(event)) != null) {
                    ledger.stashPush(event);
                }
            } catch (final RuntimeException e) {
                ledger.exception(e);
            }
        };
        final EventExecutor normal = (l, event) -> {
            if (!type.isInstance(event) || event.isAsynchronous() || this.stopped || this.foreignThread(true)) {
                return;
            }
            try {
                final E typed = type.cast(event);
                final String key = detail.apply(typed);
                if (key != null) {
                    this.onEvent(kind, key, typed);
                }
            } catch (final RuntimeException e) {
                ledger.exception(e);
            }
        };
        final EventExecutor monitor = (l, event) -> {
            if (!type.isInstance(event) || event.isAsynchronous() || this.foreignThread(false)) {
                return;
            }
            try {
                ledger.stashPop(event);
            } catch (final RuntimeException e) {
                ledger.exception(e);
            }
        };
        final var pm = this.plugin.getServer().getPluginManager();
        pm.registerEvent(type, this.listener, EventPriority.LOW, low, this.plugin, false);
        pm.registerEvent(type, this.listener, EventPriority.NORMAL, normal, this.plugin, false);
        pm.registerEvent(type, this.listener, EventPriority.MONITOR, monitor, this.plugin, false);
    }

    /**
     * True if this sync event runs off the primary thread on a server that does not promise to serialize plugin
     * code (anything but Amazake, e.g. a fork that fires some sync-flagged events from its own worker threads):
     * such events are only counted as information and never touch the ledger, so they cannot fail the run.
     * On Amazake every thread counts: its plugin lock is exactly what this module verifies.
     */
    private boolean foreignThread(boolean record) {
        if (Bukkit.isPrimaryThread()) {
            return false;
        }
        if (record) {
            this.offMainEvents.incrementAndGet();
            if (this.offMainThreads.size() < 16) {
                this.offMainThreads.add(Thread.currentThread().getName());
            }
        }
        return !this.amazake;
    }

    private void onEvent(String kind, String key, Event event) {
        final RaceLedger ledger = this.ledger;
        this.events.incrementAndGet();
        ledger.count(kind);
        ledger.count(kind + ":" + key);
        final int c = this.cursor++;
        ledger.transfer(c, c * 7 + 3, 1 + (c & 15));
        if (event instanceof RaceProbeEvent probe && probe.depth() == 0 && (probe.seq() & 3) == 0) {
            // a plugin calling another plugin's event from inside its own listener (re-entrant dispatch)
            this.fireProbe(1);
        }
    }

    private void fireProbe(int depth) {
        this.probes.incrementAndGet();
        Bukkit.getPluginManager().callEvent(new RaceProbeEvent(depth, this.probeSeq++));
    }

    /** Main-thread scheduler task: probes, transfers and a walk over the event log. */
    private void syncTask() {
        final StressContext ctx = this.ctx;
        final RaceLedger ledger = this.ledger;
        if (ctx == null || ledger == null || this.stopped) {
            return;
        }
        try {
            final int probes = ctx.scaled(this.probesPerTask);
            for (int i = 0; i < probes; i++) {
                this.fireProbe(0);
            }
            final int transfers = ctx.scaled(this.transfersPerTask);
            for (int i = 0; i < transfers; i++) {
                final int c = this.cursor++;
                ledger.transfer(c * 13 + 1, c, 1 + (c % 97));
            }
            this.scanned += ledger.scanLog();
        } catch (final RuntimeException e) {
            ledger.exception(e);
        }
    }

    @Override
    public void tick(long tick) {
        if (this.ctx != null && this.ctx.moduleTick() > 0 && this.ctx.moduleTick() % this.checkInterval == 0) {
            this.check("tick " + tick);
        }
    }

    @Override
    public void onStageChange(Stage stage) {
        this.check("stage " + stage.key());
    }

    private void check(String where) {
        final RaceLedger ledger = this.ledger;
        if (ledger == null) {
            return;
        }
        final RaceLedger.Result result = ledger.check();
        if (!result.ok()) {
            this.plugin.getLogger().warning("Race check failed (" + where + "): " + String.join("; ", result.violated()));
        }
    }

    @Override
    public void stop() {
        if (this.ctx == null || this.ledger == null || this.finalChecked) {
            return;
        }
        this.finalChecked = true;
        try {
            // Unregister first: no dispatch can be in flight in our listeners after this (we run on the thread
            // that owns the plugin's code), so the final check sees a quiescent state.
            HandlerList.unregisterAll(this.listener);
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Cannot unregister the races listeners", e);
        }
        this.stopped = true;
        this.check("stop");
        // English and stable: CI may grep it.
        this.plugin.getLogger().info("Races: " + this.ledger.checks() + " checks, " + this.ledger.violations() + " violations, "
            + this.ledger.exceptions() + " exceptions, " + this.events.get() + " events, " + this.offMainEvents.get() + " off-main");
    }

    @Override
    public String status() {
        final RaceLedger ledger = this.ledger;
        if (ledger == null) {
            return "не запущен";
        }
        return "проверок " + ledger.checks() + ", нарушений " + ledger.violations() + ", исключений " + ledger.exceptions()
            + ", событий " + this.events.get() + " (вне главного потока " + this.offMainEvents.get() + "), переводов "
            + ledger.transfers();
    }

    /**
     * The {@code stress.races} object of the report: {@code checks, invariants, violations, exceptions,
     * concurrentModifications, counted, transfers, details, events, probes, offMainEvents, offMainThreads,
     * listeners}. Null before start.
     */
    public @Nullable Map<String, Object> report() {
        final RaceLedger ledger = this.ledger;
        if (ledger == null) {
            return null;
        }
        final Map<String, Object> out = ledger.report();
        out.put("events", this.events.get());
        out.put("probes", this.probes.get());
        out.put("offMainEvents", this.offMainEvents.get());
        out.put("offMainThreads", List.copyOf(this.offMainThreads));
        out.put("listeners", List.copyOf(this.listened));
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

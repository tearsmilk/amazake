package ink.hoka.amazake.stress.profiler;

import ink.hoka.amazake.stress.ProfilerBridge;
import ink.hoka.amazake.stress.StressPlugin;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * The built-in profiler of {@code /stress profile}: Amazake's profiler collector ported to the public Paper
 * API, so it runs on any Paper fork and produces exactly the report of {@code docs/profiler-format.md}
 * (version 1) plus a top-level {@code stress} block. Reports are uploaded to {@code profiler.upload-url}
 * (default https://mc-ru.org) or saved to {@code plugins/AmazakeStress/profiles/}.
 *
 * <p>At most one measurement at a time; it runs on its own daemon thread "AmazakeStress Profiler" and never
 * blocks the main thread (main-thread reads go through the Bukkit scheduler with a timeout).
 */
public final class StressProfiler implements ProfilerBridge {

    public static final String THREAD_NAME = "AmazakeStress Profiler";
    /** How long plugin disable waits for the report to be written locally. */
    private static final long SHUTDOWN_JOIN_MS = 5000L;

    private final StressPlugin plugin;
    private final TickSampler ticks = new TickSampler();
    private final Messages messages;
    private final ReportOutput output;
    private @Nullable ProfilerSession current;
    private @Nullable Thread thread;
    private volatile @Nullable String lastResult;
    private volatile boolean delivering;

    public StressProfiler(StressPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin.getLogger());
        this.output = new ReportOutput(plugin.getLogger(), this.messages,
            plugin.getDataFolder().toPath().resolve("profiles"), plugin.getPluginMeta().getVersion());
        // Registered once; it only records ticks while a measurement runs.
        plugin.getServer().getPluginManager().registerEvents(this.ticks, plugin);
    }

    Messages messages() {
        return this.messages;
    }

    ReportOutput output() {
        return this.output;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public synchronized boolean isRunning() {
        return this.current != null;
    }

    @Override
    public synchronized void start(CommandSender sender, int requestedSeconds) {
        if (this.current != null) {
            final long left = Math.max(0L, (this.current.endMillis() - System.currentTimeMillis() + 999L) / 1000L);
            this.messages.warn(sender, "Замер уже идёт (запустил " + this.current.sender.getName() + "), закончится через "
                + left + " с. Остановить раньше: /stress profile stop");
            return;
        }
        final ProfilerSettings settings;
        try {
            this.plugin.reloadConfig();
            settings = ProfilerSettings.read(this.plugin.getConfig());
        } catch (final RuntimeException e) {
            this.messages.error(sender, "Ошибка в config.yml (раздел profiler): " + e.getMessage());
            return;
        }
        final int seconds = Math.min(settings.maxSeconds(), Math.max(1, requestedSeconds));
        final ProfilerSession session = new ProfilerSession(this.plugin, this, this.ticks, sender, seconds, settings);
        boolean ticksStarted = false;
        try {
            final Thread worker = new Thread(session, THREAD_NAME);
            worker.setDaemon(true);
            worker.setUncaughtExceptionHandler((t, e) -> this.plugin.getLogger().log(Level.SEVERE, "Uncaught exception in " + t.getName(), e));
            this.current = session;
            this.thread = worker;
            this.ticks.start();
            ticksStarted = true;
            worker.start();
        } catch (final RuntimeException | OutOfMemoryError e) {
            this.current = null;
            this.thread = null;
            if (ticksStarted) {
                this.ticks.stop();
            }
            session.releaseEntityTicks();
            this.messages.error(sender, "Не удалось запустить поток профайлера: " + e);
            return;
        }
        final String target = settings.uploadUrl().isBlank() ? "сохранится в plugins/AmazakeStress/profiles/"
            : "будет отправлен на " + settings.uploadUrl();
        this.messages.info(sender, "Замер запущен на " + seconds + " с (выборка каждые " + settings.intervalMs() + " мс), отчёт "
            + target + ". Остановить раньше: /stress profile stop");
    }

    @Override
    public synchronized void stop(CommandSender sender) {
        if (this.current == null) {
            this.messages.warn(sender, "Сейчас замер не идёт. Запустить: /stress profile [секунды]");
            return;
        }
        this.current.requestStop(false);
        this.messages.info(sender, "Останавливаю замер и готовлю отчёт…");
    }

    @Override
    public String status() {
        final ProfilerSession session;
        synchronized (this) {
            session = this.current;
        }
        if (session != null) {
            final long left = Math.max(0L, (session.endMillis() - System.currentTimeMillis() + 999L) / 1000L);
            final long done = (System.currentTimeMillis() - session.startMillis) / 1000L;
            return "идёт замер: " + done + " с из " + session.seconds + " (осталось " + left + " с)";
        }
        if (this.delivering) {
            return "замер закончен, отчёт отправляется";
        }
        final String last = this.lastResult;
        return last == null ? "не запущен" : "не запущен; последний отчёт: " + last;
    }

    @Override
    public void shutdown() {
        final ProfilerSession session;
        final Thread worker;
        synchronized (this) {
            session = this.current;
            worker = this.thread;
        }
        if (session == null && !this.delivering) {
            return;
        }
        if (session != null) {
            session.requestStop(true); // final state read here, on the main thread; report saved locally only
        }
        if (worker != null) {
            try {
                worker.join(SHUTDOWN_JOIN_MS); // the plugin's classes must still be loadable while the report is written
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (session != null) {
            session.releaseEntityTicks(); // the worker normally did it; never leave the server counting
        }
        this.ticks.stop();
    }

    /** Profiler thread: the measurement is over (the report may still be being delivered). */
    synchronized void finished(final ProfilerSession session, final @Nullable String result) {
        if (this.current == session) {
            this.current = null;
            this.delivering = true;
        }
        if (result != null) {
            this.lastResult = result;
        }
    }

    /** Profiler thread: the report was uploaded (link) or saved (path), or null on failure. */
    void delivered(final @Nullable String result) {
        if (result != null) {
            this.lastResult = result;
        }
        this.delivering = false;
    }
}

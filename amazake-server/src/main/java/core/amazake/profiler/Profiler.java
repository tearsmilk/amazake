package core.amazake.profiler;

import core.amazake.AmazakeConfig;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;

/** Entry point of /amazake profile: at most one measurement runs at a time. */
public final class Profiler {

    public static final String THREAD_NAME = "Amazake Profiler";
    static final Logger LOGGER = Logger.getLogger("Amazake");

    private static ProfilerSession current;

    private Profiler() {
    }

    /** Starts a measurement of {@code seconds} (clamped to the config's maximum), or says why not. */
    public static synchronized void start(final CommandSender sender, final int requestedSeconds) {
        if (current != null) {
            final long left = Math.max(0L, (current.endMillis() - System.currentTimeMillis() + 999L) / 1000L);
            Messages.warn(sender, "Замер уже идёт (запустил " + current.sender.getName() + "), закончится через "
                + left + " с. Остановить раньше: /amazake profile stop");
            return;
        }
        final int max = AmazakeConfig.profilerMaxSeconds;
        final int seconds = Math.min(max, Math.max(1, requestedSeconds));
        if (requestedSeconds > max) {
            Messages.warn(sender, "Больше " + max + " с нельзя (profiler.max-seconds в amazake.yml): замер будет " + max + " с.");
        }
        final int intervalMs = Math.max(250, AmazakeConfig.profilerIntervalMs);
        final ProfilerSession session = new ProfilerSession(sender, seconds, intervalMs);
        final Thread thread = new Thread(session, THREAD_NAME);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> LOGGER.log(java.util.logging.Level.SEVERE, "Uncaught exception in " + t.getName(), e));
        current = session;
        try {
            thread.start();
        } catch (final RuntimeException | OutOfMemoryError e) {
            current = null;
            Messages.error(sender, "Не удалось запустить поток профайлера: " + e);
            return;
        }
        Messages.info(sender, "Замер запущен на " + seconds + " с (выборка каждые " + intervalMs + " мс). "
            + "Остановить раньше: /amazake profile stop");
    }

    /** Ends the running measurement early; its report is made as usual. */
    public static synchronized void stop(final CommandSender sender) {
        if (current == null) {
            Messages.warn(sender, "Сейчас замер не идёт. Запустить: /amazake profile [секунды]");
            return;
        }
        current.requestStop();
        Messages.info(sender, "Останавливаю замер и готовлю отчёт…");
    }

    public static synchronized boolean running() {
        return current != null;
    }

    static synchronized void finished(final ProfilerSession session) {
        if (current == session) {
            current = null;
        }
    }
}

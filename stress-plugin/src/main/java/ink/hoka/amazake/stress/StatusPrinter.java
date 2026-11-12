package ink.hoka.amazake.stress;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/** Builds the /stress status output, the periodic console line and the end-of-run summary. */
public final class StatusPrinter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private StatusPrinter() {
    }

    static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** "TPS 19.9 (1м 20.0) · MSPT 12.3 (макс 40.1)" for the last second. */
    public static String perfLine(StressPlugin plugin) {
        final TickMonitor monitor = plugin.tickMonitor();
        final double[] tps = Bukkit.getTPS();
        return "TPS " + f1(monitor.tps(20)) + " (1м " + f2(tps.length > 0 ? tps[0] : 0.0) + ")"
            + " · MSPT " + f1(monitor.averageMspt(20)) + " (макс " + f1(monitor.maxMspt(20))
            + ", ср. 100т " + f1(Bukkit.getAverageTickTime()) + ")";
    }

    /** Compact one-line status for the console log during a run. */
    public static String shortLine(StressPlugin plugin, StageRunner run) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[').append(run.stage().key()).append("] t=").append(run.tick() / 20).append('/')
            .append(run.totalTicks() / 20).append("s ").append(perfLine(plugin))
            .append(" · entities ").append(run.world().getEntityCount())
            .append(" · chunks ").append(run.world().getLoadedChunks().length);
        for (final StageRunner.Handle handle : run.handles()) {
            sb.append(" | ").append(handle.id()).append(": ").append(moduleStatus(handle));
        }
        return sb.toString();
    }

    static String moduleStatus(StageRunner.Handle handle) {
        if (handle.failed()) {
            return "ОШИБКА " + handle.failure();
        }
        try {
            return handle.module().status();
        } catch (final Throwable t) {
            return "status() упал: " + t;
        }
    }

    /** Full /stress status output. */
    public static void sendStatus(StressPlugin plugin, CommandSender to) {
        final StageRunner run = plugin.currentRun();
        Msg.info(to, "Статус AmazakeStress " + plugin.getPluginMeta().getVersion());
        Msg.plain(to, "  Сервер: " + Bukkit.getName() + " " + Bukkit.getMinecraftVersion(), NamedTextColor.GRAY);
        Msg.plain(to, "  " + perfLine(plugin), NamedTextColor.WHITE);
        final World world = plugin.testWorld().loaded();
        if (world == null) {
            Msg.plain(to, "  Мир " + TestWorld.NAME + ": не загружен", NamedTextColor.GRAY);
        } else {
            Msg.plain(to, "  Мир " + TestWorld.NAME + ": сущностей " + world.getEntityCount() + ", чанков "
                + world.getLoadedChunks().length + ", сид " + world.getSeed(), NamedTextColor.GRAY);
        }
        if (plugin.isPreparing()) {
            Msg.plain(to, "  Подготовка мира к запуску...", NamedTextColor.YELLOW);
        }
        if (run == null) {
            Msg.plain(to, "  Прогон не запущен. /stress start [all|модуль] [секунды]", NamedTextColor.GRAY);
        } else {
            final String mode = run.isSingleMode() ? "модуль " + run.mode() : "все модули";
            Msg.plain(to, "  Прогон: " + mode + ", t=" + run.tick() / 20 + "/" + run.totalTicks() / 20
                + " с (тик " + run.tick() + "/" + run.totalTicks() + ")", NamedTextColor.WHITE);
            Msg.plain(to, "  Этап: " + run.stage().display() + stageRemaining(run), NamedTextColor.AQUA);
            Msg.plain(to, "  Чанков с тикетами: " + run.tickets().heldCount() + ", блоков изменено: "
                + run.blocks().changedBlocks(), NamedTextColor.GRAY);
            if (run.handles().isEmpty()) {
                Msg.plain(to, "  Модули ещё не запущены (базовая линия)", NamedTextColor.GRAY);
            }
            for (final StageRunner.Handle handle : run.handles()) {
                Msg.plain(to, "  • " + handle.id() + ": " + moduleStatus(handle) + " (сущностей "
                        + handle.context().entities().aliveCount() + ")",
                    handle.failed() ? NamedTextColor.RED : NamedTextColor.GREEN);
            }
        }
        Msg.plain(to, "  Профайлер: " + safeProfilerStatus(plugin), NamedTextColor.GRAY);
    }

    private static String stageRemaining(StageRunner run) {
        if (run.isSingleMode()) {
            return "";
        }
        final long end = run.stageEnd(Math.max(0, run.stage().index()));
        return ", до следующего этапа " + Math.max(0, (end - run.tick()) / 20) + " с";
    }

    private static String safeProfilerStatus(StressPlugin plugin) {
        try {
            return plugin.profiler().status();
        } catch (final Throwable t) {
            return "ошибка: " + t;
        }
    }

    /** End-of-run summary (plain text, also saved to plugins/AmazakeStress/results). */
    public static List<String> summary(StressPlugin plugin, StageRunner run, boolean completed, @Nullable String reason) {
        final List<String> lines = new ArrayList<>();
        final Runtime rt = Runtime.getRuntime();
        lines.add("AmazakeStress " + plugin.getPluginMeta().getVersion() + " — итог прогона");
        lines.add("Сервер: " + Bukkit.getName() + " " + Bukkit.getVersion() + " (MC " + Bukkit.getMinecraftVersion() + ")");
        lines.add("Java: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
            + ", ядер: " + ink.hoka.amazake.stress.profiler.Cpus.logical() + ", Xmx: " + (rt.maxMemory() >> 20) + " МБ");
        lines.add("JVM-флаги: " + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        lines.add("Начало: " + TIME.format(Instant.ofEpochMilli(run.startedAtMillis())) + ", режим: "
            + (run.isSingleMode() ? "модуль " + run.mode() : "all") + ", сид: " + run.config().seed()
            + ", длительность: " + run.totalTicks() / 20 + " с (" + run.totalTicks() + " тиков)");
        lines.add("Завершение: " + (completed ? "полностью" : "досрочно") + (reason != null ? " (" + reason + ")" : "")
            + ", выполнено тиков: " + run.tick());
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%-10s %6s %7s %6s %8s %8s %8s %8s %6s",
            "этап", "тиков", "сек", "TPS", "MSPT ср", "p95", "p99", "макс", ">50мс"));
        for (final StageRunner.StageStats s : run.stats()) {
            lines.add(String.format(Locale.ROOT, "%-10s %6d %7.1f %6.2f %8.2f %8.2f %8.2f %8.2f %6d",
                s.stage().key(), s.ticks(), s.seconds(), s.tps(), s.avgMspt(), s.percentile(95), s.percentile(99),
                s.maxMspt(), s.lagTicks()));
        }
        lines.add("");
        for (final StageRunner.Handle handle : run.handles()) {
            lines.add("Модуль " + handle.id() + ": " + moduleStatus(handle));
        }
        return lines;
    }
}

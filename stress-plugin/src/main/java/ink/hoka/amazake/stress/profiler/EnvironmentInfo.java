package ink.hoka.amazake.stress.profiler;

import io.papermc.paper.ServerBuildInfo;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The "server" and "system" blocks of the report: what runs, and on what. Public Bukkit/Paper API only, so
 * it works on any Paper fork: {@code brand} is {@link Bukkit#getName()} ("Paper", "Leaf", "Amazake", ...),
 * {@code version} is the VERSION_SIMPLE build string of {@link ServerBuildInfo}.
 */
final class EnvironmentInfo {

    private static final String HIDDEN = "<hidden>";

    private EnvironmentInfo() {
    }

    /** Read on the main thread when the measurement starts. */
    record Start(String brand, String version, String minecraftVersion, int onlinePlayers, int maxPlayers,
                 List<Report.Plugin> plugins) {
        static Start capture() {
            final List<Report.Plugin> plugins = new ArrayList<>();
            for (final org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                plugins.add(new Report.Plugin(plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled()));
            }
            return new Start(Bukkit.getName(), buildVersion(), Bukkit.getMinecraftVersion(),
                Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), List.copyOf(plugins));
        }
    }

    /**
     * {@code server.version} as the format asks: the short build string ({@link ServerBuildInfo} VERSION_SIMPLE,
     * e.g. {@code 1.21.11-7-b3a4d61}), which every Paper fork provides; {@link Bukkit#getVersion()} if it fails.
     */
    private static String buildVersion() {
        try {
            final String simple = ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE);
            if (simple != null && !simple.isBlank()) {
                return simple;
            }
        } catch (final RuntimeException | LinkageError ignored) {
            // a fork without a build info service: fall back below
        }
        return Bukkit.getVersion();
    }

    static Report.Server server(final Start start, final List<Report.World> worlds) {
        return new Report.Server(
            start.brand(),
            start.version(),
            start.minecraftVersion(),
            System.getProperty("java.version", ""),
            System.getProperty("java.vendor", ""),
            jvmArgs(),
            start.onlinePlayers(),
            start.maxPlayers(),
            start.plugins(),
            worlds,
            threadPools()
        );
    }

    static Report.SystemInfo system(final boolean perCore, final String perThreadSource) {
        Long totalMemory = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            final long total = os.getTotalMemorySize();
            totalMemory = total > 0L ? total : null;
        }
        return new Report.SystemInfo(
            System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
            System.getProperty("os.arch", ""),
            Cpus.logical(),
            cpuModel(),
            totalMemory,
            Runtime.getRuntime().maxMemory(),
            perCore,
            perThreadSource
        );
    }

    private static List<String> jvmArgs() {
        final List<String> out = new ArrayList<>();
        for (final String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            final String lower = arg.toLowerCase(Locale.ROOT);
            final boolean secret = lower.contains("password") || lower.contains("token") || lower.contains("secret");
            out.add(secret ? HIDDEN : arg);
        }
        return out;
    }

    /**
     * Best effort without server internals. Chunk workers and I/O: the live "... Common Worker #N" /
     * "... I/O Worker #N" threads (Moonrise names, used by Paper and its forks); if none are alive, the
     * {@code chunk-system} values of {@code config/paper-global.yml} when they are positive (the default
     * {@code -1} means "chosen by the server" and is reported as 0 workers / 1 I/O thread). Netty:
     * {@code io.netty.eventLoopThreads}, else Netty's default (2 x cores). A fork that renames its threads
     * reports 0 here; its threads still appear in {@code threads[]} under their own names.
     */
    private static Report.ThreadPools threadPools() {
        int workers = 0;
        int io = 0;
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        for (final ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds())) {
            if (info == null) {
                continue;
            }
            final String group = ThreadGroups.classify(info.getThreadName());
            if (ThreadGroups.CHUNK_WORKERS.equals(group)) {
                workers++;
            } else if (ThreadGroups.CHUNK_IO.equals(group)) {
                io++;
            }
        }
        if (workers == 0 || io == 0) {
            final File paperGlobal = new File("config", "paper-global.yml");
            if (paperGlobal.isFile()) {
                try {
                    final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(paperGlobal);
                    if (workers == 0) {
                        workers = Math.max(0, yaml.getInt("chunk-system.worker-threads", -1));
                    }
                    if (io == 0) {
                        io = Math.max(1, yaml.getInt("chunk-system.io-threads", -1));
                    }
                } catch (final RuntimeException ignored) {
                    // best effort
                }
            }
        }
        final int netty = Integer.getInteger("io.netty.eventLoopThreads", Cpus.logical() * 2);
        return new Report.ThreadPools(workers, io, netty);
    }

    private static String cpuModel() {
        final Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuinfo)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(cpuinfo)) {
            return lines
                .filter(line -> line.startsWith("model name"))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .filter(name -> !name.isEmpty())
                .findFirst()
                .orElse(null);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }
}

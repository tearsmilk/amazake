package core.amazake.profiler;

import core.amazake.Amazake;
import core.amazake.cpu.CoreLayout;
import core.amazake.cpu.CpuTopology;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.configuration.GlobalConfiguration;
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

/** The "server" and "system" blocks of the report: what runs, and on what. */
final class EnvironmentInfo {

    private static final String HIDDEN = "<hidden>";

    private EnvironmentInfo() {
    }

    /** Read on the command's thread when the measurement starts. */
    record Start(int onlinePlayers, int maxPlayers, List<Report.Plugin> plugins) {
        static Start capture() {
            final List<Report.Plugin> plugins = new ArrayList<>();
            for (final org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                plugins.add(new Report.Plugin(plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled()));
            }
            return new Start(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), List.copyOf(plugins));
        }
    }

    static Report.Server server(final Start start, final List<Report.World> worlds) {
        final ServerBuildInfo build = ServerBuildInfo.buildInfo();
        return new Report.Server(
            Amazake.NAME,
            build.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE),
            build.minecraftVersionName(),
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

    /**
     * @param coreIds the CPU id of each entry of samples[].cores (see {@link CpuSampler#coreIds()}),
     *                cut to the length the samples have; null when there is no per-core data
     */
    static Report.SystemInfo system(final boolean perCore, final String perThreadSource, final int[] coreIds) {
        Long totalMemory = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            final long total = os.getTotalMemorySize();
            totalMemory = total > 0L ? total : null;
        }
        return new Report.SystemInfo(
            System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""),
            System.getProperty("os.arch", ""),
            CpuTopology.availableProcessors(),
            cpuModel(),
            totalMemory,
            Runtime.getRuntime().maxMemory(),
            perCore,
            perThreadSource,
            perCore ? coreRoles(coreIds) : null
        );
    }

    /** The core layout's role of each reported core, or null when no layout is active. */
    private static List<String> coreRoles(final int[] coreIds) {
        if (coreIds == null || coreIds.length == 0) {
            return null;
        }
        try {
            final CoreLayout layout = CoreLayout.current();
            if (layout == null) {
                return null;
            }
            final List<String> roles = new ArrayList<>(coreIds.length);
            for (final int id : coreIds) {
                roles.add(layout.roleIds(id)); // "chunks+gc" when a core has several roles
            }
            return List.copyOf(roles);
        } catch (final RuntimeException e) {
            Profiler.LOGGER.warning("Profiler could not read the core layout: " + e);
            return null;
        }
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
     * Chunk worker and I/O pools: the live "... Common Worker #N" / "... I/O Worker #N" threads
     * (MoonriseCommon's pools expose no size getter), else paper-global.yml's chunk-system values.
     * Netty: io.netty.eventLoopThreads, which Spigot sets from its config, else Netty's default.
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
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config != null && config.chunkSystem != null) {
            if (workers == 0) {
                workers = Math.max(0, config.chunkSystem.workerThreads);
            }
            if (io == 0) {
                io = Math.max(1, config.chunkSystem.ioThreads);
            }
        }
        final int netty = Integer.getInteger("io.netty.eventLoopThreads", CpuTopology.availableProcessors() * 2);
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

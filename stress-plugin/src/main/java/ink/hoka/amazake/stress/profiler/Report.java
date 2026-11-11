package ink.hoka.amazake.stress.profiler;

import java.util.List;
import java.util.Map;

/**
 * The report of docs/profiler-format.md (version 1), shaped so Gson writes it field for field.
 * Gson leaves out null fields: that is how optional fields are absent.
 *
 * <p>Ported from Amazake's profiler; the only addition is the top-level {@code stress} object (scenario,
 * stage timeline, per-stage results), which readers of format version 1 ignore and the site stores as is.
 */
record Report(
    String format,
    int version,
    String createdAt,
    long durationMs,
    int intervalMs,
    Server server,
    SystemInfo system,
    List<Sample> samples,
    List<ThreadEntry> threads,
    List<GroupEntry> groups,
    List<LagSpike> lagSpikes,
    Map<String, Object> stress
) {

    static final String FORMAT = "amazake-profile";
    static final int VERSION = 1;

    record Server(
        String brand,
        String version,
        String minecraftVersion,
        String javaVersion,
        String javaVendor,
        List<String> jvmArgs,
        int onlinePlayers,
        int maxPlayers,
        List<Plugin> plugins,
        List<World> worlds,
        ThreadPools threadPools
    ) {
    }

    record Plugin(String name, String version, boolean enabled) {
    }

    record World(String name, String environment, int loadedChunks, int entities, int tileEntities, int players) {
    }

    record ThreadPools(int chunkWorkers, int chunkIo, int netty) {
    }

    record SystemInfo(
        String os,
        String arch,
        int logicalCores,
        String cpuModel,
        Long totalMemoryBytes,
        long heapMaxBytes,
        boolean perCore,
        String perThreadSource
    ) {
    }

    record Sample(
        long t,
        double[] cores,
        double systemCpu,
        double processCpu,
        double tps,
        double msptAvg,
        double msptMax,
        long heapUsedBytes,
        int gcCount,
        int gcTimeMs,
        int players,
        Integer entities,
        Integer loadedChunks
    ) {
    }

    record ThreadEntry(String name, String group, long cpuMs, double avgCores, double[] series) {
    }

    record GroupEntry(String group, int threads, long cpuMs, double avgCores, double peakCores, double[] series) {
    }

    record LagSpike(long t, double ms) {
    }
}

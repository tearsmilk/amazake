package core.amazake.stress.profiler;

import core.amazake.stress.ModuleRegistry;
import core.amazake.stress.Stage;
import core.amazake.stress.StageRunner;
import core.amazake.stress.StressConfig;
import core.amazake.stress.StressPlugin;
import core.amazake.stress.races.RacesModule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The extra top-level {@code "stress"} object of the report (readers of format version 1 ignore unknown
 * fields; the site stores the whole object). Built on the main thread, because it reads the run's state.
 *
 * <pre>
 * "stress": {
 *   "plugin": "AmazakeStress 1.0.0",
 *   "scenario": { "mode", "seed", "worldType", "generatorSettings", "totalSeconds", "totalTicks",
 *                 "stageSeconds", "minMaxStageSeconds", "maxStageMultiplier", "modules": { id: {config...} } } | null,
 *   "stageTimeline": [ { "index", "stage", "title", "startTick", "endTick", "startSec", "endSec" } ],
 *   "startedAtTick": server tick (Bukkit.getCurrentTick) when the run started | null,
 *   "profileStartedAtTick": server tick when this measurement started,
 *   "runOffsetMs": ms from the start of the measurement to the start of the run (negative: run started earlier) | null,
 *   "runTicks": ticks the run completed so far | null,
 *   "finished": bool, "endReason": string | null,
 *   "stageResults": [ { "stage", "ticks", "seconds", "tps", "msptAvg", "msptP95", "msptP99", "msptMax", "lagTicks",
 *                       "entities" (optional) } ],
 *   "modules": [ { "id", "failed", "failure", "status" } ],
 *   "races": { "checks", "invariants", "violations", "exceptions", "concurrentModifications", "counted", "transfers",
 *              "details", "events", "probes", "offMainEvents", "offMainThreads", "listeners" } (only if the races module ran)
 * }
 * </pre>
 *
 * <p>{@code stageResults[].entities} is the report's {@code entities} object (docs/profiler-format.md) for that
 * stage only, top 15 types: present on Amazake (server class {@code core.amazake.tick.EntityTickStats},
 * reached through {@link core.amazake.stress.EntityTickBridge}) when the profiler measured the whole stage
 * (for the stage still running: from its start until now). Absent on other servers (Leaf, Paper).
 */
final class StressInfo {

    /** Entity types per stage result (the top-level {@code entities} keeps 40). */
    static final int STAGE_TOP_TYPES = 15;

    private StressInfo() {
    }

    /** Main thread only. {@code run} may be null when no run was seen during the measurement. */
    static Map<String, Object> build(final StressPlugin plugin, final @Nullable StageRunner run,
                                     final @Nullable Integer runStartTick, final int profileStartTick,
                                     final long profileStartMillis) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("plugin", "AmazakeStress " + plugin.getPluginMeta().getVersion());
        if (run == null) {
            out.put("scenario", null);
            out.put("stageTimeline", List.of());
            out.put("startedAtTick", null);
            out.put("profileStartedAtTick", profileStartTick);
            return out;
        }
        final StressConfig config = run.config();
        final Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("mode", run.mode());
        scenario.put("seed", config.seed());
        scenario.put("worldType", config.worldType().name());
        scenario.put("generatorSettings", config.generatorSettings());
        scenario.put("buildY", run.baseY());
        scenario.put("totalSeconds", run.totalTicks() / 20L);
        scenario.put("totalTicks", run.totalTicks());
        final List<Integer> stageSeconds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            stageSeconds.add(config.stageSeconds(i));
        }
        scenario.put("stageSeconds", stageSeconds);
        scenario.put("minMaxStageSeconds", config.minMaxStageSeconds());
        scenario.put("maxStageMultiplier", config.maxStageMultiplier());
        scenario.put("gamerules", new LinkedHashMap<>(config.gamerules()));
        final Map<String, Object> modules = new LinkedHashMap<>();
        for (final ModuleRegistry.Entry entry : plugin.modules().all()) {
            if (run.isSingleMode() ? !entry.id().equals(run.mode()) : !config.moduleEnabled(entry.id())) {
                continue;
            }
            final Map<String, Object> values = toMap(config.moduleSection(entry.id()));
            values.put("_stage", entry.stage().key());
            modules.put(entry.id(), values);
        }
        scenario.put("modules", modules);
        out.put("scenario", scenario);

        final List<Map<String, Object>> timeline = new ArrayList<>();
        if (run.isSingleMode()) {
            timeline.add(stage(-1, Stage.SINGLE, 0L, run.totalTicks()));
        } else {
            long start = 0L;
            for (int i = 0; i < Stage.TIMELINE.length; i++) {
                final long end = run.stageEnd(i);
                timeline.add(stage(i, Stage.TIMELINE[i], start, end));
                start = end;
            }
        }
        out.put("stageTimeline", timeline);
        out.put("startedAtTick", runStartTick);
        out.put("profileStartedAtTick", profileStartTick);
        out.put("runOffsetMs", run.startedAtMillis() - profileStartMillis);
        out.put("runTicks", run.tick());
        out.put("finished", run.isFinished());
        out.put("endReason", run.endReason());

        final List<Map<String, Object>> results = new ArrayList<>();
        for (final StageRunner.StageStats stats : run.stats()) {
            final Map<String, Object> s = new LinkedHashMap<>();
            s.put("stage", stats.stage().key());
            s.put("ticks", stats.ticks());
            s.put("seconds", round(stats.seconds()));
            s.put("tps", round(stats.tps()));
            s.put("msptAvg", round(stats.avgMspt()));
            s.put("msptP95", round(stats.percentile(95)));
            s.put("msptP99", round(stats.percentile(99)));
            s.put("msptMax", round(stats.maxMspt()));
            s.put("lagTicks", stats.lagTicks());
            final Map<String, Object> entities = stats.entities(plugin.entityTicks(), STAGE_TOP_TYPES);
            if (entities != null) {
                s.put("entities", entities);
            }
            results.add(s);
        }
        out.put("stageResults", results);

        final List<Map<String, Object>> handles = new ArrayList<>();
        Map<String, Object> races = null;
        for (final StageRunner.Handle handle : run.handles()) {
            final Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", handle.id());
            h.put("failed", handle.failed());
            h.put("failure", handle.failure());
            String status;
            try {
                status = handle.module().status();
            } catch (final RuntimeException e) {
                status = "status failed: " + e;
            }
            h.put("status", status);
            handles.add(h);
            if (handle.module() instanceof RacesModule r) {
                races = r.report();
            }
        }
        out.put("modules", handles);
        if (races != null) {
            out.put("races", races);
        }
        return out;
    }

    private static Map<String, Object> stage(final int index, final Stage stage, final long startTick, final long endTick) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("stage", stage.key());
        m.put("title", stage.title());
        m.put("startTick", startTick);
        m.put("endTick", endTick);
        m.put("startSec", startTick / 20.0);
        m.put("endSec", endTick / 20.0);
        return m;
    }

    /** A config section as plain maps/lists/scalars (Gson-friendly), keys in file order. */
    static Map<String, Object> toMap(final ConfigurationSection section) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String key : section.getKeys(false)) {
            out.put(key, plain(section.get(key)));
        }
        return out;
    }

    private static @Nullable Object plain(final @Nullable Object value) {
        if (value instanceof ConfigurationSection child) {
            return toMap(child);
        }
        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>(list.size());
            for (final Object item : list) {
                copy.add(plain(item));
            }
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), plain(v)));
            return copy;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String || value == null) {
            if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
                return 0.0;
            }
            return value;
        }
        return String.valueOf(value);
    }

    private static double round(final double value) {
        return ProfilerSession.round(value);
    }
}

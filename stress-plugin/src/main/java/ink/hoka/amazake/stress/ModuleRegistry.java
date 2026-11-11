package ink.hoka.amazake.stress;

import ink.hoka.amazake.stress.async.AsyncLoadModule;
import ink.hoka.amazake.stress.chunks.ChunksModule;
import ink.hoka.amazake.stress.mobs.MobsModule;
import ink.hoka.amazake.stress.packets.PacketsModule;
import ink.hoka.amazake.stress.physics.PhysicsModule;
import ink.hoka.amazake.stress.redstone.RedstoneModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Module ids, the stage each one starts on, its fixed region lane and its factory.
 * Registration order = lane index = start order within a stage.
 */
public final class ModuleRegistry {

    /** A registered module. {@code lane} is the fixed {@link RegionAllocator} lane of this id. */
    public record Entry(String id, Stage stage, int lane, Function<StressPlugin, LoadModule> factory) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** The built-in modules. Ids, stages and lanes are part of the benchmark: do not reorder. */
    public static ModuleRegistry createDefault() {
        final ModuleRegistry registry = new ModuleRegistry();
        registry.register("mobs", Stage.MOBS, MobsModule::new);
        registry.register("physics", Stage.PHYSICS, PhysicsModule::new);
        registry.register("redstone", Stage.REDSTONE, RedstoneModule::new);
        registry.register("chunks", Stage.CHUNKS, ChunksModule::new);
        registry.register("packets", Stage.PACKETS, PacketsModule::new);
        registry.register("async", Stage.PACKETS, AsyncLoadModule::new);
        return registry;
    }

    public void register(String id, Stage stage, Function<StressPlugin, LoadModule> factory) {
        if (this.entries.containsKey(id)) {
            throw new IllegalArgumentException("duplicate module id " + id);
        }
        this.entries.put(id, new Entry(id, stage, this.entries.size(), factory));
    }

    public @Nullable Entry get(String id) {
        return this.entries.get(id);
    }

    /** All ids in registration order. */
    public List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(this.entries.keySet()));
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(this.entries.values()));
    }

    /** Modules that start on the given stage, in registration order. */
    public List<Entry> forStage(Stage stage) {
        final List<Entry> result = new ArrayList<>();
        for (final Entry entry : this.entries.values()) {
            if (entry.stage() == stage) {
                result.add(entry);
            }
        }
        return result;
    }
}

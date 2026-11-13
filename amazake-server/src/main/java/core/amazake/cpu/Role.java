package core.amazake.cpu;

import java.util.Locale;

/** A job a set of CPUs is given in the core layout; the config name is {@code amazake.yml cpu.layout.<id>}. */
public enum Role {
    /** The main thread ("Server thread"). */
    TICK("tick", "главный поток (тики)"),
    /** Moonrise chunk workers and chunk I/O. */
    CHUNKS("chunks", "чанки: генерация, загрузка, свет, I/O"),
    /** Netty: network IO, compression, encryption. */
    NETWORK("network", "сеть (Netty)"),
    /** Garbage collector, JIT compilers and the JVM's own threads. */
    GC("gc", "GC, JIT и потоки JVM"),
    /**
     * Plugins' work off the main thread: the scheduler's async pool, async chat and tab completion, the
     * pools that run plugins' async login listeners, and threads plugins create themselves. Their world
     * code (sync tasks, sync events, commands) stays on the main thread as in Bukkit.
     */
    PLUGINS("plugins", "плагины: асинхронные задачи, чат, свои потоки плагинов"),
    /** Support: Paper's own background threads, spark, Amazake; plugin threads spill here with priority. */
    BACKGROUND("background", "поддержка: фон Paper, spark, Amazake; сюда же с приоритетом переливаются потоки плагинов");

    private final String id;
    private final String descriptionRu;

    Role(final String id, final String descriptionRu) {
        this.id = id;
        this.descriptionRu = descriptionRu;
    }

    /** "tick", "chunks", "network", "gc", "plugins", "background". */
    public String id() {
        return this.id;
    }

    public String descriptionRu() {
        return this.descriptionRu;
    }

    /** The role with this config name, or null. */
    public static Role byId(final String id) {
        if (id == null) {
            return null;
        }
        final String key = id.trim().toLowerCase(Locale.ROOT);
        for (final Role role : values()) {
            if (role.id.equals(key)) {
                return role;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return this.id;
    }
}

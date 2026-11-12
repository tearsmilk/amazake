package ink.hoka.amazake.cpu;

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
    /** Every other thread: plugins, chat, Paper's async pools, spark... */
    BACKGROUND("background", "всё остальное: плагины, чат, фоновые задачи");

    private final String id;
    private final String descriptionRu;

    Role(final String id, final String descriptionRu) {
        this.id = id;
        this.descriptionRu = descriptionRu;
    }

    /** "tick", "chunks", "network", "gc", "background". */
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

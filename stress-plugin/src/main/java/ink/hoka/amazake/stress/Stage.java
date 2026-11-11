package ink.hoka.amazake.stress;

/**
 * Stages of the timeline. Loads that start on a stage stay active until the end of the run.
 * The modules that start on each stage are defined in {@link ModuleRegistry}.
 */
public enum Stage {
    /** Pseudo-stage used for {@code /stress start <module>}: one module, no timeline. */
    SINGLE(-1, "single", "одиночный модуль"),
    BASELINE(0, "baseline", "базовая линия (без нагрузки)"),
    MOBS(1, "mobs", "мобы"),
    PHYSICS(2, "physics", "физика"),
    REDSTONE(3, "redstone", "редстоун"),
    CHUNKS(4, "chunks", "чанки"),
    PACKETS(5, "packets", "пакеты + асинхронная нагрузка"),
    MAX(6, "max", "всё на максимуме");

    /** Timeline stages in order (without {@link #SINGLE}). */
    public static final Stage[] TIMELINE = {BASELINE, MOBS, PHYSICS, REDSTONE, CHUNKS, PACKETS, MAX};

    private final int index;
    private final String key;
    private final String title;

    Stage(int index, String key, String title) {
        this.index = index;
        this.key = key;
        this.title = title;
    }

    /** Stage number 0..6 ({@code -1} for {@link #SINGLE}). */
    public int index() {
        return this.index;
    }

    /** Config key under {@code timeline.stages} (MAX and SINGLE have none). */
    public String key() {
        return this.key;
    }

    /** Russian, user-facing title. */
    public String title() {
        return this.title;
    }

    public String display() {
        return this.index < 0 ? this.title : this.index + " " + this.key + " (" + this.title + ")";
    }
}

package core.amazake.core.modules.holograms;

import java.util.List;

/**
 * Неизменяемый снимок одной голограммы: где стоит, каким движком рисуется и какие строки (MiniMessage) показывает.
 * Общий между потоками как есть; изменение — это новый экземпляр, подменяемый в {@code ConcurrentHashMap} целиком
 * (см. docs/systems.md, п.6). {@code item} используется только движком {@link HologramEngine#ITEM_DISPLAY}.
 *
 * @param name   имя-ключ (нижний регистр, {@code [a-z0-9_-]}, до 32 символов)
 * @param world  имя мира
 * @param x      координата X
 * @param y      координата Y (верх голограммы)
 * @param z      координата Z
 * @param engine движок отрисовки
 * @param item   материал предмета для {@link HologramEngine#ITEM_DISPLAY} (может быть {@code null})
 * @param lines  строки MiniMessage сверху вниз (неизменяемый список)
 */
public record Hologram(String name, String world, double x, double y, double z,
                       HologramEngine engine, String item, List<String> lines) {

    public Hologram {
        lines = List.copyOf(lines);
    }

    public Hologram withLines(final List<String> newLines) {
        return new Hologram(name, world, x, y, z, engine, item, newLines);
    }

    public Hologram withEngine(final HologramEngine newEngine) {
        return new Hologram(name, world, x, y, z, newEngine, item, lines);
    }

    public Hologram withItem(final String newItem) {
        return new Hologram(name, world, x, y, z, engine, newItem, lines);
    }

    public Hologram withLocation(final String newWorld, final double nx, final double ny, final double nz) {
        return new Hologram(name, newWorld, nx, ny, nz, engine, item, lines);
    }
}

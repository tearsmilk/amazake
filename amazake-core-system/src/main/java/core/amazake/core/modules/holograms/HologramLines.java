package core.amazake.core.modules.holograms;

import java.util.ArrayList;
import java.util.List;

/**
 * Чистые операции над строками голограммы. Индексы для игрока — с единицы (как в командах {@code setline}/
 * {@code removeline}); внутри списка — с нуля. Операции не мутируют вход и возвращают новый неизменяемый список,
 * поэтому их легко проверить юнит-тестом без сервера.
 */
public final class HologramLines {

    private HologramLines() {
    }

    /** Корректен ли индекс {@code 1..size} для показа/замены/удаления существующей строки. */
    public static boolean validIndex(final List<String> lines, final int oneBased) {
        return oneBased >= 1 && oneBased <= lines.size();
    }

    /** Добавляет строку в конец. */
    public static List<String> added(final List<String> lines, final String content) {
        final List<String> out = new ArrayList<>(lines);
        out.add(content);
        return List.copyOf(out);
    }

    /**
     * Заменяет строку под индексом {@code oneBased} (с единицы).
     *
     * @throws IndexOutOfBoundsException если индекс вне {@code 1..size}
     */
    public static List<String> set(final List<String> lines, final int oneBased, final String content) {
        if (!validIndex(lines, oneBased)) {
            throw new IndexOutOfBoundsException("строка " + oneBased + " вне диапазона 1.." + lines.size());
        }
        final List<String> out = new ArrayList<>(lines);
        out.set(oneBased - 1, content);
        return List.copyOf(out);
    }

    /**
     * Удаляет строку под индексом {@code oneBased} (с единицы), остальные сдвигаются вверх.
     *
     * @throws IndexOutOfBoundsException если индекс вне {@code 1..size}
     */
    public static List<String> removed(final List<String> lines, final int oneBased) {
        if (!validIndex(lines, oneBased)) {
            throw new IndexOutOfBoundsException("строка " + oneBased + " вне диапазона 1.." + lines.size());
        }
        final List<String> out = new ArrayList<>(lines);
        out.remove(oneBased - 1);
        return List.copyOf(out);
    }
}

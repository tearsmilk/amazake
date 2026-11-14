package core.amazake.core.modules.holograms;

import java.util.Locale;

/**
 * Способ отрисовки голограммы. У каждого движка свой набор сущностей:
 * <ul>
 *   <li>{@link #ARMOR_STAND} — по невидимой «маркер»-подставке на строку, имена видимы, стопкой вниз;</li>
 *   <li>{@link #TEXT_DISPLAY} — одна сущность TextDisplay со всеми строками через перевод строки;</li>
 *   <li>{@link #ITEM_DISPLAY} — парящий ItemDisplay с предметом над необязательным TextDisplay.</li>
 * </ul>
 * Чистый перечисление без зависимостей от сервера — разбирается юнит-тестом.
 */
public enum HologramEngine {

    ARMOR_STAND("armor_stand"),
    TEXT_DISPLAY("text_display"),
    ITEM_DISPLAY("item_display");

    private final String id;

    HologramEngine(final String id) {
        this.id = id;
    }

    /** Идентификатор в конфиге и командах ({@code armor_stand}, {@code text_display}, {@code item_display}). */
    public String id() {
        return this.id;
    }

    /** Движок по идентификатору или {@code null}, если такого нет. Регистр не важен. */
    public static HologramEngine parseOrNull(final String raw) {
        if (raw == null) {
            return null;
        }
        final String s = raw.trim().toLowerCase(Locale.ROOT);
        for (final HologramEngine e : values()) {
            if (e.id.equals(s)) {
                return e;
            }
        }
        return null;
    }

    /** Движок по идентификатору, иначе {@code fallback}. */
    public static HologramEngine parse(final String raw, final HologramEngine fallback) {
        final HologramEngine e = parseOrNull(raw);
        return e == null ? fallback : e;
    }
}

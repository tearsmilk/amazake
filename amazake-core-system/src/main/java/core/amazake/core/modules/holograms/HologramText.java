package core.amazake.core.modules.holograms;

import core.amazake.core.util.Text;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Сборка текста голограммы из строк MiniMessage и проверка имени. Чистые функции — тестируются без сервера. */
public final class HologramText {

    /** Максимальная длина имени голограммы (как у колонки {@code name VARCHAR(32)}). */
    public static final int MAX_NAME = 32;

    private HologramText() {
    }

    /**
     * Склеивает строки в один {@link Component} через перевод строки — текст для TextDisplay. Пустой список даёт
     * пустой компонент.
     */
    public static Component joined(final List<String> lines) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(Text.parse(lines.get(i)));
        }
        return out;
    }

    /** Приводит имя к ключу (нижний регистр), не меняя содержимое. */
    public static String normalizeName(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Допустимое имя: {@code [a-z0-9_-]}, 1..{@value #MAX_NAME} символов (после нормализации). */
    public static boolean validName(final String normalized) {
        if (normalized == null || normalized.isEmpty() || normalized.length() > MAX_NAME) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            final char c = normalized.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}

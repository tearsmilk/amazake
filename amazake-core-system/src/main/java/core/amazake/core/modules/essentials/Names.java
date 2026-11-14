package core.amazake.core.modules.essentials;

import java.util.Locale;

/**
 * Проверка и нормализация имён домов и варпов. Чистая логика без сервера — покрыта юнит-тестами.
 */
final class Names {

    /** Максимальная длина имени (совпадает с VARCHAR(32) в таблицах). */
    static final int MAX_LENGTH = 32;

    private Names() {
    }

    /** Имя из букв, цифр, {@code _} и {@code -}, 1..32 символа. */
    static boolean valid(final String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Имена домов/варпов регистронезависимы: храним и ищем в нижнем регистре. */
    static String normalize(final String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }
}

package core.amazake.systems.example;

import java.util.Locale;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Настройки системы: неизменяемый снимок config.yml. Разбор отделён от Bukkit-логики, чтобы его можно было
 * проверить юнит-тестом (см. src/test).
 */
public record Settings(String welcome, boolean actionbarEnabled, long actionbarIntervalTicks, String actionbarFormat) {

    public static final Settings DEFAULTS = new Settings(
        "<green>Добро пожаловать, <player>!</green>", true, 20L, "<gray>TPS <green><tps></green></gray>");

    /** Читает настройки; неверные значения заменяются значениями по умолчанию с предупреждением. */
    public static Settings from(final ConfigurationSection config, final Consumer<String> warn) {
        final String welcome = config.getString("welcome", DEFAULTS.welcome());
        final boolean enabled = config.getBoolean("actionbar.enabled", DEFAULTS.actionbarEnabled());
        long interval = config.getLong("actionbar.interval-ticks", DEFAULTS.actionbarIntervalTicks());
        if (interval < 1 || interval > 20 * 60 * 60) {
            warn.accept("actionbar.interval-ticks: " + interval + " вне 1..72000, беру " + DEFAULTS.actionbarIntervalTicks());
            interval = DEFAULTS.actionbarIntervalTicks();
        }
        final String format = config.getString("actionbar.format", DEFAULTS.actionbarFormat());
        return new Settings(welcome, enabled, interval, format);
    }

    static String oneDecimal(final double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

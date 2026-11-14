package core.amazake.core.modules.essentials;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Неизменяемый снимок настроек модуля essentials. Разбор конфига отделён от Bukkit-логики, читается заново в
 * {@code onConfigReload}, поэтому {@code /amazakecore reload} включает/выключает подфичи вживую.
 */
public record EssentialsSettings(
    boolean homesEnabled,
    int maxHomes,
    boolean warpsEnabled,
    boolean tpaEnabled,
    int tpaTimeoutSeconds,
    boolean gamemodeEnabled,
    boolean invseeEnabled,
    boolean enderchestEnabled,
    Map<String, String> messages) {

    /** Тексты по умолчанию (RU, MiniMessage). Конфиг их переопределяет по ключу в секции {@code messages}. */
    static final Map<String, String> DEFAULT_MESSAGES;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("player-only", "<red>Эта команда только для игроков.");
        m.put("player-not-found", "<red>Игрок <white><player></white> не в сети.");
        m.put("feature-disabled", "<red>Эта возможность сейчас отключена.");
        m.put("world-missing", "<red>Мир этой точки не загружен.");
        // Дома
        m.put("home-invalid-name", "<red>Недопустимое имя дома: только буквы, цифры, _ и - (до 32 символов).");
        m.put("homes-limit", "<red>Достигнут лимит домов (<max>). Удалите лишний: /delhome <имя>.");
        m.put("home-set", "<green>Дом <white><home></white> сохранён.");
        m.put("home-deleted", "<yellow>Дом <white><home></white> удалён.");
        m.put("home-not-found", "<red>Дом <white><home></white> не найден.");
        m.put("home-teleport", "<green>Телепорт к дому <white><home></white>.");
        m.put("homes-empty", "<gray>У вас нет домов. Поставьте: /sethome [имя].");
        m.put("homes-list", "<gold>Ваши дома (<count>): <white><homes></white>");
        // Варпы
        m.put("warp-invalid-name", "<red>Недопустимое имя варпа: только буквы, цифры, _ и - (до 32 символов).");
        m.put("warp-set", "<green>Варп <white><warp></white> создан.");
        m.put("warp-deleted", "<yellow>Варп <white><warp></white> удалён.");
        m.put("warp-not-found", "<red>Варп <white><warp></white> не найден.");
        m.put("warp-teleport", "<green>Телепорт к варпу <white><warp></white>.");
        m.put("warps-empty", "<gray>Варпов пока нет.");
        m.put("warps-list", "<gold>Варпы (<count>): <white><warps></white>");
        // TPA
        m.put("tpa-self", "<red>Нельзя отправить запрос телепорта самому себе.");
        m.put("tpa-sent", "<green>Запрос телепорта отправлен игроку <white><player></white> (истечёт через <seconds> с).");
        m.put("tpahere-sent", "<green>Запрос «приди ко мне» отправлен игроку <white><player></white> (истечёт через <seconds> с).");
        m.put("tpa-received", "<aqua><player></aqua> хочет телепортироваться к вам. <green>/tpaccept</green> или <red>/tpdeny</red>.");
        m.put("tpahere-received", "<aqua><player></aqua> просит вас телепортироваться к себе. <green>/tpaccept</green> или <red>/tpdeny</red>.");
        m.put("tpa-no-request", "<red>Нет активных запросов телепорта.");
        m.put("tpa-accepted-target", "<green>Вы приняли запрос от <white><player></white>.");
        m.put("tpa-accepted-requester", "<green><white><player></white> принял ваш запрос. Телепортирую…");
        m.put("tpa-denied-target", "<yellow>Вы отклонили запрос от <white><player></white>.");
        m.put("tpa-denied-requester", "<red><white><player></white> отклонил ваш запрос телепорта.");
        m.put("tpa-cancelled", "<yellow>Ваш исходящий запрос телепорта отменён.");
        m.put("tpa-expired-requester", "<gray>Ваш запрос телепорта игроку <white><player></white> истёк.");
        // Режим игры
        m.put("gamemode-invalid", "<red>Режим игры: 0/s (выживание), 1/c (творческий), 2/a (приключение), 3/sp (наблюдатель).");
        m.put("gamemode-set", "<green>Ваш режим игры: <white><mode></white>.");
        m.put("gamemode-set-other", "<green>Режим игры игрока <white><player></white>: <white><mode></white>.");
        m.put("gamemode-set-by", "<green>Ваш режим игры сменил <white><player></white>: <white><mode></white>.");
        // Инвентари
        m.put("no-permission", "<red>Недостаточно прав.");
        m.put("invsee-opened", "<green>Инвентарь игрока <white><player></white> открыт.");
        m.put("enderchest-opened", "<green>Ваш эндер-сундук открыт.");
        m.put("enderchest-opened-other", "<green>Эндер-сундук игрока <white><player></white> открыт.");
        DEFAULT_MESSAGES = Map.copyOf(m);
    }

    /** Все подфичи включены, лимит домов 3, таймаут TPA 60 с, тексты по умолчанию. */
    public static final EssentialsSettings DEFAULTS =
        new EssentialsSettings(true, 3, true, true, 60, true, true, true, DEFAULT_MESSAGES);

    /** Текст по ключу (конфиг или дефолт); для отсутствующего ключа — сам ключ, чтобы была видна опечатка. */
    public String msg(final String key) {
        final String v = this.messages.get(key);
        if (v != null) {
            return v;
        }
        return DEFAULT_MESSAGES.getOrDefault(key, key);
    }

    /** Разбирает секцию {@code modules.essentials}. {@code warn} получает жалобы на кривые значения. */
    public static EssentialsSettings from(final ConfigurationSection c, final Consumer<String> warn) {
        if (c == null) {
            return DEFAULTS;
        }
        int maxHomes = c.getInt("homes.max-homes", DEFAULTS.maxHomes());
        if (maxHomes < 1 || maxHomes > 1000) {
            warn.accept("homes.max-homes: " + maxHomes + " вне 1..1000, беру " + DEFAULTS.maxHomes());
            maxHomes = DEFAULTS.maxHomes();
        }
        int timeout = c.getInt("tpa.timeout-seconds", DEFAULTS.tpaTimeoutSeconds());
        if (timeout < 5 || timeout > 3600) {
            warn.accept("tpa.timeout-seconds: " + timeout + " вне 5..3600, беру " + DEFAULTS.tpaTimeoutSeconds());
            timeout = DEFAULTS.tpaTimeoutSeconds();
        }
        final Map<String, String> msgs = new HashMap<>(DEFAULT_MESSAGES);
        final ConfigurationSection ms = c.getConfigurationSection("messages");
        if (ms != null) {
            for (final String key : ms.getKeys(false)) {
                final String value = ms.getString(key);
                if (value != null) {
                    msgs.put(key, value);
                }
            }
        }
        return new EssentialsSettings(
            c.getBoolean("homes.enabled", true),
            maxHomes,
            c.getBoolean("warps.enabled", true),
            c.getBoolean("tpa.enabled", true),
            timeout,
            c.getBoolean("gamemode.enabled", true),
            c.getBoolean("invsee.enabled", true),
            c.getBoolean("enderchest.enabled", true),
            Map.copyOf(msgs));
    }
}

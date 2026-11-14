package core.amazake.core.modules.essentials;

import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Общая база команд essentials: ссылка на модуль, актуальные настройки и помощники сообщений/автодополнения. */
abstract class EssCommand extends Command {

    final EssentialsModule module;

    @SuppressWarnings("deprecation") // permissionMessage(Component): единственный способ задать текст отказа в правах
    EssCommand(final EssentialsModule module, final String name, final String description, final String usage,
               final List<String> aliases, final String permission) {
        super(name, description, usage, aliases);
        this.setPermission(permission);
        this.permissionMessage(Text.parse("<red>Недостаточно прав."));
        this.module = module;
    }

    EssentialsSettings settings() {
        return this.module.settings();
    }

    /** Отправляет текст по ключу сообщений (с подстановками). */
    void send(final CommandSender to, final String key, final TagResolver... resolvers) {
        to.sendMessage(Text.parse(this.settings().msg(key), resolvers));
    }

    /** Проверяет право и что отправитель — игрок. Возвращает игрока или null (с уже отправленным сообщением). */
    Player requirePlayer(final CommandSender sender) {
        if (!this.testPermission(sender)) {
            return null;
        }
        if (sender instanceof Player player) {
            return player;
        }
        this.send(sender, "player-only");
        return null;
    }

    /** Онлайн-игрок по точному имени, или null. */
    static Player onlinePlayer(final String name) {
        return Bukkit.getPlayerExact(name);
    }

    /** Имена онлайн-игроков, начинающиеся с {@code typed} (для автодополнения). */
    static List<String> onlineNames(final String typed) {
        final String t = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(p.getName());
            }
        }
        return out;
    }

    static List<String> filter(final Iterable<String> options, final String typed) {
        final String t = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(o);
            }
        }
        return out;
    }
}

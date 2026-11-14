package core.amazake.core;

import core.amazake.core.framework.Module;
import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * {@code /amazakecore} (alias {@code /acore}): reload the config on the fly, list modules, enable/disable a module
 * without a restart. Permission {@code amazakecore.admin}.
 */
final class CoreCommand extends Command {

    private final AmazakeCoreSystem system;

    CoreCommand(final AmazakeCoreSystem system) {
        super("amazakecore", "Управление AmazakeCore", "/amazakecore <reload|modules|module enable|disable <id>>",
            List.of("acore"));
        this.setPermission("amazakecore.admin");
        this.system = system;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> global(() -> {
                final List<String> running = this.system.reloadConfig();
                sender.sendMessage(Text.parse("<green>AmazakeCore: настройки перечитаны. Активные модули: <white>"
                    + String.join(", ", running)));
            });
            case "modules" -> {
                sender.sendMessage(Text.parse("<gold>Модули AmazakeCore:"));
                for (final Module module : this.system.modules().all().values()) {
                    final boolean on = this.system.modules().isRunning(module.id());
                    sender.sendMessage(Text.parse((on ? "<green>● " : "<red>○ ") + "<white>" + module.id()
                        + " <gray>— " + module.description()));
                }
            }
            case "module" -> this.module(sender, args);
            default -> sender.sendMessage(Text.parse("<gray>" + this.getUsage()));
        }
        return true;
    }

    private void module(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /amazakecore module <enable|disable> <id>"));
            return;
        }
        final String action = args[1].toLowerCase(Locale.ROOT);
        final String id = args[2].toLowerCase(Locale.ROOT);
        if (!this.system.modules().all().containsKey(id)) {
            sender.sendMessage(Text.parse("<red>Нет модуля «" + id + "». Список: /amazakecore modules"));
            return;
        }
        global(() -> {
            final boolean ok;
            if (action.equals("enable")) {
                ok = this.system.modules().enable(id, this.system.context().loadConfig());
            } else if (action.equals("disable")) {
                ok = this.system.modules().disable(id);
            } else {
                sender.sendMessage(Text.parse("<red>Действие enable или disable."));
                return;
            }
            sender.sendMessage(ok
                ? Text.parse("<green>Модуль " + id + " " + (action.equals("enable") ? "включён" : "выключен") + ".")
                : Text.parse("<yellow>Модуль " + id + " уже в этом состоянии (или ошибка — смотрите консоль)."));
        });
    }

    /** Runs admin work on the global tick thread (module (un)registration touches shared state). */
    private void global(final Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(this.system.context().plugin(), task);
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "modules", "module"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return filter(List.of("enable", "disable"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("module")) {
            return filter(new ArrayList<>(this.system.modules().all().keySet()), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String typed) {
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

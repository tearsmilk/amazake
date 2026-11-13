package core.amazake.command;

import core.amazake.systems.SystemManager;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

/** /amazake systems and /amazake system enable|disable|reload|restart|info|update &lt;name&gt;. */
final class SystemsCommand {

    /** The colour of AmazakeSystems in /pl and here (crimson). */
    static final TextColor CRIMSON = TextColor.color(0xDC143C);
    private static final List<String> ACTIONS = List.of("enable", "disable", "reload", "restart", "info", "update");

    private SystemsCommand() {
    }

    static void list(final CommandSender sender) {
        if (!permitted(sender)) {
            return;
        }
        onGlobalThread(sender, () -> {
            final List<SystemManager.Overview> systems = SystemManager.rescanAndList();
            sender.sendMessage(text("Системы Amazake (" + systems.size() + "), папка systems/:", CRIMSON));
            if (systems.isEmpty()) {
                sender.sendMessage(text("  Нет ни одного .azs-файла.", GRAY));
            }
            for (final SystemManager.Overview system : systems) {
                Component line = text("  " + system.name() + " ", system.enabled() ? GREEN : RED)
                    .append(text(system.version() + " ", GRAY))
                    .append(text(system.enabled() ? "включена" : "выключена", system.enabled() ? GREEN : RED));
                if (!system.description().isEmpty()) {
                    line = line.append(text(" — " + system.description(), GRAY));
                }
                sender.sendMessage(line);
                if (system.lastError() != null && !system.enabled()) {
                    sender.sendMessage(text("    последняя ошибка: " + system.lastError(), RED));
                }
            }
            sender.sendMessage(text("/amazake system enable|disable|reload|restart|info|update <имя>", GRAY));
        });
    }

    static void system(final CommandSender sender, final String[] args) {
        if (!permitted(sender)) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(text("Использование: /amazake system <enable|disable|reload|restart|info|update> <имя>", RED));
            return;
        }
        final String action = args[1].toLowerCase(Locale.ROOT);
        final String name = args[2];
        final Supplier<SystemManager.Result> run = switch (action) {
            case "enable" -> () -> SystemManager.enable(name);
            case "disable" -> () -> SystemManager.disable(name);
            case "reload" -> () -> SystemManager.reload(name);
            case "restart" -> () -> SystemManager.restart(name);
            case "info" -> () -> SystemManager.info(name);
            case "update" -> () -> SystemManager.update(name);
            default -> null;
        };
        if (run == null) {
            sender.sendMessage(text("Неизвестное действие «" + args[1] + "». Есть: enable, disable, reload, restart, info, update", RED));
            return;
        }
        onGlobalThread(sender, () -> {
            final SystemManager.Result result = run.get();
            boolean first = true;
            for (final String line : result.lines()) {
                sender.sendMessage(text(line, !result.ok() && first ? RED : line.startsWith("Внимание") ? YELLOW : first ? GREEN : GRAY));
                first = false;
            }
        });
    }

    static List<String> complete(final String[] args) {
        if (args.length == 2) {
            final String typed = args[1].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        if (args.length == 3) {
            final String typed = args[2].toLowerCase(Locale.ROOT);
            final List<String> names = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "enable" -> SystemManager.names(false);
                case "disable", "reload" -> SystemManager.names(true);
                case "restart" -> SystemManager.names(null);
                case "update" -> core.amazake.systems.BuiltinSystems.NAMES;
                default -> SystemManager.names(null);
            };
            return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }

    private static boolean permitted(final CommandSender sender) {
        if (sender.hasPermission(SystemManager.PERMISSION)) {
            return true;
        }
        sender.sendMessage(text("Нет права " + SystemManager.PERMISSION + ".", RED));
        return false;
    }

    /** Systems change state on the global tick thread only (commands normally already run there). */
    private static void onGlobalThread(final CommandSender sender, final Runnable action) {
        final Runnable safe = () -> {
            try {
                action.run();
            } catch (final RuntimeException e) {
                java.util.logging.Logger.getLogger("Amazake").log(java.util.logging.Level.SEVERE, "/amazake system", e);
                sender.sendMessage(text("Ошибка: " + e + " (подробности в консоли)", RED));
            }
        };
        if (Bukkit.isGlobalTickThread()) {
            safe.run();
        } else {
            MinecraftServer.getServer().execute(safe);
        }
    }
}

package core.amazake.stress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jspecify.annotations.Nullable;

/**
 * /stress start [all|mobs|physics|redstone|chunks|packets|async|races] [seconds]
 * /stress run [all|module] [seconds] (like start, plus the built-in profiler for exactly the run, report uploaded at the end)
 * /stress stop | status | cleanup confirm | profile [seconds|stop]
 */
public final class StressCommand implements CommandExecutor, TabCompleter {

    public static final int MIN_SECONDS = 10;
    public static final int MAX_SECONDS = 7200;
    public static final int DEFAULT_PROFILE_SECONDS = 300;

    private static final List<String> SUBCOMMANDS = List.of("start", "run", "stop", "status", "cleanup", "profile");

    private final StressPlugin plugin;

    public StressCommand(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(Msg.PERMISSION)) {
            Msg.error(sender, "Нет прав (" + Msg.PERMISSION + ")");
            return true;
        }
        if (args.length == 0) {
            this.help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> this.start(sender, args, false);
            case "run" -> this.start(sender, args, true);
            case "stop" -> {
                if (!this.plugin.stopRun("остановлен командой")) {
                    Msg.warn(sender, "Прогон не запущен");
                }
            }
            case "status" -> StatusPrinter.sendStatus(this.plugin, sender);
            case "cleanup" -> this.cleanup(sender, args);
            case "profile" -> this.profile(sender, args);
            default -> this.help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        Msg.info(sender, "Команды:");
        Msg.plain(sender, "  /" + label + " start [all|" + String.join("|", this.plugin.modules().ids())
            + "] [секунды] — запустить нагрузку", NamedTextColor.GRAY);
        Msg.plain(sender, "  /" + label + " run [all|модуль] [секунды] — то же + профайлер на весь прогон, отчёт в конце", NamedTextColor.GRAY);
        Msg.plain(sender, "  /" + label + " stop — остановить и убрать всё созданное", NamedTextColor.GRAY);
        Msg.plain(sender, "  /" + label + " status — этап, счётчики, TPS/MSPT", NamedTextColor.GRAY);
        Msg.plain(sender, "  /" + label + " cleanup confirm — выгрузить и удалить мир " + TestWorld.NAME, NamedTextColor.GRAY);
        Msg.plain(sender, "  /" + label + " profile [секунды|stop] — встроенный профайлер", NamedTextColor.GRAY);
    }

    private static @Nullable Integer parseSeconds(String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private void start(CommandSender sender, String[] args, boolean profile) {
        String mode = "all";
        int seconds = 0;
        for (int i = 1; i < args.length; i++) {
            final String arg = args[i].toLowerCase(Locale.ROOT);
            final Integer number = parseSeconds(arg);
            if (number != null) {
                if (number < MIN_SECONDS || number > MAX_SECONDS) {
                    Msg.error(sender, "Длительность должна быть от " + MIN_SECONDS + " до " + MAX_SECONDS + " секунд");
                    return;
                }
                seconds = number;
            } else if (arg.equals("all") || this.plugin.modules().get(arg) != null) {
                mode = arg;
            } else {
                Msg.error(sender, "Неизвестный модуль: " + args[i] + ". Доступны: all, "
                    + String.join(", ", this.plugin.modules().ids()));
                return;
            }
        }
        this.plugin.startRun(sender, mode, seconds, profile);
    }

    private void cleanup(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            Msg.warn(sender, "Это выгрузит и УДАЛИТ мир " + TestWorld.NAME + " целиком (другие миры не затрагиваются).");
            Msg.warn(sender, "Для подтверждения: /stress cleanup confirm");
            return;
        }
        if (this.plugin.isPreparing()) {
            Msg.error(sender, "Мир сейчас готовится к запуску, подождите");
            return;
        }
        if (this.plugin.stopRun("очистка мира")) {
            Msg.info(sender, "Прогон остановлен");
        }
        Msg.info(sender, "Удаляю мир " + TestWorld.NAME + "...");
        this.plugin.testWorld().delete(
            () -> Msg.ok(sender, "Мир " + TestWorld.NAME + " удалён"),
            error -> Msg.error(sender, "Очистка не удалась: " + error));
    }

    private void profile(CommandSender sender, String[] args) {
        final ProfilerBridge profiler = this.plugin.profiler();
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            profiler.stop(sender);
            return;
        }
        int seconds = DEFAULT_PROFILE_SECONDS;
        if (args.length >= 2) {
            final Integer number = parseSeconds(args[1]);
            if (number == null || number < MIN_SECONDS || number > MAX_SECONDS) {
                Msg.error(sender, "Длительность профилирования: от " + MIN_SECONDS + " до " + MAX_SECONDS + " секунд");
                return;
            }
            seconds = number;
        }
        if (profiler.isRunning()) {
            Msg.warn(sender, "Профайлер уже работает: " + profiler.status());
            return;
        }
        profiler.start(sender, seconds);
    }

    @Override
    public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(Msg.PERMISSION)) {
            return List.of();
        }
        final List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start", "run" -> {
                    options.add("all");
                    options.addAll(this.plugin.modules().ids());
                    options.add("300");
                }
                case "cleanup" -> options.add("confirm");
                case "profile" -> {
                    options.add("300");
                    options.add("60");
                    options.add("stop");
                }
                default -> {
                }
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("run"))) {
            options.addAll(List.of("60", "120", "300", "600"));
        }
        final String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.startsWith(prefix));
        return options;
    }
}

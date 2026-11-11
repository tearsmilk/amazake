package ink.hoka.amazake.command;

import ink.hoka.amazake.Amazake;
import ink.hoka.amazake.AmazakeConfig;
import ink.hoka.amazake.profiler.Profiler;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

/** /amazake: version, reload, profile. */
public final class AmazakeCommand extends Command {

    private static final String RELOAD_PERMISSION = "amazake.command.reload";
    private static final String PROFILE_PERMISSION = "amazake.command.profile";
    private static final List<String> SUBCOMMANDS = List.of("version", "reload", "profile");
    private static final List<String> PROFILE_ARGUMENTS = List.of("stop", "30", "60", "300");

    public AmazakeCommand(final String name) {
        super(name);
        this.description = "Amazake commands";
        this.usageMessage = "/amazake [version|reload|profile [seconds|stop]]";
        this.setPermission("amazake.command");
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "version" -> {
                sender.sendMessage(text(Amazake.versionLine(), GOLD));
                sender.sendMessage(text(Amazake.REPOSITORY, GRAY));
            }
            case "reload" -> {
                if (!sender.hasPermission(RELOAD_PERMISSION)) {
                    sender.sendMessage(text("You may not reload Amazake's settings.", RED));
                    return true;
                }
                if (AmazakeConfig.reload()) {
                    sender.sendMessage(text("Reloaded " + AmazakeConfig.FILE_NAME + ".", GREEN));
                } else {
                    sender.sendMessage(text("Could not read " + AmazakeConfig.FILE_NAME + ": see the console. The previous settings stay.", RED));
                }
            }
            case "profile" -> this.profile(sender, args);
            default -> sender.sendMessage(text("Usage: " + this.usageMessage, RED));
        }
        return true;
    }

    /** /amazake profile [seconds] | /amazake profile stop. */
    private void profile(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(PROFILE_PERMISSION)) {
            sender.sendMessage(text("Нет права " + PROFILE_PERMISSION + ".", RED));
            return;
        }
        if (args.length < 2) {
            Profiler.start(sender, AmazakeConfig.profilerDefaultSeconds);
            return;
        }
        final String arg = args[1].toLowerCase(Locale.ROOT);
        if (arg.equals("stop")) {
            Profiler.stop(sender);
            return;
        }
        final int seconds;
        try {
            seconds = Integer.parseInt(arg);
        } catch (final NumberFormatException e) {
            sender.sendMessage(text("Использование: /amazake profile [секунды] или /amazake profile stop", RED));
            return;
        }
        if (seconds < 1) {
            sender.sendMessage(text("Время замера — целое число секунд, не меньше 1.", RED));
            return;
        }
        Profiler.start(sender, seconds);
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location) {
        if (args.length == 1) {
            final String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(typed))
                .filter(s -> !s.equals("reload") || sender.hasPermission(RELOAD_PERMISSION))
                .filter(s -> !s.equals("profile") || sender.hasPermission(PROFILE_PERMISSION))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile") && sender.hasPermission(PROFILE_PERMISSION)) {
            final String typed = args[1].toLowerCase(Locale.ROOT);
            return PROFILE_ARGUMENTS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        return List.of();
    }
}

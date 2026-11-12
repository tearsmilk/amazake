package ink.hoka.amazake.command;

import ink.hoka.amazake.Amazake;
import ink.hoka.amazake.AmazakeConfig;
import ink.hoka.amazake.cpu.Cores;
import ink.hoka.amazake.cpu.ProcStat;
import ink.hoka.amazake.profiler.Profiler;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.GOLD;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

/** /amazake: version, reload, profile, cores. */
public final class AmazakeCommand extends Command {

    private static final String RELOAD_PERMISSION = "amazake.command.reload";
    private static final String PROFILE_PERMISSION = "amazake.command.profile";
    private static final String CORES_PERMISSION = "amazake.command.cores";
    private static final List<String> SUBCOMMANDS = List.of("version", "reload", "profile", "cores");
    private static final List<String> PROFILE_ARGUMENTS = List.of("stop", "30", "60", "300");

    public AmazakeCommand(final String name) {
        super(name);
        this.description = "Amazake commands";
        this.usageMessage = "/amazake [version|reload|profile [seconds|stop]|cores]";
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
            case "cores" -> this.cores(sender);
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

    /**
     * /amazake cores: the core layout, per-core load over the last second and where each thread group
     * runs. The load comes from the "Amazake Cores" thread; without it, it is measured off the main
     * thread for one second and the answer comes then.
     */
    private void cores(final CommandSender sender) {
        if (!sender.hasPermission(CORES_PERMISSION)) {
            sender.sendMessage(text("Нет права " + CORES_PERMISSION + ".", RED));
            return;
        }
        final Cores.Status status = Cores.status();
        final Map<Integer, Double> load = status.manager() == null ? null : status.manager().recentLoad();
        if (load != null || !new java.io.File("/proc/stat").canRead()) {
            sendCores(sender, status, load);
            return;
        }
        final Map<Integer, long[]> before = ProcStat.read();
        CompletableFuture.runAsync(
            () -> sendCores(sender, Cores.status(), ProcStat.load(before, ProcStat.read())),
            CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS)
        );
    }

    private static void sendCores(final CommandSender sender, final Cores.Status status, final Map<Integer, Double> load) {
        final List<String> lines = Cores.describe(status, load == null || load.isEmpty() ? null : load);
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            sender.sendMessage(text(line, i == 0 ? (status.disabledRu() == null ? GOLD : RED) : (line.startsWith("Внимание") ? RED : GRAY)));
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location) {
        if (args.length == 1) {
            final String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(typed))
                .filter(s -> !s.equals("reload") || sender.hasPermission(RELOAD_PERMISSION))
                .filter(s -> !s.equals("profile") || sender.hasPermission(PROFILE_PERMISSION))
                .filter(s -> !s.equals("cores") || sender.hasPermission(CORES_PERMISSION))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile") && sender.hasPermission(PROFILE_PERMISSION)) {
            final String typed = args[1].toLowerCase(Locale.ROOT);
            return PROFILE_ARGUMENTS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        return List.of();
    }
}

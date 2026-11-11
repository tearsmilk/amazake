package ink.hoka.amazake.command;

import ink.hoka.amazake.Amazake;
import ink.hoka.amazake.AmazakeConfig;
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

/** /amazake: version, reload. */
public final class AmazakeCommand extends Command {

    private static final List<String> SUBCOMMANDS = List.of("version", "reload");

    public AmazakeCommand(final String name) {
        super(name);
        this.description = "Amazake commands";
        this.usageMessage = "/amazake [version|reload]";
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
                if (!sender.hasPermission("amazake.command.reload")) {
                    sender.sendMessage(text("You may not reload Amazake's settings.", RED));
                    return true;
                }
                if (AmazakeConfig.reload()) {
                    sender.sendMessage(text("Reloaded " + AmazakeConfig.FILE_NAME + ".", GREEN));
                } else {
                    sender.sendMessage(text("Could not read " + AmazakeConfig.FILE_NAME + ": see the console. The previous settings stay.", RED));
                }
            }
            default -> sender.sendMessage(text("Usage: " + this.usageMessage, RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final Location location) {
        if (args.length == 1) {
            final String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        return List.of();
    }
}

package core.amazake.systems.example;

import core.amazake.systems.api.ServerStats;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /example — информация о сервере; /example ping — свой пинг (только игроку).
 * Команда регистрируется через {@code context.registerCommand}: доступна как /example и /example:example,
 * при выключении системы ядро её удалит.
 */
final class ExampleCommand extends Command {

    private final ExampleSystem system;

    ExampleCommand(final ExampleSystem system) {
        super("example", "Пример команды системы", "/example [ping]", List.of("ex"));
        this.setPermission("example.use");
        this.system = system;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("ping")) {
            if (!(sender instanceof final Player player)) {
                sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED));
                return true;
            }
            // Команда игрока выполняется в его потоке: читать и менять его здесь можно.
            sender.sendMessage(Component.text("Твой пинг: " + player.getPing() + " мс", NamedTextColor.AQUA));
            return true;
        }
        final ServerStats stats = this.system.context().stats();
        sender.sendMessage(Component.text(this.system.context().info().name() + " " + this.system.context().info().version(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(String.format(Locale.ROOT, "TPS %.1f, MSPT %.1f, онлайн %d/%d",
            stats.tps(), stats.mspt(), stats.onlinePlayers(), stats.maxPlayers()), NamedTextColor.GRAY));
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1 && "ping".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("ping");
        }
        return List.of();
    }
}

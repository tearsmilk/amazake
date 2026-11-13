package core.amazake.profiler;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Messages to whoever ran the command. Sending chat to a CommandSender from the profiler thread is
 * safe: for a player it only queues a system chat packet on the connection (CraftPlayer#sendMessage
 * has no async catcher), for the console it is a log line. Results are also logged, so the owner
 * finds them even if the player has left.
 */
final class Messages {

    private static final Component PREFIX = Component.text("[Профайлер] ", NamedTextColor.GOLD);

    private Messages() {
    }

    static void info(final CommandSender sender, final String text) {
        send(sender, Component.text(text, NamedTextColor.GRAY));
    }

    static void warn(final CommandSender sender, final String text) {
        send(sender, Component.text(text, NamedTextColor.YELLOW));
    }

    static void error(final CommandSender sender, final String text) {
        send(sender, Component.text(text, NamedTextColor.RED));
        logIfPlayer(sender, text);
    }

    static void result(final CommandSender sender, final String text) {
        send(sender, Component.text(text, NamedTextColor.GREEN));
        logIfPlayer(sender, text);
    }

    /** "Отчёт: <url>" with the address clickable. */
    static void link(final CommandSender sender, final String text, final String url) {
        send(sender, Component.text(text + " ", NamedTextColor.GREEN)
            .append(Component.text(url, NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть отчёт в браузере")))));
        logIfPlayer(sender, text + " " + url);
    }

    private static void send(final CommandSender sender, final Component message) {
        try {
            sender.sendMessage(PREFIX.append(message));
        } catch (final RuntimeException e) {
            Profiler.LOGGER.info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    private static void logIfPlayer(final CommandSender sender, final String text) {
        if (!(sender instanceof ConsoleCommandSender)) {
            Profiler.LOGGER.info("[Профайлер] " + text);
        }
    }
}

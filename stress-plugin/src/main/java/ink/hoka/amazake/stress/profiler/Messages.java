package ink.hoka.amazake.stress.profiler;

import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Messages to whoever started the measurement (also from the profiler thread: sending chat to a
 * CommandSender only queues a packet / writes a log line). Results are also logged, so the owner finds them
 * even if the player has left.
 */
final class Messages {

    private static final Component PREFIX = Component.text("[AmazakeStress·профайлер] ", NamedTextColor.GOLD);

    private final Logger logger;

    Messages(final Logger logger) {
        this.logger = logger;
    }

    void info(final CommandSender sender, final String text) {
        this.send(sender, Component.text(text, NamedTextColor.GRAY));
    }

    void warn(final CommandSender sender, final String text) {
        this.send(sender, Component.text(text, NamedTextColor.YELLOW));
    }

    void error(final CommandSender sender, final String text) {
        this.send(sender, Component.text(text, NamedTextColor.RED));
        this.logIfPlayer(sender, text);
    }

    void result(final CommandSender sender, final String text) {
        this.send(sender, Component.text(text, NamedTextColor.GREEN));
        this.logIfPlayer(sender, text);
    }

    /** "text <url>" with the address clickable. */
    void link(final CommandSender sender, final String text, final String url) {
        this.send(sender, Component.text(text + " ", NamedTextColor.GREEN)
            .append(Component.text(url, NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Открыть отчёт в браузере")))));
        this.logIfPlayer(sender, text + " " + url);
    }

    private void send(final CommandSender sender, final Component message) {
        try {
            sender.sendMessage(PREFIX.append(message));
        } catch (final RuntimeException e) {
            this.logger.info(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    private void logIfPlayer(final CommandSender sender, final String text) {
        if (!(sender instanceof ConsoleCommandSender)) {
            this.logger.info("[профайлер] " + text);
        }
    }
}

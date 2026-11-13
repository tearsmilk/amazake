package core.amazake.stress;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** User-facing chat messages (Russian text, Adventure components). */
public final class Msg {

    public static final String PERMISSION = "stress.admin";
    private static final Component PREFIX = Component.text("[AmazakeStress] ", NamedTextColor.GOLD);

    private Msg() {
    }

    public static Component line(String text, TextColor color) {
        return PREFIX.append(Component.text(text, color));
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(line(text, NamedTextColor.WHITE));
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(line(text, NamedTextColor.GREEN));
    }

    public static void warn(CommandSender to, String text) {
        to.sendMessage(line(text, NamedTextColor.YELLOW));
    }

    public static void error(CommandSender to, String text) {
        to.sendMessage(line(text, NamedTextColor.RED));
    }

    /** Plain line without prefix (for multi-line output). */
    public static void plain(CommandSender to, String text, TextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    /** Sends to the console and every online player with {@link #PERMISSION} (except {@code skip}). */
    public static void broadcast(String text, TextColor color, @Nullable CommandSender skip) {
        final Component message = line(text, color);
        Bukkit.getConsoleSender().sendMessage(message);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player != skip && player.hasPermission(PERMISSION)) {
                player.sendMessage(message);
            }
        }
    }
}

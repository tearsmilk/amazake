package ink.hoka.amazake.systems.tab;

import ink.hoka.amazake.systems.api.ServerStats;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The built-in placeholders of one player. Created and used on that player's own thread (it reads the player's
 * location, health, ping). Values are escaped for MiniMessage, so a display name with '<' cannot inject tags.
 */
final class PlayerPlaceholders implements Function<String, String> {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Player player;
    private final ServerStats stats;
    private final GroupDef group;
    private final TabRuntime.Settings settings;
    private Location location;
    private ZonedDateTime now;

    PlayerPlaceholders(final Player player, final ServerStats stats, final GroupDef group, final TabRuntime.Settings settings) {
        this.player = player;
        this.stats = stats;
        this.group = group;
        this.settings = settings;
    }

    @Override
    public String apply(final String name) {
        return switch (name) {
            case "player" -> this.player.getName();
            case "displayname" -> escape(PlainTextComponentSerializer.plainText().serialize(this.player.displayName()));
            case "online" -> Integer.toString(this.stats.onlinePlayers());
            case "max" -> Integer.toString(this.stats.maxPlayers());
            case "tps" -> Placeholders.decimal(Math.min(20.0, this.stats.tps()), 2);
            case "mspt" -> Placeholders.decimal(this.stats.mspt(), 1);
            case "ping" -> Integer.toString(this.player.getPing());
            case "world" -> escape(this.player.getWorld().getName());
            case "x" -> Integer.toString(this.location().getBlockX());
            case "y" -> Integer.toString(this.location().getBlockY());
            case "z" -> Integer.toString(this.location().getBlockZ());
            case "health" -> Placeholders.decimal(this.player.getHealth(), 1);
            case "level" -> Integer.toString(this.player.getLevel());
            case "time" -> TIME.format(this.now());
            case "date" -> DATE.format(this.now());
            case "group" -> escape(this.group.name());
            default -> null;
        };
    }

    private Location location() {
        if (this.location == null) {
            this.location = this.player.getLocation();
        }
        return this.location;
    }

    private ZonedDateTime now() {
        if (this.now == null) {
            this.now = ZonedDateTime.now(this.settings.zone());
        }
        return this.now;
    }

    private static String escape(final String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }
}

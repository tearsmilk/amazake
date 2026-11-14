package core.amazake.core.modules.essentials;

import core.amazake.core.modules.essentials.EssentialsModule.StoredLoc;
import core.amazake.core.util.Text;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Дома игрока. Одна реализация на четыре команды: {@code /sethome [имя]}, {@code /home [имя]},
 * {@code /delhome [имя]}, {@code /homes}. Имя по умолчанию — {@code home}.
 */
final class HomesCommand extends EssCommand {

    enum Action {
        SET("sethome", "Сохранить дом", "/sethome [имя]"),
        GO("home", "Телепорт к дому", "/home [имя]"),
        DEL("delhome", "Удалить дом", "/delhome [имя]"),
        LIST("homes", "Список домов", "/homes");

        final String label;
        final String description;
        final String usage;

        Action(final String label, final String description, final String usage) {
            this.label = label;
            this.description = description;
            this.usage = usage;
        }
    }

    static final String DEFAULT_HOME = "home";
    static final String PERM = "amazakecore.essentials.homes";
    static final String PERM_UNLIMITED = "amazakecore.essentials.homes.unlimited";

    private final Action action;

    HomesCommand(final EssentialsModule module, final Action action) {
        super(module, action.label, action.description, action.usage, List.of(), PERM);
        this.action = action;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final Player player = this.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!this.settings().homesEnabled()) {
            this.send(player, "feature-disabled");
            return true;
        }
        final String name = Names.normalize(args.length >= 1 ? args[0] : DEFAULT_HOME);
        if (this.action != Action.LIST && !Names.valid(name)) {
            this.send(player, "home-invalid-name");
            return true;
        }
        final UUID uuid = player.getUniqueId();
        switch (this.action) {
            case SET -> this.setHome(player, uuid, name);
            case GO -> this.goHome(player, uuid, name);
            case DEL -> this.delHome(player, uuid, name);
            case LIST -> this.listHomes(player, uuid);
            default -> { }
        }
        return true;
    }

    private void setHome(final Player player, final UUID uuid, final String name) {
        final Location loc = player.getLocation().clone();
        final boolean unlimited = player.hasPermission(PERM_UNLIMITED);
        final int max = this.settings().maxHomes();
        this.module.db()
            .queryList("SELECT name FROM ess_homes WHERE uuid = ?", rs -> rs.getString("name"), uuid.toString())
            .thenAccept(existing -> {
                final boolean overwrite = existing.stream().anyMatch(n -> n.equalsIgnoreCase(name));
                if (!overwrite && !unlimited && existing.size() >= max) {
                    this.module.ctx().runOnceForPlayer(player,
                        () -> this.send(player, "homes-limit", Text.var("max", String.valueOf(max))));
                    return;
                }
                this.module.db().write(
                    "MERGE INTO ess_homes (uuid, name, world, x, y, z, yaw, pitch) KEY (uuid, name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    uuid.toString(), name, loc.getWorld().getName(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch())
                    .thenRun(() -> this.module.ctx().runOnceForPlayer(player,
                        () -> this.send(player, "home-set", Text.var("home", name))));
            });
    }

    private void goHome(final Player player, final UUID uuid, final String name) {
        this.module.db().queryFirst(
                "SELECT world, x, y, z, yaw, pitch FROM ess_homes WHERE uuid = ? AND name = ?",
                EssentialsModule.LOC_MAPPER, uuid.toString(), name)
            .thenAccept(loc -> {
                if (loc == null) {
                    this.module.ctx().runOnceForPlayer(player,
                        () -> this.send(player, "home-not-found", Text.var("home", name)));
                    return;
                }
                this.module.teleportToStored(player, loc,
                    Text.parse(this.settings().msg("home-teleport"), Text.var("home", name)));
            });
    }

    private void delHome(final Player player, final UUID uuid, final String name) {
        this.module.db().write("DELETE FROM ess_homes WHERE uuid = ? AND name = ?", uuid.toString(), name)
            .thenAccept(deleted -> this.module.ctx().runOnceForPlayer(player, () ->
                this.send(player, deleted > 0 ? "home-deleted" : "home-not-found", Text.var("home", name))));
    }

    private void listHomes(final Player player, final UUID uuid) {
        this.module.db()
            .queryList("SELECT name FROM ess_homes WHERE uuid = ? ORDER BY name", rs -> rs.getString("name"),
                uuid.toString())
            .thenAccept(names -> this.module.ctx().runOnceForPlayer(player, () -> {
                if (names.isEmpty()) {
                    this.send(player, "homes-empty");
                } else {
                    this.send(player, "homes-list",
                        Text.var("count", String.valueOf(names.size())),
                        Text.var("homes", String.join(", ", names)));
                }
            }));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return List.of();
    }
}

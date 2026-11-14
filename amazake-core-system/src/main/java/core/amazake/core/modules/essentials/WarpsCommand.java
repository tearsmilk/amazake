package core.amazake.core.modules.essentials;

import core.amazake.core.util.Text;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Глобальные варпы: {@code /setwarp <имя>} (право {@code amazakecore.essentials.setwarp}),
 * {@code /delwarp <имя>}, {@code /warp <имя>}, {@code /warps}.
 */
final class WarpsCommand extends EssCommand {

    enum Action {
        SET("setwarp", "Создать варп", "/setwarp <имя>", "amazakecore.essentials.setwarp"),
        DEL("delwarp", "Удалить варп", "/delwarp <имя>", "amazakecore.essentials.setwarp"),
        GO("warp", "Телепорт к варпу", "/warp <имя>", "amazakecore.essentials.warp"),
        LIST("warps", "Список варпов", "/warps", "amazakecore.essentials.warp");

        final String label;
        final String description;
        final String usage;
        final String permission;

        Action(final String label, final String description, final String usage, final String permission) {
            this.label = label;
            this.description = description;
            this.usage = usage;
            this.permission = permission;
        }
    }

    private final Action action;

    WarpsCommand(final EssentialsModule module, final Action action) {
        super(module, action.label, action.description, action.usage, List.of(), action.permission);
        this.action = action;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (!this.settings().warpsEnabled()) {
            this.send(sender, "feature-disabled");
            return true;
        }
        if (this.action == Action.LIST) {
            this.listWarps(sender);
            return true;
        }
        final Player player = this.action == Action.GO || this.action == Action.SET
            ? this.asPlayer(sender) : null;
        if ((this.action == Action.GO || this.action == Action.SET) && player == null) {
            return true;
        }
        if (args.length < 1) {
            this.send(sender, "warp-invalid-name");
            return true;
        }
        final String name = Names.normalize(args[0]);
        if (!Names.valid(name)) {
            this.send(sender, "warp-invalid-name");
            return true;
        }
        switch (this.action) {
            case SET -> this.setWarp(player, name);
            case GO -> this.goWarp(player, name);
            case DEL -> this.delWarp(sender, name);
            default -> { }
        }
        return true;
    }

    private Player asPlayer(final CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        this.send(sender, "player-only");
        return null;
    }

    private void setWarp(final Player player, final String name) {
        final Location loc = player.getLocation().clone();
        this.module.db().write(
                "MERGE INTO ess_warps (name, world, x, y, z, yaw, pitch) KEY (name) VALUES (?, ?, ?, ?, ?, ?, ?)",
                name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch())
            .thenRun(() -> {
                this.module.rememberWarp(name);
                this.module.ctx().runOnceForPlayer(player, () -> this.send(player, "warp-set", Text.var("warp", name)));
            });
    }

    private void goWarp(final Player player, final String name) {
        this.module.db().queryFirst(
                "SELECT world, x, y, z, yaw, pitch FROM ess_warps WHERE name = ?",
                EssentialsModule.LOC_MAPPER, name)
            .thenAccept(loc -> {
                if (loc == null) {
                    this.module.ctx().runOnceForPlayer(player,
                        () -> this.send(player, "warp-not-found", Text.var("warp", name)));
                    return;
                }
                this.module.teleportToStored(player, loc,
                    Text.parse(this.settings().msg("warp-teleport"), Text.var("warp", name)));
            });
    }

    private void delWarp(final CommandSender sender, final String name) {
        this.module.db().write("DELETE FROM ess_warps WHERE name = ?", name).thenAccept(deleted -> {
            if (deleted > 0) {
                this.module.forgetWarp(name);
            }
            // Ответ отправителю (игроку — в его потоке; консоли — сразу).
            if (sender instanceof Player player) {
                this.module.ctx().runOnceForPlayer(player,
                    () -> this.send(player, deleted > 0 ? "warp-deleted" : "warp-not-found", Text.var("warp", name)));
            } else {
                this.send(sender, deleted > 0 ? "warp-deleted" : "warp-not-found", Text.var("warp", name));
            }
        });
    }

    private void listWarps(final CommandSender sender) {
        this.module.db()
            .queryList("SELECT name FROM ess_warps ORDER BY name", rs -> rs.getString("name"))
            .thenAccept(names -> {
                final Runnable reply = () -> {
                    if (names.isEmpty()) {
                        this.send(sender, "warps-empty");
                    } else {
                        this.send(sender, "warps-list",
                            Text.var("count", String.valueOf(names.size())),
                            Text.var("warps", String.join(", ", names)));
                    }
                };
                if (sender instanceof Player player) {
                    this.module.ctx().runOnceForPlayer(player, reply);
                } else {
                    reply.run();
                }
            });
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if ((this.action == Action.GO || this.action == Action.DEL) && args.length == 1) {
            return filter(this.module.warpNames(), args[0]);
        }
        return List.of();
    }
}

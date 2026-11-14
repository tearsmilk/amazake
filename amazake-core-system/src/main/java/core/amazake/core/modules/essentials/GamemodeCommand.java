package core.amazake.core.modules.essentials;

import core.amazake.core.util.Text;
import java.util.List;
import java.util.Locale;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Смена режима игры. {@code /gm <0|1|2|3|s|c|a|sp> [игрок]} разбирает режим из аргумента; ярлыки {@code /gms},
 * {@code /gmc}, {@code /gma}, {@code /gmsp}, {@code /gm0}..{@code /gm3} имеют фиксированный режим и берут только
 * необязательного игрока. Право {@code amazakecore.essentials.gamemode}, для чужого режима — {@code .others}.
 */
final class GamemodeCommand extends EssCommand {

    static final String PERM = "amazakecore.essentials.gamemode";
    static final String PERM_OTHERS = "amazakecore.essentials.gamemode.others";

    /** Фиксированный режим для ярлыков; null — для {@code /gm}, где режим берётся из аргумента. */
    private final GameMode fixedMode;

    GamemodeCommand(final EssentialsModule module, final String label, final GameMode fixedMode) {
        super(module, label, "Сменить режим игры",
            fixedMode == null ? "/gm <0|1|2|3|s|c|a|sp> [игрок]" : "/" + label + " [игрок]", List.of(), PERM);
        this.fixedMode = fixedMode;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (!this.settings().gamemodeEnabled()) {
            this.send(sender, "feature-disabled");
            return true;
        }
        final GameMode mode;
        final String targetArg;
        if (this.fixedMode == null) {
            if (args.length < 1) {
                this.send(sender, "gamemode-invalid");
                return true;
            }
            final GameMode parsed = parseMode(args[0]);
            if (parsed == null) {
                this.send(sender, "gamemode-invalid");
                return true;
            }
            mode = parsed;
            targetArg = args.length >= 2 ? args[1] : null;
        } else {
            mode = this.fixedMode;
            targetArg = args.length >= 1 ? args[0] : null;
        }

        final Player target;
        if (targetArg != null) {
            if (!sender.hasPermission(PERM_OTHERS)) {
                this.send(sender, "no-permission");
                return true;
            }
            target = onlinePlayer(targetArg);
            if (target == null) {
                this.send(sender, "player-not-found", Text.var("player", targetArg));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            this.send(sender, "player-only");
            return true;
        }

        final boolean self = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
        final String senderName = sender instanceof Player p ? p.getName() : "консоль";
        // Режим игры меняем в потоке целевого игрока.
        this.module.ctx().runOnceForPlayer(target, () -> {
            target.setGameMode(mode);
            final String modeName = modeName(mode);
            if (self) {
                target.sendMessage(this.module.parse("gamemode-set", Text.var("mode", modeName)));
            } else {
                target.sendMessage(this.module.parse("gamemode-set-by",
                    Text.var("mode", modeName), Text.var("player", senderName)));
            }
        });
        if (!self) {
            this.send(sender, "gamemode-set-other",
                Text.var("player", target.getName()), Text.var("mode", modeName(mode)));
        }
        return true;
    }

    static GameMode parseMode(final String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival", "выживание" -> GameMode.SURVIVAL;
            case "1", "c", "creative", "творческий" -> GameMode.CREATIVE;
            case "2", "a", "adventure", "приключение" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator", "наблюдатель" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static String modeName(final GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "выживание";
            case CREATIVE -> "творческий";
            case ADVENTURE -> "приключение";
            case SPECTATOR -> "наблюдатель";
        };
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (this.fixedMode == null) {
            if (args.length == 1) {
                return filter(List.of("0", "1", "2", "3", "s", "c", "a", "sp"), args[0]);
            }
            if (args.length == 2 && sender.hasPermission(PERM_OTHERS)) {
                return onlineNames(args[1]);
            }
            return List.of();
        }
        if (args.length == 1 && sender.hasPermission(PERM_OTHERS)) {
            return onlineNames(args[0]);
        }
        return List.of();
    }
}

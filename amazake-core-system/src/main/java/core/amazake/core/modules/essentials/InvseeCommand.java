package core.amazake.core.modules.essentials;

import core.amazake.core.util.Text;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /invsee <игрок>}: открыть зрителю инвентарь другого игрока. Право {@code amazakecore.essentials.invsee}.
 * Открытие инвентаря — в потоке зрителя. Онлайн-просмотр инвентаря из другого региона — известное ограничение
 * эпохи Folia; сегодня тик одиночный, поэтому работает.
 */
final class InvseeCommand extends EssCommand {

    static final String PERM = "amazakecore.essentials.invsee";

    InvseeCommand(final EssentialsModule module) {
        super(module, "invsee", "Посмотреть инвентарь игрока", "/invsee <игрок>", List.of(), PERM);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final Player viewer = this.requirePlayer(sender);
        if (viewer == null) {
            return true;
        }
        if (!this.settings().invseeEnabled()) {
            this.send(viewer, "feature-disabled");
            return true;
        }
        if (args.length < 1) {
            viewer.sendMessage(Text.parse("<gray>" + this.getUsage()));
            return true;
        }
        final Player target = onlinePlayer(args[0]);
        if (target == null) {
            this.send(viewer, "player-not-found", Text.var("player", args[0]));
            return true;
        }
        this.module.ctx().runOnceForPlayer(viewer, () -> {
            viewer.openInventory(target.getInventory());
            this.send(viewer, "invsee-opened", Text.var("player", target.getName()));
        });
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}

package core.amazake.core.modules.essentials;

import core.amazake.core.util.Text;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /ec [игрок]}: открыть свой эндер-сундук, либо чужой с правом {@code amazakecore.essentials.enderchest.others}.
 * Открытие — в потоке зрителя. Онлайн-просмотр чужого эндер-сундука из другого региона — известное ограничение
 * эпохи Folia; сегодня тик одиночный, поэтому работает.
 */
final class EnderChestCommand extends EssCommand {

    static final String PERM = "amazakecore.essentials.enderchest";
    static final String PERM_OTHERS = "amazakecore.essentials.enderchest.others";

    EnderChestCommand(final EssentialsModule module) {
        super(module, "ec", "Открыть эндер-сундук", "/ec [игрок]", List.of("enderchest"), PERM);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final Player viewer = this.requirePlayer(sender);
        if (viewer == null) {
            return true;
        }
        if (!this.settings().enderchestEnabled()) {
            this.send(viewer, "feature-disabled");
            return true;
        }
        if (args.length < 1) {
            this.module.ctx().runOnceForPlayer(viewer, () -> {
                viewer.openInventory(viewer.getEnderChest());
                this.send(viewer, "enderchest-opened");
            });
            return true;
        }
        if (!viewer.hasPermission(PERM_OTHERS)) {
            this.send(viewer, "no-permission");
            return true;
        }
        final Player target = onlinePlayer(args[0]);
        if (target == null) {
            this.send(viewer, "player-not-found", Text.var("player", args[0]));
            return true;
        }
        this.module.ctx().runOnceForPlayer(viewer, () -> {
            viewer.openInventory(target.getEnderChest());
            this.send(viewer, "enderchest-opened-other", Text.var("player", target.getName()));
        });
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        return args.length == 1 && sender.hasPermission(PERM_OTHERS) ? onlineNames(args[0]) : List.of();
    }
}

package core.amazake.core.modules.essentials;

import core.amazake.core.modules.essentials.TeleportRequests.Request;
import core.amazake.core.modules.essentials.TeleportRequests.Type;
import core.amazake.core.util.Text;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Запросы телепорта: {@code /tpa <игрок>}, {@code /tpahere <игрок>}, {@code /tpaccept [игрок]},
 * {@code /tpdeny [игрок]}, {@code /tpcancel}. Запросы живут в памяти и истекают по таймауту (чистит таймер модуля).
 */
final class TpaCommand extends EssCommand {

    enum Action {
        TPA("tpa", "Запрос телепорта к игроку", "/tpa <игрок>"),
        TPAHERE("tpahere", "Позвать игрока к себе", "/tpahere <игрок>"),
        ACCEPT("tpaccept", "Принять запрос телепорта", "/tpaccept [игрок]"),
        DENY("tpdeny", "Отклонить запрос телепорта", "/tpdeny [игрок]"),
        CANCEL("tpcancel", "Отменить свой запрос", "/tpcancel");

        final String label;
        final String description;
        final String usage;

        Action(final String label, final String description, final String usage) {
            this.label = label;
            this.description = description;
            this.usage = usage;
        }
    }

    static final String PERM = "amazakecore.essentials.tpa";

    private final Action action;

    TpaCommand(final EssentialsModule module, final Action action) {
        super(module, action.label, action.description, action.usage, List.of(), PERM);
        this.action = action;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        final Player player = this.requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (!this.settings().tpaEnabled()) {
            this.send(player, "feature-disabled");
            return true;
        }
        switch (this.action) {
            case TPA -> this.request(player, args, Type.TPA);
            case TPAHERE -> this.request(player, args, Type.TPAHERE);
            case ACCEPT -> this.respond(player, args, true);
            case DENY -> this.respond(player, args, false);
            case CANCEL -> this.cancel(player);
            default -> { }
        }
        return true;
    }

    private void request(final Player requester, final String[] args, final Type type) {
        if (args.length < 1) {
            requester.sendMessage(Text.parse("<gray>" + this.getUsage()));
            return;
        }
        final Player target = onlinePlayer(args[0]);
        if (target == null) {
            this.send(requester, "player-not-found", Text.var("player", args[0]));
            return;
        }
        if (target.getUniqueId().equals(requester.getUniqueId())) {
            this.send(requester, "tpa-self");
            return;
        }
        final int timeout = this.settings().tpaTimeoutSeconds();
        final long expiry = System.currentTimeMillis() + timeout * 1000L;
        this.module.requests().put(new Request(requester.getUniqueId(), requester.getName(),
            target.getUniqueId(), target.getName(), type, expiry));

        this.send(requester, type == Type.TPA ? "tpa-sent" : "tpahere-sent",
            Text.var("player", target.getName()), Text.var("seconds", String.valueOf(timeout)));
        // Получателя трогаем в его потоке.
        this.module.ctx().runOnceForPlayer(target, () ->
            target.sendMessage(this.module.parse(type == Type.TPA ? "tpa-received" : "tpahere-received",
                Text.var("player", requester.getName()))));
    }

    private void respond(final Player target, final String[] args, final boolean accept) {
        final Request request = this.module.requests().get(target.getUniqueId());
        if (request == null || (args.length >= 1 && !request.requesterName().equalsIgnoreCase(args[0]))) {
            this.send(target, "tpa-no-request");
            return;
        }
        // Снимаем только если это всё ещё тот самый запрос (не перезаписан новым).
        this.module.requests().remove(target.getUniqueId());
        final Player requester = onlinePlayer(request.requesterName());

        if (!accept) {
            this.send(target, "tpa-denied-target", Text.var("player", request.requesterName()));
            if (requester != null) {
                this.module.ctx().runOnceForPlayer(requester, () ->
                    requester.sendMessage(this.module.parse("tpa-denied-requester",
                        Text.var("player", target.getName()))));
            }
            return;
        }
        if (requester == null) {
            this.send(target, "player-not-found", Text.var("player", request.requesterName()));
            return;
        }
        this.send(target, "tpa-accepted-target", Text.var("player", requester.getName()));
        this.module.ctx().runOnceForPlayer(requester, () ->
            requester.sendMessage(this.module.parse("tpa-accepted-requester", Text.var("player", target.getName()))));

        // TPA: отправитель -> к получателю. TPAHERE: получатель -> к отправителю.
        if (request.type() == Type.TPA) {
            this.module.teleportToPlayer(requester, target, null);
        } else {
            this.module.teleportToPlayer(target, requester, null);
        }
    }

    private void cancel(final Player requester) {
        final Request removed = this.module.requests().removeByRequester(requester.getUniqueId());
        if (removed == null) {
            this.send(requester, "tpa-no-request");
            return;
        }
        this.send(requester, "tpa-cancelled");
        final Player target = onlinePlayer(removed.targetName());
        if (target != null) {
            this.module.ctx().runOnceForPlayer(target, () ->
                target.sendMessage(this.module.parse("tpa-denied-target", Text.var("player", requester.getName()))));
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1 && (this.action == Action.TPA || this.action == Action.TPAHERE
            || this.action == Action.ACCEPT || this.action == Action.DENY)) {
            return onlineNames(args[0]);
        }
        return List.of();
    }
}

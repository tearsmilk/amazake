package core.amazake.core.modules.npc;

import core.amazake.core.framework.ModuleContext;
import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /npccore} — puts player skins on Citizens NPCs. Admin-only ({@code amazakecore.npc.admin}). Parsing is in
 * {@link NpcArgs}; the Citizens calls go through {@link CitizensBridge} on the global region scheduler (world work),
 * and the result is reported back on the sender's own thread.
 */
final class NpcCommand extends Command {

    private final ModuleContext context;
    private final CitizensBridge citizens;

    NpcCommand(final ModuleContext context, final CitizensBridge citizens) {
        super("npccore", "Скины игроков на NPC Citizens", "/npccore skin <игрок>", List.of());
        this.context = context;
        this.citizens = citizens;
        this.setPermission("amazakecore.npc.admin");
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        try {
            final NpcArgs.Result result = NpcArgs.parse(args);
            if (!result.isOk()) {
                reply(sender, "<red>" + Text.escape(result.error()) + "</red>");
                return true;
            }
            final NpcArgs parsed = result.args();
            switch (parsed.action()) {
                case SKIN_SELECTED -> dispatch(sender, null, parsed.value(),
                    "Нет выбранного NPC. Левый клик по NPC инструментом Citizens, затем /npccore skin <игрок>.");
                case SKIN_BY_ID -> dispatch(sender, parsed.npcId(), parsed.value(),
                    "NPC с номером " + parsed.npcId() + " не найден.");
                case SKIN_URL -> dispatch(sender, null, parsed.value(),
                    "Нет выбранного NPC. Выберите NPC и повторите /npccore skinurl <значение>.");
                default -> reply(sender, "<red>Неизвестное действие.</red>");
            }
        } catch (final Throwable t) {
            // The command must never throw out: log and tell the sender.
            this.context.logger().warning("Ошибка в /npccore: " + t);
            reply(sender, "<red>Внутренняя ошибка команды, подробности в консоли.</red>");
        }
        return true;
    }

    /**
     * Resolves the NPC and applies the skin on the global region scheduler (changing NPC data touches the world),
     * then reports back on the sender's thread. {@code npcId == null} means "the sender's selected NPC".
     */
    private void dispatch(final CommandSender sender, final Integer npcId, final String skin, final String notFound) {
        Bukkit.getGlobalRegionScheduler().execute(this.context.plugin(), () -> {
            String message;
            try {
                final Object npc = npcId == null ? this.citizens.selectedNpc(sender) : this.citizens.npcById(npcId);
                if (npc == null) {
                    message = "<red>" + Text.escape(notFound) + "</red>";
                } else if (this.citizens.setSkin(npc, skin)) {
                    message = "<green>Скин <white>" + Text.escape(this.citizens.describe(npc))
                        + "</white> изменён на <white>" + Text.escape(skin) + "</white>.</green>";
                } else {
                    message = "<red>Не удалось изменить скин (API Citizens отличается от ожидаемого).</red>";
                }
            } catch (final Throwable t) {
                this.context.logger().warning("Ошибка при смене скина NPC: " + t);
                message = "<red>Внутренняя ошибка при обращении к Citizens, подробности в консоли.</red>";
            }
            reply(sender, message);
        });
    }

    /** Sends a MiniMessage line to the sender; for a player, on that player's own thread. */
    private void reply(final CommandSender sender, final String miniMessage) {
        final Component component = Text.parse(miniMessage);
        if (sender instanceof Player player) {
            if (!this.context.runOnceForPlayer(player, () -> player.sendMessage(component))) {
                // Player left: nothing to do.
                return;
            }
        } else {
            sender.sendMessage(component);
        }
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (sender != null && getPermission() != null && !sender.hasPermission(getPermission())) {
            return List.of();
        }
        if (args.length == 1) {
            return prefix(args[0], List.of("skin", "skinurl"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("skin")) {
            final List<String> options = new ArrayList<>(onlineNames());
            options.add("id");
            return prefix(args[1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("skin") && args[1].equalsIgnoreCase("id")) {
            return List.of(); // the numeric id is free-form
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("skin") && args[1].equalsIgnoreCase("id")) {
            return prefix(args[3], onlineNames());
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        final List<String> names = new ArrayList<>();
        for (final Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private static List<String> prefix(final String token, final List<String> options) {
        final String lower = token.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}

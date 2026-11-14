package core.amazake.core.modules.groups;

import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * {@code /groupuser <игрок> ...} — членство в группах и личные права игрока (возможно оффлайн). Хранение по UUID.
 * Право {@code amazakecore.groups.admin}.
 */
public final class GroupUserCommand extends Command {

    private final GroupEngine engine;

    public GroupUserCommand(final GroupEngine engine) {
        super("groupuser", "Группы и права игрока", "/groupuser <игрок> <group|perm|info> ...", List.of());
        this.setPermission("amazakecore.groups.admin");
        this.engine = engine;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /groupuser <игрок> <group|perm|info> ..."));
            return true;
        }
        final String playerName = args[0];
        @SuppressWarnings("deprecation")
        final OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        final UUID id = target.getUniqueId();
        final String section = args[1].toLowerCase(Locale.ROOT);
        switch (section) {
            case "group" -> group(sender, args, id, playerName);
            case "perm" -> perm(sender, args, id, playerName);
            case "info" -> info(sender, id, playerName);
            default -> sender.sendMessage(Text.parse("<red>group, perm или info."));
        }
        return true;
    }

    private void group(final CommandSender sender, final String[] args, final UUID id, final String name) {
        // /groupuser <p> group set|add|remove <g>
        if (args.length < 4) {
            sender.sendMessage(Text.parse("<red>Использование: /groupuser <игрок> group <set|add|remove> <группа>"));
            return;
        }
        final String action = args[2].toLowerCase(Locale.ROOT);
        final String g = GroupLogic.norm(args[3]);
        if (!this.engine.groupExists(g)) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[3]) + "</white>."));
            return;
        }
        final boolean ok;
        final String verb;
        switch (action) {
            case "set" -> {
                ok = this.engine.setPlayerGroup(id, g);
                verb = "установлена группа";
            }
            case "add" -> {
                ok = this.engine.addPlayerGroup(id, g);
                verb = "добавлена группа";
            }
            case "remove" -> {
                ok = this.engine.removePlayerGroup(id, g);
                verb = "убрана группа";
            }
            default -> {
                sender.sendMessage(Text.parse("<red>set, add или remove."));
                return;
            }
        }
        sender.sendMessage(ok
            ? Text.parse("<green>Игроку <white>" + Text.escape(name) + "</white> " + verb + " <white>" + g)
            : Text.parse("<yellow>Без изменений."));
    }

    private void perm(final CommandSender sender, final String[] args, final UUID id, final String name) {
        // /groupuser <p> perm add|remove <node>
        if (args.length < 4) {
            sender.sendMessage(Text.parse("<red>Использование: /groupuser <игрок> perm <add|remove> <право>"));
            return;
        }
        final String action = args[2].toLowerCase(Locale.ROOT);
        final String node = GroupLogic.norm(args[3]);
        final boolean ok;
        if (action.equals("add")) {
            ok = this.engine.addPlayerPerm(id, node);
        } else if (action.equals("remove")) {
            ok = this.engine.removePlayerPerm(id, node);
        } else {
            sender.sendMessage(Text.parse("<red>add или remove."));
            return;
        }
        sender.sendMessage(ok
            ? Text.parse("<green>Личное право <white>" + Text.escape(node) + "</white> "
                + (action.equals("add") ? "добавлено" : "убрано") + " игроку <white>" + Text.escape(name))
            : Text.parse("<yellow>Без изменений."));
    }

    private void info(final CommandSender sender, final UUID id, final String name) {
        sender.sendMessage(Text.parse("<gold>Игрок <white>" + Text.escape(name) + " <gray>(" + id + ")"));
        sender.sendMessage(Text.parse("<gray>основная группа: <white>" + this.engine.primaryGroup(id)
            + " <gray>(вес " + this.engine.weight(id) + ")"));
        final List<String> member = new ArrayList<>(this.engine.memberGroups(id));
        sender.sendMessage(Text.parse("<gray>группы: <white>" + (member.isEmpty() ? "—" : String.join(", ", member))));
        final List<String> pp = new ArrayList<>(this.engine.personalPerms(id));
        sender.sendMessage(Text.parse("<gray>личные права (" + pp.size() + "): <white>"
            + (pp.isEmpty() ? "—" : String.join(", ", pp))));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final List<String> online = new ArrayList<>();
            for (final var p : Bukkit.getOnlinePlayers()) {
                online.add(p.getName());
            }
            return GroupCommand.filter(online, args[0]);
        }
        if (args.length == 2) {
            return GroupCommand.filter(List.of("group", "perm", "info"), args[1]);
        }
        final String section = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && section.equals("group")) {
            return GroupCommand.filter(List.of("set", "add", "remove"), args[2]);
        }
        if (args.length == 3 && section.equals("perm")) {
            return GroupCommand.filter(List.of("add", "remove"), args[2]);
        }
        if (args.length == 4 && section.equals("group")) {
            return GroupCommand.filter(new ArrayList<>(this.engine.groupNames()), args[3]);
        }
        return List.of();
    }
}

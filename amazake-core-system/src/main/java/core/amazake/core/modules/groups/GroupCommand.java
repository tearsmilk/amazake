package core.amazake.core.modules.groups;

import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * {@code /group} — управление группами: список, создание, удаление, вес, префикс/суффикс, права, наследование, инфо.
 * Право {@code amazakecore.groups.admin}. Весь вывод — на русском через MiniMessage.
 */
public final class GroupCommand extends Command {

    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final GroupEngine engine;

    public GroupCommand(final GroupEngine engine) {
        super("group", "Управление группами прав", "/group <list|create|delete|setweight|setprefix|"
            + "setsuffix|perm|parent|info>", List.of());
        this.setPermission("amazakecore.groups.admin");
        this.engine = engine;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.parse("<gray>" + this.getUsage()));
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "setweight" -> setWeight(sender, args);
            case "setprefix" -> setFix(sender, args, true);
            case "setsuffix" -> setFix(sender, args, false);
            case "perm" -> perm(sender, args);
            case "parent" -> parent(sender, args);
            case "info" -> info(sender, args);
            default -> sender.sendMessage(Text.parse("<gray>" + this.getUsage()));
        }
        return true;
    }

    private void list(final CommandSender sender) {
        final List<String> names = new ArrayList<>(this.engine.groupNames());
        names.sort((a, b) -> {
            final GroupData ga = this.engine.group(a);
            final GroupData gb = this.engine.group(b);
            final int wa = ga == null ? 0 : ga.weight();
            final int wb = gb == null ? 0 : gb.weight();
            return wa != wb ? Integer.compare(wb, wa) : a.compareTo(b);
        });
        sender.sendMessage(Text.parse("<gold>Группы (" + names.size() + "):"));
        for (final String n : names) {
            final GroupData g = this.engine.group(n);
            sender.sendMessage(Text.parse("<white>" + n + " <gray>вес=" + (g == null ? 0 : g.weight())
                + (g != null && !g.prefix().isEmpty() ? " <gray>префикс: " + g.prefix() : "")));
        }
    }

    private void create(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /group create <имя>"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(Text.parse("<red>Имя: a-z, 0-9, _ и -, до 32 символов."));
            return;
        }
        if (this.engine.createGroup(name)) {
            sender.sendMessage(Text.parse("<green>Группа <white>" + name + "</white> создана."));
        } else {
            sender.sendMessage(Text.parse("<yellow>Группа <white>" + name + "</white> уже есть."));
        }
    }

    private void delete(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /group delete <имя>"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (name.equals(this.engine.defaultGroup())) {
            sender.sendMessage(Text.parse("<red>Нельзя удалить группу по умолчанию."));
            return;
        }
        if (this.engine.deleteGroup(name)) {
            sender.sendMessage(Text.parse("<green>Группа <white>" + name + "</white> удалена."));
        } else {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
        }
    }

    private void setWeight(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /group setweight <группа> <число>"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (!this.engine.groupExists(name)) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
            return;
        }
        final int weight;
        try {
            weight = Integer.parseInt(args[2]);
        } catch (final NumberFormatException e) {
            sender.sendMessage(Text.parse("<red>Вес — целое число."));
            return;
        }
        this.engine.setWeight(name, weight);
        sender.sendMessage(Text.parse("<green>Вес группы <white>" + name + "</white> = <white>" + weight));
    }

    private void setFix(final CommandSender sender, final String[] args, final boolean prefix) {
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /group set" + (prefix ? "prefix" : "suffix")
                + " <группа> <MiniMessage...> (пусто — очистить)"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (!this.engine.groupExists(name)) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
            return;
        }
        final String value = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : "";
        if (prefix) {
            this.engine.setPrefix(name, value);
        } else {
            this.engine.setSuffix(name, value);
        }
        sender.sendMessage(Text.parse("<green>" + (prefix ? "Префикс" : "Суффикс") + " группы <white>" + name
            + "</white>: " + (value.isEmpty() ? "<gray>(пусто)" : value)));
    }

    private void perm(final CommandSender sender, final String[] args) {
        // /group perm <группа> add|remove <узел>
        if (args.length < 4) {
            sender.sendMessage(Text.parse("<red>Использование: /group perm <группа> <add|remove> <право>"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (!this.engine.groupExists(name)) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
            return;
        }
        final String action = args[2].toLowerCase(Locale.ROOT);
        final String node = GroupLogic.norm(args[3]);
        final boolean ok;
        if (action.equals("add")) {
            ok = this.engine.addGroupPerm(name, node);
        } else if (action.equals("remove")) {
            ok = this.engine.removeGroupPerm(name, node);
        } else {
            sender.sendMessage(Text.parse("<red>add или remove."));
            return;
        }
        sender.sendMessage(ok
            ? Text.parse("<green>Право <white>" + Text.escape(node) + "</white> "
                + (action.equals("add") ? "добавлено группе" : "убрано у группы") + " <white>" + name)
            : Text.parse("<yellow>Без изменений."));
    }

    private void parent(final CommandSender sender, final String[] args) {
        // /group parent <группа> add|remove <родитель>
        if (args.length < 4) {
            sender.sendMessage(Text.parse("<red>Использование: /group parent <группа> <add|remove> <родитель>"));
            return;
        }
        final String name = GroupLogic.norm(args[1]);
        if (!this.engine.groupExists(name)) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
            return;
        }
        final String action = args[2].toLowerCase(Locale.ROOT);
        final String parent = GroupLogic.norm(args[3]);
        final boolean ok;
        if (action.equals("add")) {
            if (!this.engine.groupExists(parent)) {
                sender.sendMessage(Text.parse("<red>Нет группы-родителя <white>" + Text.escape(args[3]) + "</white>."));
                return;
            }
            ok = this.engine.addParent(name, parent);
        } else if (action.equals("remove")) {
            ok = this.engine.removeParent(name, parent);
        } else {
            sender.sendMessage(Text.parse("<red>add или remove."));
            return;
        }
        sender.sendMessage(ok
            ? Text.parse("<green>Наследование <white>" + name + "</white> "
                + (action.equals("add") ? "+" : "-") + " <white>" + parent)
            : Text.parse("<yellow>Без изменений (возможно, цикл или уже так)."));
    }

    private void info(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /group info <группа>"));
            return;
        }
        final GroupData g = this.engine.group(GroupLogic.norm(args[1]));
        if (g == null) {
            sender.sendMessage(Text.parse("<red>Нет группы <white>" + Text.escape(args[1]) + "</white>."));
            return;
        }
        sender.sendMessage(Text.parse("<gold>Группа <white>" + g.name()));
        sender.sendMessage(Text.parse("<gray>вес: <white>" + g.weight()));
        sender.sendMessage(Text.parse("<gray>префикс: " + (g.prefix().isEmpty() ? "<gray>(пусто)" : g.prefix())));
        sender.sendMessage(Text.parse("<gray>суффикс: " + (g.suffix().isEmpty() ? "<gray>(пусто)" : g.suffix())));
        sender.sendMessage(Text.parse("<gray>родители: <white>"
            + (g.parents().isEmpty() ? "—" : String.join(", ", g.parents()))));
        sender.sendMessage(Text.parse("<gray>права (" + g.perms().size() + "): <white>"
            + (g.perms().isEmpty() ? "—" : String.join(", ", g.perms()))));
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            return filter(List.of("list", "create", "delete", "setweight", "setprefix", "setsuffix",
                "perm", "parent", "info"), args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !sub.equals("create") && !sub.equals("list")) {
            return filter(new ArrayList<>(this.engine.groupNames()), args[1]);
        }
        if (args.length == 3 && (sub.equals("perm") || sub.equals("parent"))) {
            return filter(List.of("add", "remove"), args[2]);
        }
        if (args.length == 4 && sub.equals("parent")) {
            return filter(new ArrayList<>(this.engine.groupNames()), args[3]);
        }
        return List.of();
    }

    static List<String> filter(final List<String> options, final String typed) {
        final String t = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) {
                out.add(o);
            }
        }
        return out;
    }
}

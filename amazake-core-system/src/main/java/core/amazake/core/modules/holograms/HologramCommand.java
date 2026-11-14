package core.amazake.core.modules.holograms;

import core.amazake.core.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /hologram} — управление голограммами. Право {@code amazakecore.holograms.admin}. Все сообщения на русском
 * (MiniMessage через {@link Text}); пользовательский текст строк не экранируется (строки — это и есть MiniMessage,
 * их пишет администратор), а имена — обычный текст, вставляется через {@link Text#escape}.
 *
 * <p>Команда выполняется в потоке игрока (или в глобальном — из консоли). Память меняется сразу, база пишется
 * асинхронно через модуль, а сущности создаются/удаляются в потоке региона внутри методов модуля.</p>
 */
final class HologramCommand extends Command {

    private static final List<String> SUBS = List.of("create", "delete", "list", "near", "addline", "setline",
        "removeline", "lines", "movehere", "tp", "setengine", "setitem", "reload");

    private final HologramsModule module;

    HologramCommand(final HologramsModule module) {
        super("hologram", "Управление голограммами",
            "/hologram <create|delete|list|near|addline|setline|removeline|lines|movehere|tp|setengine|setitem|reload>",
            List.of("holo", "hd"));
        this.setPermission("amazakecore.holograms.admin");
        this.module = module;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "list" -> list(sender);
            case "near" -> near(sender, args);
            case "addline" -> addline(sender, args);
            case "setline" -> setline(sender, args);
            case "removeline" -> removeline(sender, args);
            case "lines" -> lines(sender, args);
            case "movehere" -> movehere(sender, args);
            case "tp" -> tp(sender, args);
            case "setengine" -> setengine(sender, args);
            case "setitem" -> setitem(sender, args);
            case "reload" -> reload(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    // ---- subcommands ----

    private void create(final CommandSender sender, final String[] args) {
        final Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram create <имя> [текст…]"));
            return;
        }
        final String name = HologramText.normalizeName(args[1]);
        if (!HologramText.validName(name)) {
            sender.sendMessage(Text.parse("<red>Имя: латиница/цифры/дефис/подчёркивание, до "
                + HologramText.MAX_NAME + " символов."));
            return;
        }
        if (module.holograms().containsKey(name)) {
            sender.sendMessage(Text.parse("<red>Голограмма «" + esc(name) + "» уже есть."));
            return;
        }
        final List<String> lines = args.length > 2 ? List.of(joinFrom(args, 2)) : List.of();
        final Location loc = player.getLocation();
        final HologramsModule.Settings st = module.settings();
        final String item = st.defaultEngine() == HologramEngine.ITEM_DISPLAY ? st.defaultItem() : null;
        final Hologram h = new Hologram(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
            st.defaultEngine(), item, lines);
        module.holograms().put(name, h);
        module.save(h);
        module.respawn(h);
        sender.sendMessage(Text.parse("<green>Голограмма «<white>" + esc(name)
            + "</white>» создана (движок <white>" + h.engine().id() + "</white>)."));
    }

    private void delete(final CommandSender sender, final String[] args) {
        final Hologram h = require(sender, args);
        if (h == null) {
            return;
        }
        module.holograms().remove(h.name());
        module.despawn(h);
        module.deleteFromDb(h.name());
        sender.sendMessage(Text.parse("<green>Голограмма «<white>" + esc(h.name()) + "</white>» удалена."));
    }

    private void list(final CommandSender sender) {
        if (module.holograms().isEmpty()) {
            sender.sendMessage(Text.parse("<gray>Голограмм нет."));
            return;
        }
        sender.sendMessage(Text.parse("<gold>Голограммы (<white>" + module.holograms().size() + "</white>):"));
        for (final Hologram h : module.holograms().values()) {
            sender.sendMessage(Text.parse("<gray>• <white>" + esc(h.name()) + " <gray>— " + h.engine().id()
                + ", строк: " + h.lines().size() + ", мир " + esc(h.world())));
        }
    }

    private void near(final CommandSender sender, final String[] args) {
        final Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        double radius = 50;
        if (args.length >= 2) {
            try {
                radius = Double.parseDouble(args[1]);
            } catch (final NumberFormatException e) {
                sender.sendMessage(Text.parse("<red>Радиус — число."));
                return;
            }
        }
        final double r2 = radius * radius;
        final Location loc = player.getLocation();
        final String world = loc.getWorld().getName();
        final List<String> found = new ArrayList<>();
        for (final Hologram h : module.holograms().values()) {
            if (!h.world().equals(world)) {
                continue;
            }
            final double dx = h.x() - loc.getX();
            final double dy = h.y() - loc.getY();
            final double dz = h.z() - loc.getZ();
            final double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2) {
                found.add("<gray>• <white>" + esc(h.name()) + " <gray>(" + Math.round(Math.sqrt(d2)) + " бл.)");
            }
        }
        if (found.isEmpty()) {
            sender.sendMessage(Text.parse("<gray>В радиусе " + (long) radius + " блоков голограмм нет."));
            return;
        }
        sender.sendMessage(Text.parse("<gold>Рядом (" + found.size() + "):"));
        found.forEach(line -> sender.sendMessage(Text.parse(line)));
    }

    private void addline(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram addline <имя> <текст…>"));
            return;
        }
        final Hologram h = requireName(sender, args[1]);
        if (h == null) {
            return;
        }
        final Hologram updated = h.withLines(HologramLines.added(h.lines(), joinFrom(args, 2)));
        applyEdit(sender, updated, "Строка добавлена (всего " + updated.lines().size() + ").");
    }

    private void setline(final CommandSender sender, final String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram setline <имя> <номер> <текст…>"));
            return;
        }
        final Hologram h = requireName(sender, args[1]);
        if (h == null) {
            return;
        }
        final Integer idx = parseIndex(sender, args[2], h);
        if (idx == null) {
            return;
        }
        final Hologram updated = h.withLines(HologramLines.set(h.lines(), idx, joinFrom(args, 3)));
        applyEdit(sender, updated, "Строка " + idx + " изменена.");
    }

    private void removeline(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram removeline <имя> <номер>"));
            return;
        }
        final Hologram h = requireName(sender, args[1]);
        if (h == null) {
            return;
        }
        final Integer idx = parseIndex(sender, args[2], h);
        if (idx == null) {
            return;
        }
        final Hologram updated = h.withLines(HologramLines.removed(h.lines(), idx));
        applyEdit(sender, updated, "Строка " + idx + " удалена (осталось " + updated.lines().size() + ").");
    }

    private void lines(final CommandSender sender, final String[] args) {
        final Hologram h = require(sender, args);
        if (h == null) {
            return;
        }
        if (h.lines().isEmpty()) {
            sender.sendMessage(Text.parse("<gray>У «" + esc(h.name()) + "» нет строк."));
            return;
        }
        sender.sendMessage(Text.parse("<gold>Строки «<white>" + esc(h.name()) + "</white>»:"));
        final List<String> ls = h.lines();
        for (int i = 0; i < ls.size(); i++) {
            // Показываем сырой MiniMessage-текст строки, экранируя его, чтобы не рендерился как разметка.
            sender.sendMessage(Text.parse("<gray>" + (i + 1) + ": <white>" + Text.escape(ls.get(i))));
        }
    }

    private void movehere(final CommandSender sender, final String[] args) {
        final Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        final Hologram h = require(sender, args);
        if (h == null) {
            return;
        }
        module.despawn(h);
        final Location loc = player.getLocation();
        final Hologram moved = h.withLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        module.holograms().put(moved.name(), moved);
        module.save(moved);
        module.respawn(moved);
        sender.sendMessage(Text.parse("<green>Голограмма «<white>" + esc(h.name()) + "</white>» перемещена к вам."));
    }

    private void tp(final CommandSender sender, final String[] args) {
        final Player player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        final Hologram h = require(sender, args);
        if (h == null) {
            return;
        }
        final var world = org.bukkit.Bukkit.getWorld(h.world());
        if (world == null) {
            sender.sendMessage(Text.parse("<red>Мир «" + esc(h.world()) + "» не загружен."));
            return;
        }
        // Телепорт — работа с игроком; команда уже в его потоке. teleportAsync безопасен между регионами.
        player.teleportAsync(new Location(world, h.x(), h.y(), h.z()));
        sender.sendMessage(Text.parse("<green>Телепорт к «<white>" + esc(h.name()) + "</white>»."));
    }

    private void setengine(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram setengine <имя> <armor_stand|text_display|item_display>"));
            return;
        }
        final Hologram h = requireName(sender, args[1]);
        if (h == null) {
            return;
        }
        final HologramEngine engine = HologramEngine.parseOrNull(args[2]);
        if (engine == null) {
            sender.sendMessage(Text.parse("<red>Движок: armor_stand, text_display или item_display."));
            return;
        }
        Hologram updated = h.withEngine(engine);
        if (engine == HologramEngine.ITEM_DISPLAY && updated.item() == null) {
            updated = updated.withItem(module.settings().defaultItem());
        }
        applyEdit(sender, updated, "Движок: " + engine.id() + ".");
    }

    private void setitem(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.parse("<red>Использование: /hologram setitem <имя> <МАТЕРИАЛ>"));
            return;
        }
        final Hologram h = requireName(sender, args[1]);
        if (h == null) {
            return;
        }
        final Material material = Material.matchMaterial(args[2]);
        if (material == null || !material.isItem()) {
            sender.sendMessage(Text.parse("<red>Неизвестный материал «" + esc(args[2]) + "»."));
            return;
        }
        applyEdit(sender, h.withItem(material.name()), "Предмет: " + material.name() + ".");
    }

    private void reload(final CommandSender sender, final String[] args) {
        final Hologram h = require(sender, args);
        if (h == null) {
            return;
        }
        module.respawn(h);
        sender.sendMessage(Text.parse("<green>Голограмма «<white>" + esc(h.name()) + "</white>» пересоздана."));
    }

    // ---- shared helpers ----

    /** Применяет изменённую голограмму: память + база + пересоздание сущностей. */
    private void applyEdit(final CommandSender sender, final Hologram updated, final String message) {
        module.holograms().put(updated.name(), updated);
        module.save(updated);
        module.respawn(updated);
        sender.sendMessage(Text.parse("<green>" + message));
    }

    private Player requirePlayer(final CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Text.parse("<red>Только для игрока (нужна ваша позиция)."));
        return null;
    }

    /** Берёт голограмму из {@code args[1]}. */
    private Hologram require(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.parse("<red>Укажите имя голограммы."));
            return null;
        }
        return requireName(sender, args[1]);
    }

    private Hologram requireName(final CommandSender sender, final String raw) {
        final Hologram h = module.holograms().get(HologramText.normalizeName(raw));
        if (h == null) {
            sender.sendMessage(Text.parse("<red>Нет голограммы «" + esc(raw) + "». Список: /hologram list"));
        }
        return h;
    }

    private Integer parseIndex(final CommandSender sender, final String raw, final Hologram h) {
        final int idx;
        try {
            idx = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            sender.sendMessage(Text.parse("<red>Номер строки — целое число."));
            return null;
        }
        if (!HologramLines.validIndex(h.lines(), idx)) {
            sender.sendMessage(Text.parse("<red>Нет строки " + idx + " (всего " + h.lines().size() + ")."));
            return null;
        }
        return idx;
    }

    private static String joinFrom(final String[] args, final int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    private static String esc(final String raw) {
        return Text.escape(raw);
    }

    private void usage(final CommandSender sender) {
        sender.sendMessage(Text.parse("<gray>" + this.getUsage()));
    }

    // ---- tab completion ----

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "delete", "addline", "setline", "removeline", "lines", "movehere", "tp", "setengine", "setitem",
                    "reload" -> {
                    return filter(new ArrayList<>(module.holograms().keySet()), args[1]);
                }
                default -> {
                    return List.of();
                }
            }
        }
        if (args.length == 3 && sub.equals("setengine")) {
            return filter(List.of("armor_stand", "text_display", "item_display"), args[2]);
        }
        if (args.length == 3 && sub.equals("setitem")) {
            return List.of("DIAMOND", "EMERALD", "GOLD_INGOT", "NETHER_STAR", "DIAMOND_BLOCK");
        }
        return List.of();
    }

    private static List<String> filter(final List<String> options, final String typed) {
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

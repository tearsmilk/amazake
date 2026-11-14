package core.amazake.core.modules.holograms;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Модуль голограмм: парящий текст тремя движками (armor_stand / text_display / item_display), хранится в базе и
 * восстанавливается при включении, убирается при выключении.
 *
 * <p><b>Потоки.</b> Сущности создаются и удаляются только в потоке региона их локации
 * ({@code Bukkit.getRegionScheduler().execute(plugin, location, …)}). Память — потокобезопасные
 * {@code ConcurrentHashMap} с неизменяемыми {@link Hologram}. База — единственный блокирующий I/O, идёт через
 * {@code context.database()} (свой поток, {@code CompletableFuture}). Каждая сущность помечается PDC-ключом с
 * именем голограммы, поэтому при включении «осиротевшие» сущности находятся и удаляются до восстановления.</p>
 */
public final class HologramsModule implements Module, Listener {

    /** Параметры из конфига; подменяются целиком на reload. */
    public record Settings(HologramEngine defaultEngine, String defaultItem, double lineSpacing) {
        public static final Settings DEFAULTS = new Settings(HologramEngine.TEXT_DISPLAY, "DIAMOND", 0.25);
    }

    private ModuleContext context;
    private NamespacedKey nameKey;
    /** Модуль активен: гасит асинхронные колбэки БД и регионные задачи после выключения. */
    private volatile boolean enabled;

    /** Имя (ключ, нижний регистр) -> голограмма. Источник истины в памяти. */
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    /** Имя -> идентификаторы созданных сущностей (для точного удаления). */
    private final Map<String, Set<UUID>> spawned = new ConcurrentHashMap<>();
    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);

    @Override
    public String id() {
        return "holograms";
    }

    @Override
    public String description() {
        return "голограммы на armor stand или item/text display";
    }

    // ---- lifecycle ----

    @Override
    public void onEnable(final ModuleContext context) throws Exception {
        this.context = context;
        this.nameKey = new NamespacedKey(context.plugin(), "hologram");
        this.settings.set(parseSettings(context.config()));

        context.database().ddl(
            "CREATE TABLE IF NOT EXISTS holo_holograms("
                + "name VARCHAR(32) PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                + "engine VARCHAR(16) NOT NULL, item VARCHAR(64))",
            "CREATE TABLE IF NOT EXISTS holo_lines("
                + "name VARCHAR(32) NOT NULL, idx INT NOT NULL, content VARCHAR(512) NOT NULL, "
                + "PRIMARY KEY(name, idx))");

        context.registerListener(this);
        context.registerCommand(new HologramCommand(this));

        this.enabled = true;
        loadAll();
    }

    @Override
    public void onDisable() {
        // Убираем всё, что ядро убрать не может: реальные сущности в мире. Планируем удаление в регионе каждой
        // голограммы; при остановке сервера сущности и так пропадут (мы их не сохраняем в чанк).
        this.enabled = false;
        for (final Hologram h : holograms.values()) {
            despawn(h);
        }
        holograms.clear();
        spawned.clear();
    }

    @Override
    public void onConfigReload(final ConfigurationSection section) {
        this.settings.set(parseSettings(section));
        // Пересоздаём голограммы, чтобы новый line-spacing применился сразу.
        for (final Hologram h : holograms.values()) {
            respawn(h);
        }
    }

    ModuleContext context() {
        return this.context;
    }

    Settings settings() {
        return this.settings.get();
    }

    Map<String, Hologram> holograms() {
        return this.holograms;
    }

    private Settings parseSettings(final ConfigurationSection s) {
        if (s == null) {
            return Settings.DEFAULTS;
        }
        final HologramEngine engine = HologramEngine.parse(s.getString("default-engine"), HologramEngine.TEXT_DISPLAY);
        String item = s.getString("default-item", "DIAMOND");
        if (Material.matchMaterial(item) == null) {
            warn("default-item: неизвестный материал «" + item + "», беру DIAMOND");
            item = "DIAMOND";
        }
        double spacing = s.getDouble("line-spacing", 0.25);
        if (spacing <= 0 || spacing > 5) {
            warn("line-spacing: " + spacing + " вне (0..5], беру 0.25");
            spacing = 0.25;
        }
        return new Settings(engine, item, spacing);
    }

    private void warn(final String message) {
        if (context != null) {
            context.logger().warning(message);
        }
    }

    // ---- loading / persistence ----

    private void loadAll() {
        final List<Hologram> heads = new ArrayList<>();
        context.database().queryList("SELECT name, world, x, y, z, engine, item FROM holo_holograms",
            rs -> new Hologram(rs.getString("name"), rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                HologramEngine.parse(rs.getString("engine"), HologramEngine.TEXT_DISPLAY),
                rs.getString("item"), List.of()))
            .thenCompose(rows -> {
                heads.addAll(rows);
                return context.database().queryList(
                    "SELECT name, idx, content FROM holo_lines ORDER BY name, idx",
                    rs -> new String[] {rs.getString("name"), rs.getString("content")});
            })
            .thenAccept(lineRows -> {
                if (!this.enabled) {
                    return; // модуль успели выключить, пока грузились данные — ничего не спавним
                }
                final Map<String, List<String>> byName = new java.util.HashMap<>();
                for (final String[] row : lineRows) {
                    byName.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
                }
                for (final Hologram head : heads) {
                    final Hologram full = head.withLines(byName.getOrDefault(head.name(), List.of()));
                    holograms.put(full.name(), full);
                }
                respawnAll();
            })
            .exceptionally(t -> {
                context.logger().log(Level.SEVERE, "Не удалось загрузить голограммы из базы", t);
                return null;
            });
    }

    /** Сохраняет шапку и строки голограммы (fire-and-forget, ошибки в лог). */
    void save(final Hologram h) {
        context.database().write(
            "MERGE INTO holo_holograms(name, world, x, y, z, engine, item) KEY(name) VALUES(?,?,?,?,?,?,?)",
            h.name(), h.world(), h.x(), h.y(), h.z(), h.engine().id(), h.item())
            .thenCompose(ignored -> context.database().write("DELETE FROM holo_lines WHERE name=?", h.name()))
            .thenCompose(ignored -> {
                var future = java.util.concurrent.CompletableFuture.<Integer>completedFuture(0);
                final List<String> lines = h.lines();
                for (int i = 0; i < lines.size(); i++) {
                    final int idx = i;
                    future = future.thenCompose(x -> context.database().write(
                        "INSERT INTO holo_lines(name, idx, content) VALUES(?,?,?)", h.name(), idx, lines.get(idx)));
                }
                return future;
            })
            .exceptionally(t -> {
                context.logger().log(Level.WARNING, "Не удалось сохранить голограмму " + h.name(), t);
                return null;
            });
    }

    void deleteFromDb(final String name) {
        context.database().write("DELETE FROM holo_lines WHERE name=?", name)
            .thenCompose(ignored -> context.database().write("DELETE FROM holo_holograms WHERE name=?", name))
            .exceptionally(t -> {
                context.logger().log(Level.WARNING, "Не удалось удалить голограмму " + name + " из базы", t);
                return null;
            });
    }

    // ---- spawning (region threads) ----

    private void respawnAll() {
        // Каждую голограмму восстанавливаем в потоке её региона (чтение состояния чанка — тоже там).
        // Голограммы в ещё не загруженных чанках появятся по ChunkLoadEvent (он приходит в потоке региона чанка).
        for (final Hologram h : holograms.values()) {
            respawn(h);
        }
    }

    /** На потоке региона чанка: убрать осиротевшие наши сущности и заново создать голограммы этого чанка. */
    private void handleChunk(final World world, final Chunk chunk) {
        for (final Entity e : chunk.getEntities()) {
            if (isOurs(e)) {
                e.remove();
            }
        }
        for (final Hologram h : holograms.values()) {
            if (inChunk(h, world, chunk)) {
                spawned.remove(h.name());
                doSpawn(h, locationOf(h, world));
            }
        }
    }

    /** Пересоздать одну голограмму (если её чанк загружен). */
    void respawn(final Hologram h) {
        final World world = Bukkit.getWorld(h.world());
        if (world == null) {
            spawned.remove(h.name());
            return;
        }
        final int cx = (int) Math.floor(h.x()) >> 4;
        final int cz = (int) Math.floor(h.z()) >> 4;
        final Location loc = locationOf(h, world);
        // Состояние чанка читаем на потоке региона этой локации, не на вызывающем потоке.
        Bukkit.getRegionScheduler().execute(context.plugin(), loc, () -> {
            if (!this.enabled) {
                return;
            }
            if (!world.isChunkLoaded(cx, cz)) {
                spawned.remove(h.name());
                return; // появится по ChunkLoadEvent, когда чанк загрузится
            }
            removeOursIn(loc, h.name());
            doSpawn(h, loc);
        });
    }

    /** Убрать сущности голограммы (на потоке её региона). */
    void despawn(final Hologram h) {
        final World world = Bukkit.getWorld(h.world());
        spawned.remove(h.name());
        if (world == null) {
            return;
        }
        final int cx = (int) Math.floor(h.x()) >> 4;
        final int cz = (int) Math.floor(h.z()) >> 4;
        final Location loc = locationOf(h, world);
        // Состояние чанка читаем на потоке региона локации.
        Bukkit.getRegionScheduler().execute(context.plugin(), loc, () -> {
            if (world.isChunkLoaded(cx, cz)) {
                removeOursIn(loc, h.name());
            }
        });
    }

    /** Создаёт сущности голограммы. Должен выполняться в потоке региона локации. */
    private void doSpawn(final Hologram h, final Location loc) {
        if (!this.enabled) {
            return; // не создаём сущности после выключения модуля
        }
        final Set<UUID> ids = ConcurrentHashMap.newKeySet();
        switch (h.engine()) {
            case TEXT_DISPLAY -> spawnText(loc, HologramText.joined(h.lines()), h.name(), ids);
            case ARMOR_STAND -> {
                final double spacing = settings.get().lineSpacing();
                final List<String> lines = h.lines();
                for (int i = 0; i < lines.size(); i++) {
                    final Location line = loc.clone().add(0, -i * spacing, 0);
                    spawnArmorStand(line, core.amazake.core.util.Text.parse(lines.get(i)), h.name(), ids);
                }
            }
            case ITEM_DISPLAY -> {
                Material material = Material.matchMaterial(h.item() != null ? h.item() : settings.get().defaultItem());
                if (material == null || !material.isItem()) {
                    material = Material.DIAMOND;
                }
                spawnItem(loc.clone().add(0, 0.4, 0), material, h.name(), ids);
                if (!h.lines().isEmpty()) {
                    spawnText(loc, HologramText.joined(h.lines()), h.name(), ids);
                }
            }
            default -> {
            }
        }
        spawned.put(h.name(), ids);
    }

    private void spawnText(final Location loc, final Component text, final String name, final Set<UUID> ids) {
        final TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, e -> {
            e.text(text);
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            tag(e, name);
        });
        ids.add(td.getUniqueId());
    }

    private void spawnArmorStand(final Location loc, final Component text, final String name, final Set<UUID> ids) {
        final ArmorStand as = loc.getWorld().spawn(loc, ArmorStand.class, e -> {
            e.setInvisible(true);
            e.setMarker(true);
            e.setGravity(false);
            e.setSmall(true);
            e.setBasePlate(false);
            e.customName(text);
            e.setCustomNameVisible(true);
            e.setPersistent(false);
            tag(e, name);
        });
        ids.add(as.getUniqueId());
    }

    private void spawnItem(final Location loc, final Material material, final String name, final Set<UUID> ids) {
        final ItemDisplay id = loc.getWorld().spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(material));
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            tag(e, name);
        });
        ids.add(id.getUniqueId());
    }

    /**
     * Удаляет все наши сущности этой голограммы в её чанке (на потоке региона). Голограмма занимает один столбец,
     * то есть всегда один чанк, поэтому сканировать его сущности и точно, и безопасно для региона.
     */
    private void removeOursIn(final Location loc, final String name) {
        for (final Entity e : loc.getChunk().getEntities()) {
            if (name.equals(nameOf(e))) {
                e.remove();
            }
        }
        spawned.remove(name);
    }

    // ---- chunk events: keep holograms visible as chunks come and go ----

    @EventHandler
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (this.enabled) {
            handleChunk(event.getWorld(), event.getChunk());
        }
    }

    @EventHandler
    public void onChunkUnload(final ChunkUnloadEvent event) {
        // Непостоянные сущности пропадают вместе с чанком — снимаем только наш учёт.
        for (final Hologram h : holograms.values()) {
            if (inChunk(h, event.getWorld(), event.getChunk())) {
                spawned.remove(h.name());
            }
        }
    }

    // ---- helpers ----

    private void tag(final Entity e, final String name) {
        e.getPersistentDataContainer().set(nameKey, PersistentDataType.STRING, name);
    }

    private boolean isOurs(final Entity e) {
        return e.getPersistentDataContainer().has(nameKey, PersistentDataType.STRING);
    }

    private String nameOf(final Entity e) {
        return e.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
    }

    private static Location locationOf(final Hologram h, final World world) {
        return new Location(world, h.x(), h.y(), h.z());
    }


    private static boolean inChunk(final Hologram h, final World world, final Chunk chunk) {
        if (!h.world().equals(world.getName())) {
            return false;
        }
        final int cx = (int) Math.floor(h.x()) >> 4;
        final int cz = (int) Math.floor(h.z()) >> 4;
        return cx == chunk.getX() && cz == chunk.getZ();
    }
}

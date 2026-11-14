package core.amazake.core.modules.essentials;

import core.amazake.core.framework.Database;
import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;
import core.amazake.core.modules.essentials.TeleportRequests.Request;
import core.amazake.core.util.Text;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Essentials: дома ({@code /sethome,/home,/delhome,/homes}), варпы ({@code /setwarp,/delwarp,/warp,/warps}),
 * запросы телепорта ({@code /tpa,/tpahere,/tpaccept,/tpdeny,/tpcancel}), смена режима игры ({@code /gm} и ярлыки),
 * просмотр инвентаря ({@code /invsee}) и эндер-сундука ({@code /ec}).
 *
 * <p>Каждая подфича включается своим ключом конфига и переключается на лету через {@code /amazakecore reload}:
 * команды регистрируются один раз, а поведение сверяется с актуальным снимком настроек при каждом вызове.</p>
 *
 * <p><b>Потоки.</b> Команды игрока выполняются в его потоке; телепорт, открытие инвентаря и смена режима игры —
 * только в потоке соответствующего игрока ({@code runOnceForPlayer}). Единственный блокирующий I/O — база — идёт
 * через {@code context.database()} (свой поток, {@code CompletableFuture}); результат применяется обратно игроку
 * через {@code runOnceForPlayer}. Общее состояние (запросы TPA, кэш имён варпов) — потокобезопасное.</p>
 */
public final class EssentialsModule implements Module {

    /** Снимок точки из БД; {@link Location} собирается уже в потоке игрока (где безопасно трогать мир). */
    record StoredLoc(String world, double x, double y, double z, float yaw, float pitch) {
    }

    static final Database.RowMapper<StoredLoc> LOC_MAPPER = rs -> new StoredLoc(
        rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
        rs.getFloat("yaw"), rs.getFloat("pitch"));

    private final AtomicReference<EssentialsSettings> settings = new AtomicReference<>(EssentialsSettings.DEFAULTS);
    private final TeleportRequests requests = new TeleportRequests();
    /** Кэш имён варпов для автодополнения (БД читать в tabComplete нельзя — он синхронный). */
    private final Set<String> warpNames = ConcurrentHashMap.newKeySet();

    private ModuleContext context;

    @Override
    public String id() {
        return "essentials";
    }

    @Override
    public String description() {
        return "дома, варпы, запросы телепорта (/tpa), смена режима игры (/gm), /invsee и /ec";
    }

    @Override
    public void onEnable(final ModuleContext context) throws Exception {
        this.context = context;
        this.settings.set(EssentialsSettings.from(context.config(), w -> context.logger().warning(w)));

        context.database().ddl(
            "CREATE TABLE IF NOT EXISTS ess_homes ("
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, "
                + "PRIMARY KEY (uuid, name))",
            "CREATE TABLE IF NOT EXISTS ess_warps ("
                + "name VARCHAR(32) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL)");

        this.reloadWarpCache();

        // Дома
        context.registerCommand(new HomesCommand(this, HomesCommand.Action.SET));
        context.registerCommand(new HomesCommand(this, HomesCommand.Action.GO));
        context.registerCommand(new HomesCommand(this, HomesCommand.Action.DEL));
        context.registerCommand(new HomesCommand(this, HomesCommand.Action.LIST));
        // Варпы
        context.registerCommand(new WarpsCommand(this, WarpsCommand.Action.SET));
        context.registerCommand(new WarpsCommand(this, WarpsCommand.Action.DEL));
        context.registerCommand(new WarpsCommand(this, WarpsCommand.Action.GO));
        context.registerCommand(new WarpsCommand(this, WarpsCommand.Action.LIST));
        // TPA
        context.registerCommand(new TpaCommand(this, TpaCommand.Action.TPA));
        context.registerCommand(new TpaCommand(this, TpaCommand.Action.TPAHERE));
        context.registerCommand(new TpaCommand(this, TpaCommand.Action.ACCEPT));
        context.registerCommand(new TpaCommand(this, TpaCommand.Action.DENY));
        context.registerCommand(new TpaCommand(this, TpaCommand.Action.CANCEL));
        // Режим игры
        context.registerCommand(new GamemodeCommand(this, "gm", null));
        context.registerCommand(new GamemodeCommand(this, "gms", org.bukkit.GameMode.SURVIVAL));
        context.registerCommand(new GamemodeCommand(this, "gmc", org.bukkit.GameMode.CREATIVE));
        context.registerCommand(new GamemodeCommand(this, "gma", org.bukkit.GameMode.ADVENTURE));
        context.registerCommand(new GamemodeCommand(this, "gmsp", org.bukkit.GameMode.SPECTATOR));
        context.registerCommand(new GamemodeCommand(this, "gm0", org.bukkit.GameMode.SURVIVAL));
        context.registerCommand(new GamemodeCommand(this, "gm1", org.bukkit.GameMode.CREATIVE));
        context.registerCommand(new GamemodeCommand(this, "gm2", org.bukkit.GameMode.ADVENTURE));
        context.registerCommand(new GamemodeCommand(this, "gm3", org.bukkit.GameMode.SPECTATOR));
        // Инвентари
        context.registerCommand(new InvseeCommand(this));
        context.registerCommand(new EnderChestCommand(this));

        // Чистка истёкших запросов телепорта раз в секунду (глобальный поток).
        context.runGlobalTimer(t -> this.sweepRequests(), Duration.ofSeconds(1));
    }

    @Override
    public void onDisable() {
        // Таймеры, команды и слушатели снимает ядро. Нам остаётся очистить свои кэши в памяти.
        this.requests.clear();
        this.warpNames.clear();
    }

    @Override
    public void onConfigReload(final org.bukkit.configuration.ConfigurationSection section) {
        this.settings.set(EssentialsSettings.from(section, w -> this.context.logger().warning(w)));
    }

    // ---- доступ для команд ----

    EssentialsSettings settings() {
        return this.settings.get();
    }

    ModuleContext ctx() {
        return this.context;
    }

    Database db() {
        return this.context.database();
    }

    TeleportRequests requests() {
        return this.requests;
    }

    Set<String> warpNames() {
        return this.warpNames;
    }

    void rememberWarp(final String name) {
        this.warpNames.add(name);
    }

    void forgetWarp(final String name) {
        this.warpNames.remove(name);
    }

    void reloadWarpCache() {
        this.db().queryList("SELECT name FROM ess_warps", rs -> rs.getString("name")).thenAccept(names -> {
            this.warpNames.clear();
            this.warpNames.addAll(names);
        });
    }

    Component parse(final String key, final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... res) {
        return Text.parse(this.settings().msg(key), res);
    }

    // ---- телепорт (всегда в потоке перемещаемого игрока) ----

    /**
     * Телепортирует {@code mover} в сохранённую точку. Выполняется в его потоке; мир собирается там же. Если мир не
     * загружен — сообщение об этом. На успех отправляет {@code successMessage} (может быть null).
     */
    void teleportToStored(final Player mover, final StoredLoc sl, final Component successMessage) {
        this.context.runOnceForPlayer(mover, () -> {
            final World world = Bukkit.getWorld(sl.world());
            if (world == null) {
                mover.sendMessage(this.parse("world-missing"));
                return;
            }
            mover.teleportAsync(new Location(world, sl.x(), sl.y(), sl.z(), sl.yaw(), sl.pitch()));
            if (successMessage != null) {
                mover.sendMessage(successMessage);
            }
        });
    }

    /**
     * Телепортирует {@code mover} к текущему положению {@code anchor}. Позиция читается в потоке {@code anchor}
     * (правило «игрока трогает его поток»), затем перемещение — в потоке {@code mover}.
     */
    void teleportToPlayer(final Player mover, final Player anchor, final Component moverMessage) {
        this.context.runOnceForPlayer(anchor, () -> {
            final Location destination = anchor.getLocation().clone();
            this.context.runOnceForPlayer(mover, () -> {
                mover.teleportAsync(destination);
                if (moverMessage != null) {
                    mover.sendMessage(moverMessage);
                }
            });
        });
    }

    private void sweepRequests() {
        final List<Request> expired = this.requests.sweepExpired(System.currentTimeMillis());
        for (final Request r : expired) {
            final Player requester = Bukkit.getPlayer(r.requester());
            if (requester != null) {
                this.context.runOnceForPlayer(requester, () ->
                    requester.sendMessage(this.parse("tpa-expired-requester", Text.var("player", r.targetName()))));
            }
        }
    }
}

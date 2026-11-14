package core.amazake.core.modules.groups;

import core.amazake.core.framework.GroupService;
import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Модуль собственных групп и прав (без LuckPerms): наследование, веса, префиксы/суффиксы. Публикует
 * {@link GroupService} в реестр сервисов ядра — от него зависит чат.
 *
 * <p>Потоки: данные загружаются из базы асинхронно (на её потоке), права выдаются каждому игроку на его потоке
 * через {@link GroupEngine#applyTo} (обработчик входа и {@code runOnceForPlayer}). Кэши потокобезопасны, методы
 * сервиса не блокируются.</p>
 */
public final class GroupsModule implements Module {

    private ModuleContext context;
    private GroupEngine engine;

    @Override
    public String id() {
        return "groups";
    }

    @Override
    public String description() {
        return "свои группы и права: наследование, префиксы, веса";
    }

    @Override
    public void onEnable(final ModuleContext context) throws Exception {
        this.context = context;

        context.database().ddl(
            "CREATE TABLE IF NOT EXISTS grp_groups("
                + "name VARCHAR(32) PRIMARY KEY, weight INT, prefix VARCHAR(128), suffix VARCHAR(128))",
            "CREATE TABLE IF NOT EXISTS grp_group_perms(gname VARCHAR(32), perm VARCHAR(128))",
            "CREATE TABLE IF NOT EXISTS grp_group_parents(gname VARCHAR(32), parent VARCHAR(32))",
            "CREATE TABLE IF NOT EXISTS grp_players(uuid VARCHAR(36), gname VARCHAR(32))",
            "CREATE TABLE IF NOT EXISTS grp_player_perms(uuid VARCHAR(36), perm VARCHAR(128))");

        this.engine = new GroupEngine(context);
        applyConfig(context.config());

        // загрузка кэшей и создание группы по умолчанию (асинхронно, без блокировки потока тика)
        this.engine.loadAll();

        context.registerListener(new JoinListener(this.engine));
        context.registerCommand(new GroupCommand(this.engine));
        context.registerCommand(new GroupUserCommand(this.engine));

        // публикуем сервис для других модулей (чат)
        context.services().register(GroupService.class, this.engine);
    }

    @Override
    public void onConfigReload(final ConfigurationSection section) {
        applyConfig(section);
        if (this.engine != null) {
            this.engine.reapplyAll();
        }
    }

    private void applyConfig(final ConfigurationSection section) {
        if (section == null || this.engine == null) {
            return;
        }
        this.engine.setDefaultGroup(section.getString("default-group", "default"));
        this.engine.setApplyPermissions(section.getBoolean("apply-permissions", true));
    }

    @Override
    public void onDisable() {
        if (this.context != null) {
            this.context.services().unregister(GroupService.class);
        }
        if (this.engine != null) {
            this.engine.removeAllAttachments();
        }
        this.engine = null;
    }

    /** Выдаёт права при входе, снимает кэш привязки при выходе. Оба события приходят на потоке игрока. */
    private static final class JoinListener implements Listener {
        private final GroupEngine engine;

        JoinListener(final GroupEngine engine) {
            this.engine = engine;
        }

        @EventHandler
        public void onJoin(final PlayerJoinEvent event) {
            this.engine.applyTo(event.getPlayer());
        }

        @EventHandler
        public void onQuit(final PlayerQuitEvent event) {
            this.engine.clearPlayer(event.getPlayer().getUniqueId());
        }
    }
}

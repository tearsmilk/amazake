package core.amazake.core.modules.groups;

import core.amazake.core.framework.Database;
import core.amazake.core.framework.GroupService;
import core.amazake.core.framework.ModuleContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Ядро модуля групп: потокобезопасные кэши групп и игроков, запись изменений в базу (асинхронно), выдача прав
 * онлайн-игрокам через {@link PermissionAttachment} и реализация {@link GroupService}.
 *
 * <p>Все методы {@link GroupService} читают только кэши ({@code ConcurrentHashMap}) и не блокируются — от этого
 * зависит чат.</p>
 */
public final class GroupEngine implements GroupService {

    private final ModuleContext context;
    private final Database db;

    /** Имя группы (нижний регистр) -> её снимок. */
    private final ConcurrentHashMap<String, GroupData> groups = new ConcurrentHashMap<>();
    /** Игрок -> множество имён групп, в которых он состоит (неизменяемые наборы, нижний регистр). */
    private final ConcurrentHashMap<UUID, Set<String>> playerGroups = new ConcurrentHashMap<>();
    /** Игрок -> его личные права (неизменяемые наборы, нижний регистр). */
    private final ConcurrentHashMap<UUID, Set<String>> playerPerms = new ConcurrentHashMap<>();
    /** Активные привязки прав онлайн-игроков. */
    private final ConcurrentHashMap<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    private volatile String defaultGroup = "default";
    private volatile boolean applyPermissions = true;

    public GroupEngine(final ModuleContext context) {
        this.context = context;
        this.db = context.database();
    }

    // ------------------------------------------------------------------ config

    public void setDefaultGroup(final String name) {
        this.defaultGroup = GroupLogic.norm(name);
    }

    public String defaultGroup() {
        return this.defaultGroup;
    }

    public void setApplyPermissions(final boolean apply) {
        this.applyPermissions = apply;
    }

    // ------------------------------------------------------------------ load

    /**
     * Загружает все таблицы в кэши (асинхронно), при первом запуске создаёт группу по умолчанию с весом 0, затем
     * применяет права ко всем онлайн-игрокам. Не блокирует поток тика.
     */
    public void loadAll() {
        this.db.submit(this::loadSnapshot).whenComplete((snapshot, err) -> {
            if (err != null) {
                this.context.logger().log(Level.SEVERE, "groups: не удалось загрузить данные из базы", err);
                return;
            }
            this.groups.clear();
            this.groups.putAll(snapshot.groups());
            this.playerGroups.clear();
            this.playerGroups.putAll(snapshot.playerGroups());
            this.playerPerms.clear();
            this.playerPerms.putAll(snapshot.playerPerms());
            if (this.groups.isEmpty()) {
                // первый запуск — создаём группу по умолчанию
                final GroupData def = new GroupData(this.defaultGroup, 0, "", "", Set.of(), Set.of());
                this.groups.put(def.name(), def);
                this.db.write("INSERT INTO grp_groups(name,weight,prefix,suffix) VALUES(?,?,?,?)",
                    def.name(), 0, "", "");
            }
            reapplyAll();
        });
    }

    /** Синхронное чтение всех таблиц; выполняется на потоке базы (внутри {@link Database#submit}). */
    private Snapshot loadSnapshot(final java.sql.Connection connection) throws java.sql.SQLException {
        final Map<String, Integer> weights = new HashMap<>();
        final Map<String, String[]> fixes = new HashMap<>(); // name -> [prefix, suffix]
        final Map<String, Set<String>> gperms = new HashMap<>();
        final Map<String, Set<String>> gparents = new HashMap<>();
        final Map<UUID, Set<String>> pgroups = new HashMap<>();
        final Map<UUID, Set<String>> pperms = new HashMap<>();

        try (var ps = connection.prepareStatement(
                "SELECT name,weight,prefix,suffix FROM grp_groups"); var rs = ps.executeQuery()) {
            while (rs.next()) {
                final String name = GroupLogic.norm(rs.getString(1));
                weights.put(name, rs.getInt(2));
                fixes.put(name, new String[] {
                    rs.getString(3) == null ? "" : rs.getString(3),
                    rs.getString(4) == null ? "" : rs.getString(4) });
            }
        }
        try (var ps = connection.prepareStatement(
                "SELECT gname,perm FROM grp_group_perms"); var rs = ps.executeQuery()) {
            while (rs.next()) {
                gperms.computeIfAbsent(GroupLogic.norm(rs.getString(1)), k -> new HashSet<>())
                    .add(GroupLogic.norm(rs.getString(2)));
            }
        }
        try (var ps = connection.prepareStatement(
                "SELECT gname,parent FROM grp_group_parents"); var rs = ps.executeQuery()) {
            while (rs.next()) {
                gparents.computeIfAbsent(GroupLogic.norm(rs.getString(1)), k -> new HashSet<>())
                    .add(GroupLogic.norm(rs.getString(2)));
            }
        }
        try (var ps = connection.prepareStatement(
                "SELECT uuid,gname FROM grp_players"); var rs = ps.executeQuery()) {
            while (rs.next()) {
                pgroups.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashSet<>())
                    .add(GroupLogic.norm(rs.getString(2)));
            }
        }
        try (var ps = connection.prepareStatement(
                "SELECT uuid,perm FROM grp_player_perms"); var rs = ps.executeQuery()) {
            while (rs.next()) {
                pperms.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashSet<>())
                    .add(GroupLogic.norm(rs.getString(2)));
            }
        }

        final Map<String, GroupData> gs = new HashMap<>();
        for (final String name : weights.keySet()) {
            final String[] fx = fixes.getOrDefault(name, new String[] {"", ""});
            gs.put(name, new GroupData(name, weights.get(name), fx[0], fx[1],
                gperms.getOrDefault(name, Set.of()), gparents.getOrDefault(name, Set.of())));
        }
        final Map<UUID, Set<String>> pg = new HashMap<>();
        pgroups.forEach((k, v) -> pg.put(k, Set.copyOf(v)));
        final Map<UUID, Set<String>> pp = new HashMap<>();
        pperms.forEach((k, v) -> pp.put(k, Set.copyOf(v)));
        return new Snapshot(gs, pg, pp);
    }

    private record Snapshot(Map<String, GroupData> groups,
                            Map<UUID, Set<String>> playerGroups,
                            Map<UUID, Set<String>> playerPerms) {
    }

    // ------------------------------------------------------------------ snapshots for pure logic

    private Map<String, Set<String>> directPermsMap() {
        final Map<String, Set<String>> out = new HashMap<>();
        this.groups.forEach((name, g) -> out.put(name, g.perms()));
        return out;
    }

    private Map<String, Set<String>> parentsMap() {
        final Map<String, Set<String>> out = new HashMap<>();
        this.groups.forEach((name, g) -> out.put(name, g.parents()));
        return out;
    }

    private Map<String, Integer> weightsMap() {
        final Map<String, Integer> out = new HashMap<>();
        this.groups.forEach((name, g) -> out.put(name, g.weight()));
        return out;
    }

    /** Полный набор прав игрока по кэшам (читает только кэши). */
    public Set<String> computeEffective(final UUID player) {
        return GroupLogic.playerEffective(this.playerGroups.get(player), this.playerPerms.get(player),
            this.defaultGroup, directPermsMap(), parentsMap());
    }

    // ------------------------------------------------------------------ GroupService (неблокирующее чтение кэшей)

    @Override
    public String primaryGroup(final UUID player) {
        return GroupLogic.primaryGroup(this.playerGroups.get(player), weightsMap(), this.defaultGroup);
    }

    @Override
    public String prefix(final UUID player) {
        final GroupData g = this.groups.get(primaryGroup(player));
        return g == null ? "" : g.prefix();
    }

    @Override
    public String suffix(final UUID player) {
        final GroupData g = this.groups.get(primaryGroup(player));
        return g == null ? "" : g.suffix();
    }

    @Override
    public int weight(final UUID player) {
        final GroupData g = this.groups.get(primaryGroup(player));
        return g == null ? 0 : g.weight();
    }

    @Override
    public boolean has(final UUID player, final String permission) {
        return GroupLogic.matches(computeEffective(player), GroupLogic.norm(permission));
    }

    /** The owning plugin (for scheduling); used by commands that resolve offline players off-thread. */
    public org.bukkit.plugin.Plugin plugin() {
        return this.context.plugin();
    }

    // ------------------------------------------------------------------ permission attachments

    /** Выдаёт игроку привязку с его правами. Вызывать на потоке игрока. */
    public void applyTo(final Player player) {
        if (!this.applyPermissions) {
            return;
        }
        final UUID id = player.getUniqueId();
        final PermissionAttachment old = this.attachments.remove(id);
        if (old != null) {
            try {
                player.removeAttachment(old);
            } catch (final IllegalArgumentException ignored) {
                // привязка уже снята (игрок перезашёл) — не страшно
            }
        }
        final PermissionAttachment att = player.addAttachment(this.context.plugin());
        final Set<String> effective = computeEffective(id);
        for (final String perm : effective) {
            att.setPermission(perm, true);
        }
        // Раскрываем wildcard-права (* и a.b.*) по зарегистрированным на сервере правам, чтобы Bukkit-проверка
        // sender.hasPermission(...) совпадала с GroupService.has(...). (Нода без зарегистрированного Permission
        // раскрыта быть не может — это общее поведение систем прав.)
        final boolean all = effective.contains("*");
        for (final Permission registered : Bukkit.getPluginManager().getPermissions()) {
            final String name = registered.getName().toLowerCase(java.util.Locale.ROOT);
            if (all) {
                att.setPermission(registered.getName(), true);
                continue;
            }
            for (final String perm : effective) {
                if (perm.endsWith(".*") && name.startsWith(perm.substring(0, perm.length() - 1))) {
                    att.setPermission(registered.getName(), true);
                    break;
                }
            }
        }
        this.attachments.put(id, att);
        player.recalculatePermissions();
    }

    /** Снимает привязку у вышедшего игрока. */
    public void clearPlayer(final UUID id) {
        this.attachments.remove(id);
    }

    /** Переприменяет права ко всем онлайн-игрокам (каждому — на его потоке). */
    public void reapplyAll() {
        if (!this.applyPermissions) {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                final UUID id = p.getUniqueId();
                this.context.runOnceForPlayer(p, () -> {
                    final PermissionAttachment a = this.attachments.remove(id);
                    if (a != null) {
                        try {
                            p.removeAttachment(a);
                        } catch (final IllegalArgumentException ignored) {
                            // already gone
                        }
                    }
                });
            }
            return;
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            this.context.runOnceForPlayer(p, () -> applyTo(p));
        }
    }

    /** Снимает все привязки при выключении модуля. */
    public void removeAllAttachments() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            final PermissionAttachment a = this.attachments.remove(p.getUniqueId());
            if (a != null) {
                try {
                    p.removeAttachment(a);
                } catch (final IllegalArgumentException ignored) {
                    // already gone
                }
            }
        }
        this.attachments.clear();
    }

    // ------------------------------------------------------------------ queries for commands

    public boolean groupExists(final String name) {
        return this.groups.containsKey(GroupLogic.norm(name));
    }

    public GroupData group(final String name) {
        return this.groups.get(GroupLogic.norm(name));
    }

    public Set<String> groupNames() {
        return Set.copyOf(this.groups.keySet());
    }

    public Set<String> memberGroups(final UUID player) {
        return this.playerGroups.getOrDefault(player, Set.of());
    }

    public Set<String> personalPerms(final UUID player) {
        return this.playerPerms.getOrDefault(player, Set.of());
    }

    // ------------------------------------------------------------------ mutations (cache first, DB async)

    /** @return true, если группа создана; false, если уже была. */
    public boolean createGroup(final String rawName) {
        final String name = GroupLogic.norm(rawName);
        final GroupData prev = this.groups.putIfAbsent(name, new GroupData(name, 0, "", "", Set.of(), Set.of()));
        if (prev != null) {
            return false;
        }
        this.db.write("INSERT INTO grp_groups(name,weight,prefix,suffix) VALUES(?,?,?,?)", name, 0, "", "");
        return true;
    }

    /** @return true, если группа удалена. Нельзя удалить группу по умолчанию. */
    public boolean deleteGroup(final String rawName) {
        final String name = GroupLogic.norm(rawName);
        if (name.equals(this.defaultGroup)) {
            return false;
        }
        if (this.groups.remove(name) == null) {
            return false;
        }
        // убрать группу из членств игроков и из родителей других групп
        this.playerGroups.replaceAll((id, set) -> {
            if (!set.contains(name)) {
                return set;
            }
            final Set<String> ns = new HashSet<>(set);
            ns.remove(name);
            return Set.copyOf(ns);
        });
        this.groups.replaceAll((gn, g) -> {
            if (!g.parents().contains(name)) {
                return g;
            }
            final Set<String> np = new HashSet<>(g.parents());
            np.remove(name);
            return g.withParents(np);
        });
        this.db.write("DELETE FROM grp_groups WHERE name=?", name);
        this.db.write("DELETE FROM grp_group_perms WHERE gname=?", name);
        this.db.write("DELETE FROM grp_group_parents WHERE gname=?", name);
        this.db.write("DELETE FROM grp_group_parents WHERE parent=?", name);
        this.db.write("DELETE FROM grp_players WHERE gname=?", name);
        reapplyAll();
        return true;
    }

    public void setWeight(final String rawName, final int weight) {
        final String name = GroupLogic.norm(rawName);
        this.groups.computeIfPresent(name, (n, g) -> g.withWeight(weight));
        this.db.write("UPDATE grp_groups SET weight=? WHERE name=?", weight, name);
        reapplyAll();
    }

    public void setPrefix(final String rawName, final String prefix) {
        final String name = GroupLogic.norm(rawName);
        this.groups.computeIfPresent(name, (n, g) -> g.withPrefix(prefix));
        this.db.write("UPDATE grp_groups SET prefix=? WHERE name=?", prefix, name);
        // префиксы читает чат через сервис — переприменять права не нужно
    }

    public void setSuffix(final String rawName, final String suffix) {
        final String name = GroupLogic.norm(rawName);
        this.groups.computeIfPresent(name, (n, g) -> g.withSuffix(suffix));
        this.db.write("UPDATE grp_groups SET suffix=? WHERE name=?", suffix, name);
    }

    /** @return true, если право добавлено (его не было). */
    public boolean addGroupPerm(final String rawName, final String rawPerm) {
        final String name = GroupLogic.norm(rawName);
        final String perm = GroupLogic.norm(rawPerm);
        final GroupData g = this.groups.get(name);
        if (g == null || g.perms().contains(perm)) {
            return false;
        }
        final Set<String> np = new HashSet<>(g.perms());
        np.add(perm);
        this.groups.put(name, g.withPerms(np));
        this.db.write("INSERT INTO grp_group_perms(gname,perm) VALUES(?,?)", name, perm);
        reapplyAll();
        return true;
    }

    public boolean removeGroupPerm(final String rawName, final String rawPerm) {
        final String name = GroupLogic.norm(rawName);
        final String perm = GroupLogic.norm(rawPerm);
        final GroupData g = this.groups.get(name);
        if (g == null || !g.perms().contains(perm)) {
            return false;
        }
        final Set<String> np = new HashSet<>(g.perms());
        np.remove(perm);
        this.groups.put(name, g.withPerms(np));
        this.db.write("DELETE FROM grp_group_perms WHERE gname=? AND perm=?", name, perm);
        reapplyAll();
        return true;
    }

    public boolean addParent(final String rawName, final String rawParent) {
        final String name = GroupLogic.norm(rawName);
        final String parent = GroupLogic.norm(rawParent);
        final GroupData g = this.groups.get(name);
        if (g == null || !this.groups.containsKey(parent) || parent.equals(name) || g.parents().contains(parent)) {
            return false;
        }
        final Set<String> np = new HashSet<>(g.parents());
        np.add(parent);
        this.groups.put(name, g.withParents(np));
        this.db.write("INSERT INTO grp_group_parents(gname,parent) VALUES(?,?)", name, parent);
        reapplyAll();
        return true;
    }

    public boolean removeParent(final String rawName, final String rawParent) {
        final String name = GroupLogic.norm(rawName);
        final String parent = GroupLogic.norm(rawParent);
        final GroupData g = this.groups.get(name);
        if (g == null || !g.parents().contains(parent)) {
            return false;
        }
        final Set<String> np = new HashSet<>(g.parents());
        np.remove(parent);
        this.groups.put(name, g.withParents(np));
        this.db.write("DELETE FROM grp_group_parents WHERE gname=? AND parent=?", name, parent);
        reapplyAll();
        return true;
    }

    // ------- player membership -------

    /** Устанавливает единственную группу игрока (заменяет все членства). */
    public boolean setPlayerGroup(final UUID id, final String rawName) {
        final String name = GroupLogic.norm(rawName);
        if (!this.groups.containsKey(name)) {
            return false;
        }
        this.playerGroups.put(id, Set.of(name));
        this.db.write("DELETE FROM grp_players WHERE uuid=?", id.toString())
            .thenRun(() -> this.db.write("INSERT INTO grp_players(uuid,gname) VALUES(?,?)", id.toString(), name));
        applyToIfOnline(id);
        return true;
    }

    public boolean addPlayerGroup(final UUID id, final String rawName) {
        final String name = GroupLogic.norm(rawName);
        if (!this.groups.containsKey(name)) {
            return false;
        }
        final Set<String> cur = this.playerGroups.getOrDefault(id, Set.of());
        if (cur.contains(name)) {
            return false;
        }
        final Set<String> ns = new HashSet<>(cur);
        ns.add(name);
        this.playerGroups.put(id, Set.copyOf(ns));
        this.db.write("INSERT INTO grp_players(uuid,gname) VALUES(?,?)", id.toString(), name);
        applyToIfOnline(id);
        return true;
    }

    public boolean removePlayerGroup(final UUID id, final String rawName) {
        final String name = GroupLogic.norm(rawName);
        final Set<String> cur = this.playerGroups.getOrDefault(id, Set.of());
        if (!cur.contains(name)) {
            return false;
        }
        final Set<String> ns = new HashSet<>(cur);
        ns.remove(name);
        if (ns.isEmpty()) {
            this.playerGroups.remove(id);
        } else {
            this.playerGroups.put(id, Set.copyOf(ns));
        }
        this.db.write("DELETE FROM grp_players WHERE uuid=? AND gname=?", id.toString(), name);
        applyToIfOnline(id);
        return true;
    }

    public boolean addPlayerPerm(final UUID id, final String rawPerm) {
        final String perm = GroupLogic.norm(rawPerm);
        final Set<String> cur = this.playerPerms.getOrDefault(id, Set.of());
        if (cur.contains(perm)) {
            return false;
        }
        final Set<String> ns = new HashSet<>(cur);
        ns.add(perm);
        this.playerPerms.put(id, Set.copyOf(ns));
        this.db.write("INSERT INTO grp_player_perms(uuid,perm) VALUES(?,?)", id.toString(), perm);
        applyToIfOnline(id);
        return true;
    }

    public boolean removePlayerPerm(final UUID id, final String rawPerm) {
        final String perm = GroupLogic.norm(rawPerm);
        final Set<String> cur = this.playerPerms.getOrDefault(id, Set.of());
        if (!cur.contains(perm)) {
            return false;
        }
        final Set<String> ns = new HashSet<>(cur);
        ns.remove(perm);
        if (ns.isEmpty()) {
            this.playerPerms.remove(id);
        } else {
            this.playerPerms.put(id, Set.copyOf(ns));
        }
        this.db.write("DELETE FROM grp_player_perms WHERE uuid=? AND perm=?", id.toString(), perm);
        applyToIfOnline(id);
        return true;
    }

    private void applyToIfOnline(final UUID id) {
        final Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) {
            this.context.runOnceForPlayer(p, () -> applyTo(p));
        }
    }
}

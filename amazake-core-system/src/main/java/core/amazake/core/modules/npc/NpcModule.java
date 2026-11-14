package core.amazake.core.modules.npc;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/**
 * NPC module: puts player skins on NPCs of the Citizens plugin.
 *
 * <p>Citizens is an <b>optional soft dependency</b>, reached by reflection only ({@link CitizensBridge}) — it is not
 * on the compile classpath. If Citizens is not enabled, the module logs a clear Russian notice and registers nothing;
 * it stays "enabled" but idle. If present, it registers {@code /npccore} (permission {@code amazakecore.npc.admin}).</p>
 *
 * <p>Threading: the command runs on the sender's thread, dispatches the NPC/world work to the global region
 * scheduler, and reports back on the sender's thread (docs/systems.md §6). The core removes the registered command
 * on disable, so {@link #onDisable()} has nothing to undo.</p>
 */
public final class NpcModule implements Module {

    @Override
    public String id() {
        return "npc";
    }

    @Override
    public String description() {
        return "NPC через Citizens, скины игроков";
    }

    @Override
    public void onEnable(final ModuleContext context) throws Exception {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            context.logger().warning("Модулю npc нужен плагин Citizens — он не установлен или выключен. "
                + "Модуль остаётся включённым, но бездействует. Установите Citizens и выполните "
                + "/amazakecore reload (или перезапустите систему).");
            return;
        }
        final CitizensBridge citizens;
        try {
            citizens = CitizensBridge.create();
        } catch (final ReflectiveOperationException | LinkageError e) {
            context.logger().log(Level.WARNING, "Модуль npc: API Citizens отличается от ожидаемого — команда не "
                + "зарегистрирована. Проверьте версию Citizens.", e);
            return;
        }
        context.registerCommand(new NpcCommand(context, citizens));
        context.logger().info("Модуль npc: Citizens найден, команда /npccore зарегистрирована.");
    }

    @Override
    public void onDisable() throws Exception {
        // Nothing to undo: the core unregisters the command, and the module spawns no entities or threads.
    }
}

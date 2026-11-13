package core.amazake.systems.tab;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

/**
 * Join/quit bookkeeping. Handlers may run on several tick threads at once; they only touch concurrent maps and
 * start/cancel tasks. The scoreboard and tab list are changed by the player's own task, never here.
 */
final class TabListener implements Listener {

    private final TabRuntime runtime;

    TabListener(final TabRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        this.runtime.start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.runtime.stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceRegister(final ServiceRegisterEvent event) {
        if (event.getProvider().getService().getName().equals("net.milkbowl.vault.chat.Chat")) {
            this.runtime.refreshHooks();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServiceUnregister(final ServiceUnregisterEvent event) {
        if (event.getProvider().getService().getName().equals("net.milkbowl.vault.chat.Chat")) {
            this.runtime.refreshHooks();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(final PluginEnableEvent event) {
        final String name = event.getPlugin().getName();
        if (name.equals("PlaceholderAPI") || name.equals("Vault")) {
            this.runtime.refreshHooks();
        }
    }
}

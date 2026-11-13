package ink.hoka.amazake.systems.tab;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Optional integrations found by reflection, without compile dependencies: PlaceholderAPI
 * ({@code PlaceholderAPI.setPlaceholders(OfflinePlayer, String)}) and a Vault {@code Chat} provider
 * ({@code getPlayerPrefix/getPlayerSuffix(Player)}).
 *
 * <p>{@link #refresh} runs on the global thread (enable, reload, service registration); the resolved handles are
 * published in one immutable snapshot through a volatile field and read from players' threads.</p>
 */
final class Hooks {

    private record Papi(Plugin plugin, Method setPlaceholders) {
    }

    private record Vault(Object chat, Method prefix, Method suffix) {
    }

    private record State(Papi papi, Vault vault) {
    }

    private final Logger logger;
    private volatile State state = new State(null, null);
    private volatile boolean papiFailed;
    private volatile boolean vaultFailed;

    Hooks(final Logger logger) {
        this.logger = logger;
    }

    void refresh(final boolean wantPapi, final boolean wantVault) {
        final State old = this.state;
        final Papi papi = wantPapi ? this.findPapi() : null;
        final Vault vault = wantVault ? this.findVault() : null;
        this.state = new State(papi, vault);
        if (papi != null && (old.papi() == null || old.papi().plugin() != papi.plugin())) {
            this.logger.info("PlaceholderAPI найден: его плейсхолдеры доступны в шаблонах.");
        }
        if (vault != null && (old.vault() == null || old.vault().chat() != vault.chat())) {
            this.logger.info("Vault Chat найден (" + vault.chat().getClass().getSimpleName() + "): префиксы и суффиксы берутся из него.");
        }
    }

    boolean hasPapi() {
        final Papi papi = this.state.papi();
        return papi != null && papi.plugin().isEnabled();
    }

    /** PlaceholderAPI pass; returns the text unchanged without PlaceholderAPI or on its errors. */
    String papi(final Player player, final String text) {
        final Papi papi = this.state.papi();
        if (papi == null || !papi.plugin().isEnabled() || !Placeholders.hasPlaceholder(text)) {
            return text;
        }
        try {
            final Object result = papi.setPlaceholders().invoke(null, player, text);
            return result == null ? text : result.toString();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            if (!this.papiFailed) {
                this.papiFailed = true;
                this.logger.log(Level.WARNING, "PlaceholderAPI выбросил исключение (дальше молчу)", e);
            }
            return text;
        }
    }

    /** Vault prefix, "" if none. */
    String vaultPrefix(final Player player) {
        final Vault vault = this.state.vault();
        return vault == null ? "" : this.call(vault, vault.prefix(), player);
    }

    /** Vault suffix, "" if none. */
    String vaultSuffix(final Player player) {
        final Vault vault = this.state.vault();
        return vault == null ? "" : this.call(vault, vault.suffix(), player);
    }

    private String call(final Vault vault, final Method method, final Player player) {
        try {
            final Object result = method.invoke(vault.chat(), player);
            return result == null ? "" : result.toString();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            if (!this.vaultFailed) {
                this.vaultFailed = true;
                this.logger.log(Level.WARNING, "Vault Chat выбросил исключение (дальше молчу)", e);
            }
            return "";
        }
    }

    private Papi findPapi() {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            final Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, plugin.getClass().getClassLoader());
            return new Papi(plugin, api.getMethod("setPlaceholders", OfflinePlayer.class, String.class));
        } catch (final ReflectiveOperationException | LinkageError e) {
            this.logger.log(Level.WARNING, "PlaceholderAPI установлен, но его API не найден: плейсхолдеры PAPI отключены", e);
            return null;
        }
    }

    private Vault findVault() {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin("Vault");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            final Class<?> chatClass = Class.forName("net.milkbowl.vault.chat.Chat", true, plugin.getClass().getClassLoader());
            final RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(chatClass);
            if (registration == null || registration.getProvider() == null) {
                return null;
            }
            return new Vault(registration.getProvider(),
                chatClass.getMethod("getPlayerPrefix", Player.class), chatClass.getMethod("getPlayerSuffix", Player.class));
        } catch (final ReflectiveOperationException | LinkageError e) {
            this.logger.log(Level.WARNING, "Vault установлен, но его Chat API не найден: префиксы Vault отключены", e);
            return null;
        }
    }
}

package core.amazake.core.framework;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-module view of {@link CoreServices} that remembers what the module published, so the core can remove those
 * services automatically when the module is disabled (as {@link Module} and {@link CoreServices} promise). Reads
 * ({@link #get}) see the whole shared registry.
 */
public final class ModuleServices {

    private final CoreServices shared;
    private final Set<Class<?>> registered = ConcurrentHashMap.newKeySet();

    ModuleServices(final CoreServices shared) {
        this.shared = shared;
    }

    public <T> void register(final Class<T> type, final T service) {
        this.shared.register(type, service);
        this.registered.add(type);
    }

    public void unregister(final Class<?> type) {
        this.shared.unregister(type);
        this.registered.remove(type);
    }

    public <T> Optional<T> get(final Class<T> type) {
        return this.shared.get(type);
    }

    /** Removes every service this module still has registered. Called by the core on disable. */
    void removeAll() {
        for (final Class<?> type : this.registered) {
            this.shared.unregister(type);
        }
        this.registered.clear();
    }
}

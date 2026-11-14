package core.amazake.core.framework;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny registry so one module can offer a service another consumes without depending on its classes — e.g. the
 * groups module publishes a {@link GroupService} that the chat module uses for prefixes. Thread-safe; a service is
 * published in a module's {@code onEnable} and removed automatically when that module is disabled.
 */
public final class CoreServices {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(final Class<T> type, final T service) {
        this.services.put(type, service);
    }

    public void unregister(final Class<?> type) {
        this.services.remove(type);
    }

    /** The service of this type, if a module published one. */
    public <T> Optional<T> get(final Class<T> type) {
        return Optional.ofNullable(type.cast(this.services.get(type)));
    }
}

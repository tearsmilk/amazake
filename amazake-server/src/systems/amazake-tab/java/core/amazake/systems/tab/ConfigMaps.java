package core.amazake.systems.tab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** Bukkit configuration to plain nested maps/lists for {@link TabConfig#parse}. */
final class ConfigMaps {

    private ConfigMaps() {
    }

    static Map<String, Object> toMap(final ConfigurationSection section) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String key : section.getKeys(false)) {
            out.put(key, plain(section.get(key)));
        }
        return out;
    }

    private static Object plain(final Object value) {
        if (value instanceof ConfigurationSection s) {
            return toMap(s);
        }
        if (value instanceof Map<?, ?> m) {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), plain(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            final List<Object> out = new ArrayList<>(l.size());
            for (final Object o : l) {
                out.add(plain(o));
            }
            return out;
        }
        return value;
    }
}

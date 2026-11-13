package core.amazake.systems;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The systems the owner wants enabled: what systems.yml lists. Kept apart from what is running, so a system that
 * failed to enable (at startup, or disabled by a failed reload) or whose .azs file is missing for now stays listed
 * when another system is enabled or disabled. Names are matched ignoring case; the last spelling given wins.
 */
final class WantedSystems {

    /** Lower-case name -> name as written. */
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** Replaces the whole set (systems.yml as read at startup). */
    synchronized void replace(final Collection<String> wanted) {
        this.names.clear();
        for (final String name : wanted) {
            this.add(name);
        }
    }

    void add(final String name) {
        this.names.put(key(name), name);
    }

    /** True when the name was listed. */
    boolean remove(final String name) {
        return this.names.remove(key(name)) != null;
    }

    boolean contains(final String name) {
        return this.names.containsKey(key(name));
    }

    /** The names, sorted ignoring case. */
    List<String> sorted() {
        final List<String> out = new ArrayList<>(this.names.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static String key(final String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}

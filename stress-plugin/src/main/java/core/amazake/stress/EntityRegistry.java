package core.amazake.stress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Entities spawned by one module. {@link StressContext#spawn} tracks automatically; anything spawned
 * another way should be passed to {@link #track(Entity)} so that stop removes it.
 * Main thread only.
 */
public final class EntityRegistry {

    private final List<Entity> entities = new ArrayList<>();
    private long totalTracked;

    /** Tracks an entity and returns it (players are ignored). */
    public <T extends Entity> T track(T entity) {
        if (!(entity instanceof Player)) {
            this.entities.add(entity);
            this.totalTracked++;
        }
        return entity;
    }

    public void untrack(Entity entity) {
        this.entities.remove(entity);
    }

    /** Number of tracked entries, including ones that already died. */
    public int trackedCount() {
        return this.entities.size();
    }

    /** Total number of entities ever tracked during this run. */
    public long totalTracked() {
        return this.totalTracked;
    }

    /** Drops dead/removed entities from the registry and returns how many are still valid. */
    public int aliveCount() {
        this.entities.removeIf(e -> !e.isValid());
        return this.entities.size();
    }

    /** Copy of the currently tracked entities. */
    public List<Entity> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(this.entities));
    }

    /** Removes every tracked entity from the world and clears the registry; returns how many were removed. */
    public int removeAll() {
        int removed = 0;
        for (final Entity entity : this.entities) {
            if (entity.isValid()) {
                entity.remove();
                removed++;
            }
        }
        this.entities.clear();
        return removed;
    }
}

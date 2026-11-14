package core.amazake.core.modules.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Per-player position snapshots for local chat. Each snapshot is written only on that player's own region thread
 * (from a {@code runForPlayer} tick), so the async chat handler can read everyone's position from the map without
 * ever touching a live {@link org.bukkit.entity.Entity} off its owning thread. Thread-safe.
 */
final class PositionTracker {

    /** An immutable position snapshot. */
    record Pos(String world, double x, double y, double z) {
        double distanceSquared(final Pos other) {
            final double dx = this.x - other.x;
            final double dy = this.y - other.y;
            final double dz = this.z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<UUID, Pos> positions = new ConcurrentHashMap<>();

    /** Call on the player's own thread. */
    void update(final Player player) {
        final Location loc = player.getLocation();
        final World world = loc.getWorld();
        if (world != null) {
            this.positions.put(player.getUniqueId(), new Pos(world.getName(), loc.getX(), loc.getY(), loc.getZ()));
        }
    }

    Pos get(final UUID uuid) {
        return this.positions.get(uuid);
    }

    void remove(final UUID uuid) {
        this.positions.remove(uuid);
    }

    void clear() {
        this.positions.clear();
    }
}

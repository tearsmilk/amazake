package ink.hoka.amazake.stress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Plugin chunk tickets in stress_world, shared by all modules of a run and reference counted, so two
 * modules may hold the same chunk. A held chunk stays loaded (entity ticking) without any player nearby.
 * All tickets are released when the run stops. Main thread only (futures from {@link #loadAndHold}
 * complete on the main thread, as Paper's {@code World#getChunkAtAsync} does).
 */
public final class ChunkTickets {

    private final Plugin plugin;
    private final World world;
    private final Map<Long, Integer> holds = new HashMap<>();
    private boolean released;

    public ChunkTickets(Plugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    /**
     * Adds a ticket, loading (and generating) the chunk synchronously if needed. Prefer
     * {@link #loadAndHold(int, int)} for chunks that are probably not loaded yet.
     *
     * @return false if the run is already stopped
     */
    public boolean hold(int chunkX, int chunkZ) {
        if (this.released) {
            return false;
        }
        final long key = key(chunkX, chunkZ);
        final int count = this.holds.getOrDefault(key, 0);
        if (count == 0) {
            this.world.addPluginChunkTicket(chunkX, chunkZ, this.plugin);
        }
        this.holds.put(key, count + 1);
        return true;
    }

    /** Drops one hold; the ticket is removed when the last holder releases it. */
    public boolean release(int chunkX, int chunkZ) {
        final long key = key(chunkX, chunkZ);
        final Integer count = this.holds.get(key);
        if (count == null) {
            return false;
        }
        if (count <= 1) {
            this.holds.remove(key);
            this.world.removePluginChunkTicket(chunkX, chunkZ, this.plugin);
        } else {
            this.holds.put(key, count - 1);
        }
        return true;
    }

    /** Loads (generating if needed) the chunk asynchronously, then holds it with a ticket. */
    public CompletableFuture<Chunk> loadAndHold(int chunkX, int chunkZ) {
        return this.world.getChunkAtAsync(chunkX, chunkZ, true).thenApply(chunk -> {
            this.hold(chunkX, chunkZ);
            return chunk;
        });
    }

    /** Loads and holds every chunk of the region; completes when all of them are loaded. */
    public CompletableFuture<Void> loadAndHold(Region region) {
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>(region.chunkCount());
        region.forEachChunk((cx, cz) -> futures.add(this.loadAndHold(cx, cz)));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /** Synchronously loads and holds every chunk of the region (blocks the main thread while generating). */
    public void holdNow(Region region) {
        region.forEachChunk(this::hold);
    }

    /** Releases every chunk of the region (one hold each). */
    public void release(Region region) {
        region.forEachChunk(this::release);
    }

    /** Number of distinct chunks currently held. */
    public int heldCount() {
        return this.holds.size();
    }

    public boolean isReleased() {
        return this.released;
    }

    /** Releases everything; later {@link #hold} calls are ignored. Called by the framework on stop. */
    public void releaseAll() {
        this.released = true;
        this.holds.clear();
        this.world.removePluginChunkTickets(this.plugin);
    }
}

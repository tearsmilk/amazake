package ink.hoka.amazake.stress.profiler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Entity and chunk counts, read on the main thread (world data is not safe to read elsewhere).
 * The profiler thread hands the read to the Bukkit scheduler ({@code runTask}) and waits a little; when the
 * main thread is too busy to answer in time, that sample simply has no counts. Same contract as Amazake's
 * WorldSnapshots, without server internals.
 */
final class WorldSnapshots {

    record WorldStats(String name, String environment, int loadedChunks, int entities, int tileEntities, int players) {
    }

    record Snapshot(List<WorldStats> worlds, int players) {
        int entities() {
            int sum = 0;
            for (final WorldStats world : this.worlds) {
                sum += world.entities();
            }
            return sum;
        }

        int loadedChunks() {
            int sum = 0;
            for (final WorldStats world : this.worlds) {
                sum += world.loadedChunks();
            }
            return sum;
        }
    }

    private final Plugin plugin;
    /** A request still waiting for the main thread; no second one is queued behind it. */
    private @Nullable CompletableFuture<Snapshot> pending;

    WorldSnapshots(final Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Counts from the main thread, or null when it did not answer within {@code timeoutMs}.
     *
     * @param alsoOnMain run on the main thread right after the read (e.g. to look at the stress run); may be null
     */
    @Nullable Snapshot take(final boolean withTileEntities, final long timeoutMs, final @Nullable Runnable alsoOnMain) {
        if (this.pending != null && !this.pending.isDone()) {
            return null; // the main thread has not even run the previous request
        }
        if (Bukkit.isStopping() || !this.plugin.isEnabled()) {
            return null;
        }
        final CompletableFuture<Snapshot> future = new CompletableFuture<>();
        this.pending = future;
        try {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    future.complete(read(withTileEntities));
                    if (alsoOnMain != null) {
                        alsoOnMain.run();
                    }
                } catch (final Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final TimeoutException | ExecutionException | RuntimeException e) {
            return null; // also IllegalPluginAccessException while the plugin is being disabled
        }
    }

    /** Main thread only. */
    static Snapshot read(final boolean withTileEntities) {
        final List<WorldStats> worlds = new ArrayList<>();
        int players = 0;
        for (final World world : Bukkit.getWorlds()) {
            final int worldPlayers = world.getPlayerCount();
            players += worldPlayers;
            worlds.add(new WorldStats(
                world.getName(),
                world.getEnvironment().name(),
                world.getChunkCount(),
                world.getEntityCount(),
                withTileEntities ? world.getTileEntityCount() : 0,
                worldPlayers
            ));
        }
        return new Snapshot(List.copyOf(worlds), players);
    }

    /** Names only, for when the main thread never answered at the end. */
    static List<WorldStats> namesOnly() {
        final List<WorldStats> worlds = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            worlds.add(new WorldStats(world.getName(), world.getEnvironment().name(), 0, 0, 0, 0));
        }
        return worlds;
    }
}

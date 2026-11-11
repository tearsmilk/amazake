package ink.hoka.amazake.profiler;

import io.papermc.paper.util.MCUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Entity and chunk counts, read on the main thread (world data is not safe to read elsewhere).
 * The profiler thread hands the read to the server's task queue (MCUtil.MAIN_EXECUTOR) and waits a
 * little; when the main thread is too busy to answer, that sample simply has no counts.
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

    /** A request still waiting for the main thread; no second one is queued behind it. */
    private CompletableFuture<Snapshot> pending;

    /** Counts from the main thread, or null when it did not answer within {@code timeoutMs}. */
    Snapshot take(final boolean withTileEntities, final long timeoutMs) {
        if (this.pending != null && !this.pending.isDone()) {
            return null; // the main thread has not even run the previous request
        }
        if (Bukkit.isStopping()) {
            return null;
        }
        final CompletableFuture<Snapshot> future = new CompletableFuture<>();
        this.pending = future;
        try {
            MCUtil.MAIN_EXECUTOR.execute(() -> {
                if (!MCUtil.isMainThread()) {
                    future.complete(null); // the server is stopping and ran it on the caller's thread
                    return;
                }
                try {
                    future.complete(read(withTileEntities));
                } catch (final Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final TimeoutException | ExecutionException | RuntimeException e) {
            return null;
        }
    }

    private static Snapshot read(final boolean withTileEntities) {
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

package core.amazake.systems;

import core.amazake.systems.api.ServerStats;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;

/**
 * {@link ServerStats} for systems. The tick numbers are read from the server's 5-second tick data on the global tick
 * thread (where that data is written) by {@link #refresh}, run each second while a system is enabled, and served
 * from volatile fields to any thread.
 */
final class CoreServerStats implements ServerStats {

    static final CoreServerStats INSTANCE = new CoreServerStats();

    private volatile double tps = 20.0;
    private volatile double mspt;

    private CoreServerStats() {
    }

    /** Global tick thread only. */
    void refresh() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        final long interval = server.tickRateManager().nanosecondsPerTick();
        final Double average = server.tickTimes5s.getTPSAverage(null, interval);
        final double target = 1.0E9 / interval;
        this.tps = average == null ? target : Math.min(Math.min(20.0, target), average);
        final var data = server.getMSPTData5s();
        this.mspt = data == null ? 0.0 : data.avg();
    }

    @Override
    public double tps() {
        return this.tps;
    }

    @Override
    public double mspt() {
        return this.mspt;
    }

    @Override
    public int onlinePlayers() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }
}

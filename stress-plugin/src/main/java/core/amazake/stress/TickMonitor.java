package core.amazake.stress;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * Measures real MSPT/TPS through Paper's public {@link ServerTickEndEvent} (works on every Paper fork).
 * Keeps the last {@value #HISTORY} ticks and forwards every tick to the active run's statistics.
 */
public final class TickMonitor implements Listener {

    public static final int HISTORY = 1200;

    private final double[] durations = new double[HISTORY];
    private final long[] endNanos = new long[HISTORY];
    private int next;
    private long count;
    private @Nullable StageRunner sink;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        final double duration = event.getTickDuration();
        this.durations[this.next] = duration;
        this.endNanos[this.next] = System.nanoTime();
        this.next = (this.next + 1) % HISTORY;
        this.count++;
        final StageRunner runner = this.sink;
        if (runner != null) {
            runner.recordTick(duration);
        }
    }

    void setSink(@Nullable StageRunner runner) {
        this.sink = runner;
    }

    private int available(int ticks) {
        return (int) Math.min(Math.min(ticks, HISTORY - 1), this.count);
    }

    /** Average MSPT over the last {@code ticks} ticks (ms), 0 if no data. */
    public double averageMspt(int ticks) {
        final int n = this.available(ticks);
        if (n <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i <= n; i++) {
            sum += this.durations[Math.floorMod(this.next - i, HISTORY)];
        }
        return sum / n;
    }

    /** Max MSPT over the last {@code ticks} ticks (ms). */
    public double maxMspt(int ticks) {
        final int n = this.available(ticks);
        double max = 0.0;
        for (int i = 1; i <= n; i++) {
            max = Math.max(max, this.durations[Math.floorMod(this.next - i, HISTORY)]);
        }
        return max;
    }

    /** Real ticks per second over the last {@code ticks} ticks, measured by wall clock (not capped at 20). */
    public double tps(int ticks) {
        final int n = this.available(ticks);
        if (n < 2) {
            return 20.0;
        }
        final long newest = this.endNanos[Math.floorMod(this.next - 1, HISTORY)];
        final long oldest = this.endNanos[Math.floorMod(this.next - n, HISTORY)];
        final long span = newest - oldest;
        return span <= 0 ? 20.0 : (n - 1) * 1_000_000_000.0 / span;
    }

    public long ticksSeen() {
        return this.count;
    }
}

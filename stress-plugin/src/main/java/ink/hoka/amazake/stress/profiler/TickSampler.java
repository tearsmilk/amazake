package ink.hoka.amazake.stress.profiler;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Tick rate and tick durations per sample interval, from Paper's public {@link ServerTickEndEvent}.
 *
 * <p>Amazake's built-in profiler reads {@code Bukkit.getTickTimes()} instead, but what that array holds
 * (ring buffer or "last 5 s, oldest first") differs between Paper forks, so a plugin that must measure Leaf
 * and Amazake the same way listens to the event: every finished tick reports its duration on the main thread,
 * the profiler thread drains them once per interval. Ticks are counted exactly (no +-1 boundary error), and
 * the interval length is not limited by a 5 s history.
 */
final class TickSampler implements Listener {

    static final double SPIKE_MS = 50.0;
    static final int MAX_SPIKES = 200;

    record TickSample(double tps, double msptAvg, double msptMax, int ticks) {
    }

    record Spike(long t, double ms) {
    }

    private final Object lock = new Object();
    private final PriorityQueue<Spike> spikes = new PriorityQueue<>(Comparator.comparingDouble(Spike::ms));
    private volatile boolean active;
    private double[] pending = new double[256];
    private int pendingCount;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (!this.active) {
            return;
        }
        final double ms = event.getTickDuration();
        synchronized (this.lock) {
            if (this.pendingCount == this.pending.length) {
                this.pending = Arrays.copyOf(this.pending, this.pending.length * 2);
            }
            this.pending[this.pendingCount++] = ms;
        }
    }

    /** Starts counting; ticks finished before this call are ignored. */
    void start() {
        synchronized (this.lock) {
            this.pendingCount = 0;
            this.spikes.clear();
        }
        this.active = true;
    }

    void stop() {
        this.active = false;
    }

    /** The ticks finished since the previous call. Profiler thread. */
    TickSample sample(final long elapsedNs, final long tMs) {
        final double[] durations;
        final int count;
        synchronized (this.lock) {
            count = this.pendingCount;
            durations = Arrays.copyOf(this.pending, count);
            this.pendingCount = 0;
            if (this.pending.length > 4096) {
                this.pending = new double[256]; // shrink after a long stall
            }
        }
        final double seconds = elapsedNs / 1.0E9;
        final double tps = seconds <= 0.0 ? 0.0 : Math.min(20.0, count / seconds);
        double sum = 0.0;
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            final double ms = durations[i];
            sum += ms;
            max = Math.max(max, ms);
            if (ms > SPIKE_MS) {
                this.addSpike(new Spike(tMs, ms));
            }
        }
        return new TickSample(tps, count == 0 ? 0.0 : sum / count, max, count);
    }

    /** The longest ticks over 50 ms, longest first. */
    List<Spike> spikes() {
        final List<Spike> out;
        synchronized (this.lock) {
            out = new ArrayList<>(this.spikes);
        }
        out.sort(Comparator.comparingDouble(Spike::ms).reversed());
        return out;
    }

    private void addSpike(final Spike spike) {
        synchronized (this.lock) {
            if (this.spikes.size() < MAX_SPIKES) {
                this.spikes.add(spike);
            } else if (spike.ms() > this.spikes.peek().ms()) {
                this.spikes.poll();
                this.spikes.add(spike);
            }
        }
    }
}

package ink.hoka.amazake.profiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.bukkit.Bukkit;

/**
 * Tick rate and tick durations per sample interval.
 *
 * <p>{@link Bukkit#getCurrentTick()} counts ticks as they start. {@link Bukkit#getTickTimes()} returns,
 * oldest first, the durations in nanoseconds of the ticks finished in the last ~5 seconds (Moonrise
 * TickData behind MinecraftServer#getMSPTData5s, read under the server's stats lock, so it is safe
 * off the main thread). The ticks of one interval are the last "ticks done" entries of that array.</p>
 *
 * <p>Approximation: "ticks done" counts started ticks, the array holds finished ones, so when a tick
 * is in progress at one read and not at the next, an interval can take one tick too many or too few
 * (the boundary tick may be counted in two neighbouring intervals). The Bukkit API exposes no finished
 * tick counter to align on, so per-interval tick numbers are accurate to +-1 tick. The array covers
 * only ~5 s, which is why profiler.interval-ms is capped in AmazakeConfig.</p>
 */
final class TickSampler {

    static final double SPIKE_MS = 50.0;
    static final int MAX_SPIKES = 200;

    record TickSample(double tps, double msptAvg, double msptMax) {
    }

    record Spike(long t, double ms) {
    }

    private final PriorityQueue<Spike> spikes = new PriorityQueue<>(Comparator.comparingDouble(Spike::ms));
    private int lastTick;

    void start() {
        this.lastTick = Bukkit.getCurrentTick();
    }

    TickSample sample(final long elapsedNs, final long tMs) {
        final int tick = Bukkit.getCurrentTick();
        final int done = Math.max(0, tick - this.lastTick);
        this.lastTick = tick;

        final double seconds = elapsedNs / 1.0E9;
        final double tps = seconds <= 0.0 ? 0.0 : Math.min(20.0, done / seconds);

        final long[] times = Bukkit.getTickTimes();
        final int count = Math.min(done, times.length);
        double sum = 0.0;
        double max = 0.0;
        for (int i = times.length - count; i < times.length; i++) {
            final double ms = times[i] / 1.0E6;
            sum += ms;
            max = Math.max(max, ms);
            if (ms > SPIKE_MS) {
                this.addSpike(new Spike(tMs, ms));
            }
        }
        return new TickSample(tps, count == 0 ? 0.0 : sum / count, max);
    }

    /** The longest ticks over 50 ms, longest first. */
    List<Spike> spikes() {
        final List<Spike> out = new ArrayList<>(this.spikes);
        out.sort(Comparator.comparingDouble(Spike::ms).reversed());
        return out;
    }

    private void addSpike(final Spike spike) {
        if (this.spikes.size() < MAX_SPIKES) {
            this.spikes.add(spike);
        } else if (spike.ms() > this.spikes.peek().ms()) {
            this.spikes.poll();
            this.spikes.add(spike);
        }
    }
}

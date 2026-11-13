package ink.hoka.amazake.systems.tab;

import java.util.List;

/**
 * An animation: frames shown one after another, each for {@code intervalTicks} ticks. Pure and immutable.
 * The frame is derived from a global tick counter, so every player sees the same frame at the same time and
 * no per-player animation state is needed.
 */
public record Frames(List<String> frames, int intervalTicks) {

    public static final Frames EMPTY = new Frames(List.of(), 20);

    public Frames {
        frames = List.copyOf(frames);
        intervalTicks = Math.max(1, intervalTicks);
    }

    public static Frames of(final int intervalTicks, final String... frames) {
        return new Frames(List.of(frames), intervalTicks);
    }

    public boolean isEmpty() {
        return this.frames.isEmpty();
    }

    /** Index of the frame shown at {@code tick}; -1 when there are no frames. Negative ticks are treated as 0. */
    public int index(final long tick) {
        if (this.frames.isEmpty()) {
            return -1;
        }
        if (this.frames.size() == 1) {
            return 0;
        }
        final long step = Math.max(0L, tick) / this.intervalTicks;
        return (int) (step % this.frames.size());
    }

    /** The frame shown at {@code tick}; "" when there are no frames. */
    public String at(final long tick) {
        final int index = this.index(tick);
        return index < 0 ? "" : this.frames.get(index);
    }
}

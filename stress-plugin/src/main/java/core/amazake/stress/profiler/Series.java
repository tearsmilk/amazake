package core.amazake.stress.profiler;

// Ported unchanged from Amazake (amazake-server/src/main/java/core/amazake/profiler/Series.java),
// so the stress plugin measures CPU and threads exactly like the built-in /amazake profile.

import java.util.Arrays;

/** A growable list of per-sample values; samples a thread was not seen in stay 0. */
final class Series {

    private double[] values = new double[16];
    private int size;

    void set(final int index, final double value) {
        if (index >= this.values.length) {
            this.values = Arrays.copyOf(this.values, Math.max(index + 1, this.values.length * 2));
        }
        this.values[index] = value;
        this.size = Math.max(this.size, index + 1);
    }

    double get(final int index) {
        return index < this.size ? this.values[index] : 0.0;
    }

    /** The values padded or cut to exactly {@code length} samples. */
    double[] toArray(final int length) {
        final double[] out = new double[length];
        System.arraycopy(this.values, 0, out, 0, Math.min(length, this.size));
        return out;
    }
}

package core.amazake.stress.mobs;

import core.amazake.stress.BlockWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.block.data.BlockData;

/**
 * A queue of block operations executed with a fixed block budget per tick, so that building a structure is
 * spread over several ticks instead of showing up as one huge lag spike in the measurement.
 *
 * <p>Operations run strictly in the order they were added (boxes iterate x, then z, then y ascending), so the
 * result is identical on every server. Main thread only. Used by the mobs and physics modules.
 */
public final class BuildQueue {

    private final BlockWriter blocks;
    private final Deque<Op> ops = new ArrayDeque<>();
    private long placed;
    private long queued;

    public BuildQueue(BlockWriter blocks) {
        this.blocks = blocks;
    }

    /** Inclusive box; {@code physics=false} places without neighbour updates. */
    public BuildQueue box(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data, boolean physics) {
        final Box box = new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), data, physics);
        this.ops.addLast(box);
        this.queued += box.volume;
        return this;
    }

    /** Inclusive box without physics. */
    public BuildQueue box(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data) {
        return this.box(x1, y1, z1, x2, y2, z2, data, false);
    }

    /** Hollow walls (four sides, no floor or roof) of the inclusive box, without physics. */
    public BuildQueue walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data) {
        this.box(x1, y1, z1, x2, y2, z1, data);
        this.box(x1, y1, z2, x2, y2, z2, data);
        if (z2 - z1 > 1) {
            this.box(x1, y1, z1 + 1, x1, y2, z2 - 1, data);
            this.box(x2, y1, z1 + 1, x2, y2, z2 - 1, data);
        }
        return this;
    }

    /** One block without physics. */
    public BuildQueue set(int x, int y, int z, BlockData data) {
        return this.box(x, y, z, x, y, z, data, false);
    }

    /** One block with physics (neighbour updates). */
    public BuildQueue setWithPhysics(int x, int y, int z, BlockData data) {
        return this.box(x, y, z, x, y, z, data, true);
    }

    /** An arbitrary action (e.g. filling a dispenser) executed in order; costs one unit of budget. */
    public BuildQueue action(Runnable action) {
        this.ops.addLast(new Action(action));
        this.queued++;
        return this;
    }

    /**
     * Executes up to {@code budget} block placements.
     *
     * @return true when the queue is empty afterwards
     */
    public boolean step(int budget) {
        int left = Math.max(1, budget);
        while (left > 0 && !this.ops.isEmpty()) {
            final Op op = this.ops.peekFirst();
            final int used = op.run(this, left);
            left -= used;
            this.placed += used;
            if (op.done()) {
                this.ops.pollFirst();
            }
        }
        return this.ops.isEmpty();
    }

    public boolean isEmpty() {
        return this.ops.isEmpty();
    }

    public void clear() {
        this.ops.clear();
    }

    /** Placements executed so far. */
    public long placed() {
        return this.placed;
    }

    /** Placements ever queued. */
    public long queued() {
        return this.queued;
    }

    /** Progress in percent of everything ever queued (100 when idle). */
    public int percent() {
        return this.queued == 0 ? 100 : (int) Math.min(100, this.placed * 100 / this.queued);
    }

    private interface Op {
        int run(BuildQueue queue, int budget);

        boolean done();
    }

    private static final class Box implements Op {
        private final int x1, y1, z1, sy, sz;
        private final long volume;
        private final BlockData data;
        private final boolean physics;
        private long index;

        Box(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data, boolean physics) {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.sy = y2 - y1 + 1;
            this.sz = z2 - z1 + 1;
            this.volume = (long) (x2 - x1 + 1) * this.sy * this.sz;
            this.data = data;
            this.physics = physics;
        }

        @Override
        public int run(BuildQueue queue, int budget) {
            int used = 0;
            while (used < budget && this.index < this.volume) {
                final long i = this.index++;
                final int y = this.y1 + (int) (i % this.sy);
                final int z = this.z1 + (int) ((i / this.sy) % this.sz);
                final int x = this.x1 + (int) (i / ((long) this.sy * this.sz));
                if (this.physics) {
                    queue.blocks.setWithPhysics(x, y, z, this.data);
                } else {
                    queue.blocks.set(x, y, z, this.data);
                }
                used++;
            }
            return used;
        }

        @Override
        public boolean done() {
            return this.index >= this.volume;
        }
    }

    private static final class Action implements Op {
        private final Runnable action;
        private boolean done;

        Action(Runnable action) {
            this.action = action;
        }

        @Override
        public int run(BuildQueue queue, int budget) {
            this.done = true;
            this.action.run();
            return 1;
        }

        @Override
        public boolean done() {
            return this.done;
        }
    }
}

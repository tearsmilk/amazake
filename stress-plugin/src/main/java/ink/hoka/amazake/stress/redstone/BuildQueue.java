package ink.hoka.amazake.stress.redstone;

import ink.hoka.amazake.stress.BlockWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;

/**
 * Builds {@link Plan}s over several ticks with a fixed budget of work units per tick, so building does not
 * show up as lag in the measurement. Plans are processed strictly in queue order (deterministic). Main thread.
 *
 * <p>Costs: one checked block while clearing = 1, one placed block = 1, one kick (with physics) = 4,
 * post actions declare their own cost.
 */
final class BuildQueue {

    private static final int PHASE_CLEAR = 0;
    private static final int PHASE_BLOCKS = 1;
    private static final int PHASE_ACTIONS = 2;
    private static final int PHASE_KICKS = 3;
    private static final int PHASE_DONE = 4;

    private final BlockWriter blocks;
    private final boolean clear;
    private final Consumer<Plan> onBuilt;
    private final Deque<Plan> plans = new ArrayDeque<>();

    private Plan current;
    private int phase;
    private int cursor;
    private long placed;

    BuildQueue(BlockWriter blocks, boolean clear, Consumer<Plan> onBuilt) {
        this.blocks = blocks;
        this.clear = clear;
        this.onBuilt = onBuilt;
    }

    void add(Plan plan) {
        this.plans.add(plan);
    }

    boolean isIdle() {
        return this.current == null && this.plans.isEmpty();
    }

    /** Plans waiting or in progress. */
    int pending() {
        return this.plans.size() + (this.current == null ? 0 : 1);
    }

    long placedBlocks() {
        return this.placed;
    }

    void clear() {
        this.plans.clear();
        this.current = null;
    }

    /** Does up to {@code budget} units of work. */
    void step(int budget) {
        int left = budget;
        while (left > 0) {
            if (this.current == null) {
                this.current = this.plans.poll();
                if (this.current == null) {
                    return;
                }
                this.phase = this.clear && this.current.hasClearBox() ? PHASE_CLEAR : PHASE_BLOCKS;
                this.cursor = 0;
            }
            final Plan plan = this.current;
            switch (this.phase) {
                case PHASE_CLEAR -> left -= this.clearStep(plan, left);
                case PHASE_BLOCKS -> {
                    if (this.cursor >= plan.blocks.size()) {
                        this.next(PHASE_ACTIONS);
                    } else {
                        final Plan.Placement p = plan.blocks.get(this.cursor++);
                        this.blocks.set(p.x(), p.y(), p.z(), p.data());
                        this.placed++;
                        left--;
                    }
                }
                case PHASE_ACTIONS -> {
                    if (this.cursor >= plan.actions.size()) {
                        this.next(PHASE_KICKS);
                    } else {
                        final Plan.Action action = plan.actions.get(this.cursor++);
                        action.task().run();
                        left -= Math.max(1, action.cost());
                    }
                }
                case PHASE_KICKS -> {
                    if (this.cursor >= plan.kicks.size()) {
                        this.next(PHASE_DONE);
                    } else {
                        final Plan.Placement p = plan.kicks.get(this.cursor++);
                        this.blocks.setWithPhysics(p.x(), p.y(), p.z(), p.data());
                        this.placed++;
                        left -= 4;
                    }
                }
                default -> {
                    this.current = null;
                    this.onBuilt.accept(plan);
                }
            }
        }
    }

    private void next(int phase) {
        this.phase = phase;
        this.cursor = 0;
    }

    /** Clears one column (all Y of the box) per call; cursor walks the columns x-major. Returns cost. */
    private int clearStep(Plan plan, int left) {
        final int sizeX = plan.clearMaxX - plan.clearMinX + 1;
        final int sizeZ = plan.clearMaxZ - plan.clearMinZ + 1;
        int cost = 0;
        while (cost < left) {
            if (this.cursor >= sizeX * sizeZ) {
                this.next(PHASE_BLOCKS);
                return Math.max(1, cost);
            }
            final int x = plan.clearMinX + this.cursor / sizeZ;
            final int z = plan.clearMinZ + this.cursor % sizeZ;
            this.cursor++;
            for (int y = plan.clearMinY; y <= plan.clearMaxY; y++) {
                final Block block = this.blocks.block(x, y, z);
                final Material type = block.getType();
                cost++;
                if (type.isAir()) {
                    continue;
                }
                if (type == Material.HOPPER || type == Material.DROPPER || type == Material.DISPENSER
                    || type == Material.CHEST) {
                    final BlockState state = block.getState(false);
                    if (state instanceof Container container) {
                        container.getInventory().clear();
                    }
                }
                this.blocks.set(x, y, z, Material.AIR);
            }
        }
        return Math.max(1, cost);
    }
}

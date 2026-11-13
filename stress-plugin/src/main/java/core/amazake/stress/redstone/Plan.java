package core.amazake.stress.redstone;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.data.BlockData;

/**
 * Blueprint of one contraption: an optional box cleared first, then blocks placed WITHOUT physics, then
 * post actions (fill containers, spawn carts), then "kick" blocks placed WITH physics (the last block that
 * starts the clock). {@link #kills} are the blocks set (without physics) on stop to switch the clock off.
 */
final class Plan {

    /** One block placement, absolute coordinates. */
    record Placement(int x, int y, int z, BlockData data) {
    }

    /** A post-build action with its cost in budget units. */
    record Action(int cost, Runnable task) {
    }

    final RedstoneModule.Kind kind;
    final int index;
    final List<Placement> blocks = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final List<Placement> kicks = new ArrayList<>();
    final List<Placement> kills = new ArrayList<>();

    // clear box (inclusive); clear is skipped when minX > maxX
    int clearMinX = 1;
    int clearMaxX = 0;
    int clearMinY;
    int clearMaxY;
    int clearMinZ;
    int clearMaxZ;

    Plan(RedstoneModule.Kind kind, int index) {
        this.kind = kind;
        this.index = index;
    }

    void clearBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.clearMinX = minX;
        this.clearMinY = minY;
        this.clearMinZ = minZ;
        this.clearMaxX = maxX;
        this.clearMaxY = maxY;
        this.clearMaxZ = maxZ;
    }

    void set(int x, int y, int z, BlockData data) {
        this.blocks.add(new Placement(x, y, z, data));
    }

    void kick(int x, int y, int z, BlockData data) {
        this.kicks.add(new Placement(x, y, z, data));
    }

    void kill(int x, int y, int z, BlockData data) {
        this.kills.add(new Placement(x, y, z, data));
    }

    void action(int cost, Runnable task) {
        this.actions.add(new Action(cost, task));
    }

    boolean hasClearBox() {
        return this.clearMinX <= this.clearMaxX;
    }
}

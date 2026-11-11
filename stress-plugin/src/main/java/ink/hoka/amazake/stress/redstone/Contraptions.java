package ink.hoka.amazake.stress.redstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

/**
 * Blueprints of the redstone contraptions. Pure geometry: every method appends placements to a {@link Plan}
 * at absolute coordinates. Layer convention: {@code yb} = support layer (lamps / smooth stone),
 * {@code yb + 1} = component layer (dust, repeaters, observers, hoppers, rails).
 *
 * <p>All contraptions are built with physics OFF and started by one "kick" block placed last WITH physics,
 * so they self-start without players and a rebuild over a cleared box gives the same machine.
 */
final class Contraptions {

    /** A repeater every this many line blocks (dust loses 1 power per block; 12 keeps a safe margin). */
    static final int LINE_REPEATER_PERIOD = 12;

    /** Items used for hopper chains (all stack to 64). */
    private static final Material[] ITEMS = {
        Material.COBBLESTONE, Material.IRON_INGOT, Material.REDSTONE, Material.OAK_PLANKS,
        Material.SAND, Material.GLASS, Material.COAL, Material.COPPER_INGOT,
    };

    /** Something that fills a container at a position (implemented by the module). */
    interface Filler {
        void fill(int x, int y, int z, Material item, int amount);
    }

    /** One cart to spawn: block position on the rail and its start velocity. */
    record CartSpawn(int x, int y, int z, double vx, double vz) {
    }

    private final Parts parts;

    Contraptions(Parts parts) {
        this.parts = parts;
    }

    static boolean isLineRepeater(int k) {
        return k % LINE_REPEATER_PERIOD == LINE_REPEATER_PERIOD - 1;
    }

    // ------------------------------------------------------------------ dust line on lamps

    /**
     * Straight dust line running east from {@code (x0, y, z)} on a row of redstone lamps at {@code y - 1}
     * (dust powers the block under it, so every lamp toggles with the clock), with a repeater every
     * {@link #LINE_REPEATER_PERIOD} blocks to refresh the signal.
     */
    void line(Plan p, int x0, int y, int z, int length, int repeaterDelay) {
        for (int k = 0; k < length; k++) {
            final int x = x0 + k;
            p.set(x, y - 1, z, this.parts.lamp());
            if (isLineRepeater(k)) {
                p.set(x, y, z, this.parts.repeater(BlockFace.WEST, repeaterDelay));
            } else {
                p.set(x, y, z, this.parts.wire(BlockFace.EAST, BlockFace.WEST));
            }
        }
    }

    // ------------------------------------------------------------------ observer clock

    /**
     * Two observers facing each other at {@code (ox, oz)} and {@code (ox + 1, oz)}: the fastest vanilla clock
     * (pulse every few ticks). The east observer emits east into a dust line on lamps. Kick: the east observer
     * placed last with physics (the west one sees it appear and the pair starts pulsing forever).
     */
    void observerClock(Plan p, int ox, int yb, int oz, int length, int lineDelay) {
        final int yc = yb + 1;
        p.set(ox, yb, oz, this.parts.plain(Material.SMOOTH_STONE));
        p.set(ox + 1, yb, oz, this.parts.plain(Material.SMOOTH_STONE));
        p.set(ox, yc, oz, this.parts.observer(BlockFace.EAST));
        this.line(p, ox + 2, yc, oz, length, lineDelay);
        p.kick(ox + 1, yc, oz, this.parts.observer(BlockFace.WEST));
        p.kill(ox + 1, yc, oz, this.parts.air());
    }

    // ------------------------------------------------------------------ torch + repeater clock

    /**
     * Torch/repeater ring clock in the 4x3 box {@code x in [ox, ox+3], z in [oz, oz+2]}: a wall torch on a
     * stone block feeds a ring of 4 repeaters and dust that comes back into that stone block and switches the
     * torch off, and so on. Half period = 2 + 8 * delay ticks (delay 1 = 10 ticks: far from torch burnout).
     * The ring's output dust at {@code (ox + 3, oz)} continues east: the caller attaches a line at
     * {@code (ox + 4, oz)}. Kick: the torch placed last with physics.
     */
    void torchClock(Plan p, int ox, int yb, int oz, int delay) {
        final int yc = yb + 1;
        final int[][] supports = {{0, 0}, {2, 0}, {3, 0}, {3, 1}, {3, 2}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};
        for (final int[] s : supports) {
            p.set(ox + s[0], yb, oz + s[1], this.parts.plain(Material.SMOOTH_STONE));
        }
        p.set(ox, yc, oz, this.parts.plain(Material.SMOOTH_STONE));                            // torch block
        p.set(ox + 2, yc, oz, this.parts.repeater(BlockFace.WEST, delay));                     // torch -> east
        p.set(ox + 3, yc, oz, this.parts.wire(BlockFace.WEST, BlockFace.SOUTH, BlockFace.EAST)); // -> line
        p.set(ox + 3, yc, oz + 1, this.parts.wire(BlockFace.NORTH, BlockFace.SOUTH));
        p.set(ox + 3, yc, oz + 2, this.parts.wire(BlockFace.NORTH, BlockFace.WEST));
        p.set(ox + 2, yc, oz + 2, this.parts.repeater(BlockFace.EAST, delay));                 // -> west
        p.set(ox + 1, yc, oz + 2, this.parts.repeater(BlockFace.EAST, delay));
        p.set(ox, yc, oz + 2, this.parts.wire(BlockFace.EAST, BlockFace.NORTH));
        p.set(ox, yc, oz + 1, this.parts.repeater(BlockFace.SOUTH, delay));                    // -> torch block
        p.kick(ox + 1, yc, oz, this.parts.wallTorch(BlockFace.EAST));
        p.kill(ox + 1, yc, oz, this.parts.air());
    }

    /** Torch/repeater clock driving a dust line on lamps (cell origin = ring corner). */
    void repeaterClock(Plan p, int ox, int yb, int oz, int length, int ringDelay, int lineDelay) {
        this.torchClock(p, ox, yb, oz, ringDelay);
        this.line(p, ox + 4, yb + 1, oz, length, lineDelay);
    }

    // ------------------------------------------------------------------ piston wall

    /**
     * Repeater clock driving a dust line on lamps; the lamps under the dust are powered, so the sticky
     * pistons next to them (on both sides, z = oz +- 1, same layer as the lamps) fire on every clock
     * period, each pushing a slime or honey block with a stone block stuck on top (slime and honey
     * alternate, so neighbours never stick together and every piston moves only its own 2 blocks).
     * Occupies z in [oz - 3, oz + 3]. Build with {@code yb} one block above the ground so the slime
     * blocks do not drag the ground with them.
     */
    void pistonWall(Plan p, int ox, int yb, int oz, int length, int ringDelay, int lineDelay) {
        this.repeaterClock(p, ox, yb, oz, length, ringDelay, lineDelay);
        final int yc = yb + 1;
        for (int k = 2; k < length; k++) {
            if (isLineRepeater(k)) {
                continue; // the lamp under a repeater is not powered
            }
            final int x = ox + 4 + k;
            final boolean even = (k & 1) == 0;
            p.set(x, yb, oz + 1, this.parts.stickyPiston(BlockFace.SOUTH));
            p.set(x, yb, oz + 2, this.parts.plain(even ? Material.SLIME_BLOCK : Material.HONEY_BLOCK));
            p.set(x, yc, oz + 2, this.parts.plain(Material.STONE));
            p.set(x, yb, oz - 1, this.parts.stickyPiston(BlockFace.NORTH));
            p.set(x, yb, oz - 2, this.parts.plain(even ? Material.HONEY_BLOCK : Material.SLIME_BLOCK));
            p.set(x, yc, oz - 2, this.parts.plain(Material.STONE));
        }
    }

    // ------------------------------------------------------------------ hopper loop

    /**
     * Closed loop of {@code 2 * length} containers in two rows (z = oz going east, z = oz + 1 coming back
     * west), so items circulate forever and never run out. Element 0 is a dropper (when
     * {@code dropperClock}) fired by a vertical observer clock above it, which pushes items into the next
     * hopper and periodically locks its two neighbour hoppers. A comparator reads hopper
     * {@code (ox + length / 2, oz)} and drives (through a repeater) a dust line on lamps at z = oz - 3, so the
     * flow of items toggles redstone too. Occupies z in [oz - 3, oz + 1], y up to yb + 3.
     *
     * @param random consumed in a fixed order (item type per container)
     */
    void hopperLoop(Plan p, int ox, int yb, int oz, int length, int itemsTotal, int lineLength,
                    boolean dropperClock, Random random, Filler filler) {
        final int yc = yb + 1;
        final int n = 2 * length;
        final List<int[]> ring = new ArrayList<>(n);
        for (int i = 0; i < length; i++) {
            ring.add(new int[]{ox + i, oz, i < length - 1 ? 0 : 1}); // 0 = east, 1 = south
        }
        for (int i = length - 1; i >= 0; i--) {
            ring.add(new int[]{ox + i, oz + 1, i > 0 ? 2 : 3});      // 2 = west, 3 = north
        }
        final BlockFace[] faces = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH};
        for (int j = 0; j < n; j++) {
            final int[] e = ring.get(j);
            final BlockFace out = faces[e[2]];
            p.set(e[0], yc, e[1], j == 0 && dropperClock ? this.parts.dropper(out) : this.parts.hopper(out));
        }

        // comparator -> repeater -> dust corner -> line on lamps
        final int c = ox + length / 2;
        p.set(c, yb, oz - 1, this.parts.plain(Material.SMOOTH_STONE));
        p.set(c, yc, oz - 1, this.parts.comparator(BlockFace.SOUTH));
        p.set(c, yb, oz - 2, this.parts.plain(Material.SMOOTH_STONE));
        p.set(c, yc, oz - 2, this.parts.repeater(BlockFace.SOUTH, 1));
        p.set(c, yb, oz - 3, this.parts.lamp());
        p.set(c, yc, oz - 3, this.parts.wire(BlockFace.SOUTH, BlockFace.EAST));
        this.line(p, c + 1, yc, oz - 3, lineLength, 1);

        // fill: total spread over every container, item type per container from the seeded random
        final int per = itemsTotal / n;
        final int rem = itemsTotal % n;
        for (int j = 0; j < n; j++) {
            final int[] e = ring.get(j);
            final Material item = ITEMS[random.nextInt(ITEMS.length)];
            final int capacity = (j == 0 && dropperClock ? 9 : 5) * 64;
            final int amount = Math.min(capacity, per + (j < rem ? 1 : 0));
            if (amount > 0) {
                final int x = e[0];
                final int z = e[1];
                p.action(8, () -> filler.fill(x, yc, z, item, amount));
            }
        }

        if (dropperClock) {
            p.set(ox, yc + 1, oz, this.parts.observer(BlockFace.UP));    // emits down into the dropper
            p.kick(ox, yc + 2, oz, this.parts.observer(BlockFace.DOWN));
            p.kill(ox, yc + 2, oz, this.parts.air());
        }
    }

    // ------------------------------------------------------------------ minecart loop

    /**
     * Square rail loop with side {@code size} (corner at (ox, oz)), clockwise; every 6th straight rail is a
     * powered rail on a redstone block. Returns the carts to spawn: {@code carts} hopper minecarts spread
     * evenly over the loop (never on a corner), each moving along the loop direction at {@code speed}.
     */
    List<CartSpawn> minecartLoop(Plan p, int ox, int yb, int oz, int size, int carts, double speed) {
        final int yc = yb + 1;
        final int s = Math.max(4, size);
        final List<int[]> loop = new ArrayList<>(4 * s - 4);      // {dx, dz, shapeOrdinalIndex, dirX, dirZ, corner}
        for (int x = 0; x < s; x++) {
            loop.add(new int[]{x, 0, x == 0 ? 0 : x == s - 1 ? 1 : 4, 1, 0, x == 0 || x == s - 1 ? 1 : 0});
        }
        for (int z = 1; z < s; z++) {
            loop.add(new int[]{s - 1, z, z == s - 1 ? 2 : 5, 0, 1, z == s - 1 ? 1 : 0});
        }
        for (int x = s - 2; x >= 0; x--) {
            loop.add(new int[]{x, s - 1, x == 0 ? 3 : 4, -1, 0, x == 0 ? 1 : 0});
        }
        for (int z = s - 2; z >= 1; z--) {
            loop.add(new int[]{0, z, 5, 0, -1, 0});
        }
        final Rail.Shape[] shapes = {
            Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST, Rail.Shape.NORTH_WEST, Rail.Shape.NORTH_EAST,
            Rail.Shape.EAST_WEST, Rail.Shape.NORTH_SOUTH,
        };
        for (int i = 0; i < loop.size(); i++) {
            final int[] e = loop.get(i);
            final int x = ox + e[0];
            final int z = oz + e[1];
            final Rail.Shape shape = shapes[e[2]];
            if (e[5] == 0 && i % 6 == 3) {
                p.set(x, yb, z, this.parts.plain(Material.REDSTONE_BLOCK));
                p.set(x, yc, z, this.parts.poweredRail(shape));
            } else {
                p.set(x, yb, z, this.parts.plain(Material.SMOOTH_STONE));
                p.set(x, yc, z, this.parts.rail(shape));
            }
        }
        final int count = Math.max(0, Math.min(carts, loop.size() / 2));
        final List<CartSpawn> spawns = new ArrayList<>(count);
        for (int j = 0; j < count; j++) {
            int i = (int) ((long) j * loop.size() / count);
            while (loop.get(i)[5] == 1) {
                i = (i + 1) % loop.size();
            }
            final int[] e = loop.get(i);
            spawns.add(new CartSpawn(ox + e[0], yc, oz + e[1], e[3] * speed, e[4] * speed));
        }
        return spawns;
    }
}

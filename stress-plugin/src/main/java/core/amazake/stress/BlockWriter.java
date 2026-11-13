package core.amazake.stress;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Fast block placement in stress_world. {@code set}/{@code fill} do NOT apply physics (no neighbour
 * updates), which is what you want while building; use {@link #setWithPhysics} to trigger the load
 * (e.g. the last block that starts a redstone clock or makes sand fall). Main thread only.
 */
public final class BlockWriter {

    private final World world;
    private final Map<String, BlockData> cache = new HashMap<>();
    private long changed;

    public BlockWriter(World world) {
        this.world = world;
    }

    /** Parses (and caches) block data, e.g. {@code "minecraft:hopper[facing=east]"}. Returns a shared instance: clone before mutating. */
    public BlockData data(String blockData) {
        return this.cache.computeIfAbsent(blockData, Bukkit::createBlockData);
    }

    /** Default block data of a material (shared instance: clone before mutating). */
    public BlockData data(Material material) {
        return this.cache.computeIfAbsent(material.getKey().toString(), k -> material.createBlockData());
    }

    /** Sets a block without physics. */
    public void set(int x, int y, int z, BlockData data) {
        this.world.getBlockAt(x, y, z).setBlockData(data, false);
        this.changed++;
    }

    public void set(int x, int y, int z, Material material) {
        this.set(x, y, z, this.data(material));
    }

    /** Sets a block and applies physics / neighbour updates. */
    public void setWithPhysics(int x, int y, int z, BlockData data) {
        this.world.getBlockAt(x, y, z).setBlockData(data, true);
        this.changed++;
    }

    public Block block(int x, int y, int z) {
        return this.world.getBlockAt(x, y, z);
    }

    /** Fills the inclusive box without physics, in a fixed x, z, y order; returns the number of blocks set. */
    public int fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data) {
        final int minX = Math.min(x1, x2);
        final int maxX = Math.max(x1, x2);
        final int minY = Math.max(Math.min(y1, y2), this.world.getMinHeight());
        final int maxY = Math.min(Math.max(y1, y2), this.world.getMaxHeight() - 1);
        final int minZ = Math.min(z1, z2);
        final int maxZ = Math.max(z1, z2);
        int count = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    this.world.getBlockAt(x, y, z).setBlockData(data, false);
                    count++;
                }
            }
        }
        this.changed += count;
        return count;
    }

    /** Fills the whole horizontal area of the region between {@code y1} and {@code y2} (absolute, inclusive). */
    public int fill(Region region, int y1, int y2, BlockData data) {
        return this.fill(region.minX(), y1, region.minZ(), region.maxX(), y2, region.maxZ(), data);
    }

    /** Blocks changed through this writer so far. */
    public long changedBlocks() {
        return this.changed;
    }

    public World world() {
        return this.world;
    }
}

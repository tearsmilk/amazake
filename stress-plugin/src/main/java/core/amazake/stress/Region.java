package core.amazake.stress;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A chunk-aligned build area in stress_world handed out by {@link RegionAllocator}. Regions never overlap
 * and their coordinates depend only on the module id and the order of allocations inside that module,
 * so they are identical on every server and in both "all" and single-module mode.
 *
 * <p>All block coordinates are inclusive: x in [{@link #minX()}, {@link #maxX()}], y in [{@link #minY()},
 * {@link #maxY()}], z in [{@link #minZ()}, {@link #maxZ()}].
 */
public record Region(String owner, String name, int minChunkX, int minChunkZ, int chunksX, int chunksZ,
                     int minY, int maxY) {

    public int maxChunkX() {
        return this.minChunkX + this.chunksX - 1;
    }

    public int maxChunkZ() {
        return this.minChunkZ + this.chunksZ - 1;
    }

    public int minX() {
        return this.minChunkX << 4;
    }

    public int minZ() {
        return this.minChunkZ << 4;
    }

    public int maxX() {
        return this.minX() + this.sizeX() - 1;
    }

    public int maxZ() {
        return this.minZ() + this.sizeZ() - 1;
    }

    public int sizeX() {
        return this.chunksX << 4;
    }

    public int sizeZ() {
        return this.chunksZ << 4;
    }

    public int height() {
        return this.maxY - this.minY + 1;
    }

    public int centerX() {
        return this.minX() + this.sizeX() / 2;
    }

    public int centerZ() {
        return this.minZ() + this.sizeZ() / 2;
    }

    public int chunkCount() {
        return this.chunksX * this.chunksZ;
    }

    public boolean contains(int x, int y, int z) {
        return x >= this.minX() && x <= this.maxX() && y >= this.minY && y <= this.maxY
            && z >= this.minZ() && z <= this.maxZ();
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunkX >= this.minChunkX && chunkX <= this.maxChunkX()
            && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ();
    }

    /** Block position relative to the region's min corner (dy relative to {@link #minY()}). */
    public Location at(World world, double dx, double dy, double dz) {
        return new Location(world, this.minX() + dx, this.minY + dy, this.minZ() + dz);
    }

    /** Centre of the region at {@link #minY()}. */
    public Location center(World world) {
        return new Location(world, this.centerX() + 0.5, this.minY, this.centerZ() + 0.5);
    }

    /** Calls {@code action} for every chunk of the region, x-major then z, in a fixed order. */
    public void forEachChunk(ChunkAction action) {
        for (int cx = this.minChunkX; cx <= this.maxChunkX(); cx++) {
            for (int cz = this.minChunkZ; cz <= this.maxChunkZ(); cz++) {
                action.accept(cx, cz);
            }
        }
    }

    @FunctionalInterface
    public interface ChunkAction {
        void accept(int chunkX, int chunkZ);
    }

    @Override
    public String toString() {
        return this.owner + "/" + this.name + " [" + this.minX() + "," + this.minY + "," + this.minZ() + " .. "
            + this.maxX() + "," + this.maxY + "," + this.maxZ() + "]";
    }
}

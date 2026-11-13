package core.amazake.stress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic, non-overlapping build areas in stress_world.
 *
 * <p>Every module owns a fixed "lane": a strip of chunks along +Z starting at chunk
 * {@code x = LANE_ORIGIN_CHUNK_X + lane * LANE_SPACING_CHUNKS}, {@code z = LANE_ORIGIN_CHUNK_Z}. The lane
 * index is fixed per module id in {@link ModuleRegistry} (mobs 0, physics 1, redstone 2, chunks 3,
 * packets 4, async 5). Inside a lane, regions are stacked along +Z in allocation order with a gap of
 * {@link #GAP_CHUNKS} chunks. Lanes are 2048 blocks apart, so modules never see each other's builds.
 *
 * <p>Main thread only.
 */
public final class RegionAllocator {

    /** Distance between the starts of two lanes, in chunks (2048 blocks). */
    public static final int LANE_SPACING_CHUNKS = 128;
    /** Maximum region width (X) inside a lane, in chunks. */
    public static final int LANE_WIDTH_CHUNKS = 96;
    /** Maximum total lane length (Z), in chunks. */
    public static final int LANE_LENGTH_CHUNKS = 1024;
    /** Chunk X of lane 0 (keeps the world spawn at 0,0 free). */
    public static final int LANE_ORIGIN_CHUNK_X = 8;
    /** Chunk Z where every lane starts. */
    public static final int LANE_ORIGIN_CHUNK_Z = 8;
    /** Empty chunks between two regions of the same lane. */
    public static final int GAP_CHUNKS = 2;
    /** Default region height in blocks. */
    public static final int DEFAULT_HEIGHT = 64;

    private final String owner;
    private final int lane;
    private final int baseY;
    private final int worldMaxY;
    private final List<Region> allocated = new ArrayList<>();
    private int cursorZ;

    public RegionAllocator(String owner, int lane, int baseY, int worldMaxY) {
        this.owner = owner;
        this.lane = lane;
        this.baseY = baseY;
        this.worldMaxY = worldMaxY;
        this.cursorZ = LANE_ORIGIN_CHUNK_Z;
    }

    /** Allocates the next region of {@code chunksX x chunksZ} chunks, {@link #DEFAULT_HEIGHT} blocks high. */
    public Region allocate(String name, int chunksX, int chunksZ) {
        return this.allocate(name, chunksX, chunksZ, DEFAULT_HEIGHT);
    }

    /** Allocates the next region of {@code chunksX x chunksZ} chunks starting at {@link #baseY()}. */
    public Region allocate(String name, int chunksX, int chunksZ, int height) {
        if (chunksX < 1 || chunksX > LANE_WIDTH_CHUNKS) {
            throw new IllegalArgumentException("chunksX must be 1.." + LANE_WIDTH_CHUNKS + ": " + chunksX);
        }
        if (chunksZ < 1 || this.cursorZ + chunksZ > LANE_ORIGIN_CHUNK_Z + LANE_LENGTH_CHUNKS) {
            throw new IllegalArgumentException("lane of '" + this.owner + "' is full or chunksZ invalid: " + chunksZ);
        }
        if (height < 1) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        final int minChunkX = LANE_ORIGIN_CHUNK_X + this.lane * LANE_SPACING_CHUNKS;
        final int maxY = Math.min(this.worldMaxY - 1, this.baseY + height - 1);
        final Region region = new Region(this.owner, name, minChunkX, this.cursorZ, chunksX, chunksZ, this.baseY, maxY);
        this.cursorZ += chunksZ + GAP_CHUNKS;
        this.allocated.add(region);
        return region;
    }

    /** Regions allocated so far, in allocation order. */
    public List<Region> allocated() {
        return Collections.unmodifiableList(this.allocated);
    }

    public int lane() {
        return this.lane;
    }

    public int baseY() {
        return this.baseY;
    }

    public String owner() {
        return this.owner;
    }
}

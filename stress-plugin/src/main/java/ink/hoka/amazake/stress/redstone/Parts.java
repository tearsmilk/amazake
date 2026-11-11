package ink.hoka.amazake.stress.redstone;

import ink.hoka.amazake.stress.BlockWriter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Dispenser;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.block.data.type.Observer;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.block.data.type.RedstoneWallTorch;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Repeater;

/**
 * Typed {@link BlockData} factory for the redstone module. Every value is built from the material's default
 * data through the typed Bukkit interfaces and cached (lookups only, never iterated), so the same
 * instance is shared by all placements of the same state. Instances returned here must not be mutated.
 *
 * <p>Orientation conventions (vanilla semantics):
 * <ul>
 *   <li>Observer {@code facing}: the face looks at {@code facing}; the pulse is emitted from the back
 *       ({@code facing.getOppositeFace()}).</li>
 *   <li>Repeater / comparator {@code facing}: the side the INPUT comes from; output goes to the opposite side.</li>
 *   <li>Hopper / dropper / piston {@code facing}: the output (push) direction.</li>
 *   <li>Wall torch {@code facing}: pointing away from the block it hangs on.</li>
 * </ul>
 */
final class Parts {

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final BlockWriter blocks;
    private final Map<String, BlockData> cache = new HashMap<>();

    Parts(BlockWriter blocks) {
        this.blocks = blocks;
    }

    BlockData plain(Material material) {
        return this.blocks.data(material);
    }

    BlockData air() {
        return this.blocks.data(Material.AIR);
    }

    /** Unlit redstone lamp. */
    BlockData lamp() {
        return this.cache.computeIfAbsent("lamp", k -> {
            final Lightable lamp = (Lightable) this.blocks.data(Material.REDSTONE_LAMP).clone();
            lamp.setLit(false);
            return lamp;
        });
    }

    BlockData observer(BlockFace facing) {
        return this.cache.computeIfAbsent("observer:" + facing, k -> {
            final Observer observer = (Observer) this.blocks.data(Material.OBSERVER).clone();
            observer.setFacing(facing);
            observer.setPowered(false);
            return observer;
        });
    }

    /** Repeater whose input is on the {@code inputSide}; the delay is clamped to the allowed range. */
    BlockData repeater(BlockFace inputSide, int delay) {
        return this.cache.computeIfAbsent("repeater:" + inputSide + ":" + delay, k -> {
            final Repeater repeater = (Repeater) this.blocks.data(Material.REPEATER).clone();
            repeater.setFacing(inputSide);
            repeater.setDelay(Math.max(repeater.getMinimumDelay(), Math.min(repeater.getMaximumDelay(), delay)));
            repeater.setLocked(false);
            repeater.setPowered(false);
            return repeater;
        });
    }

    /** Comparator (compare mode) reading the container on {@code inputSide}. */
    BlockData comparator(BlockFace inputSide) {
        return this.cache.computeIfAbsent("comparator:" + inputSide, k -> {
            final Comparator comparator = (Comparator) this.blocks.data(Material.COMPARATOR).clone();
            comparator.setFacing(inputSide);
            comparator.setMode(Comparator.Mode.COMPARE);
            comparator.setPowered(false);
            return comparator;
        });
    }

    /** Unpowered redstone dust connected (SIDE) to the given horizontal faces, NONE elsewhere. */
    BlockData wire(BlockFace... connected) {
        final Set<BlockFace> set = EnumSet.noneOf(BlockFace.class);
        final StringBuilder key = new StringBuilder("wire");
        for (final BlockFace face : connected) {
            set.add(face);
        }
        for (final BlockFace face : HORIZONTAL) {
            key.append(set.contains(face) ? '1' : '0');
        }
        return this.cache.computeIfAbsent(key.toString(), k -> {
            final RedstoneWire wire = (RedstoneWire) this.blocks.data(Material.REDSTONE_WIRE).clone();
            for (final BlockFace face : HORIZONTAL) {
                wire.setFace(face, set.contains(face) ? RedstoneWire.Connection.SIDE : RedstoneWire.Connection.NONE);
            }
            wire.setPower(0);
            return wire;
        });
    }

    /** Lit wall torch hanging on the block behind it ({@code facing} points away from that block). */
    BlockData wallTorch(BlockFace facing) {
        return this.cache.computeIfAbsent("torch:" + facing, k -> {
            final RedstoneWallTorch torch = (RedstoneWallTorch) this.blocks.data(Material.REDSTONE_WALL_TORCH).clone();
            torch.setFacing(facing);
            torch.setLit(true);
            return torch;
        });
    }

    BlockData stickyPiston(BlockFace facing) {
        return this.cache.computeIfAbsent("piston:" + facing, k -> {
            final Piston piston = (Piston) this.blocks.data(Material.STICKY_PISTON).clone();
            piston.setFacing(facing);
            piston.setExtended(false);
            return piston;
        });
    }

    BlockData hopper(BlockFace output) {
        return this.cache.computeIfAbsent("hopper:" + output, k -> {
            final Hopper hopper = (Hopper) this.blocks.data(Material.HOPPER).clone();
            hopper.setFacing(output);
            hopper.setEnabled(true);
            return hopper;
        });
    }

    BlockData dropper(BlockFace output) {
        return this.cache.computeIfAbsent("dropper:" + output, k -> {
            final Dispenser dropper = (Dispenser) this.blocks.data(Material.DROPPER).clone();
            dropper.setFacing(output);
            dropper.setTriggered(false);
            return dropper;
        });
    }

    BlockData rail(Rail.Shape shape) {
        return this.cache.computeIfAbsent("rail:" + shape, k -> {
            final Rail rail = (Rail) this.blocks.data(Material.RAIL).clone();
            rail.setShape(shape);
            return rail;
        });
    }

    /** Powered rail (straight shapes only), stored as powered; a redstone block below keeps it powered. */
    BlockData poweredRail(Rail.Shape shape) {
        return this.cache.computeIfAbsent("prail:" + shape, k -> {
            final RedstoneRail rail = (RedstoneRail) this.blocks.data(Material.POWERED_RAIL).clone();
            rail.setShape(shape);
            rail.setPowered(true);
            return rail;
        });
    }
}

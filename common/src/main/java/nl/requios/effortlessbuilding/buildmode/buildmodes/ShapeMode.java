package nl.requios.effortlessbuilding.buildmode.buildmodes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import nl.requios.effortlessbuilding.buildmode.BaseBuildMode;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.shape.SchematicLibrary;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeGenerator;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.shape.ShapeMaterials;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/**
 * Builds the shape chosen in the Shape Generator screen ({@link ShapeClientState}).
 * <ul>
 *   <li>Screen sizing: one click places the shape, standing on the clicked block.</li>
 *   <li>Click sizing: the first click sets the center, the second sets the radius
 *       (horizontal distance from the center); the design scales to it.</li>
 * </ul>
 * The server regenerates the shape from the packet's {@link ShapeParams} and click positions.
 */
public class ShapeMode extends BaseBuildMode {

    private BlockPos center;

    /** Last generated cells, reused while nothing changes (the preview asks every frame). */
    private ShapeParams cachedParams;
    private int cachedAxis;
    private Built cachedBuilt = new Built(List.of(), List.of());

    @Override
    public void initialize() {
        super.initialize();
        center = null;
    }

    @Override
    public boolean onClick(BlockSet blocks, BlockPos clickedPos, Player player) {
        super.onClick(blocks, clickedPos, player);
        if (clicks == 1) {
            center = clickedPos;
            return ShapeClientState.getActive().sizing() == ShapeParams.Sizing.SCREEN;
        }
        return true;
    }

    @Override
    public void findCoordinates(BlockSet blocks, Player player) {
        if (clicks == 0 || center == null) return;
        ShapeParams params = ShapeClientState.getActive();
        BlockPos edge = params.sizing() == ShapeParams.Sizing.CLICKS
                ? Floor.findFloor(player, center, true)
                : center;
        if (edge == null) edge = center;
        fill(blocks, player, center, edge, params);
    }

    /** Preview before the first click: the shape at the targeted block, at the screen's size. */
    public void previewAt(BlockSet blocks, Player player, BlockPos target) {
        fill(blocks, player, target, target, ShapeClientState.getActive().withSizing(ShapeParams.Sizing.SCREEN));
    }

    private void fill(BlockSet blocks, Player player, BlockPos anchor, BlockPos edge, ShapeParams params) {
        blocks.clear();
        for (BlockPos pos : positions(player, anchor, edge, params)) {
            blocks.add(new BlockEntry(pos));
        }
        // firstPos is the anchor so the packet carries it; lastPos the radius point
        blocks.firstPos = anchor;
        blocks.lastPos = edge;
        assignItems(blocks, player, anchor, edge, params);
    }

    /**
     * Gives positions their own block: a schematic's saved blocks (exact states, so stairs keep their
     * facing), then the center axle. Saved blocks that have no item (water, portals) are left out.
     */
    @Override
    public void assignItems(BlockSet blocks, Player player, BlockPos firstPos, BlockPos secondPos, @Nullable ShapeParams shape) {
        if (shape == null) return;
        ShapeMaterials.of(shape).forEach((c, state) -> {
            BlockPos pos = firstPos.offset(c.x(), c.y(), c.z());
            BlockEntry entry = blocks.get(pos);
            if (entry == null) return;
            if (!(state.getBlock().asItem() instanceof BlockItem item)) {
                blocks.remove(pos);
                return;
            }
            entry.item = item;
            entry.blockState = state;
            entry.exactState = true;
        });

        if (shape.centerBlock().isEmpty()) return;
        ResourceLocation id = ResourceLocation.tryParse(shape.centerBlock());
        if (id == null || !(BuiltInRegistries.ITEM.get(id) instanceof BlockItem blockItem)) return;
        for (Cell c : build(player, firstPos, secondPos, shape).axle()) {
            BlockEntry entry = blocks.get(firstPos.offset(c.x(), c.y(), c.z()));
            if (entry == null) continue;
            entry.item = blockItem;
            entry.blockState = blockItem.getBlock().defaultBlockState();
            entry.exactState = false;
        }
    }

    /** Anchor goes out as firstPos, the radius point as secondPos. */
    @Override
    public List<BlockPos> getServerBlocks(Player player, BlockPos firstPos, BlockPos secondPos,
                                         @Nullable BlockPos thirdPos, @Nullable ShapeParams shape) {
        if (shape == null) return List.of();
        return positions(player, firstPos, secondPos, shape);
    }

    private List<BlockPos> positions(Player player, BlockPos anchor, BlockPos edge, ShapeParams params) {
        Built built = build(player, anchor, edge, params);
        Set<Cell> all = new LinkedHashSet<>(built.cells());
        all.addAll(built.axle()); // the axle fills the middle even where the shape is open
        List<BlockPos> out = new ArrayList<>(all.size());
        for (Cell c : all) out.add(anchor.offset(c.x(), c.y(), c.z()));
        return out;
    }

    /** The shape's cells and its center axle (empty without a center block), at the clicked size. */
    private record Built(List<Cell> cells, List<Cell> axle) {}

    private Built build(Player player, BlockPos anchor, BlockPos edge, ShapeParams params) {
        if (params.sizing() == ShapeParams.Sizing.CLICKS) {
            int radius = (int) Math.round(Math.hypot(edge.getX() - anchor.getX(), edge.getZ() - anchor.getZ()));
            params = params.scaledTo(Math.max(1, radius));
        }
        return cells(params, ServerConfig.INSTANCE.getMaxBlocksPerAxis(player));
    }

    private synchronized Built cells(ShapeParams params, int maxAxis) {
        if (!Objects.equals(params, cachedParams) || maxAxis != cachedAxis) {
            List<Cell> cells = ShapeGenerator.generate(params, maxAxis, SchematicLibrary::cells);
            List<Cell> axle = params.centerBlock().isEmpty() ? List.of() : ShapeGenerator.centerAxis(params.orientation(), cells);
            cachedBuilt = new Built(cells, axle);
            cachedParams = params;
            cachedAxis = maxAxis;
        }
        return cachedBuilt;
    }
}

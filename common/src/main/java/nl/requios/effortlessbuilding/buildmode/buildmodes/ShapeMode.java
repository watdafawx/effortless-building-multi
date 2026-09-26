package nl.requios.effortlessbuilding.buildmode.buildmodes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import nl.requios.effortlessbuilding.buildmode.BaseBuildMode;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.shape.SchematicLibrary;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeGenerator;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    private List<Cell> cachedCells = List.of();

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
    }

    /** Anchor goes out as firstPos, the radius point as secondPos. */
    @Override
    public List<BlockPos> getServerBlocks(Player player, BlockPos firstPos, BlockPos secondPos,
                                         @Nullable BlockPos thirdPos, @Nullable ShapeParams shape) {
        if (shape == null) return List.of();
        return positions(player, firstPos, secondPos, shape);
    }

    private List<BlockPos> positions(Player player, BlockPos anchor, BlockPos edge, ShapeParams params) {
        if (params.sizing() == ShapeParams.Sizing.CLICKS) {
            int radius = (int) Math.round(Math.hypot(edge.getX() - anchor.getX(), edge.getZ() - anchor.getZ()));
            params = params.scaledTo(Math.max(1, radius));
        }
        List<Cell> cells = cells(params, ServerConfig.INSTANCE.getMaxBlocksPerAxis(player));
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(anchor.offset(c.x(), c.y(), c.z()));
        return out;
    }

    private synchronized List<Cell> cells(ShapeParams params, int maxAxis) {
        if (!Objects.equals(params, cachedParams) || maxAxis != cachedAxis) {
            cachedCells = switch (params.type()) {
                case SCHEMATIC -> SchematicLibrary.cells(params.schematic(), maxAxis);
                default -> ShapeGenerator.generate(params, maxAxis);
            };
            cachedParams = params;
            cachedAxis = maxAxis;
        }
        return cachedCells;
    }
}

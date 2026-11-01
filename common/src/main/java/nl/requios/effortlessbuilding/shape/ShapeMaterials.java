package nl.requios.effortlessbuilding.shape;

import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.util.HashMap;
import java.util.Map;

/**
 * The blocks schematics were saved with, placed where they end up in the finished shape (offsets
 * relative to the anchor, like {@link ShapeGenerator}'s cells). Only schematics set to use their saved
 * blocks count, and only when not turned by the free rotation angles (a turned block would face the wrong
 * way); those build with the held block (or the palette) instead.
 */
public final class ShapeMaterials {

    public static final String USE_SAVED_BLOCKS = "schematic_blocks";

    private ShapeMaterials() {}

    public static Map<Cell, BlockState> of(ShapeParams p) {
        if (rotated(p)) return Map.of();
        Map<Cell, BlockState> out = new HashMap<>();
        if (usesSavedBlocks(p)) out.putAll(SchematicLibrary.states(p.schematic()));
        for (ShapeParams.Part part : p.parts()) {
            ShapeParams s = part.shape();
            if (!usesSavedBlocks(s) || rotated(s)) continue;
            SchematicLibrary.states(s.schematic()).forEach((c, state) ->
                    out.put(new Cell(c.x() + part.x(), c.y() + part.y(), c.z() + part.z()), state));
        }
        return out;
    }

    private static boolean usesSavedBlocks(ShapeParams p) {
        return p.type() == ShapeType.SCHEMATIC && p.getInt(USE_SAVED_BLOCKS) == 1;
    }

    private static boolean rotated(ShapeParams p) {
        return p.get(ShapeType.ROTATE_X) % 360 != 0 || p.get(ShapeType.ROTATE_Y) % 360 != 0 || p.get(ShapeType.ROTATE_Z) % 360 != 0;
    }
}

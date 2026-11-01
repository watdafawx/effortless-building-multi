package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.GlueBoxes.Box;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlueBoxesTest {

    /** Nothing outside the shape is inside a box, and every touching pair of blocks shares a box. */
    private static void assertTightAndConnected(Set<Cell> shape, List<Box> boxes) {
        for (Box b : boxes)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int y = b.minY(); y <= b.maxY(); y++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++)
                        assertTrue(shape.contains(new Cell(x, y, z)), "box " + b + " covers a non-shape block at " + x + "," + y + "," + z);
        int[][] dirs = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (Cell c : shape)
            for (int[] d : dirs) {
                Cell n = new Cell(c.x() + d[0], c.y() + d[1], c.z() + d[2]);
                if (!shape.contains(n)) continue;
                boolean shared = boxes.stream().anyMatch(b -> b.contains(c.x(), c.y(), c.z()) && b.contains(n.x(), n.y(), n.z()));
                assertTrue(shared, "touching blocks " + c + " and " + n + " are not glued together");
            }
    }

    @ParameterizedTest
    @EnumSource(value = ShapeType.class, names = "SCHEMATIC", mode = EnumSource.Mode.EXCLUDE)
    void everyShapeIsGluedTightly(ShapeType type) {
        Set<Cell> shape = new HashSet<>(ShapeGenerator.generate(ShapeParams.defaults(type), 1000));
        assertTightAndConnected(shape, GlueBoxes.cover(shape));
    }

    @Test
    void hollowAndRotatedShapesToo() {
        Set<Cell> ring = new HashSet<>(ShapeGenerator.generate(ShapeParams.defaults(ShapeType.GEAR).withHollow(true), 1000));
        assertTightAndConnected(ring, GlueBoxes.cover(ring));
        Set<Cell> tilted = new HashSet<>(ShapeGenerator.generate(
                ShapeParams.defaults(ShapeType.TORUS).with(ShapeType.ROTATE_X, 30), 1000));
        assertTightAndConnected(tilted, GlueBoxes.cover(tilted));
    }

    @Test
    void solidBoxIsOneGlueBox() {
        Set<Cell> cube = new HashSet<>();
        for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) cube.add(new Cell(x, y, z));
        assertEquals(List.of(new Box(0, 0, 0, 4, 4, 4)), GlueBoxes.cover(cube));
    }

    @Test
    void longRunsAreSplitAtTheHandGlueLimit() {
        Set<Cell> bar = new HashSet<>();
        for (int x = 0; x < 60; x++) bar.add(new Cell(x, 0, 0));
        List<Box> boxes = GlueBoxes.cover(bar);
        assertTrue(boxes.stream().allMatch(b -> b.maxX() - b.minX() + 1 <= GlueBoxes.MAX_SPAN));
        assertTightAndConnected(bar, boxes);
    }
}

package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BasicSolidsTest {

    private static List<Cell> cells(ShapeParams p) {
        return ShapeGenerator.generate(p, 256);
    }

    private static int[] bounds(List<Cell> cells) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { b[a] = Math.min(b[a], v[a]); b[a + 3] = Math.max(b[a + 3], v[a]); }
        }
        return b;
    }

    @Test
    void boxIsAFullCuboid() {
        List<Cell> box = cells(ShapeParams.defaults(ShapeType.BOX).withSize(2).with("length", 3).with("height", 4));
        assertEquals(5 * 7 * 4, box.size());
        int[] b = bounds(box);
        assertEquals(0, b[1]);
        assertEquals(3, b[4]);
    }

    @Test
    void sphereIsSymmetricAndStandsOnTheGround() {
        List<Cell> sphere = cells(ShapeParams.defaults(ShapeType.SPHERE).withSize(5));
        int[] b = bounds(sphere);
        assertEquals(0, b[1]);
        assertEquals(10, b[4]);
        Set<Cell> set = Set.copyOf(sphere);
        for (Cell c : sphere) assertTrue(set.contains(new Cell(-c.x(), c.y(), -c.z())));
    }

    @Test
    void hollowSphereKeepsAShell() {
        ShapeParams solid = ShapeParams.defaults(ShapeType.SPHERE).withSize(5);
        assertTrue(cells(solid.withHollow(true)).size() < cells(solid).size());
    }

    @Test
    void cylinderHasTheGivenHeightAndARoundFootprint() {
        List<Cell> cylinder = cells(ShapeParams.defaults(ShapeType.CYLINDER).withSize(4).with("height", 7));
        int[] b = bounds(cylinder);
        assertEquals(6, b[4]);
        assertTrue(cylinder.stream().noneMatch(c -> Math.hypot(c.x(), c.z()) > 4.5));
        assertTrue(cylinder.contains(new Cell(4, 3, 0)));
    }

    @Test
    void wedgeSlopesDown() {
        List<Cell> wedge = cells(ShapeParams.defaults(ShapeType.WEDGE).withSize(1).with("length", 4).with("height", 6));
        int[] b = bounds(wedge);
        assertEquals(5, b[4]);
        // The tall side (-v is -z after orienting) has more blocks in its column than the low side
        long tall = wedge.stream().filter(c -> c.x() == 0 && c.z() == b[2]).count();
        long low = wedge.stream().filter(c -> c.x() == 0 && c.z() == b[5]).count();
        assertTrue(tall > low && low >= 1);
    }

    @Test
    void capsuleHasRoundedEnds() {
        List<Cell> capsule = cells(ShapeParams.defaults(ShapeType.CAPSULE).withSize(3).with("height", 12));
        int[] b = bounds(capsule);
        assertEquals(11, b[4]);
        long bottom = capsule.stream().filter(c -> c.y() == 0).count();
        long middle = capsule.stream().filter(c -> c.y() == 6).count();
        assertTrue(bottom < middle);
    }

    @Test
    void octahedronIsDiamondShaped() {
        List<Cell> octa = cells(ShapeParams.defaults(ShapeType.OCTAHEDRON).withSize(3));
        assertEquals(63, octa.size(), "taxicab ball of radius 3");
    }

    @Test
    void anchorDefaultsToBaseCenter() {
        List<Cell> cells = cells(ShapeParams.defaults(ShapeType.BOX).withSize(2).with("length", 2).with("height", 3));
        int[] b = bounds(cells);
        assertArrayEquals(new int[]{-2, 0, -2, 2, 2, 2}, b);
    }

    @Test
    void anchorModesMoveTheShape() {
        ShapeParams box = ShapeParams.defaults(ShapeType.BOX).withSize(2).with("length", 2).with("height", 5);
        assertArrayEquals(new int[]{-2, -2, -2, 2, 2, 2}, bounds(cells(box.with(ShapeType.ANCHOR, 1))));
        assertArrayEquals(new int[]{-2, -4, -2, 2, 0, 2}, bounds(cells(box.with(ShapeType.ANCHOR, 2))));
        assertArrayEquals(new int[]{0, 0, 0, 4, 4, 4}, bounds(cells(box.with(ShapeType.ANCHOR, 3))));
    }

    @Test
    void anchorShiftMovesTheShapeTheOtherWay() {
        ShapeParams box = ShapeParams.defaults(ShapeType.BOX).withSize(1).with("length", 1).with("height", 3)
                .with(ShapeType.ANCHOR_X, 2).with(ShapeType.ANCHOR_Y, -1).with(ShapeType.ANCHOR_Z, 3);
        assertArrayEquals(new int[]{-3, 1, -4, -1, 3, -2}, bounds(cells(box)));
    }

    @Test
    void shapeKeepsItsBlockCountWhenTheAnchorMoves() {
        ShapeParams star = ShapeParams.defaults(ShapeType.STAR);
        assertEquals(cells(star).size(), cells(star.with(ShapeType.ANCHOR, 1).with(ShapeType.ANCHOR_Y, 3)).size());
    }
}

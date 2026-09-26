package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShapeGeneratorTest {

    private static final int NO_LIMIT = 10_000;

    private static List<Cell> gen(ShapeParams p) {
        return ShapeGenerator.generate(p, NO_LIMIT);
    }

    // Block totals from the Cogwheel Planner web page for the same settings
    @Test
    void gearMatchesPlanner() {
        ShapeParams gear = ShapeParams.defaults(ShapeType.GEAR);
        assertEquals(1067, gen(gear).size());
        assertEquals(568, gen(gear.withHollow(true)).size());
    }

    @Test
    void gearMatchesPlannerAtDoubleSize() {
        ShapeParams gear = ShapeParams.defaults(ShapeType.GEAR).withSize(20)
                .with("tooth_length", 6).with("tooth_width", 5)
                .with("frame_size", 14).with("frame_width", 4).with("inner_size", 8).with("inner_width", 2)
                .with("hub_size", 2).with("body_height", 4).with("frame_height", 6).with("inner_height", 6)
                .with("hub_height", 6).with("tooth_height", 4);
        assertEquals(8102, gen(gear).size());
    }

    @ParameterizedTest
    @EnumSource(value = ShapeType.class, names = "SCHEMATIC", mode = EnumSource.Mode.EXCLUDE)
    void everyShapeStandsOnTheAnchorWithoutDuplicates(ShapeType type) {
        for (ShapeParams.Orientation o : ShapeParams.Orientation.values()) {
            List<Cell> cells = gen(ShapeParams.defaults(type).withOrientation(o));
            assertFalse(cells.isEmpty(), type + " " + o);
            assertEquals(0, cells.stream().mapToInt(Cell::y).min().orElseThrow(), type + " " + o + " lowest y");
            assertEquals(cells.size(), new HashSet<>(cells).size(), type + " " + o + " duplicates");
        }
    }

    @ParameterizedTest
    @EnumSource(value = ShapeType.class, names = "SCHEMATIC", mode = EnumSource.Mode.EXCLUDE)
    void orientationKeepsBlockCount(ShapeType type) {
        ShapeParams p = ShapeParams.defaults(type);
        int flat = gen(p.withOrientation(ShapeParams.Orientation.FLAT)).size();
        assertEquals(flat, gen(p.withOrientation(ShapeParams.Orientation.UPRIGHT_NS)).size(), type.name());
        assertEquals(flat, gen(p.withOrientation(ShapeParams.Orientation.UPRIGHT_EW)).size(), type.name());
    }

    @ParameterizedTest
    @EnumSource(value = ShapeType.class, names = {"TORUS", "ELLIPSOID", "CONE", "PRISM", "STAR", "GEAR"})
    void hollowIsASubsetWithFewerBlocks(ShapeType type) {
        ShapeParams p = ShapeParams.defaults(type);
        Set<Cell> solid = new HashSet<>(gen(p));
        List<Cell> hollow = gen(p.withHollow(true));
        assertTrue(hollow.size() < solid.size(), type.name());
        assertTrue(solid.containsAll(hollow), type.name());
    }

    @Test
    void roundShapesAreMirrorSymmetric() {
        for (ShapeType type : List.of(ShapeType.TORUS, ShapeType.ELLIPSOID, ShapeType.CONE)) {
            Set<Cell> cells = new HashSet<>(gen(ShapeParams.defaults(type)));
            for (Cell c : cells) {
                assertTrue(cells.contains(new Cell(-c.x(), c.y(), c.z())), type + " x mirror of " + c);
                assertTrue(cells.contains(new Cell(c.x(), c.y(), -c.z())), type + " z mirror of " + c);
            }
        }
    }

    @Test
    void sphereLikeEllipsoidHasExpectedSpan() {
        ShapeParams sphere = ShapeParams.defaults(ShapeType.ELLIPSOID).withSize(5).with("radius_y", 5).with("radius_z", 5);
        List<Cell> cells = gen(sphere);
        assertEquals(10, cells.stream().mapToInt(Cell::y).max().orElseThrow());
        assertEquals(5, cells.stream().mapToInt(Cell::x).max().orElseThrow());
    }

    @Test
    void helixClimbsToFullHeight() {
        ShapeParams helix = ShapeParams.defaults(ShapeType.HELIX);
        List<Cell> cells = gen(helix);
        assertEquals(helix.getInt("height") - 1, cells.stream().mapToInt(Cell::y).max().orElseThrow());
        // Every layer has some step on it
        assertEquals(helix.getInt("height"), cells.stream().mapToInt(Cell::y).distinct().count());
    }

    @Test
    void archStandsUprightWithAnOpening() {
        ShapeParams arch = ShapeParams.defaults(ShapeType.ARCH);
        Set<Cell> cells = new HashSet<>(gen(arch));
        assertFalse(cells.contains(new Cell(0, 0, 0)), "doorway at the base center is open");
        assertTrue(cells.contains(new Cell(arch.size(), 0, 0)), "arch foot");
        assertTrue(cells.contains(new Cell(0, arch.size(), 0)), "arch crown");
    }

    @Test
    void polygonHasItsSideCount() {
        // A square prism of circumradius 8 with a flat side facing north is a square
        ShapeParams square = ShapeParams.defaults(ShapeType.PRISM).with("sides", 4).with("rotation", 0.5).with("height", 1);
        List<Cell> cells = gen(square);
        int minX = cells.stream().mapToInt(Cell::x).min().orElseThrow(), maxX = cells.stream().mapToInt(Cell::x).max().orElseThrow();
        int minZ = cells.stream().mapToInt(Cell::z).min().orElseThrow(), maxZ = cells.stream().mapToInt(Cell::z).max().orElseThrow();
        assertEquals((maxX - minX + 1) * (maxZ - minZ + 1), cells.size(), "filled rectangle");
    }

    @Test
    void axisLimitClipsLargeShapes() {
        ShapeParams big = ShapeParams.defaults(ShapeType.GEAR).withSize(60);
        List<Cell> cells = ShapeGenerator.generate(big, 64);
        assertTrue(cells.stream().allMatch(c -> Math.abs(c.x()) <= 31 && Math.abs(c.z()) <= 31 && c.y() < 64));
        assertFalse(cells.isEmpty());
    }

    @Test
    void scalingDoublesLengthsButKeepsCounts() {
        ShapeParams gear = ShapeParams.defaults(ShapeType.GEAR).scaledTo(20);
        assertEquals(20, gear.size());
        assertEquals(16, gear.getInt("teeth"));
        assertEquals(6, gear.getInt("tooth_length"));
        assertEquals(14, gear.getInt("frame_size"));
        assertEquals(0.5, gear.get("tooth_rotation"));
    }

    @Test
    void paramsAreClampedToTheirRange() {
        ShapeParams p = ShapeParams.defaults(ShapeType.PRISM).with("sides", 1).with("height", 2.4);
        assertEquals(3, p.getInt("sides"));
        assertEquals(2.0, p.get("height"));
        assertFalse(ShapeParams.defaults(ShapeType.ARCH).withHollow(true).hollow(), "arch has no hollow form");
    }
}

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
    @EnumSource(value = ShapeType.class, names = {"TORUS", "ELLIPSOID", "CONE", "PRISM", "STAR", "GEAR", "PYRAMID"})
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
    void helixWindsAroundAFullHeightPole() {
        ShapeParams helix = ShapeParams.defaults(ShapeType.HELIX);
        Set<Cell> cells = new HashSet<>(gen(helix));
        for (int y = 0; y < helix.getInt("height"); y++) {
            assertTrue(cells.contains(new Cell(0, y, 0)), "pole at y " + y);
        }
        Set<Cell> noPole = new HashSet<>(gen(helix.with("pole_radius", 0)));
        assertFalse(noPole.contains(new Cell(0, 5, 0)), "no pole when its radius is 0");
    }

    @Test
    void spiralArmsAreUnbroken() {
        // Every arm block touches another arm block, so the path can be walked
        Set<Cell> cells = new HashSet<>(gen(ShapeParams.defaults(ShapeType.SPIRAL)));
        for (Cell c : cells) {
            boolean touches = false;
            for (int dx = -1; dx <= 1 && !touches; dx++)
                for (int dz = -1; dz <= 1 && !touches; dz++)
                    touches = (dx != 0 || dz != 0) && cells.contains(new Cell(c.x() + dx, c.y(), c.z() + dz));
            assertTrue(touches, "isolated block " + c);
        }
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
    void pyramidNarrowsToAPoint() {
        ShapeParams pyramid = ShapeParams.defaults(ShapeType.PYRAMID);
        List<Cell> cells = gen(pyramid);
        int top = pyramid.getInt("height") - 1;
        assertEquals(List.of(new Cell(0, top, 0)), cells.stream().filter(c -> c.y() == top).toList());
        assertEquals((2 * pyramid.size() + 1) * (2 * pyramid.size() + 1), cells.stream().filter(c -> c.y() == 0).count());
    }

    @Test
    void towerHasBattlementsAboveTheWall() {
        ShapeParams tower = ShapeParams.defaults(ShapeType.TOWER);
        List<Cell> cells = gen(tower);
        int wallTop = tower.getInt("height");
        long merlonBlocks = cells.stream().filter(c -> c.y() == wallTop).count();
        long wallRing = cells.stream().filter(c -> c.y() == 0).count();
        assertTrue(merlonBlocks > wallRing / 4 && merlonBlocks < wallRing * 3 / 4, "about half the ring has merlons");
        assertFalse(new HashSet<>(cells).contains(new Cell(0, 0, 0)), "tower is open inside");
    }

    @Test
    void wheelHasHubRimAndGaps() {
        ShapeParams wheel = ShapeParams.defaults(ShapeType.WHEEL).withOrientation(ShapeParams.Orientation.FLAT);
        Set<Cell> cells = new HashSet<>(gen(wheel));
        assertTrue(cells.contains(new Cell(0, 0, 0)), "hub");
        assertTrue(cells.contains(new Cell(wheel.size(), 0, 0)), "rim");
        int filledInside = 0, inside = 0;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            if (Math.hypot(x, z) < 4 || Math.hypot(x, z) > 6) continue;
            inside++;
            if (cells.contains(new Cell(x, 0, z))) filledInside++;
        }
        assertTrue(filledInside > 0 && filledInside < inside, "spokes with gaps between them");
    }

    @Test
    void bowlRimIsHigherThanItsCenter() {
        ShapeParams bowl = ShapeParams.defaults(ShapeType.BOWL);
        Set<Cell> cells = new HashSet<>(gen(bowl));
        assertTrue(cells.contains(new Cell(0, 0, 0)), "bottom center");
        assertTrue(cells.contains(new Cell(bowl.size(), bowl.getInt("depth"), 0)), "rim at full depth");
        assertFalse(cells.contains(new Cell(0, bowl.getInt("depth"), 0)), "open above the center");
    }

    @Test
    void spiralArmsLeaveGapsBetweenThem() {
        ShapeParams spiral = ShapeParams.defaults(ShapeType.SPIRAL);
        long cells = gen(spiral).size();
        long disc = gen(ShapeParams.defaults(ShapeType.PRISM).with("sides", 64).withSize(spiral.size()).with("height", 1)).size();
        assertTrue(cells > disc / 8 && cells < disc * 3 / 4, "spiral covers part of its disc: " + cells + " of " + disc);
    }

    // ---- combining parts ----------------------------------------------------

    private static ShapeParams box(int half, int height) {
        return ShapeParams.defaults(ShapeType.PRISM).with("sides", 4).with("rotation", 0.5)
                .withSize(half).with("height", height);
    }

    private static ShapeParams combine(ShapeParams.Operation op, int dx) {
        return box(3, 1).withParts(List.of(new ShapeParams.Part(box(3, 1), op, dx, 0, 0)));
    }

    @Test
    void uniteSubtractIntersectExclude() {
        int square = gen(box(3, 1)).size();
        int side = (int) Math.round(Math.sqrt(square));
        int overlap = (side - 2) * side; // two squares shifted 2 blocks overlap in side-2 columns
        assertEquals(2 * square - overlap, gen(combine(ShapeParams.Operation.UNITE, 2)).size());
        assertEquals(square - overlap, gen(combine(ShapeParams.Operation.SUBTRACT, 2)).size());
        assertEquals(overlap, gen(combine(ShapeParams.Operation.INTERSECT, 2)).size());
        assertEquals(2 * (square - overlap), gen(combine(ShapeParams.Operation.EXCLUDE, 2)).size());
    }

    @Test
    void subtractingASmallerCylinderHollowsATube() {
        ShapeParams tube = ShapeParams.defaults(ShapeType.CONE).with("sides", 0).with("height", 256).withSize(6)
                .withParts(List.of(new ShapeParams.Part(
                        ShapeParams.defaults(ShapeType.CONE).with("sides", 0).with("height", 256).withSize(4),
                        ShapeParams.Operation.SUBTRACT, 0, 0, 0)));
        Set<Cell> cells = new HashSet<>(gen(tube));
        assertFalse(cells.contains(new Cell(0, 0, 0)), "center cut out");
        assertTrue(cells.contains(new Cell(6, 0, 0)), "outer wall kept");
    }

    @Test
    void partsScaleWithTheShape() {
        ShapeParams p = combine(ShapeParams.Operation.UNITE, 2).scaledTo(6);
        assertEquals(6, p.size());
        assertEquals(6, p.parts().getFirst().shape().size());
        assertEquals(4, p.parts().getFirst().x());
    }

    @Test
    void partsCannotNest() {
        ShapeParams inner = combine(ShapeParams.Operation.UNITE, 1);
        ShapeParams outer = box(2, 1).withParts(List.of(new ShapeParams.Part(inner, ShapeParams.Operation.UNITE, 0, 0, 0)));
        assertTrue(outer.parts().getFirst().shape().parts().isEmpty());
    }

    // ---- rotation -------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ShapeType.class, names = "SCHEMATIC", mode = EnumSource.Mode.EXCLUDE)
    void quarterTurnsKeepEveryBlock(ShapeType type) {
        ShapeParams p = ShapeParams.defaults(type);
        int count = gen(p).size();
        for (String axis : List.of(ShapeType.ROTATE_X, ShapeType.ROTATE_Y, ShapeType.ROTATE_Z)) {
            List<Cell> turned = gen(p.with(axis, 90));
            assertEquals(count, turned.size(), type + " " + axis);
            assertEquals(0, turned.stream().mapToInt(Cell::y).min().orElseThrow(), type + " " + axis + " still stands on the anchor");
        }
    }

    @Test
    void quarterTurnAroundYSwapsWidthAndDepth() {
        // A 1-deep, 7-wide upright arch turned 90 degrees faces the other way
        ShapeParams arch = ShapeParams.defaults(ShapeType.ARCH).with("depth", 1);
        List<Cell> before = gen(arch), after = gen(arch.with(ShapeType.ROTATE_Y, 90));
        int spanXBefore = before.stream().mapToInt(Cell::x).max().orElseThrow() - before.stream().mapToInt(Cell::x).min().orElseThrow();
        int spanZAfter = after.stream().mapToInt(Cell::z).max().orElseThrow() - after.stream().mapToInt(Cell::z).min().orElseThrow();
        assertEquals(spanXBefore, spanZAfter);
    }

    @Test
    void freeAngleKeepsASolidShapeSolid() {
        // A square slab turned 45 degrees: about the same area, and no holes inside it
        ShapeParams slab = box(6, 1);
        int flat = gen(slab).size();
        Set<Cell> cells = new HashSet<>(gen(slab.with(ShapeType.ROTATE_Y, 45)));
        assertTrue(cells.size() > flat * 0.8 && cells.size() < flat * 1.4, "area " + cells.size() + " vs " + flat);
        int cx = (int) Math.round(cells.stream().mapToInt(Cell::x).average().orElseThrow());
        int cz = (int) Math.round(cells.stream().mapToInt(Cell::z).average().orElseThrow());
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
                if (Math.abs(x) + Math.abs(z) <= 3) assertTrue(cells.contains(new Cell(cx + x, 0, cz + z)), "hole at " + x + "," + z);
    }

    @Test
    void tiltedRingHasNoGaps() {
        // A thin flat ring tilted 30 degrees: every block still touches a neighbor (26-connected, no breaks)
        ShapeParams ring = ShapeParams.defaults(ShapeType.TORUS).withSize(8).with("tube_radius", 1)
                .with(ShapeType.ROTATE_X, 30);
        Set<Cell> cells = new HashSet<>(gen(ring));
        Cell start = cells.iterator().next();
        Set<Cell> seen = new HashSet<>(List.of(start));
        java.util.ArrayDeque<Cell> queue = new java.util.ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            Cell c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                Cell n = new Cell(c.x() + dx, c.y() + dy, c.z() + dz);
                if (cells.contains(n) && seen.add(n)) queue.add(n);
            }
        }
        assertEquals(cells.size(), seen.size(), "one connected ring");
        int flat = gen(ring.with(ShapeType.ROTATE_X, 0)).size();
        assertTrue(cells.size() > flat * 0.8 && cells.size() < flat * 1.6, cells.size() + " vs " + flat);
    }

    @Test
    void partsTurnOnTheirOwn() {
        // Two arches, the second turned 90 degrees, cross like a groin vault
        ShapeParams plus = ShapeParams.defaults(ShapeType.ARCH).with("depth", 1).withParts(List.of(
                new ShapeParams.Part(ShapeParams.defaults(ShapeType.ARCH).with("depth", 1).with(ShapeType.ROTATE_Y, 90),
                        ShapeParams.Operation.UNITE, 0, 0, 0)));
        int one = gen(ShapeParams.defaults(ShapeType.ARCH).with("depth", 1)).size();
        int both = gen(plus).size();
        assertTrue(both > one && both < 2 * one, "two crossing arches share only their crown: " + both);
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

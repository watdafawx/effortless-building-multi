package nl.requios.effortlessbuilding.shape;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The dome, stairs, pipe, gothic arch, tower fittings, prism caps, new floors and the built-in designs. */
class ShapeExtrasTest {

    private static final int NO_LIMIT = 10_000;

    private static List<Cell> gen(ShapeParams p) {
        return ShapeGenerator.generate(p, NO_LIMIT);
    }

    private static Set<Cell> set(ShapeParams p) {
        return new HashSet<>(gen(p));
    }

    /** Number of groups of cells touching by face, edge or corner (round walls only ever touch diagonally). */
    private static int groups(Set<Cell> cells) {
        Set<Cell> left = new HashSet<>(cells);
        int groups = 0;
        List<int[]> d = new java.util.ArrayList<>();
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) if (x != 0 || y != 0 || z != 0) d.add(new int[]{x, y, z});
        while (!left.isEmpty()) {
            groups++;
            ArrayDeque<Cell> todo = new ArrayDeque<>(List.of(left.iterator().next()));
            left.removeAll(todo);
            while (!todo.isEmpty()) {
                Cell c = todo.poll();
                for (int[] n : d) {
                    Cell next = new Cell(c.x() + n[0], c.y() + n[1], c.z() + n[2]);
                    if (left.remove(next)) todo.add(next);
                }
            }
        }
        return groups;
    }

    // ---- dome ----

    @Test
    void domeIsHalfASolidBallAndHollowKeepsAnOpenBase() {
        ShapeParams dome = ShapeParams.defaults(ShapeType.DOME).withSize(6);
        Set<Cell> solid = set(dome);
        assertTrue(solid.contains(new Cell(0, 0, 0)), "base center is filled");
        assertTrue(solid.contains(new Cell(0, 6, 0)), "reaches its height at the middle");
        assertFalse(solid.contains(new Cell(0, 7, 0)));

        Set<Cell> hollow = set(dome.withHollow(true));
        assertTrue(hollow.size() < solid.size());
        assertFalse(hollow.contains(new Cell(0, 0, 0)), "open base: nothing under the dome");
        assertFalse(hollow.contains(new Cell(0, 3, 0)), "empty inside");
        assertTrue(hollow.contains(new Cell(0, 6, 0)), "the top is shell");
        assertEquals(1, groups(hollow), "shell is one piece");
    }

    @Test
    void domeFlippedHangsDownAndKeepsItsBlockCount() {
        ShapeParams dome = ShapeParams.defaults(ShapeType.DOME).withSize(5);
        List<Cell> up = gen(dome), down = gen(dome.with("flip", 1));
        assertEquals(up.size(), down.size());
        // Right way up it is widest at the bottom; hanging it is widest at the top
        int upBase = (int) up.stream().filter(c -> c.y() == 0).count();
        int downBase = (int) down.stream().filter(c -> c.y() == 0).count();
        assertTrue(upBase > downBase);
    }

    // ---- stairs ----

    @Test
    void straightStairsRiseOneBlockPerStep() {
        ShapeParams stairs = ShapeParams.defaults(ShapeType.STAIRS).withSize(1).with("steps", 6);
        Set<Cell> cells = set(stairs);
        for (int step = 0; step < 6; step++) {
            int top = step;
            // Column at the middle of step i: height i + 1, from the ground up
            assertTrue(cells.contains(new Cell(0, top, step)), "step " + step);
            assertFalse(cells.contains(new Cell(0, top + 1, step)), "step " + step + " is not taller");
            assertTrue(cells.contains(new Cell(0, 0, step)), "step " + step + " is solid down to the ground");
        }
        assertEquals(3 * (1 + 2 + 3 + 4 + 5 + 6), cells.size(), "3 wide, 1 + 2 + .. + 6 high");
    }

    @Test
    void stairsHonorRiseRunAndStepsOnlyFill() {
        ShapeParams stairs = ShapeParams.defaults(ShapeType.STAIRS).withSize(1).with("steps", 4).with("rise", 2).with("run", 3);
        Set<Cell> cells = set(stairs);
        assertTrue(cells.contains(new Cell(0, 1, 2)), "first step is 2 high and 3 long");
        assertFalse(cells.contains(new Cell(0, 2, 2)));
        assertTrue(cells.contains(new Cell(0, 7, 11)), "last step top at 8 high, 12 along");
        Set<Cell> thin = set(stairs.with("fill", 1));
        assertEquals(4 * 3 * 3, thin.size(), "one layer per step: 4 steps, 3 long, 3 wide");
        assertFalse(thin.contains(new Cell(0, 0, 11)), "nothing below a floating step");
    }

    @Test
    void turningStairsHaveALevelLandingAndEveryStepIsReachable() {
        for (int turn = 1; turn <= 3; turn++) {
            ShapeParams stairs = ShapeParams.defaults(ShapeType.STAIRS).withSize(1).with("steps", 8).with("turn", turn);
            Set<Cell> cells = set(stairs);
            assertEquals(1, groups(cells), "turn " + turn + " is one piece");
            // First flight has 4 steps (top y = 3); the 3 x 3 landing is level with its last step
            for (int u = -1; u <= 1; u++)
                for (int v = 4; v <= 6; v++) {
                    assertTrue(cells.contains(new Cell(u, 3, v)), "landing " + u + "," + v + " turn " + turn);
                    assertFalse(cells.contains(new Cell(u, 4, v)), "landing is flat, turn " + turn);
                }
            int maxY = cells.stream().mapToInt(Cell::y).max().orElseThrow();
            assertEquals(7, maxY, "8 steps reach height 8, turn " + turn);
        }
    }

    // ---- pipe ----

    @Test
    void pipeIsATubeOpenAtBothEnds() {
        ShapeParams pipe = ShapeParams.defaults(ShapeType.PIPE).withSize(2).with("pipe_length", 6).with("wall", 1);
        Set<Cell> cells = set(pipe);
        assertFalse(cells.contains(new Cell(0, 3, 0)), "hollow inside");
        assertTrue(cells.contains(new Cell(2, 3, 0)), "wall");
        assertTrue(cells.contains(new Cell(2, 0, 0)) && cells.contains(new Cell(2, 5, 0)), "wall reaches both ends");
        assertFalse(cells.contains(new Cell(0, 0, 0)) || cells.contains(new Cell(0, 5, 0)), "ends are open");
        assertEquals(6, cells.stream().map(Cell::y).distinct().count());
        // A wall as thick as the radius gives a solid rod
        assertTrue(set(pipe.with("wall", 3)).contains(new Cell(0, 3, 0)));
    }

    @Test
    void pipeElbowIsOneConnectedTubeThatTurnsEast() {
        ShapeParams elbow = ShapeParams.defaults(ShapeType.PIPE).withSize(2).with("bend", 1)
                .with("pipe_length", 5).with("bend_radius", 5).with("pipe_arm", 4);
        Set<Cell> cells = set(elbow);
        assertEquals(1, groups(cells), "no gaps in the bend");
        assertTrue(cells.size() > set(elbow.with("bend", 0)).size());
        int maxX = cells.stream().mapToInt(Cell::x).max().orElseThrow();
        int minX = cells.stream().mapToInt(Cell::x).min().orElseThrow();
        assertEquals(-2, minX, "the bend never goes west of the first leg");
        assertEquals(5 + 4 - 1, maxX, "the arm ends bend radius + arm length - 1 blocks east");
    }

    // ---- arch ----

    @Test
    void pointedArchIsTallerThanRoundAndSymmetric() {
        ShapeParams round = ShapeParams.defaults(ShapeType.ARCH).withSize(5);
        ShapeParams pointed = round.with("style", 1);
        int roundTop = gen(round).stream().mapToInt(Cell::y).max().orElseThrow();
        List<Cell> cells = gen(pointed);
        int pointedTop = cells.stream().mapToInt(Cell::y).max().orElseThrow();
        assertTrue(pointedTop > roundTop, "pointed " + pointedTop + " vs round " + roundTop);
        Set<Cell> all = new HashSet<>(cells);
        for (Cell c : cells) assertTrue(all.contains(new Cell(-c.x(), c.y(), c.z())), "mirrored " + c);
        assertTrue(all.stream().anyMatch(c -> c.x() == 0 && c.y() == pointedTop), "the point is in the middle");
        // The opening is clear
        assertFalse(all.contains(new Cell(0, 1, 0)));
        assertEquals(1, groups(all));
    }

    // ---- prism / star caps ----

    @Test
    void closedCapsGiveAHollowPrismAFloorAndCeiling() {
        for (ShapeType type : new ShapeType[]{ShapeType.PRISM, ShapeType.STAR}) {
            ShapeParams open = ShapeParams.defaults(type).withSize(6).with("height", 5).withHollow(true);
            Set<Cell> openCells = set(open), closed = set(open.with("caps", 1));
            assertTrue(closed.size() > openCells.size(), type.name());
            assertTrue(closed.containsAll(openCells), type.name());
            assertFalse(openCells.contains(new Cell(0, 0, 0)), type.name() + " open at the bottom");
            assertTrue(closed.contains(new Cell(0, 0, 0)) && closed.contains(new Cell(0, 4, 0)), type.name() + " closed");
            assertFalse(closed.contains(new Cell(0, 2, 0)), type.name() + " still empty in between");
        }
    }

    // ---- tower ----

    @Test
    void towerDoorIsOnTheNorthSideAndLeavesTheTopAlone() {
        ShapeParams tower = ShapeParams.defaults(ShapeType.TOWER).withSize(4).with("height", 12);
        Set<Cell> plain = set(tower), withDoor = set(tower.with("door", 1));
        assertTrue(plain.contains(new Cell(0, 0, -4)), "wall in the middle of the north side");
        assertFalse(withDoor.contains(new Cell(0, 0, -4)));
        assertFalse(withDoor.contains(new Cell(0, 2, -4)), "3 high");
        assertTrue(withDoor.contains(new Cell(0, 3, -4)), "wall above the door");
        assertTrue(withDoor.contains(new Cell(0, 0, 4)), "south side untouched");
    }

    @Test
    void towerWindowsSitHalfwayUpAndAvoidTheDoor() {
        ShapeParams tower = ShapeParams.defaults(ShapeType.TOWER).withSize(5).with("height", 14).with("door", 1);
        Set<Cell> plain = set(tower), windowed = set(tower.with("windows", 4));
        Set<Cell> gone = plain.stream().filter(c -> !windowed.contains(c)).collect(Collectors.toSet());
        assertFalse(gone.isEmpty());
        assertTrue(windowed.containsAll(plain.stream().filter(c -> c.y() < 7 || c.y() > 8).collect(Collectors.toSet())),
                "windows only cut layers 7 and 8");
        assertTrue(gone.stream().allMatch(c -> c.y() == 7 || c.y() == 8));
        assertFalse(gone.stream().anyMatch(c -> c.z() < 0 && Math.abs(c.x()) <= 1), "none where the door is");
        // Too low a tower gets no windows rather than a broken wall
        assertEquals(set(ShapeParams.defaults(ShapeType.TOWER).with("height", 4)),
                set(ShapeParams.defaults(ShapeType.TOWER).with("height", 4).with("windows", 6)));
    }

    @Test
    void towerFloorsFillTheInsideAtEvenHeights() {
        ShapeParams tower = ShapeParams.defaults(ShapeType.TOWER).withSize(4).with("height", 12).with("battlement", 0);
        Set<Cell> cells = set(tower.with("floors", 2));
        assertTrue(cells.contains(new Cell(0, 4, 0)) && cells.contains(new Cell(0, 8, 0)), "floors at 4 and 8");
        assertFalse(cells.contains(new Cell(0, 6, 0)));
        assertEquals(1, groups(cells), "floors join the wall");
        assertFalse(set(tower).contains(new Cell(0, 4, 0)));
    }

    @Test
    void towerRoofCapsTheWallWithAPointInsteadOfBattlements() {
        ShapeParams tower = ShapeParams.defaults(ShapeType.TOWER).withSize(4).with("height", 10).with("sides", 6);
        Set<Cell> roofed = set(tower.with("roof", 1));
        int top = roofed.stream().mapToInt(Cell::y).max().orElseThrow();
        assertTrue(top >= 10 + 3, "roof is at least a few blocks tall, was " + top);
        assertTrue(roofed.contains(new Cell(0, top, 0)), "the point is over the middle");
        assertTrue(roofed.stream().filter(c -> c.y() == 10).count() > set(tower).stream().filter(c -> c.y() == 10).count(),
                "the first roof layer covers the inside as well as the wall");
        assertEquals(1, groups(roofed));
    }

    // ---- floor ----

    @Test
    void newFloorOutlinesLookRight() {
        ShapeParams floor = ShapeParams.defaults(ShapeType.FLOOR).withSize(10).with("length", 10);
        Set<Cell> star = set(floor.with("outline", 7)), heart = set(floor.with("outline", 8)), triangle = set(floor.with("outline", 9));
        // Star: one tip straight up the +z side (v), nothing in the corners
        assertTrue(star.contains(new Cell(0, 0, 10)), "a tip points along +v");
        assertFalse(star.contains(new Cell(10, 0, 10)));
        assertFalse(star.contains(new Cell(0, 0, -10)), "an odd point count has no tip straight opposite");
        // Heart: widest above the middle, a point at the bottom
        assertTrue(heart.contains(new Cell(0, 0, -10)), "point at the bottom (-v)");
        assertFalse(heart.contains(new Cell(8, 0, -8)));
        assertTrue(heart.stream().filter(c -> c.z() == 5).count() > heart.stream().filter(c -> c.z() == -8).count());
        assertEquals(heart.stream().filter(c -> c.x() < 0).count(), heart.stream().filter(c -> c.x() > 0).count(), "symmetric");
        // Triangle: wide base on -v, narrowing to the top
        assertTrue(triangle.stream().filter(c -> c.z() == -10).count() > triangle.stream().filter(c -> c.z() == 8).count());
    }

    // ---- designs ----

    @Test
    void everyBuiltInDesignBuildsSomethingSensible() {
        assertTrue(ShapePresets.get().size() >= 10);
        Set<String> ids = new HashSet<>();
        for (ShapePresets.Preset design : ShapePresets.get()) {
            assertTrue(ids.add(design.id()), "unique id " + design.id());
            List<Cell> cells = gen(design.params());
            assertFalse(cells.isEmpty(), design.id());
            assertEquals(cells.size(), new HashSet<>(cells).size(), design.id() + " has duplicates");
            assertEquals(0, cells.stream().mapToInt(Cell::y).min().orElseThrow(), design.id() + " stands on the anchor");
            assertTrue(cells.size() < 20_000, design.id() + " is a sensible size: " + cells.size());
        }
    }

    @Test
    void connectedDesignsComeOutInOnePiece() {
        for (String id : List.of("lighthouse", "round_keep", "stair_tower", "domed_hall", "fountain", "switchback_stairs", "elbow_pipe")) {
            ShapePresets.Preset design = ShapePresets.get().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
            assertEquals(1, groups(set(design.params())), id);
        }
    }

    // ---- language file ----

    @Test
    void everyShapeParameterDesignAndOptionHasEnglishText() throws Exception {
        JsonObject lang;
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/assets/effortlessbuilding/lang/en_us.json"), StandardCharsets.UTF_8)) {
            lang = new Gson().fromJson(in, JsonObject.class);
        }
        Set<String> missing = new HashSet<>();
        for (ShapeType type : ShapeType.values()) {
            if (!lang.has(type.getNameKey())) missing.add(type.getNameKey());
            for (ShapeType.ParamSpec spec : type.params) {
                if (!lang.has(spec.getNameKey())) missing.add(spec.getNameKey());
                if (!lang.has(spec.getNameKey() + ".description")) missing.add(spec.getNameKey() + ".description");
                for (int i = 0; i < spec.options().size(); i++) {
                    if (!lang.has(spec.getOptionKey(i))) missing.add(spec.getOptionKey(i));
                }
            }
        }
        for (ShapePresets.Preset design : ShapePresets.get()) {
            if (!lang.has(design.getNameKey())) missing.add(design.getNameKey());
        }
        assertTrue(missing.isEmpty(), "missing en_us keys: " + missing.stream().sorted().toList());
    }
}

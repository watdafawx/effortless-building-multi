package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShapeModifiersTest {

    private static List<Cell> gen(ShapeParams p) {
        return ShapeGenerator.generate(p, 10_000);
    }

    /** A 5-wide, 1-deep, 20-tall bar (box standing along the depth axis, a plank). */
    private static ShapeParams bar() {
        return ShapeParams.defaults(ShapeType.BOX).withSize(2).with("length", 0).with("height", 20);
    }

    @Test
    void defaultsChangeNothing() {
        ShapeParams p = bar();
        assertFalse(ShapeModifiers.active(p));
        assertEquals(new HashSet<>(gen(p)), new HashSet<>(gen(p.with(ShapeModifiers.TWIST, 0))));
    }

    @Test
    void twistTurnsTheTopButNotTheBottom() {
        Set<Cell> flat = new HashSet<>(gen(bar()));
        Set<Cell> twisted = new HashSet<>(gen(bar().with(ShapeModifiers.TWIST, 90)));
        // Layer 0 untouched, last layer turned a quarter: the plank is now along z instead of x
        for (Cell c : flat) if (c.y() == 0) assertTrue(twisted.contains(c), "bottom layer moved " + c);
        int topX = 0, topZ = 0;
        for (Cell c : twisted) if (c.y() == 19) { topX = Math.max(topX, Math.abs(c.x())); topZ = Math.max(topZ, Math.abs(c.z())); }
        assertTrue(topZ > topX, "top should be turned: x " + topX + " z " + topZ);
    }

    @Test
    void twistKeepsVolumeAboutTheSame() {
        int before = gen(bar()).size();
        int after = gen(bar().with(ShapeModifiers.TWIST, 180)).size();
        assertTrue(Math.abs(after - before) < before * 0.25, before + " -> " + after);
    }

    @Test
    void taperNarrowsTheTop() {
        List<Cell> cells = gen(bar().with(ShapeModifiers.TAPER, 0.8));
        long bottom = cells.stream().filter(c -> c.y() == 0).count();
        long top = cells.stream().filter(c -> c.y() == 19).count();
        assertTrue(top < bottom, bottom + " vs " + top);
    }

    @Test
    void shearMovesTheTopSideways() {
        Set<Cell> cells = new HashSet<>(gen(bar().with(ShapeModifiers.SHEAR, 6)));
        int bottomMin = cells.stream().filter(c -> c.y() == 0).mapToInt(c -> c.x() + c.z()).min().orElseThrow();
        int topMin = cells.stream().filter(c -> c.y() == 19).mapToInt(c -> c.x() + c.z()).min().orElseThrow();
        assertNotEquals(bottomMin, topMin);
    }

    @Test
    void everyModifierStillMakesBlocks() {
        for (String key : List.of(ShapeModifiers.TWIST, ShapeModifiers.TAPER, ShapeModifiers.SHEAR, ShapeModifiers.BEND,
                ShapeModifiers.WAVE, ShapeModifiers.GROW, ShapeModifiers.NOISE, ShapeModifiers.DECIMATE)) {
            ShapeParams p = bar().with(key, key.equals(ShapeModifiers.TAPER) ? 0.5 : key.equals(ShapeModifiers.DECIMATE) ? 30 : 2)
                    .with(ShapeModifiers.WAVE_LENGTH, 8);
            assertFalse(gen(p).isEmpty(), key);
        }
    }

    @Test
    void originChoosesWhichLayerStaysPut() {
        Set<Cell> flat = new HashSet<>(gen(bar()));
        for (int mode = 0; mode < 3; mode++) {
            Set<Cell> twisted = new HashSet<>(gen(bar().with(ShapeModifiers.TWIST, 90).with(ShapeModifiers.ORIGIN, mode)));
            int y = new int[]{0, 10, 19}[mode];
            for (Cell c : flat) if (c.y() == y) assertTrue(twisted.contains(c), "origin " + mode + " moved layer " + y + " at " + c);
        }
    }

    @Test
    void originOffsetMovesTheTurningPoint() {
        Set<Cell> centered = new HashSet<>(gen(bar().with(ShapeModifiers.TWIST, 90)));
        Set<Cell> shifted = new HashSet<>(gen(bar().with(ShapeModifiers.TWIST, 90).with(ShapeModifiers.ORIGIN_X, 4)));
        assertNotEquals(centered, shifted);
    }

    @Test
    void modifiersKeepTheShapeOnItsBase() {
        for (String key : List.of(ShapeModifiers.GROW, ShapeModifiers.NOISE, ShapeModifiers.BEND)) {
            int minY = gen(bar().with(key, 2)).stream().mapToInt(Cell::y).min().orElseThrow();
            assertEquals(0, minY, key);
        }
    }

    @Test
    void originMarkerFollowsTheAnchor() {
        ShapeParams p = bar().with(ShapeModifiers.TWIST, 45).with(ShapeModifiers.ORIGIN, 2);
        double[] o = ShapeGenerator.modifierOrigin(p, n -> List.of());
        assertNotNull(o);
        assertEquals(19, o[1], 0.01); // end of a 20-tall bar standing on y = 0
        double[] shifted = ShapeGenerator.modifierOrigin(p.with(ShapeType.ANCHOR_Y, 5), n -> List.of());
        assertEquals(o[1] - 5, shifted[1], 0.01); // a positive anchor shift moves the shape the other way
        assertNull(ShapeGenerator.modifierOrigin(bar(), n -> List.of()));
    }

    @Test
    void growAddsAndShrinkRemoves() {
        int base = gen(bar()).size();
        assertTrue(gen(bar().with(ShapeModifiers.GROW, 1)).size() > base);
        assertTrue(gen(bar().with(ShapeModifiers.GROW, -1)).size() < base);
    }

    @Test
    void decimateAndNoiseAreStable() {
        ShapeParams p = bar().with(ShapeModifiers.DECIMATE, 40).with(ShapeModifiers.NOISE, 1).with(ShapeModifiers.SEED, 3);
        assertEquals(new HashSet<>(gen(p)), new HashSet<>(gen(p)));
        assertTrue(gen(p).size() < gen(bar()).size());
    }

    @Test
    void bendCurvesTheBar() {
        Set<Cell> straight = new HashSet<>(gen(bar()));
        Set<Cell> bent = new HashSet<>(gen(bar().with(ShapeModifiers.BEND, 90)));
        assertNotEquals(straight, bent);
        assertTrue(bent.size() >= straight.size() / 2);
    }
}

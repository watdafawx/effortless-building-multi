package nl.requios.effortlessbuilding.palette;

import nl.requios.effortlessbuilding.palette.PaletteSuggester.Swatch;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {

    // A small stand-in for the scanned block colors
    private static final List<Swatch> BLOCKS = List.of(
            new Swatch("cobblestone", 0x7F7F7F),
            new Swatch("stone", 0x7E7E7E + 0x010101),
            new Swatch("stone_bricks", 0x7A7A7A),
            new Swatch("smooth_stone", 0x9E9E9E),
            new Swatch("deepslate", 0x505053),
            new Swatch("blackstone", 0x2A2429),
            new Swatch("calcite", 0xDFE0DC),
            new Swatch("red_wool", 0xA12722),
            new Swatch("orange_wool", 0xF07613),
            new Swatch("yellow_wool", 0xF8C527),
            new Swatch("lime_wool", 0x70B919),
            new Swatch("green_wool", 0x546D1B),
            new Swatch("blue_wool", 0x35399D),
            new Swatch("terracotta", 0x985E43),
            new Swatch("red_terracotta", 0x8F3D2E),
            new Swatch("brown_terracotta", 0x4D3323),
            new Swatch("orange_terracotta", 0xA15325),
            new Swatch("white_terracotta", 0xD1B2A1));

    private static Swatch get(String id) {
        return BLOCKS.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void similarToStoneIsGrey() {
        List<Swatch> out = PaletteSuggester.similar(get("stone"), BLOCKS, 4);
        assertEquals("stone", out.getFirst().id());
        for (Swatch s : out) {
            assertTrue(Set.of("stone", "cobblestone", "stone_bricks", "smooth_stone", "deepslate").contains(s.id()), s.id());
        }
    }

    @Test
    void shadesStayInTheHueFamilyAndGoDarkToLight() {
        List<Swatch> out = PaletteSuggester.shades(get("terracotta"), BLOCKS, 4);
        assertTrue(out.size() >= 3, out.toString());
        for (Swatch s : out) assertTrue(s.id().contains("terracotta") || s.id().contains("orange"), s.id());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(PaletteSuggester.lab(out.get(i - 1).rgb())[0] <= PaletteSuggester.lab(out.get(i).rgb())[0]);
        }
    }

    @Test
    void greyShadesAreGrey() {
        List<Swatch> out = PaletteSuggester.shades(get("stone"), BLOCKS, 5);
        for (Swatch s : out) assertFalse(s.id().contains("wool") || s.id().contains("terracotta"), s.id());
        assertEquals("blackstone", out.getFirst().id());
    }

    @Test
    void blendKeepsEndsAndHasNoRepeats() {
        List<Swatch> out = PaletteSuggester.blend(get("yellow_wool"), get("red_wool"), BLOCKS, 4);
        assertEquals("yellow_wool", out.getFirst().id());
        assertEquals("red_wool", out.getLast().id());
        assertEquals(out.size(), new HashSet<>(out).size());
        assertTrue(out.stream().anyMatch(s -> s.id().equals("orange_wool")), out.toString());
    }

    @Test
    void analogousStartsWithTheSeed() {
        List<Swatch> out = PaletteSuggester.analogous(get("orange_wool"), BLOCKS, 3);
        assertEquals("orange_wool", out.getFirst().id());
        assertEquals(out.size(), new HashSet<>(out).size());
    }

    @Test
    void patterns() {
        assertEquals(0, PalettePattern.CHECKER.index(2, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(1, PalettePattern.CHECKER.index(2, 1, 1, 0, 0, 0, 0, 0));
        assertEquals(1, PalettePattern.CHECKER.index(2, 2, 2, 0, 0, 0, 0, 0), "band 2: cells are 2 wide");
        assertEquals(2, PalettePattern.LAYERS.index(3, 1, 5, 2, 7, 0, 0, 0));
        assertEquals(0, PalettePattern.LAYERS.index(3, 1, 0, -3, 0, 0, 0, 0), "works below the start");
        assertEquals(0, PalettePattern.GRADIENT.index(3, 1, 0, 0, 0, 0, 8, 0));
        assertEquals(2, PalettePattern.GRADIENT.index(3, 1, 0, 8, 0, 0, 8, 0));
        assertEquals(1, PalettePattern.RINGS.index(3, 2, 3, 0, 0, 0, 0, 0));
        // Random spreads over all entries
        Set<Integer> seen = new HashSet<>();
        for (long p = 0; p < 200; p++) seen.add(PalettePattern.RANDOM.index(4, 1, 0, 0, 0, 0, 0, p * 7919));
        assertEquals(Set.of(0, 1, 2, 3), seen);
    }
}

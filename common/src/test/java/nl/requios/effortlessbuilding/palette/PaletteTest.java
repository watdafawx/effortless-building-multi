package nl.requios.effortlessbuilding.palette;

import nl.requios.effortlessbuilding.palette.PaletteSuggester.Mode;
import nl.requios.effortlessbuilding.palette.PaletteSuggester.Swatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {

    // A small stand-in for the scanned block colors, including near-duplicates like modded copies
    private static final List<Swatch> BLOCKS = List.of(
            new Swatch("cobblestone", 0x7F7F7F),
            new Swatch("modded_cobblestone", 0x7F7F80),
            new Swatch("stone", 0x7F7F7F + 0x010101),
            new Swatch("stone_bricks", 0x7A7A7A),
            new Swatch("smooth_stone", 0x9E9E9E),
            new Swatch("andesite", 0x888889),
            new Swatch("deepslate", 0x505053),
            new Swatch("blackstone", 0x2A2429),
            new Swatch("calcite", 0xDFE0DC),
            new Swatch("red_wool", 0xA12722),
            new Swatch("orange_wool", 0xF07613),
            new Swatch("yellow_wool", 0xF8C527),
            new Swatch("lime_wool", 0x70B919),
            new Swatch("green_wool", 0x546D1B),
            new Swatch("cyan_wool", 0x158991),
            new Swatch("blue_wool", 0x35399D),
            new Swatch("purple_wool", 0x792AAC),
            new Swatch("terracotta", 0x985E43),
            new Swatch("red_terracotta", 0x8F3D2E),
            new Swatch("brown_terracotta", 0x4D3323),
            new Swatch("orange_terracotta", 0xA15325),
            new Swatch("white_terracotta", 0xD1B2A1));

    private static Swatch get(String id) {
        return BLOCKS.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<Swatch> suggest(Mode mode, String first, int count) {
        return PaletteSuggester.suggest(mode, List.of(get(first)), BLOCKS, count, 0);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void noRepeatsAndNoLookalikes(Mode mode) {
        for (long seed : new long[]{0, 1, 2, 3}) {
            List<Swatch> out = PaletteSuggester.suggest(mode, List.of(get("terracotta"), get("blue_wool")), BLOCKS, 5, seed);
            assertFalse(out.isEmpty(), mode + " " + seed);
            assertEquals(out.size(), new HashSet<>(out).size(), mode + " repeats");
            for (int i = 0; i < out.size(); i++)
                for (int j = i + 1; j < out.size(); j++)
                    assertTrue(PaletteSuggester.distance(PaletteSuggester.lab(out.get(i).rgb()), PaletteSuggester.lab(out.get(j).rgb()))
                            >= PaletteSuggester.MIN_DIFFERENCE, mode + " lookalikes " + out.get(i) + " " + out.get(j));
        }
    }

    @Test
    void similarToStoneIsGreyWithoutCopies() {
        List<Swatch> out = suggest(Mode.SIMILAR, "cobblestone", 4);
        assertEquals("cobblestone", out.getFirst().id());
        for (Swatch s : out) assertFalse(s.id().contains("wool"), s.id());
        assertFalse(out.stream().anyMatch(s -> s.id().equals("modded_cobblestone") || s.id().equals("stone")),
                "near-identical copies are skipped: " + out);
    }

    @Test
    void shadesStayInTheHueFamilyAndGoDarkToLight() {
        List<Swatch> out = suggest(Mode.SHADES, "terracotta", 4);
        assertTrue(out.size() >= 3, out.toString());
        for (Swatch s : out) assertTrue(s.id().contains("terracotta") || s.id().contains("orange"), s.id());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(PaletteSuggester.lab(out.get(i - 1).rgb())[0] <= PaletteSuggester.lab(out.get(i).rgb())[0]);
        }
    }

    @Test
    void blendKeepsEnds() {
        List<Swatch> out = PaletteSuggester.suggest(Mode.BLEND, List.of(get("yellow_wool"), get("red_wool")), BLOCKS, 4, 0);
        assertEquals("yellow_wool", out.getFirst().id());
        assertEquals("red_wool", out.getLast().id());
        assertTrue(out.stream().anyMatch(s -> s.id().equals("orange_wool")), out.toString());
    }

    @Test
    void rerollChangesThePickButSeedZeroIsStable() {
        assertEquals(suggest(Mode.SIMILAR, "terracotta", 5), suggest(Mode.SIMILAR, "terracotta", 5));
        Set<List<Swatch>> variants = new HashSet<>();
        for (long seed = 1; seed <= 12; seed++) {
            variants.add(PaletteSuggester.suggest(Mode.SIMILAR, List.of(get("terracotta")), BLOCKS, 5, seed));
        }
        assertTrue(variants.size() > 1, "rerolls give different palettes");
    }

    @Test
    void complementReachesTheOppositeHue() {
        List<Swatch> out = suggest(Mode.COMPLEMENT, "orange_wool", 3);
        assertTrue(out.stream().anyMatch(s -> s.id().equals("blue_wool") || s.id().equals("cyan_wool")), out.toString());
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
        Set<Integer> seen = new HashSet<>();
        for (long p = 0; p < 200; p++) seen.add(PalettePattern.RANDOM.index(4, 1, 0, 0, 0, 0, 0, p * 7919));
        assertEquals(Set.of(0, 1, 2, 3), seen);
    }
}

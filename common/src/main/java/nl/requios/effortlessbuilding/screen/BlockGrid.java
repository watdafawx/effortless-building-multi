package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.palette.PaletteSuggester;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A scrollable, searchable grid of every scanned block, sorted by color (greys by lightness, then around
 * the color wheel). Copies of the same block from other mods can be hidden. Shared by the palette screen
 * and the block picker; the owning screen passes in where to draw it.
 */
public class BlockGrid {

    public static final int SLOT = 20;

    private String search = "";
    private boolean hideCopies = true;
    private int scroll = 0;

    // Big packs have 18k+ blocks: filter and sort only when something changes
    private List<Item> cache = List.of();
    private Object cacheKey = null;

    public void setSearch(String search) {
        this.search = search;
        scroll = 0;
    }

    public String search() { return search; }

    public boolean hideCopies() { return hideCopies; }

    public void toggleHideCopies() {
        hideCopies = !hideCopies;
        scroll = 0;
    }

    /** Blocks shown, in grid order. */
    public List<Item> items() {
        Map<Item, Integer> colors = hideCopies ? BlockColorCache.distinctColors() : BlockColorCache.colors();
        Object key = List.of(System.identityHashCode(colors), colors.size(), search, hideCopies);
        if (!key.equals(cacheKey)) {
            String q = search.toLowerCase(Locale.ROOT).trim();
            List<Item> list = new ArrayList<>();
            for (Item item : colors.keySet()) {
                if (q.isEmpty() || BuiltInRegistries.ITEM.getKey(item).toString().contains(q)
                        || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)) list.add(item);
            }
            list.sort(Comparator.comparingDouble(item -> hueKey(colors.get(item))));
            cache = list;
            cacheKey = key;
        }
        return cache;
    }

    private static double hueKey(int rgb) {
        double[] lch = PaletteSuggester.lch(PaletteSuggester.lab(rgb));
        if (lch[1] < 10) return lch[0] / 100.0; // greys: 0..1 by lightness
        // Hue bands of 15 degrees, each running dark to light
        return 1 + Math.floor(lch[2] / 15) + lch[0] / 101.0;
    }

    /** Draws the visible part of the grid; returns the block under the mouse, if any. */
    public @Nullable Item render(GuiGraphics g, Font font, int x, int y, int w, int h, int mouseX, int mouseY,
                                 String emptyText) {
        List<Item> grid = items();
        int cols = cols(w), rows = rows(h);
        scroll = Math.max(0, Math.min(scroll, maxScroll(w, h)));
        Item hovered = null;
        for (int i = 0; i < cols * rows; i++) {
            int index = i + scroll * cols;
            if (index >= grid.size()) break;
            Item item = grid.get(index);
            int sx = x + (i % cols) * SLOT, sy = y + (i / cols) * SLOT;
            g.fill(sx, sy, sx + SLOT - 2, sy + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(item));
            g.renderItem(new ItemStack(item), sx + 1, sy + 1);
            if (mouseX >= sx && mouseX < sx + SLOT - 2 && mouseY >= sy && mouseY < sy + SLOT - 2) hovered = item;
        }
        if (grid.isEmpty()) g.drawString(font, emptyText, x, y + 4, 0x888888);
        return hovered;
    }

    public @Nullable Item itemAt(double mouseX, double mouseY, int x, int y, int w, int h) {
        if (mouseX < x || mouseY < y) return null;
        int col = (int) (mouseX - x) / SLOT, row = (int) (mouseY - y) / SLOT;
        if (col >= cols(w) || row >= rows(h)) return null;
        int index = (row + scroll) * cols(w) + col;
        List<Item> grid = items();
        return index < grid.size() ? grid.get(index) : null;
    }

    /** Scrolls one row per wheel notch, or a few rows at once while Alt is held. */
    public void scroll(double scrollY, int w, int h) {
        scroll = Math.max(0, Math.min(maxScroll(w, h), scroll + (scrollY > 0 ? -1 : 1) * scrollStep(rows(h))));
    }

    /** Rows to move per wheel notch: 1, or most of a page with Alt held (for long lists). */
    public static int scrollStep(int visibleRows) {
        return Screen.hasAltDown() ? Math.max(3, visibleRows - 1) : 1;
    }

    private int maxScroll(int w, int h) {
        return Math.max(0, (items().size() + cols(w) - 1) / cols(w) - rows(h));
    }

    public static int cols(int w) { return Math.max(1, w / SLOT); }
    public static int rows(int h) { return Math.max(1, h / SLOT); }
}

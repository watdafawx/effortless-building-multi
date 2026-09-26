package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nl.requios.effortlessbuilding.palette.*;
import nl.requios.effortlessbuilding.palette.PaletteSuggester.Swatch;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Block palette: build with several blocks at once, mixed in a pattern.
 * <p>
 * Left: on/off, where the blocks come from (hotbar slots or a picked list), the pattern and a color
 * strip. Right: every block sorted by color with search, and suggestions built from the palette's
 * first block (shades, similar, blend to the last block, neighbors on the color wheel).
 */
public class PaletteScreen extends Screen {

    private static final int SLOT = 20;
    private static final int LEFT_W = 206;
    private static final int SUGGEST_COUNT_MAX = 9;

    /** A suggestion strategy and its label key. */
    private record Suggestion(String key, BiFunction<List<Swatch>, List<Swatch>, List<Swatch>> make) {}

    private final Screen parent;
    private int panelW, panelH;
    private BlockPalette palette;
    private boolean enabled;

    private String search = "";
    private int gridScroll = 0;
    private int suggestCount = 5;
    private List<Item> suggestion = List.of();
    private String suggestionLabel = "";

    // Sorted by color, rebuilt when the scan finishes
    private List<Item> allBlocks = List.of();
    private int sortedForSize = -1;

    private ScreenWidgets widgets;
    private EditBox searchBox;

    public PaletteScreen(@Nullable Screen parent) {
        super(Component.translatable("effortlessbuilding.screen.palette"));
        this.parent = parent;
        this.palette = PaletteClientState.getPalette();
        this.enabled = PaletteClientState.isEnabled();
    }

    // =========================================================================
    // Init
    // =========================================================================

    @Override
    protected void init() {
        BlockColorCache.ensureReady();
        panelW = Math.min(width - 16, 860);
        panelH = Math.min(height - 16, 500);
        widgets = new ScreenWidgets(font, this::addRenderableWidget);
        widgets.clear();
        int px = panelX(), py = panelY();

        // ---- left column ----
        int x = px + 6, y = py + 22;
        widgets.addCheckbox(x, y + 3, I18n.get("effortlessbuilding.screen.palette_enabled"), enabled,
                () -> { enabled = !enabled; apply(); rebuildWidgets(); });
        y += 18;
        cycleRow(x, y, "effortlessbuilding.screen.palette_source", I18n.get(palette.source().getNameKey()),
                () -> palette = palette.withSource(next(BlockPalette.Source.values(), palette.source())));
        if (palette.source() == BlockPalette.Source.CUSTOM) {
            int by = slotsY() + customRows() * SLOT + 4;
            addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_add_held"), b -> addHeld())
                    .bounds(x, by, 98, 16).build());
            addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_clear"), b -> {
                        palette = palette.withCustom(List.of());
                        apply();
                        rebuildWidgets();
                    })
                    .bounds(x + 102, by, 60, 16).build());
        }
        y = patternY();
        cycleRow(x, y, "effortlessbuilding.screen.palette_pattern", I18n.get(palette.pattern().getNameKey()),
                () -> palette = palette.withPattern(next(PalettePattern.values(), palette.pattern())));
        y += 22;
        widgets.addIntField(x, y, String.valueOf(palette.band()), v -> { palette = palette.withBand(v); apply(); });
        y += 22;

        // ---- right: search, rescan, suggestions ----
        int rx = px + LEFT_W + 14, rw = panelW - LEFT_W - 20;
        searchBox = new EditBox(font, rx, py + 20, Math.min(160, rw - 150), 16, Component.translatable("effortlessbuilding.screen.palette_search"));
        searchBox.setHint(Component.translatable("effortlessbuilding.screen.palette_search"));
        searchBox.setValue(search);
        searchBox.setResponder(s -> { search = s; gridScroll = 0; });
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_rescan"), b -> BlockColorCache.scan())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_rescan.description")))
                .bounds(rx + searchBox.getWidth() + 4, py + 20, 70, 16).build());

        int sy = py + 44;
        int bx = rx;
        for (Suggestion s : suggestions()) {
            int w = font.width(I18n.get(s.key())) + 12;
            addRenderableWidget(Button.builder(Component.translatable(s.key()), b -> suggest(s))
                    .tooltip(Tooltip.create(Component.translatable(s.key() + ".description")))
                    .bounds(bx, sy, w, 16).build());
            bx += w + 4;
        }
        addRenderableWidget(Button.builder(Component.literal("−"), b -> { suggestCount = Math.max(2, suggestCount - 1); })
                .bounds(bx + 4, sy, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> { suggestCount = Math.min(SUGGEST_COUNT_MAX, suggestCount + 1); })
                .bounds(bx + 40, sy, 14, 16).build());
        if (!suggestion.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_use_suggestion"), b -> {
                        palette = palette.withSource(BlockPalette.Source.CUSTOM).withCustom(suggestion);
                        enabled = true;
                        apply();
                        rebuildWidgets();
                    })
                    .bounds(rx + suggestion.size() * SLOT + 8, sy + 22, 90, 16).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(px + panelW - 76, py + panelH - 20, 70, 16).build());
    }

    private void cycleRow(int x, int y, String labelKey, String value, Runnable cycle) {
        addRenderableWidget(Button.builder(Component.literal(value), b -> { cycle.run(); apply(); rebuildWidgets(); })
                .tooltip(Tooltip.create(Component.translatable(labelKey + ".description")))
                .bounds(x + ScreenWidgets.LABEL_W, y, LEFT_W - ScreenWidgets.LABEL_W - 8, 16).build());
    }

    private List<Suggestion> suggestions() {
        return List.of(
                new Suggestion("effortlessbuilding.screen.suggest.shades", (seed, all) ->
                        PaletteSuggester.shades(seed.getFirst(), all, suggestCount)),
                new Suggestion("effortlessbuilding.screen.suggest.similar", (seed, all) ->
                        PaletteSuggester.similar(seed.getFirst(), all, suggestCount)),
                new Suggestion("effortlessbuilding.screen.suggest.blend", (seed, all) ->
                        PaletteSuggester.blend(seed.getFirst(), seed.getLast(), all, suggestCount)),
                new Suggestion("effortlessbuilding.screen.suggest.analogous", (seed, all) ->
                        PaletteSuggester.analogous(seed.getFirst(), all, suggestCount)));
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void apply() {
        PaletteClientState.setPalette(palette);
        PaletteClientState.setEnabled(enabled);
    }

    private void addHeld() {
        if (minecraft == null || minecraft.player == null) return;
        Item held = minecraft.player.getMainHandItem().getItem();
        if (held instanceof BlockItem) addCustom(held);
    }

    private void addCustom(Item item) {
        List<Item> custom = new ArrayList<>(palette.custom());
        if (custom.size() >= BlockPalette.MAX_CUSTOM) return;
        custom.add(item);
        palette = palette.withSource(BlockPalette.Source.CUSTOM).withCustom(custom);
        apply();
        rebuildWidgets();
    }

    /** Builds a suggestion from the palette's blocks (or the held block when it has none). */
    private void suggest(Suggestion s) {
        Map<Item, Integer> colors = BlockColorCache.colors();
        List<Swatch> seed = new ArrayList<>();
        for (Item item : currentBlocks()) {
            Integer rgb = colors.get(item);
            if (rgb != null) seed.add(swatch(item, rgb));
        }
        if (seed.isEmpty() && minecraft != null && minecraft.player != null) {
            Item held = minecraft.player.getMainHandItem().getItem();
            if (colors.containsKey(held)) seed.add(swatch(held, colors.get(held)));
        }
        if (seed.isEmpty()) {
            suggestion = List.of();
            suggestionLabel = I18n.get("effortlessbuilding.screen.suggest.need_seed");
        } else {
            List<Swatch> all = new ArrayList<>();
            colors.forEach((item, rgb) -> all.add(swatch(item, rgb)));
            List<Item> out = new ArrayList<>();
            for (Swatch w : s.make().apply(seed, all)) {
                BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.ResourceLocation.parse(w.id())).ifPresent(out::add);
            }
            suggestion = out;
            suggestionLabel = I18n.get(s.key());
        }
        rebuildWidgets();
    }

    private static Swatch swatch(Item item, int rgb) {
        return new Swatch(BuiltInRegistries.ITEM.getKey(item).toString(), rgb);
    }

    private List<Item> currentBlocks() {
        return minecraft != null && minecraft.player != null ? palette.resolve(minecraft.player) : palette.custom();
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (widgets.handleCheckboxClick(mouseX, mouseY)) return true;
        int px = panelX(), py = panelY();
        int x = px + 6, slotsY = slotsY();

        if (palette.source() == BlockPalette.Source.HOTBAR) {
            int slot = slotAt(mouseX, mouseY, x, slotsY, Inventory.getSelectionSize(), Inventory.getSelectionSize());
            if (slot >= 0) {
                palette = palette.withHotbarSlots(palette.hotbarSlots() ^ (1 << slot));
                apply();
                return true;
            }
        } else {
            int i = slotAt(mouseX, mouseY, x, slotsY, 8, palette.custom().size());
            if (i >= 0) {
                List<Item> custom = new ArrayList<>(palette.custom());
                custom.remove(i);
                palette = palette.withCustom(custom);
                apply();
                rebuildWidgets();
                return true;
            }
        }

        Item gridItem = gridItemAt(mouseX, mouseY);
        if (gridItem != null) {
            addCustom(gridItem);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (widgets.handleScroll(mouseX, mouseY, scrollY)) return true;
        if (mouseX > panelX() + LEFT_W) {
            gridScroll = Math.max(0, Math.min(maxGridScroll(), gridScroll + (scrollY > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Index of the slot under the mouse in a row-wrapped grid, or -1. */
    private int slotAt(double mouseX, double mouseY, int x, int y, int perRow, int count) {
        if (mouseX < x || mouseY < y) return -1;
        int col = (int) (mouseX - x) / SLOT, row = (int) (mouseY - y) / SLOT;
        if (col >= perRow) return -1;
        int i = row * perRow + col;
        return i < count ? i : -1;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 170 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();
        g.fill(px + LEFT_W + 8, py + 2, px + LEFT_W + 9, py + panelH - 24, 0xFF555555);
        g.drawString(font, title, px + 6, py + 7, 0xFFFFFF);
        widgets.renderCheckboxes(g, mouseX, mouseY);

        // ---- left ----
        int x = px + 6, y = slotsY();
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_source"), x, y - 18, 0xCCCCCC);
        Item hovered = null;
        if (palette.source() == BlockPalette.Source.HOTBAR) {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                int sx = x + slot * SLOT;
                boolean on = palette.hasHotbarSlot(slot);
                g.fill(sx, y, sx + SLOT - 2, y + SLOT - 2, on ? 0xFF2F6B2F : 0xFF303030);
                if (on) g.renderOutline(sx, y, SLOT - 2, SLOT - 2, 0xFF7FD17F);
                ItemStack stack = minecraft != null && minecraft.player != null
                        ? minecraft.player.getInventory().getItem(slot) : ItemStack.EMPTY;
                g.renderItem(stack, sx + 1, y + 1);
                if (hit(mouseX, mouseY, sx, y) && !stack.isEmpty()) hovered = stack.getItem();
            }
            g.drawString(font, I18n.get("effortlessbuilding.screen.palette_hotbar_hint"), x, y + SLOT + 1, 0x888888);
        } else {
            List<Item> custom = palette.custom();
            for (int i = 0; i < Math.max(custom.size(), 1); i++) {
                int sx = x + (i % 8) * SLOT, sy = y + (i / 8) * SLOT;
                g.fill(sx, sy, sx + SLOT - 2, sy + SLOT - 2, 0xFF303030);
                if (i < custom.size()) {
                    g.renderItem(new ItemStack(custom.get(i)), sx + 1, sy + 1);
                    if (hit(mouseX, mouseY, sx, sy)) hovered = custom.get(i);
                }
            }
            if (custom.isEmpty()) g.drawString(font, I18n.get("effortlessbuilding.screen.palette_custom_hint"), x + SLOT + 2, y + 6, 0x888888);
        }
        y = patternY();
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_pattern"), x, y + 4, 0xCCCCCC);
        y += 22;
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_band"), x, y + 4, 0xCCCCCC);
        y += 26;

        // Color strip and a small pattern preview of what will be built
        List<Item> blocks = currentBlocks();
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_preview"), x, y, 0xAAAAAA);
        y += 11;
        if (blocks.isEmpty()) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.palette_empty"), x, y + 2, 0xFF8888);
        } else {
            int cell = 8, cols = (LEFT_W - 12) / cell, rows = Math.max(4, Math.min(12, (py + panelH - 30 - y) / cell));
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    // A wall seen from the front: x across, y up
                    int index = palette.pattern().index(blocks.size(), palette.band(), c, rows - 1 - r, 0, 0, rows - 1,
                            net.minecraft.core.BlockPos.asLong(c, rows - 1 - r, 0));
                    int rgb = BlockColorCache.colorOf(blocks.get(index));
                    g.fill(x + c * cell, y + r * cell, x + (c + 1) * cell, y + (r + 1) * cell, 0xFF000000 | rgb);
                }
            }
        }
        if (!enabled) g.drawString(font, I18n.get("effortlessbuilding.screen.palette_off_hint"), x, py + panelH - 18, 0xFFAA66);

        // ---- right ----
        int rx = px + LEFT_W + 14, rw = panelW - LEFT_W - 20;
        String status = BlockColorCache.isScanning() ? I18n.get("effortlessbuilding.screen.palette_scanning")
                : I18n.get("effortlessbuilding.screen.palette_block_count", BlockColorCache.colors().size());
        g.drawString(font, status, rx + searchBox.getWidth() + 80, py + 24, 0xAAAAAA);

        int sy = py + 44;
        int countX = rx;
        for (Suggestion s : suggestions()) countX += font.width(I18n.get(s.key())) + 16;
        g.drawCenteredString(font, String.valueOf(suggestCount), countX + 29, sy + 4, 0xFFFFFF);
        g.drawString(font, I18n.get("effortlessbuilding.screen.suggest.blocks"), countX + 58, sy + 4, 0xAAAAAA);

        int suggestY = sy + 22;
        if (suggestion.isEmpty()) {
            g.drawString(font, suggestionLabel.isEmpty() ? I18n.get("effortlessbuilding.screen.suggest.hint") : suggestionLabel,
                    rx, suggestY + 4, 0x888888);
        } else {
            for (int i = 0; i < suggestion.size(); i++) {
                int sx = rx + i * SLOT;
                g.fill(sx, suggestY, sx + SLOT - 2, suggestY + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(suggestion.get(i)));
                g.renderItem(new ItemStack(suggestion.get(i)), sx + 1, suggestY + 1);
                if (hit(mouseX, mouseY, sx, suggestY)) hovered = suggestion.get(i);
            }
        }

        // Block grid, sorted by color
        List<Item> grid = filteredBlocks();
        int gx = rx, gy = gridTop(), cols = gridCols(), rows = gridRows();
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_all_blocks"), gx, gy - 11, 0xAAAAAA);
        for (int i = 0; i < cols * rows; i++) {
            int index = i + gridScroll * cols;
            if (index >= grid.size()) break;
            Item item = grid.get(index);
            int sx = gx + (i % cols) * SLOT, sy2 = gy + (i / cols) * SLOT;
            g.fill(sx, sy2, sx + SLOT - 2, sy2 + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(item));
            g.renderItem(new ItemStack(item), sx + 1, sy2 + 1);
            if (hit(mouseX, mouseY, sx, sy2)) hovered = item;
        }
        if (grid.isEmpty()) {
            g.drawString(font, I18n.get(BlockColorCache.isScanning() ? "effortlessbuilding.screen.palette_scanning"
                    : "effortlessbuilding.screen.palette_no_match"), gx, gy + 4, 0x888888);
        }

        if (hovered != null) g.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
    }

    private static boolean hit(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2;
    }

    // =========================================================================
    // Block grid
    // =========================================================================

    /** All scanned blocks, greys first by lightness, then colors around the wheel. */
    private List<Item> sortedBlocks() {
        Map<Item, Integer> colors = BlockColorCache.colors();
        if (colors.size() != sortedForSize) {
            List<Item> list = new ArrayList<>(colors.keySet());
            list.sort(Comparator.comparingDouble(item -> hueKey(colors.get(item))));
            allBlocks = list;
            sortedForSize = colors.size();
        }
        return allBlocks;
    }

    private static double hueKey(int rgb) {
        double[] lab = PaletteSuggester.lab(rgb);
        double chroma = Math.hypot(lab[1], lab[2]);
        if (chroma < 10) return lab[0] / 100.0; // greys: 0..1 by lightness
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        if (hue < 0) hue += 360;
        // Bucket hues so each band runs dark to light
        return 1 + Math.floor(hue / 15) + lab[0] / 101.0;
    }

    private List<Item> filteredBlocks() {
        List<Item> sorted = sortedBlocks();
        if (search.isBlank()) return sorted;
        String q = search.toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        for (Item item : sorted) {
            if (BuiltInRegistries.ITEM.getKey(item).toString().contains(q)
                    || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)) out.add(item);
        }
        return out;
    }

    private @Nullable Item gridItemAt(double mouseX, double mouseY) {
        int gx = panelX() + LEFT_W + 14, gy = gridTop();
        if (mouseX < gx || mouseY < gy) return null;
        int col = (int) (mouseX - gx) / SLOT, row = (int) (mouseY - gy) / SLOT;
        if (col >= gridCols() || row >= gridRows()) return null;
        int index = (row + gridScroll) * gridCols() + col;
        List<Item> grid = filteredBlocks();
        return index < grid.size() ? grid.get(index) : null;
    }

    private int gridTop() { return panelY() + 44 + 22 + SLOT + 16; }
    private int gridCols() { return Math.max(1, (panelW - LEFT_W - 20) / SLOT); }
    private int gridRows() { return Math.max(1, (panelY() + panelH - 26 - gridTop()) / SLOT); }

    private int maxGridScroll() {
        int rowsTotal = (filteredBlocks().size() + gridCols() - 1) / gridCols();
        return Math.max(0, rowsTotal - gridRows());
    }

    /** Top of the hotbar slots / picked blocks. */
    private int slotsY() { return panelY() + 22 + 18 + 22; }

    /** Pattern row: below the slots (and, for picked blocks, their buttons). */
    private int patternY() {
        return slotsY() + (palette.source() == BlockPalette.Source.HOTBAR ? SLOT + 14 : customRows() * SLOT + 26);
    }

    private int customRows() {
        return Math.max(1, (palette.custom().size() + 7) / 8);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }

    @Override
    public void onClose() {
        apply();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

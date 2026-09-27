package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.palette.*;
import nl.requios.effortlessbuilding.palette.PaletteSuggester.Mode;
import nl.requios.effortlessbuilding.palette.PaletteSuggester.Swatch;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Block palette: build with several blocks at once, mixed in a pattern.
 * <p>
 * Left: on/off, where the blocks come from (hotbar slots or a picked list), pattern, band width,
 * save as favorite, and a rotatable 3D preview built from the real blocks.
 * Right: suggestions (eight color strategies, rerollable), then tabs for all blocks by color,
 * saved favorites and ready-made presets.
 */
public class PaletteScreen extends Screen {

    private static final int SLOT = 20;
    private static final int LEFT_W = 206;
    private static final int LIST_ROW_H = 22;
    private static final int SUGGEST_COUNT_MAX = 9;

    private enum Tab { ALL, FAVORITES, PRESETS }

    private final Screen parent;
    private int panelW, panelH;
    private BlockPalette palette;
    private boolean enabled;

    // ---- right side state ----
    private Tab tab = Tab.ALL;
    private String search = "";
    private boolean hideCopies = true;
    private int scroll = 0;
    private int suggestCount = 5;
    private Mode lastMode = null;
    private long rerollSeed = 0;
    private List<Item> suggestion = List.of();
    private String suggestionNote = "";

    // ---- block grid cache (18k+ blocks in big packs: filter only when something changes) ----
    private List<Item> gridCache = List.of();
    private Object gridKey = null;

    // ---- 3D preview camera ----
    private float yaw = -35, pitch = 25, zoom = 1;
    private boolean dragging = false;

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
        panelW = Math.min(width - 16, 900);
        panelH = Math.min(height - 16, 520);
        widgets = new ScreenWidgets(font, this::addRenderableWidget);
        widgets.clear();
        int px = panelX(), py = panelY();

        // ---- left column ----
        int x = px + 6;
        widgets.addCheckbox(x, py + 25, I18n.get("effortlessbuilding.screen.palette_enabled"), enabled,
                () -> { enabled = !enabled; apply(); rebuildWidgets(); });
        cycleRow(x, sourceY(), "effortlessbuilding.screen.palette_source", I18n.get(palette.source().getNameKey()),
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
        cycleRow(x, patternY(), "effortlessbuilding.screen.palette_pattern", I18n.get(palette.pattern().getNameKey()),
                () -> palette = palette.withPattern(next(PalettePattern.values(), palette.pattern())));
        widgets.addIntField(x, patternY() + 22, String.valueOf(palette.band()), v -> { palette = palette.withBand(v); apply(); });
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_save_favorite"), b -> saveFavorite())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_save_favorite.description")))
                .bounds(x, patternY() + 46, LEFT_W - 12, 16).build());

        // ---- right: search, copies, rescan ----
        int rx = rightX();
        searchBox = new EditBox(font, rx, py + 20, 150, 16, Component.translatable("effortlessbuilding.screen.palette_search"));
        searchBox.setHint(Component.translatable("effortlessbuilding.screen.palette_search"));
        searchBox.setValue(search);
        searchBox.setResponder(s -> { search = s; scroll = 0; tab = Tab.ALL; });
        addRenderableWidget(searchBox);
        widgets.addCheckbox(rx + 156, py + 24, I18n.get("effortlessbuilding.screen.palette_hide_copies"), hideCopies,
                () -> { hideCopies = !hideCopies; scroll = 0; rebuildWidgets(); });
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_rescan"), b -> BlockColorCache.scan())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_rescan.description")))
                .bounds(rescanX(), py + 20, 56, 16).build());

        // ---- suggestions: one button per strategy ----
        int bx = rx, sy = py + 42;
        for (Mode mode : Mode.values()) {
            int w = font.width(I18n.get(mode.getNameKey())) + 10;
            if (bx + w > px + panelW - 6) break;
            addRenderableWidget(Button.builder(Component.translatable(mode.getNameKey()), btn -> {
                        rerollSeed = 0;
                        suggest(mode);
                    })
                    .tooltip(Tooltip.create(Component.translatable(mode.getNameKey() + ".description")))
                    .bounds(bx, sy, w, 16).build());
            bx += w + 3;
        }
        int ry = py + 64;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> suggestCount = Math.max(2, suggestCount - 1))
                .bounds(rx, ry, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> suggestCount = Math.min(SUGGEST_COUNT_MAX, suggestCount + 1))
                .bounds(rx + 34, ry, 14, 16).build());
        Button reroll = addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_reroll"), b -> {
                    rerollSeed = (rerollSeed * 31 + System.nanoTime()) | 1;
                    if (lastMode != null) suggest(lastMode);
                })
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_reroll.description")))
                .bounds(rx + 86, ry, 54, 16).build());
        reroll.active = lastMode != null;
        if (!suggestion.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_use_suggestion"), b -> {
                        palette = palette.withSource(BlockPalette.Source.CUSTOM).withCustom(suggestion);
                        enabled = true;
                        apply();
                        rebuildWidgets();
                    })
                    .bounds(suggestionX() + suggestion.size() * SLOT + 4, ry, 70, 16).build());
        }

        // ---- tabs ----
        int tx = rx, ty = py + 88;
        for (Tab t : Tab.values()) {
            String label = I18n.get("effortlessbuilding.screen.palette_tab." + t.name().toLowerCase());
            if (t == Tab.FAVORITES) label += " (" + PaletteClientState.getFavorites().size() + ")";
            if (t == Tab.PRESETS) label += " (" + PalettePresets.get().size() + ")";
            int w = font.width(label) + 12;
            Button b = addRenderableWidget(Button.builder(Component.literal(label), btn -> { tab = t; scroll = 0; rebuildWidgets(); })
                    .bounds(tx, ty, w, 16).build());
            b.active = tab != t;
            tx += w + 3;
        }
        if (tab == Tab.PRESETS) {
            addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_reload_presets"), b -> {
                        PalettePresets.reload();
                        rebuildWidgets();
                    })
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_reload_presets.description")))
                    .bounds(tx + 8, ty, 60, 16).build());
        }
        if (tab == Tab.FAVORITES) {
            List<PaletteClientState.Favorite> favorites = PaletteClientState.getFavorites();
            for (int row = 0; row < listRows(); row++) {
                int i = row + scroll;
                if (i >= favorites.size()) break;
                String name = favorites.get(i).name();
                addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                            PaletteClientState.deleteFavorite(name);
                            rebuildWidgets();
                        })
                        .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_delete_favorite")))
                        .bounds(px + panelW - 22, contentTop() + row * LIST_ROW_H + 2, 14, 14).build());
            }
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(px + panelW - 76, py + panelH - 20, 70, 16).build());
    }

    private void cycleRow(int x, int y, String labelKey, String value, Runnable cycle) {
        addRenderableWidget(Button.builder(Component.literal(value), b -> { cycle.run(); apply(); rebuildWidgets(); })
                .tooltip(Tooltip.create(Component.translatable(labelKey + ".description")))
                .bounds(x + ScreenWidgets.LABEL_W, y, LEFT_W - ScreenWidgets.LABEL_W - 8, 16).build());
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
        List<Item> custom = new ArrayList<>(palette.source() == BlockPalette.Source.CUSTOM ? palette.custom() : List.of());
        if (custom.size() >= BlockPalette.MAX_CUSTOM) return;
        custom.add(item);
        palette = palette.withSource(BlockPalette.Source.CUSTOM).withCustom(custom);
        apply();
        rebuildWidgets();
    }

    private void load(BlockPalette p) {
        palette = p;
        enabled = true;
        apply();
        rebuildWidgets();
    }

    private void saveFavorite() {
        List<Item> blocks = currentBlocks();
        if (blocks.isEmpty()) return;
        // Name after the first two blocks, plus how many more
        String name = blocks.getFirst().getDescription().getString()
                + (blocks.size() > 1 ? " + " + blocks.get(1).getDescription().getString() : "")
                + (blocks.size() > 2 ? " +" + (blocks.size() - 2) : "");
        // A hotbar palette is saved as the blocks it holds right now
        PaletteClientState.saveFavorite(name, palette.withSource(BlockPalette.Source.CUSTOM).withCustom(blocks));
        tab = Tab.FAVORITES;
        rebuildWidgets();
    }

    /** Runs a strategy from the palette's blocks (or the held block when it has none). */
    private void suggest(Mode mode) {
        lastMode = mode;
        Map<Item, Integer> colors = BlockColorCache.colors();
        List<Swatch> start = new ArrayList<>();
        for (Item item : currentBlocks()) {
            Integer rgb = colors.get(item);
            if (rgb != null) start.add(swatch(item, rgb));
        }
        if (start.isEmpty() && minecraft != null && minecraft.player != null) {
            Item held = minecraft.player.getMainHandItem().getItem();
            if (colors.containsKey(held)) start.add(swatch(held, colors.get(held)));
        }
        // Suggestions never offer copies of the same block
        List<Swatch> all = new ArrayList<>();
        BlockColorCache.distinctColors().forEach((item, rgb) -> all.add(swatch(item, rgb)));
        if (all.isEmpty() || start.isEmpty() && mode != Mode.SURPRISE) {
            suggestion = List.of();
            suggestionNote = I18n.get(all.isEmpty() ? "effortlessbuilding.screen.palette_scanning"
                    : "effortlessbuilding.screen.suggest.need_seed");
        } else {
            if (start.isEmpty()) start.add(all.getFirst());
            List<Item> out = new ArrayList<>();
            for (Swatch w : PaletteSuggester.suggest(mode, start, all, suggestCount, rerollSeed)) {
                BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(w.id())).ifPresent(out::add);
            }
            suggestion = out;
            suggestionNote = "";
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
        int x = panelX() + 6;

        if (palette.source() == BlockPalette.Source.HOTBAR) {
            int slot = slotAt(mouseX, mouseY, x, slotsY(), Inventory.getSelectionSize(), Inventory.getSelectionSize());
            if (slot >= 0) {
                palette = palette.withHotbarSlots(palette.hotbarSlots() ^ (1 << slot));
                apply();
                return true;
            }
        } else {
            int i = slotAt(mouseX, mouseY, x, slotsY(), 8, palette.custom().size());
            if (i >= 0) {
                List<Item> custom = new ArrayList<>(palette.custom());
                custom.remove(i);
                palette = palette.withCustom(custom);
                apply();
                rebuildWidgets();
                return true;
            }
        }

        // Clicking a suggested block adds just that one
        int si = slotAt(mouseX, mouseY, suggestionX(), panelY() + 62, SUGGEST_COUNT_MAX, suggestion.size());
        if (si >= 0) {
            addCustom(suggestion.get(si));
            return true;
        }

        if (inPreview(mouseX, mouseY)) {
            dragging = true;
            return true;
        }

        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        switch (tab) {
            case ALL -> {
                Item item = gridItemAt(mouseX, mouseY);
                if (item != null) {
                    addCustom(item);
                    return true;
                }
            }
            case FAVORITES, PRESETS -> {
                int row = listRowAt(mouseX, mouseY);
                if (row >= 0) {
                    if (tab == Tab.FAVORITES) {
                        var favorites = PaletteClientState.getFavorites();
                        if (row < favorites.size()) load(favorites.get(row).palette());
                    } else {
                        var presets = PalettePresets.get();
                        if (row < presets.size()) load(presets.get(row).toPalette(palette));
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            yaw += (float) dragX * 0.9f;
            pitch = Math.max(-89, Math.min(89, pitch + (float) dragY * 0.9f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (widgets.handleScroll(mouseX, mouseY, scrollY)) return true;
        if (inPreview(mouseX, mouseY)) {
            zoom = Math.max(0.4f, Math.min(4f, zoom * (scrollY > 0 ? 1.15f : 1 / 1.15f)));
            return true;
        }
        if (mouseX > rightX()) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
            rebuildWidgets();
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

        Item hovered = renderLeft(g, mouseX, mouseY);
        Item hoveredRight = renderRight(g, mouseX, mouseY);
        if (hoveredRight != null) hovered = hoveredRight;
        if (hovered != null) g.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
    }

    private @Nullable Item renderLeft(GuiGraphics g, int mouseX, int mouseY) {
        int py = panelY(), x = panelX() + 6;
        Item hovered = null;
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_source"), x, sourceY() + 4, 0xCCCCCC);
        int y = slotsY();
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
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_pattern"), x, patternY() + 4, 0xCCCCCC);
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_band"), x, patternY() + 26, 0xCCCCCC);

        // 3D preview from the real blocks
        int[] r = previewRect();
        g.drawString(font, I18n.get("effortlessbuilding.screen.palette_preview"), r[0], r[1] - 10, 0xAAAAAA);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF1B1D22);
        List<Item> blocks = currentBlocks();
        if (blocks.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.palette_empty"), r[0] + r[2] / 2, r[1] + r[3] / 2 - 4, 0xFF8888);
        } else {
            renderBlocks3D(g, r, blocks);
        }
        if (!enabled) g.drawString(font, I18n.get("effortlessbuilding.screen.palette_off_hint"), x, py + panelH - 18, 0xFFAA66);
        return hovered;
    }

    /** A floor and two walls built from the palette with its pattern, lit like items in the inventory. */
    private void renderBlocks3D(GuiGraphics g, int[] r, List<Item> blocks) {
        if (minecraft == null) return;
        int size = 6, height = 5;
        List<int[]> cells = new ArrayList<>();
        for (int a = 0; a < size; a++) for (int b = 0; b < size; b++) cells.add(new int[]{a, 0, b}); // floor
        for (int yy = 1; yy < height; yy++) {
            for (int a = 0; a < size; a++) cells.add(new int[]{a, yy, 0});      // back wall
            for (int b = 1; b < size; b++) cells.add(new int[]{0, yy, b});      // side wall
        }

        float scale = zoom * Math.min(r[2], r[3]) / 11f;
        g.enableScissor(r[0], r[1], r[0] + r[2], r[1] + r[3]);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(r[0] + r[2] / 2f, r[1] + r[3] / 2f, 120);
        // Like vanilla item rendering: y flipped for the GUI. Depth squashed to stay above the background.
        pose.scale(scale, -scale, Math.min(scale, 12f));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-size / 2f, -height / 2f, -size / 2f);
        Lighting.setupFor3DItems();
        var dispatcher = minecraft.getBlockRenderer();
        VertexConsumer buffer = g.bufferSource().getBuffer(Sheets.cutoutBlockSheet());
        Map<Item, float[]> tints = new HashMap<>();
        for (int[] c : cells) {
            int index = palette.pattern().index(blocks.size(), palette.band(), c[0], c[1], c[2], 0, height - 1,
                    BlockPos.asLong(c[0], c[1], c[2]));
            Item item = blocks.get(index);
            BlockState state = ((BlockItem) item).getBlock().defaultBlockState();
            float[] tint = tints.computeIfAbsent(item, i -> blockTint(state));
            pose.pushPose();
            pose.translate(c[0], c[1], c[2]);
            dispatcher.getModelRenderer().renderModel(pose.last(), buffer, state, dispatcher.getBlockModel(state),
                    tint[0], tint[1], tint[2], LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        g.flush();
        pose.popPose();
        g.disableScissor();
    }

    /** Grass, leaves and similar blocks are grey until tinted; use their default tint. */
    private float[] blockTint(BlockState state) {
        int color = -1;
        try {
            if (minecraft != null) color = minecraft.getBlockColors().getColor(state, null, null, 0);
        } catch (RuntimeException ignored) {
            // Tint needs a level
        }
        if (color == -1) return new float[]{1, 1, 1};
        return new float[]{((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
    }

    private @Nullable Item renderRight(GuiGraphics g, int mouseX, int mouseY) {
        int py = panelY(), rx = rightX();
        Item hovered = null;
        String status = BlockColorCache.isScanning() ? I18n.get("effortlessbuilding.screen.palette_scanning")
                : I18n.get("effortlessbuilding.screen.palette_block_count",
                (hideCopies ? BlockColorCache.distinctColors() : BlockColorCache.colors()).size());
        g.drawString(font, status, rescanX() + 62, py + 24, 0xAAAAAA);

        // Count, then the current suggestion
        int ry = py + 64;
        g.drawCenteredString(font, String.valueOf(suggestCount), rx + 24, ry + 4, 0xFFFFFF);
        g.drawString(font, I18n.get("effortlessbuilding.screen.suggest.blocks"), rx + 51, ry + 4, 0xAAAAAA);
        int sx0 = suggestionX();
        if (suggestion.isEmpty()) {
            g.drawString(font, suggestionNote.isEmpty() ? I18n.get("effortlessbuilding.screen.suggest.hint") : suggestionNote,
                    sx0, ry + 4, 0x888888);
        } else {
            for (int i = 0; i < suggestion.size(); i++) {
                int sx = sx0 + i * SLOT, sy = ry - 2;
                g.fill(sx, sy, sx + SLOT - 2, sy + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(suggestion.get(i)));
                g.renderItem(new ItemStack(suggestion.get(i)), sx + 1, sy + 1);
                if (hit(mouseX, mouseY, sx, sy)) hovered = suggestion.get(i);
            }
        }

        int top = contentTop();
        switch (tab) {
            case ALL -> {
                List<Item> grid = filteredBlocks();
                int cols = gridCols(), rows = gridRows();
                for (int i = 0; i < cols * rows; i++) {
                    int index = i + scroll * cols;
                    if (index >= grid.size()) break;
                    Item item = grid.get(index);
                    int sx = rx + (i % cols) * SLOT, sy = top + (i / cols) * SLOT;
                    g.fill(sx, sy, sx + SLOT - 2, sy + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(item));
                    g.renderItem(new ItemStack(item), sx + 1, sy + 1);
                    if (hit(mouseX, mouseY, sx, sy)) hovered = item;
                }
                if (grid.isEmpty()) {
                    g.drawString(font, I18n.get(BlockColorCache.isScanning() ? "effortlessbuilding.screen.palette_scanning"
                            : "effortlessbuilding.screen.palette_no_match"), rx, top + 4, 0x888888);
                }
            }
            case FAVORITES -> {
                List<PaletteClientState.Favorite> favorites = PaletteClientState.getFavorites();
                if (favorites.isEmpty()) g.drawString(font, I18n.get("effortlessbuilding.screen.palette_no_favorites"), rx, top + 4, 0x888888);
                for (int row = 0; row < listRows(); row++) {
                    int i = row + scroll;
                    if (i >= favorites.size()) break;
                    Item h = renderListRow(g, mouseX, mouseY, row, favorites.get(i).name(), favorites.get(i).palette().custom());
                    if (h != null) hovered = h;
                }
            }
            case PRESETS -> {
                List<PalettePresets.Preset> presets = PalettePresets.get();
                for (int row = 0; row < listRows(); row++) {
                    int i = row + scroll;
                    if (i >= presets.size()) break;
                    Item h = renderListRow(g, mouseX, mouseY, row, presets.get(i).name(), presets.get(i).blocks());
                    if (h != null) hovered = h;
                }
            }
        }
        return hovered;
    }

    /** One favorite/preset row: name, then its blocks. Returns the hovered block, if any. */
    private @Nullable Item renderListRow(GuiGraphics g, int mouseX, int mouseY, int row, String name, List<Item> blocks) {
        int rx = rightX(), y = contentTop() + row * LIST_ROW_H;
        if (listRowAt(mouseX, mouseY) == row + scroll) g.fill(rx - 2, y, panelX() + panelW - 24, y + LIST_ROW_H - 2, 0x30FFFFFF);
        g.drawString(font, font.plainSubstrByWidth(name, 130), rx, y + 6, 0xFFD37F);
        Item hovered = null;
        int bx = rx + 136;
        for (int i = 0; i < blocks.size() && bx + SLOT < panelX() + panelW - 24; i++, bx += SLOT) {
            g.fill(bx, y + 1, bx + SLOT - 2, y + SLOT - 1, 0xFF000000 | BlockColorCache.colorOf(blocks.get(i)));
            g.renderItem(new ItemStack(blocks.get(i)), bx + 1, y + 2);
            if (hit(mouseX, mouseY, bx, y + 1)) hovered = blocks.get(i);
        }
        return hovered;
    }

    private static boolean hit(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SLOT - 2 && mouseY >= y && mouseY < y + SLOT - 2;
    }

    // =========================================================================
    // Block grid and lists
    // =========================================================================

    /** Blocks by color (greys by lightness first, then around the wheel), searched; recomputed only on change. */
    private List<Item> filteredBlocks() {
        Map<Item, Integer> colors = hideCopies ? BlockColorCache.distinctColors() : BlockColorCache.colors();
        Object key = List.of(System.identityHashCode(colors), colors.size(), search, hideCopies);
        if (!key.equals(gridKey)) {
            String q = search.toLowerCase(Locale.ROOT).trim();
            List<Item> list = new ArrayList<>();
            for (Item item : colors.keySet()) {
                if (q.isEmpty() || BuiltInRegistries.ITEM.getKey(item).toString().contains(q)
                        || item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)) list.add(item);
            }
            list.sort(Comparator.comparingDouble(item -> hueKey(colors.get(item))));
            gridCache = list;
            gridKey = key;
        }
        return gridCache;
    }

    private static double hueKey(int rgb) {
        double[] lch = PaletteSuggester.lch(PaletteSuggester.lab(rgb));
        if (lch[1] < 10) return lch[0] / 100.0; // greys: 0..1 by lightness
        // Hue bands of 15°, each running dark to light
        return 1 + Math.floor(lch[2] / 15) + lch[0] / 101.0;
    }

    private @Nullable Item gridItemAt(double mouseX, double mouseY) {
        int gx = rightX(), gy = contentTop();
        if (mouseX < gx || mouseY < gy) return null;
        int col = (int) (mouseX - gx) / SLOT, row = (int) (mouseY - gy) / SLOT;
        if (col >= gridCols() || row >= gridRows()) return null;
        int index = (row + scroll) * gridCols() + col;
        List<Item> grid = filteredBlocks();
        return index < grid.size() ? grid.get(index) : null;
    }

    /** List index under the mouse on the favorites/presets tabs, or -1 (not over the delete button). */
    private int listRowAt(double mouseX, double mouseY) {
        int top = contentTop();
        if (mouseX < rightX() - 2 || mouseX > panelX() + panelW - 24 || mouseY < top) return -1;
        int row = (int) (mouseY - top) / LIST_ROW_H;
        return row < listRows() ? row + scroll : -1;
    }

    private int maxScroll() {
        return switch (tab) {
            case ALL -> Math.max(0, (filteredBlocks().size() + gridCols() - 1) / gridCols() - gridRows());
            case FAVORITES -> Math.max(0, PaletteClientState.getFavorites().size() - listRows());
            case PRESETS -> Math.max(0, PalettePresets.get().size() - listRows());
        };
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }
    private int rightX() { return panelX() + LEFT_W + 14; }
    private int rescanX() { return rightX() + 162 + font.width("☐ " + I18n.get("effortlessbuilding.screen.palette_hide_copies")); }
    private int suggestionX() { return rightX() + 146; }
    private int contentTop() { return panelY() + 110; }
    private int gridCols() { return Math.max(1, (panelW - LEFT_W - 20) / SLOT); }
    private int gridRows() { return Math.max(1, (panelY() + panelH - 26 - contentTop()) / SLOT); }
    private int listRows() { return Math.max(1, (panelY() + panelH - 26 - contentTop()) / LIST_ROW_H); }

    private int sourceY() { return panelY() + 40; }
    /** Top of the hotbar slots / picked blocks. */
    private int slotsY() { return sourceY() + 22; }

    /** Pattern row: below the slots (and, for picked blocks, their buttons). */
    private int patternY() {
        return slotsY() + (palette.source() == BlockPalette.Source.HOTBAR ? SLOT + 14 : customRows() * SLOT + 26);
    }

    /** x, y, width, height of the 3D preview: the rest of the left column. */
    private int[] previewRect() {
        int x = panelX() + 6, y = patternY() + 80;
        int bottom = panelY() + panelH - (enabled ? 26 : 38);
        return new int[]{x, y, LEFT_W - 12, Math.max(40, bottom - y)};
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int[] r = previewRect();
        return mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
    }

    private int customRows() {
        return Math.max(1, (palette.custom().size() + 7) / 8);
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

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

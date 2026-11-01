package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
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
import nl.requios.effortlessbuilding.utilities.ShareCode;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.IntConsumer;

import static nl.requios.effortlessbuilding.screen.Ui.*;

/**
 * Block palette: build with several blocks at once, mixed in a pattern.
 * <p>
 * Left: on/off, where the blocks come from (hotbar slots or a picked list), pattern, band width,
 * save as favorite, and a rotatable 3D preview built from the real blocks.
 * Right: suggestions (eight color strategies, rerollable), then tabs for all blocks by color,
 * saved favorites and ready-made presets.
 */
public class PaletteScreen extends BaseOwoScreen<FlowLayout> {

    private static final int SLOT = 20;
    private static final int LEFT_W = 214;
    private static final int SUGGEST_COUNT_MAX = 9;

    private enum Tab { ALL, FAVORITES, PRESETS }

    private final Screen parent;
    private BlockPalette palette;
    private boolean enabled;

    // ---- right side state ----
    private Tab tab = Tab.ALL;
    private final BlockGrid grid = new BlockGrid();
    private int suggestCount = 5;
    private Mode lastMode = null;
    private long rerollSeed = 0;
    private List<Item> suggestion = List.of();
    private String suggestionNote = "";

    // ---- 3D preview camera ----
    private float yaw = -35, pitch = 25, zoom = 1;

    // ---- containers refreshed on change ----
    private FlowLayout root, left, suggestionRow, tabs, content;
    private LabelComponent status;
    private String shownStatus = "";

    public PaletteScreen(@Nullable Screen parent) {
        super(Component.translatable("effortlessbuilding.screen.palette"));
        this.parent = parent;
        this.palette = PaletteClientState.getPalette();
        this.enabled = PaletteClientState.isEnabled();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    // =========================================================================
    // Build
    // =========================================================================

    @Override
    protected void build(FlowLayout root) {
        this.root = root;
        BlockColorCache.ensureReady();
        root.surface(Surface.flat(BACKGROUND));
        root.padding(Insets.of(8));

        FlowLayout top = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(6).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.bottom(6));
        top.child(label(title.copy().withStyle(s -> s.withBold(true)), 0xFFFFFF));
        top.child(hspace());
        top.child(w(Components.button(Component.translatable("effortlessbuilding.screen.copy_code"), b -> copyCode()))
                .tooltip(Component.translatable("effortlessbuilding.screen.copy_palette_code.description")));
        top.child(w(Components.button(Component.translatable("effortlessbuilding.screen.paste_code"), b -> pasteCode()))
                .tooltip(Component.translatable("effortlessbuilding.screen.paste_code.description")));
        root.child(top);

        FlowLayout body = Containers.horizontalFlow(Sizing.fill(100), Sizing.expand());
        body.gap(6);
        left = panel(Containers.verticalFlow(Sizing.fixed(LEFT_W), Sizing.fill(100)));
        left.gap(4);
        body.child(left);
        body.child(buildRight());
        root.child(body);

        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.verticalAlignment(VerticalAlignment.CENTER).margins(Insets.top(6));
        bottom.child(hspace());
        bottom.child(w(Components.button(Component.translatable("gui.done"), b -> onClose())).horizontalSizing(Sizing.fixed(70)));
        root.child(bottom);

        refresh();
    }

    private FlowLayout buildRight() {
        FlowLayout right = panel(Containers.verticalFlow(Sizing.expand(), Sizing.fill(100)));
        right.gap(5);

        // Search, hide copies, rescan, status
        FlowLayout searchRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        searchRow.gap(6).verticalAlignment(VerticalAlignment.CENTER);
        TextBoxComponent search = Components.textBox(Sizing.fixed(150));
        search.setHint(Component.translatable("effortlessbuilding.screen.palette_search"));
        search.text(grid.search());
        search.onChanged().subscribe(s -> {
            grid.setSearch(s);
            if (tab != Tab.ALL) { tab = Tab.ALL; refresh(); }
        });
        searchRow.child(w(search));
        ButtonComponent copies = Components.button(Component.empty(), b -> {});
        copies.onPress(b -> {
            grid.toggleHideCopies();
            b.setMessage(hideCopiesText());
        });
        copies.setMessage(hideCopiesText());
        searchRow.child(w(copies));
        searchRow.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_rescan"), b -> BlockColorCache.scan()))
                .tooltip(Component.translatable("effortlessbuilding.screen.palette_rescan.description")));
        status = label(Component.empty(), 0xAAAAAA);
        searchRow.child(status);
        right.child(searchRow);

        // Suggestion strategies (wrap onto more lines when narrow)
        FlowLayout modes = Containers.ltrTextFlow(Sizing.fill(100), Sizing.content());
        modes.gap(3);
        for (Mode mode : Mode.values()) {
            modes.child(w(Components.button(Component.translatable(mode.getNameKey()), b -> {
                rerollSeed = 0;
                suggest(mode);
            })).tooltip(Component.translatable(mode.getNameKey() + ".description")));
        }
        right.child(modes);

        suggestionRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        suggestionRow.gap(4).verticalAlignment(VerticalAlignment.CENTER);
        right.child(suggestionRow);

        tabs = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        tabs.gap(3).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.top(4));
        right.child(tabs);

        content = Containers.verticalFlow(Sizing.fill(100), Sizing.expand());
        right.child(content);
        return right;
    }

    private Component hideCopiesText() {
        return Component.literal((grid.hideCopies() ? "☑ " : "☐ ") + I18n.get("effortlessbuilding.screen.palette_hide_copies"));
    }

    /** Rebuilds everything that depends on the palette, the suggestion or the tab. */
    private void refresh() {
        fillLeft();
        fillSuggestion();
        fillTabs();
        fillContent();
    }

    // =========================================================================
    // Left: on/off, source, blocks, pattern, preview
    // =========================================================================

    private void fillLeft() {
        left.clearChildren();
        left.child(settingRow("effortlessbuilding.screen.palette_enabled", w(Components.button(
                Component.translatable(enabled ? "options.on" : "options.off"), b -> { enabled = !enabled; apply(); refresh(); }))
                .sizing(Sizing.fixed(110), Sizing.fixed(16))));
        left.child(settingRow("effortlessbuilding.screen.palette_source", w(choice(this, root, I18n.get(palette.source().getNameKey()),
                List.of(BlockPalette.Source.values()), s -> I18n.get(s.getNameKey()),
                s -> { palette = palette.withSource(s); apply(); }, this::refresh))
                .sizing(Sizing.fixed(110), Sizing.fixed(16)).tooltip(Component.translatable("effortlessbuilding.screen.palette_source.description"))));

        left.child(new SlotsComponent());
        if (palette.source() == BlockPalette.Source.HOTBAR) {
            left.child(label(Component.translatable("effortlessbuilding.screen.palette_hotbar_hint"), 0x888888).maxWidth(LEFT_W - 12));
        } else {
            if (palette.custom().isEmpty()) {
                left.child(label(Component.translatable("effortlessbuilding.screen.palette_custom_hint"), 0x888888).maxWidth(LEFT_W - 12));
            }
            FlowLayout buttons = Containers.horizontalFlow(Sizing.content(), Sizing.content());
            buttons.gap(4);
            buttons.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_add_held"), b -> addHeld())));
            buttons.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_clear"), b -> {
                palette = palette.withCustom(List.of());
                apply();
                refresh();
            })));
            left.child(buttons);
        }

        left.child(settingRow("effortlessbuilding.screen.palette_pattern", w(choice(this, root, I18n.get(palette.pattern().getNameKey()),
                List.of(PalettePattern.values()), p -> I18n.get(p.getNameKey()),
                p -> { palette = palette.withPattern(p); apply(); }, this::refresh))
                .sizing(Sizing.fixed(110), Sizing.fixed(16)).tooltip(Component.translatable("effortlessbuilding.screen.palette_pattern.description"))));
        left.child(settingRow("effortlessbuilding.screen.palette_band", bandField()));
        left.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_save_favorite"), b -> saveFavorite()))
                .horizontalSizing(Sizing.fill(100))
                .tooltip(Component.translatable("effortlessbuilding.screen.palette_save_favorite.description")));

        left.child(label(Component.translatable("effortlessbuilding.screen.palette_preview"), 0xAAAAAA).maxWidth(LEFT_W - 12).margins(Insets.top(4)));
        left.child(new PreviewComponent().sizing(Sizing.fill(100), Sizing.expand()));
        if (!enabled) left.child(label(Component.translatable("effortlessbuilding.screen.palette_off_hint"), 0xFFAA66).maxWidth(LEFT_W - 12));
    }

    private FlowLayout settingRow(String labelKey, io.wispforest.owo.ui.core.Component control) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(18));
        row.verticalAlignment(VerticalAlignment.CENTER);
        row.child(label(Component.translatable(labelKey), 0xCCCCCC).horizontalSizing(Sizing.fixed(LEFT_W - 12 - 112)));
        row.child(control);
        return row;
    }

    private io.wispforest.owo.ui.core.Component bandField() {
        FlowLayout flow = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        flow.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        TextBoxComponent box = Components.textBox(Sizing.fixed(40));
        w(box).verticalSizing(Sizing.fixed(16));
        box.text(String.valueOf(palette.band()));
        box.setFilter(s -> s.matches("\\d*"));
        box.onChanged().subscribe(s -> {
            try {
                palette = palette.withBand(Integer.parseInt(s));
                apply();
            } catch (NumberFormatException ignored) {
            }
        });
        IntConsumer step = d -> box.text(String.valueOf(Math.max(1, palette.band() + d)));
        flow.child(w(Components.button(Component.literal("−"), b -> step.accept(-1))).sizing(Sizing.fixed(14), Sizing.fixed(16)));
        flow.child(w(box));
        flow.child(w(Components.button(Component.literal("+"), b -> step.accept(1))).sizing(Sizing.fixed(14), Sizing.fixed(16)));
        w(box).mouseScroll().subscribe((x, y, amount) -> { step.accept(amount > 0 ? 1 : -1); return true; });
        return flow;
    }

    // =========================================================================
    // Right: suggestion, tabs, content
    // =========================================================================

    private void fillSuggestion() {
        suggestionRow.clearChildren();
        suggestionRow.child(w(Components.button(Component.literal("−"), b -> { suggestCount = Math.max(2, suggestCount - 1); fillSuggestion(); }))
                .sizing(Sizing.fixed(14), Sizing.fixed(16)));
        suggestionRow.child(label(Component.literal(String.valueOf(suggestCount)), 0xFFFFFF));
        suggestionRow.child(w(Components.button(Component.literal("+"), b -> { suggestCount = Math.min(SUGGEST_COUNT_MAX, suggestCount + 1); fillSuggestion(); }))
                .sizing(Sizing.fixed(14), Sizing.fixed(16)));
        suggestionRow.child(label(Component.translatable("effortlessbuilding.screen.suggest.blocks"), 0xAAAAAA));
        ButtonComponent reroll = Components.button(Component.translatable("effortlessbuilding.screen.palette_reroll"), b -> {
            rerollSeed = (rerollSeed * 31 + System.nanoTime()) | 1;
            if (lastMode != null) suggest(lastMode);
        });
        reroll.active(lastMode != null);
        suggestionRow.child(w(reroll).tooltip(Component.translatable("effortlessbuilding.screen.palette_reroll.description")));
        if (suggestion.isEmpty()) {
            suggestionRow.child(label(Component.literal(suggestionNote.isEmpty() ? I18n.get("effortlessbuilding.screen.suggest.hint") : suggestionNote), 0x888888));
        } else {
            // Clicking a suggested block adds just that one
            suggestionRow.child(new SwatchesComponent(suggestion, i -> addCustom(suggestion.get(i))));
            suggestionRow.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_use_suggestion"), b -> {
                palette = palette.withSource(BlockPalette.Source.CUSTOM).withCustom(suggestion);
                enabled = true;
                apply();
                refresh();
            })));
        }
    }

    private void fillTabs() {
        tabs.clearChildren();
        for (Tab t : Tab.values()) {
            String text = I18n.get("effortlessbuilding.screen.palette_tab." + t.name().toLowerCase());
            if (t == Tab.FAVORITES) text += " (" + PaletteClientState.getFavorites().size() + ")";
            if (t == Tab.PRESETS) text += " (" + PalettePresets.get().size() + ")";
            ButtonComponent b = Components.button(Component.literal(text), btn -> { tab = t; refresh(); });
            b.active(tab != t);
            tabs.child(w(b));
        }
        if (tab == Tab.PRESETS) {
            tabs.child(w(Components.button(Component.translatable("effortlessbuilding.screen.palette_reload_presets"), b -> {
                PalettePresets.reload();
                refresh();
            })).margins(Insets.left(8)).tooltip(Component.translatable("effortlessbuilding.screen.palette_reload_presets.description")));
        }
    }

    private void fillContent() {
        content.clearChildren();
        switch (tab) {
            case ALL -> content.child(new GridComponent().sizing(Sizing.fill(100), Sizing.fill(100)));
            case FAVORITES -> {
                FlowLayout list = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
                list.gap(2);
                List<PaletteClientState.Favorite> favorites = PaletteClientState.getFavorites();
                if (favorites.isEmpty()) list.child(label(Component.translatable("effortlessbuilding.screen.palette_no_favorites"), 0x888888));
                for (PaletteClientState.Favorite f : favorites) {
                    list.child(paletteRow(f.name(), f.palette().custom(), () -> load(f.palette()), () -> {
                        PaletteClientState.deleteFavorite(f.name());
                        refresh();
                    }));
                }
                content.child(Containers.verticalScroll(Sizing.fill(100), Sizing.fill(100), list).scrollbarThiccness(3));
            }
            case PRESETS -> {
                FlowLayout list = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
                list.gap(2);
                for (PalettePresets.Preset p : PalettePresets.get()) {
                    list.child(paletteRow(p.name(), p.blocks(), () -> load(p.toPalette(palette)), null));
                }
                content.child(Containers.verticalScroll(Sizing.fill(100), Sizing.fill(100), list).scrollbarThiccness(3));
            }
        }
    }

    /** A favorite or preset: its name and blocks; click loads it. */
    private FlowLayout paletteRow(String name, List<Item> blocks, Runnable load, @Nullable Runnable delete) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(SLOT + 2));
        row.gap(6).verticalAlignment(VerticalAlignment.CENTER).padding(Insets.horizontal(4));
        row.surface(Surface.flat(0x14FFFFFF));
        row.mouseEnter().subscribe(() -> row.surface(Surface.flat(0x30FFFFFF)));
        row.mouseLeave().subscribe(() -> row.surface(Surface.flat(0x14FFFFFF)));
        row.mouseDown().subscribe((x, y, button) -> {
            if (button != 0) return false;
            load.run();
            return true;
        });
        LabelComponent label = label(Component.literal(name), ACCENT);
        label.horizontalSizing(Sizing.fixed(130));
        row.child(label);
        row.child(new SwatchesComponent(blocks, null));
        if (delete != null) {
            row.child(hspace());
            row.child(w(Components.button(Component.literal("×"), b -> delete.run())).sizing(Sizing.fixed(14), Sizing.fixed(14))
                    .tooltip(Component.translatable("effortlessbuilding.screen.palette_delete_favorite")));
        }
        return row;
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
        fillLeft();
    }

    private void load(BlockPalette p) {
        palette = p;
        enabled = true;
        apply();
        refresh();
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
        refresh();
    }

    private void copyCode() {
        if (minecraft == null) return;
        minecraft.keyboardHandler.setClipboard(ShareCode.encode(ShareCode.PALETTE,
                PaletteClientState.toJson(palette.withSource(BlockPalette.Source.CUSTOM).withCustom(currentBlocks()))));
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.code_copied"), true);
    }

    private void pasteCode() {
        if (minecraft == null) return;
        var json = ShareCode.decode(ShareCode.PALETTE, minecraft.keyboardHandler.getClipboard());
        try {
            if (json == null) throw new IllegalArgumentException();
            load(PaletteClientState.fromJson(json));
        } catch (RuntimeException e) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.code_invalid"), true);
        }
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
        fillSuggestion();
    }

    private static Swatch swatch(Item item, int rgb) {
        return new Swatch(BuiltInRegistries.ITEM.getKey(item).toString(), rgb);
    }

    private List<Item> currentBlocks() {
        return minecraft != null && minecraft.player != null ? palette.resolve(minecraft.player) : palette.custom();
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Only touch the label when its text changes (each change re-lays out the screen)
        String text = BlockColorCache.isScanning() ? I18n.get("effortlessbuilding.screen.palette_scanning")
                : I18n.get("effortlessbuilding.screen.palette_block_count", grid.items().size());
        if (status != null && !text.equals(shownStatus)) { shownStatus = text; status.text(Component.literal(text)); }
        super.render(g, mouseX, mouseY, partialTick);
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

    // =========================================================================
    // Components
    // =========================================================================

    /** A component that shows the block under the mouse as an item tooltip. */
    private abstract static class ItemTooltips extends BaseComponent {
        protected @Nullable Item hoveredItem;

        @Override
        public boolean shouldDrawTooltip(double mouseX, double mouseY) {
            return hoveredItem != null && isInBoundingBox(mouseX, mouseY);
        }

        @Override
        public void drawTooltip(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            if (hoveredItem != null) context.renderTooltip(net.minecraft.client.Minecraft.getInstance().font, new ItemStack(hoveredItem), mouseX, mouseY);
        }

        protected boolean over(int mouseX, int mouseY, int sx, int sy) {
            return mouseX >= sx && mouseX < sx + SLOT - 2 && mouseY >= sy && mouseY < sy + SLOT - 2;
        }
    }

    /** Hotbar slots to toggle, or the picked blocks (click one to remove it). */
    private class SlotsComponent extends ItemTooltips {
        private static final int PER_ROW = 8;

        SlotsComponent() {
            int rows = palette.source() == BlockPalette.Source.HOTBAR ? 1 : Math.max(1, (palette.custom().size() + PER_ROW - 1) / PER_ROW);
            int cols = palette.source() == BlockPalette.Source.HOTBAR ? Inventory.getSelectionSize() : PER_ROW;
            sizing(Sizing.fixed(cols * SLOT), Sizing.fixed(rows * SLOT));
        }

        @Override
        public void draw(OwoUIDrawContext g, int mouseX, int mouseY, float partialTicks, float delta) {
            hoveredItem = null;
            if (palette.source() == BlockPalette.Source.HOTBAR) {
                for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                    int sx = x + slot * SLOT;
                    boolean on = palette.hasHotbarSlot(slot);
                    g.fill(sx, y, sx + SLOT - 2, y + SLOT - 2, on ? 0xFF2F6B2F : 0xFF303030);
                    if (on) g.renderOutline(sx, y, SLOT - 2, SLOT - 2, 0xFF7FD17F);
                    ItemStack stack = minecraft != null && minecraft.player != null ? minecraft.player.getInventory().getItem(slot) : ItemStack.EMPTY;
                    g.renderItem(stack, sx + 1, y + 1);
                    if (over(mouseX, mouseY, sx, y) && !stack.isEmpty()) hoveredItem = stack.getItem();
                }
            } else {
                List<Item> custom = palette.custom();
                for (int i = 0; i < Math.max(custom.size(), 1); i++) {
                    int sx = x + (i % PER_ROW) * SLOT, sy = y + (i / PER_ROW) * SLOT;
                    g.fill(sx, sy, sx + SLOT - 2, sy + SLOT - 2, 0xFF303030);
                    if (i < custom.size()) {
                        g.renderItem(new ItemStack(custom.get(i)), sx + 1, sy + 1);
                        if (over(mouseX, mouseY, sx, sy)) hoveredItem = custom.get(i);
                    }
                }
            }
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            int col = (int) mouseX / SLOT, row = (int) mouseY / SLOT;
            if (palette.source() == BlockPalette.Source.HOTBAR) {
                if (col >= 0 && col < Inventory.getSelectionSize()) {
                    palette = palette.withHotbarSlots(palette.hotbarSlots() ^ (1 << col));
                    apply();
                    return true;
                }
            } else {
                int i = row * PER_ROW + col;
                if (col < PER_ROW && i >= 0 && i < palette.custom().size()) {
                    List<Item> custom = new ArrayList<>(palette.custom());
                    custom.remove(i);
                    palette = palette.withCustom(custom);
                    apply();
                    fillLeft();
                    return true;
                }
            }
            return false;
        }
    }

    /** A row of blocks on their colors; optionally clickable. */
    private class SwatchesComponent extends ItemTooltips {
        private final List<Item> items;
        private final @Nullable IntConsumer click;

        SwatchesComponent(List<Item> items, @Nullable IntConsumer click) {
            this.items = items;
            this.click = click;
            sizing(Sizing.fixed(Math.max(1, items.size()) * SLOT), Sizing.fixed(SLOT));
        }

        @Override
        public void draw(OwoUIDrawContext g, int mouseX, int mouseY, float partialTicks, float delta) {
            hoveredItem = null;
            for (int i = 0; i < items.size(); i++) {
                int sx = x + i * SLOT;
                g.fill(sx, y, sx + SLOT - 2, y + SLOT - 2, 0xFF000000 | BlockColorCache.colorOf(items.get(i)));
                g.renderItem(new ItemStack(items.get(i)), sx + 1, y + 1);
                if (over(mouseX, mouseY, sx, y)) hoveredItem = items.get(i);
            }
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            int i = (int) mouseX / SLOT;
            if (click == null || i < 0 || i >= items.size()) return false;
            click.accept(i);
            return true;
        }
    }

    /** Every block, sorted by color; click adds one to the picked blocks. */
    private class GridComponent extends ItemTooltips {
        @Override
        public void draw(OwoUIDrawContext g, int mouseX, int mouseY, float partialTicks, float delta) {
            hoveredItem = grid.render(g, font, x, y, width, height, mouseX, mouseY,
                    I18n.get(BlockColorCache.isScanning() ? "effortlessbuilding.screen.palette_scanning" : "effortlessbuilding.screen.palette_no_match"));
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            Item item = grid.itemAt(x + mouseX, y + mouseY, x, y, width, height);
            if (item == null) return false;
            addCustom(item);
            return true;
        }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            grid.scroll(amount, width, height);
            return true;
        }
    }

    /** A floor and two walls built from the palette with its pattern, lit like items; drag to turn. */
    private class PreviewComponent extends BaseComponent {
        @Override
        public void draw(OwoUIDrawContext g, int mouseX, int mouseY, float partialTicks, float delta) {
            g.fill(x, y, x + width, y + height, 0xFF1B1D22);
            List<Item> blocks = currentBlocks();
            if (blocks.isEmpty()) {
                // Wrapped to the preview's width, centered
                var lines = font.split(Component.translatable("effortlessbuilding.screen.palette_empty"), width - 8);
                int ty = y + height / 2 - lines.size() * 5;
                for (var line : lines) {
                    g.drawString(font, line, x + (width - font.width(line)) / 2, ty, 0xFF8888);
                    ty += 10;
                }
            } else {
                renderBlocks(g, blocks);
            }
        }

        private void renderBlocks(OwoUIDrawContext g, List<Item> blocks) {
            if (minecraft == null) return;
            int size = 6, height3d = 5;
            List<int[]> cells = new ArrayList<>();
            for (int a = 0; a < size; a++) for (int b = 0; b < size; b++) cells.add(new int[]{a, 0, b}); // floor
            for (int yy = 1; yy < height3d; yy++) {
                for (int a = 0; a < size; a++) cells.add(new int[]{a, yy, 0});      // back wall
                for (int b = 1; b < size; b++) cells.add(new int[]{0, yy, b});      // side wall
            }
            float scale = zoom * Math.min(width, height) / 11f;
            g.enableScissor(x, y, x + width, y + height);
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate(x + width / 2f, y + height / 2f, 120);
            // Like vanilla item rendering: y flipped for the GUI. Depth squashed to stay above the background.
            pose.scale(scale, -scale, Math.min(scale, 12f));
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-size / 2f, -height3d / 2f, -size / 2f);
            Lighting.setupFor3DItems();
            var dispatcher = minecraft.getBlockRenderer();
            VertexConsumer buffer = g.bufferSource().getBuffer(Sheets.cutoutBlockSheet());
            Map<Item, float[]> tints = new HashMap<>();
            for (int[] c : cells) {
                int index = palette.pattern().index(blocks.size(), palette.band(), c[0], c[1], c[2], 0, height3d - 1,
                        BlockPos.asLong(c[0], c[1], c[2]));
                Item item = blocks.get(index);
                if (!(item instanceof BlockItem blockItem)) continue;
                BlockState state = blockItem.getBlock().defaultBlockState();
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
            // Menus and tooltips drawn later must not hide behind the blocks
            com.mojang.blaze3d.systems.RenderSystem.clear(256, net.minecraft.client.Minecraft.ON_OSX);
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

        @Override
        public boolean canFocus(FocusSource source) {
            return source == FocusSource.MOUSE_CLICK;
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            super.onMouseDown(mouseX, mouseY, button);
            return true;
        }

        @Override
        public boolean onMouseDrag(double mouseX, double mouseY, double deltaX, double deltaY, int button) {
            yaw += (float) deltaX * 0.9f;
            pitch = Math.max(-89, Math.min(89, pitch + (float) deltaY * 0.9f));
            return true;
        }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            zoom = Math.max(0.4f, Math.min(4f, zoom * (amount > 0 ? 1.15f : 1 / 1.15f)));
            return true;
        }
    }
}

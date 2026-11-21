package io.github.watdafawx.shapewright.screen;

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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import io.github.watdafawx.shapewright.palette.BlockColorCache;
import io.github.watdafawx.shapewright.shape.FloorDesigner;
import io.github.watdafawx.shapewright.shape.FloorThemes;
import io.github.watdafawx.shapewright.shape.SchematicLibrary;
import io.github.watdafawx.shapewright.shape.SchematicWriter;
import io.github.watdafawx.shapewright.shape.ShapeGenerator.Cell;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.github.watdafawx.shapewright.screen.Ui.w;

/**
 * Interactive multi-block Floor Designer: generates symmetrical mandalas, floral mosaics,
 * sunbursts, crescents, Celtic knots, parquet and gradients with 5 customizable block slots,
 * curated color themes, and 8-way symmetry touch-up painting. Saves as a schematic so all
 * block types build together in SHAPE mode.
 */
public class FloorDesignerScreen extends BaseOwoScreen<FlowLayout> {

    private static final int CONTROL_H = 16;
    private static final int SIDE_W = 216;
    private static final int LABEL_W = 68;
    private static final int FIELD_W = 128;

    private static final String[] SLOT_KEYS = {"base", "primary", "secondary", "trim", "accent"};

    private final Screen parent;
    private final Consumer<String> onCreated;
    private final Random rng = new Random();

    private FloorDesigner.Style style = FloorDesigner.Style.MANDALA;
    private FloorDesigner.Outline outline = FloorDesigner.Outline.QUATREFOIL;
    private int size = 33;
    private int symmetry = 8;
    private int complexity = 3;
    private int seed = 1;
    private boolean border = true;
    private int themeIndex = 0;
    private final String[] slotBlocks;
    private int activeBrush = FloorDesigner.PRIMARY;
    private boolean paintMirror = true;

    private int[][] grid;
    private final Map<Long, Integer> painted = new HashMap<>();

    private FlowLayout root;
    private TextBoxComponent nameBox;
    private String nameDraft = "mandala_floor";
    private LabelComponent infoLabel;

    public FloorDesignerScreen(Screen parent, Consumer<String> onCreated) {
        super(Component.translatable("shapewright.screen.floor_designer"));
        this.parent = parent;
        this.onCreated = onCreated;
        FloorThemes.reload();
        this.slotBlocks = FloorThemes.get().getFirst().blocks().clone();
        regenerate();
    }

    private static String themeLabel(FloorThemes.Theme t) {
        return t.displayName() != null ? t.displayName() : I18n.get(t.getNameKey());
    }

    private void regenerate() {
        grid = FloorDesigner.generate(new FloorDesigner.Config(style, outline, size, symmetry, complexity, seed, border));
        painted.clear();
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void init() {
        boolean reopened = uiAdapter != null;
        super.init();
        if (reopened && root != null) {
            root.clearChildren();
            build(root);
        }
    }

    @Override
    protected void build(FlowLayout root) {
        this.root = root;
        BlockColorCache.ensureReady();
        root.surface(Surface.flat(0xE6101010));
        root.padding(Insets.of(8));

        // Top bar
        FlowLayout top = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(8).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.bottom(6));
        top.child(Ui.label(title.copy().withStyle(s -> s.withBold(true)), 0xFFFFFF));
        infoLabel = Ui.label(Component.empty(), Ui.MUTED);
        top.child(infoLabel);
        top.child(Ui.hspace());
        ButtonComponent randomize = Components.button(Component.translatable("shapewright.floor_designer.randomize"), b -> {
            seed = rng.nextInt(1, 9999);
            regenerate();
            rebuildScreen();
        });
        top.child(w(randomize).sizing(Sizing.fixed(font.width(randomize.getMessage()) + 14), Sizing.fixed(CONTROL_H)));
        root.child(top);

        // Main body: left controls, right 2D canvas
        FlowLayout body = Containers.horizontalFlow(Sizing.fill(100), Sizing.expand());
        body.gap(6);

        FlowLayout controls = Containers.verticalFlow(Sizing.content(), Sizing.content());
        controls.gap(3).padding(Insets.right(6));

        controls.child(row("shapewright.floor_designer.style", choice(I18n.get(style.getNameKey()),
                FloorDesigner.Style.values(), s -> I18n.get(s.getNameKey()), s -> {
                    style = s;
                    nameDraft = s.key + "_floor";
                    regenerate();
                    rebuildScreen();
                })));
        controls.child(row("shapewright.floor_designer.outline", choice(I18n.get(outline.getNameKey()),
                FloorDesigner.Outline.values(), o -> I18n.get(o.getNameKey()), o -> {
                    outline = o;
                    regenerate();
                    rebuildScreen();
                })));
        controls.child(row("shapewright.floor_designer.size", intStep(size, 2, 11, 81, v -> {
            size = v | 1;
            regenerate();
            rebuildScreen();
        })));
        controls.child(row("shapewright.floor_designer.symmetry", choice(symmetry + "-way",
                new Integer[]{4, 8, 12}, n -> n + "-way", n -> {
                    symmetry = n;
                    regenerate();
                    rebuildScreen();
                })));
        controls.child(row("shapewright.floor_designer.complexity", intStep(complexity, 1, 1, 5, v -> {
            complexity = v;
            regenerate();
            rebuildScreen();
        })));
        controls.child(row("shapewright.floor_designer.seed", intStep(seed, 1, 1, 9999, v -> {
            seed = v;
            regenerate();
            rebuildScreen();
        })));
        controls.child(row("shapewright.floor_designer.border", toggle(border, () -> {
            border = !border;
            regenerate();
            rebuildScreen();
        })));

        controls.child(Ui.label(Component.translatable("shapewright.floor_designer.palette").withStyle(s -> s.withBold(true)), Ui.SECTION)
                .margins(Insets.of(6, 2, 0, 0)));
        List<FloorThemes.Theme> themes = FloorThemes.get();
        if (themeIndex >= themes.size()) themeIndex = 0;
        controls.child(row("shapewright.floor_designer.theme", choice(themeLabel(themes.get(themeIndex)),
                themes.toArray(new FloorThemes.Theme[0]), FloorDesignerScreen::themeLabel, t -> {
                    themeIndex = themes.indexOf(t);
                    System.arraycopy(t.blocks(), 0, slotBlocks, 0, slotBlocks.length);
                    rebuildScreen();
                })));

        for (int i = 0; i < SLOT_KEYS.length; i++) {
            int slot = i;
            controls.child(blockSlotRow(slot));
        }

        controls.child(row("shapewright.floor_designer.mirror_paint", toggle(paintMirror, () -> {
            paintMirror = !paintMirror;
            rebuildScreen();
        })).margins(Insets.top(4)));

        FlowLayout leftPanel = Ui.panel(Containers.verticalFlow(Sizing.fixed(SIDE_W), Sizing.fill(100)));
        leftPanel.child(Containers.verticalScroll(Sizing.fill(100), Sizing.fill(100), controls));
        body.child(leftPanel);

        FlowLayout canvasPanel = Ui.panel(Containers.verticalFlow(Sizing.expand(), Sizing.fill(100)));
        canvasPanel.child(new CanvasComponent().sizing(Sizing.fill(100), Sizing.expand()));
        body.child(canvasPanel);

        root.child(body);

        // Bottom bar: name, Use Floor, Cancel
        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.gap(4).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.top(6));
        nameBox = Components.textBox(Sizing.fixed(180));
        w(nameBox).verticalSizing(Sizing.fixed(20));
        nameBox.setMaxLength(40);
        nameBox.text(nameDraft);
        nameBox.onChanged().subscribe(s -> nameDraft = s);
        bottom.child(w(nameBox));
        bottom.child(Ui.hspace());

        ButtonComponent use = Components.button(Component.translatable("shapewright.floor_designer.use"), b -> saveAndUse());
        ButtonComponent cancel = Components.button(Component.translatable("gui.cancel"), b -> onClose());
        bottom.child(w(use).sizing(Sizing.fixed(Math.max(90, font.width(use.getMessage()) + 14)), Sizing.fixed(20)));
        bottom.child(w(cancel).sizing(Sizing.fixed(70), Sizing.fixed(20)));
        root.child(bottom);

        updateInfo();
    }

    private void rebuildScreen() {
        if (root == null) return;
        root.clearChildren();
        build(root);
    }

    private void updateInfo() {
        if (infoLabel == null || grid == null) return;
        int count = 0;
        for (int z = 0; z < grid.length; z++)
            for (int x = 0; x < grid.length; x++)
                if (cellAt(x, z) != FloorDesigner.EMPTY) count++;
        infoLabel.text(Component.literal(size + " × " + size + "  ·  " + count + " blocks"));
    }

    private int cellAt(int x, int z) {
        Integer over = painted.get(((long) z << 32) | (x & 0xFFFFFFFFL));
        return over != null ? over : grid[z][x];
    }

    private void paintCell(int x, int z, int value) {
        int n = grid.length;
        if (x < 0 || z < 0 || x >= n || z >= n) return;
        if (grid[z][x] == FloorDesigner.EMPTY) return;
        setPaint(x, z, value);
        if (paintMirror) {
            int mx = n - 1 - x, mz = n - 1 - z;
            setPaint(mx, z, value);
            setPaint(x, mz, value);
            setPaint(mx, mz, value);
            setPaint(z, x, value);
            setPaint(mz, x, value);
            setPaint(z, mx, value);
            setPaint(mz, mx, value);
        }
        updateInfo();
    }

    private void setPaint(int x, int z, int value) {
        if (grid[z][x] != FloorDesigner.EMPTY) {
            painted.put(((long) z << 32) | (x & 0xFFFFFFFFL), value);
        }
    }

    private void saveAndUse() {
        if (minecraft == null) return;
        int n = grid.length, half = n / 2;
        BlockState[] states = new BlockState[slotBlocks.length];
        for (int i = 0; i < slotBlocks.length; i++) {
            Item item = itemOf(slotBlocks[i]);
            if (item instanceof BlockItem bi) states[i] = bi.getBlock().defaultBlockState();
        }
        Map<Cell, BlockState> blocks = new HashMap<>();
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int idx = cellAt(x, z);
                if (idx >= 0 && idx < states.length && states[idx] != null) {
                    blocks.put(new Cell(x - half, 0, z - half), states[idx]);
                }
            }
        }
        String raw = nameBox != null && !nameBox.getValue().isBlank() ? nameBox.getValue() : style.key + "_floor";
        String name = SchematicWriter.safeName(raw);
        try {
            SchematicWriter.write(SchematicLibrary.FOLDERS.getFirst().resolve(name + ".schem"), blocks);
            onCreated.accept(name);
            onClose();
        } catch (IOException e) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("shapewright.message.export_failed", e.getMessage()), false);
            }
        }
    }

    // ---- UI helpers ----

    private FlowLayout row(String labelKey, io.wispforest.owo.ui.core.Component control) {
        FlowLayout line = Containers.horizontalFlow(Sizing.fixed(LABEL_W + FIELD_W), Sizing.fixed(18));
        line.verticalAlignment(VerticalAlignment.CENTER);
        LabelComponent lbl = Ui.label(Component.translatable(labelKey), 0xCCCCCC);
        lbl.horizontalSizing(Sizing.fixed(LABEL_W));
        line.child(lbl);
        line.child(control);
        return line;
    }

    private FlowLayout blockSlotRow(int slot) {
        FlowLayout line = Containers.horizontalFlow(Sizing.fixed(LABEL_W + FIELD_W), Sizing.fixed(18));
        line.verticalAlignment(VerticalAlignment.CENTER);
        boolean active = activeBrush == slot;
        ButtonComponent brushBtn = Components.button(
                Component.translatable("shapewright.floor_designer.slot." + SLOT_KEYS[slot])
                        .withColor(active ? Ui.ACCENT : 0xCCCCCC),
                b -> { activeBrush = slot; rebuildScreen(); });
        line.child(w(brushBtn).sizing(Sizing.fixed(LABEL_W - 2), Sizing.fixed(CONTROL_H)).margins(Insets.right(2)));

        FlowLayout picker = Containers.horizontalFlow(Sizing.fixed(FIELD_W), Sizing.fixed(CONTROL_H));
        picker.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        Item item = itemOf(slotBlocks[slot]);
        if (item != null && item != Items.AIR) {
            picker.child(Components.item(new ItemStack(item)).sizing(Sizing.fixed(16), Sizing.fixed(16)));
        }
        String label = item != null ? item.getDescription().getString() : "None";
        if (font.width(label) > FIELD_W - 26) label = font.plainSubstrByWidth(label, FIELD_W - 32) + "…";
        ButtonComponent pick = Components.button(Component.literal(label), b -> {
            if (minecraft == null) return;
            minecraft.setScreen(new BlockPickerScreen(this,
                    Component.translatable("shapewright.floor_designer.slot." + SLOT_KEYS[slot]),
                    picked -> {
                        if (picked != null) slotBlocks[slot] = BuiltInRegistries.ITEM.getKey(picked).toString();
                    }));
        });
        picker.child(w(pick).sizing(Sizing.fixed(FIELD_W - 18), Sizing.fixed(CONTROL_H)));
        line.child(picker);
        return line;
    }

    private <T> io.wispforest.owo.ui.core.Component choice(String shown, T[] options, Function<T, String> name, Consumer<T> pick) {
        String text = shown;
        if (font.width(text) > FIELD_W - 22) text = font.plainSubstrByWidth(text, FIELD_W - 28) + "…";
        ButtonComponent btn = Components.button(Component.literal(text + "  ▼"),
                b -> Ui.openMenu(this, root, b, shown, List.of(options), name, pick, () -> {}));
        return w(btn).sizing(Sizing.fixed(FIELD_W), Sizing.fixed(CONTROL_H));
    }

    private io.wispforest.owo.ui.core.Component intStep(int value, int step, int min, int max, Consumer<Integer> set) {
        FlowLayout flow = Containers.horizontalFlow(Sizing.fixed(FIELD_W), Sizing.fixed(CONTROL_H));
        flow.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        flow.child(w(Components.button(Component.literal("−"), b -> set.accept(Math.max(min, value - step))))
                .sizing(Sizing.fixed(16), Sizing.fixed(CONTROL_H)));
        LabelComponent val = Ui.label(Component.literal(String.valueOf(value)), 0xFFFFFF);
        val.horizontalSizing(Sizing.fixed(FIELD_W - 36));
        val.horizontalTextAlignment(io.wispforest.owo.ui.core.HorizontalAlignment.CENTER);
        flow.child(val);
        flow.child(w(Components.button(Component.literal("+"), b -> set.accept(Math.min(max, value + step))))
                .sizing(Sizing.fixed(16), Sizing.fixed(CONTROL_H)));
        return flow;
    }

    private io.wispforest.owo.ui.core.Component toggle(boolean on, Runnable flip) {
        ButtonComponent btn = Components.button(
                Component.translatable(on ? "options.on" : "options.off").withColor(on ? 0x8FD18F : Ui.TEXT),
                b -> flip.run());
        return w(btn).sizing(Sizing.fixed(FIELD_W), Sizing.fixed(CONTROL_H));
    }

    private static Item itemOf(String id) {
        ResourceLocation loc = id == null || id.isEmpty() ? null : ResourceLocation.tryParse(id);
        return loc == null ? null : BuiltInRegistries.ITEM.get(loc);
    }

    // ---- Live 2D Canvas ----

    private class CanvasComponent extends BaseComponent {
        private int drawX, drawY, cellPx;

        @Override
        public void draw(OwoUIDrawContext ctx, int mouseX, int mouseY, float partialTicks, float delta) {
            ctx.fill(x, y, x + width, y + height, 0xFF16181C);
            if (grid == null) return;
            int n = grid.length;
            int avail = Math.min(width - 16, height - 28);
            cellPx = Math.max(2, avail / n);
            int total = cellPx * n;
            drawX = x + (width - total) / 2;
            drawY = y + (height - total) / 2;

            int[] colors = new int[slotBlocks.length];
            for (int i = 0; i < slotBlocks.length; i++) {
                Item item = itemOf(slotBlocks[i]);
                colors[i] = item != null ? (0xFF000000 | BlockColorCache.colorOf(item)) : 0xFF888888;
            }

            for (int gz = 0; gz < n; gz++) {
                for (int gx = 0; gx < n; gx++) {
                    int idx = cellAt(gx, gz);
                    if (idx < 0 || idx >= colors.length) continue;
                    int px = drawX + gx * cellPx, py = drawY + gz * cellPx;
                    ctx.fill(px, py, px + cellPx, py + cellPx, colors[idx]);
                }
            }
            ctx.renderOutline(drawX - 1, drawY - 1, total + 2, total + 2, 0xFF3C3C3C);
            ctx.drawString(font, I18n.get("shapewright.floor_designer.paint_hint"),
                    x + 6, y + height - 12, Ui.MUTED, false);
        }

        private boolean paintAt(double mouseX, double mouseY, int button) {
            if (cellPx <= 0 || grid == null) return false;
            int gx = (int) Math.floor((mouseX - drawX) / cellPx);
            int gz = (int) Math.floor((mouseY - drawY) / cellPx);
            if (gx < 0 || gz < 0 || gx >= grid.length || gz >= grid.length) return false;
            paintCell(gx, gz, button == 1 ? FloorDesigner.BASE : activeBrush);
            return true;
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            return paintAt(mouseX, mouseY, button);
        }

        @Override
        public boolean onMouseDrag(double mouseX, double mouseY, double deltaX, double deltaY, int button) {
            return paintAt(mouseX, mouseY, button);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

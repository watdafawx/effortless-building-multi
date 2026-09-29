package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.DropdownComponent;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.HorizontalAlignment;
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
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.compat.create.CreateGlue;
import nl.requios.effortlessbuilding.network.BuildModeHintC2SPacket;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.shape.*;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.utilities.ShareCode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Shape Generator: pick a parametric shape (or a template, or a schematic), combine more shapes into it
 * (unite, subtract, intersect, exclude), check it in a 3D or slice preview, then build it with
 * SHAPE mode or save it as a template.
 * <p>
 * Built with owo-ui. Layout: shapes and templates on the left, settings in the middle (grouped,
 * scrolling, two columns when there is room), preview on the right, name/save/build along the bottom.
 */
public class ShapeGeneratorScreen extends BaseOwoScreen<FlowLayout> {

    private static final int SIDEBAR_W = 124;
    private static final int ROW_H = 17;
    /** Height of buttons and fields in the settings (vanilla's 20 leaves little room). */
    private static final int CONTROL_H = 16;
    private static final int COL_GAP = 10;
    /** Narrowest preview before the settings fall back to one column. */
    private static final int MIN_PREVIEW_W = 200;
    /** Height of one entry in a dropdown menu, for placing the menu before it is laid out. */
    private static final int MENU_ENTRY_H = 12;

    private static final int TEXT = 0xE0E0E0, MUTED = 0x9A9A9A, ACCENT = 0xFFD37F, SECTION = 0xE8A33D;

    /** Settings are grouped under these headings. */
    private enum Section { DESIGN, SHAPE, ROTATION, BUILD }

    /** One settings row: its label, optional hover text, and how to make its control. */
    private record Row(Section section, String label, @Nullable String tooltipKey, Supplier<io.wispforest.owo.ui.core.Component> control) {}

    // ---- state ----
    private ShapeParams params;
    /** Which part the settings edit: -1 for the main shape, otherwise an index into its parts. */
    private int editing = -1;
    /** Name of the template being edited, or null for an unsaved shape. */
    private @Nullable String templateName;
    private String nameDraft;
    private final ShapePreview preview = new ShapePreview();

    // ---- layout, measured from the text ----
    private int labelW = 60, fieldW = 110, cols = 1;

    // ---- containers refreshed on change ----
    private FlowLayout root, sidebarList, settings;
    /** Sidebar search text (lower case matching). */
    private String filter = "";
    /** Top-down thumbnails of saved templates, by design (made once, reused while the screen is open). */
    private final Map<ShapeParams, int[]> thumbnails = new java.util.HashMap<>();
    private LabelComponent header, layerLabel, sizeLabel, hintLabel;
    private TextBoxComponent nameBox;
    private ButtonComponent viewButton;
    private String shownSize = "", shownLayer = "";

    // ---- undo: earlier designs; quick successive edits (typing, dragging) count as one step ----
    private record Snapshot(ShapeParams params, int editing) {}
    private final java.util.ArrayDeque<Snapshot> undo = new java.util.ArrayDeque<>(), redo = new java.util.ArrayDeque<>();
    private ShapeParams lastSeen;
    private int lastSeenEditing;
    private long lastEditTime;
    private static final long EDIT_MERGE_MS = 800;
    private static final int UNDO_LIMIT = 100;

    // ---- dragging a part: blocks moved but not applied yet ----
    private final double[] dragRest = new double[3];

    public ShapeGeneratorScreen() {
        super(Component.translatable("effortlessbuilding.screen.shape_generator"));
        params = ShapeClientState.getActive();
    }

    // =========================================================================
    // Editing target: the main shape or one of its parts
    // =========================================================================

    private ShapeParams current() {
        return editing < 0 ? params : params.parts().get(editing).shape();
    }

    private void setCurrent(ShapeParams shape) {
        params = editing < 0 ? shape.withParts(params.parts())
                : params.withPart(editing, params.parts().get(editing).withShape(shape));
    }

    private ShapeParams.Part currentPart() {
        return params.parts().get(editing);
    }

    private void setCurrentPart(ShapeParams.Part part) {
        params = params.withPart(editing, part);
    }

    // =========================================================================
    // Build
    // =========================================================================

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void init() {
        boolean rebuilt = uiAdapter != null;
        super.init();
        // Back from the block picker, palette or pixel art: show what changed there
        if (rebuilt && root != null) refresh();
    }

    @Override
    protected void build(FlowLayout root) {
        this.root = root;
        BlockColorCache.ensureReady();
        root.surface(Surface.flat(0xB0101010));
        root.padding(Insets.of(8));
        // No gap on the root: menus are mounted there, and a gap would shift the screen when one opens

        // ---- header ----
        FlowLayout top = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.margins(Insets.bottom(6));
        top.gap(6).verticalAlignment(VerticalAlignment.CENTER);
        top.child(label(title.copy().withStyle(s -> s.withBold(true))).color(Color.WHITE));
        header = label(Component.empty()).color(Color.ofRgb(ACCENT));
        top.child(header);
        top.child(hspace());
        top.child(w(button("effortlessbuilding.screen.undo_short", "effortlessbuilding.screen.undo_design", this::undo)));
        top.child(w(button("effortlessbuilding.screen.redo_short", "effortlessbuilding.screen.redo_design", this::redo)));
        top.child(w(button("effortlessbuilding.screen.copy_code", "effortlessbuilding.screen.copy_code.description", this::copyCode)));
        top.child(w(button("effortlessbuilding.screen.paste_code", "effortlessbuilding.screen.paste_code.description", this::pasteCode)));
        root.child(top);

        // ---- body: sidebar | settings | preview ----
        FlowLayout body = Containers.horizontalFlow(Sizing.fill(100), Sizing.expand());
        body.gap(6);

        sidebarList = Containers.verticalFlow(Sizing.fill(100), Sizing.content());
        FlowLayout sidebar = panel(Containers.verticalFlow(Sizing.fixed(SIDEBAR_W), Sizing.fill(100)));
        // Search: filters shapes and templates as you type
        TextBoxComponent search = Components.textBox(Sizing.fill(100));
        w(search).verticalSizing(Sizing.fixed(CONTROL_H)).margins(Insets.bottom(4));
        search.setHint(Component.translatable("effortlessbuilding.screen.search"));
        search.text(filter);
        search.onChanged().subscribe(text -> {
            filter = text;
            fillSidebar();
        });
        sidebar.child(w(search));
        sidebar.child(Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), sidebarList).scrollbarThiccness(3));
        body.child(sidebar);

        settings = Containers.verticalFlow(Sizing.content(), Sizing.content());
        settings.gap(2);
        settings.padding(Insets.right(6)); // room for the scrollbar
        FlowLayout settingsPanel = panel(Containers.verticalFlow(Sizing.content(), Sizing.fill(100)));
        settingsPanel.child(Containers.verticalScroll(Sizing.content(), Sizing.fill(100), settings).scrollbarThiccness(3));
        body.child(settingsPanel);

        FlowLayout previewPanel = panel(Containers.verticalFlow(Sizing.expand(), Sizing.fill(100)));
        previewPanel.gap(4);
        previewPanel.child(new PreviewComponent().sizing(Sizing.fill(100), Sizing.expand()));
        FlowLayout layerRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        layerRow.gap(4).verticalAlignment(VerticalAlignment.CENTER);
        viewButton = button(() -> "", "effortlessbuilding.screen.view.description", () -> { preview.nextView(); refresh(); });
        layerRow.child(w(viewButton).horizontalSizing(Sizing.fixed(56)));
        layerRow.child(w(Components.button(Component.literal("◀"), b -> preview.stepLayer(params, -1))).sizing(Sizing.fixed(16), Sizing.fixed(16)));
        layerLabel = label(Component.empty()).color(Color.ofRgb(TEXT));
        layerLabel.horizontalTextAlignment(HorizontalAlignment.CENTER).horizontalSizing(Sizing.expand());
        layerRow.child(layerLabel);
        layerRow.child(w(Components.button(Component.literal("▶"), b -> preview.stepLayer(params, 1))).sizing(Sizing.fixed(16), Sizing.fixed(16)));
        previewPanel.child(layerRow);
        FlowLayout infoRow = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        infoRow.gap(4).verticalAlignment(VerticalAlignment.CENTER);
        sizeLabel = label(Component.empty()).color(Color.ofRgb(TEXT));
        previewPanel.child(sizeLabel);
        infoRow.child(hspace());
        infoRow.child(w(button("effortlessbuilding.screen.export_schem", "effortlessbuilding.screen.export_schem.description", () -> export(".schem"))));
        infoRow.child(w(Components.button(Component.literal(".nbt"), b -> export(".nbt")))
                .tooltip(Component.translatable("effortlessbuilding.screen.export_nbt.description")));
        infoRow.child(w(button("effortlessbuilding.screen.materials", "effortlessbuilding.screen.materials.description",
                () -> { if (minecraft != null) minecraft.setScreen(new MaterialsScreen(this, preview.requiredItems(params))); })));
        previewPanel.child(infoRow);
        hintLabel = label(Component.empty()).color(Color.ofRgb(MUTED));
        previewPanel.child(hintLabel);
        body.child(previewPanel);
        root.child(body);

        // ---- bottom bar ----
        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.margins(Insets.top(6));
        bottom.gap(6).verticalAlignment(VerticalAlignment.CENTER);
        nameBox = Components.textBox(Sizing.fixed(150));
        nameBox.setMaxLength(40);
        nameBox.text(nameDraft != null ? nameDraft : templateName != null ? templateName : defaultName());
        nameBox.onChanged().subscribe(s -> nameDraft = s);
        bottom.child(w(nameBox));
        bottom.child(w(button("effortlessbuilding.screen.save_template", null, this::saveTemplate)));
        bottom.child(w(button("effortlessbuilding.screen.palette_button", "effortlessbuilding.screen.palette_button.description",
                () -> { if (minecraft != null) minecraft.setScreen(new PaletteScreen(this)); })));
        bottom.child(hspace());
        bottom.child(w(button("effortlessbuilding.screen.use_shape", "effortlessbuilding.screen.use_shape.description", this::useShape))
                .horizontalSizing(Sizing.fixed(90)));
        bottom.child(w(Components.button(Component.translatable("gui.done"), b -> onClose())).horizontalSizing(Sizing.fixed(70)));
        root.child(bottom);

        refresh();
    }

    /** Rebuilds everything that depends on the design: list selection, settings, header, hints. */
    private void refresh() {
        if (editing >= params.parts().size()) editing = -1;
        String headerText = templateName != null ? templateName : I18n.get(params.type().getNameKey());
        if (!params.parts().isEmpty()) headerText += " + " + params.parts().size();
        header.text(Component.literal(headerText));
        fillSidebar();
        fillSettings();
        String hint = params.type() != ShapeType.SCHEMATIC && params.sizing() == ShapeParams.Sizing.CLICKS
                ? I18n.get("effortlessbuilding.screen.click_sizing_hint")
                : params.sizing() == ShapeParams.Sizing.PATH ? I18n.get("effortlessbuilding.screen.path_sizing_hint")
                : editing >= 0 ? I18n.get(preview.view == ShapePreview.View.THREE_D
                        ? "effortlessbuilding.screen.drag_part_3d" : "effortlessbuilding.screen.drag_part_slice")
                : preview.view == ShapePreview.View.THREE_D ? I18n.get("effortlessbuilding.screen.drag_to_rotate") : "";
        hintLabel.text(Component.literal(hint));
        viewButton.setMessage(Component.translatable("effortlessbuilding.screen.view." + preview.view.name().toLowerCase()));
    }

    // =========================================================================
    // Sidebar: shape types and templates
    // =========================================================================

    private void fillSidebar() {
        sidebarList.clearChildren();
        sidebarList.child(sectionLabel(I18n.get("effortlessbuilding.screen.shapes")));
        if (editing >= 0) {
            // While a part is being edited, the list sets that part's shape
            sidebarList.child(label(Component.translatable("effortlessbuilding.screen.shapes_for_part", editing + 1))
                    .color(Color.ofRgb(MUTED)).maxWidth(SIDEBAR_W - 10).margins(Insets.bottom(3)));
        }
        String query = filter.trim().toLowerCase(java.util.Locale.ROOT);
        for (ShapeType type : ShapeType.values()) {
            String name = I18n.get(type.getNameKey());
            if (!query.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
            boolean selected = (editing >= 0 || templateName == null) && current().type() == type;
            sidebarList.child(listRow(name, TEXT, selected, () -> selectType(type), null, null));
        }
        sidebarList.child(sectionLabel(I18n.get("effortlessbuilding.screen.templates")).margins(Insets.top(6)));
        List<ShapeClientState.Template> templates = ShapeClientState.getTemplates().stream()
                .filter(t -> query.isEmpty() || t.name().toLowerCase(java.util.Locale.ROOT).contains(query)).toList();
        if (templates.isEmpty()) {
            sidebarList.child(label(Component.translatable(query.isEmpty() ? "effortlessbuilding.screen.no_templates"
                    : "effortlessbuilding.screen.no_matches")).color(Color.ofRgb(0x777777)));
        }
        for (ShapeClientState.Template template : templates) {
            boolean selected = template.name().equals(templateName) && editing < 0;
            sidebarList.child(listRow(template.name(), ACCENT, selected, () -> selectTemplate(template), () -> {
                ShapeClientState.deleteTemplate(template.name());
                if (template.name().equals(templateName)) templateName = null;
                ShapeTemplateStorage.save();
                refresh();
            }, template.params()));
        }
    }

    private static final int THUMB = 16;

    /**
     * A small top-down picture of a design: THUMB x THUMB colors (0 = empty), higher blocks lighter.
     * Parts with their own block show its color.
     */
    private int[] thumbnail(ShapeParams params) {
        return thumbnails.computeIfAbsent(params, p -> {
            int[] pixels = new int[THUMB * THUMB];
            var labels = ShapeGenerator.generateLabeled(p, 256, SchematicLibrary::cells);
            if (labels.isEmpty()) return pixels;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (Cell c : labels.keySet()) {
                minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x());
                minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z());
                minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y());
            }
            int span = Math.max(maxX - minX, maxZ - minZ) + 1;
            int[] top = new int[THUMB * THUMB];
            java.util.Arrays.fill(top, Integer.MIN_VALUE);
            for (var e : labels.entrySet()) {
                Cell c = e.getKey();
                int px = (c.x() - minX) * THUMB / span, pz = (c.z() - minZ) * THUMB / span;
                int i = pz * THUMB + px;
                if (c.y() <= top[i]) continue;
                top[i] = c.y();
                int base = 0xE8A33D;
                Integer label = e.getValue();
                if (label != null && label >= 0 && label < p.parts().size() && !p.parts().get(label).block().isEmpty()) {
                    ResourceLocation id = ResourceLocation.tryParse(p.parts().get(label).block());
                    if (id != null) base = BlockColorCache.colorOf(BuiltInRegistries.ITEM.get(id));
                }
                float light = maxY == minY ? 1f : 0.55f + 0.45f * (c.y() - minY) / (maxY - minY);
                int r = (int) (((base >> 16) & 0xFF) * light), g = (int) (((base >> 8) & 0xFF) * light), b = (int) ((base & 0xFF) * light);
                pixels[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            return pixels;
        });
    }

    /** Draws a design's thumbnail. */
    private class ThumbnailComponent extends BaseComponent {
        private final ShapeParams params;

        ThumbnailComponent(ShapeParams params) {
            this.params = params;
            sizing(Sizing.fixed(THUMB), Sizing.fixed(THUMB));
        }

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            int[] pixels = thumbnail(params);
            context.fill(x, y, x + THUMB, y + THUMB, 0xFF151515);
            for (int i = 0; i < pixels.length; i++) {
                if (pixels[i] != 0) context.fill(x + i % THUMB, y + i / THUMB, x + i % THUMB + 1, y + i / THUMB + 1, pixels[i]);
            }
        }
    }

    /** A clickable list line, highlighted when selected or hovered, with an optional delete button. */
    private FlowLayout listRow(String text, int color, boolean selected, Runnable select, @Nullable Runnable delete,
                               @Nullable ShapeParams thumbnail) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(thumbnail != null ? THUMB + 2 : 14));
        row.verticalAlignment(VerticalAlignment.CENTER).padding(Insets.horizontal(3));
        Surface normal = selected ? Surface.flat(0x50FFFFFF) : Surface.BLANK;
        row.surface(normal);
        row.mouseEnter().subscribe(() -> row.surface(selected ? normal : Surface.flat(0x22FFFFFF)));
        row.mouseLeave().subscribe(() -> row.surface(normal));
        row.mouseDown().subscribe((x, y, button) -> {
            if (button != 0) return false;
            select.run();
            return true;
        });
        if (thumbnail != null) row.child(new ThumbnailComponent(thumbnail).margins(Insets.right(4)));
        LabelComponent label = label(Component.literal(text)).color(Color.ofRgb(color));
        label.maxWidth(SIDEBAR_W - (delete != null ? 32 : 18) - (thumbnail != null ? THUMB + 4 : 0));
        row.child(label);
        if (delete != null) {
            row.child(hspace());
            row.child(w(Components.button(Component.literal("×"), b -> delete.run())).sizing(Sizing.fixed(11), Sizing.fixed(11))
                    .tooltip(Component.translatable("effortlessbuilding.screen.delete_template")));
        }
        return row;
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private void fillSettings() {
        List<Row> rows = rows();
        measure(rows);
        settings.clearChildren();
        for (Section section : Section.values()) {
            List<Row> inSection = rows.stream().filter(r -> r.section() == section).toList();
            if (inSection.isEmpty()) continue;
            settings.child(sectionLabel(I18n.get("effortlessbuilding.screen.section." + section.name().toLowerCase()))
                    .margins(Insets.of(settings.children().isEmpty() ? 0 : 6, 2, 0, 0)));
            // Rows fill the first column, then the second
            FlowLayout columns = Containers.horizontalFlow(Sizing.content(), Sizing.content());
            columns.gap(COL_GAP);
            int perColumn = (inSection.size() + cols - 1) / cols;
            for (int c = 0; c < cols; c++) {
                FlowLayout column = Containers.verticalFlow(Sizing.fixed(labelW + fieldW), Sizing.content());
                column.gap(2);
                for (int i = c * perColumn; i < Math.min(inSection.size(), (c + 1) * perColumn); i++) column.child(settingRow(inSection.get(i)));
                columns.child(column);
            }
            settings.child(columns);
        }
    }

    private FlowLayout settingRow(Row row) {
        FlowLayout line = Containers.horizontalFlow(Sizing.fixed(labelW + fieldW), Sizing.fixed(ROW_H));
        line.verticalAlignment(VerticalAlignment.CENTER);
        LabelComponent label = label(Component.literal(row.label())).color(Color.ofRgb(0xCCCCCC));
        label.horizontalSizing(Sizing.fixed(labelW));
        if (row.tooltipKey() != null && I18n.exists(row.tooltipKey())) label.tooltip(Component.translatable(row.tooltipKey()));
        line.child(label);
        line.child(row.control().get());
        return line;
    }

    /** Label column as wide as the longest label; controls as wide as the longest choice; 1 or 2 columns. */
    private void measure(List<Row> rows) {
        labelW = 40;
        for (Row row : rows) labelW = Math.max(labelW, font.width(row.label()) + 8);
        fieldW = 76; // number fields with their −/+ buttons
        for (ShapeType.ParamSpec spec : current().type().params) {
            for (int i = 0; i < spec.options().size(); i++) fieldW = Math.max(fieldW, font.width(I18n.get(spec.getOptionKey(i))) + 22);
        }
        for (ShapeParams.Orientation o : ShapeParams.Orientation.values()) fieldW = Math.max(fieldW, font.width(I18n.get(o.getNameKey())) + 22);
        for (ShapeParams.Sizing z : ShapeParams.Sizing.values()) fieldW = Math.max(fieldW, font.width(I18n.get(z.getNameKey())) + 22);
        fieldW = Math.min(fieldW, 190);
        int twoColumns = 2 * (labelW + fieldW) + COL_GAP + 20;
        int room = width - 16 - SIDEBAR_W - 12 - 12;
        cols = room - twoColumns >= MIN_PREVIEW_W ? 2 : 1;
    }

    /** All settings for what is being edited. */
    private List<Row> rows() {
        List<Row> list = new ArrayList<>();
        list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.editing"), "effortlessbuilding.screen.editing.description",
                this::partControl));
        if (editing >= 0) {
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.operation"), null, () -> choice(
                    I18n.get(currentPart().operation().getNameKey()), ShapeParams.Operation.values(), o -> I18n.get(o.getNameKey()),
                    o -> setCurrentPart(currentPart().withOperation(o)))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.offset_x"), null, () -> number(currentPart().x(), 1, true,
                    v -> setCurrentPart(currentPart().withOffset((int) v, currentPart().y(), currentPart().z())))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.offset_y"), null, () -> number(currentPart().y(), 1, true,
                    v -> setCurrentPart(currentPart().withOffset(currentPart().x(), (int) v, currentPart().z())))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.offset_z"), null, () -> number(currentPart().z(), 1, true,
                    v -> setCurrentPart(currentPart().withOffset(currentPart().x(), currentPart().y(), (int) v)))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.part_block"), "effortlessbuilding.screen.part_block.description", () ->
                    w(Components.button(Component.literal(blockLabel(currentPart().block())), b -> {
                        if (minecraft == null) return;
                        int index = editing;
                        minecraft.setScreen(new BlockPickerScreen(this, Component.translatable("effortlessbuilding.screen.pick_part_block"),
                                item -> params = params.withPart(index, params.parts().get(index)
                                        .withBlock(item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString()))));
                    })).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.part_repeat"), "effortlessbuilding.screen.part_repeat.description",
                    () -> number(currentPart().repeat(), 1, true, v -> setCurrentPart(currentPart().withRepeat((int) v)))));
        }

        ShapeType type = current().type();
        if (type == ShapeType.SCHEMATIC) {
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.file"), "effortlessbuilding.screen.schematic.description",
                    this::schematicControl));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.pixel_art_row"), "effortlessbuilding.screen.pixel_art.description",
                    () -> w(button("effortlessbuilding.screen.pixel_art_open", "effortlessbuilding.screen.pixel_art.description", () -> {
                        if (minecraft != null) minecraft.setScreen(new PixelArtScreen(this, name -> setCurrent(current().withSchematic(name))));
                    })).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H))));
        } else {
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.size"), null,
                    () -> number(current().size(), 1, true, v -> setCurrent(current().withSize((int) v)))));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.orientation"), null, () -> choice(
                    I18n.get(current().orientation().getNameKey()), ShapeParams.Orientation.values(), o -> I18n.get(o.getNameKey()),
                    o -> setCurrent(current().withOrientation(o)))));
            if (editing < 0) {
                // Sizing belongs to the whole shape: parts scale along with it
                list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.sizing"), null, () -> choice(
                        I18n.get(params.sizing().getNameKey()), ShapeParams.Sizing.values(), z -> I18n.get(z.getNameKey()),
                        z -> params = params.withSizing(z))));
            }
            if (type.hollowable) {
                list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.hollow"), null, () ->
                        w(Components.button(Component.translatable(current().hollow() ? "options.on" : "options.off"), b -> {
                            setCurrent(current().withHollow(!current().hollow()));
                            refresh();
                        })).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H))));
            }
        }
        if (editing < 0) {
            // A center axle in its own block; 2 by 2 when the shape has no single middle block
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.center_block"),
                    "effortlessbuilding.shape.param.center_block.description", () ->
                    w(Components.button(Component.literal(centerBlockLabel()), b -> {
                        if (minecraft == null) return;
                        minecraft.setScreen(new BlockPickerScreen(this, Component.translatable("effortlessbuilding.screen.pick_center_block"),
                                item -> params = params.withCenterBlock(item == null ? "" : BuiltInRegistries.ITEM.getKey(item).toString())));
                    })).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H))));
        }

        for (ShapeType.ParamSpec spec : type.params) {
            if (hidden(spec)) continue;
            String tooltip = spec.getNameKey() + ".description";
            Supplier<io.wispforest.owo.ui.core.Component> control;
            if (!spec.options().isEmpty()) {
                List<Integer> options = new ArrayList<>();
                for (int i = 0; i < spec.options().size(); i++) options.add(i);
                control = () -> choice(I18n.get(spec.getOptionKey(current().getInt(spec.key()))), options.toArray(new Integer[0]),
                        i -> I18n.get(spec.getOptionKey(i)), i -> setCurrent(current().with(spec.key(), i)));
            } else {
                double step = spec.integer() ? 1 : spec.step();
                control = () -> number(current().get(spec.key()), step, spec.integer(), v -> setCurrent(current().with(spec.key(), v)));
            }
            list.add(new Row(sectionOf(spec.key()), I18n.get(spec.getNameKey()), tooltip, control));
        }
        return list;
    }

    private static Section sectionOf(String key) {
        if (key.equals(ShapeType.ROTATE_X) || key.equals(ShapeType.ROTATE_Y) || key.equals(ShapeType.ROTATE_Z)) return Section.ROTATION;
        if (ShapeType.isBuildSetting(key) || key.equals(TerrainBlender.ENABLED) || key.equals(TerrainBlender.MARGIN)
                || key.equals(ShapeMaterials.USE_SAVED_BLOCKS)) return Section.BUILD;
        return Section.SHAPE;
    }

    /** Super glue (only with Create) and ground blending are offered on the main shape only. */
    private boolean hidden(ShapeType.ParamSpec spec) {
        if (editing >= 0 && ShapeType.isBuildSetting(spec.key())) return true;
        if (spec.key().equals(ShapeType.SUPER_GLUE)) return !CreateGlue.isAvailable();
        if (spec.key().equals(ShapeType.BEARING)) return !CreateGlue.isAvailable() || params.getInt(ShapeType.SUPER_GLUE) != 1;
        if (spec.key().equals(ShapeType.PATH_SPACING) || spec.key().equals(ShapeType.PATH_ALIGN)) {
            return params.sizing() != ShapeParams.Sizing.PATH;
        }
        return editing >= 0 && (spec.key().equals(TerrainBlender.ENABLED) || spec.key().equals(TerrainBlender.MARGIN));
    }

    // ---- controls ----

    /** Which part to edit (opens a menu of the main shape and its parts), plus add/remove. */
    private io.wispforest.owo.ui.core.Component partControl() {
        FlowLayout flow = Containers.horizontalFlow(Sizing.fixed(fieldW), Sizing.content());
        flow.gap(2);
        Integer[] targets = new Integer[params.parts().size() + 1];
        for (int i = 0; i < targets.length; i++) targets[i] = i - 1;
        io.wispforest.owo.ui.core.Component menu = choice(partLabel(editing), targets, this::partLabel, i -> editing = i);
        menu.sizing(Sizing.fixed(fieldW - 36), Sizing.fixed(CONTROL_H));
        flow.child(menu);
        // "+" asks which shape to add
        ButtonComponent add = Components.button(Component.literal("+"),
                b -> openMenu(b, "", ShapeType.values(), t -> I18n.get(t.getNameKey()), this::addPart));
        add.active(params.parts().size() < ShapeParams.MAX_PARTS);
        flow.child(w(add).sizing(Sizing.fixed(16), Sizing.fixed(16)).tooltip(Component.translatable("effortlessbuilding.screen.add_part")));
        ButtonComponent remove = Components.button(Component.literal("×"), b -> removePart());
        remove.active(editing >= 0);
        flow.child(w(remove).sizing(Sizing.fixed(16), Sizing.fixed(16)).tooltip(Component.translatable("effortlessbuilding.screen.remove_part")));
        return flow;
    }

    private io.wispforest.owo.ui.core.Component schematicControl() {
        List<String> names = SchematicLibrary.list();
        String name = current().schematic().isEmpty() && !names.isEmpty() ? names.getFirst() : current().schematic();
        if (!name.equals(current().schematic())) setCurrent(current().withSchematic(name));
        if (names.isEmpty()) {
            return label(Component.translatable("effortlessbuilding.screen.no_schematics")).color(Color.ofRgb(MUTED));
        }
        return choice(name, names.toArray(new String[0]), n -> n, n -> setCurrent(current().withSchematic(n)));
    }

    /** A button showing the current choice; clicking opens a menu with every option. */
    private <T> io.wispforest.owo.ui.core.Component choice(String shown, T[] options, java.util.function.Function<T, String> name,
                                                           java.util.function.Consumer<T> pick) {
        ButtonComponent button = Components.button(Component.literal(shown + "  ▼"), b -> openMenu(b, shown, options, name, pick));
        return w(button).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H));
    }

    /** Opens a menu of options under the button (the current one marked); picking one applies it and refreshes. */
    private <T> void openMenu(ButtonComponent b, String current, T[] options, java.util.function.Function<T, String> name,
                              java.util.function.Consumer<T> pick) {
        // Below the button, or above it when the menu would run off the bottom of the screen
        int menuH = options.length * MENU_ENTRY_H + 8;
        int menuY = b.getY() + b.getHeight() + menuH <= height - 4 ? b.getY() + b.getHeight() : Math.max(4, b.getY() - menuH);
        DropdownComponent opened = DropdownComponent.openContextMenu(this, root, FlowLayout::child, b.getX(), menuY, menu -> {
            menu.surface(Surface.flat(0xF0181818).and(Surface.outline(0xFF555555)));
            for (T option : options) {
                String text = name.apply(option);
                menu.button(Component.literal(text.equals(current) ? "» " + text : "   " + text), m -> {
                    pick.accept(option);
                    m.remove();
                    refresh();
                });
            }
        });
        ignoreTextClicks(opened); // menu entries are labels too: same guard as label()
        opened.zIndex(300); // above the fields (drawn batched, so depth decides) and below tooltips
    }

    /** A number field with − and + buttons; scrolling over it steps too (Alt: 5 steps). */
    private io.wispforest.owo.ui.core.Component number(double value, double step, boolean integer, DoubleConsumer set) {
        FlowLayout flow = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        flow.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        TextBoxComponent box = Components.textBox(Sizing.fixed(40));
        w(box).verticalSizing(Sizing.fixed(CONTROL_H));
        box.text(format(value, integer));
        box.setFilter(s -> s.matches("-?\\d*" + (integer ? "" : "\\.?\\d*")));
        box.onChanged().subscribe(s -> {
            try {
                set.accept(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
            }
        });
        Runnable down = () -> stepBox(box, -step, integer);
        Runnable up = () -> stepBox(box, step, integer);
        flow.child(w(Components.button(Component.literal("−"), b -> down.run())).sizing(Sizing.fixed(14), Sizing.fixed(16)));
        flow.child(w(box));
        flow.child(w(Components.button(Component.literal("+"), b -> up.run())).sizing(Sizing.fixed(14), Sizing.fixed(16)));
        // The wheel over the field changes the number (Alt: 5 steps); over labels it scrolls the settings
        w(box).mouseScroll().subscribe((x, y, amount) -> {
            stepBox(box, (amount > 0 ? step : -step) * (Screen.hasAltDown() ? 5 : 1), integer);
            return true;
        });
        return flow;
    }

    private static void stepBox(TextBoxComponent box, double delta, boolean integer) {
        double value;
        try {
            value = Double.parseDouble(box.getValue());
        } catch (NumberFormatException e) {
            value = 0;
        }
        box.text(format(Math.round((value + delta) * 1000) / 1000.0, integer)); // fires onChanged
    }

    private static String format(double v, boolean integer) {
        if (integer || v == Math.rint(v)) return String.valueOf((long) Math.rint(v));
        return String.valueOf(v);
    }

    private ButtonComponent button(String key, @Nullable String tooltipKey, Runnable action) {
        return button(() -> I18n.get(key), tooltipKey, action);
    }

    private ButtonComponent button(Supplier<String> text, @Nullable String tooltipKey, Runnable action) {
        ButtonComponent b = Components.button(Component.literal(text.get()), btn -> action.run());
        if (tooltipKey != null) w(b).tooltip(Component.translatable(tooltipKey));
        return b;
    }

    private FlowLayout panel(FlowLayout flow) {
        flow.surface(Surface.flat(0xC0202020).and(Surface.outline(0xFF3C3C3C)));
        flow.padding(Insets.of(5));
        return flow;
    }

    private static LabelComponent sectionLabel(String text) {
        return (LabelComponent) label(Component.literal(text).withStyle(s -> s.withBold(true))).color(Color.ofRgb(SECTION))
                .margins(Insets.bottom(2));
    }

    /**
     * A label whose text clicks do nothing. owo passes clicks on plain text to the screen with no style,
     * and a mod in the pack (an EMI add-on) crashes on that; none of our labels have links anyway.
     */
    private static LabelComponent label(Component text) {
        return Components.label(text).textClickHandler(style -> false);
    }

    private static void ignoreTextClicks(io.wispforest.owo.ui.core.Component c) {
        if (c instanceof LabelComponent l) l.textClickHandler(style -> false);
        if (c instanceof io.wispforest.owo.ui.core.ParentComponent parent) parent.children().forEach(ShapeGeneratorScreen::ignoreTextClicks);
    }

    /** Pushes what follows to the right edge (owo's own spacer also grows vertically, stretching the row). */
    private static io.wispforest.owo.ui.core.Component hspace() {
        return Components.spacer().verticalSizing(Sizing.fixed(0));
    }

    /** owo turns vanilla widgets into UI components at runtime; this gives them that type for the compiler. */
    private static io.wispforest.owo.ui.core.Component w(Object widget) {
        return (io.wispforest.owo.ui.core.Component) widget;
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private String partLabel(int index) {
        if (index < 0) return I18n.get("effortlessbuilding.screen.main_shape");
        ShapeParams.Part part = params.parts().get(index);
        return (index + 1) + ". " + I18n.get(part.shape().type().getNameKey()); // its operation has its own row
    }

    /** Adds a part of the chosen shape, half the main shape's size, joined on (Operation changes that). */
    private void addPart(ShapeType type) {
        if (params.parts().size() >= ShapeParams.MAX_PARTS) return;
        ShapeParams shape = ShapeParams.defaults(type);
        if (type.resizable()) shape = shape.withSize(Math.max(1, params.size() / 2));
        List<ShapeParams.Part> parts = new ArrayList<>(params.parts());
        parts.add(new ShapeParams.Part(shape, ShapeParams.Operation.UNITE, 0, 0, 0));
        params = params.withParts(parts);
        editing = parts.size() - 1;
        refresh();
    }

    private void removePart() {
        if (editing < 0) return;
        List<ShapeParams.Part> parts = new ArrayList<>(params.parts());
        parts.remove(editing);
        params = params.withParts(parts);
        editing = Math.min(editing, parts.size() - 1);
        refresh();
    }

    private void saveTemplate() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = defaultName();
        ShapeClientState.saveTemplate(new ShapeClientState.Template(name, params));
        templateName = name;
        nameDraft = null;
        ShapeTemplateStorage.save();
        refresh();
    }

    /** Makes this the active shape, switches to SHAPE mode and closes. */
    private void useShape() {
        ShapeClientState.setActive(params);
        ShapeTemplateStorage.save();
        if (BuildModes.CLIENT.getBuildMode() != BuildModeEnum.SHAPE) {
            BuildPipelineClient.clearAnchor();
            BuildModes.CLIENT.setBuildMode(BuildModeEnum.SHAPE);
            PacketHandler.sendToServer(new BuildModeHintC2SPacket());
        }
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(BuildModeEnum.SHAPE.getNameKey()), true);
        }
        onClose();
    }

    /** Picking a shape type changes what is being edited: the main shape (keeping its parts) or a part. */
    private void selectType(ShapeType type) {
        if (type == current().type()) return;
        if (editing < 0) {
            params = ShapeParams.defaults(type).withSizing(params.sizing()).withParts(params.parts());
            templateName = null;
            setName(null);
        } else {
            setCurrent(ShapeParams.defaults(type));
        }
        preview.layer = -1;
        refresh();
    }

    private void selectTemplate(ShapeClientState.Template template) {
        params = template.params();
        templateName = template.name();
        editing = -1;
        setName(template.name());
        preview.layer = -1;
        refresh();
    }

    /** Puts a name in the name box (null: the default name for the design). */
    private void setName(@Nullable String name) {
        nameDraft = name;
        nameBox.text(name != null ? name : defaultName());
    }

    private void copyCode() {
        if (minecraft == null) return;
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("name", nameBox.getValue());
        json.add("shape", ShapeTemplateStorage.toJson(params));
        minecraft.keyboardHandler.setClipboard(ShareCode.encode(ShareCode.SHAPE, json));
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.code_copied"), true);
    }

    private void pasteCode() {
        if (minecraft == null) return;
        com.google.gson.JsonObject json = ShareCode.decode(ShareCode.SHAPE, minecraft.keyboardHandler.getClipboard());
        ShapeParams pasted = json != null && json.has("shape") ? ShapeTemplateStorage.fromJson(json.getAsJsonObject("shape")) : null;
        if (pasted == null) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.code_invalid"), true);
            return;
        }
        params = pasted;
        editing = -1;
        templateName = null;
        setName(json.has("name") ? json.get("name").getAsString() : null);
        preview.layer = -1;
        refresh();
    }

    /** Saves exactly what would be built (saved blocks, middle block, palette, else the held block) as a schematic. */
    private void export(String extension) {
        if (minecraft == null || minecraft.player == null) return;
        Map<Cell, BlockState> blocks = preview.blocksToExport(params);
        if (blocks.isEmpty()) {
            minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.export_hold_block"), true);
            return;
        }
        String name = SchematicWriter.safeName(nameBox.getValue().isBlank() ? defaultName() : nameBox.getValue());
        java.nio.file.Path file = SchematicLibrary.FOLDERS.getFirst().resolve(name + extension);
        try {
            SchematicWriter.write(file, blocks);
            minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.exported", file.toString()), false);
        } catch (java.io.IOException e) {
            minecraft.player.displayClientMessage(Component.translatable("effortlessbuilding.message.export_failed", e.getMessage()), false);
        }
    }

    private String centerBlockLabel() {
        return blockLabel(params.centerBlock());
    }

    /** A block's name from its item id, or "None" when empty. */
    private static String blockLabel(String itemId) {
        ResourceLocation id = itemId.isEmpty() ? null : ResourceLocation.tryParse(itemId);
        if (id == null) return I18n.get("effortlessbuilding.screen.picker_none");
        return BuiltInRegistries.ITEM.get(id).getDescription().getString();
    }

    private String defaultName() {
        String base = I18n.get(params.type().getNameKey());
        return params.type() == ShapeType.SCHEMATIC ? base + " " + params.schematic() : base + " " + params.size();
    }

    // =========================================================================
    // Per-frame text and the preview
    // =========================================================================

    // =========================================================================
    // Undo / redo of design changes
    // =========================================================================

    /** Notices a design change since the last frame and keeps the design before it for undo. */
    private void trackChanges() {
        if (lastSeen == null) {
            lastSeen = params;
            lastSeenEditing = editing;
            return;
        }
        if (params.equals(lastSeen)) {
            lastSeenEditing = editing;
            return;
        }
        long now = System.currentTimeMillis();
        // Adding or removing a part, or changing a shape, is always its own step
        boolean structural = !structure(lastSeen).equals(structure(params));
        if (structural || now - lastEditTime > EDIT_MERGE_MS || undo.isEmpty()) {
            undo.push(new Snapshot(lastSeen, lastSeenEditing));
            while (undo.size() > UNDO_LIMIT) undo.removeLast();
        }
        redo.clear();
        lastEditTime = structural ? 0 : now;
        lastSeen = params;
        lastSeenEditing = editing;
    }

    /** The main shape type and each part's type: what changes when a design changes shape rather than a value. */
    private static List<ShapeType> structure(ShapeParams p) {
        List<ShapeType> out = new ArrayList<>();
        out.add(p.type());
        for (ShapeParams.Part part : p.parts()) out.add(part.shape().type());
        return out;
    }

    private void undo() {
        trackChanges();
        if (undo.isEmpty()) return;
        redo.push(new Snapshot(params, editing));
        restore(undo.pop());
    }

    private void redo() {
        trackChanges();
        if (redo.isEmpty()) return;
        undo.push(new Snapshot(params, editing));
        restore(redo.pop());
    }

    private void restore(Snapshot snapshot) {
        params = snapshot.params();
        editing = Math.min(snapshot.editing(), params.parts().size() - 1);
        lastSeen = params;
        lastSeenEditing = editing;
        lastEditTime = 0; // the next edit starts a new step
        refresh();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean control = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            if ((modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) redo(); else undo();
            return true;
        }
        if (control && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // =========================================================================
    // Dragging a part in the preview
    // =========================================================================

    /** Moves the part being edited by a screen drag; whole blocks only, the rest carries over. */
    private void dragPart(double dx, double dy, boolean vertical) {
        if (editing < 0) return;
        double[] move = preview.dragToBlocks(dx, dy, vertical);
        int[] step = new int[3];
        for (int a = 0; a < 3; a++) {
            dragRest[a] += move[a];
            step[a] = (int) dragRest[a];
            dragRest[a] -= step[a];
        }
        if (step[0] == 0 && step[1] == 0 && step[2] == 0) return;
        ShapeParams.Part part = currentPart();
        setCurrentPart(part.withOffset(part.x() + step[0], part.y() + step[1], part.z() + step[2]));
        refresh(); // the offset fields show the new position
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        trackChanges();
        preview.highlight = editing >= 0 ? editing : Integer.MIN_VALUE;
        // Only touch the labels when their text changes (each change re-lays out the screen)
        if (sizeLabel != null) {
            String size = preview.sizeText(params);
            if (!size.equals(shownSize)) { shownSize = size; sizeLabel.text(Component.literal(size)); }
            String layer = preview.layerText(params);
            if (!layer.equals(shownLayer)) { shownLayer = layer; layerLabel.text(Component.literal(layer)); }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** The 3D or slice preview: drag to turn, scroll to zoom (or step layers in slice views). */
    private class PreviewComponent extends BaseComponent {

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            preview.render(context, font, x, y, width, height, params);
            context.renderOutline(x, y, width, height, 0xFF3C3C3C);
        }

        @Override
        public boolean canFocus(FocusSource source) {
            return source == FocusSource.MOUSE_CLICK;
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            super.onMouseDown(mouseX, mouseY, button);
            java.util.Arrays.fill(dragRest, 0);
            // 3D turns; slice views drag the part being edited
            return preview.view == ShapePreview.View.THREE_D || editing >= 0;
        }

        @Override
        public boolean onMouseDrag(double mouseX, double mouseY, double deltaX, double deltaY, int button) {
            boolean moving = editing >= 0 && (preview.view != ShapePreview.View.THREE_D || Screen.hasShiftDown() || Screen.hasControlDown());
            if (moving) {
                dragPart(deltaX, deltaY, preview.view == ShapePreview.View.THREE_D && Screen.hasControlDown());
                return true;
            }
            if (preview.view != ShapePreview.View.THREE_D) return false;
            preview.drag(deltaX, deltaY);
            return true;
        }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            preview.scroll(params, amount);
            return true;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

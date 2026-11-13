package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
import io.wispforest.owo.ui.component.TextBoxComponent;
import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Color;
import io.wispforest.owo.ui.core.CursorStyle;
import io.wispforest.owo.ui.core.Insets;
import io.wispforest.owo.ui.core.OwoUIAdapter;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;
import io.wispforest.owo.ui.core.Surface;
import io.wispforest.owo.ui.core.VerticalAlignment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.compat.FramedCompat;
import nl.requios.effortlessbuilding.compat.create.CreateGlue;
import nl.requios.effortlessbuilding.network.BuildModeHintC2SPacket;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.shape.*;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.utilities.ShareCode;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static nl.requios.effortlessbuilding.screen.Ui.w;

/**
 * Shape Generator: pick a parametric shape (or a template, or a schematic), combine more shapes into it
 * (unite, subtract, intersect, exclude), check it in a 3D or slice preview, then build it with
 * SHAPE mode or save it as a template.
 * <p>
 * Built with owo-ui. Layout: library (shapes, designs, templates) on the left, settings in the middle
 * (parts list, then collapsible groups, two columns when there is room), preview on the right,
 * name/save/build along the bottom. On narrow screens the library and the settings share one tabbed panel.
 */
public class ShapeGeneratorScreen extends BaseOwoScreen<FlowLayout> {

    private static final int SIDEBAR_W = 124;
    private static final int ROW_H = 18;
    /** Height of buttons and fields in the panels (vanilla's 20 leaves little room). */
    private static final int CONTROL_H = 16;
    private static final int GAP = 6, COL_GAP = 10;
    /** Width the settings panel adds around its columns: padding, scroll padding and scrollbar. */
    private static final int SETTINGS_CHROME = 20;
    private static final int MIN_PREVIEW_W = 170;
    /** The settings only take a second column when the preview keeps at least this much. */
    private static final int TWO_COLUMN_PREVIEW_W = 280;
    /** Below this screen width (scaled pixels) the library and settings share a tabbed panel. */
    private static final int COMPACT_W = 522;
    private static final int MIN_LABEL_W = 48, MAX_LABEL_W = 110, MIN_FIELD_W = 84, MAX_FIELD_W = 150;
    private static final int THUMB = 16;
    private static final int TOOLTIP_W = 220;

    private static final int TEXT = Ui.TEXT, MUTED = Ui.MUTED, ACCENT = Ui.ACCENT, LABEL = 0xCCCCCC, DESIGN = 0x9FD7FF;
    private static final Surface SELECTED = Surface.flat(0x50FFFFFF), HOVER = Surface.flat(0x22FFFFFF);
    private static final Surface PILL = Surface.flat(0xFF262626).and(Surface.outline(0xFF3C3C3C));
    private static final Surface PILL_HOVER = Surface.flat(0xFF333333).and(Surface.outline(0xFF5A5A5A));
    private static final Surface PILL_ON = Surface.flat(0xFF3A3326).and(Surface.outline(0xFFE8A33D));

    /** Settings are grouped under these headings; the closed ones fold away until clicked. */
    private enum Section {
        PART(true), DESIGN(true), SHAPE(true), HEIGHTS(false), ROTATION(false), BUILD(true);

        final boolean openByDefault;

        Section(boolean openByDefault) {
            this.openByDefault = openByDefault;
        }
    }

    /** Which settings and library groups are open, by name; kept while the game runs. */
    private static final Map<String, Boolean> OPEN = new HashMap<>();
    /** Top-down thumbnails of designs (made once, reused while the game runs). */
    private static final Map<ShapeParams, int[]> THUMBNAILS = new HashMap<>();

    /** One settings row: its label, optional hover text, whether it differs from the default, and its control. */
    private record Row(Section section, String label, @Nullable String tooltipKey, boolean changed,
                       Supplier<io.wispforest.owo.ui.core.Component> control) {
        Row(Section section, String label, @Nullable String tooltipKey, Supplier<io.wispforest.owo.ui.core.Component> control) {
            this(section, label, tooltipKey, false, control);
        }
    }

    // ---- state ----
    private ShapeParams params;
    /** Which part the settings edit: -1 for the main shape, otherwise an index into its parts. */
    private int editing = -1;
    /** Name of the template being edited, or null for an unsaved shape. */
    private @Nullable String templateName;
    private String nameDraft;
    private final ShapePreview preview = new ShapePreview();
    /** Library search text. */
    private String filter = "";
    /** Narrow screens: whether the shared panel shows the library (else the settings). */
    private boolean libraryTab;

    // ---- layout, measured from the screen and the text ----
    private boolean compact;
    private int labelW = 60, fieldW = 110, cols = 1, settingsMaxW = 200, leftW = SIDEBAR_W, builtW, builtH;

    // ---- components refreshed on change ----
    private FlowLayout root;
    private @Nullable FlowLayout sidebarList, settings, viewTabs;
    private LabelComponent header, layerLabel;
    private TextBoxComponent nameBox, search;
    private ButtonComponent undoButton, redoButton;
    private String shownLayer = "", hint = "";

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

    /** Edits part {@code index} (-1: the main shape), as picking it in the parts list does. */
    public void editPart(int index) {
        editing = Math.max(-1, Math.min(index, params.parts().size() - 1));
        if (root != null) refresh();
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
        boolean reopened = uiAdapter != null;
        super.init();
        if (!reopened || root == null) return;
        // owo keeps the old components on resize: rebuild when the room changed, else just show
        // what changed in the block picker, palette or pixel art screen
        if (width != builtW || height != builtH) rebuild();
        else refresh();
    }

    private void rebuild() {
        root.clearChildren();
        build(root);
    }

    @Override
    protected void build(FlowLayout root) {
        this.root = root;
        builtW = width;
        builtH = height;
        BlockColorCache.ensureReady();
        // Opaque enough that the HUD and chat behind it don't show through
        root.surface(Surface.flat(0xE6101010));
        root.padding(Insets.of(8));
        // No gap on the root: menus are mounted there, and a gap would shift the screen when one opens

        int inner = width - 16;
        compact = width < COMPACT_W;
        if (compact) {
            leftW = Math.max(160, Math.min(inner / 2, inner - MIN_PREVIEW_W - GAP));
            settingsMaxW = leftW - SETTINGS_CHROME;
        } else {
            leftW = SIDEBAR_W;
            settingsMaxW = inner - SIDEBAR_W - 2 * GAP - MIN_PREVIEW_W - SETTINGS_CHROME;
        }

        root.child(headerBar(inner));

        FlowLayout body = Containers.horizontalFlow(Sizing.fill(100), Sizing.expand());
        body.gap(GAP);
        sidebarList = null;
        settings = null;
        if (compact) {
            FlowLayout shared = Ui.panel(Containers.verticalFlow(Sizing.fixed(leftW), Sizing.fill(100)));
            shared.gap(4);
            FlowLayout tabs = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
            tabs.gap(2);
            tabs.child(pill(I18n.get("effortlessbuilding.screen.library"), libraryTab, null, () -> { libraryTab = true; rebuild(); }));
            tabs.child(pill(I18n.get("effortlessbuilding.screen.settings"), !libraryTab, null, () -> { libraryTab = false; rebuild(); }));
            shared.child(tabs);
            if (libraryTab) fillLibrary(shared, leftW - 10);
            else shared.child(settingsScroll());
            body.child(shared);
        } else {
            FlowLayout sidebar = Ui.panel(Containers.verticalFlow(Sizing.fixed(SIDEBAR_W), Sizing.fill(100)));
            fillLibrary(sidebar, SIDEBAR_W - 10);
            body.child(sidebar);
            FlowLayout settingsPanel = Ui.panel(Containers.verticalFlow(Sizing.content(), Sizing.fill(100)));
            settingsPanel.child(settingsScroll());
            body.child(settingsPanel);
        }
        body.child(previewPanel());
        root.child(body);
        root.child(bottomBar(inner));
        refresh();
    }

    /** Title, what is being edited, undo/redo and share codes. */
    private FlowLayout headerBar(int inner) {
        FlowLayout top = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.margins(Insets.bottom(GAP));
        top.gap(4).verticalAlignment(VerticalAlignment.CENTER);
        Component titleText = title.copy().withStyle(s -> s.withBold(true));
        top.child(Ui.label(titleText, 0xFFFFFF).margins(Insets.right(4)));
        undoButton = textButton("effortlessbuilding.screen.undo_short", "effortlessbuilding.screen.undo_design", this::undo);
        redoButton = textButton("effortlessbuilding.screen.redo_short", "effortlessbuilding.screen.redo_design", this::redo);
        ButtonComponent copy = textButton("effortlessbuilding.screen.copy_code", "effortlessbuilding.screen.copy_code.description", this::copyCode);
        ButtonComponent paste = textButton("effortlessbuilding.screen.paste_code", "effortlessbuilding.screen.paste_code.description", this::pasteCode);
        // Title, header, spacer and four buttons: six gaps, plus the margins
        int fixed = font.width(titleText) + 4 + buttonW("effortlessbuilding.screen.undo_short") + buttonW("effortlessbuilding.screen.redo_short")
                + 8 + buttonW("effortlessbuilding.screen.copy_code") + buttonW("effortlessbuilding.screen.paste_code") + 6 * 4;
        header = Ui.label(Component.empty(), ACCENT);
        header.horizontalSizing(Sizing.fixed(Math.max(20, inner - fixed)));
        top.child(header);
        top.child(Ui.hspace());
        top.child(w(undoButton));
        top.child(w(redoButton).margins(Insets.right(8)));
        top.child(w(copy));
        top.child(w(paste));
        return top;
    }

    /** Name box, save and palette on the left; build and done on the right. */
    private FlowLayout bottomBar(int inner) {
        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.margins(Insets.top(GAP));
        bottom.gap(4).verticalAlignment(VerticalAlignment.CENTER);
        ButtonComponent save = textButton("effortlessbuilding.screen.save_template", "effortlessbuilding.screen.save_template.description", this::saveTemplate);
        ButtonComponent palette = textButton("effortlessbuilding.screen.palette_button", "effortlessbuilding.screen.palette_button.description",
                () -> { if (minecraft != null) minecraft.setScreen(new PaletteScreen(this)); });
        ButtonComponent build = textButton("effortlessbuilding.screen.use_shape", "effortlessbuilding.screen.use_shape.description", this::useShape);
        ButtonComponent done = Components.button(Component.translatable("gui.done"), b -> onClose());
        int buildW = Math.max(80, buttonW("effortlessbuilding.screen.use_shape") + 12), doneW = Math.max(60, buttonW("gui.done") + 12);
        for (ButtonComponent b : List.of(save, palette, build, done)) w(b).verticalSizing(Sizing.fixed(20));
        w(build).horizontalSizing(Sizing.fixed(buildW));
        w(done).horizontalSizing(Sizing.fixed(doneW));
        // Name box, save, palette, spacer, build, done: five gaps, plus palette's margin and some air
        int others = buttonW("effortlessbuilding.screen.save_template") + buttonW("effortlessbuilding.screen.palette_button")
                + buildW + doneW + 5 * 4 + 4 + 8;
        nameBox = Components.textBox(Sizing.fixed(Math.max(60, Math.min(180, inner - others))));
        w(nameBox).verticalSizing(Sizing.fixed(20));
        nameBox.setMaxLength(40);
        nameBox.setHint(Component.translatable("effortlessbuilding.screen.template_name"));
        nameBox.text(nameDraft != null ? nameDraft : templateName != null ? templateName : defaultName());
        nameBox.onChanged().subscribe(s -> nameDraft = s);
        bottom.child(w(nameBox));
        bottom.child(w(save));
        bottom.child(w(palette).margins(Insets.left(4)));
        bottom.child(Ui.hspace());
        bottom.child(w(build));
        bottom.child(w(done));
        return bottom;
    }

    /** The settings in a scroll area; scrolling over a number field changes it instead. */
    private io.wispforest.owo.ui.core.Component settingsScroll() {
        settings = Containers.verticalFlow(Sizing.content(), Sizing.content());
        settings.gap(2);
        settings.padding(Insets.right(6)); // room for the scrollbar
        return Containers.verticalScroll(Sizing.content(), compact ? Sizing.expand() : Sizing.fill(100), settings).scrollbarThiccness(3);
    }

    /** Preview with its info drawn on top, the view and layer pickers, then materials and export. */
    private FlowLayout previewPanel() {
        FlowLayout panel = Ui.panel(Containers.verticalFlow(Sizing.expand(), Sizing.fill(100)));
        panel.gap(4);
        panel.child(new PreviewComponent().sizing(Sizing.fill(100), Sizing.expand()));

        FlowLayout tools = Containers.ltrTextFlow(Sizing.fill(100), Sizing.content());
        tools.gap(6);
        viewTabs = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        viewTabs.gap(2);
        tools.child(viewTabs);

        FlowLayout layers = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        layers.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        layers.child(w(Components.button(Component.literal("◀"), b -> preview.stepLayer(params, -1))).sizing(Sizing.fixed(14), Sizing.fixed(CONTROL_H)));
        layerLabel = Ui.label(Component.empty(), TEXT);
        layerLabel.margins(Insets.horizontal(3));
        layers.child(layerLabel);
        layers.child(w(Components.button(Component.literal("▶"), b -> preview.stepLayer(params, 1))).sizing(Sizing.fixed(14), Sizing.fixed(CONTROL_H)));
        tools.child(layers);

        FlowLayout actions = Containers.horizontalFlow(Sizing.content(), Sizing.content());
        actions.gap(2);
        ButtonComponent materials = textButton("effortlessbuilding.screen.materials", "effortlessbuilding.screen.materials.description",
                () -> { if (minecraft != null) minecraft.setScreen(new MaterialsScreen(this, preview.requiredItems(params))); });
        actions.child(w(materials).verticalSizing(Sizing.fixed(CONTROL_H)));
        String exportText = I18n.get("effortlessbuilding.screen.export") + "  ▼";
        ButtonComponent export = Components.button(Component.literal(exportText), b -> Ui.openMenu(this, root, b, "",
                List.of(".schem", ".nbt"), ext -> I18n.get(ext.equals(".schem") ? "effortlessbuilding.screen.export_schem"
                        : "effortlessbuilding.screen.export_nbt"), this::export, () -> {}));
        w(export).sizing(Sizing.fixed(font.width(exportText) + 14), Sizing.fixed(CONTROL_H));
        w(export).tooltip(wrapped(I18n.get("effortlessbuilding.screen.export_schem.description") + "\n"
                + I18n.get("effortlessbuilding.screen.export_nbt.description")));
        actions.child(w(export));
        tools.child(actions);
        panel.child(tools);
        return panel;
    }

    /** Rebuilds everything that depends on the design: list selection, settings, header, hints, views. */
    private void refresh() {
        if (editing >= params.parts().size()) editing = -1;
        String headerText = templateName != null ? templateName : I18n.get(params.type().getNameKey());
        if (!params.parts().isEmpty()) headerText += "  ·  " + I18n.get(params.parts().size() == 1 ? "effortlessbuilding.screen.part_count.one" : "effortlessbuilding.screen.part_count", params.parts().size());
        header.text(Component.literal(fit(headerText, header.horizontalSizing().get().value)));
        if (sidebarList != null) fillSidebar();
        if (settings != null) fillSettings();
        hint = params.type() != ShapeType.SCHEMATIC && params.sizing() == ShapeParams.Sizing.CLICKS
                ? I18n.get("effortlessbuilding.screen.click_sizing_hint")
                : params.sizing() == ShapeParams.Sizing.PATH ? I18n.get("effortlessbuilding.screen.path_sizing_hint")
                : editing >= 0 ? I18n.get(preview.view == ShapePreview.View.THREE_D
                        ? "effortlessbuilding.screen.drag_part_3d" : "effortlessbuilding.screen.drag_part_slice")
                : preview.view == ShapePreview.View.THREE_D ? I18n.get("effortlessbuilding.screen.drag_to_rotate") : "";
        fillViewTabs();
    }

    private void fillViewTabs() {
        if (viewTabs == null) return;
        viewTabs.clearChildren();
        for (ShapePreview.View view : ShapePreview.View.values()) {
            viewTabs.child(pill(I18n.get("effortlessbuilding.screen.view." + view.name().toLowerCase(Locale.ROOT)),
                    preview.view == view, "effortlessbuilding.screen.view.description", () -> {
                        preview.view = view;
                        preview.layer = -1;
                        refresh();
                    }));
        }
    }

    // =========================================================================
    // Library: shape types, built-in designs and templates
    // =========================================================================

    private void fillLibrary(FlowLayout panel, int listW) {
        search = Components.textBox(Sizing.fill(100));
        w(search).verticalSizing(Sizing.fixed(CONTROL_H)).margins(Insets.bottom(4));
        search.setHint(Component.translatable("effortlessbuilding.screen.search"));
        search.text(filter);
        search.onChanged().subscribe(text -> {
            filter = text;
            fillSidebar();
        });
        panel.child(w(search));
        sidebarList = Containers.verticalFlow(Sizing.fixed(listW - 6), Sizing.content());
        panel.child(Containers.verticalScroll(Sizing.fill(100), Sizing.expand(), sidebarList).scrollbarThiccness(3));
    }

    private void fillSidebar() {
        if (sidebarList == null) return;
        sidebarList.clearChildren();
        int listW = sidebarList.horizontalSizing().get().value;
        String query = filter.trim().toLowerCase(Locale.ROOT);
        boolean searching = !query.isEmpty();

        List<ShapeType> types = Arrays.stream(ShapeType.values()).filter(t -> matches(I18n.get(t.getNameKey()), query)).toList();
        if (groupHeading(sidebarList, "library.shapes", I18n.get("effortlessbuilding.screen.shapes"), types.size(), true, searching, this::fillSidebar)) {
            if (editing >= 0) {
                // While a part is being edited, the list sets that part's shape
                sidebarList.child(Ui.label(Component.translatable("effortlessbuilding.screen.shapes_for_part", editing + 1), MUTED)
                        .maxWidth(listW).margins(Insets.of(0, 3, 3, 0)));
            }
            for (ShapeType type : types) {
                boolean selected = (editing >= 0 || templateName == null) && current().type() == type;
                sidebarList.child(listRow(I18n.get(type.getNameKey()), TEXT, selected, () -> selectType(type), null,
                        typeIcon(type), listW));
            }
        }
        if (editing < 0) {
            List<ShapePresets.Preset> designs = ShapePresets.get().stream().filter(d -> matches(I18n.get(d.getNameKey()), query)).toList();
            if (!designs.isEmpty() && groupHeading(sidebarList, "library.designs", I18n.get("effortlessbuilding.screen.designs"),
                    designs.size(), true, searching, this::fillSidebar)) {
                for (ShapePresets.Preset design : designs) {
                    sidebarList.child(listRow(I18n.get(design.getNameKey()), DESIGN, false, () -> selectPreset(design), null, design.params(), listW));
                }
            }
        }
        List<ShapeClientState.Template> templates = ShapeClientState.getTemplates().stream().filter(t -> matches(t.name(), query)).toList();
        if (groupHeading(sidebarList, "library.templates", I18n.get("effortlessbuilding.screen.templates"), templates.size(), true,
                searching, this::fillSidebar)) {
            if (templates.isEmpty()) {
                sidebarList.child(Ui.label(Component.translatable(searching ? "effortlessbuilding.screen.no_matches"
                        : "effortlessbuilding.screen.no_templates"), 0x777777).maxWidth(listW).margins(Insets.left(3)));
            }
            for (ShapeClientState.Template template : templates) {
                boolean selected = template.name().equals(templateName) && editing < 0;
                sidebarList.child(listRow(template.name(), ACCENT, selected, () -> selectTemplate(template), () -> {
                    ShapeClientState.deleteTemplate(template.name());
                    if (template.name().equals(templateName)) templateName = null;
                    ShapeTemplateStorage.save();
                    refresh();
                }, template.params(), listW));
            }
        }
    }

    private static boolean matches(String name, String query) {
        return query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * A small top-down picture of a design: THUMB x THUMB colors (0 = empty), higher blocks lighter.
     * Parts with their own block show its color.
     */
    private static int[] thumbnail(ShapeParams params) {
        if (THUMBNAILS.size() > 512) THUMBNAILS.clear();
        return THUMBNAILS.computeIfAbsent(params, p -> {
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
            Arrays.fill(top, Integer.MIN_VALUE);
            for (var e : labels.entrySet()) {
                Cell c = e.getKey();
                int px = (c.x() - minX) * THUMB / span, pz = (c.z() - minZ) * THUMB / span;
                int i = pz * THUMB + px;
                if (c.y() <= top[i]) continue;
                top[i] = c.y();
                int base = 0xE8A33D;
                Integer label = e.getValue();
                if (label != null && label >= 0 && label < p.parts().size() && !p.parts().get(label).block().isEmpty()) {
                    Item item = itemOf(p.parts().get(label).block());
                    if (item != null) base = BlockColorCache.colorOf(item);
                }
                float light = maxY == minY ? 1f : 0.55f + 0.45f * (c.y() - minY) / (maxY - minY);
                int r = (int) (((base >> 16) & 0xFF) * light), g = (int) (((base >> 8) & 0xFF) * light), b = (int) ((base & 0xFF) * light);
                pixels[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
            return pixels;
        });
    }

    /** A shape type's default shape lying flat, so upright ones (arch, wheel, text) show their outline from above. */
    private static ShapeParams typeIcon(ShapeType type) {
        return ShapeParams.defaults(type).withOrientation(ShapeParams.Orientation.FLAT);
    }

    /** Draws a design's thumbnail; a file icon when it has no blocks of its own (a schematic). */
    private static class ThumbnailComponent extends BaseComponent {
        private final ShapeParams params;

        ThumbnailComponent(ShapeParams params) {
            this.params = params;
            sizing(Sizing.fixed(THUMB), Sizing.fixed(THUMB));
        }

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            int[] pixels = thumbnail(params);
            context.fill(x, y, x + THUMB, y + THUMB, 0xFF151515);
            if (Arrays.stream(pixels).allMatch(p -> p == 0)) {
                // A page with a folded corner
                context.fill(x + 4, y + 2, x + 10, y + 14, 0xFF8A8A8A);
                context.fill(x + 10, y + 5, x + 12, y + 14, 0xFF8A8A8A);
                context.fill(x + 10, y + 2, x + 12, y + 5, 0xFF5A5A5A);
                for (int line = 0; line < 3; line++) context.fill(x + 6, y + 6 + line * 2, x + 10, y + 7 + line * 2, 0xFF4A4A4A);
                return;
            }
            for (int i = 0; i < pixels.length; i++) {
                if (pixels[i] != 0) context.fill(x + i % THUMB, y + i / THUMB, x + i % THUMB + 1, y + i / THUMB + 1, pixels[i]);
            }
        }
    }

    /** A clickable list line with a thumbnail, highlighted when selected or hovered, with an optional delete button. */
    private FlowLayout listRow(String text, int color, boolean selected, Runnable select, @Nullable Runnable delete,
                               ShapeParams thumbnail, int rowW) {
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(ROW_H));
        row.verticalAlignment(VerticalAlignment.CENTER).padding(Insets.horizontal(2));
        clickable(row, selected ? SELECTED : Surface.BLANK, selected ? SELECTED : HOVER, select);
        row.child(new ThumbnailComponent(thumbnail).margins(Insets.right(4)));
        row.child(fittedLabel(text, color, rowW - THUMB - 10 - (delete != null ? 10 : 0)));
        if (delete != null) {
            row.child(Ui.hspace());
            row.child(iconLink("×", 0xC86464, "effortlessbuilding.screen.delete_template", delete));
        }
        return row;
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private void fillSettings() {
        if (settings == null) return;
        List<Row> rows = rows();
        measure(rows);
        int contentW = cols * (labelW + fieldW) + (cols - 1) * COL_GAP;
        settings.clearChildren();
        settings.child(partsList(contentW));
        for (Section section : Section.values()) {
            List<Row> inSection = rows.stream().filter(r -> r.section() == section).toList();
            if (inSection.isEmpty()) continue;
            String title = section == Section.PART
                    ? I18n.get("effortlessbuilding.screen.section.part", editing + 1, I18n.get(current().type().getNameKey()))
                    : I18n.get("effortlessbuilding.screen.section." + section.name().toLowerCase(Locale.ROOT));
            int changed = (int) inSection.stream().filter(Row::changed).count();
            FlowLayout heading = Containers.verticalFlow(Sizing.fixed(contentW), Sizing.content());
            if (!groupHeading(heading, "settings." + section.name(), fit(title, contentW - 40), changed, section.openByDefault, false, this::fillSettings)) {
                settings.child(heading);
                continue;
            }
            settings.child(heading);
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

    /** The main shape and its parts; click one to edit it. Each part shows how it combines. */
    private FlowLayout partsList(int width) {
        FlowLayout list = Containers.verticalFlow(Sizing.fixed(width), Sizing.content());
        list.gap(1);
        FlowLayout heading = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(CONTROL_H));
        heading.verticalAlignment(VerticalAlignment.CENTER).margins(Insets.bottom(2));
        heading.child(Ui.label(Component.literal(I18n.get("effortlessbuilding.screen.section.parts")).withStyle(s -> s.withBold(true)), Ui.SECTION)
                .tooltip(wrapped(I18n.get("effortlessbuilding.screen.editing.description"))));
        heading.child(Ui.hspace());
        String addText = I18n.get("effortlessbuilding.screen.add_part_button");
        ButtonComponent add = Components.button(Component.literal(addText),
                b -> Ui.openMenu(this, root, b, "", List.of(ShapeType.values()), t -> I18n.get(t.getNameKey()), this::addPart, () -> {}));
        add.active(params.parts().size() < ShapeParams.MAX_PARTS);
        heading.child(w(add).sizing(Sizing.fixed(font.width(addText) + 12), Sizing.fixed(CONTROL_H))
                .tooltip(wrapped(I18n.get("effortlessbuilding.screen.add_part"))));
        list.child(heading);
        for (int i = -1; i < params.parts().size(); i++) list.child(partRow(i, width));
        return list;
    }

    private FlowLayout partRow(int index, int width) {
        ShapeParams shape = index < 0 ? params : params.parts().get(index).shape();
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(ROW_H));
        row.verticalAlignment(VerticalAlignment.CENTER).padding(Insets.horizontal(2));
        boolean selected = index == editing;
        clickable(row, selected ? SELECTED : Surface.BLANK, selected ? SELECTED : HOVER, () -> {
            editing = index;
            refresh();
        });
        row.child(new ThumbnailComponent(typeIcon(shape.type())).margins(Insets.right(4)));
        String name = index < 0 ? I18n.get("effortlessbuilding.screen.main_shape") + ": " + I18n.get(shape.type().getNameKey())
                : partLabel(index);
        int room = width - THUMB - 10;
        if (index >= 0) {
            ShapeParams.Operation op = params.parts().get(index).operation();
            String opText = I18n.get(op.getNameKey());
            room -= font.width(opText) + 16;
            row.child(fittedLabel(name, selected ? 0xFFFFFF : TEXT, room));
            row.child(Ui.hspace());
            row.child(Ui.label(Component.literal(opText), operationColor(op)).margins(Insets.right(4)));
            row.child(iconLink("×", 0xC86464, "effortlessbuilding.screen.remove_part", () -> removePart(index)));
        } else {
            row.child(fittedLabel(name, selected ? 0xFFFFFF : TEXT, room));
        }
        return row;
    }

    private static int operationColor(ShapeParams.Operation op) {
        return switch (op) {
            case UNITE -> 0x8FD18F;
            case SUBTRACT -> 0xE58A8A;
            case INTERSECT -> 0x8AC4EA;
            case EXCLUDE -> 0xC9A2EA;
        };
    }

    private FlowLayout settingRow(Row row) {
        FlowLayout line = Containers.horizontalFlow(Sizing.fixed(labelW + fieldW), Sizing.fixed(ROW_H));
        line.verticalAlignment(VerticalAlignment.CENTER);
        String shown = fit(row.label(), labelW - 6);
        LabelComponent label = Ui.label(Component.literal(shown), LABEL);
        label.horizontalSizing(Sizing.fixed(labelW));
        // Hover: the full name when it is cut short, and what the setting does
        String tip = row.tooltipKey() != null && I18n.exists(row.tooltipKey()) ? I18n.get(row.tooltipKey()) : "";
        if (!shown.equals(row.label())) tip = tip.isEmpty() ? row.label() : row.label() + "\n" + tip;
        if (!tip.isEmpty()) label.tooltip(wrapped(tip));
        line.child(label);
        line.child(row.control().get());
        return line;
    }

    /**
     * Label column as wide as the longest label and controls as wide as the longest choice, within limits;
     * two columns when the preview keeps enough room, else one column squeezed into the room there is.
     */
    private void measure(List<Row> rows) {
        labelW = MIN_LABEL_W;
        for (Row row : rows) labelW = Math.max(labelW, font.width(row.label()) + 8);
        labelW = Math.min(labelW, MAX_LABEL_W);
        fieldW = MIN_FIELD_W;
        for (ShapeType.ParamSpec spec : current().type().params) {
            for (int i = 0; i < spec.options().size(); i++) fieldW = Math.max(fieldW, font.width(I18n.get(spec.getOptionKey(i))) + 22);
        }
        for (ShapeParams.Orientation o : ShapeParams.Orientation.values()) fieldW = Math.max(fieldW, font.width(I18n.get(o.getNameKey())) + 22);
        for (ShapeParams.Sizing z : ShapeParams.Sizing.values()) fieldW = Math.max(fieldW, font.width(I18n.get(z.getNameKey())) + 22);
        fieldW = Math.min(fieldW, MAX_FIELD_W);
        int twoColumns = 2 * (labelW + fieldW) + COL_GAP;
        cols = !compact && settingsMaxW - twoColumns >= TWO_COLUMN_PREVIEW_W - MIN_PREVIEW_W ? 2 : 1;
        int over = labelW + fieldW - settingsMaxW;
        if (cols == 1 && over > 0) {
            labelW = Math.max(MIN_LABEL_W, labelW - (over + 1) / 2);
            fieldW = Math.max(MIN_FIELD_W, settingsMaxW - labelW);
        }
    }

    /** All settings for what is being edited. */
    private List<Row> rows() {
        List<Row> list = new ArrayList<>();
        if (editing >= 0) {
            list.add(new Row(Section.PART, I18n.get("effortlessbuilding.screen.operation"), null, () -> choice(
                    I18n.get(currentPart().operation().getNameKey()), ShapeParams.Operation.values(), o -> I18n.get(o.getNameKey()),
                    o -> setCurrentPart(currentPart().withOperation(o)))));
            String[] axes = {"x", "y", "z"};
            for (int a = 0; a < 3; a++) {
                int axis = a;
                list.add(new Row(Section.PART, I18n.get("effortlessbuilding.screen.offset_" + axes[a]), null,
                        () -> number(offset(currentPart(), axis), 1, true, v -> setCurrentPart(withOffset(currentPart(), axis, (int) v)))));
            }
            int index = editing;
            list.add(new Row(Section.PART, I18n.get("effortlessbuilding.screen.part_block"), "effortlessbuilding.screen.part_block.description",
                    () -> blockControl(currentPart().block(), "effortlessbuilding.screen.pick_part_block",
                            id -> params = params.withPart(index, params.parts().get(index).withBlock(id)))));
            if (FramedCompat.isAvailable()) {
                list.add(new Row(Section.PART, I18n.get("effortlessbuilding.screen.part_camo"), "effortlessbuilding.screen.part_camo.description",
                        () -> blockControl(currentPart().camo(), "effortlessbuilding.screen.pick_part_camo",
                                id -> params = params.withPart(index, params.parts().get(index).withCamo(id)))));
            }
            list.add(new Row(Section.PART, I18n.get("effortlessbuilding.screen.part_repeat"), "effortlessbuilding.screen.part_repeat.description",
                    currentPart().repeat() > 1, () -> number(currentPart().repeat(), 1, true, v -> setCurrentPart(currentPart().withRepeat((int) v)))));
        }

        ShapeType type = current().type();
        if (type == ShapeType.SCHEMATIC) {
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.file"), "effortlessbuilding.screen.schematic.description",
                    this::schematicControl));
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.screen.pixel_art_row"), "effortlessbuilding.screen.pixel_art.description",
                    () -> fixedButton(I18n.get("effortlessbuilding.screen.pixel_art_open"), fieldW, () -> {
                        if (minecraft != null) minecraft.setScreen(new PixelArtScreen(this, name -> setCurrent(current().withSchematic(name))));
                    })));
        } else {
            if (type == ShapeType.TEXT) {
                list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.text"), "effortlessbuilding.shape.param.text.description", () -> {
                    TextBoxComponent box = Components.textBox(Sizing.fixed(fieldW));
                    w(box).verticalSizing(Sizing.fixed(CONTROL_H));
                    box.setMaxLength(200);
                    box.text(current().text());
                    box.onChanged().subscribe(text -> setCurrent(current().withSchematic(text)));
                    return w(box);
                }));
            } else {
                list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.size"), null,
                        () -> number(current().size(), 1, true, v -> setCurrent(current().withSize((int) v)))));
            }
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
                list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.hollow"), null, () -> toggle(current().hollow(),
                        I18n.get(current().hollow() ? "options.on" : "options.off"),
                        () -> setCurrent(current().withHollow(!current().hollow())))));
            }
        }
        if (editing < 0) {
            // A center axle in its own block; 2 by 2 when the shape has no single middle block
            list.add(new Row(Section.DESIGN, I18n.get("effortlessbuilding.shape.param.center_block"),
                    "effortlessbuilding.shape.param.center_block.description",
                    () -> blockControl(params.centerBlock(), "effortlessbuilding.screen.pick_center_block", id -> params = params.withCenterBlock(id))));
        }

        for (ShapeType.ParamSpec spec : type.params) {
            if (hidden(spec)) continue;
            String tooltip = spec.getNameKey() + ".description";
            boolean changed = Math.abs(current().get(spec.key()) - spec.defaultValue()) > 1e-6;
            Supplier<io.wispforest.owo.ui.core.Component> control;
            if (spec.options().equals(List.of("off", "on"))) {
                control = () -> {
                    boolean on = current().getInt(spec.key()) == 1;
                    return toggle(on, I18n.get(spec.getOptionKey(on ? 1 : 0)), () -> setCurrent(current().with(spec.key(), on ? 0 : 1)));
                };
            } else if (!spec.options().isEmpty()) {
                Integer[] options = new Integer[spec.options().size()];
                for (int i = 0; i < options.length; i++) options[i] = i;
                control = () -> choice(I18n.get(spec.getOptionKey(current().getInt(spec.key()))), options,
                        i -> I18n.get(spec.getOptionKey(i)), i -> setCurrent(current().with(spec.key(), i)));
            } else {
                double step = spec.integer() ? 1 : spec.step();
                control = () -> number(current().get(spec.key()), step, spec.integer(), v -> setCurrent(current().with(spec.key(), v)));
            }
            list.add(new Row(sectionOf(spec.key()), I18n.get(spec.getNameKey()), tooltip, changed, control));
        }
        return list;
    }

    private static int offset(ShapeParams.Part part, int axis) {
        return axis == 0 ? part.x() : axis == 1 ? part.y() : part.z();
    }

    private static ShapeParams.Part withOffset(ShapeParams.Part part, int axis, int value) {
        return part.withOffset(axis == 0 ? value : part.x(), axis == 1 ? value : part.y(), axis == 2 ? value : part.z());
    }

    private static Section sectionOf(String key) {
        if (key.equals(ShapeType.ROTATE_X) || key.equals(ShapeType.ROTATE_Y) || key.equals(ShapeType.ROTATE_Z)) return Section.ROTATION;
        if (ShapeType.isBuildSetting(key) || key.equals(TerrainBlender.ENABLED) || key.equals(TerrainBlender.MARGIN)
                || key.equals(ShapeMaterials.USE_SAVED_BLOCKS)) return Section.BUILD;
        // Where each layer of a layered shape (the gear) sits: many rows, rarely changed
        if (key.endsWith("_start") || (key.endsWith("_height") && !key.equals("height")) || key.equals("align")) return Section.HEIGHTS;
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

    // ---- controls: all fieldW wide and CONTROL_H high ----

    private io.wispforest.owo.ui.core.Component schematicControl() {
        List<String> names = SchematicLibrary.list();
        String name = current().schematic().isEmpty() && !names.isEmpty() ? names.getFirst() : current().schematic();
        if (!name.equals(current().schematic())) setCurrent(current().withSchematic(name));
        if (names.isEmpty()) {
            return fittedLabel(I18n.get("effortlessbuilding.screen.no_schematics"), MUTED, fieldW);
        }
        return choice(name, names.toArray(new String[0]), n -> n, n -> setCurrent(current().withSchematic(n)));
    }

    /** A button showing the current choice; clicking opens a menu with every option. */
    private <T> io.wispforest.owo.ui.core.Component choice(String shown, T[] options, Function<T, String> name, Consumer<T> pick) {
        String arrow = "  ▼";
        String text = fit(shown, fieldW - 8 - font.width(arrow));
        ButtonComponent button = Components.button(Component.literal(text + arrow),
                b -> Ui.openMenu(this, root, b, shown, List.of(options), name, pick, this::refresh));
        if (!text.equals(shown)) w(button).tooltip(Component.literal(shown));
        return w(button).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H));
    }

    /** An on/off button: green when on. */
    private io.wispforest.owo.ui.core.Component toggle(boolean on, String text, Runnable flip) {
        ButtonComponent button = Components.button(Component.literal(fit(text, fieldW - 8)).withColor(on ? 0x8FD18F : TEXT), b -> {
            flip.run();
            refresh();
        });
        return w(button).sizing(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H));
    }

    /** The block's icon and name; clicking opens the block picker. */
    private io.wispforest.owo.ui.core.Component blockControl(String itemId, String pickTitleKey, Consumer<String> set) {
        FlowLayout flow = Containers.horizontalFlow(Sizing.fixed(fieldW), Sizing.fixed(CONTROL_H));
        flow.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        Item item = itemOf(itemId);
        if (item != null && item != Items.AIR) {
            flow.child(Components.item(new ItemStack(item)).sizing(Sizing.fixed(16), Sizing.fixed(16)));
        } else {
            flow.child(Containers.verticalFlow(Sizing.fixed(16), Sizing.fixed(16)).surface(Surface.flat(0xFF151515).and(Surface.outline(0xFF3C3C3C))));
        }
        flow.child(fixedButton(blockLabel(itemId), fieldW - 18, () -> {
            if (minecraft == null) return;
            minecraft.setScreen(new BlockPickerScreen(this, Component.translatable(pickTitleKey),
                    picked -> set.accept(picked == null ? "" : BuiltInRegistries.ITEM.getKey(picked).toString())));
        }));
        return flow;
    }

    /** A number field with − and + buttons; scrolling over it steps too (Alt: 5 steps). */
    private io.wispforest.owo.ui.core.Component number(double value, double step, boolean integer, DoubleConsumer set) {
        FlowLayout flow = Containers.horizontalFlow(Sizing.fixed(fieldW), Sizing.content());
        flow.gap(2).verticalAlignment(VerticalAlignment.CENTER);
        TextBoxComponent box = Components.textBox(Sizing.fixed(fieldW - 32));
        w(box).verticalSizing(Sizing.fixed(CONTROL_H));
        box.text(format(value, integer));
        box.setFilter(s -> s.matches("-?\\d*" + (integer ? "" : "\\.?\\d*")));
        box.onChanged().subscribe(s -> {
            try {
                set.accept(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
            }
        });
        flow.child(w(Components.button(Component.literal("−"), b -> stepBox(box, -step, integer))).sizing(Sizing.fixed(14), Sizing.fixed(CONTROL_H)));
        flow.child(w(box));
        flow.child(w(Components.button(Component.literal("+"), b -> stepBox(box, step, integer))).sizing(Sizing.fixed(14), Sizing.fixed(CONTROL_H)));
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

    // ---- building blocks ----

    /** A button sized to its translated text, with an optional wrapped tooltip. */
    private ButtonComponent textButton(String key, @Nullable String tooltipKey, Runnable action) {
        ButtonComponent b = Components.button(Component.literal(I18n.get(key)), btn -> action.run());
        w(b).sizing(Sizing.fixed(buttonW(key)), Sizing.fixed(CONTROL_H));
        if (tooltipKey != null) w(b).tooltip(wrapped(I18n.get(tooltipKey)));
        return b;
    }

    /** Width of a {@link #textButton} for a translation key. */
    private int buttonW(String key) {
        return font.width(I18n.get(key)) + 12;
    }

    /** A button of a fixed width whose text is shortened to fit (the full text then shows on hover). */
    private io.wispforest.owo.ui.core.Component fixedButton(String text, int width, Runnable action) {
        String shown = fit(text, width - 8);
        ButtonComponent b = Components.button(Component.literal(shown), btn -> action.run());
        if (!shown.equals(text)) w(b).tooltip(Component.literal(text));
        return w(b).sizing(Sizing.fixed(width), Sizing.fixed(CONTROL_H));
    }

    /** A label cut to {@code width} with "…", the full text on hover when cut. */
    private LabelComponent fittedLabel(String text, int color, int width) {
        String shown = fit(text, width);
        LabelComponent label = Ui.label(Component.literal(shown), color);
        if (!shown.equals(text)) label.tooltip(Component.literal(text));
        return label;
    }

    /** A small text button (no frame) that lights up on hover, e.g. the × to delete a row. */
    private static LabelComponent iconLink(String text, int hoverColor, String tooltipKey, Runnable action) {
        LabelComponent link = Ui.label(Component.literal(text), 0x808080);
        link.margins(Insets.horizontal(2));
        link.tooltip(Component.translatable(tooltipKey));
        link.cursorStyle(CursorStyle.HAND);
        link.mouseEnter().subscribe(() -> link.color(Color.ofRgb(hoverColor)));
        link.mouseLeave().subscribe(() -> link.color(Color.ofRgb(0x808080)));
        link.mouseDown().subscribe((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
        return link;
    }

    /** A flat tab-like button; the selected one is outlined in the accent color. */
    private FlowLayout pill(String text, boolean selected, @Nullable String tooltipKey, Runnable action) {
        FlowLayout pill = Containers.horizontalFlow(Sizing.content(), Sizing.fixed(CONTROL_H));
        pill.padding(Insets.horizontal(6)).verticalAlignment(VerticalAlignment.CENTER);
        clickable(pill, selected ? PILL_ON : PILL, selected ? PILL_ON : PILL_HOVER, action);
        pill.child(Ui.label(Component.literal(text), selected ? ACCENT : TEXT));
        if (tooltipKey != null) pill.tooltip(wrapped(I18n.get(tooltipKey)));
        return pill;
    }

    /** Makes a row clickable (left button) with a hover surface and a hand cursor. */
    private static void clickable(FlowLayout flow, Surface normal, Surface hover, Runnable action) {
        flow.surface(normal);
        flow.cursorStyle(CursorStyle.HAND);
        flow.mouseEnter().subscribe(() -> flow.surface(hover));
        flow.mouseLeave().subscribe(() -> flow.surface(normal));
        flow.mouseDown().subscribe((x, y, button) -> {
            if (button != 0) return false;
            action.run();
            return true;
        });
    }

    /**
     * Adds a clickable group heading ("▾ Title", or "▸ Title" when folded, with a count) to {@code parent}.
     * {@code count}: for library groups the number of entries, for settings how many differ from the default.
     *
     * @return whether the group is open
     */
    private boolean groupHeading(FlowLayout parent, String id, String title, int count, boolean openByDefault, boolean forceOpen,
                                 Runnable redraw) {
        boolean open = forceOpen || OPEN.getOrDefault(id, openByDefault);
        boolean library = id.startsWith("library.");
        FlowLayout row = Containers.horizontalFlow(Sizing.fill(100), Sizing.fixed(12));
        row.verticalAlignment(VerticalAlignment.CENTER).margins(Insets.of(library && parent.children().isEmpty() ? 0 : 4, 1, 0, 0));
        clickable(row, Surface.BLANK, HOVER, () -> {
            OPEN.put(id, !open);
            redraw.run();
        });
        row.child(Ui.label(Component.literal(open ? "▾ " : "▸ "), MUTED));
        row.child(Ui.label(Component.literal(title).withStyle(s -> s.withBold(true)), Ui.SECTION));
        if (library) {
            row.child(Ui.label(Component.literal(" " + count), 0x777777));
        } else if (!open && count > 0) {
            row.child(Ui.label(Component.literal("  " + I18n.get("effortlessbuilding.screen.changed_count", count)), ACCENT));
        }
        parent.child(row);
        return open;
    }

    /** Hover text wrapped to a readable width. */
    private List<ClientTooltipComponent> wrapped(String text) {
        List<ClientTooltipComponent> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            for (FormattedCharSequence line : font.split(Component.literal(paragraph), TOOLTIP_W)) lines.add(ClientTooltipComponent.create(line));
        }
        return lines;
    }

    /** {@code text}, cut with "…" to fit {@code width} pixels. */
    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private String partLabel(int index) {
        ShapeParams.Part part = params.parts().get(index);
        return (index + 1) + ". " + I18n.get(part.shape().type().getNameKey());
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

    private void removePart(int index) {
        List<ShapeParams.Part> parts = new ArrayList<>(params.parts());
        parts.remove(index);
        params = params.withParts(parts);
        if (editing == index) editing = Math.min(index, parts.size() - 1);
        else if (editing > index) editing--;
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

    /** Loads a built-in design for editing; it is saved as a template only when the player saves it. */
    private void selectPreset(ShapePresets.Preset design) {
        params = design.params();
        templateName = null;
        editing = -1;
        setName(I18n.get(design.getNameKey()));
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

    private static @Nullable Item itemOf(String itemId) {
        ResourceLocation id = itemId.isEmpty() ? null : ResourceLocation.tryParse(itemId);
        return id == null ? null : BuiltInRegistries.ITEM.get(id);
    }

    /** A block's name from its item id, or "None" when empty. */
    private static String blockLabel(String itemId) {
        Item item = itemOf(itemId);
        if (item == null) return I18n.get("effortlessbuilding.screen.picker_none");
        return item.getDescription().getString();
    }

    private String defaultName() {
        String base = I18n.get(params.type().getNameKey());
        if (params.type() == ShapeType.TEXT) return base + " " + params.text().replace('|', ' ');
        return params.type() == ShapeType.SCHEMATIC ? base + " " + params.schematic() : base + " " + params.size();
    }

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

    /**
     * Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z): undo / redo. Ctrl+S or Enter in the name box: save the template.
     * Ctrl+F: search the library.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && keyCode == GLFW.GLFW_KEY_Z) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) redo(); else undo();
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if ((control && keyCode == GLFW.GLFW_KEY_S) || (enter && nameBox != null && nameBox.isFocused())) {
            saveTemplate();
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_F) {
            if (compact && !libraryTab) {
                libraryTab = true;
                rebuild();
            }
            if (search != null) uiAdapter.rootComponent.focusHandler().focus(w(search), io.wispforest.owo.ui.core.Component.FocusSource.KEYBOARD_CYCLE);
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
        if (undoButton != null) {
            undoButton.active(!undo.isEmpty());
            redoButton.active(!redo.isEmpty());
        }
        // Only touch the label when its text changes (each change re-lays out the screen)
        if (layerLabel != null) {
            String layer = preview.layerText(params);
            if (!layer.equals(shownLayer)) {
                shownLayer = layer;
                layerLabel.text(Component.literal(layer));
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** The 3D or slice preview: drag to turn, scroll to zoom (or step layers in slice views). Size and hints on top. */
    private class PreviewComponent extends BaseComponent {

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            preview.render(context, font, x, y, width, height, params);
            context.renderOutline(x, y, width, height, 0xFF3C3C3C);
            // Block count and size in the top left corner, one fact per line
            int lineY = y + 4;
            String size = preview.sizeText(params);
            // (nothing to build: the preview says so in the middle)
            for (String line : size.equals(I18n.get("effortlessbuilding.screen.nothing_to_build")) ? new String[0] : size.split(" {2}· {2}")) {
                drawBacked(context, fit(line, width - 12), x + 4, lineY, TEXT);
                lineY += 11;
            }
            // How to use the preview along the bottom, wrapped
            List<FormattedCharSequence> lines = hint.isEmpty() ? List.of() : font.split(Component.literal(hint), width - 12);
            int hintY = y + height - 4 - lines.size() * 10;
            for (FormattedCharSequence line : lines) {
                context.fill(x + 3, hintY - 1, x + 5 + font.width(line) + 1, hintY + 9, 0x90000000);
                context.drawString(font, line, x + 5, hintY, MUTED, false);
                hintY += 10;
            }
        }

        private void drawBacked(OwoUIDrawContext context, String text, int tx, int ty, int color) {
            context.fill(tx - 1, ty - 1, tx + font.width(text) + 3, ty + 9, 0x90000000);
            context.drawString(font, text, tx + 1, ty, color, false);
        }

        @Override
        public boolean canFocus(FocusSource source) {
            return source == FocusSource.MOUSE_CLICK;
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            super.onMouseDown(mouseX, mouseY, button);
            Arrays.fill(dragRest, 0);
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

package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.network.BuildModeHintC2SPacket;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.shape.*;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shape Generator: pick a parametric shape (or a template, or a schematic), tune it with a live
 * slice preview, then build it with SHAPE mode or save it as a template.
 * <p>
 * Layout: shape types and templates on the left, parameters in the middle (scrolls),
 * preview on the right, name/save/use along the bottom.
 */
public class ShapeGeneratorScreen extends Screen {

    // ---- layout ----
    private static final int PANEL_W = 440;
    private static final int PANEL_H = 262;
    private static final int LIST_W = 108;
    private static final int PARAM_X = LIST_W + 12;
    private static final int PARAM_W = 164;
    private static final int PREVIEW_X = PARAM_X + PARAM_W + 6;
    private static final int PREVIEW_W = PANEL_W - PREVIEW_X - 6;
    private static final int PREVIEW_H = 150;
    private static final int TOP = 22;
    private static final int ROW_H = 20;
    private static final int LIST_ROW_H = 13;
    private static final int PARAM_ROWS = 10;

    /** Which plane the preview slices through. */
    private enum View { TOP, FRONT, SIDE }

    // ---- state ----
    private ShapeParams params;
    /** Name of the template being edited, or null for an unsaved shape. */
    private String templateName;
    private int paramScroll = 0;
    private int listScroll = 0;
    private View view = View.TOP;
    /** Slice index along the view's depth axis, or -1 for all layers. */
    private int layer = -1;
    private String nameDraft = null;

    private ScreenWidgets widgets;
    private EditBox nameBox;

    // ---- preview cache ----
    private ShapeParams previewParams;
    private List<Cell> previewCells = List.of();

    public ShapeGeneratorScreen() {
        super(Component.translatable("effortlessbuilding.screen.shape_generator"));
        params = ShapeClientState.getActive();
    }

    // =========================================================================
    // Init
    // =========================================================================

    @Override
    protected void init() {
        widgets = new ScreenWidgets(font, this::addRenderableWidget);
        widgets.clear();
        int px = panelX(), py = panelY();

        buildListWidgets(px, py);
        buildParamWidgets(px + PARAM_X, py + TOP);
        buildPreviewWidgets(px + PREVIEW_X, py + TOP);

        // Bottom bar: template name, save, use, close
        int by = py + PANEL_H - 20;
        nameBox = new EditBox(font, px + PARAM_X, by, 100, 16, Component.translatable("effortlessbuilding.screen.template_name"));
        nameBox.setMaxLength(40);
        nameBox.setValue(nameDraft != null ? nameDraft : templateName != null ? templateName : defaultName());
        nameBox.setResponder(s -> nameDraft = s);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.save_template"), b -> saveTemplate())
                .bounds(px + PARAM_X + 104, by, 60, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.use_shape"), b -> useShape())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.use_shape.description")))
                .bounds(px + PREVIEW_X, by, 70, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(px + PANEL_W - 76, by, 70, 16).build());
    }

    private void buildListWidgets(int px, int py) {
        // Delete buttons for templates (shape types are plain clickable rows)
        List<ShapeClientState.Template> templates = ShapeClientState.getTemplates();
        int firstTemplateRow = ShapeType.values().length + 1;
        for (int i = 0; i < templates.size(); i++) {
            int row = firstTemplateRow + i - listScroll;
            if (row < 0 || row >= listRows()) continue;
            String name = templates.get(i).name();
            addRenderableWidget(Button.builder(Component.literal("×"), b -> {
                        ShapeClientState.deleteTemplate(name);
                        if (name.equals(templateName)) templateName = null;
                        ShapeTemplateStorage.save();
                        rebuildWidgets();
                    })
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.delete_template")))
                    .bounds(px + 4 + LIST_W - 12, py + TOP + row * LIST_ROW_H, 11, 11).build());
        }
    }

    /** One row per setting; the rows past {@link #PARAM_ROWS} scroll. */
    private void buildParamWidgets(int x, int y) {
        List<Consumer<Integer>> rows = new ArrayList<>();
        ShapeType type = params.type();

        if (type == ShapeType.SCHEMATIC) {
            rows.add(ry -> {
                List<String> names = SchematicLibrary.list();
                String current = params.schematic().isEmpty() && !names.isEmpty() ? names.getFirst() : params.schematic();
                if (!current.equals(params.schematic())) params = params.withSchematic(current);
                addRenderableWidget(Button.builder(Component.literal(names.isEmpty()
                                        ? I18n.get("effortlessbuilding.screen.no_schematics") : current),
                                b -> { cycleSchematic(names); rebuildWidgets(); })
                        .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.schematic.description")))
                        .bounds(x + ScreenWidgets.LABEL_W, ry, PARAM_W - ScreenWidgets.LABEL_W, 16).build());
            });
        } else {
            rows.add(ry -> widgets.addIntField(x, ry, String.valueOf(params.size()), v -> {
                params = params.withSize(v);
            }));
            rows.add(ry -> addCycleButton(x, ry, I18n.get(params.orientation().getNameKey()), () ->
                    params = params.withOrientation(next(ShapeParams.Orientation.values(), params.orientation()))));
            rows.add(ry -> addCycleButton(x, ry, I18n.get(params.sizing().getNameKey()), () ->
                    params = params.withSizing(next(ShapeParams.Sizing.values(), params.sizing()))));
            if (type.hollowable) {
                rows.add(ry -> widgets.addCheckbox(x + ScreenWidgets.LABEL_W, ry + 4, "", params.hollow(),
                        () -> { params = params.withHollow(!params.hollow()); rebuildWidgets(); }));
            }
            for (ShapeType.ParamSpec spec : type.params) {
                rows.add(ry -> {
                    if (spec.integer()) {
                        widgets.addIntField(x, ry, String.valueOf(params.getInt(spec.key())),
                                v -> params = params.with(spec.key(), v));
                    } else {
                        widgets.addDoubleField(x, ry, ScreenWidgets.formatDouble(params.get(spec.key())),
                                v -> params = params.with(spec.key(), v), 0.05);
                    }
                });
            }
        }

        paramScroll = Math.max(0, Math.min(paramScroll, rows.size() - PARAM_ROWS));
        for (int i = paramScroll; i < Math.min(rows.size(), paramScroll + PARAM_ROWS); i++) {
            rows.get(i).accept(y + (i - paramScroll) * ROW_H);
        }
        paramRowCount = rows.size();
    }

    private int paramRowCount;

    private void addCycleButton(int x, int y, String label, Runnable cycle) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> { cycle.run(); rebuildWidgets(); })
                .bounds(x + ScreenWidgets.LABEL_W, y, PARAM_W - ScreenWidgets.LABEL_W, 16).build());
    }

    private void buildPreviewWidgets(int x, int y) {
        int by = y + PREVIEW_H + 4;
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.view." + view.name().toLowerCase()),
                        b -> { view = next(View.values(), view); layer = -1; })
                .bounds(x, by, 44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepLayer(-1)).bounds(x + 48, by, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepLayer(1)).bounds(x + PREVIEW_W - 16, by, 16, 16).build());
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void saveTemplate() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = defaultName();
        ShapeClientState.saveTemplate(new ShapeClientState.Template(name, params));
        templateName = name;
        nameDraft = null;
        ShapeTemplateStorage.save();
        rebuildWidgets();
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

    private void selectType(ShapeType type) {
        if (type == params.type()) return;
        params = ShapeParams.defaults(type).withSizing(params.sizing());
        templateName = null;
        nameDraft = null;
        paramScroll = 0;
        layer = -1;
        rebuildWidgets();
    }

    private void selectTemplate(ShapeClientState.Template template) {
        params = template.params();
        templateName = template.name();
        nameDraft = null;
        paramScroll = 0;
        layer = -1;
        rebuildWidgets();
    }

    private void cycleSchematic(List<String> names) {
        if (names.isEmpty()) return;
        int i = names.indexOf(params.schematic());
        params = params.withSchematic(names.get((i + 1) % names.size()));
    }

    private void stepLayer(int dir) {
        int depth = previewDepth();
        if (depth <= 0) return;
        // -1 (all) → 0 → … → depth-1 → -1
        layer = layer + dir < -1 ? depth - 1 : layer + dir >= depth ? -1 : layer + dir;
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (widgets.handleCheckboxClick(mouseX, mouseY)) return true;

        int lx = panelX() + 4, ly = panelY() + TOP;
        if (mouseX >= lx && mouseX < lx + LIST_W - 14 && mouseY >= ly && mouseY < ly + listRows() * LIST_ROW_H) {
            int row = (int) (mouseY - ly) / LIST_ROW_H + listScroll;
            ShapeType[] types = ShapeType.values();
            List<ShapeClientState.Template> templates = ShapeClientState.getTemplates();
            if (row < types.length) {
                selectType(types[row]);
                return true;
            }
            int t = row - types.length - 1;
            if (t >= 0 && t < templates.size()) {
                selectTemplate(templates.get(t));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (widgets.handleScroll(mouseX, mouseY, scrollY)) return true;
        int px = panelX();
        int dir = scrollY > 0 ? -1 : 1;
        if (mouseX < px + LIST_W + 4) {
            int max = Math.max(0, ShapeType.values().length + 1 + ShapeClientState.getTemplates().size() - listRows());
            listScroll = Math.max(0, Math.min(max, listScroll + dir));
            rebuildWidgets();
            return true;
        }
        if (mouseX < px + PREVIEW_X) {
            paramScroll = Math.max(0, Math.min(Math.max(0, paramRowCount - PARAM_ROWS), paramScroll + dir));
            rebuildWidgets();
            return true;
        }
        stepLayer(-dir);
        return true;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 150 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();

        g.fill(px + LIST_W + 6, py + 2, px + LIST_W + 7, py + PANEL_H - 24, 0xFF555555);
        g.fill(px + PREVIEW_X - 4, py + 2, px + PREVIEW_X - 3, py + PANEL_H - 24, 0xFF555555);

        g.drawString(font, title, px + 5, py + 8, 0xFFFFFF);
        String header = templateName != null ? templateName : I18n.get(params.type().getNameKey());
        g.drawString(font, header, px + PARAM_X, py + 8, 0xFFFFFF);

        renderList(g, px + 4, py + TOP, mouseX, mouseY);
        renderParamLabels(g, px + PARAM_X, py + TOP);
        widgets.renderCheckboxes(g, mouseX, mouseY);
        renderPreview(g, px + PREVIEW_X, py + TOP);
    }

    private void renderList(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        ShapeType[] types = ShapeType.values();
        List<ShapeClientState.Template> templates = ShapeClientState.getTemplates();
        int rows = listRows();
        for (int r = 0; r < rows; r++) {
            int i = r + listScroll;
            int ry = y + r * LIST_ROW_H;
            String text;
            boolean selected;
            int color = 0xEEEEEE;
            if (i < types.length) {
                text = I18n.get(types[i].getNameKey());
                selected = templateName == null && params.type() == types[i];
            } else if (i == types.length) {
                g.drawString(font, I18n.get("effortlessbuilding.screen.templates"), x + 1, ry + 3, 0xAAAAAA);
                continue;
            } else if (i - types.length - 1 < templates.size()) {
                text = templates.get(i - types.length - 1).name();
                selected = text.equals(templateName);
                color = 0xFFD37F;
            } else {
                if (templates.isEmpty() && i == types.length + 1) {
                    g.drawString(font, I18n.get("effortlessbuilding.screen.no_templates"), x + 2, ry + 3, 0x777777);
                }
                continue;
            }
            boolean hovered = mouseX >= x && mouseX < x + LIST_W - 14 && mouseY >= ry && mouseY < ry + LIST_ROW_H;
            if (selected) g.fill(x, ry, x + LIST_W - 14, ry + LIST_ROW_H - 1, 0x40FFFFFF);
            else if (hovered) g.fill(x, ry, x + LIST_W - 14, ry + LIST_ROW_H - 1, 0x20FFFFFF);
            g.drawString(font, font.plainSubstrByWidth(text, LIST_W - 18), x + 2, ry + 3, color);
        }
    }

    private void renderParamLabels(GuiGraphics g, int x, int y) {
        List<String> labels = new ArrayList<>();
        ShapeType type = params.type();
        if (type == ShapeType.SCHEMATIC) {
            labels.add(I18n.get("effortlessbuilding.shape.param.file"));
        } else {
            labels.add(I18n.get("effortlessbuilding.shape.param.size"));
            labels.add(I18n.get("effortlessbuilding.shape.param.orientation"));
            labels.add(I18n.get("effortlessbuilding.shape.param.sizing"));
            if (type.hollowable) labels.add(I18n.get("effortlessbuilding.shape.param.hollow"));
            for (ShapeType.ParamSpec spec : type.params) labels.add(I18n.get(spec.getNameKey()));
        }
        for (int i = paramScroll; i < Math.min(labels.size(), paramScroll + PARAM_ROWS); i++) {
            g.drawString(font, font.plainSubstrByWidth(labels.get(i), ScreenWidgets.LABEL_W - 2),
                    x, y + (i - paramScroll) * ROW_H + 4, 0xCCCCCC);
        }
        if (labels.size() > PARAM_ROWS) {
            String more = (paramScroll + 1) + "–" + Math.min(labels.size(), paramScroll + PARAM_ROWS) + " / " + labels.size();
            g.drawString(font, more, x, y + PARAM_ROWS * ROW_H + 2, 0x777777);
        }
        if (type != ShapeType.SCHEMATIC && params.sizing() == ShapeParams.Sizing.CLICKS) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.click_sizing_hint"), x, y + PARAM_ROWS * ROW_H + 12, 0x999999);
        }
    }

    /** Slice (or projection when all layers) of the shape through the chosen plane, block count and size. */
    private void renderPreview(GuiGraphics g, int x, int y) {
        List<Cell> cells = cells();
        g.fill(x, y, x + PREVIEW_W, y + PREVIEW_H, 0xFF1E1E1E);
        if (cells.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.nothing_to_build"), x + PREVIEW_W / 2, y + PREVIEW_H / 2 - 4, 0x888888);
            return;
        }
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        }
        // Axes of the view: (horizontal, vertical-on-screen, depth)
        int[] axes = switch (view) {
            case TOP -> new int[]{0, 2, 1};
            case FRONT -> new int[]{0, 1, 2};
            case SIDE -> new int[]{2, 1, 0};
        };
        boolean flipVertical = view != View.TOP; // y up on screen
        int spanU = max[axes[0]] - min[axes[0]] + 1, spanV = max[axes[1]] - min[axes[1]] + 1;
        int depth = max[axes[2]] - min[axes[2]] + 1;
        if (layer >= depth) layer = -1;
        int scale = Math.max(1, Math.min((PREVIEW_W - 4) / spanU, (PREVIEW_H - 4) / spanV));
        int ox = x + (PREVIEW_W - spanU * scale) / 2, oy = y + (PREVIEW_H - spanV * scale) / 2;

        // Nearest-to-viewer depth per column (projection), or the chosen slice
        int[][] top = new int[spanU][spanV];
        for (int[] col : top) java.util.Arrays.fill(col, Integer.MIN_VALUE);
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            int d = v[axes[2]] - min[axes[2]];
            int u = v[axes[0]] - min[axes[0]], w = v[axes[1]] - min[axes[1]];
            if (layer >= 0) {
                if (d == layer) top[u][w] = d;
                else if (d == layer - 1 && top[u][w] == Integer.MIN_VALUE) top[u][w] = -2; // ghost of the layer below
            } else if (d > top[u][w]) {
                top[u][w] = d;
            }
        }
        for (int u = 0; u < spanU; u++) {
            for (int w = 0; w < spanV; w++) {
                int d = top[u][w];
                if (d == Integer.MIN_VALUE) continue;
                int sy = flipVertical ? spanV - 1 - w : w;
                int color;
                if (d == -2) {
                    color = 0xFF3A3A3A;
                } else {
                    float light = layer >= 0 ? 1f : 0.55f + 0.45f * (d + 1) / depth;
                    if (((u + w + d) & 1) == 1) light *= 0.85f; // alternating shades make blocks countable
                    color = shade(0xE8A33D, light);
                }
                g.fill(ox + u * scale, oy + sy * scale, ox + (u + 1) * scale - (scale > 3 ? 1 : 0),
                        oy + (sy + 1) * scale - (scale > 3 ? 1 : 0), color);
            }
        }

        int iy = y + PREVIEW_H + 24;
        String layerText = layer < 0 ? I18n.get("effortlessbuilding.screen.all_layers") : I18n.get("effortlessbuilding.screen.layer", layer + 1, depth);
        g.drawCenteredString(font, layerText, x + 48 + 16 + (PREVIEW_W - 48 - 32) / 2, y + PREVIEW_H + 8, 0xDDDDDD);
        g.drawString(font, I18n.get("effortlessbuilding.screen.block_count", cells.size()), x, iy, 0xDDDDDD);
        g.drawString(font, (max[0] - min[0] + 1) + " × " + (max[1] - min[1] + 1) + " × " + (max[2] - min[2] + 1)
                + " " + I18n.get("effortlessbuilding.screen.dimensions"), x, iy + 11, 0xAAAAAA);
        if (params.sizing() == ShapeParams.Sizing.CLICKS && params.type().resizable()) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.preview_at_size", params.size()), x, iy + 22, 0x888888);
        }
    }

    private int previewDepth() {
        List<Cell> cells = cells();
        if (cells.isEmpty()) return 0;
        int axis = switch (view) { case TOP -> 1; case FRONT -> 2; case SIDE -> 0; };
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Cell c : cells) {
            int v = axis == 0 ? c.x() : axis == 1 ? c.y() : c.z();
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo + 1;
    }

    private List<Cell> cells() {
        if (!params.equals(previewParams)) {
            int maxAxis = minecraft != null && minecraft.player != null
                    ? ServerConfig.INSTANCE.getMaxBlocksPerAxis(minecraft.player) : 1000;
            previewCells = params.type() == ShapeType.SCHEMATIC
                    ? SchematicLibrary.cells(params.schematic(), maxAxis)
                    : ShapeGenerator.generate(params, maxAxis);
            previewParams = params;
        }
        return previewCells;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int gr = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private String defaultName() {
        String base = I18n.get(params.type().getNameKey());
        return params.type() == ShapeType.SCHEMATIC ? base + " " + params.schematic() : base + " " + params.size();
    }

    private int listRows() { return (PANEL_H - TOP - 26) / LIST_ROW_H; }
    private int panelX() { return (width - PANEL_W) / 2; }
    private int panelY() { return (height - PANEL_H) / 2; }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

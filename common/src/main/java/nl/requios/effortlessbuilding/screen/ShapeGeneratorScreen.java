package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.compat.create.CreateGlue;
import nl.requios.effortlessbuilding.network.BuildModeHintC2SPacket;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.palette.BlockPalette;
import nl.requios.effortlessbuilding.palette.PaletteClientState;
import nl.requios.effortlessbuilding.shape.*;
import net.minecraft.core.BlockPos;
import nl.requios.effortlessbuilding.utilities.ShareCode;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.joml.Matrix4f;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Shape Generator: pick a parametric shape (or a template, or a schematic), combine more shapes into it
 * (unite, subtract, intersect, exclude), check it in a 3D or slice preview, then build it with
 * SHAPE mode or save it as a template.
 * <p>
 * Layout: shape types and templates on the left, settings in the middle (scrolls),
 * preview on the right, name/save/use along the bottom.
 */
public class ShapeGeneratorScreen extends Screen {

    // ---- layout (the panel grows with the window; the preview takes the extra space) ----
    private static final int LIST_W = 112;
    private static final int PARAM_X = LIST_W + 12;
    private static final int PARAM_W = 184;
    private static final int PREVIEW_X = PARAM_X + PARAM_W + 6;
    private static final int TOP = 22;
    private static final int ROW_H = 20;
    private static final int LIST_ROW_H = 13;
    private static final int BLOCK_COLOR = 0xE8A33D;
    private int panelW = 460, panelH = 262, previewW = 150, previewH = 150, paramRows = 10;

    /** How the preview shows the shape: rotatable 3D, or a slice through one plane. */
    private enum View { THREE_D, TOP, FRONT, SIDE }

    /** One settings row: its label and how to build its widgets at a given y. */
    private record Row(String label, IntConsumer build) {}

    // ---- state ----
    private ShapeParams params;
    /** Which part the settings edit: -1 for the main shape, otherwise an index into its parts. */
    private int editing = -1;
    /** Name of the template being edited, or null for an unsaved shape. */
    private String templateName;
    private int paramScroll = 0;
    private int listScroll = 0;
    private View view = View.THREE_D;
    /** Slice index along the view's depth axis (in 3D: build up to this layer), or -1 for all. */
    private int layer = -1;
    private String nameDraft = null;

    // ---- 3D camera ----
    private float yaw = 35, pitch = 28, zoom = 1;
    private boolean dragging = false;

    private ScreenWidgets widgets;
    private EditBox nameBox;
    private List<Row> rows = List.of();

    // ---- preview cache ----
    private ShapeParams previewParams;
    private List<Cell> previewCells = List.of();
    private ShapeParams facesParams;
    private Object facesPalette;
    private int facesLayer = Integer.MIN_VALUE;
    /** Visible cube faces: x, y, z of four corners, then an ARGB color, per face. */
    private float[][] faces = new float[0][];
    private float[] facesCenter = new float[3];
    private float facesRadius = 1;

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
    // Init
    // =========================================================================

    @Override
    protected void init() {
        panelW = Math.max(Math.min(width - 16, 960), Math.min(470, width));
        panelH = Math.max(Math.min(height - 16, 560), Math.min(262, height));
        previewW = panelW - PREVIEW_X - 6;
        previewH = panelH - TOP - 80;
        paramRows = Math.max(4, (panelH - TOP - 52) / ROW_H);
        BlockColorCache.ensureReady();
        widgets = new ScreenWidgets(font, this::addRenderableWidget);
        widgets.clear();
        if (editing >= params.parts().size()) editing = -1;
        int px = panelX(), py = panelY();

        buildListWidgets(px, py);
        buildRows(px + PARAM_X);
        paramScroll = Math.max(0, Math.min(paramScroll, rows.size() - paramRows));
        for (int i = paramScroll; i < Math.min(rows.size(), paramScroll + paramRows); i++) {
            rows.get(i).build().accept(py + TOP + (i - paramScroll) * ROW_H);
        }
        buildPreviewWidgets(px + PREVIEW_X, py + TOP);

        // Bottom bar: template name, save, use, close
        int by = py + panelH - 20;
        nameBox = new EditBox(font, px + PARAM_X, by, 90, 16, Component.translatable("effortlessbuilding.screen.template_name"));
        nameBox.setMaxLength(40);
        nameBox.setValue(nameDraft != null ? nameDraft : templateName != null ? templateName : defaultName());
        nameBox.setResponder(s -> nameDraft = s);
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.save_template"), b -> saveTemplate())
                .bounds(px + PARAM_X + 94, by, 44, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.palette_button"),
                        b -> { if (minecraft != null) minecraft.setScreen(new PaletteScreen(this)); })
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.palette_button.description")))
                .bounds(px + PARAM_X + 142, by, 42, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.use_shape"), b -> useShape())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.use_shape.description")))
                .bounds(px + PREVIEW_X, by, 70, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(px + panelW - 76, by, 70, 16).build());

        // Share: copy this design as a text code, or paste one from chat
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.copy_code"), b -> copyCode())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.copy_code.description")))
                .bounds(px + panelW - 6 - 70 - 4 - 70, py + 4, 70, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.paste_code"), b -> pasteCode())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.paste_code.description")))
                .bounds(px + panelW - 6 - 70, py + 4, 70, 14).build());
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
        nameDraft = json.has("name") ? json.get("name").getAsString() : null;
        paramScroll = 0;
        layer = -1;
        rebuildWidgets();
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

    /** All settings rows for what is being edited; only {@link #paramRows} are shown at once. */
    private void buildRows(int x) {
        List<Row> list = new ArrayList<>();
        int buttonX = x + ScreenWidgets.LABEL_W, buttonW = PARAM_W - ScreenWidgets.LABEL_W;

        // Which part to edit, plus add/remove
        list.add(new Row(I18n.get("effortlessbuilding.screen.editing"), y -> {
            addRenderableWidget(Button.builder(Component.literal(editingLabel()), b -> {
                        editing = editing + 1 >= params.parts().size() ? -1 : editing + 1;
                        paramScroll = 0;
                        rebuildWidgets();
                    })
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.editing.description")))
                    .bounds(buttonX, y, buttonW - 34, 16).build());
            Button add = addRenderableWidget(Button.builder(Component.literal("+"), b -> addPart())
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.add_part")))
                    .bounds(buttonX + buttonW - 32, y, 16, 16).build());
            add.active = params.parts().size() < ShapeParams.MAX_PARTS;
            Button remove = addRenderableWidget(Button.builder(Component.literal("×"), b -> removePart())
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.remove_part")))
                    .bounds(buttonX + buttonW - 16, y, 16, 16).build());
            remove.active = editing >= 0;
        }));

        if (editing >= 0) {
            list.add(new Row(I18n.get("effortlessbuilding.screen.operation"), y -> addCycleButton(x, y,
                    I18n.get(currentPart().operation().getNameKey()),
                    () -> setCurrentPart(currentPart().withOperation(next(ShapeParams.Operation.values(), currentPart().operation()))))));
            list.add(new Row(I18n.get("effortlessbuilding.screen.offset_x"), y -> widgets.addIntField(x, y, String.valueOf(currentPart().x()),
                    v -> setCurrentPart(currentPart().withOffset(v, currentPart().y(), currentPart().z())))));
            list.add(new Row(I18n.get("effortlessbuilding.screen.offset_y"), y -> widgets.addIntField(x, y, String.valueOf(currentPart().y()),
                    v -> setCurrentPart(currentPart().withOffset(currentPart().x(), v, currentPart().z())))));
            list.add(new Row(I18n.get("effortlessbuilding.screen.offset_z"), y -> widgets.addIntField(x, y, String.valueOf(currentPart().z()),
                    v -> setCurrentPart(currentPart().withOffset(currentPart().x(), currentPart().y(), v)))));
        }

        ShapeParams shape = current();
        ShapeType type = shape.type();
        if (type == ShapeType.SCHEMATIC) {
            list.add(new Row(I18n.get("effortlessbuilding.shape.param.file"), y -> {
                List<String> names = SchematicLibrary.list();
                String name = current().schematic().isEmpty() && !names.isEmpty() ? names.getFirst() : current().schematic();
                if (!name.equals(current().schematic())) setCurrent(current().withSchematic(name));
                addRenderableWidget(Button.builder(Component.literal(names.isEmpty()
                                        ? I18n.get("effortlessbuilding.screen.no_schematics") : name),
                                b -> { cycleSchematic(names); rebuildWidgets(); })
                        .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.schematic.description")))
                        .bounds(buttonX, y, buttonW, 16).build());
            }));
            list.add(new Row(I18n.get("effortlessbuilding.screen.pixel_art_row"), y ->
                    addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.pixel_art_open"), b -> {
                                if (minecraft != null) minecraft.setScreen(new PixelArtScreen(this,
                                        name -> setCurrent(current().withSchematic(name))));
                            })
                            .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.pixel_art.description")))
                            .bounds(buttonX, y, buttonW, 16).build())));
            for (ShapeType.ParamSpec spec : type.params) {
                if (hidden(spec)) continue;
                list.add(new Row(I18n.get(spec.getNameKey()), y -> {
                    if (!spec.options().isEmpty()) {
                        int chosen = current().getInt(spec.key());
                        addCycleButton(x, y, I18n.get(spec.getOptionKey(chosen)),
                                () -> setCurrent(current().with(spec.key(), (chosen + 1) % spec.options().size())));
                    } else {
                        widgets.addDoubleField(x, y, ScreenWidgets.formatDouble(current().get(spec.key())),
                                v -> setCurrent(current().with(spec.key(), v)), spec.step());
                    }
                }));
            }
        } else {
            list.add(new Row(I18n.get("effortlessbuilding.shape.param.size"), y ->
                    widgets.addIntField(x, y, String.valueOf(current().size()), v -> setCurrent(current().withSize(v)))));
            list.add(new Row(I18n.get("effortlessbuilding.shape.param.orientation"), y -> addCycleButton(x, y,
                    I18n.get(current().orientation().getNameKey()),
                    () -> setCurrent(current().withOrientation(next(ShapeParams.Orientation.values(), current().orientation()))))));
            if (editing < 0) {
                // Sizing belongs to the whole shape: parts scale along with it
                list.add(new Row(I18n.get("effortlessbuilding.shape.param.sizing"), y -> addCycleButton(x, y,
                        I18n.get(params.sizing().getNameKey()),
                        () -> params = params.withSizing(next(ShapeParams.Sizing.values(), params.sizing())))));
            }
        }
        if (editing < 0) {
            // A center axle in its own block; 2 by 2 when the shape has no single middle block
            list.add(new Row(I18n.get("effortlessbuilding.shape.param.center_block"), y ->
                    addRenderableWidget(Button.builder(Component.literal(centerBlockLabel()), b -> {
                                if (minecraft == null) return;
                                minecraft.setScreen(new BlockPickerScreen(this,
                                        Component.translatable("effortlessbuilding.screen.pick_center_block"),
                                        item -> params = params.withCenterBlock(item == null ? ""
                                                : BuiltInRegistries.ITEM.getKey(item).toString())));
                            })
                            .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.shape.param.center_block.description")))
                            .bounds(buttonX, y, buttonW, 16).build())));
        }
        if (type != ShapeType.SCHEMATIC) {
            if (type.hollowable) {
                list.add(new Row(I18n.get("effortlessbuilding.shape.param.hollow"), y ->
                        widgets.addCheckbox(buttonX, y + 4, "", current().hollow(),
                                () -> { setCurrent(current().withHollow(!current().hollow())); rebuildWidgets(); })));
            }
            for (ShapeType.ParamSpec spec : type.params) {
                if (hidden(spec)) continue;
                list.add(new Row(I18n.get(spec.getNameKey()), y -> {
                    if (!spec.options().isEmpty()) {
                        int chosen = current().getInt(spec.key());
                        addCycleButton(x, y, I18n.get(spec.getOptionKey(chosen)),
                                () -> setCurrent(current().with(spec.key(), (chosen + 1) % spec.options().size())));
                    } else if (spec.integer()) {
                        widgets.addIntField(x, y, String.valueOf(current().getInt(spec.key())),
                                v -> setCurrent(current().with(spec.key(), v)));
                    } else {
                        widgets.addDoubleField(x, y, ScreenWidgets.formatDouble(current().get(spec.key())),
                                v -> setCurrent(current().with(spec.key(), v)), spec.step());
                    }
                }));
            }
        }
        rows = list;
    }

    /** Super glue (only with Create) and ground blending are offered on the main shape only. */
    private boolean hidden(ShapeType.ParamSpec spec) {
        if (editing >= 0 && ShapeType.isBuildSetting(spec.key())) return true;
        if (spec.key().equals(ShapeType.SUPER_GLUE)) return !CreateGlue.isAvailable();
        if (spec.key().equals(ShapeType.PATH_SPACING) || spec.key().equals(ShapeType.PATH_ALIGN)) {
            return params.sizing() != ShapeParams.Sizing.PATH;
        }
        // Ground blending belongs to the whole build, not to a part
        return editing >= 0 && (spec.key().equals(TerrainBlender.ENABLED) || spec.key().equals(TerrainBlender.MARGIN));
    }

    private void addCycleButton(int x, int y, String label, Runnable cycle) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> { cycle.run(); rebuildWidgets(); })
                .bounds(x + ScreenWidgets.LABEL_W, y, PARAM_W - ScreenWidgets.LABEL_W, 16).build());
    }

    private void buildPreviewWidgets(int x, int y) {
        int by = y + previewH + 4;
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.view." + view.name().toLowerCase()),
                        b -> { view = next(View.values(), view); layer = -1; rebuildWidgets(); })
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.view.description")))
                .bounds(x, by, 44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> stepLayer(-1)).bounds(x + 48, by, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> stepLayer(1)).bounds(x + previewW - 16, by, 16, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.materials"), b -> {
                    if (minecraft != null) minecraft.setScreen(new MaterialsScreen(this, requiredItems()));
                })
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.materials.description")))
                .bounds(x + previewW - 76, by + 22, 76, 16).build());
        addRenderableWidget(Button.builder(Component.literal(".nbt"), b -> export(".nbt"))
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.export_nbt.description")))
                .bounds(x + previewW - 76 - 4 - 36, by + 22, 36, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.export_schem"), b -> export(".schem"))
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.export_schem.description")))
                .bounds(x + previewW - 76 - 4 - 36 - 4 - 80, by + 22, 80, 16).build());
    }

    /** Saves exactly what would be built (saved blocks, middle block, palette, else the held block) as a schematic. */
    private void export(String extension) {
        if (minecraft == null || minecraft.player == null) return;
        List<Cell> cells = cells();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Cell c : cells) { minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y()); }
        Item held = minecraft.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem b ? b : null;
        Map<Cell, BlockState> blocks = new HashMap<>();
        for (Cell c : cells) {
            BlockState saved = previewMaterials.get(c);
            if (saved != null && !previewAxle.contains(c)) { blocks.put(c, saved); continue; }
            Item item = cellItem(c, minY, maxY);
            if (item == null) item = held;
            if (item instanceof net.minecraft.world.item.BlockItem blockItem) blocks.put(c, blockItem.getBlock().defaultBlockState());
        }
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

    /** Blocks the build needs, per item: saved schematic blocks, the middle block, palette, else the held block. */
    private Map<Item, Integer> requiredItems() {
        List<Cell> cells = cells();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Cell c : cells) { minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y()); }
        Item held = minecraft != null && minecraft.player != null
                && minecraft.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem b ? b : null;
        Map<Item, Integer> out = new HashMap<>();
        for (Cell c : cells) {
            Item item = cellItem(c, minY, maxY);
            if (item == null) item = held;
            if (item != null) out.merge(item, 1, Integer::sum);
        }
        return out;
    }

    private String editingLabel() {
        if (editing < 0) return I18n.get("effortlessbuilding.screen.main_shape");
        ShapeParams.Part part = currentPart();
        return (editing + 1) + ". " + I18n.get(part.operation().getNameKey()) + " " + I18n.get(part.shape().type().getNameKey());
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private void addPart() {
        if (params.parts().size() >= ShapeParams.MAX_PARTS) return;
        // Start with a smaller copy of the main shape, cutting a hole: the most common first step
        ShapeParams shape = ShapeParams.defaults(params.type() == ShapeType.SCHEMATIC ? ShapeType.ELLIPSOID : params.type())
                .withSize(Math.max(1, params.size() / 2));
        List<ShapeParams.Part> parts = new ArrayList<>(params.parts());
        parts.add(new ShapeParams.Part(shape, ShapeParams.Operation.SUBTRACT, 0, 0, 0));
        params = params.withParts(parts);
        editing = parts.size() - 1;
        paramScroll = 0;
        rebuildWidgets();
    }

    private void removePart() {
        if (editing < 0) return;
        List<ShapeParams.Part> parts = new ArrayList<>(params.parts());
        parts.remove(editing);
        params = params.withParts(parts);
        editing = Math.min(editing, parts.size() - 1);
        paramScroll = 0;
        rebuildWidgets();
    }

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

    /** Picking a shape type changes what is being edited: the main shape (keeping its parts) or a part. */
    private void selectType(ShapeType type) {
        if (type == current().type()) return;
        if (editing < 0) {
            params = ShapeParams.defaults(type).withSizing(params.sizing()).withParts(params.parts());
            templateName = null;
            nameDraft = null;
        } else {
            setCurrent(ShapeParams.defaults(type));
        }
        paramScroll = 0;
        layer = -1;
        rebuildWidgets();
    }

    private void selectTemplate(ShapeClientState.Template template) {
        params = template.params();
        templateName = template.name();
        editing = -1;
        nameDraft = null;
        paramScroll = 0;
        layer = -1;
        rebuildWidgets();
    }

    private void cycleSchematic(List<String> names) {
        if (names.isEmpty()) return;
        int i = names.indexOf(current().schematic());
        setCurrent(current().withSchematic(names.get((i + 1) % names.size())));
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
        if (view == View.THREE_D && inPreview(mouseX, mouseY)) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        int px = panelX();
        int dir = scrollY > 0 ? -1 : 1;
        if (mouseX < px + LIST_W + 4) {
            int max = Math.max(0, ShapeType.values().length + 1 + ShapeClientState.getTemplates().size() - listRows());
            listScroll = Math.max(0, Math.min(max, listScroll + dir * BlockGrid.scrollStep(listRows())));
            rebuildWidgets();
            return true;
        }
        if (mouseX < px + PREVIEW_X) {
            int step = dir * BlockGrid.scrollStep(paramRows);
            paramScroll = Math.max(0, Math.min(Math.max(0, rows.size() - paramRows), paramScroll + step));
            rebuildWidgets();
            return true;
        }
        if (view == View.THREE_D && inPreview(mouseX, mouseY)) {
            zoom = Math.max(0.3f, Math.min(6f, zoom * (scrollY > 0 ? 1.15f : 1 / 1.15f)));
        } else {
            stepLayer(-dir);
        }
        return true;
    }

    private boolean inPreview(double mouseX, double mouseY) {
        int x = panelX() + PREVIEW_X, y = panelY() + TOP;
        return mouseX >= x && mouseX < x + previewW && mouseY >= y && mouseY < y + previewH;
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

        g.fill(px + LIST_W + 6, py + 2, px + LIST_W + 7, py + panelH - 24, 0xFF555555);
        g.fill(px + PREVIEW_X - 4, py + 2, px + PREVIEW_X - 3, py + panelH - 24, 0xFF555555);

        g.drawString(font, title, px + 5, py + 8, 0xFFFFFF);
        String header = templateName != null ? templateName : I18n.get(params.type().getNameKey());
        if (!params.parts().isEmpty()) header += " + " + params.parts().size();
        g.drawString(font, font.plainSubstrByWidth(header, PARAM_W), px + PARAM_X, py + 8, 0xFFFFFF);

        renderList(g, px + 4, py + TOP, mouseX, mouseY);
        renderRowLabels(g, px + PARAM_X, py + TOP);
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
                selected = (editing >= 0 || templateName == null) && current().type() == types[i];
            } else if (i == types.length) {
                g.drawString(font, I18n.get("effortlessbuilding.screen.templates"), x + 1, ry + 3, 0xAAAAAA);
                continue;
            } else if (i - types.length - 1 < templates.size()) {
                text = templates.get(i - types.length - 1).name();
                selected = text.equals(templateName) && editing < 0;
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

    private void renderRowLabels(GuiGraphics g, int x, int y) {
        for (int i = paramScroll; i < Math.min(rows.size(), paramScroll + paramRows); i++) {
            g.drawString(font, font.plainSubstrByWidth(rows.get(i).label(), ScreenWidgets.LABEL_W - 2),
                    x, y + (i - paramScroll) * ROW_H + 4, 0xCCCCCC);
        }
        if (rows.size() > paramRows) {
            String more = (paramScroll + 1) + "–" + Math.min(rows.size(), paramScroll + paramRows) + " / " + rows.size()
                    + "  " + I18n.get("effortlessbuilding.screen.scroll_for_more");
            g.drawString(font, more, x, y + paramRows * ROW_H + 2, 0x777777);
        }
        if (params.type() != ShapeType.SCHEMATIC && params.sizing() == ShapeParams.Sizing.CLICKS) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.click_sizing_hint"), x, y + paramRows * ROW_H + 12, 0x999999);
        } else if (params.sizing() == ShapeParams.Sizing.PATH) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.path_sizing_hint"), x, y + paramRows * ROW_H + 12, 0x999999);
        }
    }

    /** 3D view or a slice/projection through one plane, plus block count and size. */
    private void renderPreview(GuiGraphics g, int x, int y) {
        List<Cell> cells = cells();
        g.fill(x, y, x + previewW, y + previewH, 0xFF1E1E1E);
        if (cells.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.nothing_to_build"), x + previewW / 2, y + previewH / 2 - 4, 0x888888);
            return;
        }
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        }
        int depth = previewDepth();
        if (layer >= depth) layer = -1;

        if (view == View.THREE_D) render3D(g, x, y, cells, min[1]);
        else renderSlice(g, x, y, cells, min, max, depth);

        int iy = y + previewH + 24;
        String layerText = layer < 0 ? I18n.get("effortlessbuilding.screen.all_layers")
                : I18n.get(view == View.THREE_D ? "effortlessbuilding.screen.up_to_layer" : "effortlessbuilding.screen.layer", layer + 1, depth);
        g.drawCenteredString(font, layerText, x + 48 + 16 + (previewW - 48 - 32) / 2, y + previewH + 8, 0xDDDDDD);
        g.drawString(font, I18n.get("effortlessbuilding.screen.block_count", cells.size()), x, iy, 0xDDDDDD);
        g.drawString(font, (max[0] - min[0] + 1) + " × " + (max[1] - min[1] + 1) + " × " + (max[2] - min[2] + 1)
                + " " + I18n.get("effortlessbuilding.screen.dimensions"), x, iy + 11, 0xAAAAAA);
        if (view == View.THREE_D) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.drag_to_rotate"), x, iy + 22, 0x777777);
        } else if (params.sizing() == ShapeParams.Sizing.CLICKS && params.type().resizable()) {
            g.drawString(font, I18n.get("effortlessbuilding.screen.preview_at_size", params.size()), x, iy + 22, 0x888888);
        }
    }

    /** Solid cubes with a light per face direction, depth-tested, rotatable by dragging. */
    private void render3D(GuiGraphics g, int x, int y, List<Cell> cells, int minY) {
        updateFaces(cells, minY);
        float scale = zoom * 0.46f * Math.min(previewW, previewH) / facesRadius;

        g.enableScissor(x, y, x + previewW, y + previewH);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + previewW / 2f, y + previewH / 2f, 100);
        // World y up = screen up. Depth is squashed (order kept) so the cubes stay between
        // the panel background (z 0) and tooltips (z 400) at any zoom.
        pose.scale(scale, -scale, Math.min(scale, 60f / facesRadius));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-facesCenter[0], -facesCenter[1], -facesCenter[2]);
        Matrix4f matrix = pose.last().pose();
        VertexConsumer buffer = g.bufferSource().getBuffer(RenderType.debugQuads());
        for (float[] f : faces) {
            int color = (int) f[12];
            for (int v = 0; v < 4; v++) buffer.addVertex(matrix, f[v * 3], f[v * 3 + 1], f[v * 3 + 2]).setColor(color);
        }
        g.flush();
        pose.popPose();
        g.disableScissor();
    }

    /** Rebuilds the visible-face list when the shape or the shown layers change. */
    private void updateFaces(List<Cell> cells, int minY) {
        Object paletteKey = paletteKey();
        if (params.equals(facesParams) && layer == facesLayer && paletteKey.equals(facesPalette)) return;
        facesParams = params;
        facesLayer = layer;
        facesPalette = paletteKey;
        int maxY = Integer.MIN_VALUE;
        for (Cell c : cells) maxY = Math.max(maxY, c.y());

        Set<Cell> shown = new HashSet<>();
        for (Cell c : cells) if (layer < 0 || c.y() - minY <= layer) shown.add(c);

        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Cell c : cells) {
            float[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a] + 1); }
        }
        facesCenter = new float[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        facesRadius = Math.max(1, (float) Math.sqrt(sq(hi[0] - lo[0]) + sq(hi[1] - lo[1]) + sq(hi[2] - lo[2])) / 2);

        // Neighbor offset, brightness, and the four corners of that face on a unit cube
        int[][] dirs = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        float[] light = {1f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f};
        float[][][] corners = {
                {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
                {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
                {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
                {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
                {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
                {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};
        List<float[]> out = new ArrayList<>();
        for (Cell c : shown) {
            boolean topLayer = layer >= 0 && c.y() - minY == layer;
            float checker = ((c.x() + c.y() + c.z()) & 1) == 1 ? 0.88f : 1f; // alternating shades make blocks countable
            for (int d = 0; d < 6; d++) {
                if (shown.contains(new Cell(c.x() + dirs[d][0], c.y() + dirs[d][1], c.z() + dirs[d][2]))) continue;
                float[] f = new float[13];
                for (int v = 0; v < 4; v++) {
                    f[v * 3] = c.x() + corners[d][v][0];
                    f[v * 3 + 1] = c.y() + corners[d][v][1];
                    f[v * 3 + 2] = c.z() + corners[d][v][2];
                }
                float dim = layer >= 0 && !topLayer ? 0.6f : 1f; // layers below the chosen one are dimmed
                f[12] = shade(cellColor(c, minY, maxY), light[d] * checker * dim);
                out.add(f);
            }
        }
        faces = out.toArray(new float[0][]);
    }

    private static float sq(float v) { return v * v; }

    /** The block color a cell gets: from the palette when it is on, else the plain preview color. */
    private int cellColor(Cell c, int minY, int maxY) {
        Item item = cellItem(c, minY, maxY);
        return item != null ? BlockColorCache.colorOf(item) : BLOCK_COLOR;
    }

    /** The block a cell gets on its own (middle block, saved schematic block, palette), or null for the held block. */
    private @org.jetbrains.annotations.Nullable Item cellItem(Cell c, int minY, int maxY) {
        if (previewAxle.contains(c)) {
            ResourceLocation id = ResourceLocation.tryParse(params.centerBlock());
            return id == null ? null : BuiltInRegistries.ITEM.get(id);
        }
        BlockState saved = previewMaterials.get(c);
        if (saved != null) {
            Item item = saved.getBlock().asItem();
            return item == net.minecraft.world.item.Items.AIR ? null : item;
        }
        List<Item> blocks = paletteBlocks();
        if (blocks.isEmpty()) return null;
        BlockPalette palette = PaletteClientState.getPalette();
        int index = palette.pattern().index(blocks.size(), palette.band(), c.x(), c.y(), c.z(), minY, maxY,
                BlockPos.asLong(c.x(), c.y(), c.z()));
        return blocks.get(index);
    }

    private List<Item> paletteBlocks() {
        BlockPalette palette = PaletteClientState.getActive();
        if (palette == null || minecraft == null || minecraft.player == null) return List.of();
        return palette.resolve(minecraft.player);
    }

    /** Changes when anything that affects preview colors changes. */
    private Object paletteKey() {
        return List.of(paletteBlocks(), PaletteClientState.getPalette(), BlockColorCache.colors().size());
    }

    /** Slice (or projection when all layers) through the chosen plane. */
    private void renderSlice(GuiGraphics g, int x, int y, List<Cell> cells, int[] min, int[] max, int depth) {
        // Axes of the view: (horizontal, vertical-on-screen, depth)
        int[] axes = switch (view) {
            case FRONT -> new int[]{0, 1, 2};
            case SIDE -> new int[]{2, 1, 0};
            default -> new int[]{0, 2, 1};
        };
        boolean flipVertical = view != View.TOP; // y up on screen
        int spanU = max[axes[0]] - min[axes[0]] + 1, spanV = max[axes[1]] - min[axes[1]] + 1;
        int scale = Math.max(1, Math.min((previewW - 4) / spanU, (previewH - 4) / spanV));
        int ox = x + (previewW - spanU * scale) / 2, oy = y + (previewH - spanV * scale) / 2;

        // Nearest-to-viewer depth per column (projection), or the chosen slice
        int[][] top = new int[spanU][spanV];
        Cell[][] topCell = new Cell[spanU][spanV];
        for (int[] col : top) Arrays.fill(col, Integer.MIN_VALUE);
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            int d = v[axes[2]] - min[axes[2]];
            int u = v[axes[0]] - min[axes[0]], w = v[axes[1]] - min[axes[1]];
            if (layer >= 0) {
                if (d == layer) { top[u][w] = d; topCell[u][w] = c; }
                else if (d == layer - 1 && top[u][w] == Integer.MIN_VALUE) top[u][w] = -2; // ghost of the layer below
            } else if (d > top[u][w]) {
                top[u][w] = d;
                topCell[u][w] = c;
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
                    color = shade(cellColor(topCell[u][w], min[1], max[1]), light);
                }
                g.fill(ox + u * scale, oy + sy * scale, ox + (u + 1) * scale - (scale > 3 ? 1 : 0),
                        oy + (sy + 1) * scale - (scale > 3 ? 1 : 0), color);
            }
        }
    }

    /** Number of layers the ◀ ▶ buttons step through: height in 3D and top view, else the view's depth. */
    private int previewDepth() {
        List<Cell> cells = cells();
        if (cells.isEmpty()) return 0;
        int axis = switch (view) { case FRONT -> 2; case SIDE -> 0; default -> 1; };
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
            List<Cell> cells = ShapeGenerator.generate(params, maxAxis, SchematicLibrary::cells);
            previewAxle = params.centerBlock().isEmpty() ? Set.of()
                    : new HashSet<>(ShapeGenerator.centerAxis(params.orientation(), cells));
            previewMaterials = ShapeMaterials.of(params);
            Set<Cell> all = new LinkedHashSet<>(cells);
            all.addAll(previewAxle);
            previewCells = List.copyOf(all);
            previewParams = params;
        }
        return previewCells;
    }

    private Set<Cell> previewAxle = Set.of();
    private Map<Cell, BlockState> previewMaterials = Map.of();

    private String centerBlockLabel() {
        ResourceLocation id = ResourceLocation.tryParse(params.centerBlock());
        if (params.centerBlock().isEmpty() || id == null) return I18n.get("effortlessbuilding.screen.picker_none");
        return BuiltInRegistries.ITEM.get(id).getDescription().getString();
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

    private int listRows() { return (panelH - TOP - 26) / LIST_ROW_H; }
    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

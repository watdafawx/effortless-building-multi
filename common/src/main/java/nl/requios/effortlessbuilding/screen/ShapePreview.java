package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.palette.BlockPalette;
import nl.requios.effortlessbuilding.palette.PaletteClientState;
import nl.requios.effortlessbuilding.shape.*;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.*;

/**
 * The Shape Generator's preview: generates the design's cells (cached), draws them as rotatable 3D
 * cubes or as a slice through one plane, and works out which block each cell gets (saved schematic
 * block, middle block, palette, else the held block) for the preview colors, materials and export.
 */
public final class ShapePreview {

    /** How the preview shows the shape: rotatable 3D, or a slice through one plane. */
    public enum View { THREE_D, TOP, FRONT, SIDE }

    private static final int BLOCK_COLOR = 0xE8A33D;

    public View view = View.THREE_D;
    /** Slice index along the view's depth axis (in 3D: build up to this layer), or -1 for all. */
    public int layer = -1;
    public float yaw = 35, pitch = 28, zoom = 1;
    /** Part whose blocks are tinted (the one being edited), or none. */
    public int highlight = Integer.MIN_VALUE;
    /** Screen pixels per block in the last drawn 3D view and slice, for dragging parts. */
    public float lastScale = 1;
    public int lastSliceScale = 1;

    // ---- cells cache ----
    private ShapeParams cellsParams;
    private List<Cell> cells = List.of();
    private Set<Cell> axle = Set.of();
    private Map<Cell, BlockState> materials = Map.of();
    /** Which shape each cell comes from ({@link ShapeGenerator#MAIN} or a part index). */
    private Map<Cell, Integer> labels = Map.of();

    // ---- 3D faces cache ----
    private ShapeParams facesParams;
    private Object facesPalette;
    private int facesLayer = Integer.MIN_VALUE;
    private int facesHighlight = Integer.MIN_VALUE;
    /** Visible cube faces: x, y, z of four corners, then an ARGB color, per face. */
    private float[][] faces = new float[0][];
    private float[] facesCenter = new float[3];
    private float facesRadius = 1;

    // =========================================================================
    // Cells and their blocks
    // =========================================================================

    public List<Cell> cells(ShapeParams params) {
        if (!params.equals(cellsParams)) {
            Minecraft mc = Minecraft.getInstance();
            int maxAxis = mc.player != null ? ServerConfig.INSTANCE.getMaxBlocksPerAxis(mc.player) : 1000;
            labels = ShapeGenerator.generateLabeled(params, maxAxis, SchematicLibrary::cells);
            List<Cell> generated = new ArrayList<>(labels.keySet());
            axle = params.centerBlock().isEmpty() ? Set.of()
                    : new HashSet<>(ShapeGenerator.centerAxis(params.orientation(), generated));
            materials = ShapeMaterials.of(params);
            Set<Cell> all = new LinkedHashSet<>(generated);
            all.addAll(axle);
            cells = List.copyOf(all);
            cellsParams = params;
        }
        return cells;
    }

    /** The block a cell gets on its own (middle block, saved schematic block, palette), or null for the held block. */
    public @Nullable Item cellItem(ShapeParams params, Cell c, int minY, int maxY) {
        cells(params);
        if (axle.contains(c)) {
            ResourceLocation id = ResourceLocation.tryParse(params.centerBlock());
            return id == null ? null : BuiltInRegistries.ITEM.get(id);
        }
        Integer label = labels.get(c);
        if (label != null && label >= 0 && label < params.parts().size() && !params.parts().get(label).block().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(params.parts().get(label).block());
            if (id != null) return BuiltInRegistries.ITEM.get(id);
        }
        BlockState saved = materials.get(c);
        if (saved != null) {
            Item item = saved.getBlock().asItem();
            return item == Items.AIR ? null : item;
        }
        List<Item> blocks = paletteBlocks();
        if (blocks.isEmpty()) return null;
        BlockPalette palette = PaletteClientState.getPalette();
        int index = palette.pattern().index(blocks.size(), palette.band(), c.x(), c.y(), c.z(), minY, maxY,
                BlockPos.asLong(c.x(), c.y(), c.z()));
        return blocks.get(index);
    }

    /** Blocks the build needs, per item: saved schematic blocks, the middle block, palette, else the held block. */
    public Map<Item, Integer> requiredItems(ShapeParams params) {
        List<Cell> all = cells(params);
        int[] y = yRange(all);
        Item held = heldBlock();
        Map<Item, Integer> out = new HashMap<>();
        for (Cell c : all) {
            Item item = cellItem(params, c, y[0], y[1]);
            if (item == null) item = held;
            if (item != null) out.merge(item, 1, Integer::sum);
        }
        return out;
    }

    /** Exactly what would be built, as block states (saved states keep their facing); empty with nothing to build with. */
    public Map<Cell, BlockState> blocksToExport(ShapeParams params) {
        List<Cell> all = cells(params);
        int[] y = yRange(all);
        Item held = heldBlock();
        Map<Cell, BlockState> blocks = new HashMap<>();
        for (Cell c : all) {
            BlockState saved = materials.get(c);
            Integer label = labels.get(c);
            boolean partBlock = label != null && label >= 0 && label < params.parts().size() && !params.parts().get(label).block().isEmpty();
            if (saved != null && !axle.contains(c) && !partBlock) { blocks.put(c, saved); continue; }
            Item item = cellItem(params, c, y[0], y[1]);
            if (item == null) item = held;
            if (item instanceof BlockItem blockItem) blocks.put(c, blockItem.getBlock().defaultBlockState());
        }
        return blocks;
    }

    private static @Nullable Item heldBlock() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getMainHandItem().getItem() instanceof BlockItem b ? b : null;
    }

    private static List<Item> paletteBlocks() {
        BlockPalette palette = PaletteClientState.getActive();
        Minecraft mc = Minecraft.getInstance();
        if (palette == null || mc.player == null) return List.of();
        return palette.resolve(mc.player);
    }

    private static int[] yRange(List<Cell> cells) {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Cell c : cells) { minY = Math.min(minY, c.y()); maxY = Math.max(maxY, c.y()); }
        return new int[]{minY, maxY};
    }

    // =========================================================================
    // Layers and info
    // =========================================================================

    /** Number of layers the layer buttons step through: height in 3D and top view, else the view's depth. */
    public int depth(ShapeParams params) {
        List<Cell> all = cells(params);
        if (all.isEmpty()) return 0;
        int axis = switch (view) { case FRONT -> 2; case SIDE -> 0; default -> 1; };
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Cell c : all) {
            int v = axis == 0 ? c.x() : axis == 1 ? c.y() : c.z();
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return hi - lo + 1;
    }

    public void stepLayer(ShapeParams params, int dir) {
        int depth = depth(params);
        if (depth <= 0) return;
        // -1 (all) → 0 → … → depth-1 → -1
        layer = layer + dir < -1 ? depth - 1 : layer + dir >= depth ? -1 : layer + dir;
    }

    public void nextView() {
        View[] views = View.values();
        view = views[(view.ordinal() + 1) % views.length];
        layer = -1;
    }

    public String layerText(ShapeParams params) {
        int depth = depth(params);
        if (layer >= depth) layer = -1;
        return layer < 0 ? I18n.get("effortlessbuilding.screen.all_layers")
                : I18n.get(view == View.THREE_D ? "effortlessbuilding.screen.up_to_layer" : "effortlessbuilding.screen.layer", layer + 1, depth);
    }

    /** "N blocks · W × H × D". */
    public String sizeText(ShapeParams params) {
        List<Cell> all = cells(params);
        if (all.isEmpty()) return I18n.get("effortlessbuilding.screen.nothing_to_build");
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : all) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        }
        return I18n.get("effortlessbuilding.screen.block_count", all.size()) + "  ·  "
                + (max[0] - min[0] + 1) + " × " + (max[1] - min[1] + 1) + " × " + (max[2] - min[2] + 1)
                + " " + I18n.get("effortlessbuilding.screen.dimensions");
    }

    // =========================================================================
    // Input
    // =========================================================================

    public void drag(double dx, double dy) {
        yaw += (float) dx * 0.9f;
        pitch = Math.max(-89, Math.min(89, pitch + (float) dy * 0.9f));
    }

    public void scroll(ShapeParams params, double amount) {
        if (view == View.THREE_D) zoom = Math.max(0.3f, Math.min(6f, zoom * (amount > 0 ? 1.15f : 1 / 1.15f)));
        else stepLayer(params, amount > 0 ? 1 : -1);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    /** Draws the preview filling the given box. */
    public void render(GuiGraphics g, Font font, int x, int y, int w, int h, ShapeParams params) {
        List<Cell> all = cells(params);
        g.fill(x, y, x + w, y + h, 0xFF1A1A1A);
        if (all.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.nothing_to_build"), x + w / 2, y + h / 2 - 4, 0x888888);
            return;
        }
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : all) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { min[a] = Math.min(min[a], v[a]); max[a] = Math.max(max[a], v[a]); }
        }
        int depth = depth(params);
        if (layer >= depth) layer = -1;
        if (view == View.THREE_D) render3D(g, x, y, w, h, params, all, min[1]);
        else renderSlice(g, x, y, w, h, params, all, min, max, depth);
    }

    /** Solid cubes with a light per face direction, depth-tested, rotatable by dragging. */
    private void render3D(GuiGraphics g, int x, int y, int w, int h, ShapeParams params, List<Cell> all, int minY) {
        updateFaces(params, all, minY);
        float scale = zoom * 0.46f * Math.min(w, h) / facesRadius;
        lastScale = scale;

        g.enableScissor(x, y, x + w, y + h);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 100);
        // World y up = screen up. Depth is squashed (order kept) so the cubes stay below tooltips.
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
        // Menus and tooltips drawn later must not hide behind the cubes
        RenderSystem.clear(256, Minecraft.ON_OSX);
    }

    /** Rebuilds the visible-face list when the shape or the shown layers change. */
    private void updateFaces(ShapeParams params, List<Cell> all, int minY) {
        Object paletteKey = List.of(paletteBlocks(), PaletteClientState.getPalette(), BlockColorCache.colors().size());
        if (params.equals(facesParams) && layer == facesLayer && highlight == facesHighlight && paletteKey.equals(facesPalette)) return;
        facesParams = params;
        facesLayer = layer;
        facesHighlight = highlight;
        facesPalette = paletteKey;
        int maxY = Integer.MIN_VALUE;
        for (Cell c : all) maxY = Math.max(maxY, c.y());

        Set<Cell> shown = new HashSet<>();
        for (Cell c : all) if (layer < 0 || c.y() - minY <= layer) shown.add(c);

        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Cell c : all) {
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
                f[12] = shade(cellColor(params, c, minY, maxY), light[d] * checker * dim);
                out.add(f);
            }
        }
        faces = out.toArray(new float[0][]);
    }

    /** Slice (or projection when all layers) through the chosen plane. */
    private void renderSlice(GuiGraphics g, int x, int y, int w, int h, ShapeParams params, List<Cell> all,
                             int[] min, int[] max, int depth) {
        // Axes of the view: (horizontal, vertical-on-screen, depth)
        int[] axes = switch (view) {
            case FRONT -> new int[]{0, 1, 2};
            case SIDE -> new int[]{2, 1, 0};
            default -> new int[]{0, 2, 1};
        };
        boolean flipVertical = view != View.TOP; // y up on screen
        int spanU = max[axes[0]] - min[axes[0]] + 1, spanV = max[axes[1]] - min[axes[1]] + 1;
        int scale = Math.max(1, Math.min((w - 4) / spanU, (h - 4) / spanV));
        lastSliceScale = scale;
        int ox = x + (w - spanU * scale) / 2, oy = y + (h - spanV * scale) / 2;

        // Nearest-to-viewer depth per column (projection), or the chosen slice
        int[][] top = new int[spanU][spanV];
        Cell[][] topCell = new Cell[spanU][spanV];
        for (int[] col : top) Arrays.fill(col, Integer.MIN_VALUE);
        for (Cell c : all) {
            int[] v = {c.x(), c.y(), c.z()};
            int d = v[axes[2]] - min[axes[2]];
            int u = v[axes[0]] - min[axes[0]], wv = v[axes[1]] - min[axes[1]];
            if (layer >= 0) {
                if (d == layer) { top[u][wv] = d; topCell[u][wv] = c; }
                else if (d == layer - 1 && top[u][wv] == Integer.MIN_VALUE) top[u][wv] = -2; // ghost of the layer below
            } else if (d > top[u][wv]) {
                top[u][wv] = d;
                topCell[u][wv] = c;
            }
        }
        for (int u = 0; u < spanU; u++) {
            for (int wv = 0; wv < spanV; wv++) {
                int d = top[u][wv];
                if (d == Integer.MIN_VALUE) continue;
                int sy = flipVertical ? spanV - 1 - wv : wv;
                int color;
                if (d == -2) {
                    color = 0xFF3A3A3A;
                } else {
                    float light = layer >= 0 ? 1f : 0.55f + 0.45f * (d + 1) / depth;
                    if (((u + wv + d) & 1) == 1) light *= 0.85f; // alternating shades make blocks countable
                    color = shade(cellColor(params, topCell[u][wv], min[1], max[1]), light);
                }
                g.fill(ox + u * scale, oy + sy * scale, ox + (u + 1) * scale - (scale > 3 ? 1 : 0),
                        oy + (sy + 1) * scale - (scale > 3 ? 1 : 0), color);
            }
        }
    }

    /** The block color a cell gets: from its own block when it has one, else the plain preview color. */
    private int cellColor(ShapeParams params, Cell c, int minY, int maxY) {
        Item item = cellItem(params, c, minY, maxY);
        int color = item != null ? BlockColorCache.colorOf(item) : BLOCK_COLOR;
        Integer label = labels.get(c);
        if (label != null && label == highlight) color = mix(color, 0x3FA9FF, 0.55f); // the part being edited
        return color;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    /**
     * Where a screen drag of (dx, dy) pixels moves a part, in blocks along x, y and z (not rounded).
     * Slice views move in their plane. In 3D the drag moves across the ground, or up and down when
     * {@code vertical}; the camera angle decides which world directions screen right and up are.
     */
    public double[] dragToBlocks(double dx, double dy, boolean vertical) {
        double[] out = new double[3];
        switch (view) {
            case TOP -> { out[0] = dx / lastSliceScale; out[2] = dy / lastSliceScale; }
            case FRONT -> { out[0] = dx / lastSliceScale; out[1] = -dy / lastSliceScale; }
            case SIDE -> { out[2] = dx / lastSliceScale; out[1] = -dy / lastSliceScale; }
            case THREE_D -> {
                // How one block along each world axis shows on screen (same turns as the drawing)
                org.joml.Matrix3f turn = new org.joml.Matrix3f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw));
                org.joml.Vector3f ax = turn.transform(new org.joml.Vector3f(1, 0, 0));
                org.joml.Vector3f ay = turn.transform(new org.joml.Vector3f(0, 1, 0));
                org.joml.Vector3f az = turn.transform(new org.joml.Vector3f(0, 0, 1));
                float s = lastScale;
                if (vertical) {
                    double perBlock = -ay.y * s; // screen y grows downward
                    if (Math.abs(perBlock) > 1e-3) out[1] = dy / perBlock;
                } else {
                    // Solve dx = x*ax.x*s + z*az.x*s, dy = -(x*ax.y + z*az.y)*s for the ground move (x, z)
                    double a = ax.x * s, b = az.x * s, c = -ax.y * s, d = -az.y * s;
                    double det = a * d - b * c;
                    if (Math.abs(det) > 1e-3) {
                        out[0] = (dx * d - b * dy) / det;
                        out[2] = (a * dy - c * dx) / det;
                    } else if (Math.abs(a) > 1e-3) {
                        out[0] = dx / a;
                    }
                }
            }
        }
        return out;
    }

    private static float sq(float v) { return v * v; }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int gr = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }
}

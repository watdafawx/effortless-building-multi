package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A rotatable 3D view of colored cubes for screens: drag to rotate, scroll to zoom. Only faces with no
 * neighbor are drawn, each shaded by its direction, with alternating shades so blocks can be counted.
 * The owning screen supplies the cells and their colors, and forwards mouse input.
 */
public class VoxelView {

    public float yaw = 35, pitch = 28, zoom = 1;
    private boolean dragging = false;

    private Object facesKey;
    /** Visible faces: x, y, z of four corners, then an ARGB color. */
    private float[][] faces = new float[0][];
    private float[] center = new float[3];
    private float radius = 1;

    private static final int[][] DIRS = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final float[] LIGHT = {1f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f};
    private static final float[][][] CORNERS = {
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};

    /**
     * Sets what to draw; rebuilt only when {@code key} changes.
     *
     * @param colors cell → 0xRRGGBB (alpha is ignored)
     * @param solid  cells that hide the faces of their neighbors (usually all of {@code colors})
     */
    public void setCells(Object key, Map<Cell, Integer> colors, java.util.Set<Cell> solid) {
        if (Objects.equals(key, facesKey)) return;
        facesKey = key;
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        List<float[]> out = new ArrayList<>();
        for (var e : colors.entrySet()) {
            Cell c = e.getKey();
            float[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a] + 1); }
            float checker = ((c.x() + c.y() + c.z()) & 1) == 1 ? 0.88f : 1f;
            for (int d = 0; d < 6; d++) {
                if (solid.contains(new Cell(c.x() + DIRS[d][0], c.y() + DIRS[d][1], c.z() + DIRS[d][2]))) continue;
                float[] f = new float[13];
                for (int k = 0; k < 4; k++) {
                    f[k * 3] = c.x() + CORNERS[d][k][0];
                    f[k * 3 + 1] = c.y() + CORNERS[d][k][1];
                    f[k * 3 + 2] = c.z() + CORNERS[d][k][2];
                }
                f[12] = shade(e.getValue(), LIGHT[d] * checker);
                out.add(f);
            }
        }
        faces = out.toArray(new float[0][]);
        if (colors.isEmpty()) return;
        center = new float[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        radius = Math.max(1, (float) Math.sqrt(sq(hi[0] - lo[0]) + sq(hi[1] - lo[1]) + sq(hi[2] - lo[2])) / 2);
    }

    public void render(GuiGraphics g, int x, int y, int w, int h) {
        float scale = zoom * 0.46f * Math.min(w, h) / radius;
        g.enableScissor(x, y, x + w, y + h);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h / 2f, 100);
        // World y up = screen up; depth squashed to stay between the background and tooltips at any zoom
        pose.scale(scale, -scale, Math.min(scale, 60f / radius));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-center[0], -center[1], -center[2]);
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

    // ---- input, forwarded by the screen ----------------------------------------

    public boolean mouseClicked(double mx, double my, int x, int y, int w, int h) {
        dragging = mx >= x && mx < x + w && my >= y && my < y + h;
        return dragging;
    }

    public boolean mouseDragged(double dragX, double dragY) {
        if (!dragging) return false;
        yaw += (float) dragX * 0.9f;
        pitch = Math.max(-89, Math.min(89, pitch + (float) dragY * 0.9f));
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    public void zoom(double scrollY) {
        zoom = Math.max(0.3f, Math.min(8f, zoom * (scrollY > 0 ? 1.15f : 1 / 1.15f)));
    }

    private static float sq(float v) { return v * v; }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int gr = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }
}

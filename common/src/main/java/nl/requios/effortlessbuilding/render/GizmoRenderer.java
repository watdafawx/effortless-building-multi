package nl.requios.effortlessbuilding.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.config.ClientConfig;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeGenerator;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.shape.SchematicLibrary;
import nl.requios.effortlessbuilding.utilities.BlockSet;

/**
 * The usual 3D axis gizmo (red x, green y, blue z) at the anchor of the shape being placed, so it is clear
 * which block the shape hangs from. With a modifier on, a yellow cross marks the modifier's origin.
 * Same look as the one in the Shape Generator's preview.
 */
public final class GizmoRenderer {

    private static final ResourceLocation BLANK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("effortlessbuilding", "textures/special/blank.png");

    private static ShapeParams cachedParams;
    private static double[] cachedOrigin;
    private static BlockSet cachedSet;
    private static int cachedCount = -1;
    private static final float[] cachedExtent = {1, 1, 1};

    private GizmoRenderer() {}

    /** Draws the gizmo for the current shape preview; does nothing outside Shape mode or when switched off. */
    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, double camX, double camY, double camZ,
                              BlockSet blockSet) {
        if (!ClientConfig.INSTANCE.shouldShowGizmo() || blockSet == null || blockSet.firstPos == null) return;
        if (BuildModes.CLIENT.getBuildMode() != BuildModeEnum.SHAPE) return;
        BlockPos anchor = blockSet.firstPos;

        ShapeParams params = ShapeClientState.getActive();
        if (params != cachedParams) {
            cachedParams = params;
            cachedOrigin = ShapeGenerator.modifierOrigin(params, SchematicLibrary::cells);
        }

        if (blockSet != cachedSet || blockSet.size() != cachedCount) {
            cachedSet = blockSet;
            cachedCount = blockSet.size();
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (BlockPos p : blockSet.keySet()) {
                int[] v = {p.getX(), p.getY(), p.getZ()};
                for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a] + 1); }
            }
            for (int a = 0; a < 3; a++) cachedExtent[a] = Math.max(1, hi[a] - lo[a]);
        }
        // About half the shape along each axis, at most half its biggest side, kept readable from a distance
        float biggest = Math.max(cachedExtent[0], Math.max(cachedExtent[1], cachedExtent[2]));
        float base = Math.min(Math.max(3f, biggest * 0.5f), 24f);
        float t = Math.max(0.06f, base * 0.02f);
        var consumer = bufferSource.getBuffer(RenderType.entityTranslucent(BLANK_TEXTURE));
        var pose = poseStack.last();

        float cx = (float) (anchor.getX() + 0.5 - camX), cy = (float) (anchor.getY() + 0.5 - camY), cz = (float) (anchor.getZ() + 0.5 - camZ);
        int[][] colors = {{229, 72, 77}, {70, 194, 86}, {74, 134, 240}};
        float[] c = {cx, cy, cz};
        for (int a = 0; a < 3; a++) {
            float len = Math.max(2f, Math.min(base, cachedExtent[a] * 0.5f + 1f));
            float[] lo = {cx - t, cy - t, cz - t}, hi = {cx + t, cy + t, cz + t};
            hi[a] = c[a] + len;
            box(consumer, pose, lo, hi, colors[a], 235);
            float[] tipLo = {cx - t * 2.5f, cy - t * 2.5f, cz - t * 2.5f}, tipHi = {cx + t * 2.5f, cy + t * 2.5f, cz + t * 2.5f};
            tipLo[a] = c[a] + len - t * 2.5f;
            tipHi[a] = c[a] + len + t * 2.5f;
            box(consumer, pose, tipLo, tipHi, colors[a], 235);
            float[] sLo = {cx - t, cy - t, cz - t}, sHi = {cx + t, cy + t, cz + t};
            sLo[a] = c[a] - len * 0.35f;
            box(consumer, pose, sLo, sHi, new int[]{colors[a][0] / 2, colors[a][1] / 2, colors[a][2] / 2}, 235);
        }
        box(consumer, pose, new float[]{cx - t * 2, cy - t * 2, cz - t * 2}, new float[]{cx + t * 2, cy + t * 2, cz + t * 2}, new int[]{255, 255, 255}, 235);

        if (cachedOrigin != null) {
            float ox = (float) (anchor.getX() + 0.5 + cachedOrigin[0] - camX), oy = (float) (anchor.getY() + 0.5 + cachedOrigin[1] - camY),
                    oz = (float) (anchor.getZ() + 0.5 + cachedOrigin[2] - camZ);
            float arm = Math.max(1.2f, base * 0.25f);
            int[] yellow = {255, 210, 63};
            box(consumer, pose, new float[]{ox - arm, oy - t, oz - t}, new float[]{ox + arm, oy + t, oz + t}, yellow, 235);
            box(consumer, pose, new float[]{ox - t, oy - arm, oz - t}, new float[]{ox + t, oy + arm, oz + t}, yellow, 235);
            box(consumer, pose, new float[]{ox - t, oy - t, oz - arm}, new float[]{ox + t, oy + t, oz + arm}, yellow, 235);
            float s = t * 3;
            box(consumer, pose, new float[]{ox - s, oy - s, oz - s}, new float[]{ox + s, oy + s, oz + s}, yellow, 235);
        }

        RenderSystem.depthMask(false);
        bufferSource.endBatch(RenderType.entityTranslucent(BLANK_TEXTURE));
        RenderSystem.depthMask(true);
    }

    private static void box(VertexConsumer b, PoseStack.Pose pose, float[] lo, float[] hi, int[] rgb, int alpha) {
        float x0 = lo[0], y0 = lo[1], z0 = lo[2], x1 = hi[0], y1 = hi[1], z1 = hi[2];
        face(b, pose, rgb, 1f, alpha, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        face(b, pose, rgb, 0.5f, alpha, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        face(b, pose, rgb, 0.8f, alpha, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        face(b, pose, rgb, 0.8f, alpha, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        face(b, pose, rgb, 0.65f, alpha, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        face(b, pose, rgb, 0.65f, alpha, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
    }

    private static void face(VertexConsumer b, PoseStack.Pose pose, int[] rgb, float shade, int alpha, float nx, float ny, float nz, float... p) {
        int r = (int) (rgb[0] * shade), g = (int) (rgb[1] * shade), bl = (int) (rgb[2] * shade);
        for (int v = 0; v < 4; v++) {
            b.addVertex(pose, p[v * 3], p[v * 3 + 1], p[v * 3 + 2]).setColor(r, g, bl, alpha)
                    .setUv(v == 0 || v == 3 ? 0 : 1, v < 2 ? 0 : 1)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        }
    }
}

package nl.requios.effortlessbuilding.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipelineClient;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Overview of the anchored preview: a rotatable 3D view of the whole build with the real terrain around
 * it, for builds too big to see from where you stand. Buttons move the anchor a block at a time
 * (5 with Shift), build it, or release it.
 */
public class AnchorViewScreen extends Screen {

    /** Terrain shown around the build, in blocks. */
    private static final int MARGIN = 6;
    /** Past this many sampled blocks the terrain is left out, to keep the view fast. */
    private static final long MAX_TERRAIN_VOLUME = 400_000;

    private final VoxelView view = new VoxelView();
    private boolean showTerrain = true;
    private int panelW, panelH;
    private String note = "";
    private Object cellsKey;

    public AnchorViewScreen() {
        super(Component.translatable("effortlessbuilding.screen.anchor_view"));
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 900);
        panelH = Math.min(height - 16, 520);
        int px = panelX(), by = panelY() + panelH - 20;
        String[][] moves = {{"X-", "-1,0,0"}, {"X+", "1,0,0"}, {"Y-", "0,-1,0"}, {"Y+", "0,1,0"}, {"Z-", "0,0,-1"}, {"Z+", "0,0,1"}};
        int bx = px + 6;
        for (String[] m : moves) {
            String[] d = m[1].split(",");
            addRenderableWidget(Button.builder(Component.literal(m[0]), b -> {
                        int step = hasShiftDown() ? 5 : 1;
                        BuildPipelineClient.nudgeAnchor(Integer.parseInt(d[0]) * step, Integer.parseInt(d[1]) * step,
                                Integer.parseInt(d[2]) * step);
                    })
                    .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.anchor_move.description")))
                    .bounds(bx, by, 26, 16).build());
            bx += 28;
        }
        addRenderableWidget(Button.builder(Component.translatable(showTerrain
                        ? "effortlessbuilding.screen.anchor_terrain_on" : "effortlessbuilding.screen.anchor_terrain_off"),
                b -> { showTerrain = !showTerrain; rebuildWidgets(); }).bounds(bx + 8, by, 84, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.use_shape"), b -> {
            BuildPipelineClient.buildAnchor();
            onClose();
        }).bounds(px + panelW - 76 - 4 - 70 - 4 - 70, by, 70, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.anchor_release"), b -> {
            BuildPipelineClient.clearAnchor();
            onClose();
        }).bounds(px + panelW - 76 - 4 - 70, by, 70, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(px + panelW - 76, by, 70, 16).build());
    }

    /** Anchored blocks in their own colors, plus the terrain around them in dimmer map colors. */
    private void updateCells(BlockSet blocks) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        // Sampling the terrain is costly: only redo it when the anchor moved or the toggle changed
        Object key = List.of(System.identityHashCode(blocks), showTerrain);
        if (key.equals(cellsKey)) return;
        cellsKey = key;
        Level level = minecraft.level;
        Item held = minecraft.player.getMainHandItem().getItem();
        Map<Cell, Integer> colors = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockEntry e : blocks.values()) {
            BlockPos p = e.blockPos;
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            Item item = e.item != null ? e.item : held;
            int rgb = item instanceof BlockItem ? BlockColorCache.colorOf(item) : 0xE8A33D;
            colors.put(new Cell(p.getX(), p.getY(), p.getZ()), rgb);
        }
        Set<Cell> solid = new HashSet<>(colors.keySet());
        note = "";
        if (showTerrain && !blocks.isEmpty()) {
            long volume = (long) (maxX - minX + 1 + 2 * MARGIN) * (maxY - minY + 1 + MARGIN + 2) * (maxZ - minZ + 1 + 2 * MARGIN);
            if (volume > MAX_TERRAIN_VOLUME) {
                note = I18n.get("effortlessbuilding.screen.anchor_terrain_too_big");
            } else {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int x = minX - MARGIN; x <= maxX + MARGIN; x++)
                    for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++)
                        for (int y = minY - MARGIN; y <= maxY + 2; y++) {
                            Cell c = new Cell(x, y, z);
                            if (colors.containsKey(c)) continue;
                            BlockState state = level.getBlockState(pos.set(x, y, z));
                            if (state.isAir()) continue;
                            MapColor map = state.getMapColor(level, pos);
                            if (map == MapColor.NONE) continue;
                            colors.put(c, dim(map.col));
                            solid.add(c);
                        }
            }
        }
        view.setCells(key, colors, solid);
    }

    /** Terrain is drawn dimmer and greyer so the build stands out. */
    private static int dim(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int grey = (r + g + b) / 3;
        r = (int) ((r * 0.5 + grey * 0.5) * 0.6); g = (int) ((g * 0.5 + grey * 0.5) * 0.6); b = (int) ((b * 0.5 + grey * 0.5) * 0.6);
        return r << 16 | g << 8 | b;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 170 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int px = panelX(), py = panelY();
        g.drawString(font, title, px + 6, py + 7, 0xFFFFFF);
        BlockSet blocks = BuildPipelineClient.getAnchorBlocks();
        int vx = px + 6, vy = py + 22, vw = panelW - 12, vh = panelH - 22 - 26;
        g.fill(vx, vy, vx + vw, vy + vh, 0xFF1B1D22);
        if (blocks == null || blocks.isEmpty()) {
            g.drawCenteredString(font, I18n.get("effortlessbuilding.screen.anchor_none"), vx + vw / 2, vy + vh / 2, 0x888888);
            return;
        }
        updateCells(blocks);
        view.render(g, vx, vy, vw, vh);
        BlockPos first = blocks.firstPos;
        String info = I18n.get("effortlessbuilding.screen.block_count", blocks.size())
                + (first != null ? "   @ " + first.getX() + ", " + first.getY() + ", " + first.getZ() : "")
                + "   " + I18n.get("effortlessbuilding.screen.drag_to_rotate");
        g.drawString(font, info, px + 110, py + 7, 0xAAAAAA);
        if (!note.isEmpty()) g.drawString(font, note, vx + 4, vy + 4, 0xFFAA66);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return view.mouseClicked(mouseX, mouseY, panelX() + 6, panelY() + 22, panelW - 12, panelH - 48);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return view.mouseDragged(dragX, dragY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        view.mouseReleased();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        view.zoom(scrollY);
        return true;
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

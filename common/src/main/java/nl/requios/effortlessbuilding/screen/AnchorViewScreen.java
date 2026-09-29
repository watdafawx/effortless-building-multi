package nl.requios.effortlessbuilding.screen;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.base.BaseOwoScreen;
import io.wispforest.owo.ui.component.ButtonComponent;
import io.wispforest.owo.ui.component.Components;
import io.wispforest.owo.ui.component.LabelComponent;
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

import static nl.requios.effortlessbuilding.screen.Ui.*;

/**
 * Overview of the anchored preview: a rotatable 3D view of the whole build with the real terrain around
 * it, for builds too big to see from where you stand. Buttons move the anchor a block at a time
 * (5 with Shift), build it, or release it.
 */
public class AnchorViewScreen extends BaseOwoScreen<FlowLayout> {

    /** Terrain shown around the build, in blocks. */
    private static final int MARGIN = 6;
    /** Past this many sampled blocks the terrain is left out, to keep the view fast. */
    private static final long MAX_TERRAIN_VOLUME = 400_000;

    private final VoxelView view = new VoxelView();
    private boolean showTerrain = true;
    private String note = "";
    private Object cellsKey;
    private LabelComponent info, noteLabel;
    private ButtonComponent terrainButton;
    private String shownInfo = "", shownNote = "";

    public AnchorViewScreen() {
        super(Component.translatable("effortlessbuilding.screen.anchor_view"));
    }

    @Override
    protected OwoUIAdapter<FlowLayout> createAdapter() {
        return OwoUIAdapter.create(this, Containers::verticalFlow);
    }

    @Override
    protected void build(FlowLayout root) {
        root.surface(Surface.flat(BACKGROUND));
        root.padding(Insets.of(8));

        FlowLayout top = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        top.gap(10).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.bottom(6));
        top.child(label(title.copy().withStyle(s -> s.withBold(true)), 0xFFFFFF));
        info = label(Component.empty(), 0xAAAAAA);
        top.child(info);
        root.child(top);

        FlowLayout body = panel(Containers.verticalFlow(Sizing.fill(100), Sizing.expand()));
        noteLabel = label(Component.empty(), 0xFFAA66);
        body.child(noteLabel);
        body.child(new ViewComponent().sizing(Sizing.fill(100), Sizing.expand()));
        root.child(body);

        FlowLayout bottom = Containers.horizontalFlow(Sizing.fill(100), Sizing.content());
        bottom.gap(4).verticalAlignment(VerticalAlignment.CENTER).margins(Insets.top(6));
        String[][] moves = {{"X-", "-1,0,0"}, {"X+", "1,0,0"}, {"Y-", "0,-1,0"}, {"Y+", "0,1,0"}, {"Z-", "0,0,-1"}, {"Z+", "0,0,1"}};
        for (String[] m : moves) {
            String[] d = m[1].split(",");
            bottom.child(w(Components.button(Component.literal(m[0]), b -> {
                        int step = Screen.hasShiftDown() ? 5 : 1;
                        BuildPipelineClient.nudgeAnchor(Integer.parseInt(d[0]) * step, Integer.parseInt(d[1]) * step,
                                Integer.parseInt(d[2]) * step);
                    })).horizontalSizing(Sizing.fixed(28))
                    .tooltip(Component.translatable("effortlessbuilding.screen.anchor_move.description")));
        }
        terrainButton = Components.button(Component.empty(), b -> {
            showTerrain = !showTerrain;
            updateTerrainButton();
        });
        updateTerrainButton();
        bottom.child(w(terrainButton).horizontalSizing(Sizing.fixed(96)).margins(Insets.left(8)));
        bottom.child(hspace());
        bottom.child(w(Components.button(Component.translatable("effortlessbuilding.screen.use_shape"), b -> {
            BuildPipelineClient.buildAnchor();
            onClose();
        })).horizontalSizing(Sizing.fixed(80)));
        bottom.child(w(Components.button(Component.translatable("effortlessbuilding.screen.anchor_release"), b -> {
            BuildPipelineClient.clearAnchor();
            onClose();
        })).horizontalSizing(Sizing.fixed(80)));
        bottom.child(w(Components.button(Component.translatable("gui.done"), b -> onClose())).horizontalSizing(Sizing.fixed(70)));
        root.child(bottom);
    }

    private void updateTerrainButton() {
        terrainButton.setMessage(Component.translatable(showTerrain
                ? "effortlessbuilding.screen.anchor_terrain_on" : "effortlessbuilding.screen.anchor_terrain_off"));
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
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Only touch the labels when their text changes (each change re-lays out the screen)
        BlockSet blocks = BuildPipelineClient.getAnchorBlocks();
        String text = "";
        if (blocks != null && !blocks.isEmpty()) {
            BlockPos first = blocks.firstPos;
            text = I18n.get("effortlessbuilding.screen.block_count", blocks.size())
                    + (first != null ? "   @ " + first.getX() + ", " + first.getY() + ", " + first.getZ() : "")
                    + "   " + I18n.get("effortlessbuilding.screen.drag_to_rotate");
        }
        if (info != null && !text.equals(shownInfo)) { shownInfo = text; info.text(Component.literal(text)); }
        if (noteLabel != null && !note.equals(shownNote)) { shownNote = note; noteLabel.text(Component.literal(note)); }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** The 3D view of the build and its surroundings: drag to turn, scroll to zoom. */
    private class ViewComponent extends BaseComponent {

        @Override
        public void draw(OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
            context.fill(x, y, x + width, y + height, 0xFF1B1D22);
            BlockSet blocks = BuildPipelineClient.getAnchorBlocks();
            if (blocks == null || blocks.isEmpty()) {
                context.drawCenteredString(font, I18n.get("effortlessbuilding.screen.anchor_none"), x + width / 2, y + height / 2, 0x888888);
                return;
            }
            updateCells(blocks);
            view.render(context, x, y, width, height);
        }

        @Override
        public boolean canFocus(FocusSource source) {
            return source == FocusSource.MOUSE_CLICK;
        }

        @Override
        public boolean onMouseDown(double mouseX, double mouseY, int button) {
            super.onMouseDown(mouseX, mouseY, button);
            return view.mouseClicked(x + 1, y + 1, x, y, width, height); // the click is on this view; start turning
        }

        @Override
        public boolean onMouseDrag(double mouseX, double mouseY, double deltaX, double deltaY, int button) {
            return view.mouseDragged(deltaX, deltaY);
        }

        @Override
        public boolean onMouseUp(double mouseX, double mouseY, int button) {
            view.mouseReleased();
            return super.onMouseUp(mouseX, mouseY, button);
        }

        @Override
        public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
            view.zoom(amount);
            return true;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

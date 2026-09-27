package nl.requios.effortlessbuilding.screen;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.palette.BlockColorCache;
import nl.requios.effortlessbuilding.palette.PaletteSuggester;
import nl.requios.effortlessbuilding.shape.SchematicLibrary;
import nl.requios.effortlessbuilding.shape.SchematicWriter;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Turns a PNG from {@code config/effortlessbuilding/images} into pixel art: each pixel becomes the block
 * whose average color is closest (CIELAB), optionally with dithering, as a wall or a floor. The result is
 * saved as a schematic, so it builds like any other (saved blocks, preview, materials, ground blending).
 */
public class PixelArtScreen extends Screen {

    public static final Path FOLDER = Path.of("config", Constants.MOD_ID, "images");

    private enum Layout { WALL_NS, WALL_EW, FLOOR }
    private enum Blocks { ALL, CARRIED }

    private final Screen parent;
    private final Consumer<String> onCreated;
    private List<String> images = List.of();
    private int imageIndex = 0;
    private int artWidth = 48;
    private Layout layout = Layout.WALL_NS;
    private Blocks blocks = Blocks.ALL;
    private boolean dither = false;
    private int panelW, panelH;

    // Result cache: pixel grid of items (null = transparent), rebuilt when a setting changes
    private Item[][] result = null;
    private Object resultKey = null;
    private String error = "";

    private ScreenWidgets widgets;

    public PixelArtScreen(Screen parent, Consumer<String> onCreated) {
        super(Component.translatable("effortlessbuilding.screen.pixel_art"));
        this.parent = parent;
        this.onCreated = onCreated;
    }

    @Override
    protected void init() {
        BlockColorCache.ensureReady();
        images = listImages();
        if (imageIndex >= images.size()) imageIndex = 0;
        panelW = Math.min(width - 16, 640);
        panelH = Math.min(height - 16, 420);
        widgets = new ScreenWidgets(font, this::addRenderableWidget);
        widgets.clear();
        int x = panelX() + 6, y = panelY() + 22, bw = 150;

        addRenderableWidget(Button.builder(Component.literal(images.isEmpty() ? I18n.get("effortlessbuilding.screen.pixel_art_no_images")
                                : images.get(imageIndex)),
                        b -> { if (!images.isEmpty()) imageIndex = (imageIndex + 1) % images.size(); rebuildWidgets(); })
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.pixel_art_file.description")))
                .bounds(x + ScreenWidgets.LABEL_W, y, bw, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.pixel_art_open_folder"), b -> {
                    try {
                        Files.createDirectories(FOLDER);
                        Util.getPlatform().openFile(FOLDER.toFile());
                    } catch (IOException ignored) {
                        // Folder could not be created; the list just stays empty
                    }
                })
                .bounds(x + ScreenWidgets.LABEL_W + bw + 4, y, 70, 16).build());
        y += 22;
        widgets.addIntField(x, y, String.valueOf(artWidth), v -> artWidth = Math.max(2, Math.min(256, v)));
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.pixel_art_layout." + layout.name().toLowerCase()),
                b -> { layout = next(Layout.values(), layout); rebuildWidgets(); }).bounds(x + ScreenWidgets.LABEL_W, y, bw, 16).build());
        y += 22;
        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.pixel_art_blocks." + blocks.name().toLowerCase()),
                b -> { blocks = next(Blocks.values(), blocks); rebuildWidgets(); }).bounds(x + ScreenWidgets.LABEL_W, y, bw, 16).build());
        y += 22;
        widgets.addCheckbox(x + ScreenWidgets.LABEL_W, y + 4, "", dither, () -> { dither = !dither; rebuildWidgets(); });

        addRenderableWidget(Button.builder(Component.translatable("effortlessbuilding.screen.pixel_art_create"), b -> create())
                .tooltip(Tooltip.create(Component.translatable("effortlessbuilding.screen.pixel_art_create.description")))
                .bounds(panelX() + panelW - 76 - 4 - 110, panelY() + panelH - 20, 110, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(panelX() + panelW - 76, panelY() + panelH - 20, 70, 16).build());
    }

    private static List<String> listImages() {
        if (!Files.isDirectory(FOLDER)) return List.of();
        try (Stream<Path> files = Files.list(FOLDER)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // =========================================================================
    // Conversion
    // =========================================================================

    private Item[][] result() {
        if (images.isEmpty()) return null;
        Object key = List.of(images.get(imageIndex), artWidth, dither, blocks, BlockColorCache.colors().size());
        if (!key.equals(resultKey)) {
            resultKey = key;
            try {
                result = convert(FOLDER.resolve(images.get(imageIndex)));
                error = "";
            } catch (IOException | RuntimeException e) {
                result = null;
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }
        return result;
    }

    private Item[][] convert(Path file) throws IOException {
        Map<Item, Integer> candidates = candidates();
        if (candidates.isEmpty()) throw new IOException(I18n.get("effortlessbuilding.screen.pixel_art_no_blocks"));
        List<Item> items = new ArrayList<>(candidates.keySet());
        double[][] labs = new double[items.size()][];
        for (int i = 0; i < items.size(); i++) labs[i] = PaletteSuggester.lab(candidates.get(items.get(i)));

        try (InputStream in = Files.newInputStream(file); NativeImage image = NativeImage.read(in)) {
            int w = artWidth, h = Math.max(1, Math.round((float) image.getHeight() * artWidth / image.getWidth()));
            // Box-average each output pixel from the source (alpha-weighted); fully clear ones stay empty
            float[][][] rgb = new float[h][w][];
            for (int py = 0; py < h; py++)
                for (int px = 0; px < w; px++) {
                    int x0 = px * image.getWidth() / w, x1 = Math.max(x0 + 1, (px + 1) * image.getWidth() / w);
                    int y0 = py * image.getHeight() / h, y1 = Math.max(y0 + 1, (py + 1) * image.getHeight() / h);
                    double r = 0, g = 0, b = 0, a = 0;
                    int n = 0;
                    for (int sy = y0; sy < y1; sy++)
                        for (int sx = x0; sx < x1; sx++) {
                            int abgr = image.getPixelRGBA(sx, sy);
                            double alpha = ((abgr >>> 24) & 0xFF) / 255.0;
                            r += (abgr & 0xFF) * alpha; g += ((abgr >> 8) & 0xFF) * alpha; b += ((abgr >> 16) & 0xFF) * alpha;
                            a += alpha;
                            n++;
                        }
                    if (a / n < 0.5) continue;
                    rgb[py][px] = new float[]{(float) (r / a), (float) (g / a), (float) (b / a)};
                }

            Item[][] out = new Item[h][w];
            Map<Integer, Integer> nearestCache = new HashMap<>();
            for (int py = 0; py < h; py++)
                for (int px = 0; px < w; px++) {
                    float[] c = rgb[py][px];
                    if (c == null) continue;
                    int packed = clamp(c[0]) << 16 | clamp(c[1]) << 8 | clamp(c[2]);
                    int best = nearestCache.computeIfAbsent(packed & 0xF8F8F8, q -> nearest(labs, PaletteSuggester.lab(packed)));
                    out[py][px] = items.get(best);
                    if (dither) {
                        // Floyd-Steinberg: pass the leftover color error on to unvisited neighbors
                        int chosen = candidates.get(items.get(best));
                        float er = c[0] - ((chosen >> 16) & 0xFF), eg = c[1] - ((chosen >> 8) & 0xFF), eb = c[2] - (chosen & 0xFF);
                        spread(rgb, px + 1, py, er, eg, eb, 7 / 16f);
                        spread(rgb, px - 1, py + 1, er, eg, eb, 3 / 16f);
                        spread(rgb, px, py + 1, er, eg, eb, 5 / 16f);
                        spread(rgb, px + 1, py + 1, er, eg, eb, 1 / 16f);
                    }
                }
            return out;
        }
    }

    private static void spread(float[][][] rgb, int x, int y, float er, float eg, float eb, float f) {
        if (y >= rgb.length || x < 0 || x >= rgb[0].length || rgb[y][x] == null) return;
        rgb[y][x][0] += er * f; rgb[y][x][1] += eg * f; rgb[y][x][2] += eb * f;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int nearest(double[][] labs, double[] target) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < labs.length; i++) {
            double d = PaletteSuggester.distance(labs[i], target);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private Map<Item, Integer> candidates() {
        Map<Item, Integer> all = BlockColorCache.distinctColors();
        if (blocks == Blocks.ALL || minecraft == null || minecraft.player == null) return all;
        Map<Item, Integer> carried = new LinkedHashMap<>();
        var inv = minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            Item item = inv.getItem(i).getItem();
            Integer rgb = BlockColorCache.colors().get(item);
            if (rgb != null) carried.put(item, rgb);
        }
        return carried;
    }

    private void create() {
        Item[][] art = result();
        if (art == null || minecraft == null || minecraft.player == null) return;
        int h = art.length, w = art[0].length;
        Map<Cell, BlockState> cells = new HashMap<>();
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                if (!(art[py][px] instanceof BlockItem item)) continue;
                int up = h - 1 - py; // image top = top of the wall
                Cell c = switch (layout) {
                    case WALL_NS -> new Cell(px - w / 2, up, 0);
                    case WALL_EW -> new Cell(0, up, px - w / 2);
                    case FLOOR -> new Cell(px - w / 2, 0, py - h / 2);
                };
                cells.put(c, item.getBlock().defaultBlockState());
            }
        String base = images.get(imageIndex);
        String name = SchematicWriter.safeName(base.substring(0, base.length() - 4) + "_art");
        try {
            SchematicWriter.write(SchematicLibrary.FOLDERS.getFirst().resolve(name + ".schem"), cells);
            onCreated.accept(name);
            onClose();
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 170 << 24);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = panelX() + 6, y = panelY() + 22;
        g.drawString(font, title, panelX() + 6, panelY() + 7, 0xFFFFFF);
        String[] labels = {"pixel_art_file", "pixel_art_width", "pixel_art_layout", "pixel_art_blocks", "pixel_art_dither"};
        for (int i = 0; i < labels.length; i++) {
            g.drawString(font, I18n.get("effortlessbuilding.screen." + labels[i]), x, y + i * 22 + 4, 0xCCCCCC);
        }
        widgets.renderCheckboxes(g, mouseX, mouseY);
        g.drawString(font, I18n.get("effortlessbuilding.screen.pixel_art_hint", FOLDER.toString()), x, y + 5 * 22 + 4, 0x888888);

        // Result preview, block colors per pixel
        int px0 = x, py0 = y + 6 * 22, pw = panelW - 12, ph = panelY() + panelH - 26 - py0;
        g.fill(px0, py0, px0 + pw, py0 + ph, 0xFF1B1D22);
        Item[][] art = result();
        if (art == null) {
            g.drawString(font, error.isEmpty() ? I18n.get("effortlessbuilding.screen.pixel_art_no_images") : error, px0 + 4, py0 + 4, 0xFF8888);
            return;
        }
        int h = art.length, w = art[0].length;
        int cell = Math.max(1, Math.min(pw / w, ph / h));
        int ox = px0 + (pw - w * cell) / 2, oy = py0 + (ph - h * cell) / 2;
        Map<Item, Integer> counts = new HashMap<>();
        Item hovered = null;
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                Item item = art[py][px];
                if (item == null) continue;
                counts.merge(item, 1, Integer::sum);
                int sx = ox + px * cell, sy = oy + py * cell;
                g.fill(sx, sy, sx + cell, sy + cell, 0xFF000000 | BlockColorCache.colorOf(item));
                if (mouseX >= sx && mouseX < sx + cell && mouseY >= sy && mouseY < sy + cell) hovered = item;
            }
        g.drawString(font, I18n.get("effortlessbuilding.screen.pixel_art_size", w, h, counts.size()), px0, panelY() + panelH - 16, 0xAAAAAA);
        if (hovered != null) g.renderTooltip(font, new ItemStack(hovered), mouseX, mouseY);
    }

    private static <T> T next(T[] values, T current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private int panelX() { return (width - panelW) / 2; }
    private int panelY() { return (height - panelH) / 2; }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (widgets.handleCheckboxClick(mouseX, mouseY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return widgets.handleScroll(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

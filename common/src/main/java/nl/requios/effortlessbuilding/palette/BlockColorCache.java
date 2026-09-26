package nl.requios.effortlessbuilding.palette;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import nl.requios.effortlessbuilding.Constants;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Average texture color of every full-cube block, for palette suggestions and previews. Client-side.
 * <p>
 * Scanning reads each block's texture once, in the background, and saves the result to
 * {@code config/effortlessbuilding-block-colors.json}. The file is reused until the resource packs
 * or the set of blocks change.
 */
public final class BlockColorCache {

    private static final Path FILE = Path.of("config", Constants.MOD_ID + "-block-colors.json");
    private static final Gson GSON = new Gson();

    private static volatile Map<Item, Integer> colors = Map.of();
    private static volatile boolean scanning = false;
    private static boolean loaded = false;

    private BlockColorCache() {}

    /** Item → 0xRRGGBB. Empty until loaded or scanned. */
    public static Map<Item, Integer> colors() {
        return colors;
    }

    public static boolean isScanning() {
        return scanning;
    }

    /** Color for a block item, falling back to grey when it was not scanned. */
    public static int colorOf(Item item) {
        return colors.getOrDefault(item, 0x808080);
    }

    /** Loads the saved colors if they match the current packs, otherwise scans. Call on the render thread. */
    public static void ensureReady() {
        if (loaded || scanning) return;
        loaded = true;
        if (!loadFromFile()) scan();
    }

    /** Scans every block again (e.g. after changing resource packs). Call on the render thread. */
    public static void scan() {
        if (scanning) return;
        Minecraft mc = Minecraft.getInstance();
        // Model and tint lookups on the render thread; texture reading in the background
        record Job(Item item, ResourceLocation texture, int tint) {}
        List<Job> jobs = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();
            if (!(item instanceof BlockItem)) continue;
            BlockState state = block.defaultBlockState();
            try {
                if (state.getRenderShape() != RenderShape.MODEL || state.hasBlockEntity()) continue;
                if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) continue;
                var sprite = mc.getBlockRenderer().getBlockModel(state).getParticleIcon();
                ResourceLocation name = sprite.contents().name();
                if (name.getPath().contains("missingno")) continue;
                int tint = -1;
                try {
                    tint = mc.getBlockColors().getColor(state, null, null, 0);
                } catch (RuntimeException ignored) {
                    // Some tints need a level; use the plain texture
                }
                jobs.add(new Job(item, name.withPath(p -> "textures/" + p + ".png"), tint));
            } catch (RuntimeException e) {
                // A block with an unusual model: leave it out
            }
        }
        scanning = true;
        String key = packsKey();
        CompletableFuture.supplyAsync(() -> {
            Map<Item, Integer> result = new LinkedHashMap<>();
            for (Job job : jobs) {
                Integer rgb = averageColor(mc, job.texture());
                if (rgb != null) result.put(job.item(), job.tint() == -1 ? rgb : multiply(rgb, job.tint()));
            }
            return result;
        }, Util.backgroundExecutor()).whenComplete((result, error) -> {
            scanning = false;
            if (error != null) {
                Constants.LOG.warn("[EffortlessBuilding] Block color scan failed", error);
                return;
            }
            colors = Collections.unmodifiableMap(result);
            saveToFile(result, key);
            Constants.LOG.info("[EffortlessBuilding] Scanned colors of {} blocks", result.size());
        });
    }

    /** Alpha-weighted average of the texture's first frame, or null if unreadable or mostly transparent. */
    private static Integer averageColor(Minecraft mc, ResourceLocation texture) {
        Optional<Resource> resource = mc.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int size = Math.min(image.getWidth(), image.getHeight()); // animated textures stack frames vertically
            long r = 0, g = 0, b = 0, weight = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int a = (abgr >>> 24) & 0xFF;
                    if (a < 128) continue;
                    r += abgr & 0xFF;
                    g += (abgr >> 8) & 0xFF;
                    b += (abgr >> 16) & 0xFF;
                    weight++;
                }
            }
            if (weight < size * size / 4) return null;
            return (int) (r / weight) << 16 | (int) (g / weight) << 8 | (int) (b / weight);
        } catch (Exception e) {
            return null;
        }
    }

    private static int multiply(int rgb, int tint) {
        int r = ((rgb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (tint & 0xFF) / 255;
        return r << 16 | g << 8 | b;
    }

    /** Identifies the resource packs and blocks the saved colors belong to. */
    private static String packsKey() {
        Minecraft mc = Minecraft.getInstance();
        return String.join(",", mc.getResourcePackRepository().getSelectedIds()) + "|" + BuiltInRegistries.BLOCK.size();
    }

    private static boolean loadFromFile() {
        if (!Files.isRegularFile(FILE)) return false;
        try {
            JsonObject root = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            if (!packsKey().equals(root.get("packs").getAsString())) return false;
            Map<Item, Integer> result = new LinkedHashMap<>();
            for (var e : root.getAsJsonObject("colors").entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                if (id == null) continue;
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(item ->
                        result.put(item, Integer.parseInt(e.getValue().getAsString(), 16)));
            }
            colors = Collections.unmodifiableMap(result);
            return !result.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveToFile(Map<Item, Integer> result, String key) {
        JsonObject root = new JsonObject();
        root.addProperty("packs", key);
        JsonObject map = new JsonObject();
        result.forEach((item, rgb) -> map.addProperty(BuiltInRegistries.ITEM.getKey(item).toString(), String.format("%06x", rgb)));
        root.add("colors", map);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (Exception e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot save block colors to {}", FILE, e);
        }
    }
}

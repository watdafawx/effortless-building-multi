package nl.requios.effortlessbuilding.palette;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import nl.requios.effortlessbuilding.Constants;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Ready-made palettes: the mod's own list ({@code assets/effortlessbuilding/palettes/presets.json},
 * which resource packs can replace) plus any {@code .json} files in {@code config/effortlessbuilding/palettes/}
 * with the same layout. Blocks that are not installed are skipped. Client-side.
 */
public final class PalettePresets {

    public static final Path FOLDER = Path.of("config", Constants.MOD_ID, "palettes");
    private static final ResourceLocation BUILT_IN = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "palettes/presets.json");
    private static final Gson GSON = new Gson();

    /** A named palette of fixed blocks. */
    public record Preset(String name, List<Item> blocks, PalettePattern pattern) {
        public BlockPalette toPalette(BlockPalette current) {
            return current.withSource(BlockPalette.Source.CUSTOM).withCustom(blocks).withPattern(pattern);
        }
    }

    private static List<Preset> presets = null;

    private PalettePresets() {}

    public static List<Preset> get() {
        if (presets == null) reload();
        return presets;
    }

    public static void reload() {
        List<Preset> list = new ArrayList<>();
        Minecraft.getInstance().getResourceManager().getResource(BUILT_IN).ifPresent(resource -> {
            try (Reader reader = resource.openAsReader()) {
                read(GSON.fromJson(reader, JsonObject.class), list);
            } catch (Exception e) {
                Constants.LOG.warn("[EffortlessBuilding] Cannot read built-in palettes", e);
            }
        });
        if (Files.isDirectory(FOLDER)) {
            try (Stream<Path> files = Files.list(FOLDER)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        read(GSON.fromJson(Files.readString(file), JsonObject.class), list);
                    } catch (Exception e) {
                        Constants.LOG.warn("[EffortlessBuilding] Cannot read palette file {}", file, e);
                    }
                }
            } catch (Exception e) {
                Constants.LOG.warn("[EffortlessBuilding] Cannot list {}", FOLDER, e);
            }
        }
        presets = List.copyOf(list);
    }

    private static void read(JsonObject root, List<Preset> out) {
        for (JsonElement e : root.getAsJsonArray("palettes")) {
            JsonObject o = e.getAsJsonObject();
            List<Item> blocks = new ArrayList<>();
            for (JsonElement b : o.getAsJsonArray("blocks")) {
                ResourceLocation id = ResourceLocation.tryParse(b.getAsString());
                if (id == null) continue;
                BuiltInRegistries.ITEM.getOptional(id).filter(item -> item instanceof BlockItem).ifPresent(blocks::add);
            }
            if (blocks.size() < 2) continue;
            PalettePattern pattern = PalettePattern.RANDOM;
            try {
                if (o.has("pattern")) pattern = PalettePattern.valueOf(o.get("pattern").getAsString());
            } catch (IllegalArgumentException ignored) {
                // Unknown pattern name: keep random
            }
            out.add(new Preset(o.get("name").getAsString(), blocks, pattern));
        }
    }
}

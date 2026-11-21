package io.github.watdafawx.shapewright.shape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.watdafawx.shapewright.Constants;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Color themes (5-block palettes: base, primary, secondary, trim, accent) for {@link FloorDesigner}.
 * Ships with built-in defaults and loads/merges user or modpack themes from
 * {@code config/shapewright/floor_themes.json}.
 */
public final class FloorThemes {

    public static final Path FILE = Path.of("config", Constants.MOD_ID, "floor_themes.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Theme(String id, @Nullable String displayName, String[] blocks) {
        public String getNameKey() {
            return "shapewright.floor_designer.theme." + id;
        }
    }

    private static final List<Theme> DEFAULTS = List.of(
            new Theme("pastel_lavender", null, new String[]{
                    "minecraft:smooth_quartz", "minecraft:purple_terracotta", "minecraft:light_gray_terracotta",
                    "minecraft:purple_concrete_powder", "minecraft:sea_lantern"}),
            new Theme("royal_gold", null, new String[]{
                    "minecraft:smooth_sandstone", "minecraft:yellow_terracotta", "minecraft:terracotta",
                    "minecraft:brown_terracotta", "minecraft:glowstone"}),
            new Theme("emerald_oak", null, new String[]{
                    "minecraft:dark_oak_planks", "minecraft:dark_prismarine", "minecraft:birch_planks",
                    "minecraft:spruce_planks", "minecraft:ochre_froglight"}),
            new Theme("mossy_ruins", null, new String[]{
                    "minecraft:mud_bricks", "minecraft:moss_block", "minecraft:oxidized_cut_copper",
                    "minecraft:cobblestone", "minecraft:verdant_froglight"}),
            new Theme("desert_sunburst", null, new String[]{
                    "minecraft:deepslate_tiles", "minecraft:yellow_concrete", "minecraft:orange_terracotta",
                    "minecraft:blackstone", "minecraft:sea_lantern"}),
            new Theme("deepslate_quartz", null, new String[]{
                    "minecraft:polished_deepslate", "minecraft:quartz_block", "minecraft:cyan_terracotta",
                    "minecraft:deepslate_bricks", "minecraft:shroomlight"})
    );

    private static List<Theme> loaded = null;

    private FloorThemes() {}

    public static List<Theme> get() {
        if (loaded == null) reload();
        return loaded;
    }

    /** Reloads built-in defaults merged with any custom themes in {@code config/shapewright/floor_themes.json}. */
    public static void reload() {
        Map<String, Theme> byId = new LinkedHashMap<>();
        for (Theme t : DEFAULTS) byId.put(t.id(), t);

        if (Files.isRegularFile(FILE)) {
            try {
                JsonArray arr = GSON.fromJson(Files.readString(FILE), JsonArray.class);
                if (arr != null) {
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        if (!o.has("id") || !o.has("blocks")) continue;
                        String id = o.get("id").getAsString();
                        String name = o.has("name") ? o.get("name").getAsString() : id;
                        JsonArray bArr = o.getAsJsonArray("blocks");
                        if (bArr.size() < 5) continue;
                        String[] blocks = new String[5];
                        for (int i = 0; i < 5; i++) blocks[i] = bArr.get(i).getAsString();
                        byId.put(id, new Theme(id, name, blocks));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Constants.LOG.warn("[Shapewright] Cannot read custom floor themes from {}", FILE, e);
            }
        }
        loaded = List.copyOf(new ArrayList<>(byId.values()));
    }
}

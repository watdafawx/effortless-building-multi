package nl.requios.effortlessbuilding.palette;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import nl.requios.effortlessbuilding.Constants;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's palette setting: on/off, the palette itself and saved favorites. Client-side; the server
 * receives the palette with each build. Saved to {@code config/effortlessbuilding-palette.json}.
 */
public final class PaletteClientState {

    /** A palette the player saved under a name. */
    public record Favorite(String name, BlockPalette palette) {}

    private static final Path FILE = Path.of("config", Constants.MOD_ID + "-palette.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean enabled = false;
    private static BlockPalette palette = BlockPalette.defaults();
    private static final List<Favorite> favorites = new ArrayList<>();

    private PaletteClientState() {}

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean on) {
        enabled = on;
        save();
    }

    public static BlockPalette getPalette() { return palette; }

    public static void setPalette(BlockPalette p) {
        palette = p;
        save();
    }

    /** The palette to build with, or null when off. */
    public static @Nullable BlockPalette getActive() {
        return enabled ? palette : null;
    }

    /** Whether builds by this player currently use palette blocks (the preview shows them). */
    public static boolean isActiveFor(Player player) {
        return enabled && !palette.resolve(player).isEmpty();
    }

    public static List<Favorite> getFavorites() {
        return List.copyOf(favorites);
    }

    /** Saves under the name, replacing a favorite with the same name. */
    public static void saveFavorite(String name, BlockPalette p) {
        favorites.removeIf(f -> f.name().equalsIgnoreCase(name));
        favorites.add(new Favorite(name, p));
        save();
    }

    public static void deleteFavorite(String name) {
        favorites.removeIf(f -> f.name().equals(name));
        save();
    }

    // ---- file ----------------------------------------------------------------

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            palette = fromJson(o);
            enabled = o.get("enabled").getAsBoolean();
            favorites.clear();
            if (o.has("favorites")) {
                for (JsonElement e : o.getAsJsonArray("favorites")) {
                    JsonObject f = e.getAsJsonObject();
                    favorites.add(new Favorite(f.get("name").getAsString(), fromJson(f)));
                }
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot read palette from {}", FILE, e);
        }
    }

    private static void save() {
        JsonObject o = toJson(palette);
        o.addProperty("enabled", enabled);
        JsonArray list = new JsonArray();
        for (Favorite f : favorites) {
            JsonObject fo = toJson(f.palette());
            fo.addProperty("name", f.name());
            list.add(fo);
        }
        o.add("favorites", list);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot save palette to {}", FILE, e);
        }
    }

    public static JsonObject toJson(BlockPalette p) {
        JsonObject o = new JsonObject();
        o.addProperty("source", p.source().name());
        o.addProperty("hotbar_slots", p.hotbarSlots());
        JsonArray custom = new JsonArray();
        for (Item item : p.custom()) custom.add(BuiltInRegistries.ITEM.getKey(item).toString());
        o.add("custom", custom);
        o.addProperty("pattern", p.pattern().name());
        o.addProperty("band", p.band());
        return o;
    }

    public static BlockPalette fromJson(JsonObject o) {
        List<Item> custom = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("custom")) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id != null) BuiltInRegistries.ITEM.getOptional(id).ifPresent(custom::add);
        }
        return new BlockPalette(
                BlockPalette.Source.valueOf(o.get("source").getAsString()),
                o.get("hotbar_slots").getAsInt(),
                custom,
                PalettePattern.valueOf(o.get("pattern").getAsString()),
                o.get("band").getAsInt());
    }
}

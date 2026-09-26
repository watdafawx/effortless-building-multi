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
 * The player's palette setting: on/off plus the palette itself. Client-side; the server receives
 * the palette with each build. Saved to {@code config/effortlessbuilding-palette.json}.
 */
public final class PaletteClientState {

    private static final Path FILE = Path.of("config", Constants.MOD_ID + "-palette.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean enabled = false;
    private static BlockPalette palette = BlockPalette.defaults();

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

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            List<Item> custom = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("custom")) {
                ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
                if (id != null) BuiltInRegistries.ITEM.getOptional(id).ifPresent(custom::add);
            }
            palette = new BlockPalette(
                    BlockPalette.Source.valueOf(o.get("source").getAsString()),
                    o.get("hotbar_slots").getAsInt(),
                    custom,
                    PalettePattern.valueOf(o.get("pattern").getAsString()),
                    o.get("band").getAsInt());
            enabled = o.get("enabled").getAsBoolean();
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot read palette from {}", FILE, e);
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("source", palette.source().name());
        o.addProperty("hotbar_slots", palette.hotbarSlots());
        JsonArray custom = new JsonArray();
        for (Item item : palette.custom()) custom.add(BuiltInRegistries.ITEM.getKey(item).toString());
        o.add("custom", custom);
        o.addProperty("pattern", palette.pattern().name());
        o.addProperty("band", palette.band());
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(o));
        } catch (IOException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot save palette to {}", FILE, e);
        }
    }
}

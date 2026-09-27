package nl.requios.effortlessbuilding.shape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import nl.requios.effortlessbuilding.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the player's shape templates (and the active shape) to
 * {@code <gameDir>/config/effortlessbuilding-shapes.json}. Client-side.
 */
public final class ShapeTemplateStorage {

    private static final Path FILE = Path.of("config", Constants.MOD_ID + "-shapes.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ShapeTemplateStorage() {}

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            List<ShapeClientState.Template> templates = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("templates")) {
                JsonObject t = e.getAsJsonObject();
                ShapeParams params = fromJson(t.getAsJsonObject("shape"));
                if (params != null) templates.add(new ShapeClientState.Template(t.get("name").getAsString(), params));
            }
            ShapeClientState.setTemplates(templates);
            if (root.has("active")) {
                ShapeParams active = fromJson(root.getAsJsonObject("active"));
                if (active != null) ShapeClientState.setActive(active);
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot read shape templates from {}", FILE, e);
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.add("active", toJson(ShapeClientState.getActive()));
        JsonArray list = new JsonArray();
        for (ShapeClientState.Template t : ShapeClientState.getTemplates()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.add("shape", toJson(t.params()));
            list.add(o);
        }
        root.add("templates", list);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot save shape templates to {}", FILE, e);
        }
    }

    private static JsonObject toJson(ShapeParams p) {
        JsonObject o = new JsonObject();
        o.addProperty("type", p.type().name());
        o.addProperty("size", p.size());
        o.addProperty("orientation", p.orientation().name());
        o.addProperty("hollow", p.hollow());
        o.addProperty("sizing", p.sizing().name());
        o.addProperty("schematic", p.schematic());
        if (!p.centerBlock().isEmpty()) o.addProperty("center_block", p.centerBlock());
        JsonObject values = new JsonObject();
        p.values().forEach(values::addProperty);
        o.add("values", values);
        if (!p.parts().isEmpty()) {
            JsonArray parts = new JsonArray();
            for (ShapeParams.Part part : p.parts()) {
                JsonObject po = new JsonObject();
                po.add("shape", toJson(part.shape()));
                po.addProperty("operation", part.operation().name());
                po.addProperty("x", part.x());
                po.addProperty("y", part.y());
                po.addProperty("z", part.z());
                parts.add(po);
            }
            o.add("parts", parts);
        }
        return o;
    }

    /** Returns null for entries this version cannot read (e.g. a shape type from a newer version). */
    private static ShapeParams fromJson(JsonObject o) {
        try {
            Map<String, Double> values = new HashMap<>();
            if (o.has("values")) {
                for (var e : o.getAsJsonObject("values").entrySet()) values.put(e.getKey(), e.getValue().getAsDouble());
            }
            List<ShapeParams.Part> parts = new ArrayList<>();
            if (o.has("parts")) {
                for (JsonElement e : o.getAsJsonArray("parts")) {
                    JsonObject po = e.getAsJsonObject();
                    ShapeParams shape = fromJson(po.getAsJsonObject("shape"));
                    if (shape == null) continue;
                    parts.add(new ShapeParams.Part(shape, ShapeParams.Operation.valueOf(po.get("operation").getAsString()),
                            po.get("x").getAsInt(), po.get("y").getAsInt(), po.get("z").getAsInt()));
                }
            }
            return new ShapeParams(
                    ShapeType.valueOf(o.get("type").getAsString()),
                    o.get("size").getAsInt(),
                    values,
                    ShapeParams.Orientation.valueOf(o.get("orientation").getAsString()),
                    o.get("hollow").getAsBoolean(),
                    ShapeParams.Sizing.valueOf(o.get("sizing").getAsString()),
                    o.has("schematic") ? o.get("schematic").getAsString() : "",
                    parts,
                    o.has("center_block") ? o.get("center_block").getAsString() : "");
        } catch (RuntimeException e) {
            return null;
        }
    }
}

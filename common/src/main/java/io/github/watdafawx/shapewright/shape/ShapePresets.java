package io.github.watdafawx.shapewright.shape;

import com.google.gson.Gson;
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
import java.util.stream.Stream;

/**
 * Ready-made designs shown in the Shape Generator next to the player's own templates. Built-in
 * defaults are merged with any custom {@code .json} files placed in {@code config/shapewright/designs/}.
 */
public final class ShapePresets {

    public static final Path FOLDER = Path.of("config", Constants.MOD_ID, "designs");
    private static final Gson GSON = new Gson();

    /**
     * @param id          unique id; built-in titles use {@code shapewright.design.<id>}
     * @param displayName optional literal name from JSON (null for built-ins using lang keys)
     */
    public record Preset(String id, @Nullable String displayName, ShapeParams params) {
        public Preset(String id, ShapeParams params) {
            this(id, null, params);
        }

        public String getNameKey() {
            return "shapewright.design." + id;
        }
    }

    private static final List<Preset> ALL = List.of(
            new Preset("lighthouse", tower(4, 22, 0).with("wall", 1).with("battlement", 0).with("door", 1).with("windows", 4)
                    .with("floors", 3).with("roof", 1)),
            new Preset("watchtower", tower(3, 16, 4).with("door", 1).with("windows", 4).with("floors", 2)),
            new Preset("round_keep", tower(7, 20, 8).with("wall", 2).with("battlement", 2).with("door", 1).with("windows", 8)
                    .with("floors", 3)),
            new Preset("stair_tower", tower(5, 18, 0).with("battlement", 0).with("roof", 1).with("door", 1)
                    .withParts(List.of(new ShapeParams.Part(
                            ShapeParams.defaults(ShapeType.HELIX).withSize(4).with("pole_radius", 1).with("inner_radius", 2)
                                    .with("height", 18).with("turns", 3).with("thickness", 1),
                            ShapeParams.Operation.UNITE, 0, 0, 0)))),
            new Preset("domed_hall", tower(8, 7, 0).with("battlement", 0).with("door", 1).with("windows", 6)
                    .withParts(List.of(new ShapeParams.Part(
                            ShapeParams.defaults(ShapeType.DOME).withSize(8).withHollow(true),
                            ShapeParams.Operation.UNITE, 0, 7, 0)))),
            new Preset("gothic_doorway", ShapeParams.defaults(ShapeType.ARCH).withSize(3).with("style", 1)
                    .with("height_ratio", 1).with("thickness", 1).with("depth", 2)),
            new Preset("cathedral_window", ShapeParams.defaults(ShapeType.ARCH).withSize(4).with("style", 1)
                    .with("height_ratio", 1.2).with("thickness", 2).with("depth", 1)),
            new Preset("fountain", ShapeParams.defaults(ShapeType.BOWL).withSize(6).with("depth", 3)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.PRISM).withSize(1).with("sides", 4).with("height", 6),
                                    ShapeParams.Operation.UNITE, 0, 0, 0),
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.BOWL).withSize(3).with("depth", 2),
                                    ShapeParams.Operation.UNITE, 0, 6, 0)))),
            new Preset("switchback_stairs", ShapeParams.defaults(ShapeType.STAIRS).withSize(2).with("steps", 14).with("turn", 3)),
            new Preset("elbow_pipe", ShapeParams.defaults(ShapeType.PIPE).withSize(2).with("bend", 1).with("pipe_length", 8)),
            new Preset("rose_plaza", ShapeParams.defaults(ShapeType.FLOOR).withSize(10).with("length", 10).with("outline", 2)
                    .with("pattern", 14).with("tile", 2)),
            new Preset("cobble_courtyard", ShapeParams.defaults(ShapeType.FLOOR).withSize(9).with("length", 7).with("outline", 1)
                    .with("pattern", 15).with("tile", 1)),
            new Preset("heart_terrace", ShapeParams.defaults(ShapeType.FLOOR).withSize(9).with("length", 9).with("outline", 8)
                    .with("pattern", 5).with("tile", 2)),
            new Preset("gazebo", ShapeParams.defaults(ShapeType.FLOOR).withSize(5).with("length", 5).with("outline", 4)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.CYLINDER).withSize(0).with("height", 6),
                                    ShapeParams.Operation.UNITE, 4, 1, 0).withRepeat(8),
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.CONE).withSize(6).with("height", 5).with("sides", 8).withHollow(true),
                                    ShapeParams.Operation.UNITE, 0, 7, 0)))),
            new Preset("cottage_shell", ShapeParams.defaults(ShapeType.BOX).withSize(5).with("length", 7).with("height", 6).withHollow(true)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.ROOF).withSize(5).with("length", 7)
                                    .with("overhang", 1).with("pitch", 1.0).with("gable_ends", 1),
                                    ShapeParams.Operation.UNITE, 0, 6, 0)))),
            new Preset("windmill", ShapeParams.defaults(ShapeType.FRUSTUM).withSize(5).with("height", 14).with("sides", 8)
                    .with("top_ratio", 0.55).withHollow(true).with("wall", 1)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.WHEEL).withSize(7).withOrientation(ShapeParams.Orientation.UPRIGHT_NS)
                                    .with("spokes", 4).with("wheel_rim", 1).with("spoke_width", 1).with("hub_size", 1).with("thickness", 1),
                                    ShapeParams.Operation.UNITE, 0, 4, -5)))),
            new Preset("aqueduct_span", ShapeParams.defaults(ShapeType.ARCH).withSize(4).with("height_ratio", 1.0)
                    .with("thickness", 2).with("depth", 3)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.BOX).withSize(4).with("length", 1).with("height", 2),
                                    ShapeParams.Operation.UNITE, 0, 5, 0)))),
            new Preset("portal_frame", ShapeParams.defaults(ShapeType.TORUS).withSize(5).with("tube_radius", 1)
                    .withOrientation(ShapeParams.Orientation.UPRIGHT_NS)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.BOX).withSize(6).with("length", 2).with("height", 1),
                                    ShapeParams.Operation.UNITE, 0, 0, 0)))),
            new Preset("pagoda_tier", ShapeParams.defaults(ShapeType.FRUSTUM).withSize(6).with("height", 5).with("sides", 4)
                    .with("top_ratio", 0.7).withHollow(true)
                    .withParts(List.of(
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.FRUSTUM).withSize(8).with("height", 2).with("sides", 4)
                                    .with("top_ratio", 0.65).withHollow(true),
                                    ShapeParams.Operation.UNITE, 0, 5, 0),
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.FRUSTUM).withSize(4).with("height", 4).with("sides", 4)
                                    .with("top_ratio", 0.7).withHollow(true),
                                    ShapeParams.Operation.UNITE, 0, 7, 0),
                            new ShapeParams.Part(ShapeParams.defaults(ShapeType.PYRAMID).withSize(5).with("height", 4).withHollow(true),
                                    ShapeParams.Operation.UNITE, 0, 11, 0)))),
            new Preset("twisted_spire", ShapeParams.defaults(ShapeType.PRISM).withSize(5).with("sides", 6).with("height", 20)
                    .withHollow(true).with(ShapeModifiers.TAPER, 0.8).with(ShapeModifiers.TWIST, 180)));

    private static List<Preset> loaded = null;

    private ShapePresets() {}

    public static List<Preset> get() {
        if (loaded == null) reload();
        return loaded;
    }

    /** Reloads built-in designs merged with any custom {@code .json} files in {@code config/shapewright/designs/}. */
    public static void reload() {
        Map<String, Preset> byId = new LinkedHashMap<>();
        for (Preset p : ALL) byId.put(p.id(), p);

        if (Files.isDirectory(FOLDER)) {
            try (Stream<Path> files = Files.list(FOLDER)) {
                List<Path> jsonFiles = files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".json"))
                        .sorted().toList();
                for (Path file : jsonFiles) {
                    loadFile(file, byId);
                }
            } catch (IOException e) {
                Constants.LOG.warn("[Shapewright] Cannot scan custom designs in {}", FOLDER, e);
            }
        }
        loaded = List.copyOf(new ArrayList<>(byId.values()));
    }

    private static void loadFile(Path file, Map<String, Preset> byId) {
        try {
            JsonElement root = GSON.fromJson(Files.readString(file), JsonElement.class);
            if (root == null) return;
            if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    if (el.isJsonObject()) readEntry(el.getAsJsonObject(), null, byId);
                }
            } else if (root.isJsonObject()) {
                String fallbackId = file.getFileName().toString().replaceFirst("(?i)\\.json$", "");
                readEntry(root.getAsJsonObject(), fallbackId, byId);
            }
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[Shapewright] Cannot read custom design {}", file, e);
        }
    }

    private static void readEntry(JsonObject o, @Nullable String fallbackId, Map<String, Preset> byId) {
        if (!o.has("shape") || !o.get("shape").isJsonObject()) return;
        ShapeParams params = ShapeTemplateStorage.fromJson(o.getAsJsonObject("shape"));
        if (params == null) return;
        String id = o.has("id") ? o.get("id").getAsString() : fallbackId;
        if (id == null || id.isBlank()) return;
        String name = o.has("name") ? o.get("name").getAsString() : id;
        byId.put(id, new Preset(id, name, params));
    }

    private static ShapeParams tower(int radius, int height, int sides) {
        return ShapeParams.defaults(ShapeType.TOWER).withSize(radius).with("height", height).with("sides", sides);
    }
}

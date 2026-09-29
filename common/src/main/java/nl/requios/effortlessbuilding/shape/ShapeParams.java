package nl.requios.effortlessbuilding.shape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything needed to generate a shape: its type, size, per-type parameters, how it is placed,
 * and any extra parts combined into it. Immutable; the {@code with...} methods return copies.
 *
 * @param size      main radius in blocks (ignored for schematics)
 * @param values    per-type parameters, keyed by {@link ShapeType.ParamSpec#key()}; missing keys use defaults
 * @param schematic file name for {@link ShapeType#SCHEMATIC}, empty otherwise
 * @param parts     more shapes combined into this one, in order (a part never has parts of its own)
 * @param centerBlock block id for a center axle through the shape (1 by 1, or 2 by 2 when the shape has an
 *                  even width), empty for none
 * @param path      path sizing: the points clicked between the start and the end, relative to the start
 *                  (set while placing, not part of a saved design)
 */
public record ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                          boolean hollow, Sizing sizing, String schematic, List<Part> parts, String centerBlock,
                          List<ShapeGenerator.Cell> path) {

    /** Most points a path can have between its start and end. */
    public static final int MAX_PATH_POINTS = 64;

    public static final int MAX_SIZE = 256;
    public static final int MAX_PARTS = 16;

    /** How the shape's local frame maps onto the world. Sent by ordinal: only append. */
    public enum Orientation {
        /** Face plane horizontal, depth axis up. */
        FLAT,
        /** Face plane vertical, facing north/south. */
        UPRIGHT_NS,
        /** Face plane vertical, facing east/west. */
        UPRIGHT_EW;

        public String getNameKey() {
            return "effortlessbuilding.shape.orientation." + name().toLowerCase();
        }
    }

    /** Where the size comes from when placing. Sent by ordinal: only append. */
    public enum Sizing {
        /** The size set in the screen; one click places. */
        SCREEN,
        /** Click the center, then click again at the radius. */
        CLICKS,
        /** Click a start and an end; copies repeat along the line between them. */
        PATH;

        public String getNameKey() {
            return "effortlessbuilding.shape.sizing." + name().toLowerCase();
        }
    }

    /** How a part combines with everything before it, like Illustrator's Pathfinder. Sent by ordinal: only append. */
    public enum Operation {
        /** Add the part's blocks. */
        UNITE,
        /** Cut the part's blocks away. */
        SUBTRACT,
        /** Keep only blocks inside both. */
        INTERSECT,
        /** Keep blocks inside exactly one of them. */
        EXCLUDE;

        public String getNameKey() {
            return "effortlessbuilding.shape.operation." + name().toLowerCase();
        }
    }

    /** Most copies a part can be repeated into around the main shape. */
    public static final int MAX_REPEAT = 64;

    /**
     * A shape combined into the main one.
     *
     * @param x      offset of the part's anchor from the main anchor, in blocks (likewise y, z)
     * @param block  item id of the block this part's blocks use, or "" for the held block / palette
     * @param repeat copies spread evenly around the main shape's axis (1: just this one)
     */
    public record Part(ShapeParams shape, Operation operation, int x, int y, int z, String block, int repeat) {
        public Part {
            // Parts are flat: drop any nested parts
            if (!shape.parts().isEmpty()) shape = shape.withParts(List.of());
            int limit = MAX_SIZE * 2;
            x = Math.max(-limit, Math.min(limit, x));
            y = Math.max(-limit, Math.min(limit, y));
            z = Math.max(-limit, Math.min(limit, z));
            block = block == null ? "" : block;
            repeat = Math.max(1, Math.min(MAX_REPEAT, repeat));
        }

        public Part(ShapeParams shape, Operation operation, int x, int y, int z) {
            this(shape, operation, x, y, z, "", 1);
        }

        public Part withShape(ShapeParams shape) { return new Part(shape, operation, x, y, z, block, repeat); }
        public Part withOperation(Operation operation) { return new Part(shape, operation, x, y, z, block, repeat); }
        public Part withOffset(int x, int y, int z) { return new Part(shape, operation, x, y, z, block, repeat); }
        public Part withBlock(String block) { return new Part(shape, operation, x, y, z, block, repeat); }
        public Part withRepeat(int repeat) { return new Part(shape, operation, x, y, z, block, repeat); }
    }

    public ShapeParams {
        size = Math.max(1, Math.min(MAX_SIZE, size));
        TreeMap<String, Double> clean = new TreeMap<>();
        for (ShapeType.ParamSpec spec : type.params) {
            Double v = values.get(spec.key());
            clean.put(spec.key(), spec.clamp(v == null ? spec.defaultValue() : v));
        }
        values = Collections.unmodifiableMap(clean);
        hollow = hollow && type.hollowable;
        if (!type.resizable() && sizing == Sizing.CLICKS) sizing = Sizing.SCREEN;
        schematic = schematic == null ? "" : schematic;
        centerBlock = centerBlock == null ? "" : centerBlock;
        parts = parts == null ? List.of() : List.copyOf(parts.subList(0, Math.min(parts.size(), MAX_PARTS)));
        path = path == null ? List.of() : List.copyOf(path.subList(0, Math.min(path.size(), MAX_PATH_POINTS)));
    }

    /** A shape with parts and a center block, no path. */
    public ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                       boolean hollow, Sizing sizing, String schematic, List<Part> parts, String centerBlock) {
        this(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, List.of());
    }

    public ShapeParams withPath(List<ShapeGenerator.Cell> path) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    /** A single shape without parts. */
    public ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                       boolean hollow, Sizing sizing, String schematic) {
        this(type, size, values, orientation, hollow, sizing, schematic, List.of(), "");
    }

    /** A shape with parts and no center block. */
    public ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                       boolean hollow, Sizing sizing, String schematic, List<Part> parts) {
        this(type, size, values, orientation, hollow, sizing, schematic, parts, "");
    }

    public ShapeParams withCenterBlock(String blockId) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, blockId, path);
    }

    public static ShapeParams defaults(ShapeType type) {
        return new ShapeParams(type, Math.max(1, type.defaultSize), Map.of(), type.defaultOrientation,
                false, Sizing.SCREEN, "");
    }

    public double get(String key) {
        Double v = values.get(key);
        return v != null ? v : type.param(key).defaultValue();
    }

    public int getInt(String key) {
        return (int) Math.round(get(key));
    }

    public ShapeParams with(String key, double value) {
        TreeMap<String, Double> copy = new TreeMap<>(values);
        copy.put(key, value);
        return new ShapeParams(type, size, copy, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withSize(int size) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withOrientation(Orientation orientation) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withHollow(boolean hollow) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withSizing(Sizing sizing) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withSchematic(String schematic) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withParts(List<Part> parts) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }

    /** The same shape as a different type: keeps placement settings and parts, resets the type's parameters. */
    public ShapeParams withType(ShapeType type) {
        ShapeParams d = defaults(type);
        return new ShapeParams(type, d.size(), Map.of(), d.orientation(), hollow, sizing, schematic, parts, centerBlock, path);
    }

    public ShapeParams withPart(int index, Part part) {
        List<Part> copy = new ArrayList<>(parts);
        copy.set(index, part);
        return withParts(copy);
    }

    /**
     * The same design at another size: every length parameter scales with it, counts and ratios stay.
     * Parts scale with it too, offsets included, so the combination keeps its proportions.
     * Lengths never drop below their minimum.
     */
    public ShapeParams scaledTo(int newSize) {
        if (newSize == size || !type.resizable()) return withSize(newSize);
        double factor = (double) newSize / size;
        List<Part> scaledParts = new ArrayList<>(parts.size());
        for (Part part : parts) {
            ShapeParams s = part.shape();
            scaledParts.add(new Part(s.scaleBy(factor), part.operation(),
                    (int) Math.round(part.x() * factor), (int) Math.round(part.y() * factor),
                    (int) Math.round(part.z() * factor)));
        }
        return scaleBy(factor).withSize(newSize).withParts(scaledParts);
    }

    private ShapeParams scaleBy(double factor) {
        if (!type.resizable()) return this;
        TreeMap<String, Double> scaled = new TreeMap<>(values);
        for (ShapeType.ParamSpec spec : type.params) {
            if (spec.scales()) scaled.put(spec.key(), spec.clamp(get(spec.key()) * factor));
        }
        int newSize = (int) Math.max(1, Math.round(size * factor));
        return new ShapeParams(type, newSize, scaled, orientation, hollow, sizing, schematic, parts, centerBlock, path);
    }
}

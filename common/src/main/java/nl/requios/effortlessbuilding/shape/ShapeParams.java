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
 */
public record ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                          boolean hollow, Sizing sizing, String schematic, List<Part> parts) {

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
        CLICKS;

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

    /**
     * A shape combined into the main one.
     *
     * @param x offset of the part's anchor from the main anchor, in blocks (likewise y, z)
     */
    public record Part(ShapeParams shape, Operation operation, int x, int y, int z) {
        public Part {
            // Parts are flat: drop any nested parts
            if (!shape.parts().isEmpty()) shape = shape.withParts(List.of());
            int limit = MAX_SIZE * 2;
            x = Math.max(-limit, Math.min(limit, x));
            y = Math.max(-limit, Math.min(limit, y));
            z = Math.max(-limit, Math.min(limit, z));
        }

        public Part withShape(ShapeParams shape) { return new Part(shape, operation, x, y, z); }
        public Part withOperation(Operation operation) { return new Part(shape, operation, x, y, z); }
        public Part withOffset(int x, int y, int z) { return new Part(shape, operation, x, y, z); }
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
        if (!type.resizable()) sizing = Sizing.SCREEN;
        schematic = schematic == null ? "" : schematic;
        parts = parts == null ? List.of() : List.copyOf(parts.subList(0, Math.min(parts.size(), MAX_PARTS)));
    }

    /** A single shape without parts. */
    public ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                       boolean hollow, Sizing sizing, String schematic) {
        this(type, size, values, orientation, hollow, sizing, schematic, List.of());
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
        return new ShapeParams(type, size, copy, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withSize(int size) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withOrientation(Orientation orientation) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withHollow(boolean hollow) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withSizing(Sizing sizing) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withSchematic(String schematic) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    public ShapeParams withParts(List<Part> parts) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic, parts);
    }

    /** The same shape as a different type: keeps placement settings and parts, resets the type's parameters. */
    public ShapeParams withType(ShapeType type) {
        ShapeParams d = defaults(type);
        return new ShapeParams(type, d.size(), Map.of(), d.orientation(), hollow, sizing, schematic, parts);
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
        return new ShapeParams(type, newSize, scaled, orientation, hollow, sizing, schematic, parts);
    }
}

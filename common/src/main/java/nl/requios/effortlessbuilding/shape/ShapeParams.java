package nl.requios.effortlessbuilding.shape;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything needed to generate a shape: its type, size, per-type parameters and how it is placed.
 * Immutable; the {@code with...} methods return copies.
 *
 * @param size      main radius in blocks (ignored for schematics)
 * @param values    per-type parameters, keyed by {@link ShapeType.ParamSpec#key()}; missing keys use defaults
 * @param schematic file name for {@link ShapeType#SCHEMATIC}, empty otherwise
 */
public record ShapeParams(ShapeType type, int size, Map<String, Double> values, Orientation orientation,
                          boolean hollow, Sizing sizing, String schematic) {

    public static final int MAX_SIZE = 256;

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
        return new ShapeParams(type, size, copy, orientation, hollow, sizing, schematic);
    }

    public ShapeParams withSize(int size) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    public ShapeParams withOrientation(Orientation orientation) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    public ShapeParams withHollow(boolean hollow) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    public ShapeParams withSizing(Sizing sizing) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    public ShapeParams withSchematic(String schematic) {
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    /**
     * The same design at another size: every length parameter scales with it, counts and ratios stay.
     * Lengths never drop below their minimum.
     */
    public ShapeParams scaledTo(int newSize) {
        if (newSize == size || !type.resizable()) return withSize(newSize);
        double factor = (double) newSize / size;
        TreeMap<String, Double> scaled = new TreeMap<>(values);
        for (ShapeType.ParamSpec spec : type.params) {
            if (spec.scales()) scaled.put(spec.key(), spec.clamp(get(spec.key()) * factor));
        }
        return new ShapeParams(type, newSize, scaled, orientation, hollow, sizing, schematic);
    }
}

package nl.requios.effortlessbuilding.shape;

import java.util.List;

/**
 * Parametric shapes the Shape Generator can build. Sent over the network by ordinal:
 * only ever append new entries.
 * <p>
 * Every shape is described in a local frame: a face plane (u, v) and a depth axis w
 * (height for flat shapes, thickness for upright ones). {@link ShapeParams.Orientation}
 * maps that frame onto the world.
 */
public enum ShapeType {
    GEAR(10, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.count("teeth", 16, 3, 128),
            ParamSpec.length("tooth_length", 3, 1),
            ParamSpec.length("tooth_width", 3, 1),
            ParamSpec.ratio("tooth_rotation", 0.5, 0, 1),
            ParamSpec.length("rim_width", 3, 1),
            ParamSpec.length("frame_size", 7, 0),
            ParamSpec.length("frame_width", 2, 0),
            ParamSpec.length("inner_size", 4, 0),
            ParamSpec.length("inner_width", 1, 0),
            ParamSpec.length("hub_size", 1, 0),
            ParamSpec.length("body_height", 2, 1),
            ParamSpec.length("frame_height", 3, 0),
            ParamSpec.length("inner_height", 3, 0),
            ParamSpec.length("hub_height", 3, 0),
            ParamSpec.length("tooth_start", 0, 0),
            ParamSpec.length("tooth_height", 2, 1))),
    TORUS(10, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("tube_radius", 3, 1))),
    ARCH(8, false, ShapeParams.Orientation.UPRIGHT_NS, List.of(
            ParamSpec.ratio("height_ratio", 1, 0.2, 4),
            ParamSpec.length("thickness", 2, 1),
            ParamSpec.length("depth", 3, 1))),
    HELIX(6, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("inner_radius", 1, 0),
            ParamSpec.length("height", 16, 1),
            ParamSpec.ratio("turns", 2, 0.25, 32),
            ParamSpec.length("thickness", 1, 1))),
    ELLIPSOID(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("radius_y", 6, 1),
            ParamSpec.length("radius_z", 8, 1))),
    PRISM(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.count("sides", 6, 3, 64),
            ParamSpec.length("height", 10, 1),
            ParamSpec.ratio("rotation", 0, 0, 1))),
    STAR(10, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.count("points", 5, 3, 64),
            ParamSpec.ratio("inner_ratio", 0.45, 0.1, 0.95),
            ParamSpec.length("height", 2, 1),
            ParamSpec.ratio("rotation", 0, 0, 1))),
    CONE(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 12, 1),
            ParamSpec.count("sides", 0, 0, 64))),
    /** Positions of a .schem file; no parameters and a fixed size. */
    SCHEMATIC(0, false, ShapeParams.Orientation.FLAT, List.of());

    /** Size (main radius in blocks) the parameter defaults are designed for. */
    public final int defaultSize;
    public final boolean hollowable;
    public final ShapeParams.Orientation defaultOrientation;
    public final List<ParamSpec> params;

    ShapeType(int defaultSize, boolean hollowable, ShapeParams.Orientation defaultOrientation, List<ParamSpec> params) {
        this.defaultSize = defaultSize;
        this.hollowable = hollowable;
        this.defaultOrientation = defaultOrientation;
        this.params = params;
    }

    public String getNameKey() {
        return "effortlessbuilding.shape." + name().toLowerCase();
    }

    /** Whether the shape can be sized by clicking (everything but fixed-size schematics). */
    public boolean resizable() {
        return this != SCHEMATIC;
    }

    public ParamSpec param(String key) {
        for (ParamSpec spec : params) if (spec.key().equals(key)) return spec;
        throw new IllegalArgumentException(this + " has no parameter " + key);
    }

    /**
     * @param scales whether the value is a length that grows with the shape's size
     * @param integer whether the value is a whole number
     */
    public record ParamSpec(String key, double defaultValue, double min, double max, boolean scales, boolean integer) {
        static ParamSpec length(String key, double def, double min) {
            return new ParamSpec(key, def, min, 256, true, true);
        }

        static ParamSpec count(String key, double def, double min, double max) {
            return new ParamSpec(key, def, min, max, false, true);
        }

        static ParamSpec ratio(String key, double def, double min, double max) {
            return new ParamSpec(key, def, min, max, false, false);
        }

        public double clamp(double value) {
            double v = Math.max(min, Math.min(max, value));
            return integer ? Math.round(v) : v;
        }

        public String getNameKey() {
            return "effortlessbuilding.shape.param." + key;
        }
    }
}

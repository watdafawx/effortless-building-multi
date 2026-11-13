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
            ParamSpec.choice("align", 0, "bottom", "center", "top"),
            ParamSpec.length("body_height", 2, 1),
            ParamSpec.offset("body_start"),
            ParamSpec.length("frame_height", 3, 0),
            ParamSpec.offset("frame_start"),
            ParamSpec.length("inner_height", 3, 0),
            ParamSpec.offset("inner_start"),
            ParamSpec.length("hub_height", 3, 0),
            ParamSpec.offset("hub_start"),
            ParamSpec.length("tooth_height", 2, 1),
            ParamSpec.offset("tooth_start"))),
    TORUS(10, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("tube_radius", 3, 1))),
    ARCH(8, false, ShapeParams.Orientation.UPRIGHT_NS, List.of(
            ParamSpec.ratio("height_ratio", 1, 0.2, 4),
            ParamSpec.length("thickness", 2, 1),
            ParamSpec.length("depth", 3, 1),
            ParamSpec.choice("style", 0, "round", "pointed"))),
    HELIX(6, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("pole_radius", 1, 0),
            ParamSpec.length("inner_radius", 2, 0),
            ParamSpec.length("height", 16, 1),
            ParamSpec.ratio("turns", 2, 0.25, 32),
            ParamSpec.length("thickness", 1, 1))),
    ELLIPSOID(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("radius_y", 6, 1),
            ParamSpec.length("radius_z", 8, 1))),
    PRISM(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.count("sides", 6, 3, 64),
            ParamSpec.length("height", 10, 1),
            ParamSpec.ratio("rotation", 0, 0, 1),
            ParamSpec.choice("caps", 0, "open", "closed"))),
    STAR(10, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.count("points", 5, 3, 64),
            ParamSpec.ratio("inner_ratio", 0.45, 0.1, 0.95),
            ParamSpec.length("height", 2, 1),
            ParamSpec.ratio("rotation", 0, 0, 1),
            ParamSpec.choice("caps", 0, "open", "closed"))),
    CONE(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 12, 1),
            ParamSpec.count("sides", 0, 0, 64))),
    PYRAMID(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 9, 1))),
    /** Paraboloid dish opening upward. */
    BOWL(8, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("depth", 4, 1),
            ParamSpec.length("thickness", 1, 1))),
    /** Rim, spokes and hub. */
    WHEEL(10, false, ShapeParams.Orientation.UPRIGHT_NS, List.of(
            ParamSpec.length("wheel_rim", 2, 1),
            ParamSpec.count("spokes", 6, 2, 32),
            ParamSpec.length("spoke_width", 1, 1),
            ParamSpec.length("hub_size", 2, 0),
            ParamSpec.length("thickness", 1, 1))),
    /** Round or polygonal tower wall topped with battlements. */
    TOWER(6, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 14, 1),
            ParamSpec.count("sides", 0, 0, 64),
            ParamSpec.length("wall", 1, 1),
            ParamSpec.length("battlement", 1, 0),
            ParamSpec.choice("door", 0, "off", "on"),
            ParamSpec.count("windows", 0, 0, 16),
            ParamSpec.count("floors", 0, 0, 16),
            ParamSpec.choice("roof", 0, "off", "cone"))),
    /** Flat spiral with one or more arms, like a galaxy or a maze path. */
    SPIRAL(10, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.ratio("turns", 2, 0.25, 16),
            ParamSpec.count("arms", 2, 1, 12),
            ParamSpec.length("arm_width", 1, 1),
            ParamSpec.length("height", 1, 1))),
    /** Positions of a .schem file; no parameters and a fixed size. */
    SCHEMATIC(0, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.choice(ShapeMaterials.USE_SAVED_BLOCKS, 1, "held", "saved"),
            ParamSpec.choice(TerrainBlender.ENABLED, 0, "off", "on"),
            ParamSpec.count(TerrainBlender.MARGIN, 6, 1, 32))),
    /** Block letters in a 5x7 pixel font; the text is {@link ShapeParams#text()}, '|' starts a new line. */
    TEXT(1, false, ShapeParams.Orientation.UPRIGHT_NS, List.of(
            ParamSpec.count("scale", 1, 1, 8),
            ParamSpec.count("depth", 1, 1, 16),
            ParamSpec.count("letter_spacing", 1, 0, 4),
            ParamSpec.choice("bold", 0, "off", "on"))),
    /**
     * Flat floor with a footprint outline and a tile pattern. The pattern keeps some blocks and leaves the rest
     * open; add the same floor as a part with "invert" on and its own block for the other half.
     */
    FLOOR(10, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("length", 10, 1),
            ParamSpec.choice("outline", 0, "rectangle", "rounded", "ellipse", "diamond", "octagon", "hexagon", "plus",
                    "star", "heart", "triangle"),
            ParamSpec.choice("pattern", 0, "solid", "checker", "stripes", "rows", "diagonal", "border", "grid",
                    "bricks", "diamonds", "dots", "rings", "chevron", "basketweave", "wave", "spiral", "scatter"),
            ParamSpec.count("tile", 2, 1, 16),
            ParamSpec.choice("invert", 0, "off", "on"),
            ParamSpec.length("thickness", 1, 1))),
    /** Half an ellipsoid standing on its flat base (or hanging, flipped); hollow keeps a shell with an open base. */
    DOME(8, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.ratio("height_ratio", 1, 0.2, 4),
            ParamSpec.length("wall", 1, 1),
            ParamSpec.choice("flip", 0, "up", "down"))),
    /** Flight of steps, straight or turning at a landing. */
    STAIRS(3, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("steps", 8, 1),
            ParamSpec.count("rise", 1, 1, 4),
            ParamSpec.count("run", 1, 1, 4),
            ParamSpec.choice("turn", 0, "straight", "left", "right", "u_turn"),
            ParamSpec.choice("fill", 0, "solid", "steps"))),
    /** Tube with a wall, straight or with a 90 degree elbow. Stands upright by default. */
    PIPE(3, false, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("pipe_length", 10, 1),
            ParamSpec.length("wall", 1, 1),
            ParamSpec.choice("bend", 0, "straight", "elbow"),
            ParamSpec.length("bend_radius", 4, 1),
            ParamSpec.length("pipe_arm", 6, 1))),
    /** Cuboid: size is the half-width, so the width is 2 * size + 1 (likewise the length). */
    BOX(4, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("length", 4, 0),
            ParamSpec.length("height", 9, 1))),
    SPHERE(6, true, ShapeParams.Orientation.FLAT, List.of()),
    CYLINDER(4, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 10, 1))),
    /** Ramp: full height on the -v side, sloping down to the +v side. */
    WEDGE(4, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("length", 6, 0),
            ParamSpec.length("height", 6, 1))),
    /** Cylinder with a half sphere on each end; the height includes both caps. */
    CAPSULE(4, true, ShapeParams.Orientation.FLAT, List.of(
            ParamSpec.length("height", 14, 1))),
    /** Two pyramids base to base. */
    OCTAHEDRON(6, true, ShapeParams.Orientation.FLAT, List.of());

    /** Size (main radius in blocks) the parameter defaults are designed for. */
    public final int defaultSize;
    public final boolean hollowable;
    public final ShapeParams.Orientation defaultOrientation;
    public final List<ParamSpec> params;

    ShapeType(int defaultSize, boolean hollowable, ShapeParams.Orientation defaultOrientation, List<ParamSpec> params) {
        this.defaultSize = defaultSize;
        this.hollowable = hollowable;
        this.defaultOrientation = defaultOrientation;
        // Every shape can be turned freely around each axis (applied after its orientation)
        List<ParamSpec> all = new java.util.ArrayList<>(params);
        all.add(ParamSpec.angle(ROTATE_X));
        all.add(ParamSpec.angle(ROTATE_Y));
        all.add(ParamSpec.angle(ROTATE_Z));
        // Only used on the main shape, and only offered when Create is installed
        all.add(ParamSpec.choice(SUPER_GLUE, 0, "off", "on"));
        all.add(ParamSpec.choice(BEARING, 0, "off", "start", "end"));
        // Main shape only: follow the terrain, and path settings (shown when sizing is Path)
        all.add(ParamSpec.choice(FOLLOW_GROUND, 0, "off", "on"));
        all.add(ParamSpec.choice(SMOOTH, 0, "off", "stairs", "slabs", "slopes"));
        all.add(ParamSpec.count(PATH_SPACING, 8, 1, 64));
        all.add(ParamSpec.choice(PATH_ALIGN, 1, "off", "on"));
        // Main shape only: which point of the shape sits on the placement point, and a shift from there
        all.add(ParamSpec.choice(ANCHOR, 0, "base_center", "center", "top_center", "corner"));
        all.add(ParamSpec.offset(ANCHOR_X));
        all.add(ParamSpec.offset(ANCHOR_Y));
        all.add(ParamSpec.offset(ANCHOR_Z));
        this.params = List.copyOf(all);
    }

    /**
     * Which point of the shape sits on the placement point: 0 the middle of its base (default), 1 its center,
     * 2 the middle of its top, 3 its lowest corner. {@link #ANCHOR_X} and friends then move that point, in blocks.
     */
    public static final String ANCHOR = "anchor", ANCHOR_X = "anchor_x", ANCHOR_Y = "anchor_y", ANCHOR_Z = "anchor_z";

    /** Move every column onto the terrain below it (1) or keep the shape level (0). */
    public static final String FOLLOW_GROUND = "follow_ground";
    /** Soften one-block steps on the top of the shape: 0 off, 1 stairs (slabs at corners), 2 slabs only, 3 Framed Blocks slopes (slabs at corners). */
    public static final String SMOOTH = "smooth";
    /** Path sizing: blocks between copies, and whether copies turn to face along the path. */
    public static final String PATH_SPACING = "path_spacing", PATH_ALIGN = "path_align";

    /** Settings that belong to the whole build, never to a part. */
    public static boolean isBuildSetting(String key) {
        return key.equals(SUPER_GLUE) || key.equals(BEARING) || key.equals(FOLLOW_GROUND) || key.equals(SMOOTH)
                || key.equals(PATH_SPACING) || key.equals(PATH_ALIGN)
                || key.equals(ANCHOR) || key.equals(ANCHOR_X) || key.equals(ANCHOR_Y) || key.equals(ANCHOR_Z);
    }

    public static final String ROTATE_X = "rotate_x", ROTATE_Y = "rotate_y", ROTATE_Z = "rotate_z";
    /** Glue the built shape together with Create's super glue (1) or not (0). */
    public static final String SUPER_GLUE = "super_glue";
    /**
     * Put a Create mechanical bearing against one end of the center axle, facing the shape, so the glued
     * shape can spin: 0 off, 1 at the start of the axis (below / north / west), 2 at the end.
     */
    public static final String BEARING = "bearing";

    public String getNameKey() {
        return "effortlessbuilding.shape." + name().toLowerCase();
    }

    /** Whether the shape can be sized by clicking (everything but fixed-size schematics). */
    public boolean resizable() {
        return this != SCHEMATIC && this != TEXT;
    }

    public ParamSpec param(String key) {
        for (ParamSpec spec : params) if (spec.key().equals(key)) return spec;
        throw new IllegalArgumentException(this + " has no parameter " + key);
    }

    /**
     * @param scales whether the value is a length that grows with the shape's size
     * @param integer whether the value is a whole number
     */
    /**
     * @param options for a choice: the option names (the value is the chosen index); empty otherwise
     */
    public record ParamSpec(String key, double defaultValue, double min, double max, boolean scales, boolean integer,
                            double step, List<String> options) {
        static ParamSpec length(String key, double def, double min) {
            return new ParamSpec(key, def, min, 256, true, true, 1, List.of());
        }

        /** A shift in blocks, up or down (negative), that grows with the shape. */
        static ParamSpec offset(String key) {
            return new ParamSpec(key, 0, -256, 256, true, true, 1, List.of());
        }

        static ParamSpec count(String key, double def, double min, double max) {
            return new ParamSpec(key, def, min, max, false, true, 1, List.of());
        }

        static ParamSpec ratio(String key, double def, double min, double max) {
            return new ParamSpec(key, def, min, max, false, false, 0.05, List.of());
        }

        /** Degrees, -180 to 180, stepping 15 in the screen (any value can be typed). */
        static ParamSpec angle(String key) {
            return new ParamSpec(key, 0, -180, 180, false, false, 15, List.of());
        }

        /** One of a few named options, shown as a button that cycles through them. */
        static ParamSpec choice(String key, int def, String... options) {
            return new ParamSpec(key, def, 0, options.length - 1, false, true, 1, List.of(options));
        }

        public String getOptionKey(int index) {
            return getNameKey() + "." + options.get(Math.max(0, Math.min(options.size() - 1, index)));
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

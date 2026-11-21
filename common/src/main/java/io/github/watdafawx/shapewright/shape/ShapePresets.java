package io.github.watdafawx.shapewright.shape;

import java.util.List;

/**
 * Ready-made designs shown in the Shape Generator next to the player's own templates. They are built from
 * the same parameters as anything the player makes, so picking one just loads it for editing.
 */
public final class ShapePresets {

    /** @param id names the design: its title is the language key {@code shapewright.design.<id>} */
    public record Preset(String id, ShapeParams params) {
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

    private ShapePresets() {}

    public static List<Preset> get() {
        return ALL;
    }

    private static ShapeParams tower(int radius, int height, int sides) {
        return ShapeParams.defaults(ShapeType.TOWER).withSize(radius).with("height", height).with("sides", sides);
    }
}

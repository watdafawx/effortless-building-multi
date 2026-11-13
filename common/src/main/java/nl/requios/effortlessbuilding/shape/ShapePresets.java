package nl.requios.effortlessbuilding.shape;

import java.util.List;

/**
 * Ready-made designs shown in the Shape Generator next to the player's own templates. They are built from
 * the same parameters as anything the player makes, so picking one just loads it for editing.
 */
public final class ShapePresets {

    /** @param id names the design: its title is the language key {@code effortlessbuilding.design.<id>} */
    public record Preset(String id, ShapeParams params) {
        public String getNameKey() {
            return "effortlessbuilding.design." + id;
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
                    .with("pattern", 5).with("tile", 2)));

    private ShapePresets() {}

    public static List<Preset> get() {
        return ALL;
    }

    private static ShapeParams tower(int radius, int height, int sides) {
        return ShapeParams.defaults(ShapeType.TOWER).withSize(radius).with("height", height).with("sides", sides);
    }
}

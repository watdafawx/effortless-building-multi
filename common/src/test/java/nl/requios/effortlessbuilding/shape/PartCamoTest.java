package nl.requios.effortlessbuilding.shape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A part's camo (for Framed Blocks) survives saved templates, edits and scaling. */
class PartCamoTest {

    private static ShapeParams withCamo(String block, String camo) {
        ShapeParams part = ShapeParams.defaults(ShapeType.PRISM).withSize(1);
        return ShapeParams.defaults(ShapeType.TOWER).withSize(4)
                .withParts(List.of(new ShapeParams.Part(part, ShapeParams.Operation.UNITE, 0, 0, 0, block, 3, camo)));
    }

    @Test
    void camoDefaultsToNone() {
        ShapeParams.Part part = new ShapeParams.Part(ShapeParams.defaults(ShapeType.PRISM), ShapeParams.Operation.UNITE, 0, 0, 0);
        assertEquals("", part.camo());
        assertEquals("minecraft:stone", part.withCamo("minecraft:stone").camo());
        assertEquals("", part.withCamo(null).camo());
    }

    @Test
    void otherEditsKeepTheCamo() {
        ShapeParams.Part part = withCamo("", "minecraft:stone").parts().get(0);
        assertEquals("minecraft:stone", part.withRepeat(5).camo());
        assertEquals("minecraft:stone", part.withBlock("minecraft:glass").camo());
        assertEquals("minecraft:stone", part.withOffset(1, 2, 3).camo());
    }

    @Test
    void camoIsSavedInTemplates() {
        ShapeParams p = withCamo("", "minecraft:stone");
        ShapeParams back = ShapeTemplateStorage.fromJson(ShapeTemplateStorage.toJson(p));
        assertNotNull(back);
        assertEquals("minecraft:stone", back.parts().get(0).camo());
        // Older saves have no camo key
        var json = ShapeTemplateStorage.toJson(withCamo("", ""));
        assertFalse(json.getAsJsonArray("parts").get(0).getAsJsonObject().has("camo"));
        assertEquals("", ShapeTemplateStorage.fromJson(json).parts().get(0).camo());
    }

    @Test
    void scalingKeepsBlockRepeatAndCamo() {
        ShapeParams scaled = withCamo("minecraft:glass", "minecraft:stone").scaledTo(8);
        ShapeParams.Part part = scaled.parts().get(0);
        assertEquals("minecraft:glass", part.block());
        assertEquals("minecraft:stone", part.camo());
        assertEquals(3, part.repeat());
    }
}

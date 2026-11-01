package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchematicTransferTest {

    @Test
    void roundTripsBlocksAndRejectsGarbage() {
        Map<Cell, String> blocks = new LinkedHashMap<>();
        for (int x = -20; x <= 20; x++)
            for (int y = 0; y < 10; y++)
                blocks.put(new Cell(x, y, -x), y % 2 == 0 ? "minecraft:stone" : "minecraft:oak_stairs[facing=north]");
        byte[] data = SchematicTransfer.encode(blocks);
        assertEquals(blocks, SchematicTransfer.decode(data));
        assertEquals(SchematicTransfer.hash(data), SchematicTransfer.hash(SchematicTransfer.encode(blocks)));
        assertNull(SchematicTransfer.decode(new byte[]{1, 2, 3}));
    }
}

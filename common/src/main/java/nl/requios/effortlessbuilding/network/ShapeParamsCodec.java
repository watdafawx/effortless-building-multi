package nl.requios.effortlessbuilding.network;

import net.minecraft.network.FriendlyByteBuf;
import nl.requios.effortlessbuilding.shape.ShapeGenerator;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.shape.ShapeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire format for optional {@link ShapeParams} inside build packets. The server treats the result as
 * untrusted: enum ordinals are bounds-checked, and {@link ShapeParams} clamps every value to its range.
 */
public final class ShapeParamsCodec {

    private static final int MAX_VALUES = 64;
    private static final int MAX_STRING = 256;

    private ShapeParamsCodec() {}

    public static void write(FriendlyByteBuf buf, @Nullable ShapeParams p) {
        buf.writeBoolean(p != null);
        if (p == null) return;
        writeShape(buf, p);
        buf.writeUtf(p.centerBlock(), MAX_STRING);
        buf.writeVarInt(p.parts().size());
        for (ShapeParams.Part part : p.parts()) {
            writeShape(buf, part.shape());
            buf.writeVarInt(part.operation().ordinal());
            buf.writeVarInt(part.x());
            buf.writeVarInt(part.y());
            buf.writeVarInt(part.z());
            buf.writeUtf(part.block(), 256);
            buf.writeVarInt(part.repeat());
        }
        buf.writeVarInt(p.path().size());
        for (ShapeGenerator.Cell c : p.path()) {
            buf.writeVarInt(c.x());
            buf.writeVarInt(c.y());
            buf.writeVarInt(c.z());
        }
    }

    public static @Nullable ShapeParams read(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) return null;
        ShapeParams shape = readShape(buf).withCenterBlock(buf.readUtf(MAX_STRING));
        int count = buf.readVarInt();
        if (count < 0 || count > ShapeParams.MAX_PARTS) throw new IllegalArgumentException("Too many shape parts: " + count);
        List<ShapeParams.Part> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ShapeParams partShape = readShape(buf);
            ShapeParams.Operation op = byOrdinal(ShapeParams.Operation.values(), buf.readVarInt());
            parts.add(new ShapeParams.Part(partShape, op, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readUtf(256), buf.readVarInt()));
        }
        int points = buf.readVarInt();
        if (points < 0 || points > ShapeParams.MAX_PATH_POINTS) throw new IllegalArgumentException("Too many path points: " + points);
        List<ShapeGenerator.Cell> path = new ArrayList<>(points);
        for (int i = 0; i < points; i++) path.add(new ShapeGenerator.Cell(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        return shape.withParts(parts).withPath(path);
    }

    /** One shape without its parts. */
    private static void writeShape(FriendlyByteBuf buf, ShapeParams p) {
        buf.writeVarInt(p.type().ordinal());
        buf.writeVarInt(p.size());
        buf.writeVarInt(p.values().size());
        for (var e : p.values().entrySet()) {
            buf.writeUtf(e.getKey(), MAX_STRING);
            buf.writeDouble(e.getValue());
        }
        buf.writeVarInt(p.orientation().ordinal());
        buf.writeBoolean(p.hollow());
        buf.writeVarInt(p.sizing().ordinal());
        buf.writeUtf(p.schematic(), MAX_STRING);
    }

    private static ShapeParams readShape(FriendlyByteBuf buf) {
        ShapeType type = byOrdinal(ShapeType.values(), buf.readVarInt());
        int size = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_VALUES) throw new IllegalArgumentException("Too many shape values: " + count);
        Map<String, Double> values = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(MAX_STRING);
            double value = buf.readDouble();
            if (Double.isFinite(value)) values.put(key, value);
        }
        ShapeParams.Orientation orientation = byOrdinal(ShapeParams.Orientation.values(), buf.readVarInt());
        boolean hollow = buf.readBoolean();
        ShapeParams.Sizing sizing = byOrdinal(ShapeParams.Sizing.values(), buf.readVarInt());
        String schematic = buf.readUtf(MAX_STRING);
        return new ShapeParams(type, size, values, orientation, hollow, sizing, schematic);
    }

    private static <T> T byOrdinal(T[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Bad ordinal " + ordinal);
        return values[ordinal];
    }
}

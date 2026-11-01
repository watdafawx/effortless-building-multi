package nl.requios.effortlessbuilding.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.utilities.UndoManager.Summary;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C packet: the player's undo stack (newest first) and redo stack (next redo first).
 * Ages are in seconds, measured on the server.
 */
public record UndoHistoryS2CPacket(List<Summary> undo, List<Summary> redo) implements CustomPacketPayload {

    public static final Type<UndoHistoryS2CPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "undo_history"));

    public static final StreamCodec<FriendlyByteBuf, UndoHistoryS2CPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                write(buf, p.undo);
                write(buf, p.redo);
            },
            buf -> new UndoHistoryS2CPacket(read(buf), read(buf))
    );

    private static void write(FriendlyByteBuf buf, List<Summary> list) {
        buf.writeVarInt(list.size());
        for (Summary s : list) {
            buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(s.item()));
            buf.writeVarInt(s.blocks());
            buf.writeEnum(s.kind());
            buf.writeVarLong(s.ageSeconds());
            buf.writeBoolean(s.free());
        }
    }

    private static List<Summary> read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 256);
        List<Summary> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Summary(BuiltInRegistries.ITEM.get(buf.readResourceLocation()), buf.readVarInt(),
                    buf.readEnum(Summary.Kind.class), buf.readVarLong(), buf.readBoolean()));
        }
        return list;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

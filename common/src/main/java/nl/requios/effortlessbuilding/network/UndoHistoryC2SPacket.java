package nl.requios.effortlessbuilding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.Constants;

/** C2S packet asking for the player's undo/redo history; answered with {@link UndoHistoryS2CPacket}. */
public record UndoHistoryC2SPacket() implements CustomPacketPayload {

    public static final Type<UndoHistoryC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "undo_history_request"));

    public static final StreamCodec<FriendlyByteBuf, UndoHistoryC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {},
            buf -> new UndoHistoryC2SPacket()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package nl.requios.effortlessbuilding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.Constants;

/**
 * C2S packet requesting the server to undo the player's last operations.
 *
 * @param steps how many operations at once (the history screen can go back several)
 */
public record UndoPacket(int steps) implements CustomPacketPayload {

    public UndoPacket() {
        this(1);
    }

    public static final Type<UndoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "undo"));

    public static final StreamCodec<FriendlyByteBuf, UndoPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.steps),
            buf -> new UndoPacket(buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package nl.requios.effortlessbuilding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.Constants;

/**
 * C2S packet requesting the server to redo the player's last operations.
 *
 * @param steps how many operations at once (the history screen can go back several)
 */
public record RedoPacket(int steps) implements CustomPacketPayload {

    public RedoPacket() {
        this(1);
    }

    public static final Type<RedoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "redo"));

    public static final StreamCodec<FriendlyByteBuf, RedoPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.steps),
            buf -> new RedoPacket(buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

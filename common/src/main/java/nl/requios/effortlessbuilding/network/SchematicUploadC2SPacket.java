package nl.requios.effortlessbuilding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.shape.SchematicTransfer;

/**
 * C2S packet: one piece of a schematic the client has and the server may not. Sent before the build
 * that uses it, so the server can build schematics that only exist on the player's computer.
 *
 * @param hash  fingerprint of the whole upload ({@link SchematicTransfer#hash})
 * @param index this piece, from 0
 * @param total number of pieces
 */
public record SchematicUploadC2SPacket(String name, long hash, int index, int total, byte[] data)
        implements CustomPacketPayload {

    public static final Type<SchematicUploadC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "schematic_upload"));

    public static final StreamCodec<FriendlyByteBuf, SchematicUploadC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.name, 256);
                buf.writeLong(p.hash);
                buf.writeVarInt(p.index);
                buf.writeVarInt(p.total);
                buf.writeByteArray(p.data);
            },
            buf -> new SchematicUploadC2SPacket(buf.readUtf(256), buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(SchematicTransfer.CHUNK))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

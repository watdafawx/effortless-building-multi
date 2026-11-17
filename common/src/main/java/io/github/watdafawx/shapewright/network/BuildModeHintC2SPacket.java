package io.github.watdafawx.shapewright.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.github.watdafawx.shapewright.Constants;

/** Empty C2S packet sent after selecting a non-disabled build mode. */
public record BuildModeHintC2SPacket() implements CustomPacketPayload {
    public static final Type<BuildModeHintC2SPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "build_mode_hint"));
    public static final StreamCodec<FriendlyByteBuf, BuildModeHintC2SPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {}, buf -> new BuildModeHintC2SPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

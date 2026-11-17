package io.github.watdafawx.shapewright.platform.services;

import net.minecraft.server.level.ServerPlayer;
import io.github.watdafawx.shapewright.network.BreakBuildModePacket;
import io.github.watdafawx.shapewright.network.PlaceBuildModePacket;
import io.github.watdafawx.shapewright.network.UndoPacket;
import io.github.watdafawx.shapewright.network.RedoPacket;
import io.github.watdafawx.shapewright.network.UpdateModifiersC2SPacket;
import io.github.watdafawx.shapewright.network.SyncModifiersS2CPacket;
import io.github.watdafawx.shapewright.network.UpdateServerConfigC2SPacket;
import io.github.watdafawx.shapewright.network.SyncServerConfigS2CPacket;
import io.github.watdafawx.shapewright.network.QueryAE2CountC2SPacket;
import io.github.watdafawx.shapewright.network.SyncAE2CountS2CPacket;
import io.github.watdafawx.shapewright.network.BuildModeHintC2SPacket;

public interface INetworkHelper {

    void sendToServer(PlaceBuildModePacket packet);

    void sendToServer(BreakBuildModePacket packet);

    void sendToServer(UndoPacket packet);

    void sendToServer(RedoPacket packet);

    void sendToServer(UpdateModifiersC2SPacket packet);

    void sendToClient(ServerPlayer player, SyncModifiersS2CPacket packet);

    void sendToServer(UpdateServerConfigC2SPacket packet);

    void sendToClient(ServerPlayer player, SyncServerConfigS2CPacket packet);

    void sendToServer(QueryAE2CountC2SPacket packet);

    void sendToServer(BuildModeHintC2SPacket packet);

    void sendToClient(ServerPlayer player, SyncAE2CountS2CPacket packet);

    /** Sends any registered mod payload to the server (newer packets use this instead of an overload each). */
    void sendPayloadToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload);

    /** Sends any registered mod payload to a player. */
    void sendPayloadToClient(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload);

}

package io.github.watdafawx.shapewright.platform;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import io.github.watdafawx.shapewright.platform.services.INetworkHelper;

public class FabricNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(PlaceBuildModePacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToServer(BreakBuildModePacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToServer(UndoPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToServer(RedoPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToServer(UpdateModifiersC2SPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncModifiersS2CPacket packet) {
        ServerPlayNetworking.send(player, packet);
    }

    @Override
    public void sendToServer(UpdateServerConfigC2SPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncServerConfigS2CPacket packet) {
        ServerPlayNetworking.send(player, packet);
    }

    @Override
    public void sendToServer(QueryAE2CountC2SPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToServer(BuildModeHintC2SPacket packet) {
        ClientPlayNetworking.send(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncAE2CountS2CPacket packet) {
        ServerPlayNetworking.send(player, packet);
    }

    @Override
    public void sendPayloadToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void sendPayloadToClient(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

}

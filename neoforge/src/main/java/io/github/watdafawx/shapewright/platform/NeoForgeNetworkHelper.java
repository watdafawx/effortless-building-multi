package io.github.watdafawx.shapewright.platform;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
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

public class NeoForgeNetworkHelper implements INetworkHelper {

    @Override
    public void sendToServer(PlaceBuildModePacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToServer(BreakBuildModePacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToServer(UndoPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToServer(RedoPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToServer(UpdateModifiersC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncModifiersS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    @Override
    public void sendToServer(UpdateServerConfigC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncServerConfigS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    @Override
    public void sendToServer(QueryAE2CountC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToServer(BuildModeHintC2SPacket packet) {
        PacketDistributor.sendToServer(packet);
    }

    @Override
    public void sendToClient(ServerPlayer player, SyncAE2CountS2CPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    @Override
    public void sendPayloadToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public void sendPayloadToClient(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

}

package io.github.watdafawx.shapewright;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import io.github.watdafawx.shapewright.menu.ModMenus;
import net.minecraft.server.level.ServerPlayer;
import io.github.watdafawx.shapewright.modifier.ModifierServerStorage;
import io.github.watdafawx.shapewright.network.BreakBuildModePacket;
import io.github.watdafawx.shapewright.network.PacketHandler;
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
import io.github.watdafawx.shapewright.config.ServerConfig;
import io.github.watdafawx.shapewright.config.ServerConfigStorage;
import io.github.watdafawx.shapewright.config.WelcomeMessageStorage;
import io.github.watdafawx.shapewright.config.BuildModeHintStorage;
import io.github.watdafawx.shapewright.utilities.PlacedBlockTracker;
import io.github.watdafawx.shapewright.utilities.UndoManager;
import io.github.watdafawx.shapewright.item.RandomizerToolItem;

public class Shapewright implements ModInitializer {

    @Override
    public void onInitialize() {
        Constants.LOG.info("Hello Fabric world!");

        Item randomizerTool = Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "randomizer_tool"),
                new RandomizerToolItem(new Item.Properties().stacksTo(1)));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.accept(randomizerTool));
        Registry.register(BuiltInRegistries.MENU,
                ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "randomizer"), ModMenus.RANDOMIZER);

        // Register C2S packets
        PayloadTypeRegistry.playC2S().register(PlaceBuildModePacket.TYPE, PlaceBuildModePacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BreakBuildModePacket.TYPE, BreakBuildModePacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UndoPacket.TYPE, UndoPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RedoPacket.TYPE, RedoPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(io.github.watdafawx.shapewright.network.SchematicUploadC2SPacket.TYPE,
                io.github.watdafawx.shapewright.network.SchematicUploadC2SPacket.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(io.github.watdafawx.shapewright.network.SchematicUploadC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleSchematicUpload(payload, context.player())));
        PayloadTypeRegistry.playC2S().register(io.github.watdafawx.shapewright.network.UndoHistoryC2SPacket.TYPE,
                io.github.watdafawx.shapewright.network.UndoHistoryC2SPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(io.github.watdafawx.shapewright.network.UndoHistoryS2CPacket.TYPE,
                io.github.watdafawx.shapewright.network.UndoHistoryS2CPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateModifiersC2SPacket.TYPE, UpdateModifiersC2SPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UpdateServerConfigC2SPacket.TYPE, UpdateServerConfigC2SPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(QueryAE2CountC2SPacket.TYPE, QueryAE2CountC2SPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BuildModeHintC2SPacket.TYPE, BuildModeHintC2SPacket.STREAM_CODEC);

        // Register S2C packets
        PayloadTypeRegistry.playS2C().register(SyncModifiersS2CPacket.TYPE, SyncModifiersS2CPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncServerConfigS2CPacket.TYPE, SyncServerConfigS2CPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SyncAE2CountS2CPacket.TYPE, SyncAE2CountS2CPacket.STREAM_CODEC);

        // Register server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(PlaceBuildModePacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handlePlaceBuildMode(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(BreakBuildModePacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleBreakBuildMode(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(UndoPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleUndo(context.player(), payload.steps())));
        ServerPlayNetworking.registerGlobalReceiver(RedoPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleRedo(context.player(), payload.steps())));
        ServerPlayNetworking.registerGlobalReceiver(io.github.watdafawx.shapewright.network.UndoHistoryC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleUndoHistory(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(UpdateModifiersC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleUpdateModifiers(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(UpdateServerConfigC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleUpdateServerConfig(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(QueryAE2CountC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleQueryAE2Count(payload, context.player())));
        ServerPlayNetworking.registerGlobalReceiver(BuildModeHintC2SPacket.TYPE, (payload, context) ->
                context.server().execute(() -> PacketHandler.handleBuildModeHint(context.player())));

        // Load + send modifiers and config on player join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            ModifierServerStorage.loadPlayer(server, player.getUUID());
            PacketHandler.sendToClient(player, new SyncModifiersS2CPacket(
                    ModifierServerStorage.serializePlayer(player.getUUID())));
            PacketHandler.sendToClient(player, new SyncServerConfigS2CPacket(
                    ServerConfig.INSTANCE.toJson()));
            WelcomeMessageStorage.showIfNeeded(player);
        });

        // Save + clean up on player disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            ModifierServerStorage.savePlayer(server, player.getUUID());
            ModifierServerStorage.removePlayer(player.getUUID());
            io.github.watdafawx.shapewright.utilities.BuildQueue.finish(player.getUUID());
            io.github.watdafawx.shapewright.shape.SchematicUploads.clear(player.getUUID());
            UndoManager.clearPlayer(player.getUUID());
            PlacedBlockTracker.clearPlayer(player.getUUID());
        });

        // Gradual building: place queued blocks a batch per tick
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                io.github.watdafawx.shapewright.utilities.BuildQueue::tick);

        // Clear all cached data when the server stops
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ModifierServerStorage.clearAll();
            ServerConfigStorage.clear();
            WelcomeMessageStorage.clear();
            BuildModeHintStorage.clear();
        });

        // Load server config on server start
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerConfigStorage.load(server);
            WelcomeMessageStorage.load(server);
            BuildModeHintStorage.load(server);
        });

        CommonClass.init();
    }
}

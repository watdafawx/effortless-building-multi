package io.github.watdafawx.shapewright;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import io.github.watdafawx.shapewright.menu.ModMenus;
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

@Mod(Constants.MOD_ID)
public class Shapewright {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Constants.MOD_ID);
    private static final DeferredItem<Item> RANDOMIZER_TOOL = ITEMS.register(
            "randomizer_tool", () -> new RandomizerToolItem(new Item.Properties().stacksTo(1)));
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);

    static {
        MENUS.register("randomizer", () -> ModMenus.RANDOMIZER);
    }

    public Shapewright(IEventBus eventBus, ModContainer modContainer) {

        ITEMS.register(eventBus);
        MENUS.register(eventBus);
        eventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(RANDOMIZER_TOOL);
        });

        if (FMLEnvironment.dist.isClient()) {
            NeoForgeConfigScreenRegistrar.register(modContainer);
        }

        eventBus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar(Constants.MOD_ID);
            registrar.playToServer(
                    PlaceBuildModePacket.TYPE,
                    PlaceBuildModePacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handlePlaceBuildMode(payload, (ServerPlayer) context.player())));
            registrar.playToServer(
                    BreakBuildModePacket.TYPE,
                    BreakBuildModePacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleBreakBuildMode(payload, (ServerPlayer) context.player())));
            registrar.playToServer(
                    UndoPacket.TYPE,
                    UndoPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleUndo((ServerPlayer) context.player(), payload.steps())));
            registrar.playToServer(
                    RedoPacket.TYPE,
                    RedoPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleRedo((ServerPlayer) context.player(), payload.steps())));
            registrar.playToServer(
                    io.github.watdafawx.shapewright.network.SchematicUploadC2SPacket.TYPE,
                    io.github.watdafawx.shapewright.network.SchematicUploadC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleSchematicUpload(payload, (ServerPlayer) context.player())));
            registrar.playToServer(
                    io.github.watdafawx.shapewright.network.UndoHistoryC2SPacket.TYPE,
                    io.github.watdafawx.shapewright.network.UndoHistoryC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleUndoHistory((ServerPlayer) context.player())));
            registrar.playToClient(
                    io.github.watdafawx.shapewright.network.UndoHistoryS2CPacket.TYPE,
                    io.github.watdafawx.shapewright.network.UndoHistoryS2CPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleUndoHistoryList(payload)));
            registrar.playToServer(
                    UpdateModifiersC2SPacket.TYPE,
                    UpdateModifiersC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleUpdateModifiers(payload, (ServerPlayer) context.player())));
            registrar.playToClient(
                    SyncModifiersS2CPacket.TYPE,
                    SyncModifiersS2CPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleSyncModifiers(payload)));
            registrar.playToServer(
                    UpdateServerConfigC2SPacket.TYPE,
                    UpdateServerConfigC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleUpdateServerConfig(payload, (ServerPlayer) context.player())));
            registrar.playToClient(
                    SyncServerConfigS2CPacket.TYPE,
                    SyncServerConfigS2CPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleSyncServerConfig(payload)));
            // AE2 count query — client requests item count from ME network
            registrar.playToServer(
                    QueryAE2CountC2SPacket.TYPE,
                    QueryAE2CountC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleQueryAE2Count(payload, (ServerPlayer) context.player())));
            registrar.playToServer(
                    BuildModeHintC2SPacket.TYPE,
                    BuildModeHintC2SPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleBuildModeHint((ServerPlayer) context.player())));
            registrar.playToClient(
                    SyncAE2CountS2CPacket.TYPE,
                    SyncAE2CountS2CPacket.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() ->
                            PacketHandler.handleSyncAE2Count(payload)));
        });

        // Load + send modifiers and config on player join
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                ModifierServerStorage.loadPlayer(serverPlayer.server, serverPlayer.getUUID());
                PacketHandler.sendToClient(serverPlayer, new SyncModifiersS2CPacket(
                        ModifierServerStorage.serializePlayer(serverPlayer.getUUID())));
                PacketHandler.sendToClient(serverPlayer, new SyncServerConfigS2CPacket(
                        ServerConfig.INSTANCE.toJson()));
                WelcomeMessageStorage.showIfNeeded(serverPlayer);
            }
        });

        // Save + clean up on player disconnect
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                ModifierServerStorage.savePlayer(serverPlayer.server, serverPlayer.getUUID());
                ModifierServerStorage.removePlayer(serverPlayer.getUUID());
            }
            io.github.watdafawx.shapewright.utilities.BuildQueue.finish(event.getEntity().getUUID());
            io.github.watdafawx.shapewright.shape.SchematicUploads.clear(event.getEntity().getUUID());
            UndoManager.clearPlayer(event.getEntity().getUUID());
            PlacedBlockTracker.clearPlayer(event.getEntity().getUUID());
        });

        // Gradual building: place queued blocks a batch per tick
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) ->
                io.github.watdafawx.shapewright.utilities.BuildQueue.tick(event.getServer()));

        // Clear all cached data when the server stops (singleplayer world changes)
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            ModifierServerStorage.clearAll();
            ServerConfigStorage.clear();
            WelcomeMessageStorage.clear();
            BuildModeHintStorage.clear();
        });

        // Load server config on server start
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            ServerConfigStorage.load(event.getServer());
            WelcomeMessageStorage.load(event.getServer());
            BuildModeHintStorage.load(event.getServer());
        });

        CommonClass.init();
    }
}

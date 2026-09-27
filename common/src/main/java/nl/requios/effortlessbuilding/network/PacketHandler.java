package nl.requios.effortlessbuilding.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import nl.requios.effortlessbuilding.mixin.BucketItemAccessor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.buildpipeline.BuildPipeline;

import nl.requios.effortlessbuilding.buildmode.BuildSettings;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.config.ServerConfigStorage;
import nl.requios.effortlessbuilding.config.BuildModeHintStorage;
import nl.requios.effortlessbuilding.modifier.IModifier;
import nl.requios.effortlessbuilding.modifier.ModifierSerializer;
import nl.requios.effortlessbuilding.modifier.ModifierServerStorage;
import nl.requios.effortlessbuilding.modifier.ModifierSystem;
import nl.requios.effortlessbuilding.platform.Services;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import nl.requios.effortlessbuilding.utilities.InventoryHelper;
import nl.requios.effortlessbuilding.compat.ae2.AE2Integration;
import nl.requios.effortlessbuilding.compat.create.CreateGlue;
import nl.requios.effortlessbuilding.utilities.BuildQueue;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.shape.ShapeType;
import nl.requios.effortlessbuilding.shape.TerrainBlender;
import nl.requios.effortlessbuilding.utilities.PlacedBlockTracker;
import nl.requios.effortlessbuilding.utilities.UndoManager;
import nl.requios.effortlessbuilding.item.RandomizerToolItem;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

public class PacketHandler {

    public static void sendToServer(PlaceBuildModePacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToServer(BreakBuildModePacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToServer(UndoPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToServer(RedoPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToServer(UpdateModifiersC2SPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToClient(ServerPlayer player, SyncModifiersS2CPacket packet) {
        Services.NETWORK.sendToClient(player, packet);
    }

    public static void sendToServer(UpdateServerConfigC2SPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToClient(ServerPlayer player, SyncServerConfigS2CPacket packet) {
        Services.NETWORK.sendToClient(player, packet);
    }

    public static void sendToServer(QueryAE2CountC2SPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToServer(BuildModeHintC2SPacket packet) {
        Services.NETWORK.sendToServer(packet);
    }

    public static void sendToClient(ServerPlayer player, SyncAE2CountS2CPacket packet) {
        Services.NETWORK.sendToClient(player, packet);
    }

    /**
     * Called on the server when a {@link QueryAE2CountC2SPacket} is received.
     * Queries the AE2 network and sends the count back to the client.
     */
    public static void handleQueryAE2Count(QueryAE2CountC2SPacket packet, ServerPlayer player) {
        int count = AE2Integration.countOnNetwork(player, packet.item());
        sendToClient(player, new SyncAE2CountS2CPacket(packet.item(), count));
    }

    /**
     * Called on the client when a {@link SyncAE2CountS2CPacket} is received.
     * Stores the count for the HUD preview.
     */
    public static void handleSyncAE2Count(SyncAE2CountS2CPacket packet) {
        AE2Integration.setCachedCount(packet.item(), packet.count());
    }

    /** Called after the client selects a non-disabled build mode. */
    public static void handleBuildModeHint(ServerPlayer player) {
        BuildModeHintStorage.showIfNeeded(player);
    }

    /**
     * Called on the server when a {@link PlaceBuildModePacket} is received.
     */
    public static void handlePlaceBuildMode(PlaceBuildModePacket packet, ServerPlayer player) {
        ServerLevel level = player.serverLevel();

        // Run the full server pipeline: BuildMode → Modifiers → Constraints
        BlockSet blockSet = BuildPipeline.SERVER.runServerPipeline(
                packet.buildMode(), packet.firstPos(), packet.secondPos(), packet.thirdPos(),
                player, BuildPipeline.BuildState.PLACING,
                packet.fill(), packet.cubeFill(), packet.raisedEdge(), packet.circleStart(), 
                packet.shape(), packet.palette(), packet.protectTileEntities());

        if (blockSet == null) {
            Constants.LOG.warn("[EffortlessBuilding] Received PlaceBuildModePacket but mode {} returned no blocks", packet.buildMode());
            return;
        }

        // Sort by distance to player so closest blocks are placed first when inventory is limited
        blockSet.sortByDistance();

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        boolean creative = player.isCreative();

        BuildSettings.ReplaceMode replaceMode = packet.replaceMode();

        Map<BlockPos, UndoManager.BlockChange> undoChanges = new LinkedHashMap<>();
        // Per item, how many placed blocks were paid from the AE2 network (refunded there on undo)
        Map<Item, Integer> networkDebit = new HashMap<>();
        // Tool interactions and fluids are undone without moving block items
        boolean paidWithItems = true;

        // Shape the ground around a schematic first, so the build stands on natural-looking terrain
        ShapeParams shape = packet.shape();
        if (shape != null && shape.getInt(TerrainBlender.ENABLED) == 1) {
            TerrainBlender.blend(player, level, blockSet.validPositions(), packet.firstPos().getY() - 1,
                    shape.getInt(TerrainBlender.MARGIN), undoChanges);
        }

        int placed = 0;
        // Per-block items: from the Randomizer tool, the palette, or a shape's center block.
        // Blocks without their own item use the held block.
        boolean perBlockItems = held.getItem() instanceof RandomizerToolItem
                || blockSet.values().stream().anyMatch(e -> e.item instanceof BlockItem);
        if (perBlockItems) {
            Item fallback = held.getItem() instanceof BlockItem && BuildPipeline.isBuildTriggerItem(held) ? held.getItem() : null;
            Map<Item, Integer> required = new LinkedHashMap<>();
            for (var mapEntry : blockSet.validEntries()) {
                if (!BuildSettings.canPlaceAt(level, mapEntry.getKey(), replaceMode, offHand)) continue;
                Item item = mapEntry.getValue().item != null ? mapEntry.getValue().item : fallback;
                if (item instanceof BlockItem) required.merge(item, 1, Integer::sum);
            }

            Map<Item, Integer> available = new HashMap<>();
            Map<Item, Integer> inventoryCounts = new HashMap<>();
            Map<Item, Integer> networkExtracted = new HashMap<>();
            for (var requirement : required.entrySet()) {
                if (creative) {
                    available.put(requirement.getKey(), Integer.MAX_VALUE);
                } else {
                    int inventoryCount = InventoryHelper.findTotalItemsInInventory(player, requirement.getKey());
                    int networkNeeded = Math.max(0, requirement.getValue() - inventoryCount);
                    int fromNetwork = InventoryHelper.supplementFromNetwork(
                            player, requirement.getKey(), networkNeeded);
                    inventoryCounts.put(requirement.getKey(), inventoryCount);
                    networkExtracted.put(requirement.getKey(), fromNetwork);
                    available.put(requirement.getKey(), inventoryCount + fromNetwork);
                }
            }

            Map<Item, Integer> used = new HashMap<>();
            double yFrac = packet.hitLocation().y - Math.floor(packet.hitLocation().y);
            for (var mapEntry : blockSet.validEntries()) {
                BlockPos pos = mapEntry.getKey();
                BlockEntry entry = mapEntry.getValue();
                Item item = entry.item != null ? entry.item : fallback;
                if (!(item instanceof BlockItem blockItem)) continue;
                if (!creative && used.getOrDefault(item, 0) >= available.getOrDefault(item, 0)) continue;
                if (!BuildSettings.canPlaceAt(level, pos, replaceMode, offHand)) continue;

                BlockState oldState = level.getBlockState(pos);
                if (!creative && !oldState.canBeReplaced()) {
                    ItemStack toolForDrops = ServerConfig.INSTANCE.survivalRequireTools
                            ? InventoryHelper.findCorrectTool(player, oldState)
                            : player.getMainHandItem();
                    var drops = Block.getDrops(oldState, level, pos, level.getBlockEntity(pos), player, toolForDrops);
                    for (ItemStack drop : drops) {
                        InventoryHelper.giveOrDropItems(player, drop);
                    }
                    if (ServerConfig.INSTANCE.survivalUseDurability) {
                        InventoryHelper.damageCorrectTool(player, oldState);
                    }
                }

                ItemStack placementStack = new ItemStack(item);
                Vec3 localHit = new Vec3(packet.hitLocation().x, pos.getY() + yFrac, packet.hitLocation().z);
                BlockHitResult serverHit = new BlockHitResult(localHit, packet.hitFace(), pos, false);
                BlockPlaceContext ctx = new OpenBlockPlaceContext(
                        level, player, InteractionHand.MAIN_HAND, placementStack, serverHit);
                BlockState state = entry.exactState && entry.blockState != null
                        ? entry.blockState // e.g. a schematic's saved block, facing included
                        : blockItem.getBlock().getStateForPlacement(ctx);
                if (state == null) state = blockItem.getBlock().defaultBlockState();
                state = entry.applyTransforms(state);
                BlockState placedState = state;
                BuildQueue.submit(player, () -> level.setBlock(pos, placedState, 3));
                recordChange(undoChanges, pos, new UndoManager.BlockChange(oldState, state));
                used.merge(item, 1, Integer::sum);
                placed++;
            }

            if (!creative) {
                // Every requirement is settled, including ones that ended up unused, so
                // unneeded network items go back to the network
                for (Item item : required.keySet()) {
                    int fromNetwork = InventoryHelper.settleBuild(player, item, inventoryCounts.get(item),
                            networkExtracted.get(item), used.getOrDefault(item, 0));
                    if (fromNetwork > 0) networkDebit.put(item, fromNetwork);
                }
            }
        } else if (held.getItem() instanceof BlockItem blockItem) {
            Item heldItem = held.getItem();
            // Components belong to this exact stack. Do not let a filled or otherwise
            // customised block borrow plain copies from the inventory/AE2 network,
            // because that would duplicate its data onto those copies.
            boolean hasStackData = !held.getComponentsPatch().isEmpty();

            // Determine how many blocks we can afford BEFORE placing any
            int available;
            int inventoryCount = 0;
            int ae2Extracted = 0;
            if (creative) {
                available = Integer.MAX_VALUE;
            } else if (hasStackData) {
                available = held.getCount();
            } else {
                inventoryCount = InventoryHelper.findTotalItemsInInventory(player, heldItem);
                int validCount = blockSet.validEntries().size();

                // Pre-extract from AE2 what exceeds inventory (digital — no ItemStack created).
                // settleBuild below returns whatever placement did not use.
                int neededFromNetwork = Math.max(0, validCount - inventoryCount);
                if (neededFromNetwork > 0) {
                    ae2Extracted = InventoryHelper.supplementFromNetwork(player, heldItem, neededFromNetwork);
                }
                available = inventoryCount + ae2Extracted;
            }

            double yFrac = packet.hitLocation().y - Math.floor(packet.hitLocation().y);
            for (var mapEntry : blockSet.validEntries()) {
                BlockPos pos = mapEntry.getKey();
                if (!creative && placed >= available) break;

                if (BuildSettings.canPlaceAt(level, pos, replaceMode, offHand)) {
                    BlockState oldState = level.getBlockState(pos);

                    // Survival: give drops and damage tools for displaced non-replaceable blocks
                    if (!creative && !oldState.canBeReplaced()) {
                        ItemStack toolForDrops = ServerConfig.INSTANCE.survivalRequireTools
                                ? InventoryHelper.findCorrectTool(player, oldState)
                                : player.getMainHandItem();
                        var drops = Block.getDrops(oldState, level, pos, level.getBlockEntity(pos),
                                player, toolForDrops);
                        for (ItemStack drop : drops) {
                            InventoryHelper.giveOrDropItems(player, drop);
                        }
                        if (ServerConfig.INSTANCE.survivalUseDurability) {
                            InventoryHelper.damageCorrectTool(player, oldState);
                        }
                    }

                    Vec3 localHit = new Vec3(packet.hitLocation().x, pos.getY() + yFrac, packet.hitLocation().z);
                    BlockHitResult serverHit = new BlockHitResult(localHit, packet.hitFace(), pos, false);
                    BlockPlaceContext ctx = new OpenBlockPlaceContext(level, player, InteractionHand.MAIN_HAND, held, serverHit);
                    BlockState state = blockItem.getBlock().getStateForPlacement(ctx);
                    if (state == null) state = blockItem.getBlock().defaultBlockState();
                    BlockEntry entry = blockSet.get(pos);
                    if (entry != null) {
                        state = entry.applyTransforms(state);
                    }
                    BlockState placedState = state;
                    ItemStack placedFrom = held.copyWithCount(1); // the held stack shrinks before a queued step runs
                    BuildQueue.submit(player, () -> {
                        level.setBlock(pos, placedState, 3);
                        transferBlockItemData(level, player, pos, placedFrom);
                    });
                    recordChange(undoChanges, pos, new UndoManager.BlockChange(oldState, state));
                    placed++;
                }
            }

            if (!creative) {
                if (hasStackData) {
                    held.shrink(placed);
                } else {
                    // Pay from inventory first; unused network items go back to the network
                    int fromNetwork = InventoryHelper.settleBuild(player, heldItem, inventoryCount, ae2Extracted, placed);
                    if (fromNetwork > 0) networkDebit.put(heldItem, fromNetwork);
                    // Restock held stack from AE2 network (e.g. top-up from 4 → 64, or refill an emptied hand)
                    if (placed > 0) InventoryHelper.restockFromNetwork(player, heldItem);
                }
            }
        } else if (held.getItem() instanceof BucketItem bucketItem) {
            paidWithItems = false;
            var fluid = ((BucketItemAccessor) bucketItem).effortlessbuilding$getFluid();
            if (!fluid.isSame(Fluids.EMPTY)) {
                BlockState fluidState = fluid.defaultFluidState().createLegacyBlock();
                int maxPlace = creative ? Integer.MAX_VALUE : 1;
                for (var mapEntry : blockSet.validEntries()) {
                    BlockPos pos = mapEntry.getKey();
                    if (placed >= maxPlace) break;
                    if (BuildSettings.canPlaceAt(level, pos, replaceMode, offHand)) {
                        BlockState oldState = level.getBlockState(pos);

                        // Survival: give drops and damage tools for displaced non-replaceable blocks
                        if (!creative && !oldState.canBeReplaced()) {
                            ItemStack toolForDrops = ServerConfig.INSTANCE.survivalRequireTools
                                    ? InventoryHelper.findCorrectTool(player, oldState)
                                    : player.getMainHandItem();
                            var drops = Block.getDrops(oldState, level, pos, level.getBlockEntity(pos),
                                    player, toolForDrops);
                            for (ItemStack drop : drops) {
                                InventoryHelper.giveOrDropItems(player, drop);
                            }
                            if (ServerConfig.INSTANCE.survivalUseDurability) {
                                InventoryHelper.damageCorrectTool(player, oldState);
                            }
                        }

                        BuildQueue.submit(player, () -> level.setBlock(pos, fluidState, 3));
                        recordChange(undoChanges, pos, new UndoManager.BlockChange(oldState, fluidState));
                        placed++;
                    }
                }
                if (!creative && placed > 0) {
                    player.setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(net.minecraft.world.item.Items.BUCKET));
                }
            }
        } else if (held.getItem() instanceof DiggerItem) {
            paidWithItems = false;
            // Tool interactions: axe strips logs, shovel makes paths, hoe tills dirt, etc.
            // Calls useOn for each position — works for vanilla and modded tools.
            net.minecraft.world.level.Level worldLevel = level;
            for (var mapEntry : blockSet.validEntries()) {
                BlockPos pos = mapEntry.getKey();
                BlockState oldState = level.getBlockState(pos);

                Vec3 localHit = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                BlockHitResult serverHit = new BlockHitResult(localHit, packet.hitFace(), pos, false);
                UseOnContext useCtx = new OpenUseOnContext(worldLevel, player, InteractionHand.MAIN_HAND, held, serverHit);
                var result = held.getItem().useOn(useCtx);
                if (result.consumesAction()) {
                    BlockState newState = level.getBlockState(pos);
                    if (!oldState.equals(newState)) {
                        recordChange(undoChanges, pos, new UndoManager.BlockChange(oldState, newState));
                        placed++;
                    }
                }
                // Stop if tool breaks
                if (held.isEmpty()) break;
            }
        } else {
            return;
        }

        if (!undoChanges.isEmpty()) {
            // Glue exactly the blocks that were placed, when the shape asks for it
            if (paidWithItems && packet.shape() != null && packet.shape().getInt(ShapeType.SUPER_GLUE) == 1) {
                // Queued last, so it runs once every block of a gradual build is in place
                Set<BlockPos> toGlue = new HashSet<>(undoChanges.keySet());
                // The bearing stays outside the glue, or it would turn with the shape
                toGlue.removeIf(pos -> CreateGlue.isBearing(undoChanges.get(pos).newState()));
                BuildQueue.submit(player, () -> CreateGlue.glue(player, level, toGlue));
            }
            if (paidWithItems) {
                UndoManager.recordOperation(player, level.dimension(), undoChanges, networkDebit);
            } else {
                UndoManager.recordFreeOperation(player, level.dimension(), undoChanges);
            }
            PlacedBlockTracker.trackAll(player.getUUID(), level.dimension(), undoChanges.keySet());
        }
        networkDebit.keySet().forEach(item -> pushAE2Count(player, item));
    }

    /** Sends the player's current network count for {@code item}, so the preview does not show a stale number. */
    private static void pushAE2Count(ServerPlayer player, Item item) {
        if (item == net.minecraft.world.item.Items.AIR || !AE2Integration.hasLinkedTerminal(player)) return;
        sendToClient(player, new SyncAE2CountS2CPacket(item, AE2Integration.countOnNetwork(player, item)));
    }

    /** Records a change, keeping an earlier "before" for the same block (terrain blending ran first). */
    private static void recordChange(Map<BlockPos, UndoManager.BlockChange> changes, BlockPos pos, UndoManager.BlockChange change) {
        changes.merge(pos.immutable(), change, (first, next) -> new UndoManager.BlockChange(first.oldState(), next.newState()));
    }

    /**
     * Mirrors the block-entity part of {@link BlockItem#place(BlockPlaceContext)}.
     *
     * <p>The build pipeline intentionally sets the block directly so a modifier can
     * control the exact target position and transformed state. Direct placement skips
     * vanilla's item-to-block-entity transfer, however, which would otherwise erase
     * contents such as a filled shulker box or data stored by another mod.</p>
     */
    private static void transferBlockItemData(ServerLevel level, ServerPlayer player,
                                              BlockPos pos, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;

        BlockState placedState = level.getBlockState(pos);
        if (!placedState.is(blockItem.getBlock())) return;

        BlockItem.updateCustomBlockEntityTag(level, player, pos, stack);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntity.applyComponentsFromItemStack(stack);
            blockEntity.setChanged();
        }
        placedState.getBlock().setPlacedBy(level, pos, placedState, player, stack);
    }

    /**
     * Called on the server when a {@link BreakBuildModePacket} is received.
     */
    public static void handleBreakBuildMode(BreakBuildModePacket packet, ServerPlayer player) {
        boolean creative = player.isCreative();

        // Enforce survivalAllowBreaking (early exit before running pipeline)
        if (!creative && !ServerConfig.INSTANCE.survivalAllowBreaking) {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.breaking_disabled"), true);
            return;
        }

        ServerLevel level = player.serverLevel();

        // Run the full server pipeline: BuildMode → Modifiers → Constraints
        BlockSet blockSet = BuildPipeline.SERVER.runServerPipeline(
                packet.buildMode(), packet.firstPos(), packet.secondPos(), packet.thirdPos(),
                player, BuildPipeline.BuildState.BREAKING,
                packet.fill(), packet.cubeFill(), packet.raisedEdge(), packet.circleStart(),
                packet.shape(), null, packet.protectTileEntities());

        if (blockSet == null) {
            Constants.LOG.warn("[EffortlessBuilding] Received BreakBuildModePacket but mode {} returned no blocks", packet.buildMode());
            return;
        }

        Map<BlockPos, UndoManager.BlockChange> undoChanges = new LinkedHashMap<>();
        BlockState airState = Blocks.AIR.defaultBlockState();

        int broken = 0;
        for (var mapEntry : blockSet.validEntries()) {
            BlockPos pos = mapEntry.getKey();
            BlockState oldState = level.getBlockState(pos);
            if (oldState.isAir()) continue;

            if (!creative) {

                // Use the correct tool from inventory for drop calculation (enchantments matter)
                ItemStack toolForDrops = ServerConfig.INSTANCE.survivalRequireTools
                        ? InventoryHelper.findCorrectTool(player, oldState)
                        : player.getMainHandItem();
                var drops = Block.getDrops(oldState, level, pos, level.getBlockEntity(pos),
                        player, toolForDrops);
                for (ItemStack drop : drops) {
                    InventoryHelper.giveOrDropItems(player, drop);
                }
                // Use tool durability if enabled
                if (ServerConfig.INSTANCE.survivalUseDurability) {
                    InventoryHelper.damageCorrectTool(player, oldState);
                }
                BuildQueue.submit(player, () -> level.setBlock(pos, airState, 3));
            } else {
                BuildQueue.submit(player, () -> level.destroyBlock(pos, false, player));
            }
            recordChange(undoChanges, pos, new UndoManager.BlockChange(oldState, airState));
            broken++;
        }

        if (!undoChanges.isEmpty()) {
            UndoManager.recordOperation(player, level.dimension(), undoChanges, Map.of());
        }
    }

    /**
     * Called on the server when an {@link UndoPacket} is received.
     */
    public static void handleUndo(ServerPlayer player) {
        BuildQueue.finish(player.getUUID()); // a gradual build finishes before it can be undone
        int count = UndoManager.undo(player);
        pushAE2Count(player, player.getMainHandItem().getItem());
        if (count >= 0) {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.undo", count), true);
        } else {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.nothing_to_undo"), true);
        }
    }

    /**
     * Called on the server when a {@link RedoPacket} is received.
     */
    public static void handleRedo(ServerPlayer player) {
        BuildQueue.finish(player.getUUID());
        int count = UndoManager.redo(player);
        pushAE2Count(player, player.getMainHandItem().getItem());
        if (count >= 0) {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.redo", count), true);
        } else {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.nothing_to_redo"), true);
        }
    }

    /**
     * Called on the server when an {@link UpdateModifiersC2SPacket} is received.
     */
    public static void handleUpdateModifiers(UpdateModifiersC2SPacket packet, ServerPlayer player) {
        List<IModifier> modifiers = ModifierSerializer.deserialize(packet.json());
        ModifierServerStorage.setModifiers(player.getUUID(), modifiers);
        ModifierServerStorage.savePlayer(player.server, player.getUUID());
        // Echo back to client as confirmation
        sendToClient(player, new SyncModifiersS2CPacket(
                ModifierServerStorage.serializePlayer(player.getUUID())));
    }

    /**
     * Called on the client when a {@link SyncModifiersS2CPacket} is received.
     * Replaces the client-side modifier list with the server's authoritative copy.
     */
    public static void handleSyncModifiers(SyncModifiersS2CPacket packet) {
        List<IModifier> modifiers = ModifierSerializer.deserialize(packet.json());
        ModifierSystem.CLIENT.clearModifiers();
        for (IModifier m : modifiers) {
            ModifierSystem.CLIENT.addModifier(m);
        }
    }

    /**
     * Called on the server when an {@link UpdateServerConfigC2SPacket} is received.
     * Only operators (permission level 2+) may update the config.
     */
    public static void handleUpdateServerConfig(UpdateServerConfigC2SPacket packet, ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            player.displayClientMessage(
                    Component.translatable("effortlessbuilding.message.not_operator"), false);
            return;
        }
        ServerConfig incoming = ServerConfig.fromJson(packet.json());
        ServerConfig.INSTANCE.copyFrom(incoming);
        ServerConfigStorage.save(player.server);

        // Broadcast updated config to all connected players
        String json = ServerConfig.INSTANCE.toJson();
        for (ServerPlayer p : player.server.getPlayerList().getPlayers()) {
            sendToClient(p, new SyncServerConfigS2CPacket(json));
        }
    }

    /**
     * Called on the client when a {@link SyncServerConfigS2CPacket} is received.
     */
    public static void handleSyncServerConfig(SyncServerConfigS2CPacket packet) {
        ServerConfig incoming = ServerConfig.fromJson(packet.json());
        ServerConfig.INSTANCE.copyFrom(incoming);
    }


    /** Exposes the protected {@link BlockPlaceContext} constructor for server-side use. */
    private static final class OpenBlockPlaceContext extends BlockPlaceContext {
        OpenBlockPlaceContext(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player,
                              InteractionHand hand, ItemStack stack, BlockHitResult hit) {
            super(level, player, hand, stack, hit);
        }
    }

    /** Exposes the protected {@link UseOnContext} constructor for server-side use. */
    private static final class OpenUseOnContext extends UseOnContext {
        OpenUseOnContext(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player,
                         InteractionHand hand, ItemStack stack, BlockHitResult hit) {
            super(level, player, hand, stack, hit);
        }
    }
}

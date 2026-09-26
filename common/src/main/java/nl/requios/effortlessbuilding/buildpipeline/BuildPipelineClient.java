package nl.requios.effortlessbuilding.buildpipeline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.buildmode.BuildModeEnum;
import nl.requios.effortlessbuilding.buildmode.BuildModes;
import nl.requios.effortlessbuilding.buildmode.BuildSettings;
import nl.requios.effortlessbuilding.buildmode.ModeOptions;
import nl.requios.effortlessbuilding.buildmode.ThreeClicksBuildMode;
import nl.requios.effortlessbuilding.buildmode.buildmodes.ShapeMode;
import nl.requios.effortlessbuilding.shape.ShapeClientState;
import nl.requios.effortlessbuilding.shape.ShapeParams;
import nl.requios.effortlessbuilding.config.ClientConfig;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.modifier.ModifierSystem;
import nl.requios.effortlessbuilding.network.BreakBuildModePacket;
import nl.requios.effortlessbuilding.network.PacketHandler;
import nl.requios.effortlessbuilding.network.PlaceBuildModePacket;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import nl.requios.effortlessbuilding.utilities.BlockStatus;
import nl.requios.effortlessbuilding.utilities.BreakDisplayTracker;
import nl.requios.effortlessbuilding.utilities.ItemUsageTracker;
import nl.requios.effortlessbuilding.utilities.PlacedBlockTracker;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluids;
import nl.requios.effortlessbuilding.mixin.BucketItemAccessor;
import nl.requios.effortlessbuilding.item.RandomizerToolItem;

/**
 * Client-side controller for the unified build pipeline.
 *
 * <p>Manages the multi-click sequence state, preview computation, and packet
 * dispatch for all build modes. When mode is DISABLED but modifiers are active,
 * the pipeline still runs (producing mirrored/arrayed copies of the single block).
 *
 * <p>Every method here touches {@link Minecraft} or other client-only types, keeping
 * them out of the server-side {@link BuildPipeline} to prevent Fabric's dedicated-server
 * classloader from pulling in {@code net.minecraft.client.*}.
 */
public class BuildPipelineClient {

    /**
     * Client-side pipeline with all stages pre-registered.
     * Pipeline order: ModifierSystem.CLIENT → ConstraintSystem
     */
    public static final BuildPipeline CLIENT = createClientPipeline();

    private static BuildPipeline createClientPipeline() {
        BuildPipeline pipeline = new BuildPipeline();
        pipeline.addSystem(ModifierSystem.CLIENT);
        pipeline.addSystem(RandomizerSystem.INSTANCE);
        pipeline.addSystem(ConstraintSystem.INSTANCE);
        return pipeline;
    }

    /** Client-side item usage tracker — updated each frame during preview rendering. */
    public static final ItemUsageTracker ITEM_USAGE = new ItemUsageTracker();

    /** Client-side break display tracker — updated each frame during breaking preview. */
    public static final BreakDisplayTracker BREAK_DISPLAY = new BreakDisplayTracker();

    /** Preview lock (anchor) state: when true, the ghost preview stays frozen in the world. */
    public static boolean previewLocked = false;

    /**
     * Everything needed to place the anchored preview later, captured when locking, so the server
     * regenerates exactly the frozen shape no matter where the player walks or which options change.
     */
    private record Anchor(BlockSet blocks, ResourceKey<Level> dimension, BuildModeEnum mode,
                          BlockPos firstPos, BlockPos secondPos, @Nullable BlockPos thirdPos,
                          Direction hitFace, Vec3 hitLocation,
                          ModeOptions.ActionEnum fill, ModeOptions.ActionEnum cubeFill,
                          ModeOptions.ActionEnum raisedEdge, ModeOptions.ActionEnum circleStart,
                          @Nullable ShapeParams shape) {}

    @Nullable private static Anchor anchor = null;

    /** The most recently computed preview (used to initialize the lock). */
    @Nullable private static BlockSet lastPreviewBlocks = null;

    /** Toggles the preview lock on/off using the last computed preview. */
    public static void togglePreviewLock() {
        if (previewLocked) {
            clearAnchor();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockSet preview = lastPreviewBlocks;
        if (mc.level == null || preview == null || preview.isEmpty()
                || preview.firstPos == null || preview.lastPos == null) return;

        BuildModeEnum mode = BuildModes.CLIENT.getBuildMode();
        // Same position semantics as a finished click sequence (see handleClick). A three-click
        // mode locked before its second click is sent as a single layer (third = second).
        BlockPos intermediate = buildState != null ? mode.instance.getIntermediatePos() : null;
        BlockPos secondPos = intermediate != null ? intermediate : preview.lastPos;
        BlockPos thirdPos = intermediate != null ? preview.lastPos
                : mode.instance instanceof ThreeClicksBuildMode ? preview.lastPos : null;

        BlockHitResult hit = firstClickHit != null ? firstClickHit
                : mc.hitResult instanceof BlockHitResult b ? b : null;
        ShapeParams shape = shapeFor(mode);
        // Before the first click the preview shows the shape at the screen's size
        if (shape != null && buildState == null) shape = shape.withSizing(ShapeParams.Sizing.SCREEN);

        anchor = new Anchor(preview, mc.level.dimension(), mode, preview.firstPos, secondPos, thirdPos,
                hit != null ? hit.getDirection() : Direction.UP,
                hit != null ? hit.getLocation() : Vec3.atCenterOf(preview.firstPos),
                ModeOptions.getFill(), ModeOptions.getCubeFill(),
                ModeOptions.getRaisedEdge(), ModeOptions.getCircleStart(), shape);
        previewLocked = true;

        // The click sequence is captured; start over so the next right-click places the anchor
        mode.instance.initialize();
        buildState = null;
        firstClickHit = null;
    }

    /** Toggles the lock and tells the player the new state on the action bar. */
    public static void togglePreviewLockWithMessage() {
        togglePreviewLock();
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(previewLocked
                    ? "effortlessbuilding.message.preview_locked"
                    : "effortlessbuilding.message.preview_unlocked"), true);
        }
    }

    /** Drops the anchored preview, e.g. when the build mode changes. */
    public static void clearAnchor() {
        previewLocked = false;
        anchor = null;
    }

    // -------------------------------------------------------------------------
    // Multi-click sequence state
    // -------------------------------------------------------------------------

    @Nullable private static BuildPipeline.BuildState buildState = null;
    @Nullable private static BlockHitResult firstClickHit = null;

    static {
        // Run while the old mode is still active, so its multi-click state is reset.
        BuildModes.CLIENT.setBeforeDisable(BuildPipelineClient::cancelCurrentSequence);
    }

    public static @Nullable BuildPipeline.BuildState getBuildState() { return buildState; }
    public static @Nullable BlockHitResult getFirstClickHit() { return firstClickHit; }

    // -------------------------------------------------------------------------
    // Interception decision
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the mod should intercept vanilla click handling.
     */
    public static boolean shouldInterceptPlacing() {
        Minecraft mc = Minecraft.getInstance();
        return BuildModes.CLIENT.getBuildMode() != BuildModeEnum.DISABLED
                || mc.player != null && mc.player.getMainHandItem().getItem() instanceof RandomizerToolItem;
    }
    
    /**
     * Returns {@code true} if the mod should intercept vanilla break handling.
     */
    public static boolean shouldInterceptBreaking() {
        if (BuildModes.CLIENT.getBuildMode() == BuildModeEnum.DISABLED) return false;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return false;
        if (!player.getAbilities().instabuild && !ServerConfig.INSTANCE.survivalAllowBreaking) {
            return false;
        }
        // A left-click during an existing sequence cancels that sequence, rather than mining.
        if (buildState != null || player.getAbilities().instabuild) return true;

        // Do not take over vanilla mining for a target that this mod would reject. This lets
        // survival players hold attack to break that single block with vanilla behaviour.
        if (mc.level != null && mc.hitResult instanceof BlockHitResult hit) {
            BlockPos target = hit.getBlockPos();
            BlockSet singleTarget = new BlockSet();
            singleTarget.add(new BlockEntry(target));
            ConstraintSystem.INSTANCE.processBlocks(singleTarget, player, BuildPipeline.BuildState.BREAKING);
            BlockEntry entry = singleTarget.get(target);
            if (entry != null && !entry.isValid()) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Click handling — called by platform-specific client tick handlers
    // -------------------------------------------------------------------------

    public static void handleRightClick(Minecraft mc) {
        handleClick(mc, BuildPipeline.BuildState.PLACING);
    }

    public static void handleLeftClick(Minecraft mc) {
        handleClick(mc, BuildPipeline.BuildState.BREAKING);
    }

    private static void handleClick(Minecraft mc, BuildPipeline.BuildState action) {
        BuildModeEnum mode = BuildModes.CLIENT.getBuildMode();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // While anchored, right-click builds the anchored shape and left-click releases it
        if (previewLocked && anchor != null) {
            if (action == BuildPipeline.BuildState.PLACING) {
                sendLockedPlacement(mc, player);
            } else {
                clearAnchor();
                player.displayClientMessage(Component.translatable("effortlessbuilding.message.preview_unlocked"), true);
            }
            return;
        }

        BlockPos clickedPos;
        if (mode.instance.isFirstClick()) {
            Vec3 start = player.getEyePosition();
            Vec3 end = start.add(player.getLookAngle().scale(ServerConfig.INSTANCE.getReach(player)));
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
            BlockHitResult hit = mc.level.clip(ctx);
            if (hit.getType() != HitResult.Type.BLOCK) return;
            clickedPos = resolveFirstClickPos(hit, action, mc.level);
            buildState = action;
            firstClickHit = hit;
        } else {
            clickedPos = player.blockPosition();
        }

        BlockSet blocks = new BlockSet();
        boolean shouldPlace = mode.instance.onClick(blocks, clickedPos, player);

        if (shouldPlace) {
            mode.instance.findCoordinates(blocks, player);
            CLIENT.processBlocks(blocks, player, action);

            if (blocks.firstPos != null && blocks.lastPos != null) {
                SoundType soundType;
                if (action == BuildPipeline.BuildState.PLACING) {
                    var held = player.getMainHandItem();
                    BlockEntry firstEntry = blocks.get(blocks.firstPos);
                    if (firstEntry != null && firstEntry.blockState != null) {
                        soundType = firstEntry.blockState.getSoundType();
                    } else {
                        soundType = held.getItem() instanceof BlockItem blockItem
                                ? blockItem.getBlock().defaultBlockState().getSoundType()
                                : SoundType.STONE;
                    }
                    mc.level.playLocalSound(blocks.firstPos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                            soundType.getVolume(), soundType.getPitch(), false);
                } else {
                    soundType = mc.level.getBlockState(blocks.firstPos).getSoundType();
                    mc.level.playLocalSound(blocks.firstPos, soundType.getBreakSound(), SoundSource.BLOCKS,
                            soundType.getVolume(), soundType.getPitch(), false);
                }

                BlockPos intermediate = mode.instance.getIntermediatePos();
                BlockPos secondPos = intermediate != null ? intermediate : blocks.lastPos;
                BlockPos thirdPos  = intermediate != null ? blocks.lastPos : null;

                if (action == BuildPipeline.BuildState.PLACING) {
                    // Show warnings for rejected entries during placement
                    if (!blocks.rejectedEntries().isEmpty()) {
                        BlockStatus firstRejection = blocks.rejectedEntries().getFirst().getValue().getStatus();
                        if (firstRejection == BlockStatus.WORLD_BORDER) {
                            player.displayClientMessage(
                                    Component.translatable("effortlessbuilding.message.world_border"), true);
                        } else if (!player.getAbilities().instabuild) {
                            if (firstRejection == BlockStatus.NOT_PLACED_BY_PLAYER) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.only_replace_placed"), true);
                            } else if (firstRejection == BlockStatus.TOO_HARD) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.too_hard"), true);
                            } else if (firstRejection == BlockStatus.PROTECTED_TILE_ENTITY) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.protected_tile_entity"), true);
                            }
                        }
                    }
                    Direction hitFace = firstClickHit != null ? firstClickHit.getDirection() : Direction.UP;
                    Vec3 hitLocation = firstClickHit != null ? firstClickHit.getLocation() : Vec3.atCenterOf(blocks.firstPos);
                    PacketHandler.sendToServer(new PlaceBuildModePacket(
                            mode, blocks.firstPos, secondPos, thirdPos,
                            hitFace, hitLocation,
                            ModeOptions.getFill(), ModeOptions.getCubeFill(),
                            ModeOptions.getRaisedEdge(), ModeOptions.getCircleStart(),
                            BuildSettings.CLIENT.getReplaceMode(),
                            ClientConfig.INSTANCE.shouldProtectTileEntities(),
                            shapeFor(mode)));
                    // Client-side placement tracking
                    PlacedBlockTracker.clientTrackAll(mc.level.dimension(), blocks.keySet());
                } else {
                    // Show warnings for specific rejection reasons
                    if (!blocks.rejectedEntries().isEmpty()) {
                        BlockStatus firstRejection = blocks.rejectedEntries().getFirst().getValue().getStatus();
                        if (firstRejection == BlockStatus.WORLD_BORDER) {
                            player.displayClientMessage(
                                    Component.translatable("effortlessbuilding.message.world_border"), true);
                        } else if (!player.getAbilities().instabuild) {
                            if (firstRejection == BlockStatus.NOT_PLACED_BY_PLAYER) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.only_break_placed"), true);
                            } else if (firstRejection == BlockStatus.TOO_HARD) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.too_hard"), true);
                            } else if (firstRejection == BlockStatus.PROTECTED_TILE_ENTITY) {
                                player.displayClientMessage(
                                        Component.translatable("effortlessbuilding.message.protected_tile_entity"), true);
                            }
                        }
                    }
                    PacketHandler.sendToServer(new BreakBuildModePacket(
                            mode, blocks.firstPos, secondPos, thirdPos,
                            ModeOptions.getFill(), ModeOptions.getCubeFill(),
                            ModeOptions.getRaisedEdge(), ModeOptions.getCircleStart(),
                            ClientConfig.INSTANCE.shouldProtectTileEntities(),
                            shapeFor(mode)));
                }
            } else {
                Constants.LOG.warn("[EffortlessBuilding] Build mode {} produced no block positions", mode);
            }

            mode.instance.initialize();
            buildState = null;
            firstClickHit = null;
        }
    }

    // -------------------------------------------------------------------------
    // Preview computation
    // -------------------------------------------------------------------------

    /**
     * Computes the block set that should be highlighted in the preview this frame.
     */
    public static BlockSet getPreviewBlocks(Minecraft mc) {
        Player player = mc.player;
        if (player == null || mc.level == null) return null;

        // If preview is locked, return the frozen block set (released when leaving its dimension)
        if (previewLocked && anchor != null) {
            if (anchor.dimension() == mc.level.dimension()) return anchor.blocks();
            clearAnchor();
        }

        BuildModeEnum mode = BuildModes.CLIENT.getBuildMode();

        BlockSet result;
        if (!mode.instance.isFirstClick()) {
            BlockSet previewBlocks = new BlockSet();
            mode.instance.findCoordinates(previewBlocks, player);
            BuildPipeline.BuildState action = buildState != null ? buildState : BuildPipeline.BuildState.PLACING;
            CLIENT.processBlocks(previewBlocks, player, action);
            if (previewBlocks.isEmpty()) return null;
            previewBlocks.sortByDistance();
            previewBlocks.truncate(ServerConfig.INSTANCE.getMaxBlocksPlaced(player));
            result = previewBlocks;
        } else {
            Vec3 start = player.getEyePosition();
            Vec3 end = start.add(player.getLookAngle().scale(ServerConfig.INSTANCE.getReach(player)));
            ClipContext ctx = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
            BlockHitResult hit = mc.level.clip(ctx);
            if (hit.getType() != HitResult.Type.BLOCK) return null;
            BlockPos targetPos = resolveFirstClickPos(hit, BuildPipeline.BuildState.PLACING, mc.level);
            BlockSet blockSet = new BlockSet();
            if (mode == BuildModeEnum.SHAPE) {
                // Show the whole shape where it would stand, not just the targeted block
                ((ShapeMode) mode.instance).previewAt(blockSet, player, targetPos);
            } else {
                blockSet.add(new BlockEntry(targetPos));
                blockSet.firstPos = targetPos;
                blockSet.lastPos = targetPos;
            }
            CLIENT.processBlocks(blockSet, player, BuildPipeline.BuildState.PLACING);
            result = blockSet;
        }

        // Cache the result for preview lock
        lastPreviewBlocks = result;

        // Update item usage tracker for the preview
        updateDisplayTrackers(player, result);
        return result;
    }

    /**
     * Updates the client-side trackers based on the current preview block set.
     */
    private static void updateDisplayTrackers(Player player, BlockSet blockSet) {
        BuildPipeline.BuildState action = buildState != null ? buildState : BuildPipeline.BuildState.PLACING;

        if (action == BuildPipeline.BuildState.BREAKING) {
            BREAK_DISPLAY.compute(player, blockSet);
            ITEM_USAGE.initialize();
        } else {
            var held = player.getMainHandItem();
            net.minecraft.world.item.Item heldItem = null;
            if (held.getItem() instanceof BlockItem) {
                heldItem = held.getItem();
            } else if (held.getItem() instanceof BucketItem bucketItem) {
                var fluid = ((BucketItemAccessor) bucketItem).effortlessbuilding$getFluid();
                if (!fluid.isSame(Fluids.EMPTY)) {
                    heldItem = held.getItem();
                }
            }

            if (held.getItem() instanceof RandomizerToolItem) {
                ITEM_USAGE.compute(player, blockSet, player.getAbilities().instabuild);
            } else if (heldItem != null) {
                ITEM_USAGE.compute(player, blockSet.validPositions(), heldItem, player.getAbilities().instabuild);
            } else {
                ITEM_USAGE.initialize();
            }
            BREAK_DISPLAY.initialize();
        }
    }

    // -------------------------------------------------------------------------
    // Sequence cancellation
    // -------------------------------------------------------------------------

    /** Builds the anchored shape with the settings captured when it was locked, then releases it. */
    private static void sendLockedPlacement(Minecraft mc, Player player) {
        Anchor a = anchor;
        clearAnchor();
        if (a == null) return;

        SoundType soundType = player.getMainHandItem().getItem() instanceof BlockItem blockItem
                ? blockItem.getBlock().defaultBlockState().getSoundType()
                : SoundType.STONE;
        mc.level.playLocalSound(a.firstPos(), soundType.getPlaceSound(), SoundSource.BLOCKS,
                soundType.getVolume(), soundType.getPitch(), false);

        PacketHandler.sendToServer(new PlaceBuildModePacket(
                a.mode(), a.firstPos(), a.secondPos(), a.thirdPos(),
                a.hitFace(), a.hitLocation(),
                a.fill(), a.cubeFill(), a.raisedEdge(), a.circleStart(),
                BuildSettings.CLIENT.getReplaceMode(),
                ClientConfig.INSTANCE.shouldProtectTileEntities(),
                a.shape()));
        PlacedBlockTracker.clientTrackAll(mc.level.dimension(), a.blocks().keySet());
    }

    public static void cancelCurrentSequence() {
        if (buildState != null) {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_OUT, 1f));
        }
        BuildModes.CLIENT.getBuildMode().instance.initialize();
        buildState = null;
        firstClickHit = null;
        clearAnchor();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** The Shape Generator settings to send with a build, only for SHAPE mode. */
    private static @Nullable ShapeParams shapeFor(BuildModeEnum mode) {
        return mode == BuildModeEnum.SHAPE ? ShapeClientState.getActive() : null;
    }

    private static BlockPos resolveFirstClickPos(BlockHitResult hit, BuildPipeline.BuildState action, Level level) {
        BlockPos hitPos = hit.getBlockPos();
        if (action == BuildPipeline.BuildState.BREAKING) return hitPos;
        // Tools interact with the clicked block itself, not adjacent
        var mc = Minecraft.getInstance();
        if (mc.player != null && BuildPipeline.isToolInteractionItem(mc.player.getMainHandItem())) {
            return hitPos;
        }
        // When replacing blocks, click on the block itself instead of adjacent
        if (BuildSettings.CLIENT.shouldOffsetStartPosition()) return hitPos;
        if (level.getBlockState(hitPos).canBeReplaced()) return hitPos;
        return hitPos.relative(hit.getDirection());
    }
}

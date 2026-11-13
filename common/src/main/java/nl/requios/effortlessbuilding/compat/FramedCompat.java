package nl.requios.effortlessbuilding.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Optional support for FramedBlocks camos, without compiling against the mod.
 *
 * <p>A framed block's camo lives in its block entity and travels on the item as the data component
 * {@code framedblocks:camo_list}, a list of {@code {type: "framedblocks:block", state: {Name: ...}}}.
 * The component's own codec builds the value from that JSON and the block entity applies it through
 * the same path vanilla uses for a placed item, so the mod's own validation and syncing run.</p>
 */
public final class FramedCompat {
    public static final String MOD_ID = "framedblocks";
    private static final ResourceLocation CAMO_LIST = ResourceLocation.fromNamespaceAndPath(MOD_ID, "camo_list");

    private FramedCompat() {}

    /** The camo component, or null when FramedBlocks is not installed. */
    private static @Nullable DataComponentType<?> camoType() {
        return BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(CAMO_LIST).orElse(null);
    }

    public static boolean isAvailable() {
        return camoType() != null;
    }

    /** True for framed blocks (the mod's namespace): the only ones that take a camo. */
    public static boolean isFramed(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(MOD_ID);
    }

    /** The block a camo item stands for, or null when the item cannot be a camo (no block, or a block entity). */
    public static @Nullable BlockState camoState(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        if (!(BuiltInRegistries.ITEM.get(id) instanceof BlockItem blockItem)) return null;
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.isAir() || state.hasBlockEntity()) return null;
        if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(MOD_ID)) return null;
        return state;
    }

    /** The item the camo costs in survival (one per framed block), or null when the id is no usable camo. */
    public static @Nullable Item camoItem(String itemId) {
        BlockState state = camoState(itemId);
        return state == null ? null : state.getBlock().asItem();
    }

    /**
     * A framed slope whose low side faces {@code low} (its "facing" is the high side, the other way round
     * from stairs), or null when FramedBlocks is missing.
     */
    public static @Nullable BlockState slopeState(Direction low) {
        BlockState state = defaultState("framed_slope");
        if (state == null) return null;
        state = with(state, "facing", low.getOpposite().getName());
        return with(state, "type", "bottom");
    }

    /** A framed bottom slab, or null when FramedBlocks is missing. */
    public static @Nullable BlockState slabState() {
        BlockState state = defaultState("framed_slab");
        return state == null ? null : with(state, "top", "false");
    }

    /** The camo item id a plain block would get when it is turned into a framed block, or null if it cannot be one. */
    public static @Nullable String camoIdFor(BlockState plain) {
        if (camoState(BuiltInRegistries.BLOCK.getKey(plain.getBlock()).toString()) == null) return null;
        return BuiltInRegistries.BLOCK.getKey(plain.getBlock()).toString();
    }

    private static @Nullable BlockState defaultState(String path) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath(MOD_ID, path))
                .map(Block::defaultBlockState).orElse(null);
    }

    /** Sets a property by name and value, leaving the state alone when this version of the mod has no such property. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, String name, String value) {
        Property property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) return state;
        return (BlockState) property.getValue(value).map(v -> state.setValue(property, (Comparable) v)).orElse(state);
    }

    /**
     * What to draw for the preview of a framed block that will get this camo: the camo block itself when the
     * framed block is a full cube (the preview has no block entity to hold the camo), otherwise the framed block.
     */
    public static BlockState ghostState(BlockState framed, @Nullable String camoItemId) {
        BlockState camo = camoState(camoItemId);
        if (camo == null || !isFramed(framed)) return framed;
        try {
            if (Block.isShapeFullBlock(framed.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) return camo;
        } catch (RuntimeException ignored) {
            // A shape that needs its block entity: keep the plain framed block
        }
        return framed;
    }

    /**
     * Applies the camo to the framed block at {@code pos}. Does nothing when FramedBlocks is missing, the
     * block there is not a framed block, or the item is no valid camo.
     *
     * @return whether a camo was applied
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean applyCamo(ServerLevel level, BlockPos pos, String camoItemId) {
        DataComponentType type = camoType();
        if (type == null || type.codec() == null) return false;
        BlockState camo = camoState(camoItemId);
        if (camo == null) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !isFramed(level.getBlockState(pos))) return false;

        JsonObject entry = new JsonObject();
        entry.addProperty("type", MOD_ID + ":block");
        JsonObject state = new JsonObject();
        state.addProperty("Name", BuiltInRegistries.BLOCK.getKey(camo.getBlock()).toString());
        entry.add("state", state);
        JsonArray list = new JsonArray();
        list.add(entry);

        var parsed = type.codec().parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), list);
        Object value = parsed.result().orElse(null);
        if (value == null) return false;

        DataComponentMap map = DataComponentMap.builder().set(type, value).build();
        be.applyComponents(map, DataComponentPatch.EMPTY);
        be.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        return true;
    }
}

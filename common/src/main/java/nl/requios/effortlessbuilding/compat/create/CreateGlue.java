package nl.requios.effortlessbuilding.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.platform.Services;
import nl.requios.effortlessbuilding.shape.GlueBoxes;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Optional bridge to Create's super glue. Create is not a dependency: everything goes through
 * reflection and does nothing when Create is missing or its glue class changed.
 * <p>
 * Glue is laid as boxes that cover only the given blocks ({@link GlueBoxes}), so blocks around the
 * build are never glued to it. In survival each box costs one use of a Super Glue item from the
 * inventory, like gluing by hand; the item's own durability rules (Unbreaking, unbreakable) apply.
 */
public final class CreateGlue {

    private static final String GLUE_CLASS = "com.simibubi.create.content.contraptions.glue.SuperGlueEntity";
    private static final ResourceLocation GLUE_ITEM = ResourceLocation.fromNamespaceAndPath("create", "super_glue");
    private static final ResourceLocation BEARING = ResourceLocation.fromNamespaceAndPath("create", "mechanical_bearing");

    private static boolean initialized = false;
    private static Class<?> glueClass;
    private static Constructor<?> glueConstructor;

    private CreateGlue() {}

    /** True when Create is installed and its glue entity can be created. */
    public static synchronized boolean isAvailable() {
        if (!initialized) {
            initialized = true;
            if (Services.PLATFORM.isModLoaded("create")) {
                try {
                    glueClass = Class.forName(GLUE_CLASS);
                    glueConstructor = glueClass.getConstructor(net.minecraft.world.level.Level.class, AABB.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    Constants.LOG.warn("[EffortlessBuilding] Create is installed but its super glue could not be found; gluing is off", e);
                    glueClass = null;
                    glueConstructor = null;
                }
            }
        }
        return glueConstructor != null;
    }

    /**
     * Glues the placed blocks together. Tells the player when they ran out of glue part way.
     *
     * @return number of glue boxes placed
     */
    public static int glue(ServerPlayer player, ServerLevel level, Collection<BlockPos> placed) {
        if (!isAvailable() || placed.isEmpty()) return 0;
        List<Cell> cells = new ArrayList<>(placed.size());
        for (BlockPos p : placed) cells.add(new Cell(p.getX(), p.getY(), p.getZ()));

        boolean free = player.isCreative();
        int count = 0;
        for (GlueBoxes.Box box : GlueBoxes.cover(cells)) {
            if (!free && !useGlue(player, level)) {
                player.displayClientMessage(Component.translatable("effortlessbuilding.message.out_of_super_glue"), true);
                break;
            }
            try {
                AABB bounds = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
                level.addFreshEntity((Entity) glueConstructor.newInstance(level, bounds));
                count++;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Constants.LOG.warn("[EffortlessBuilding] Could not place super glue", e);
                break;
            }
        }
        return count;
    }

    /** Create's mechanical bearing facing the given way, or null without Create. */
    public static @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState bearing(net.minecraft.core.Direction facing) {
        var block = BuiltInRegistries.BLOCK.getOptional(BEARING).orElse(null);
        if (block == null) return null;
        var state = block.defaultBlockState();
        for (var property : state.getProperties()) {
            if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty dir
                    && dir.getPossibleValues().contains(facing)) {
                return state.setValue(dir, facing);
            }
        }
        return state;
    }

    public static boolean isBearing(@org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState state) {
        return state != null && BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(BEARING);
    }

    /** Removes glue that lies entirely within the given blocks (used when a glued build is undone). */
    public static void removeWithin(ServerLevel level, Set<BlockPos> blocks) {
        if (!isAvailable() || blocks.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        AABB area = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        for (Entity glue : level.getEntities((Entity) null, area, glueClass::isInstance)) {
            if (coversOnly(glue.getBoundingBox(), blocks)) glue.discard();
        }
    }

    private static boolean coversOnly(AABB box, Set<BlockPos> blocks) {
        for (int x = (int) Math.floor(box.minX); x < (int) Math.ceil(box.maxX); x++)
            for (int y = (int) Math.floor(box.minY); y < (int) Math.ceil(box.maxY); y++)
                for (int z = (int) Math.floor(box.minZ); z < (int) Math.ceil(box.maxZ); z++)
                    if (!blocks.contains(new BlockPos(x, y, z))) return false;
        return true;
    }

    /** Takes one use from a Super Glue in the inventory; false when there is none. */
    private static boolean useGlue(ServerPlayer player, ServerLevel level) {
        Item glueItem = BuiltInRegistries.ITEM.get(GLUE_ITEM);
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(glueItem)) continue;
            // hurtAndBreak applies Unbreaking and skips unbreakable items
            stack.hurtAndBreak(1, level, player, item -> {});
            return true;
        }
        return false;
    }
}

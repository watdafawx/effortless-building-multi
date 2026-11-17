package io.github.watdafawx.shapewright.compat;

import io.github.watdafawx.shapewright.shape.ShapeGenerator.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional bridge to the mc-mcp mod's "ghost builds" (translucent client-only block previews), without compiling
 * against it. Everything goes through {@code dev.watdafawx.mcmcp.ghost.GhostApi}, looked up by name at run time; when
 * the mod is missing, older, or a call fails, {@link #isAvailable()} is false or the call returns a neutral value,
 * and Shapewright behaves exactly as it does without the mod. Contract: see {@code GHOST-BRIDGE-CONTRACT.md}
 * (static methods, vanilla/JDK types only, positions relative to the ghost origin).
 */
public final class GhostCompat {
    public static final String MOD_ID = "mcmcp";
    private static final String API = "dev.watdafawx.mcmcp.ghost.GhostApi";

    private record Api(MethodHandle isReady, MethodHandle ids, MethodHandle set, MethodHandle blocks) {}

    private static boolean resolved;
    private static @Nullable Api api;

    private GhostCompat() {}

    private static synchronized @Nullable Api api() {
        if (resolved) return api;
        resolved = true;
        try {
            Class<?> c = Class.forName(API, false, GhostCompat.class.getClassLoader());
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            // Required: isAvailable() and set(id, blocks, origin). Optional (import from ghost): ids(), blocks(id).
            api = new Api(
                    l.findStatic(c, "isAvailable", MethodType.methodType(boolean.class)),
                    optional(l, c, "ids", MethodType.methodType(List.class)),
                    l.findStatic(c, "set", MethodType.methodType(void.class, String.class, Map.class, BlockPos.class)),
                    optional(l, c, "blocks", MethodType.methodType(Map.class, String.class)));
        } catch (ReflectiveOperationException | LinkageError e) {
            api = null; // not installed, or an older version without the bridge
        }
        return api;
    }

    private static @Nullable MethodHandle optional(MethodHandles.Lookup l, Class<?> c, String name, MethodType type) {
        try {
            return l.findStatic(c, name, type);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** True when mc-mcp's ghost bridge can import ghosts (older builds without ids() and blocks(id) can only receive). */
    public static boolean canImport() {
        Api a = api();
        return a != null && a.ids != null && a.blocks != null && isAvailable();
    }

    /** True when mc-mcp with the ghost bridge is installed and ready. */
    public static boolean isAvailable() {
        Api a = api();
        if (a == null) return false;
        try {
            return (boolean) a.isReady.invoke();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ids of the ghosts mc-mcp holds; empty when unavailable. */
    @SuppressWarnings("unchecked")
    public static List<String> ids() {
        Api a = api();
        if (a == null || a.ids == null) return List.of();
        try {
            return List.copyOf((List<String>) a.ids.invoke());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Creates or replaces a ghost from blocks given relative to {@code origin}. False when unavailable or refused. */
    public static boolean send(String id, BlockPos origin, Map<Cell, BlockState> blocks) {
        Api a = api();
        if (a == null) return false;
        Map<BlockPos, BlockState> local = new HashMap<>();
        blocks.forEach((c, s) -> local.put(new BlockPos(c.x(), c.y(), c.z()), s));
        try {
            a.set.invoke(id, local, origin);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A ghost's blocks relative to its origin, shifted so the lowest corner is 0,0,0; null when unavailable or unknown. */
    @SuppressWarnings("unchecked")
    public static @Nullable Map<Cell, BlockState> fetch(String id) {
        Api a = api();
        if (a == null || a.blocks == null) return null;
        try {
            Map<BlockPos, BlockState> m = (Map<BlockPos, BlockState>) a.blocks.invoke(id);
            if (m == null || m.isEmpty()) return null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (BlockPos p : m.keySet()) { minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ()); }
            Map<Cell, BlockState> out = new HashMap<>();
            for (var e : m.entrySet()) out.put(new Cell(e.getKey().getX() - minX, e.getKey().getY() - minY, e.getKey().getZ() - minZ), e.getValue());
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}

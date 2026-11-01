package nl.requios.effortlessbuilding.shape;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.utilities.InventoryHelper;
import nl.requios.effortlessbuilding.utilities.UndoManager;

import java.util.*;

/**
 * Prepares natural ground for a build: under its footprint the ground is filled up to (or cut down to)
 * the build's base, and around it the ground eases back to the original height over {@code margin}
 * blocks along a smooth curve with a little roughness, so it looks like a slope rather than a flat pad.
 * <p>
 * Only natural terrain (dirt, grass, sand, stone, gravel, terracotta, snow) and plants are touched;
 * columns with water or anything built on them are left alone. Each column keeps its own surface
 * block. In survival, filling uses the column's subsoil item (inventory, then AE2) and cutting gives the
 * blocks' drops; in creative both are free. All changes go into the build's undo entry.
 */
public final class TerrainBlender {

    /** Shape settings: blend on (1) or off (0), and how many blocks the slope around the build takes. */
    public static final String ENABLED = "blend_terrain", MARGIN = "blend_margin";

    private TerrainBlender() {}

    /**
     * @param build   positions the build will occupy
     * @param groundY the height of the ground the build stands on (one below its lowest block)
     */
    public static void blend(ServerPlayer player, ServerLevel level, Collection<BlockPos> build, int groundY, int margin,
                             Map<BlockPos, UndoManager.BlockChange> undoChanges) {
        Set<Long> footprint = new HashSet<>();
        for (BlockPos p : build) footprint.add(column(p.getX(), p.getZ()));
        if (footprint.isEmpty()) return;
        List<int[]> edge = edgeColumns(footprint);

        boolean survival = !player.isCreative();
        boolean mayCut = !survival || ServerConfig.INSTANCE.survivalAllowBreaking;

        // Plan the height of every column, footprint first so it gets fill material before the slopes
        record Plan(int x, int z, int surface, int target, BlockState top, BlockState sub) {}
        List<Plan> plans = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long c : footprint) {
            int x = colX(c), z = colZ(c);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        List<long[]> order = new ArrayList<>();
        for (int x = minX - margin; x <= maxX + margin; x++)
            for (int z = minZ - margin; z <= maxZ + margin; z++) {
                boolean inside = footprint.contains(column(x, z));
                double d = inside ? 0 : distance(x, z, edge);
                if (d > margin) continue;
                order.add(new long[]{x, z, (long) (d * 1000)});
            }
        order.sort(Comparator.comparingLong(o -> o[2]));

        for (long[] o : order) {
            int x = (int) o[0], z = (int) o[1];
            double d = o[2] / 1000.0;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockState topState = level.getBlockState(new BlockPos(x, surface, z));
            if (!topState.getFluidState().isEmpty() || !isTerrain(topState)) continue; // water or something built
            BlockState below = level.getBlockState(new BlockPos(x, surface - 1, z));
            BlockState sub = isTerrain(below) ? below : Blocks.DIRT.defaultBlockState();

            int target;
            if (d == 0) {
                target = groundY;
            } else {
                double t = smoothstep(d / (margin + 1.0));
                double height = groundY + (surface - groundY) * t;
                // A little roughness in the middle of the slope, never past either end
                if (d > 1 && d < margin) height += (jitter(x, z) - 0.5) * 1.2;
                target = (int) Math.round(Math.max(Math.min(groundY, surface), Math.min(Math.max(groundY, surface), height)));
            }
            if (target != surface) plans.add(new Plan(x, z, surface, target, topState, sub));
        }

        // Survival: take fill items up front (inventory, then AE2), per item
        Map<Item, Integer> budget = new HashMap<>();
        Map<Item, Integer> fromNetwork = new HashMap<>();
        if (survival) {
            Map<Item, Integer> needed = new HashMap<>();
            for (Plan p : plans) if (p.target() > p.surface()) needed.merge(p.sub().getBlock().asItem(), p.target() - p.surface(), Integer::sum);
            for (var n : needed.entrySet()) {
                if (!(n.getKey() instanceof BlockItem)) continue;
                InventoryHelper.Taken taken = InventoryHelper.takeItems(player, n.getKey(), n.getValue());
                budget.put(n.getKey(), taken.total());
                fromNetwork.put(n.getKey(), taken.fromNetwork());
            }
        }

        for (Plan p : plans) {
            if (p.target() > p.surface()) {
                // Raise: subsoil up to the target, and the column's own surface block on top
                Item fill = p.sub().getBlock().asItem();
                for (int y = p.surface(); y <= p.target(); y++) {
                    BlockPos pos = new BlockPos(p.x(), y, p.z());
                    BlockState now = level.getBlockState(pos);
                    boolean newTop = y == p.target();
                    if (y == p.surface()) {
                        // The old surface block becomes subsoil once covered (creative only, to keep survival simple)
                        if (!survival && now.equals(p.top()) && !p.top().equals(p.sub())) set(level, pos, now, p.sub(), undoChanges);
                        continue;
                    }
                    if (!now.canBeReplaced()) break; // something in the way: stop raising this column
                    if (survival) {
                        int left = budget.getOrDefault(fill, 0);
                        if (left <= 0) break;
                        budget.put(fill, left - 1);
                    }
                    set(level, pos, now, newTop && !survival ? p.top() : p.sub(), undoChanges);
                }
            } else if (mayCut) {
                // Lower: clear natural ground (and plants) above the target, then put the surface block on top
                boolean blocked = false;
                for (int y = p.surface(); y > p.target() && !blocked; y--) {
                    BlockPos pos = new BlockPos(p.x(), y, p.z());
                    BlockState now = level.getBlockState(pos);
                    if (!isTerrain(now)) { blocked = true; continue; }
                    // Plants resting on the column go too, so nothing floats
                    BlockPos above = pos.above();
                    BlockState plant = level.getBlockState(above);
                    if (!plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty()) set(level, above, plant, Blocks.AIR.defaultBlockState(), undoChanges);
                    if (survival) giveDrops(player, level, pos, now);
                    set(level, pos, now, Blocks.AIR.defaultBlockState(), undoChanges);
                }
                if (!blocked && !survival) {
                    BlockPos top = new BlockPos(p.x(), p.target(), p.z());
                    BlockState now = level.getBlockState(top);
                    if (isTerrain(now) && !now.equals(p.top())) set(level, top, now, p.top(), undoChanges);
                }
            }
        }

        // Unused fill goes back where it came from (the network share first)
        for (var left : budget.entrySet()) {
            int n = left.getValue();
            if (n > 0) InventoryHelper.returnItems(player, left.getKey(), n, Math.min(n, fromNetwork.getOrDefault(left.getKey(), 0)));
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState before, BlockState after,
                            Map<BlockPos, UndoManager.BlockChange> undoChanges) {
        level.setBlock(pos, after, Block.UPDATE_ALL);
        // Keep the first "before" so one undo restores the original ground
        undoChanges.merge(pos.immutable(), new UndoManager.BlockChange(before, after),
                (first, next) -> new UndoManager.BlockChange(first.oldState(), next.newState()));
    }

    private static void giveDrops(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack tool = ServerConfig.INSTANCE.survivalRequireTools ? InventoryHelper.findCorrectTool(player, state) : player.getMainHandItem();
        for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), player, tool)) {
            InventoryHelper.giveOrDropItems(player, drop);
        }
    }

    /** Natural ground the blender may reshape. */
    static boolean isTerrain(BlockState state) {
        if (state.hasBlockEntity()) return false;
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.TERRACOTTA) || state.is(BlockTags.SNOW) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.DIRT_PATH);
    }

    // ---- geometry -------------------------------------------------------------

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int colX(long c) { return (int) (c >> 32); }
    private static int colZ(long c) { return (int) c; }

    /** Footprint columns with a neighbor outside the footprint: the distance field is measured from these. */
    private static List<int[]> edgeColumns(Set<Long> footprint) {
        List<int[]> edge = new ArrayList<>();
        for (long c : footprint) {
            int x = colX(c), z = colZ(c);
            if (!footprint.contains(column(x + 1, z)) || !footprint.contains(column(x - 1, z))
                    || !footprint.contains(column(x, z + 1)) || !footprint.contains(column(x, z - 1))) edge.add(new int[]{x, z});
        }
        return edge;
    }

    private static double distance(int x, int z, List<int[]> edge) {
        double best = Double.MAX_VALUE;
        for (int[] e : edge) best = Math.min(best, Math.hypot(x - e[0], z - e[1]));
        return best;
    }

    private static double smoothstep(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Stable 0..1 value per column, so the roughness is the same on every run. */
    private static double jitter(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        return ((h >>> 11) & 0xFFFF) / 65535.0;
    }
}

package nl.requios.effortlessbuilding.buildmode.buildmodes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import nl.requios.effortlessbuilding.compat.create.CreateGlue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import nl.requios.effortlessbuilding.buildmode.BaseBuildMode;
import nl.requios.effortlessbuilding.config.ServerConfig;
import nl.requios.effortlessbuilding.shape.*;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import nl.requios.effortlessbuilding.utilities.BlockEntry;
import nl.requios.effortlessbuilding.utilities.BlockSet;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Builds the shape chosen in the Shape Generator screen ({@link ShapeClientState}).
 * <ul>
 *   <li>Screen sizing: one click places the shape, standing on the clicked block.</li>
 *   <li>Click sizing: the first click sets the center, the second sets the radius
 *       (horizontal distance from the center); the design scales to it.</li>
 *   <li>Path: the first click sets the start, the second the end; copies repeat along the line,
 *       optionally turned to face along it.</li>
 * </ul>
 * With "follow ground", every column of the result is moved up or down to sit on the terrain below it.
 * The server regenerates everything from the packet's {@link ShapeParams} and click positions.
 */
public class ShapeMode extends BaseBuildMode {

    private BlockPos center;

    /** Last generated cells, reused while nothing changes (the preview asks every frame). */
    private ShapeParams cachedParams;
    private int cachedAxis;
    private List<Long> cachedVersions;
    private Built cachedBuilt = new Built(List.of(), Set.of(), Set.of(), null, null);

    @Override
    public void initialize() {
        super.initialize();
        center = null;
    }

    @Override
    public boolean onClick(BlockSet blocks, BlockPos clickedPos, Player player) {
        super.onClick(blocks, clickedPos, player);
        if (clicks == 1) {
            center = clickedPos;
            return ShapeClientState.getActive().sizing() == ShapeParams.Sizing.SCREEN;
        }
        return true;
    }

    @Override
    public void findCoordinates(BlockSet blocks, Player player) {
        if (clicks == 0 || center == null) return;
        ShapeParams params = ShapeClientState.getActive();
        BlockPos edge = params.sizing() != ShapeParams.Sizing.SCREEN ? Floor.findFloor(player, center, true) : center;
        if (edge == null) edge = center;
        fill(blocks, player, center, edge, params);
    }

    /** Preview before the first click: the shape at the targeted block, at the screen's size. */
    public void previewAt(BlockSet blocks, Player player, BlockPos target) {
        fill(blocks, player, target, target, ShapeClientState.getActive().withSizing(ShapeParams.Sizing.SCREEN));
    }

    private void fill(BlockSet blocks, Player player, BlockPos anchor, BlockPos edge, ShapeParams params) {
        blocks.clear();
        for (BlockPos pos : layout(player, anchor, edge, params).keySet()) {
            blocks.add(new BlockEntry(pos));
        }
        // firstPos is the anchor so the packet carries it; lastPos the radius point or path end
        blocks.firstPos = anchor;
        blocks.lastPos = edge;
        assignItems(blocks, player, anchor, edge, params);
    }

    /** Anchor goes out as firstPos, the radius point or path end as secondPos. */
    @Override
    public List<BlockPos> getServerBlocks(Player player, BlockPos firstPos, BlockPos secondPos,
                                         @Nullable BlockPos thirdPos, @Nullable ShapeParams shape) {
        if (shape == null) return List.of();
        return new ArrayList<>(layout(player, firstPos, secondPos, shape).keySet());
    }

    /**
     * Gives positions their own block: a schematic's saved blocks (exact states, so stairs keep their
     * facing) and the center axle's block. Everything else keeps the held block or palette.
     */
    @Override
    public void assignItems(BlockSet blocks, Player player, BlockPos firstPos, BlockPos secondPos, @Nullable ShapeParams shape) {
        if (shape == null) return;
        layout(player, firstPos, secondPos, shape).forEach((pos, own) -> {
            if (own == null) return;
            BlockEntry entry = blocks.get(pos);
            if (entry == null) return;
            entry.item = own.getBlock().asItem();
            entry.blockState = own;
            entry.exactState = true;
        });
    }

    // =========================================================================
    // Layout: where every block goes, and which have their own block state
    // =========================================================================

    /** One copy of the shape: where it stands and with which settings (a path turns each copy). */
    private record Placement(BlockPos anchor, ShapeParams params) {}

    /** World position → the block it must be (null: the held block or palette decides). */
    private Map<BlockPos, BlockState> layout(Player player, BlockPos first, BlockPos second, ShapeParams params) {
        int maxAxis = ServerConfig.INSTANCE.getMaxBlocksPerAxis(player);
        BlockState centerState = null;
        ResourceLocation centerId = params.centerBlock().isEmpty() ? null : ResourceLocation.tryParse(params.centerBlock());
        if (centerId != null && BuiltInRegistries.ITEM.get(centerId) instanceof BlockItem item) centerState = item.getBlock().defaultBlockState();

        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Placement placement : placements(first, second, params)) {
            Built built = cells(placement.params(), maxAxis);
            Map<Cell, BlockState> saved = ShapeMaterials.of(placement.params());
            List<Cell> all = new ArrayList<>(built.cells());
            for (Cell c : built.axle()) if (!built.cellSet().contains(c)) all.add(c); // the axle fills even open middles
            if (built.bearing() != null) {
                BlockState bearing = CreateGlue.bearing(built.bearingFacing());
                Cell b = built.bearing();
                if (bearing != null) out.put(placement.anchor().offset(b.x(), b.y(), b.z()), bearing);
            }
            for (Cell c : all) {
                BlockPos pos = placement.anchor().offset(c.x(), c.y(), c.z());
                BlockState own = null;
                if (centerState != null && built.axle().contains(c)) {
                    own = centerState;
                } else if (saved.containsKey(c)) {
                    own = saved.get(c);
                    if (!(own.getBlock().asItem() instanceof BlockItem)) continue; // water, portals: left out
                }
                out.put(pos, own);
            }
        }
        if (params.getInt(ShapeType.FOLLOW_GROUND) == 1) out = followGround(player.level(), out);
        if (params.getInt(ShapeType.SMOOTH) != 0) smooth(player, out, params.getInt(ShapeType.SMOOTH) == 1);
        return out;
    }

    /**
     * Turns top blocks that sit one step above a neighbor into stairs (low side toward the drop) or
     * bottom slabs, using the stair/slab version of the block that would be placed there. Blocks
     * without such a version stay full.
     */
    private static void smooth(Player player, Map<BlockPos, BlockState> layout, boolean stairs) {
        BlockState held = player.getMainHandItem().getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState() : null;
        Map<BlockPos, BlockState> changes = new HashMap<>();
        for (var e : layout.entrySet()) {
            BlockPos pos = e.getKey();
            if (layout.containsKey(pos.above())) continue; // only the top surface
            List<Direction> drops = new ArrayList<>();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(d);
                if (!layout.containsKey(side) && layout.containsKey(side.below())) drops.add(d);
            }
            if (drops.isEmpty()) continue;
            BlockState base = e.getValue() != null ? e.getValue() : held;
            if (base == null) continue;
            BlockState smoothed = null;
            if (stairs && drops.size() == 1) {
                Block stair = variant(base.getBlock(), "_stairs");
                if (stair instanceof StairBlock) {
                    smoothed = stair.defaultBlockState().setValue(StairBlock.FACING, drops.get(0).getOpposite());
                }
            }
            if (smoothed == null) {
                Block slab = variant(base.getBlock(), "_slab");
                if (slab instanceof SlabBlock) smoothed = slab.defaultBlockState();
            }
            if (smoothed != null) changes.put(pos, smoothed);
        }
        layout.putAll(changes);
    }

    /**
     * The stairs or slab made from a block, found by name: stone → stone_stairs, stone_bricks →
     * stone_brick_stairs, oak_planks → oak_stairs, quartz_block → quartz_stairs. Null when there is none.
     */
    private static @Nullable Block variant(Block block, String suffix) {
        if (block instanceof StairBlock || block instanceof SlabBlock) return null;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String name = id.getPath();
        List<String> bases = new ArrayList<>(List.of(name));
        for (String end : List.of("_planks", "_block", "s")) {
            if (name.endsWith(end)) bases.add(name.substring(0, name.length() - end.length()));
        }
        for (String b : bases) {
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), b + suffix);
            var found = BuiltInRegistries.BLOCK.getOptional(candidate);
            if (found.isPresent()) return found.get();
        }
        return null;
    }

    private List<Placement> placements(BlockPos first, BlockPos second, ShapeParams params) {
        return switch (params.sizing()) {
            case SCREEN -> List.of(new Placement(first, params));
            case CLICKS -> {
                int radius = (int) Math.round(Math.hypot(second.getX() - first.getX(), second.getZ() - first.getZ()));
                yield List.of(new Placement(first, params.scaledTo(Math.max(1, radius))));
            }
            case PATH -> {
                double dx = second.getX() - first.getX(), dz = second.getZ() - first.getZ();
                double length = Math.hypot(dx, dz);
                int spacing = Math.max(1, params.getInt(ShapeType.PATH_SPACING));
                ShapeParams copy = params;
                if (params.getInt(ShapeType.PATH_ALIGN) == 1 && length > 0) {
                    // Turn so the shape's front (+z) points along the path
                    double turn = Math.toDegrees(Math.atan2(dx, dz));
                    copy = params.with(ShapeType.ROTATE_Y, Math.round(params.get(ShapeType.ROTATE_Y) + turn));
                }
                List<Placement> list = new ArrayList<>();
                for (double t = 0; t <= length + 1e-6; t += spacing) {
                    double f = length == 0 ? 0 : t / length;
                    BlockPos at = new BlockPos((int) Math.round(first.getX() + dx * f), first.getY(),
                            (int) Math.round(first.getZ() + dz * f));
                    list.add(new Placement(at, copy));
                }
                yield list;
            }
        };
    }

    /**
     * Moves every column up or down so its lowest block sits on the ground right below it
     * (the first free block above the terrain, ignoring leaves). Columns in unloaded chunks stay put.
     */
    private static Map<BlockPos, BlockState> followGround(Level level, Map<BlockPos, BlockState> layout) {
        Map<Long, Integer> lowest = new HashMap<>();
        for (BlockPos p : layout.keySet()) lowest.merge(column(p), p.getY(), Math::min);
        Map<Long, Integer> shift = new HashMap<>();
        for (var e : lowest.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) { shift.put(e.getKey(), 0); continue; }
            shift.put(e.getKey(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - e.getValue());
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        layout.forEach((p, own) -> out.put(p.above(shift.get(column(p))), own));
        return out;
    }

    private static long column(BlockPos p) {
        return ((long) p.getX() << 32) | (p.getZ() & 0xFFFFFFFFL);
    }

    /**
     * The shape's cells (as a list and a set), its center axle (empty without a center block or bearing)
     * and where a Create bearing goes, facing the shape (null without one).
     */
    private record Built(List<Cell> cells, Set<Cell> cellSet, Set<Cell> axle,
                         @Nullable Cell bearing, @Nullable Direction bearingFacing) {}

    private synchronized Built cells(ShapeParams params, int maxAxis) {
        // Schematic versions: a re-uploaded or edited schematic with the same name must not reuse old cells
        List<Long> versions = SchematicLibrary.namesIn(params).stream().map(SchematicLibrary::version).toList();
        if (!Objects.equals(params, cachedParams) || maxAxis != cachedAxis || !versions.equals(cachedVersions)) {
            List<Cell> cells = ShapeGenerator.generate(params, maxAxis, SchematicLibrary::cells);
            int bearingEnd = CreateGlue.isAvailable() && params.getInt(ShapeType.SUPER_GLUE) == 1
                    ? params.getInt(ShapeType.BEARING) : 0;
            // A bearing needs something to hold in the middle, so it brings the axle with it
            Set<Cell> axle = params.centerBlock().isEmpty() && bearingEnd == 0 ? Set.of()
                    : new LinkedHashSet<>(ShapeGenerator.centerAxis(params.orientation(), cells));
            Cell bearing = null;
            Direction facing = null;
            if (bearingEnd != 0 && !axle.isEmpty()) {
                Direction.Axis axis = switch (params.orientation()) {
                    case FLAT -> Direction.Axis.Y;
                    case UPRIGHT_NS -> Direction.Axis.Z;
                    case UPRIGHT_EW -> Direction.Axis.X;
                };
                boolean atStart = bearingEnd == 1;
                // The axle's end block (the first of a 2x2 axle), then one step outside the shape
                Cell end = null;
                for (Cell c : axle) {
                    int t = axis.choose(c.x(), c.y(), c.z()), best = end == null ? 0 : axis.choose(end.x(), end.y(), end.z());
                    if (end == null || (atStart ? t < best : t > best)) end = c;
                }
                facing = Direction.fromAxisAndDirection(axis, atStart ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
                bearing = new Cell(end.x() - facing.getStepX(), end.y() - facing.getStepY(), end.z() - facing.getStepZ());
            }
            cachedBuilt = new Built(cells, new HashSet<>(cells), axle, bearing, facing);
            cachedParams = params;
            cachedAxis = maxAxis;
            cachedVersions = versions;
        }
        return cachedBuilt;
    }
}

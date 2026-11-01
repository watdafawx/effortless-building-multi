package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.util.*;

/**
 * Covers a set of blocks with boxes for Create's super glue, which glues two touching blocks when one
 * box contains both. Every box holds only blocks from the set, so nothing around the shape (the ground,
 * a hollow middle, a neighbor's wall) gets glued by mistake, and every pair of touching blocks in the set
 * shares a box, so the whole shape holds together.
 * <p>
 * First the blocks are split into solid cuboids; then, where two cuboids touch, a two-block-thick box
 * over their contact area joins them.
 */
public final class GlueBoxes {

    /** Inclusive block bounds. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** Boxes stay within the span a player could glue by hand. */
    public static final int MAX_SPAN = 24;

    private GlueBoxes() {}

    public static List<Box> cover(Collection<Cell> blocks) {
        Set<Cell> set = new HashSet<>(blocks);
        List<Cell> order = new ArrayList<>(set);
        order.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));

        // Solid cuboids, grown greedily along x, then z, then y
        Map<Cell, Integer> owner = new HashMap<>();
        List<Box> cuboids = new ArrayList<>();
        for (Cell start : order) {
            if (owner.containsKey(start)) continue;
            int x0 = start.x(), y0 = start.y(), z0 = start.z();
            int x1 = x0, z1 = z0, y1 = y0;
            while (x1 - x0 + 1 < MAX_SPAN && free(set, owner, x1 + 1, x1 + 1, y0, y0, z0, z0)) x1++;
            while (z1 - z0 + 1 < MAX_SPAN && free(set, owner, x0, x1, y0, y0, z1 + 1, z1 + 1)) z1++;
            while (y1 - y0 + 1 < MAX_SPAN && free(set, owner, x0, x1, y1 + 1, y1 + 1, z0, z1)) y1++;
            int id = cuboids.size();
            cuboids.add(new Box(x0, y0, z0, x1, y1, z1));
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) owner.put(new Cell(x, y, z), id);
        }

        // Joints: for each pair of touching cuboids and each axis they touch along, one box
        // two blocks thick over the whole contact rectangle (all of it is inside the set)
        List<Box> out = new ArrayList<>(cuboids);
        Set<String> joined = new HashSet<>();
        int[][] dirs = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (Cell c : order) {
            int a = owner.get(c);
            for (int[] d : dirs) {
                Integer b = owner.get(new Cell(c.x() + d[0], c.y() + d[1], c.z() + d[2]));
                if (b == null || b == a || !joined.add(a + ":" + b + ":" + Arrays.toString(d))) continue;
                out.add(joint(cuboids.get(a), cuboids.get(b), d));
            }
        }
        return out;
    }

    /** The two-block slab across the face where cuboid a meets cuboid b (b is on the +d side of a). */
    private static Box joint(Box a, Box b, int[] d) {
        int minX = Math.max(a.minX(), b.minX()), maxX = Math.min(a.maxX(), b.maxX());
        int minY = Math.max(a.minY(), b.minY()), maxY = Math.min(a.maxY(), b.maxY());
        int minZ = Math.max(a.minZ(), b.minZ()), maxZ = Math.min(a.maxZ(), b.maxZ());
        if (d[0] == 1) { minX = a.maxX(); maxX = b.minX(); }
        if (d[1] == 1) { minY = a.maxY(); maxY = b.minY(); }
        if (d[2] == 1) { minZ = a.maxZ(); maxZ = b.minZ(); }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean free(Set<Cell> set, Map<Cell, Integer> owner, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    Cell c = new Cell(x, y, z);
                    if (!set.contains(c) || owner.containsKey(c)) return false;
                }
        return true;
    }
}

package nl.requios.effortlessbuilding.shape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns {@link ShapeParams} into block positions. Pure math, shared by the client preview and the server,
 * so both always agree on the result.
 * <p>
 * Output positions are relative to the placement anchor: x/z centered on the shape's center,
 * y = 0 at the shape's lowest block (the shape stands on the clicked block).
 */
public final class ShapeGenerator {

    /** A block position relative to the anchor. */
    public record Cell(int x, int y, int z) {}

    private ShapeGenerator() {}

    /** Math shapes only; schematic shapes and parts come out empty. */
    public static List<Cell> generate(ShapeParams p, int maxAxis) {
        return generate(p, maxAxis, name -> List.of());
    }

    /**
     * Generates the shape and combines its parts into it, in order.
     *
     * @param maxAxis    blocks further than this from the anchor along any axis are dropped (server size limit)
     * @param schematics cells of a schematic by name, anchored like every other shape (see {@code SchematicLibrary})
     * @return cells sorted bottom layer first
     */
    public static List<Cell> generate(ShapeParams p, int maxAxis, Function<String, List<Cell>> schematics) {
        Set<Cell> result = new HashSet<>(single(p, schematics));
        for (ShapeParams.Part part : p.parts()) {
            Set<Cell> other = new HashSet<>();
            for (Cell c : single(part.shape(), schematics)) {
                other.add(new Cell(c.x() + part.x(), c.y() + part.y(), c.z() + part.z()));
            }
            switch (part.operation()) {
                case UNITE -> result.addAll(other);
                case SUBTRACT -> result.removeAll(other);
                case INTERSECT -> result.retainAll(other);
                case EXCLUDE -> {
                    Set<Cell> both = new HashSet<>(result);
                    both.retainAll(other);
                    result.addAll(other);
                    result.removeAll(both);
                }
            }
        }
        return clip(result, maxAxis);
    }

    /** One shape (its parts ignored), oriented and anchored, not clipped. */
    private static Collection<Cell> single(ShapeParams p, Function<String, List<Cell>> schematics) {
        if (p.type() == ShapeType.SCHEMATIC) return schematics.apply(p.schematic());
        Set<Cell> local = new HashSet<>();
        switch (p.type()) {
            case GEAR -> gear(p, local);
            case TORUS -> torus(p, local);
            case ARCH -> arch(p, local);
            case HELIX -> helix(p, local);
            case ELLIPSOID -> ellipsoid(p, local);
            case PRISM -> extrudedPolygon(regularPolygon(p.getInt("sides"), p.size(), p.get("rotation")), p.getInt("height"), p.hollow(), local);
            case STAR -> extrudedPolygon(star(p), p.getInt("height"), p.hollow(), local);
            case CONE -> cone(p, local);
            case PYRAMID -> pyramid(p, local);
            case BOWL -> bowl(p, local);
            case WHEEL -> wheel(p, local);
            case TOWER -> tower(p, local);
            case SPIRAL -> spiral(p, local);
            case SCHEMATIC -> { }
        }
        if (p.hollow() && shellIn3D(p.type())) local = shell(local);
        return orient(local, p.orientation());
    }

    // -------------------------------------------------------------------------
    // Shapes, in the local frame: (u, v) face plane, w depth
    // -------------------------------------------------------------------------

    /**
     * Create-style cogwheel: round body with radial teeth, a square frame, an inner square ring
     * and a hub, each with its own height. Hollow keeps only a rim of the body plus the teeth.
     */
    private static void gear(ShapeParams p, Set<Cell> out) {
        int r = p.size();
        int teeth = p.getInt("teeth"), toothLength = p.getInt("tooth_length");
        double toothWidth = p.get("tooth_width"), rotation = p.get("tooth_rotation");
        int rimWidth = p.getInt("rim_width");
        int frameSize = p.getInt("frame_size"), frameWidth = p.getInt("frame_width");
        int innerSize = p.getInt("inner_size"), innerWidth = p.getInt("inner_width");
        int hubSize = p.getInt("hub_size");
        int bodyH = p.getInt("body_height"), frameH = p.getInt("frame_height");
        int innerH = p.getInt("inner_height"), hubH = p.getInt("hub_height");
        int toothStart = p.getInt("tooth_start"), toothH = p.getInt("tooth_height");

        double[][] dirs = new double[teeth][];
        for (int t = 0; t < teeth; t++) {
            double a = 2 * Math.PI * (t + rotation) / teeth - Math.PI / 2;
            dirs[t] = new double[]{Math.cos(a), Math.sin(a)};
        }

        int reach = Math.max(Math.max(r + toothLength, frameSize), Math.max(innerSize, hubSize)) + 1;
        for (int u = -reach; u <= reach; u++) {
            for (int v = -reach; v <= reach; v++) {
                double d = Math.hypot(u, v);
                int k = Math.max(Math.abs(u), Math.abs(v)); // square ring index
                boolean inBody = d < r + 0.5 && (!p.hollow() || d >= r + 0.5 - rimWidth);
                boolean solid = inBody && !p.hollow();

                int top = 0; // this column is filled from w = bottom up to top (exclusive), plus teeth
                if (solid && k <= hubSize) top = Math.max(top, hubH);
                if (solid && k <= innerSize && k > innerSize - innerWidth) top = Math.max(top, innerH);
                if (solid && k <= frameSize && k > frameSize - frameWidth) top = Math.max(top, frameH);
                if (inBody) top = Math.max(top, bodyH);
                for (int w = 0; w < top; w++) out.add(new Cell(u, v, w));

                if (inTooth(u, v, dirs, r, toothLength, toothWidth)) {
                    for (int w = toothStart; w < toothStart + toothH; w++) out.add(new Cell(u, v, w));
                }
            }
        }
    }

    private static boolean inTooth(int u, int v, double[][] dirs, int r, int length, double width) {
        for (double[] dir : dirs) {
            double along = u * dir[0] + v * dir[1];
            double across = Math.abs(-u * dir[1] + v * dir[0]);
            if (along >= r - 1 && along <= r + length && across < width / 2) return true;
        }
        return false;
    }

    private static void torus(ShapeParams p, Set<Cell> out) {
        int major = p.size(), tube = p.getInt("tube_radius");
        int reach = major + tube + 1;
        for (int u = -reach; u <= reach; u++)
            for (int v = -reach; v <= reach; v++)
                for (int w = -tube - 1; w <= tube + 1; w++) {
                    double ring = Math.hypot(u, v) - major;
                    if (Math.hypot(ring, w) < tube + 0.5) out.add(new Cell(u, v, w));
                }
    }

    /** Half an elliptical ring standing on v = 0, extruded along w. */
    private static void arch(ShapeParams p, Set<Cell> out) {
        double rx = p.size() + 0.5, ry = p.size() * p.get("height_ratio") + 0.5;
        int thickness = p.getInt("thickness"), depth = p.getInt("depth");
        double ix = rx - thickness, iy = ry - thickness;
        int height = (int) Math.ceil(ry);
        for (int u = -p.size(); u <= p.size(); u++)
            for (int v = 0; v <= height; v++) {
                if (!inEllipse(u, v, rx, ry)) continue;
                if (ix > 0 && iy > 0 && inEllipse(u, v, ix, iy)) continue;
                for (int w = 0; w < depth; w++) out.add(new Cell(u, v, w));
            }
    }

    private static boolean inEllipse(double u, double v, double rx, double ry) {
        return (u * u) / (rx * rx) + (v * v) / (ry * ry) <= 1;
    }

    /** Spiral staircase: a ribbon between the inner and outer radius climbing {@code turns} times, around an optional pole. */
    private static void helix(ShapeParams p, Set<Cell> out) {
        int outer = p.size(), inner = p.getInt("inner_radius"), height = p.getInt("height");
        int thickness = p.getInt("thickness");
        double turns = p.get("turns");
        double risePerTurn = height / turns;
        int pole = p.getInt("pole_radius");
        for (int u = -outer; u <= outer; u++)
            for (int v = -outer; v <= outer; v++) {
                double d = Math.hypot(u, v);
                // Center pole the full height (pole radius 0 = none)
                if (pole > 0 && d < pole + 0.5) {
                    for (int w = 0; w < height; w++) out.add(new Cell(u, v, w));
                    continue;
                }
                if (d > outer + 0.5 || d < inner - 0.5) continue;
                double turn = (Math.atan2(v, u) + Math.PI) / (2 * Math.PI); // 0..1 around the axis
                for (int k = 0; k < Math.ceil(turns); k++) {
                    int step = (int) Math.floor((turn + k) * risePerTurn);
                    if (step >= height) break;
                    for (int w = step; w < Math.min(height, step + thickness); w++) out.add(new Cell(u, v, w));
                }
            }
    }

    private static void ellipsoid(ShapeParams p, Set<Cell> out) {
        double rx = p.size() + 0.5, rv = p.get("radius_z") + 0.5, rw = p.get("radius_y") + 0.5;
        for (int u = -p.size(); u <= p.size(); u++)
            for (int v = (int) -rv; v <= rv; v++)
                for (int w = (int) -rw; w <= rw; w++)
                    if (u * u / (rx * rx) + v * v / (rv * rv) + w * w / (rw * rw) <= 1) out.add(new Cell(u, v, w));
    }

    /** Round (sides = 0) or polygonal cone narrowing to a point at the top. */
    private static void cone(ShapeParams p, Set<Cell> out) {
        int r = p.size(), height = p.getInt("height"), sides = p.getInt("sides");
        for (int w = 0; w < height; w++) {
            double layerRadius = r * (height - w) / (double) height;
            if (sides >= 3) {
                double[][] poly = regularPolygon(sides, layerRadius, 0);
                for (int u = -r; u <= r; u++)
                    for (int v = -r; v <= r; v++)
                        if (inPolygon(u, v, poly)) out.add(new Cell(u, v, w));
            } else {
                for (int u = -r; u <= r; u++)
                    for (int v = -r; v <= r; v++)
                        if (Math.hypot(u, v) < layerRadius + 0.5) out.add(new Cell(u, v, w));
            }
        }
    }

    /** Square pyramid: each layer's half-width shrinks evenly from the base to a point. */
    private static void pyramid(ShapeParams p, Set<Cell> out) {
        int half = p.size(), height = p.getInt("height");
        for (int w = 0; w < height; w++) {
            int h = (int) Math.round(half * (height - 1 - w) / (double) Math.max(1, height - 1));
            for (int u = -h; u <= h; u++)
                for (int v = -h; v <= h; v++) out.add(new Cell(u, v, w));
        }
    }

    /** Paraboloid dish: a shell {@code thickness} blocks thick, {@code depth} deep at the rim. */
    private static void bowl(ShapeParams p, Set<Cell> out) {
        int r = p.size(), depth = p.getInt("depth"), thickness = p.getInt("thickness");
        for (int u = -r; u <= r; u++)
            for (int v = -r; v <= r; v++) {
                if (Math.hypot(u, v) > r + 0.5) continue;
                int surface = bowlSurface(u, v, r, depth);
                // On the steep rim, reach up to the highest neighbor so the wall has no holes
                int top = surface + thickness;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nu = u + n[0], nv = v + n[1];
                    if (Math.hypot(nu, nv) <= r + 0.5) top = Math.max(top, bowlSurface(nu, nv, r, depth));
                }
                for (int w = surface; w < top; w++) out.add(new Cell(u, v, w));
            }
    }

    private static int bowlSurface(int u, int v, int r, int depth) {
        double t = Math.hypot(u, v) / r;
        return (int) Math.round(depth * t * t);
    }

    /** Wheel: a rim, evenly spaced spokes and a square hub. */
    private static void wheel(ShapeParams p, Set<Cell> out) {
        int r = p.size(), rim = p.getInt("wheel_rim"), spokes = p.getInt("spokes");
        double spokeWidth = p.get("spoke_width");
        int hub = p.getInt("hub_size"), thickness = p.getInt("thickness");
        for (int u = -r; u <= r; u++)
            for (int v = -r; v <= r; v++) {
                double d = Math.hypot(u, v);
                if (d > r + 0.5) continue;
                boolean filled = d >= r + 0.5 - rim || Math.max(Math.abs(u), Math.abs(v)) <= hub;
                for (int s = 0; s < spokes && !filled; s++) {
                    double a = 2 * Math.PI * s / spokes - Math.PI / 2;
                    double along = u * Math.cos(a) + v * Math.sin(a);
                    double across = Math.abs(-u * Math.sin(a) + v * Math.cos(a));
                    filled = along >= 0 && across < spokeWidth / 2 + 0.25;
                }
                if (filled) for (int w = 0; w < thickness; w++) out.add(new Cell(u, v, w));
            }
    }

    /** Tower wall with alternating merlons on top; round when sides = 0. */
    private static void tower(ShapeParams p, Set<Cell> out) {
        int r = p.size(), height = p.getInt("height"), sides = p.getInt("sides");
        int wall = p.getInt("wall"), battlement = p.getInt("battlement");
        double[][] outer = sides >= 3 ? regularPolygon(sides, r, 0.5) : null;
        double[][] inner = sides >= 3 && r - wall >= 0 ? regularPolygon(sides, r - wall, 0.5) : null;
        // Merlon + gap about two blocks each along the outside, an even count so it stays symmetric
        int merlons = Math.max(4, 2 * (int) Math.round(Math.PI * r / 2));
        for (int u = -r - 1; u <= r + 1; u++)
            for (int v = -r - 1; v <= r + 1; v++) {
                boolean inWall = sides >= 3
                        ? inPolygon(u, v, outer) && (inner == null || !inPolygon(u, v, inner))
                        : Math.hypot(u, v) < r + 0.5 && Math.hypot(u, v) >= r + 0.5 - wall;
                if (!inWall) continue;
                double turn = (Math.atan2(v, u) + Math.PI) / (2 * Math.PI);
                boolean merlon = ((int) Math.floor(turn * merlons)) % 2 == 0;
                int top = height + (merlon ? battlement : 0);
                for (int w = 0; w < top; w++) out.add(new Cell(u, v, w));
            }
    }

    /** Archimedean spiral arms from the center out to the size, traced so each arm is unbroken. */
    private static void spiral(ShapeParams p, Set<Cell> out) {
        int r = p.size(), arms = p.getInt("arms"), height = p.getInt("height");
        double turns = p.get("turns"), reach = p.get("arm_width") / 2;
        Set<Cell> face = new HashSet<>();
        for (int k = 0; k < arms; k++) {
            // Small steps along the curve; stamp every block within half the arm width of it
            for (double t = 0; t <= r; t += 0.05) {
                double a = 2 * Math.PI * (turns * t / r + (double) k / arms);
                double cu = t * Math.cos(a), cv = t * Math.sin(a);
                for (int u = (int) Math.floor(cu - reach); u <= Math.ceil(cu + reach); u++)
                    for (int v = (int) Math.floor(cv - reach); v <= Math.ceil(cv + reach); v++)
                        if (Math.hypot(u - cu, v - cv) <= Math.max(reach, 0.5)) face.add(new Cell(u, v, 0));
            }
        }
        for (Cell c : face) for (int w = 0; w < height; w++) out.add(new Cell(c.x(), c.y(), w));
    }

    /** A polygon extruded {@code height} blocks; hollow keeps only its outline (open top and bottom). */
    private static void extrudedPolygon(double[][] poly, int height, boolean hollow, Set<Cell> out) {
        int reach = 1;
        for (double[] pt : poly) reach = Math.max(reach, (int) Math.ceil(Math.max(Math.abs(pt[0]), Math.abs(pt[1]))) + 1);
        Set<Cell> face = new HashSet<>();
        for (int u = -reach; u <= reach; u++)
            for (int v = -reach; v <= reach; v++)
                if (inPolygon(u, v, poly)) face.add(new Cell(u, v, 0));
        for (Cell c : face) {
            if (hollow && face.contains(new Cell(c.x() + 1, c.y(), 0)) && face.contains(new Cell(c.x() - 1, c.y(), 0))
                    && face.contains(new Cell(c.x(), c.y() + 1, 0)) && face.contains(new Cell(c.x(), c.y() - 1, 0))) continue;
            for (int w = 0; w < height; w++) out.add(new Cell(c.x(), c.y(), w));
        }
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /** Vertices of a regular polygon; {@code rotation} is a fraction of one side's angle. */
    static double[][] regularPolygon(int sides, double radius, double rotation) {
        double[][] pts = new double[sides][];
        for (int i = 0; i < sides; i++) {
            double a = 2 * Math.PI * (i + rotation) / sides - Math.PI / 2;
            // + 0.5 so edge blocks whose centers sit on the boundary are included
            pts[i] = new double[]{(radius + 0.5) * Math.cos(a), (radius + 0.5) * Math.sin(a)};
        }
        return pts;
    }

    private static double[][] star(ShapeParams p) {
        int points = p.getInt("points");
        double outer = p.size() + 0.5, inner = outer * p.get("inner_ratio");
        double[][] pts = new double[points * 2][];
        for (int i = 0; i < points * 2; i++) {
            double a = Math.PI * (i + 2 * p.get("rotation")) / points - Math.PI / 2;
            double r = i % 2 == 0 ? outer : inner;
            pts[i] = new double[]{r * Math.cos(a), r * Math.sin(a)};
        }
        return pts;
    }

    /** Even-odd ray cast against the block's center. */
    static boolean inPolygon(double x, double y, double[][] poly) {
        boolean inside = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    /** Shapes whose hollow form is a 3D shell; the others handle hollow themselves. */
    private static boolean shellIn3D(ShapeType type) {
        return type == ShapeType.TORUS || type == ShapeType.ELLIPSOID || type == ShapeType.CONE
                || type == ShapeType.PYRAMID;
    }

    /** Keeps the cells with at least one empty face neighbor. */
    static Set<Cell> shell(Set<Cell> solid) {
        Set<Cell> out = new HashSet<>();
        for (Cell c : solid) {
            int x = c.x(), y = c.y(), z = c.z();
            if (!solid.contains(new Cell(x + 1, y, z)) || !solid.contains(new Cell(x - 1, y, z))
                    || !solid.contains(new Cell(x, y + 1, z)) || !solid.contains(new Cell(x, y - 1, z))
                    || !solid.contains(new Cell(x, y, z + 1)) || !solid.contains(new Cell(x, y, z - 1))) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Maps local (u, v, w) cells to world offsets and anchors them: lowest block at y = 0, and
     * when the depth axis lies horizontally it is centered on the anchor.
     */
    static List<Cell> orient(Set<Cell> local, ShapeParams.Orientation orientation) {
        if (local.isEmpty()) return List.of();
        int minW = Integer.MAX_VALUE, maxW = Integer.MIN_VALUE;
        for (Cell c : local) {
            minW = Math.min(minW, c.z());
            maxW = Math.max(maxW, c.z());
        }
        int midW = Math.floorDiv(minW + maxW, 2);

        List<Cell> world = new ArrayList<>(local.size());
        for (Cell c : local) {
            int u = c.x(), v = c.y(), w = c.z();
            world.add(switch (orientation) {
                case FLAT -> new Cell(u, w, v);
                // Upright: the face plane stands up, v pointing up
                case UPRIGHT_NS -> new Cell(u, v, w - midW);
                case UPRIGHT_EW -> new Cell(w - midW, v, u);
            });
        }
        int minY = Integer.MAX_VALUE;
        for (Cell c : world) minY = Math.min(minY, c.y());

        List<Cell> anchored = new ArrayList<>(world.size());
        for (Cell c : world) anchored.add(new Cell(c.x(), c.y() - minY, c.z()));
        return anchored;
    }

    /** Drops cells outside the size limit (a span of at most maxAxis blocks, centered on the anchor) and sorts bottom up. */
    private static List<Cell> clip(Collection<Cell> cells, int maxAxis) {
        int half = (maxAxis - 1) / 2;
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) {
            if (Math.abs(c.x()) <= half && c.y() >= -half && c.y() < maxAxis && Math.abs(c.z()) <= half) out.add(c);
        }
        out.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        return out;
    }
}

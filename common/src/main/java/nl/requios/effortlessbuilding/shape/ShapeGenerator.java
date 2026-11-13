package nl.requios.effortlessbuilding.shape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
        return new ArrayList<>(generateLabeled(p, maxAxis, schematics).keySet());
    }

    /** Label of cells that come from the main shape (parts are labelled with their index). */
    public static final int MAIN = -1;

    /**
     * Like {@link #generate}, also telling which shape each cell comes from: {@link #MAIN} or the index of
     * the part that put it there (a part that joins on owns the blocks it covers, so it can have its own block).
     */
    public static LinkedHashMap<Cell, Integer> generateLabeled(ShapeParams p, int maxAxis, Function<String, List<Cell>> schematics) {
        Collection<Cell> main = single(p, schematics);
        Map<Cell, Integer> result = new HashMap<>();
        for (Cell c : main) result.put(c, MAIN);
        int axis = depthAxis(p.orientation());
        double[] pivot = center(main);
        for (int i = 0; i < p.parts().size(); i++) {
            ShapeParams.Part part = p.parts().get(i);
            Set<Cell> placed = new HashSet<>();
            // Each part turns on its own before it is placed at its offset
            for (Cell c : rotate(single(part.shape(), schematics), part.shape())) {
                placed.add(new Cell(c.x() + part.x(), c.y() + part.y(), c.z() + part.z()));
            }
            Set<Cell> other = repeatAround(placed, part.repeat(), axis, pivot);
            switch (part.operation()) {
                case UNITE -> { for (Cell c : other) result.put(c, i); }
                case SUBTRACT -> result.keySet().removeAll(other);
                case INTERSECT -> result.keySet().retainAll(other);
                case EXCLUDE -> {
                    for (Cell c : other) {
                        if (result.remove(c) == null) result.put(c, i);
                    }
                }
            }
        }
        // The main shape's rotation turns the whole combination
        return clip(rotateLabeled(result, p.get(ShapeType.ROTATE_X), p.get(ShapeType.ROTATE_Y), p.get(ShapeType.ROTATE_Z)), maxAxis);
    }

    /** The axis a shape's face looks along: up for flat shapes, through the face for upright ones (0 x, 1 y, 2 z). */
    static int depthAxis(ShapeParams.Orientation orientation) {
        return switch (orientation) { case FLAT -> 1; case UPRIGHT_NS -> 2; case UPRIGHT_EW -> 0; };
    }

    /** Middle of the cells' bounding box (half-block values for even spans). */
    private static double[] center(Collection<Cell> cells) {
        if (cells.isEmpty()) return new double[3];
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a]); }
        }
        return new double[]{(lo[0] + hi[0]) / 2.0, (lo[1] + hi[1]) / 2.0, (lo[2] + hi[2]) / 2.0};
    }

    /**
     * The cells plus {@code count - 1} copies turned evenly around the given axis through the pivot
     * (a part repeated around the main shape: spokes, pillars, teeth). Copies are found by inverse
     * mapping, like {@link #rotate}, so turned copies stay solid.
     */
    static Set<Cell> repeatAround(Set<Cell> cells, int count, int axis, double[] pivot) {
        if (count <= 1 || cells.isEmpty()) return cells;
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        Set<Cell> out = new HashSet<>(cells);
        for (int k = 1; k < count; k++) {
            double angle = Math.toRadians(360.0 * k / count);
            double cos = Math.cos(angle), sin = Math.sin(angle);
            if (Math.abs(cos) < 1e-9) cos = 0;
            if (Math.abs(sin) < 1e-9) sin = 0;
            Set<Cell> candidates = new HashSet<>();
            for (Cell c : cells) {
                int[] v = {c.x(), c.y(), c.z()};
                double da = v[a] - pivot[a], db = v[b] - pivot[b];
                int ta = (int) Math.round(pivot[a] + da * cos - db * sin), tb = (int) Math.round(pivot[b] + da * sin + db * cos);
                for (int ea = -1; ea <= 1; ea++)
                    for (int eb = -1; eb <= 1; eb++) {
                        int[] t = v.clone();
                        t[a] = ta + ea;
                        t[b] = tb + eb;
                        candidates.add(new Cell(t[0], t[1], t[2]));
                    }
            }
            for (Cell t : candidates) {
                int[] v = {t.x(), t.y(), t.z()};
                double da = v[a] - pivot[a], db = v[b] - pivot[b];
                int[] src = v.clone();
                src[a] = (int) Math.round(pivot[a] + da * cos + db * sin); // turned back
                src[b] = (int) Math.round(pivot[b] - da * sin + db * cos);
                if (cells.contains(new Cell(src[0], src[1], src[2]))) out.add(t);
            }
        }
        return out;
    }

    /**
     * A center axle through the finished shape, along its depth axis (up for flat shapes, through the face
     * for upright ones) and as long as the shape is deep. One block thick, or 2 by 2 when the shape has an
     * even width, so it stays centered. Filled even where the shape is open, like a hollow gear's middle.
     */
    public static List<Cell> centerAxis(ShapeParams.Orientation orientation, Collection<Cell> cells) {
        if (cells.isEmpty()) return List.of();
        int axis = depthAxis(orientation);
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a]); }
        }
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        List<Cell> out = new ArrayList<>();
        for (int va : middle(lo[a], hi[a]))
            for (int vb : middle(lo[b], hi[b]))
                for (int t = lo[axis]; t <= hi[axis]; t++) {
                    int[] v = new int[3];
                    v[axis] = t;
                    v[a] = va;
                    v[b] = vb;
                    out.add(new Cell(v[0], v[1], v[2]));
                }
        return out;
    }

    /** The middle block of a span, or the two middle blocks when the span is even. */
    private static int[] middle(int lo, int hi) {
        int sum = lo + hi;
        return sum % 2 == 0 ? new int[]{sum / 2} : new int[]{Math.floorDiv(sum, 2), Math.floorDiv(sum, 2) + 1};
    }

    private static Collection<Cell> rotate(Collection<Cell> cells, ShapeParams p) {
        return rotate(cells, p.get(ShapeType.ROTATE_X), p.get(ShapeType.ROTATE_Y), p.get(ShapeType.ROTATE_Z));
    }

    /**
     * Turns cells around their bounding-box center by the given degrees (X, then Y, then Z), keeping
     * the lowest layer where it was so the shape still stands on its anchor.
     * <p>
     * Every block of the result is looked up in the original shape (inverse mapping), so rotated shapes
     * stay solid instead of getting holes. Quarter turns are exact; other angles also count a block when
     * enough of its corners fall inside the original, which keeps thin walls closed.
     */
    static Collection<Cell> rotate(Collection<Cell> cells, double rx, double ry, double rz) {
        if (cells.isEmpty() || (rx % 360 == 0 && ry % 360 == 0 && rz % 360 == 0)) return cells;
        Map<Cell, Integer> labeled = new HashMap<>();
        for (Cell c : cells) labeled.put(c, MAIN);
        return rotateLabeled(labeled, rx, ry, rz).keySet();
    }

    /** {@link #rotate} keeping each cell's label (taken from the original block it maps back to). */
    static Map<Cell, Integer> rotateLabeled(Map<Cell, Integer> cells, double rx, double ry, double rz) {
        if (cells.isEmpty() || (rx % 360 == 0 && ry % 360 == 0 && rz % 360 == 0)) return cells;
        double[][] m = rotationMatrix(rx, ry, rz);
        boolean exact = rx % 90 == 0 && ry % 90 == 0 && rz % 90 == 0;

        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells.keySet()) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], v[a]); hi[a] = Math.max(hi[a], v[a]); }
        }
        // A whole-block pivot keeps quarter turns exact (no half-block rounding)
        double[] center = {Math.floorDiv(lo[0] + hi[0], 2), Math.floorDiv(lo[1] + hi[1], 2), Math.floorDiv(lo[2] + hi[2], 2)};
        Map<Cell, Integer> source = cells;

        // Only blocks near where some original block lands can be part of the result
        int reach = exact ? 0 : 1;
        Set<Cell> candidates = new HashSet<>();
        for (Cell c : cells.keySet()) {
            double[] q = transform(m, c.x() - center[0], c.y() - center[1], c.z() - center[2], false);
            int x = (int) Math.round(q[0] + center[0]), y = (int) Math.round(q[1] + center[1]), z = (int) Math.round(q[2] + center[2]);
            for (int dx = -reach; dx <= reach; dx++)
                for (int dy = -reach; dy <= reach; dy++)
                    for (int dz = -reach; dz <= reach; dz++) candidates.add(new Cell(x + dx, y + dy, z + dz));
        }

        Map<Cell, Integer> out = new HashMap<>();
        for (Cell t : candidates) {
            Integer label = labelAt(source, m, center, t.x(), t.y(), t.z());
            if (label != null) {
                out.put(t, label);
            } else if (!exact) {
                int hits = 0;
                Integer first = null;
                for (int corner = 0; corner < 8; corner++) {
                    double ox = (corner & 1) == 0 ? -0.3 : 0.3, oy = (corner & 2) == 0 ? -0.3 : 0.3, oz = (corner & 4) == 0 ? -0.3 : 0.3;
                    Integer l = labelAt(source, m, center, t.x() + ox, t.y() + oy, t.z() + oz);
                    if (l != null) {
                        hits++;
                        if (first == null) first = l;
                    }
                }
                if (hits >= 3) out.put(t, first);
            }
        }
        if (out.isEmpty()) return out;

        int minY = Integer.MAX_VALUE;
        for (Cell c : out.keySet()) minY = Math.min(minY, c.y());
        int shift = lo[1] - minY;
        Map<Cell, Integer> shifted = new HashMap<>(out.size() * 2);
        out.forEach((c, l) -> shifted.put(new Cell(c.x(), c.y() + shift, c.z()), l));
        return shifted;
    }

    /** The label of the original block the point falls in when turned back into the original frame, or null. */
    private static Integer labelAt(Map<Cell, Integer> source, double[][] m, double[] center, double x, double y, double z) {
        double[] s = transform(m, x - center[0], y - center[1], z - center[2], true);
        return source.get(new Cell((int) Math.round(s[0] + center[0]), (int) Math.round(s[1] + center[1]),
                (int) Math.round(s[2] + center[2])));
    }

    /** R = Rz * Ry * Rx for angles in degrees. */
    private static double[][] rotationMatrix(double rx, double ry, double rz) {
        double[][] x = {{1, 0, 0}, {0, cos(rx), -sin(rx)}, {0, sin(rx), cos(rx)}};
        double[][] y = {{cos(ry), 0, sin(ry)}, {0, 1, 0}, {-sin(ry), 0, cos(ry)}};
        double[][] z = {{cos(rz), -sin(rz), 0}, {sin(rz), cos(rz), 0}, {0, 0, 1}};
        return multiply(z, multiply(y, x));
    }

    /** m * v, or the inverse (the transpose, since m is a rotation). */
    private static double[] transform(double[][] m, double x, double y, double z, boolean inverse) {
        double[] v = {x, y, z}, out = new double[3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out[i] += (inverse ? m[j][i] : m[i][j]) * v[j];
        return out;
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                for (int k = 0; k < 3; k++) out[i][j] += a[i][k] * b[k][j];
        return out;
    }

    // Exact values at quarter turns keep 90 degree rotations free of rounding error
    private static double cos(double degrees) {
        double d = ((degrees % 360) + 360) % 360;
        if (d == 0) return 1;
        if (d == 90 || d == 270) return 0;
        if (d == 180) return -1;
        return Math.cos(Math.toRadians(degrees));
    }

    private static double sin(double degrees) {
        double d = ((degrees % 360) + 360) % 360;
        if (d == 0 || d == 180) return 0;
        if (d == 90) return 1;
        if (d == 270) return -1;
        return Math.sin(Math.toRadians(degrees));
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
            case PRISM -> extrudedPolygon(regularPolygon(p.getInt("sides"), p.size(), p.get("rotation")), p.getInt("height"), p.hollow(),
                    p.getInt("caps") == 1, local);
            case STAR -> extrudedPolygon(star(p), p.getInt("height"), p.hollow(), p.getInt("caps") == 1, local);
            case CONE -> cone(p, local);
            case PYRAMID -> pyramid(p, local);
            case BOWL -> bowl(p, local);
            case WHEEL -> wheel(p, local);
            case TOWER -> tower(p, local);
            case SPIRAL -> spiral(p, local);
            case TEXT -> text(p, local);
            case FLOOR -> floor(p, local);
            case DOME -> dome(p, local);
            case STAIRS -> stairs(p, local);
            case PIPE -> pipe(p, local);
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
        // Each part covers layers [start, end): its height, aligned against the tallest part
        // (bottom, center or top), then shifted by its own start offset
        int bodyH = p.getInt("body_height"), frameH = p.getInt("frame_height");
        int innerH = p.getInt("inner_height"), hubH = p.getInt("hub_height"), toothH = p.getInt("tooth_height");
        int tallest = Math.max(Math.max(bodyH, frameH), Math.max(Math.max(innerH, hubH), toothH));
        int align = p.getInt("align");
        int[] body = layers(bodyH, p.getInt("body_start"), tallest, align);
        int[] frame = layers(frameH, p.getInt("frame_start"), tallest, align);
        int[] inner = layers(innerH, p.getInt("inner_start"), tallest, align);
        int[] hub = layers(hubH, p.getInt("hub_start"), tallest, align);
        int[] tooth = layers(toothH, p.getInt("tooth_start"), tallest, align);

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

                if (solid && k <= hubSize) addLayers(out, u, v, hub);
                if (solid && k <= innerSize && k > innerSize - innerWidth) addLayers(out, u, v, inner);
                if (solid && k <= frameSize && k > frameSize - frameWidth) addLayers(out, u, v, frame);
                if (inBody) addLayers(out, u, v, body);
                if (inTooth(u, v, dirs, r, toothLength, toothWidth)) addLayers(out, u, v, tooth);
            }
        }
    }

    /** Layers [start, end) of a part: aligned to the bottom (0), center (1) or top (2) of the tallest part, then offset. */
    private static int[] layers(int height, int offset, int tallest, int align) {
        int base = switch (align) {
            case 1 -> Math.floorDiv(tallest - height, 2);
            case 2 -> tallest - height;
            default -> 0;
        };
        return new int[]{base + offset, base + offset + height};
    }

    private static void addLayers(Set<Cell> out, int u, int v, int[] range) {
        for (int w = range[0]; w < range[1]; w++) out.add(new Cell(u, v, w));
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
        boolean pointed = p.getInt("style") == 1;
        double ratio = p.get("height_ratio");
        double ix = rx - thickness, iy = ry - thickness;
        // A pointed arch is two circular arcs, each centered on the opposite springing point
        int height = (int) Math.ceil(pointed ? Math.sqrt(3) * rx * ratio + 1 : ry);
        for (int u = -p.size(); u <= p.size(); u++)
            for (int v = 0; v <= height; v++) {
                if (pointed) {
                    if (!inPointedArch(u, v, rx, ratio)) continue;
                    if (ix > 0 && inPointedArch(u, v, ix, ratio)) continue;
                } else {
                    if (!inEllipse(u, v, rx, ry)) continue;
                    if (ix > 0 && iy > 0 && inEllipse(u, v, ix, iy)) continue;
                }
                for (int w = 0; w < depth; w++) out.add(new Cell(u, v, w));
            }
    }

    /** Inside both circles of radius 2a centered on (+a, 0) and (-a, 0): the equilateral pointed arch, stretched by {@code ratio}. */
    private static boolean inPointedArch(double u, double v, double a, double ratio) {
        double vv = v / ratio, r2 = 4 * a * a;
        return (u - a) * (u - a) + vv * vv <= r2 && (u + a) * (u + a) + vv * vv <= r2;
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

    /**
     * Tower wall with alternating merlons on top, or a cone roof; round when sides = 0. Optionally a door
     * on the -v side, windows spread around the wall and interior floors.
     */
    private static void tower(ShapeParams p, Set<Cell> out) {
        int r = p.size(), height = p.getInt("height"), sides = p.getInt("sides");
        int wall = p.getInt("wall"), battlement = p.getInt("battlement");
        boolean door = p.getInt("door") == 1, roof = p.getInt("roof") == 1;
        int windows = p.getInt("windows"), floors = p.getInt("floors");
        double[][] outer = sides >= 3 ? regularPolygon(sides, r, 0.5) : null;
        double[][] inner = sides >= 3 && r - wall >= 0 ? regularPolygon(sides, r - wall, 0.5) : null;
        // Merlon + gap about two blocks each along the outside, an even count so it stays symmetric
        int merlons = Math.max(4, 2 * (int) Math.round(Math.PI * r / 2));
        int doorHalf = r >= 5 ? 1 : 0, doorHeight = height >= 5 ? 3 : 2;
        int windowBase = Math.max(2, height / 2);
        boolean hasWindows = windows > 0 && windowBase + 2 <= height - 1;
        int[] floorLayers = new int[floors];
        for (int f = 0; f < floors; f++) floorLayers[f] = Math.max(1, Math.round((f + 1) * height / (float) (floors + 1)));

        for (int u = -r - 1; u <= r + 1; u++)
            for (int v = -r - 1; v <= r + 1; v++) {
                boolean inOuter = sides >= 3 ? inPolygon(u, v, outer) : Math.hypot(u, v) < r + 0.5;
                boolean inWall = sides >= 3
                        ? inOuter && (inner == null || !inPolygon(u, v, inner))
                        : inOuter && Math.hypot(u, v) >= r + 0.5 - wall;
                if (!inOuter) continue;
                if (!inWall) {
                    for (int layer : floorLayers) out.add(new Cell(u, v, layer));
                    continue;
                }
                double turn = (Math.atan2(v, u) + Math.PI) / (2 * Math.PI);
                boolean merlon = ((int) Math.floor(turn * merlons)) % 2 == 0;
                int top = height + (!roof && merlon ? battlement : 0);
                boolean doorColumn = door && v < 0 && Math.abs(u) <= doorHalf;
                boolean windowColumn = false;
                if (hasWindows) {
                    // Windows sit between the door (at a quarter turn) and each other
                    for (int k = 0; k < windows && !windowColumn; k++) {
                        double centre = 0.25 + (k + 0.5) / windows;
                        double apart = Math.abs(((turn - centre) % 1 + 1.5) % 1 - 0.5);
                        // Within three quarters of a block of the window's centre line: one column, or two on a diagonal
                        windowColumn = Math.hypot(u, v) * Math.sin(apart * 2 * Math.PI) <= 0.75 && apart < 0.25;
                    }
                }
                for (int w = 0; w < top; w++) {
                    if (doorColumn && w < doorHeight) continue;
                    if (windowColumn && w >= windowBase && w < windowBase + 2) continue;
                    out.add(new Cell(u, v, w));
                }
            }

        if (roof) {
            // Cone cap, one block wider than the wall all round so it overhangs
            int roofHeight = Math.max(2, r + 1);
            for (int k = 0; k < roofHeight; k++) {
                double radius = (r + 1) * (roofHeight - k) / (double) roofHeight;
                double[][] poly = sides >= 3 ? regularPolygon(sides, radius, 0.5) : null;
                for (int u = -r - 1; u <= r + 1; u++)
                    for (int v = -r - 1; v <= r + 1; v++) {
                        boolean in = poly != null ? inPolygon(u, v, poly) : Math.hypot(u, v) < radius + 0.5;
                        if (in) out.add(new Cell(u, v, height + k));
                    }
            }
        }
    }

    /** Rectangle-ish floor: an outline picks the footprint, a tile pattern picks which blocks of it stay. */
    private static void floor(ShapeParams p, Set<Cell> out) {
        int rx = p.size(), ry = p.getInt("length"), thickness = p.getInt("thickness");
        int outline = p.getInt("outline"), pattern = p.getInt("pattern"), tile = p.getInt("tile");
        boolean invert = p.getInt("invert") == 1;
        for (int u = -rx; u <= rx; u++)
            for (int v = -ry; v <= ry; v++) {
                if (!inOutline(outline, u, v, rx, ry)) continue;
                if (floorPattern(pattern, outline, u, v, rx, ry, tile) == invert) continue;
                for (int w = 0; w < thickness; w++) out.add(new Cell(u, v, w));
            }
    }

    /** Whether (u, v) is inside the outline of half-extents rx, ry (outline index as in {@code ShapeType.FLOOR}). */
    private static boolean inOutline(int outline, int u, int v, double rx, double ry) {
        if (rx < 0 || ry < 0 || Math.abs(u) > rx || Math.abs(v) > ry) return false;
        double nu = Math.abs(u) / (rx + 0.5), nv = Math.abs(v) / (ry + 0.5);
        return switch (outline) {
            case 1 -> Math.pow(nu, 4) + Math.pow(nv, 4) <= 1;        // rounded
            case 2 -> nu * nu + nv * nv <= 1;                         // ellipse
            case 3 -> nu + nv <= 1;                                   // diamond
            case 4 -> nu + nv <= 1.5;                                 // octagon
            case 5 -> nv <= 1 && nu + nv * 0.5 <= 1;                  // hexagon
            case 6 -> nu <= 0.34 || nv <= 0.34;                       // plus
            case 7 -> {                                               // star, five tips
                double rho = Math.hypot(u / (rx + 0.5), v / (ry + 0.5)), phi = Math.atan2(v / (ry + 0.5), u / (rx + 0.5));
                double tip = (1 + Math.cos(5 * (phi - Math.PI / 2))) / 2; // 1 at the five tips, 0 between them
                yield rho <= 0.4 + 0.6 * Math.pow(tip, 1.5);
            }
            case 8 -> {                                               // heart, point down
                double hx = u / (rx + 0.5) * 1.14, hy = v / (ry + 0.5) * 1.135 + 0.135;
                double t = hx * hx + hy * hy - 1;
                yield t * t * t - hx * hx * hy * hy * hy <= 0;
            }
            case 9 -> nu <= (1 - v / (ry + 0.5)) / 2 + 0.02;          // triangle, point up
            default -> true;                                          // rectangle
        };
    }

    /** True for the blocks the pattern keeps. Tiles are counted from the floor's corner so both sides match. */
    private static boolean floorPattern(int pattern, int outline, int u, int v, int rx, int ry, int tile) {
        int a = u + rx, b = v + ry; // from the corner, never negative
        return switch (pattern) {
            case 1 -> (Math.floorDiv(a, tile) + Math.floorDiv(b, tile)) % 2 == 0;           // checker
            case 2 -> Math.floorDiv(a, tile) % 2 == 0;                                       // stripes
            case 3 -> Math.floorDiv(b, tile) % 2 == 0;                                       // rows
            case 4 -> Math.floorDiv(a + b, tile) % 2 == 0;                                   // diagonal
            case 5 -> !inOutline(outline, u, v, rx - tile, ry - tile);                       // border
            case 6 -> a % (tile + 1) == 0 || b % (tile + 1) == 0;                            // grid lines
            case 7 -> {                                                                      // bricks, running bond
                int row = Math.floorDiv(b, tile);
                yield (Math.floorDiv(a + (row % 2) * tile, tile * 2) + row) % 2 == 0;
            }
            case 8 -> (Math.floorDiv(a + b, tile) + Math.floorDiv(a - b, tile)) % 2 == 0;    // diamonds
            case 9 -> a % (tile * 2) < tile && b % (tile * 2) < tile;                        // dots
            case 10 -> (int) Math.floor(Math.hypot(u, v) / tile) % 2 == 0;                   // rings
            case 11 -> {                                                                     // chevron: diagonal bands that flip every two rows
                int ca = Math.floorDiv(a, tile), cb = Math.floorDiv(b, tile);
                int lean = Math.floorMod(Math.floorDiv(cb, 2), 2) == 0 ? cb : -cb;
                yield Math.floorMod(ca + lean, 4) < 2;
            }
            case 12 -> {                                                                     // basketweave: 2 x 2 groups of alternating direction
                int ca = Math.floorDiv(a, tile), cb = Math.floorDiv(b, tile);
                boolean horizontal = Math.floorMod(Math.floorDiv(ca, 2) + Math.floorDiv(cb, 2), 2) == 0;
                yield horizontal ? Math.floorMod(cb, 2) == 0 : Math.floorMod(ca, 2) == 0;
            }
            case 13 -> Math.floorMod(Math.floorDiv(b + (int) Math.round(Math.sin(a / (tile * 2.0)) * tile), tile), 2) == 0; // wave
            case 14 -> Math.floorMod((int) Math.floor(Math.hypot(u, v) / tile - Math.atan2(v, u) / (2 * Math.PI)), 2) == 0; // spiral
            case 15 -> {                                                                     // scatter: about three tiles in four, hashed so it never changes
                int h = Math.floorDiv(a, tile) * 374761393 + Math.floorDiv(b, tile) * 668265263;
                h = (h ^ (h >>> 13)) * 1274126177;
                h ^= h >>> 16;
                yield ((h >>> 4) & 3) != 0;
            }
            default -> true;                                                                 // solid
        };
    }

    /** Half an ellipsoid: base radius {@code size}, height {@code size * height_ratio}; hollow is a shell {@code wall} thick with an open base. */
    private static void dome(ShapeParams p, Set<Cell> out) {
        int r = p.size(), wall = p.getInt("wall");
        double rx = r + 0.5, rh = r * p.get("height_ratio") + 0.5;
        boolean flip = p.getInt("flip") == 1;
        double ix = rx - wall, ih = rh - wall;
        for (int u = -r; u <= r; u++)
            for (int v = -r; v <= r; v++)
                for (int w = 0; w <= (int) rh; w++) {
                    double d2 = u * u + v * v;
                    if (d2 / (rx * rx) + w * w / (rh * rh) > 1) continue;
                    if (p.hollow() && ix > 0 && ih > 0 && d2 / (ix * ix) + w * w / (ih * ih) <= 1) continue;
                    out.add(new Cell(u, v, flip ? -w : w));
                }
    }

    /**
     * A flight of steps climbing along +v, {@code rise} up and {@code run} forward per step, as wide as the
     * shape. Turning flights split the steps in two around a landing and leave it left, right or back beside
     * the first flight. Solid steps fill down to the ground; the other kind is a single block per step.
     */
    private static void stairs(ShapeParams p, Set<Cell> out) {
        int a = p.size(), width = 2 * a + 1;
        int steps = p.getInt("steps"), rise = p.getInt("rise"), run = p.getInt("run");
        int turn = p.getInt("turn");
        boolean solid = p.getInt("fill") == 0;
        int first = turn == 0 ? steps : Math.max(1, steps / 2);
        for (int i = 0; i < first; i++) stairBox(out, -a, a, i * run, (i + 1) * run - 1, (i + 1) * rise, solid);
        if (turn == 0) return;

        int landV0 = first * run, landV1 = landV0 + width - 1;
        stairBox(out, -a, turn == 3 ? a + width : a, landV0, landV1, first * rise, solid);
        for (int j = 0; j < steps - first; j++) {
            int top = (first + 1 + j) * rise;
            switch (turn) {
                case 1 -> stairBox(out, -a - (j + 1) * run, -a - 1 - j * run, landV0, landV1, top, solid);
                case 2 -> stairBox(out, a + 1 + j * run, a + (j + 1) * run, landV0, landV1, top, solid);
                default -> stairBox(out, a + 1, a + width, landV0 - (j + 1) * run, landV0 - 1 - j * run, top, solid);
            }
        }
    }

    private static void stairBox(Set<Cell> out, int u0, int u1, int v0, int v1, int top, boolean solid) {
        for (int u = u0; u <= u1; u++)
            for (int v = v0; v <= v1; v++)
                for (int w = solid ? 0 : top - 1; w < top; w++) out.add(new Cell(u, v, w));
    }

    /**
     * A tube of outer radius {@code size} with a wall, {@code pipe_length} long along w. The elbow turns it
     * 90 degrees toward +u on a bend of {@code bend_radius} (at least the tube radius + 1) into an arm
     * {@code pipe_arm} long. Both ends are open.
     */
    private static void pipe(ShapeParams p, Set<Cell> out) {
        int r = p.size(), wall = p.getInt("wall"), length = p.getInt("pipe_length");
        boolean elbow = p.getInt("bend") == 1;
        int bend = Math.max(p.getInt("bend_radius"), r + 1), arm = p.getInt("pipe_arm");
        double outer = r + 0.5, inner = r + 0.5 - wall;
        int maxU = elbow ? bend + arm : 0, maxW = length + (elbow ? bend : 0);
        for (int u = -r - 1; u <= maxU + r + 1; u++)
            for (int v = -r - 1; v <= r + 1; v++)
                for (int w = 0; w <= maxW + r + 1; w++) {
                    double t;
                    if (w < length) {
                        t = Math.hypot(u, v);                                   // first leg, up the w axis
                    } else if (!elbow) {
                        continue;
                    } else if (u < bend) {
                        double ring = Math.hypot(u - bend, w - length) - bend;  // quarter turn around (bend, length)
                        t = Math.hypot(ring, v);
                    } else if (u < bend + arm) {
                        t = Math.hypot(v, w - length - bend);                   // arm, along +u
                    } else {
                        continue;
                    }
                    if (t < outer && t >= inner) out.add(new Cell(u, v, w));
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

    /** A polygon extruded {@code height} blocks; hollow keeps only its outline (open top and bottom unless {@code closedCaps}). */
    private static void extrudedPolygon(double[][] poly, int height, boolean hollow, boolean closedCaps, Set<Cell> out) {
        int reach = 1;
        for (double[] pt : poly) reach = Math.max(reach, (int) Math.ceil(Math.max(Math.abs(pt[0]), Math.abs(pt[1]))) + 1);
        Set<Cell> face = new HashSet<>();
        for (int u = -reach; u <= reach; u++)
            for (int v = -reach; v <= reach; v++)
                if (inPolygon(u, v, poly)) face.add(new Cell(u, v, 0));
        for (Cell c : face) {
            boolean interior = hollow && face.contains(new Cell(c.x() + 1, c.y(), 0)) && face.contains(new Cell(c.x() - 1, c.y(), 0))
                    && face.contains(new Cell(c.x(), c.y() + 1, 0)) && face.contains(new Cell(c.x(), c.y() - 1, 0));
            for (int w = 0; w < height; w++) {
                // The inside of a hollow prism stays empty except for the floor and ceiling of a closed one
                if (interior && !(closedCaps && (w == 0 || w == height - 1))) continue;
                out.add(new Cell(c.x(), c.y(), w));
            }
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

    /**
     * Block letters from {@link PixelFont}: each pixel a scale x scale square, depth blocks thick, lines
     * split at '|' and each line centered. Bold widens every stroke by a pixel.
     */
    private static void text(ShapeParams p, Set<Cell> out) {
        int scale = p.getInt("scale"), depth = p.getInt("depth"), spacing = p.getInt("letter_spacing");
        boolean bold = p.getInt("bold") == 1;
        String[] lines = p.text().split("\\|", -1);
        int lineHeight = PixelFont.HEIGHT + 2;
        for (int line = 0; line < lines.length; line++) {
            String text = lines[line].length() > 64 ? lines[line].substring(0, 64) : lines[line];
            // Pixels of this line: (column, row from the bottom)
            List<int[]> pixels = new ArrayList<>();
            int cursor = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == ' ') { cursor += PixelFont.SPACE + spacing; continue; }
                String[] rows = PixelFont.glyph(ch);
                for (int r = 0; r < rows.length; r++)
                    for (int col = 0; col < rows[r].length(); col++) {
                        if (rows[r].charAt(col) != '1') continue;
                        pixels.add(new int[]{cursor + col, PixelFont.HEIGHT - 1 - r});
                        if (bold) pixels.add(new int[]{cursor + col + 1, PixelFont.HEIGHT - 1 - r});
                    }
                cursor += rows[0].length() + (bold ? 1 : 0) + spacing;
            }
            int width = Math.max(0, cursor - spacing);
            int base = (lines.length - 1 - line) * lineHeight; // first line on top
            for (int[] px : pixels) {
                int u0 = (px[0] - width / 2) * scale, v0 = (px[1] + base) * scale;
                for (int du = 0; du < scale; du++)
                    for (int dv = 0; dv < scale; dv++)
                        for (int w = 0; w < depth; w++) out.add(new Cell(u0 + du, v0 + dv, w));
            }
        }
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
    private static LinkedHashMap<Cell, Integer> clip(Map<Cell, Integer> cells, int maxAxis) {
        int half = (maxAxis - 1) / 2;
        List<Cell> kept = new ArrayList<>(cells.size());
        for (Cell c : cells.keySet()) {
            if (Math.abs(c.x()) <= half && c.y() >= -half && c.y() < maxAxis && Math.abs(c.z()) <= half) kept.add(c);
        }
        kept.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::z).thenComparingInt(Cell::x));
        LinkedHashMap<Cell, Integer> out = new LinkedHashMap<>(kept.size() * 2);
        for (Cell c : kept) out.put(c, cells.get(c));
        return out;
    }
}

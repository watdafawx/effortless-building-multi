package io.github.watdafawx.shapewright.shape;

import io.github.watdafawx.shapewright.shape.ShapeGenerator.Cell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Blender-style modifiers on a finished shape, applied in order: taper, twist, shear, wave, bend (all along
 * one axis), then grow/shrink, noise and decimate. Pure math like {@link ShapeGenerator}, so the preview and
 * the server agree. Every modifier is off at its default value.
 * <p>
 * The <b>origin</b> is the point the deform works around: along the deform axis it is the layer that stays put
 * (the start, middle or end of the shape), across it the point twist and taper turn and scale around. Three
 * offsets move it away from there.
 * <p>
 * Taper, twist, shear and wave keep each layer's axis coordinate, so together they are one transform per layer
 * and are found by inverse mapping (every result block looks back into the original), which keeps walls
 * solid. Bend moves blocks along the axis too, so it samples several points per block instead.
 * After all of them the shape is lowered or raised so its lowest block stays where it was.
 */
public final class ShapeModifiers {

    private ShapeModifiers() {}

    // Parameter keys (see ShapeType)
    public static final String TWIST = "twist", TAPER = "taper", SHEAR = "shear", WAVE = "wave", WAVE_LENGTH = "wave_length",
            BEND = "bend_angle", GROW = "grow", NOISE = "noise", DECIMATE = "decimate", SEED = "seed", AXIS = "deform_axis",
            ORIGIN = "deform_origin", ORIGIN_X = "origin_x", ORIGIN_Y = "origin_y", ORIGIN_Z = "origin_z";

    public static boolean isModifierKey(String key) {
        return switch (key) {
            case TWIST, TAPER, SHEAR, WAVE, WAVE_LENGTH, BEND, GROW, NOISE, DECIMATE, SEED, AXIS,
                 ORIGIN, ORIGIN_X, ORIGIN_Y, ORIGIN_Z -> true;
            default -> false;
        };
    }

    /** True when any modifier would change the shape. */
    public static boolean active(ShapeParams p) {
        return p.get(TWIST) != 0 || p.get(TAPER) != 0 || p.get(SHEAR) != 0 || p.get(WAVE) != 0 || p.get(BEND) != 0
                || p.getInt(GROW) != 0 || p.getInt(NOISE) != 0 || p.getInt(DECIMATE) != 0;
    }

    private static int axisOf(ShapeParams p) {
        int choice = p.getInt(AXIS);
        return choice == 0 ? ShapeGenerator.depthAxis(p.orientation()) : choice - 1;
    }

    /**
     * The origin of the deform in the cells' own coordinates (cell indices, so a block's center is its index):
     * start, middle or end of the shape along the deform axis, the middle across it, then shifted by the offsets.
     */
    public static double[] origin(List<Cell> cells, ShapeParams p) {
        if (cells.isEmpty()) return new double[3];
        int axis = axisOf(p);
        int[][] bb = bounds(cells);
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) o[i] = (bb[0][i] + bb[1][i]) / 2.0;
        o[axis] = switch (p.getInt(ORIGIN)) { case 1 -> (bb[0][axis] + bb[1][axis]) / 2.0; case 2 -> bb[1][axis]; default -> bb[0][axis]; };
        o[0] += p.getInt(ORIGIN_X);
        o[1] += p.getInt(ORIGIN_Y);
        o[2] += p.getInt(ORIGIN_Z);
        return o;
    }

    public static List<Cell> apply(List<Cell> cells, ShapeParams p) {
        if (cells.isEmpty() || !active(p)) return cells;
        List<Cell> out = deform(cells, p);
        int lift = lift(cells, out);
        return lift == 0 ? out : shiftY(out, lift);
    }

    /** How far {@link #apply} raises the deformed shape so its lowest block stays where it was. */
    public static int lift(List<Cell> before, List<Cell> after) {
        if (before.isEmpty() || after.isEmpty()) return 0;
        return bounds(before)[0][1] - bounds(after)[0][1];
    }

    /** The raw deform, before the shape is put back on its base. */
    public static List<Cell> deform(List<Cell> cells, ShapeParams p) {
        if (cells.isEmpty() || !active(p)) return cells;
        int axis = axisOf(p);
        double[] origin = origin(cells, p);
        List<Cell> out = cells;
        if (p.get(TWIST) != 0 || p.get(TAPER) != 0 || p.get(SHEAR) != 0 || p.get(WAVE) != 0) out = layerDeform(out, p, axis, origin);
        if (p.get(BEND) != 0) out = bend(out, p.get(BEND), axis, origin);
        int grow = p.getInt(GROW);
        if (grow != 0) out = grow(out, grow);
        if (p.getInt(NOISE) != 0) out = noise(out, p.getInt(NOISE), p.getInt(SEED));
        if (p.getInt(DECIMATE) != 0) out = decimate(out, p.getInt(DECIMATE), p.getInt(SEED));
        return out;
    }

    private static List<Cell> shiftY(List<Cell> cells, int dy) {
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) out.add(new Cell(c.x(), c.y() + dy, c.z()));
        return out;
    }

    private static int[][] bounds(List<Cell> cells) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], v[i]); hi[i] = Math.max(hi[i], v[i]); }
        }
        return new int[][]{lo, hi};
    }

    /** The layer transform at {@code t} along the axis: scale, turn (radians) and a shift along a. */
    private record Layer(double scale, double cos, double sin, double shift) {
        static Layer at(ShapeParams p, int t, double originT, double span) {
            double s = span == 0 ? 0 : (t - originT) / span;
            double scale = Math.max(0.05, 1 - p.get(TAPER) * s);
            double angle = Math.toRadians(p.get(TWIST) * s);
            double shift = p.get(SHEAR) * s;
            double length = p.get(WAVE_LENGTH);
            if (p.get(WAVE) != 0 && length > 0) shift += p.get(WAVE) * Math.sin(2 * Math.PI * (t - originT) / length);
            return new Layer(scale, Math.cos(angle), Math.sin(angle), shift);
        }
    }

    private static List<Cell> layerDeform(List<Cell> cells, ShapeParams p, int axis, double[] origin) {
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        int[][] bb = bounds(cells);
        int[] lo = bb[0], hi = bb[1];
        double span = hi[axis] - lo[axis];
        double pa = origin[a], pb = origin[b];
        Set<Cell> source = new HashSet<>(cells);

        Layer[] layers = new Layer[hi[axis] - lo[axis] + 1];
        int reach = 1;
        for (int i = 0; i < layers.length; i++) {
            layers[i] = Layer.at(p, lo[axis] + i, origin[axis], span);
            reach = Math.max(reach, (int) Math.ceil(layers[i].scale()) + 1);
        }

        Set<Cell> candidates = new HashSet<>();
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            Layer l = layers[v[axis] - lo[axis]];
            double da = (v[a] - pa) * l.scale(), db = (v[b] - pb) * l.scale();
            int ta = (int) Math.round(pa + da * l.cos() - db * l.sin() + l.shift());
            int tb = (int) Math.round(pb + da * l.sin() + db * l.cos());
            for (int ea = -reach; ea <= reach; ea++)
                for (int eb = -reach; eb <= reach; eb++) {
                    int[] t = v.clone();
                    t[a] = ta + ea;
                    t[b] = tb + eb;
                    candidates.add(new Cell(t[0], t[1], t[2]));
                }
        }

        Set<Cell> out = new HashSet<>();
        for (Cell t : candidates) {
            int[] v = {t.x(), t.y(), t.z()};
            Layer l = layers[v[axis] - lo[axis]];
            if (hit(source, v, a, b, pa, pb, l, 0, 0)) {
                out.add(t);
                continue;
            }
            // Thin walls: count the block when enough of its corners land inside the original
            int hits = 0;
            for (int corner = 0; corner < 4; corner++) {
                double oa = (corner & 1) == 0 ? -0.3 : 0.3, ob = (corner & 2) == 0 ? -0.3 : 0.3;
                if (hit(source, v, a, b, pa, pb, l, oa, ob)) hits++;
            }
            if (hits >= 2) out.add(t);
        }
        return new ArrayList<>(out);
    }

    private static boolean hit(Set<Cell> source, int[] v, int a, int b, double pa, double pb, Layer l, double oa, double ob) {
        double da = v[a] + oa - pa - l.shift(), db = v[b] + ob - pb;
        // Turn back, then undo the scale
        double ra = (da * l.cos() + db * l.sin()) / l.scale(), rb = (-da * l.sin() + db * l.cos()) / l.scale();
        int[] s = v.clone();
        s[a] = (int) Math.round(pa + ra);
        s[b] = (int) Math.round(pb + rb);
        return source.contains(new Cell(s[0], s[1], s[2]));
    }

    /**
     * Bends the axis into an arc of {@code degrees} over the shape's length, curving toward +a (the next axis),
     * with the origin's layer staying put. Each block is sampled several times along the axis and across the
     * bend (more for sharper bends, where the outside stretches the most) so the curve has no gaps.
     */
    private static List<Cell> bend(List<Cell> cells, double degrees, int axis, double[] origin) {
        int a = (axis + 1) % 3;
        int[][] bb = bounds(cells);
        int[] lo = bb[0], hi = bb[1];
        double span = hi[axis] - lo[axis] + 1;
        double radius = span / Math.toRadians(degrees); // signed: negative curves toward -a
        double pa = origin[a];
        int n = Math.min(7, 3 + (int) (Math.abs(degrees) / 45));
        double step = 1.0 / n;
        Set<Cell> out = new HashSet<>();
        for (Cell c : cells) {
            int[] v = {c.x(), c.y(), c.z()};
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    double t = v[axis] + (i - (n - 1) / 2.0) * step - origin[axis];
                    double d = v[a] + (j - (n - 1) / 2.0) * step - pa;
                    double phi = t / radius;
                    double r = radius - d;
                    int[] w = v.clone();
                    w[axis] = (int) Math.round(origin[axis] + r * Math.sin(phi));
                    w[a] = (int) Math.round(pa + radius - r * Math.cos(phi));
                    out.add(new Cell(w[0], w[1], w[2]));
                }
        }
        return new ArrayList<>(out);
    }

    /** Dilates ({@code amount} > 0) or erodes (< 0) by that many blocks, face neighbors each step. */
    private static List<Cell> grow(List<Cell> cells, int amount) {
        Set<Cell> cur = new HashSet<>(cells);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int step = 0; step < Math.abs(amount) && !cur.isEmpty(); step++) {
            Set<Cell> next = new HashSet<>();
            if (amount > 0) {
                for (Cell c : cur) {
                    next.add(c);
                    for (int[] d : dirs) next.add(new Cell(c.x() + d[0], c.y() + d[1], c.z() + d[2]));
                }
            } else {
                for (Cell c : cur) {
                    boolean inner = true;
                    for (int[] d : dirs) if (!cur.contains(new Cell(c.x() + d[0], c.y() + d[1], c.z() + d[2]))) { inner = false; break; }
                    if (inner) next.add(c);
                }
            }
            cur = next;
        }
        return new ArrayList<>(cur);
    }

    /** Pushes every block up to {@code amount} blocks along each axis, hashed from its position so it never changes. */
    private static List<Cell> noise(List<Cell> cells, int amount, int seed) {
        Set<Cell> out = new HashSet<>();
        for (Cell c : cells) {
            int h = hash(c.x(), c.y(), c.z(), seed);
            int span = 2 * amount + 1;
            int dx = Math.floorMod(h, span) - amount, dy = Math.floorMod(h / span, span) - amount, dz = Math.floorMod(h / (span * span), span) - amount;
            out.add(new Cell(c.x() + dx, c.y() + dy, c.z() + dz));
        }
        return new ArrayList<>(out);
    }

    /** Drops {@code percent} of the blocks, chosen by hash (weathered, crumbling look). */
    private static List<Cell> decimate(List<Cell> cells, int percent, int seed) {
        List<Cell> out = new ArrayList<>(cells.size());
        for (Cell c : cells) if (Math.floorMod(hash(c.x(), c.y(), c.z(), seed + 7919), 100) >= percent) out.add(c);
        return out;
    }

    private static int hash(int x, int y, int z, int seed) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 1103515245;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return h & 0x7fffffff;
    }
}

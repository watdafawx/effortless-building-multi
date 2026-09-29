package nl.requios.effortlessbuilding.shape;

import java.util.ArrayList;
import java.util.List;

/**
 * A smooth curve through clicked points (Catmull-Rom, so it passes through every point), and the stops
 * along it where path copies go. Pure math, unit tested.
 */
public final class PathCurve {

    /** Samples per segment when measuring length; plenty for blocks. */
    private static final int SAMPLES = 64;

    private PathCurve() {}

    /**
     * Stops every {@code spacing} blocks along the curve (measured along it, not straight), starting at the
     * first point and ending on the last. Each stop is {x, y, z, dx, dz}: the position and the horizontal
     * direction the curve heads there. With two points this is a straight line.
     */
    public static List<double[]> stops(List<double[]> points, double spacing) {
        List<double[]> out = new ArrayList<>();
        if (points.isEmpty()) return out;
        if (points.size() == 1) {
            double[] p = points.getFirst();
            out.add(new double[]{p[0], p[1], p[2], 0, 0});
            return out;
        }
        // Dense samples along the whole curve
        List<double[]> samples = new ArrayList<>();
        for (int seg = 0; seg < points.size() - 1; seg++) {
            double[] p0 = points.get(Math.max(0, seg - 1)), p1 = points.get(seg), p2 = points.get(seg + 1);
            double[] p3 = points.get(Math.min(points.size() - 1, seg + 2));
            for (int i = 0; i < SAMPLES; i++) samples.add(catmullRom(p0, p1, p2, p3, (double) i / SAMPLES));
        }
        samples.add(points.getLast().clone());

        double next = 0, walked = 0;
        double[] first = samples.getFirst(), dir0 = direction(samples, 0);
        out.add(new double[]{first[0], first[1], first[2], dir0[0], dir0[1]});
        next += spacing;
        for (int i = 1; i < samples.size(); i++) {
            double[] a = samples.get(i - 1), s = samples.get(i);
            double step = Math.sqrt(dist2(a, s));
            // Every stop that falls within this small step, placed exactly by distance
            while (step > 0 && walked + step + 1e-6 >= next) {
                double f = Math.min(1, (next - walked) / step);
                double[] dir = direction(samples, i);
                out.add(new double[]{a[0] + (s[0] - a[0]) * f, a[1] + (s[1] - a[1]) * f, a[2] + (s[2] - a[2]) * f, dir[0], dir[1]});
                next += spacing;
            }
            walked += step;
        }
        // Always finish on the last point
        double[] last = samples.getLast();
        double[] lastStop = out.getLast();
        if (dist2(last, lastStop) > 0.25) {
            double[] dir = direction(samples, samples.size() - 1);
            out.add(new double[]{last[0], last[1], last[2], dir[0], dir[1]});
        }
        return out;
    }

    private static double[] catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] r = new double[3];
        for (int a = 0; a < 3; a++) {
            r[a] = 0.5 * (2 * p1[a] + (-p0[a] + p2[a]) * t + (2 * p0[a] - 5 * p1[a] + 4 * p2[a] - p3[a]) * t2
                    + (-p0[a] + 3 * p1[a] - 3 * p2[a] + p3[a]) * t3);
        }
        return r;
    }

    /** Horizontal heading at a sample (x, z), from its neighbors. */
    private static double[] direction(List<double[]> samples, int i) {
        double[] a = samples.get(Math.max(0, i - 1)), b = samples.get(Math.min(samples.size() - 1, i + 1));
        return new double[]{b[0] - a[0], b[2] - a[2]};
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }
}

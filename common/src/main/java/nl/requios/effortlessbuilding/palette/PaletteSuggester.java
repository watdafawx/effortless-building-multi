package nl.requios.effortlessbuilding.palette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Suggests block palettes from block colors. Colors are compared in CIELAB, where equal distances look
 * about equally different, so "close" means close to the eye. Pure: works on ids and RGB only.
 */
public final class PaletteSuggester {

    /** A block and its average texture color (0xRRGGBB). */
    public record Swatch(String id, int rgb) {}

    private PaletteSuggester() {}

    /** The {@code count} blocks closest in color to the seed, the seed itself first. */
    public static List<Swatch> similar(Swatch seed, List<Swatch> all, int count) {
        double[] s = lab(seed.rgb());
        List<Swatch> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(w -> distance(s, lab(w.rgb()))));
        return withSeedFirst(seed, sorted, count);
    }

    /**
     * Light-to-dark shades around the seed's hue: blocks of a similar hue and saturation,
     * spread evenly over the lightness range they cover, sorted dark to light.
     */
    public static List<Swatch> shades(Swatch seed, List<Swatch> all, int count) {
        double[] s = lch(lab(seed.rgb()));
        boolean grey = s[1] < 12;
        List<Swatch> family = new ArrayList<>();
        for (Swatch w : all) {
            double[] c = lch(lab(w.rgb()));
            boolean match = grey ? c[1] < 14 : c[1] >= 8 && hueDistance(c[2], s[2]) < 28 && Math.abs(c[1] - s[1]) < 40;
            if (match) family.add(w);
        }
        if (family.size() <= count) {
            family.sort(Comparator.comparingDouble(w -> lab(w.rgb())[0]));
            return family;
        }
        family.sort(Comparator.comparingDouble(w -> lab(w.rgb())[0]));
        double lo = lab(family.getFirst().rgb())[0], hi = lab(family.getLast().rgb())[0];
        List<double[]> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double l = lo + (hi - lo) * i / Math.max(1, count - 1);
            targets.add(new double[]{l, s[1], s[2]});
        }
        return nearestEach(targets, family, true);
    }

    /** Evenly spaced steps from {@code from} to {@code to}, each the closest unused block. */
    public static List<Swatch> blend(Swatch from, Swatch to, List<Swatch> all, int count) {
        double[] a = lab(from.rgb()), b = lab(to.rgb());
        List<double[]> targets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double t = (double) i / Math.max(1, count - 1);
            targets.add(lchOf(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}));
        }
        List<Swatch> out = nearestEach(targets, all, false);
        // Keep the chosen ends exactly
        if (!out.isEmpty()) {
            out.set(0, from);
            out.set(out.size() - 1, to);
        }
        return dedupe(out);
    }

    /** The seed plus neighbors on the color wheel (hues ±30° and beyond), keeping its lightness. */
    public static List<Swatch> analogous(Swatch seed, List<Swatch> all, int count) {
        double[] s = lch(lab(seed.rgb()));
        List<double[]> targets = new ArrayList<>();
        targets.add(s);
        for (int i = 1; targets.size() < count; i++) {
            double step = 30.0 * ((i + 1) / 2) * (i % 2 == 1 ? 1 : -1);
            targets.add(new double[]{s[0], Math.max(s[1], 20), s[2] + step});
        }
        List<Swatch> out = nearestEach(targets, all, false);
        if (!out.isEmpty()) out.set(0, seed);
        return dedupe(out);
    }

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    /** For each LCh target, the nearest block not used yet. */
    private static List<Swatch> nearestEach(List<double[]> lchTargets, List<Swatch> pool, boolean sortByLightness) {
        Set<String> used = new HashSet<>();
        List<Swatch> out = new ArrayList<>();
        for (double[] target : lchTargets) {
            double[] t = labOfLch(target);
            Swatch best = null;
            double bestD = Double.MAX_VALUE;
            for (Swatch w : pool) {
                if (used.contains(w.id())) continue;
                double d = distance(t, lab(w.rgb()));
                if (d < bestD) { bestD = d; best = w; }
            }
            if (best == null) break;
            used.add(best.id());
            out.add(best);
        }
        if (sortByLightness) out.sort(Comparator.comparingDouble(w -> lab(w.rgb())[0]));
        return out;
    }

    private static List<Swatch> withSeedFirst(Swatch seed, List<Swatch> sorted, int count) {
        List<Swatch> out = new ArrayList<>();
        out.add(seed);
        for (Swatch w : sorted) {
            if (out.size() >= count) break;
            if (!w.id().equals(seed.id())) out.add(w);
        }
        return out;
    }

    private static List<Swatch> dedupe(List<Swatch> list) {
        Set<String> seen = new HashSet<>();
        List<Swatch> out = new ArrayList<>();
        for (Swatch w : list) if (seen.add(w.id())) out.add(w);
        return out;
    }

    // -------------------------------------------------------------------------
    // Color math (sRGB → CIELAB, D65)
    // -------------------------------------------------------------------------

    public static double[] lab(int rgb) {
        double r = linear(((rgb >> 16) & 0xFF) / 255.0);
        double g = linear(((rgb >> 8) & 0xFF) / 255.0);
        double b = linear((rgb & 0xFF) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = (0.2126 * r + 0.7152 * g + 0.0722 * b);
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    /** Perceptual color distance (CIE76). */
    public static double distance(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double[] lch(double[] lab) {
        double c = Math.hypot(lab[1], lab[2]);
        double h = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return new double[]{lab[0], c, h < 0 ? h + 360 : h};
    }

    private static double[] lchOf(double[] lab) {
        return lch(lab);
    }

    private static double[] labOfLch(double[] lch) {
        double h = Math.toRadians(lch[2]);
        return new double[]{lch[0], lch[1] * Math.cos(h), lch[1] * Math.sin(h)};
    }

    private static double hueDistance(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }
}

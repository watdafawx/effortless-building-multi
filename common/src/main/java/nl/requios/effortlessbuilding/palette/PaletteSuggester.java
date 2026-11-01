package nl.requios.effortlessbuilding.palette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Suggests block palettes from block colors. Colors are compared in CIELAB, where equal distances look
 * about equally different, so "close" means close to the eye. Pure: works on ids and RGB only.
 * <p>
 * Each mode turns the starting blocks into a list of target colors and picks, for each target, a block
 * near it that is not too close to a block already picked, so a suggestion never repeats the same look.
 * A non-zero {@code seed} picks among the few best matches at random (reroll); seed 0 always takes the best.
 */
public final class PaletteSuggester {

    /** A block and its average texture color (0xRRGGBB). */
    public record Swatch(String id, int rgb) {}

    /** Suggestion strategies. */
    public enum Mode {
        /** Dark to light in the first block's color family. */
        SHADES,
        /** The closest colors to the first block. */
        SIMILAR,
        /** Even steps from the first block to the last. */
        BLEND,
        /** Nearby hues on the color wheel. */
        NEIGHBORS,
        /** The first block's color and its opposite, with lighter and darker versions. */
        COMPLEMENT,
        /** Three hues evenly spaced around the wheel. */
        TRIAD,
        /** The first block's hue from near-black to near-white. */
        CONTRAST,
        /** A random colorful block and a random one of the modes above. */
        SURPRISE;

        public String getNameKey() {
            return "effortlessbuilding.screen.suggest." + name().toLowerCase();
        }
    }

    /** Picked blocks must differ from each other by at least this much (CIE76 ΔE; ~2.3 is just noticeable). */
    static final double MIN_DIFFERENCE = 4.0;
    /** With a seed, choose among this many best matches. */
    private static final int REROLL_CHOICES = 4;

    private PaletteSuggester() {}

    /**
     * @param start the palette's current blocks (first and last matter); must not be empty
     * @param all   every block to choose from
     * @param seed  0 for the best matches, anything else for a varied pick
     */
    public static List<Swatch> suggest(Mode mode, List<Swatch> start, List<Swatch> all, int count, long seed) {
        Random rng = seed == 0 ? null : new Random(seed);
        Swatch first = start.getFirst();
        if (mode == Mode.SURPRISE) {
            Random r = rng != null ? rng : new Random(1);
            List<Swatch> colorful = all.stream().filter(w -> lch(lab(w.rgb()))[1] > 18).toList();
            Swatch pick = colorful.isEmpty() ? first : colorful.get(r.nextInt(colorful.size()));
            Mode[] modes = {Mode.SHADES, Mode.NEIGHBORS, Mode.COMPLEMENT, Mode.TRIAD};
            return suggest(modes[r.nextInt(modes.length)], List.of(pick), all, count, r.nextLong() | 1);
        }

        double[] s = lch(lab(first.rgb()));
        List<double[]> targets = new ArrayList<>();
        List<Swatch> pool = all;
        switch (mode) {
            case SIMILAR -> {
                for (int i = 1; i < count; i++) targets.add(s);
            }
            case SHADES -> {
                pool = family(first, all);
                double lo = 100, hi = 0;
                for (Swatch w : pool) { double l = lab(w.rgb())[0]; lo = Math.min(lo, l); hi = Math.max(hi, l); }
                for (int i = 0; i < count; i++) targets.add(new double[]{lo + (hi - lo) * i / Math.max(1, count - 1), s[1], s[2]});
            }
            case BLEND -> {
                double[] a = lab(first.rgb()), b = lab(start.getLast().rgb());
                for (int i = 1; i < count - 1; i++) {
                    double t = (double) i / (count - 1);
                    targets.add(lch(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}));
                }
            }
            case NEIGHBORS -> {
                for (int i = 1; i < count; i++) {
                    double step = 30.0 * ((i + 1) / 2) * (i % 2 == 1 ? 1 : -1);
                    targets.add(new double[]{s[0], Math.max(s[1], 20), s[2] + step});
                }
            }
            case COMPLEMENT -> {
                double[][] variants = {{0, 180}, {12, 0}, {12, 180}, {-12, 0}, {-12, 180}, {24, 0}, {24, 180}, {-24, 0}};
                for (int i = 0; i < count - 1; i++) {
                    double[] v = variants[i % variants.length];
                    targets.add(new double[]{clampL(s[0] + v[0]), Math.max(s[1], 20), s[2] + v[1]});
                }
            }
            case TRIAD -> {
                for (int i = 1; i < count; i++) {
                    double lightness = s[0] + (i / 3) * (i % 2 == 0 ? 12 : -12);
                    targets.add(new double[]{clampL(lightness), Math.max(s[1], 20), s[2] + 120 * (i % 3)});
                }
            }
            case CONTRAST -> {
                for (int i = 0; i < count; i++) targets.add(new double[]{15 + 75.0 * i / Math.max(1, count - 1), s[1] * 0.6, s[2]});
            }
            default -> { }
        }

        List<Swatch> picked = new ArrayList<>();
        boolean startsWithSeed = mode != Mode.SHADES && mode != Mode.CONTRAST;
        if (startsWithSeed) picked.add(first);
        for (double[] target : targets) {
            if (picked.size() >= count) break;
            Swatch w = pickNear(labOf(target), pool, picked, rng);
            if (w == null && pool != all) w = pickNear(labOf(target), all, picked, rng);
            if (w != null) picked.add(w);
        }
        if (mode == Mode.BLEND && count >= 2 && picked.stream().noneMatch(w -> w.id().equals(start.getLast().id()))) {
            picked.add(start.getLast());
        }
        if (mode == Mode.SHADES || mode == Mode.CONTRAST) picked.sort(Comparator.comparingDouble(w -> lab(w.rgb())[0]));
        return picked;
    }

    /**
     * The block nearest the target that is not already picked and not nearly the same color as
     * one that is. With a random source, one of the few nearest, favoring the closest.
     */
    private static Swatch pickNear(double[] targetLab, List<Swatch> pool, List<Swatch> picked, Random rng) {
        Set<String> used = new HashSet<>();
        List<double[]> pickedLabs = new ArrayList<>();
        for (Swatch w : picked) { used.add(w.id()); pickedLabs.add(lab(w.rgb())); }

        List<Swatch> best = new ArrayList<>();
        List<Double> bestD = new ArrayList<>();
        int keep = rng == null ? 1 : REROLL_CHOICES;
        for (Swatch w : pool) {
            if (used.contains(w.id())) continue;
            double[] l = lab(w.rgb());
            boolean tooClose = false;
            for (double[] p : pickedLabs) if (distance(l, p) < MIN_DIFFERENCE) { tooClose = true; break; }
            if (tooClose) continue;
            double d = distance(targetLab, l);
            int at = 0;
            while (at < bestD.size() && bestD.get(at) <= d) at++;
            if (at < keep) {
                best.add(at, w);
                bestD.add(at, d);
                if (best.size() > keep) { best.removeLast(); bestD.removeLast(); }
            }
        }
        if (best.isEmpty()) return null;
        if (rng == null) return best.getFirst();
        // Weighted toward the closest: 1, 1/2, 1/3, ...
        double total = 0;
        for (int i = 0; i < best.size(); i++) total += 1.0 / (i + 1);
        double r = rng.nextDouble() * total;
        for (int i = 0; i < best.size(); i++) {
            r -= 1.0 / (i + 1);
            if (r <= 0) return best.get(i);
        }
        return best.getLast();
    }

    /** Blocks of a similar hue and saturation (or all greys, for a grey block). */
    private static List<Swatch> family(Swatch seed, List<Swatch> all) {
        double[] s = lch(lab(seed.rgb()));
        boolean grey = s[1] < 12;
        List<Swatch> out = new ArrayList<>();
        for (Swatch w : all) {
            double[] c = lch(lab(w.rgb()));
            boolean match = grey ? c[1] < 14 : c[1] >= 8 && hueDistance(c[2], s[2]) < 28 && Math.abs(c[1] - s[1]) < 40;
            if (match) out.add(w);
        }
        return out;
    }

    private static double clampL(double l) {
        return Math.max(8, Math.min(95, l));
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

    /** Lightness, chroma and hue (degrees) of a CIELAB color. */
    public static double[] lch(double[] lab) {
        double c = Math.hypot(lab[1], lab[2]);
        double h = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return new double[]{lab[0], c, h < 0 ? h + 360 : h};
    }

    private static double[] labOf(double[] lch) {
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

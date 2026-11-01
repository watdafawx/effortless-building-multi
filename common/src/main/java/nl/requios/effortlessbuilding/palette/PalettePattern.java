package nl.requios.effortlessbuilding.palette;

/**
 * How palette blocks are laid out over a build. Pure math shared by the client preview and the
 * server, so both pick the same block for every position. Sent by ordinal: only append.
 */
public enum PalettePattern {
    /** Each block picked at random (stable per position). */
    RANDOM,
    /** 3D checkerboard. */
    CHECKER,
    /** Horizontal bands, repeating. */
    LAYERS,
    /** First block at the bottom through the last at the top, once. */
    GRADIENT,
    /** Rings around the build's start point, repeating. */
    RINGS,
    /** Soft patches, like weathering; band width sets the patch size. */
    NOISE,
    /** Bottom to top like GRADIENT, with ragged natural borders between the blocks (good for terrain). */
    NOISY_GRADIENT;

    public String getNameKey() {
        return "effortlessbuilding.palette.pattern." + name().toLowerCase();
    }

    /**
     * Which of {@code count} palette entries goes at a position.
     *
     * @param band        blocks per band (checker cell, layer or ring width), at least 1
     * @param dx          position relative to the build's start point (likewise dy, dz)
     * @param minDy       lowest relative y in the build (for GRADIENT; likewise maxDy)
     * @param absolutePos the world position packed as a long (for RANDOM, so modifier copies differ)
     */
    public int index(int count, int band, int dx, int dy, int dz, int minDy, int maxDy, long absolutePos) {
        if (count <= 1) return 0;
        band = Math.max(1, band);
        return switch (this) {
            case RANDOM -> (int) Math.floorMod(mix(absolutePos), (long) count);
            case CHECKER -> Math.floorMod(Math.floorDiv(dx, band) + Math.floorDiv(dy, band) + Math.floorDiv(dz, band), count);
            case LAYERS -> Math.floorMod(Math.floorDiv(dy, band), count);
            case GRADIENT -> {
                int height = maxDy - minDy + 1;
                yield Math.min(count - 1, (int) ((long) (dy - minDy) * count / Math.max(1, height)));
            }
            case RINGS -> Math.floorMod((int) Math.floor(Math.hypot(dx, dz) / band), count);
            case NOISE -> Math.min(count - 1, (int) (valueNoise(dx, dy, dz, band * 3.0) * count));
            case NOISY_GRADIENT -> {
                double height = (dy - minDy + 0.5) / Math.max(1, maxDy - minDy + 1);
                double wobble = (valueNoise(dx, dy, dz, band * 2.0) - 0.5) * 0.35;
                yield Math.max(0, Math.min(count - 1, (int) Math.floor((height + wobble) * count)));
            }
        };
    }

    /**
     * Smooth 3D value noise in [0, 1): random values on a lattice {@code scale} blocks apart,
     * blended with smoothstep between them. Stable per position.
     */
    static double valueNoise(int x, int y, int z, double scale) {
        double fx = x / scale, fy = y / scale, fz = z / scale;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
        double tx = smooth(fx - x0), ty = smooth(fy - y0), tz = smooth(fz - z0);
        double result = 0;
        for (int i = 0; i < 8; i++) {
            int cx = x0 + (i & 1), cy = y0 + ((i >> 1) & 1), cz = z0 + ((i >> 2) & 1);
            double w = ((i & 1) == 1 ? tx : 1 - tx) * (((i >> 1) & 1) == 1 ? ty : 1 - ty) * (((i >> 2) & 1) == 1 ? tz : 1 - tz);
            long h = mix(cx * 73856093L ^ cy * 19349663L ^ cz * 83492791L);
            result += w * ((h >>> 11) / (double) (1L << 53));
        }
        return Math.min(0.999999, result);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** SplitMix64 finalizer: a well-spread hash of the position. */
    private static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

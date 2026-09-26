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
    RINGS;

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
        };
    }

    /** SplitMix64 finalizer: a well-spread hash of the position. */
    private static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

package io.github.watdafawx.shapewright.shape;

import java.util.Random;

/**
 * Procedural multi-color floor pattern generator: produces an {@code int[size][size]} grid of
 * palette indices ({@code -1} = empty, {@code 0} = base, {@code 1} = primary, {@code 2} = secondary,
 * {@code 3} = trim/outline, {@code 4} = accent/light). Pure math, shared and unit-testable.
 */
public final class FloorDesigner {

    public static final int EMPTY = -1;
    public static final int BASE = 0;
    public static final int PRIMARY = 1;
    public static final int SECONDARY = 2;
    public static final int TRIM = 3;
    public static final int ACCENT = 4;

    public enum Style {
        PROCEDURAL("procedural"),
        MANDALA("mandala"),
        FLORAL("floral"),
        SUNBURST("sunburst"),
        CRESCENT("crescent"),
        COMPASS("compass"),
        ORNATE_TILE("ornate_tile"),
        MEDALLION("medallion"),
        LABYRINTH("labyrinth"),
        ROSE_WINDOW("rose_window"),
        GRADIENT("gradient");

        public final String key;
        Style(String key) { this.key = key; }
        public String getNameKey() { return "shapewright.floor_designer.style." + key; }
    }

    public enum Outline {
        QUATREFOIL("quatrefoil"),
        CIRCLE("circle"),
        SQUARE("square"),
        OCTAGON("octagon"),
        DIAMOND("diamond"),
        SCALLOPED("scalloped");

        public final String key;
        Outline(String key) { this.key = key; }
        public String getNameKey() { return "shapewright.floor_designer.outline." + key; }
    }

    public record Config(Style style, Outline outline, int size, int symmetry, int complexity, int seed, boolean border) {
        public Config {
            size = Math.max(11, Math.min(81, size | 1));
            symmetry = Math.max(4, Math.min(12, symmetry));
            complexity = Math.max(1, Math.min(5, complexity));
        }

        public static Config defaults() {
            return new Config(Style.MANDALA, Outline.QUATREFOIL, 33, 8, 3, 1, true);
        }
    }

    private FloorDesigner() {}

    /** Generates the full {@code size x size} palette-index grid. */
    public static int[][] generate(Config cfg) {
        int n = cfg.size();
        int half = n / 2;
        int[][] grid = new int[n][n];
        Random rng = new Random(cfg.seed() * 0x9E3779B97F4A7C15L + cfg.style().ordinal() * 31L + cfg.complexity());

        // Seed-driven shape parameters so Randomize produces genuinely distinct designs
        double freq1 = 1.0 + rng.nextDouble() * 1.5;
        double freq2 = 2.0 + rng.nextDouble() * 2.5;
        double phase = rng.nextDouble() * Math.PI * 2;
        double ringPos = 0.42 + rng.nextDouble() * 0.22;
        double petalSharp = 0.6 + rng.nextDouble() * 0.9;
        int subVariant = rng.nextInt(3);

        if (cfg.style() == Style.PROCEDURAL) {
            generateProcedural(grid, cfg, rng);
            if (cfg.border()) applyBorder(grid);
            return grid;
        }

        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double dx = (x - half) / (half + 0.5);
                double dz = (z - half) / (half + 0.5);
                if (!inOutline(cfg.outline(), dx, dz, cfg.symmetry())) {
                    grid[z][x] = EMPTY;
                    continue;
                }
                grid[z][x] = switch (cfg.style()) {
                    case MANDALA -> mandala(dx, dz, cfg, freq1, freq2, phase, ringPos, petalSharp);
                    case PROCEDURAL -> BASE;
                    case FLORAL -> floral(dx, dz, x - half, z - half, half, cfg, freq1, phase, subVariant);
                    case SUNBURST -> sunburst(dx, dz, cfg, petalSharp, phase);
                    case CRESCENT -> crescent(dx, dz, cfg, subVariant);
                    case COMPASS -> compass(dx, dz, cfg);
                    case ORNATE_TILE -> ornateTile(x, z, cfg, subVariant);
                    case MEDALLION -> medallion(dx, dz, x, z, cfg);
                    case LABYRINTH -> labyrinth(dx, dz, x - half, z - half, half, cfg);
                    case ROSE_WINDOW -> roseWindow(dx, dz, cfg, freq1);
                    case GRADIENT -> gradient(dx, dz, x, z, cfg, subVariant);
                };
            }
        }

        if (cfg.border()) {
            applyBorder(grid);
        }
        return grid;
    }

    private static boolean inOutline(Outline outline, double dx, double dz, int symmetry) {
        double ax = Math.abs(dx), az = Math.abs(dz);
        double r = Math.hypot(dx, dz);
        return switch (outline) {
            case SQUARE -> ax <= 1.0 && az <= 1.0;
            case CIRCLE -> r <= 1.0;
            case OCTAGON -> ax <= 1.0 && az <= 1.0 && (ax + az) <= 1.42;
            case DIAMOND -> (ax + az) <= 1.02;
            case QUATREFOIL -> {
                // Four overlapping lobes along the cardinal axes + central diamond (classic palace/cathedral medallion)
                double lobe = 0.50;
                double rad = 0.52;
                boolean inLobe = Math.hypot(dx - lobe, dz) <= rad || Math.hypot(dx + lobe, dz) <= rad
                        || Math.hypot(dx, dz - lobe) <= rad || Math.hypot(dx, dz + lobe) <= rad;
                yield inLobe || (ax + az) <= 0.78;
            }
            case SCALLOPED -> {
                double ang = Math.atan2(dz, dx);
                int lobes = Math.max(4, symmetry);
                double maxR = 0.84 + 0.16 * Math.abs(Math.cos(lobes * ang / 2.0));
                yield r <= maxR;
            }
        };
    }

    /** Fold angle into a symmetric dihedral wedge [0, pi / sym]. */
    private static double foldAngle(double dx, double dz, int sym) {
        double a = Math.atan2(dz, dx);
        if (a < 0) a += Math.PI * 2;
        double sector = (Math.PI * 2) / sym;
        double mod = a % sector;
        return mod > sector / 2.0 ? sector - mod : mod;
    }

    /** 1. Mandala / Filigree: radial petals, concentric rings, scroll curls, center rosette. */
    private static int mandala(double dx, double dz, Config cfg,
                               double freq1, double freq2, double phase, double ringPos, double petalSharp) {
        double r = Math.hypot(dx, dz);
        int sym = cfg.symmetry();
        double wa = foldAngle(dx, dz, sym);
        double normA = wa / (Math.PI / sym); // 0 along axis, 1 along diagonal

        // Center jewel / rosette
        if (r < 0.08) return ACCENT;
        if (r < 0.14) return TRIM;

        // Inner lotus petals
        double innerPetal = 0.16 + 0.24 * Math.pow(Math.cos(normA * Math.PI * 0.5), petalSharp);
        if (r < innerPetal) {
            if (Math.abs(r - innerPetal) < 0.045) return TRIM;
            return normA < 0.35 ? PRIMARY : SECONDARY;
        }

        // Filigree scroll / wave ring
        double wave = Math.sin(r * (6.0 + cfg.complexity() * 2.5) * freq1 + normA * Math.PI * freq2 + phase);
        double ringDist = Math.abs(r - ringPos);
        if (ringDist < 0.055) return TRIM;
        if (ringDist < 0.11 + 0.04 * cfg.complexity()) {
            if (wave > 0.35) return PRIMARY;
            if (wave < -0.35) return SECONDARY;
            return BASE;
        }

        // Outer scroll curls / medallions along axes & diagonals
        double scroll = Math.cos(normA * Math.PI * (1.0 + cfg.complexity() * 0.5)) * Math.sin((r - ringPos) * 10.0 * freq1);
        if (r > ringPos + 0.10) {
            if (Math.abs(normA - 0.5) < 0.10 && Math.abs(r - 0.72) < 0.08) return ACCENT;
            if (scroll > 0.42) return PRIMARY;
            if (scroll > 0.18) return SECONDARY;
            if (scroll < -0.55) return TRIM;
        }
        return BASE;
    }

    /** 2. Floral Mosaic: center blossom, diagonal foliage/petals, framed border, corner accents. */
    private static int floral(double dx, double dz, int gx, int gz, int half, Config cfg,
                              double freq1, double phase, int variant) {
        double ax = Math.abs(dx), az = Math.abs(dz);
        double r = Math.hypot(dx, dz);
        double u = Math.max(ax, az), v = Math.min(ax, az); // 8-way fold

        // Outer decorative frame band (for square/octagon floors)
        if (u > 0.86) return TRIM;
        if (u > 0.78 && v > 0.55) return ACCENT;
        if (u > 0.76) return (Math.floorDiv(gx + gz, 2) & 1) == 0 ? SECONDARY : BASE;

        // Center flower (4- or 8-petal)
        double wa = foldAngle(dx, dz, cfg.symmetry());
        double normA = wa / (Math.PI / cfg.symmetry());
        double flowerR = 0.22 + 0.16 * Math.cos(normA * Math.PI);
        if (r < 0.07) return ACCENT;
        if (r < flowerR) {
            if (r > flowerR - 0.045) return TRIM;
            return r < flowerR * 0.55 ? PRIMARY : SECONDARY;
        }

        // Diagonal leaf/vine clusters in the mid-field
        double diag = Math.abs(ax - az);
        double leafWave = Math.sin((ax + az) * (5.0 + cfg.complexity() * 2.0) * freq1 + phase);
        if (diag < 0.18 + 0.05 * cfg.complexity() && r > 0.32 && r < 0.78) {
            if (diag < 0.045) return TRIM;
            return leafWave > 0 ? PRIMARY : SECONDARY;
        }

        // Corner blossoms / cardinal motifs
        double cornerDist = Math.hypot(ax - 0.62, az - 0.62);
        if (cornerDist < 0.14) return cornerDist < 0.06 ? ACCENT : PRIMARY;
        if (variant == 1 && v < 0.08 && u > 0.40 && u < 0.72) return SECONDARY;
        return BASE;
    }

    /** 3. Sunburst / Star: layered N-point stars with stepped gradient rings + glowing center. */
    private static int sunburst(double dx, double dz, Config cfg, double sharp, double phase) {
        double r = Math.hypot(dx, dz);
        if (r < 0.09) return ACCENT;
        int points = cfg.symmetry();
        double wa = foldAngle(dx, dz, points);
        double normA = wa / (Math.PI / points);
        double starFactor = 0.58 + 0.42 * Math.pow(1.0 - normA, sharp);
        double scaled = r / starFactor;

        if (scaled < 0.22) return ACCENT;
        if (scaled < 0.40) return PRIMARY;
        if (scaled < 0.46) return TRIM;
        if (scaled < 0.66) return SECONDARY;
        if (scaled < 0.72) return TRIM;
        if (scaled < 0.90) {
            // Secondary alternating rays in outer band
            double ray = Math.sin(normA * Math.PI * (1 + cfg.complexity()) + phase);
            return ray > 0.2 ? PRIMARY : BASE;
        }
        return BASE;
    }

    /** 4. Crescent / Emblem: bold geometric crescent moon inside a framed medallion. */
    private static int crescent(double dx, double dz, Config cfg, int variant) {
        double r = Math.hypot(dx, dz);
        // Outer double ring
        if (r > 0.88) return TRIM;
        if (r > 0.78) return SECONDARY;
        if (r > 0.72) return TRIM;

        // Crescent moon: inside outer disk, outside offset inner disk
        double ox = variant == 0 ? 0.18 : (variant == 1 ? -0.18 : 0.14);
        double oz = variant == 2 ? 0.14 : 0.0;
        boolean inOuter = r <= 0.62;
        boolean inInner = Math.hypot(dx - ox, dz - oz) <= 0.46;
        if (inOuter && !inInner) return PRIMARY;
        if (inInner) {
            // Subtle textured inner medallion
            double innerR = Math.hypot(dx - ox, dz - oz);
            if (innerR < 0.12 && cfg.complexity() >= 3) return ACCENT;
            return BASE;
        }
        return SECONDARY;
    }

    /** 5. Compass Rose: 8-point nautical star with split light/dark needles and concentric degree ring. */
    private static int compass(double dx, double dz, Config cfg) {
        double r = Math.hypot(dx, dz);
        if (r < 0.08) return ACCENT;
        if (r < 0.14) return TRIM;

        // Outer compass ring with degree ticks
        double ang = Math.atan2(dz, dx);
        if (r > 0.78 && r < 0.88) {
            int sector = (int) Math.floor((ang + Math.PI) / (Math.PI / (cfg.symmetry() * 2)));
            return (sector & 1) == 0 ? TRIM : SECONDARY;
        }

        // 4 primary cardinal needles (long) + 4 diagonal needles (medium), split left/right for 3D bevel look
        double ax = Math.abs(dx), az = Math.abs(dz);
        boolean onCardinal = (ax < 0.24 * (1.0 - r * 0.85) && r < 0.90) || (az < 0.24 * (1.0 - r * 0.85) && r < 0.90);
        if (onCardinal) {
            boolean halfSide = ax < az ? dx >= 0 : dz >= 0;
            return halfSide ? PRIMARY : SECONDARY;
        }
        // Diagonal 45-degree needles
        double u = (dx + dz) * 0.7071, v = (dx - dz) * 0.7071;
        double au = Math.abs(u), av = Math.abs(v);
        boolean onDiag = (au < 0.20 * (1.0 - r * 1.1) && r < 0.68) || (av < 0.20 * (1.0 - r * 1.1) && r < 0.68);
        if (onDiag) {
            boolean halfSide = au < av ? u >= 0 : v >= 0;
            return halfSide ? SECONDARY : TRIM;
        }
        return BASE;
    }

    /** 6. Ornate Tile: repeating framed palace/cathedral tiles with inner diamond and corner jewels. */
    private static int ornateTile(int x, int z, Config cfg, int variant) {
        int period = 5 + cfg.complexity() * 2; // 7, 9, 11, 13, 15 (odd for crisp center)
        int lx = Math.floorMod(x, period), lz = Math.floorMod(z, period);
        int h = period / 2;
        int dx = Math.abs(lx - h), dz = Math.abs(lz - h);

        // Tile frame
        if (lx == 0 || lz == 0) return (lx == 0 && lz == 0) ? ACCENT : TRIM;
        // Center jewel + diamond
        if (dx + dz == 0) return ACCENT;
        if (dx + dz <= h - 2) return (dx + dz == h - 2) ? TRIM : PRIMARY;
        if (variant == 1 && (dx == h - 1 || dz == h - 1)) return SECONDARY;
        return ((dx + dz) == h - 1) ? SECONDARY : BASE;
    }

    /** 7. Royal Medallion: classic diagonal checkerboard hall with a framed central medallion. */
    private static int medallion(double dx, double dz, int x, int z, Config cfg) {
        double r = Math.hypot(dx, dz);
        if (r < 0.09) return ACCENT;
        if (r < 0.28) {
            double wa = foldAngle(dx, dz, cfg.symmetry());
            return wa < (Math.PI / (cfg.symmetry() * 2.0)) ? PRIMARY : SECONDARY;
        }
        if (r < 0.35) return TRIM;
        if (r < 0.44) return PRIMARY;
        if (r < 0.50) return TRIM;
        // Classic diagonal checkerboard outside the medallion
        int tile = Math.max(2, 5 - cfg.complexity() / 2);
        return ((Math.floorDiv(x + z, tile) + Math.floorDiv(x - z, tile)) & 1) == 0 ? BASE : SECONDARY;
    }

    /** 8. Labyrinth / Greek Key: concentric stepped rings with alternating doorways and glowing center. */
    private static int labyrinth(double dx, double dz, int gx, int gz, int half, Config cfg) {
        int ringStep = Math.max(2, 5 - cfg.complexity() / 2);
        int cheb = Math.max(Math.abs(gx), Math.abs(gz));
        if (cheb <= 1) return ACCENT;
        if (cheb == 2) return TRIM;
        int ringIdx = cheb / ringStep;
        boolean onWall = (cheb % ringStep) == 0;
        if (onWall) {
            // Leave symmetric openings on alternating axes per ring
            boolean open = (ringIdx & 1) == 0 ? Math.abs(gx) <= 1 : Math.abs(gz) <= 1;
            if (open) return PRIMARY;
            return TRIM;
        }
        return (ringIdx & 1) == 0 ? BASE : SECONDARY;
    }

    /** 9. Rose Window: gothic stained-glass radial spokes, arched petal compartments, bright accents. */
    private static int roseWindow(double dx, double dz, Config cfg, double freq1) {
        double r = Math.hypot(dx, dz);
        int spokes = cfg.symmetry();
        double wa = foldAngle(dx, dz, spokes);
        double normA = wa / (Math.PI / spokes);

        if (r < 0.10) return ACCENT;
        if (r < 0.16) return TRIM;
        // Stone tracery rings
        if (Math.abs(r - 0.48) < 0.045 || Math.abs(r - 0.82) < 0.045) return TRIM;
        // Radial stone spokes
        if (normA < 0.12 && r < 0.85) return TRIM;
        // Arched stained-glass compartments
        if (r < 0.48) {
            return normA > 0.45 ? PRIMARY : SECONDARY;
        }
        if (r < 0.82) {
            double arch = 0.65 + 0.14 * Math.sin(normA * Math.PI);
            if (Math.abs(r - arch) < 0.045) return TRIM;
            if (r < arch) return normA > 0.35 && normA < 0.65 && Math.abs(r - 0.62) < 0.07 ? ACCENT : PRIMARY;
            return SECONDARY;
        }
        return (Math.sin(normA * Math.PI * freq1) > 0) ? SECONDARY : BASE;
    }

    /** 7. Gradient Fade: organic stepped multi-block dithered blend (moss -> cobble -> stone style). */
    private static int gradient(double dx, double dz, int x, int z, Config cfg, int variant) {
        double t = variant == 0
                ? Math.hypot(dx, dz)                          // radial from center
                : (variant == 1 ? (dx + 1.0) * 0.5            // left to right
                : (dx + dz + 2.0) * 0.25);                    // diagonal
        // Deterministic spatial hash for organic dithered transitions
        int h = (x * 374761393 + z * 668265263 + cfg.seed() * 1442695041);
        h = (h ^ (h >>> 13)) * 1274126177;
        double jitter = (((h ^ (h >>> 16)) & 0xFF) / 255.0 - 0.5) * (0.12 + cfg.complexity() * 0.04);
        double v = Math.max(0.0, Math.min(0.999, t + jitter));
        if (v < 0.20) return ACCENT;
        if (v < 0.42) return PRIMARY;
        if (v < 0.65) return SECONDARY;
        if (v < 0.85) return BASE;
        return TRIM;
    }

    /**
     * Procedural architectural floor composer: stacks seed-driven symmetric geometric layers
     * (field, outer band, petals/stars/arches/filigree/jewels, centerpiece) with automatic
     * trim outlining so every seed produces a crisp, unique, intentional mosaic.
     */
    private static void generateProcedural(int[][] grid, Config cfg, Random rng) {
        int n = grid.length;
        int half = n / 2;
        int sym = cfg.symmetry();

        // 1. Background field (0 = solid, 1 = subtle checker, 2 = diagonal checker, 3 = radial rays)
        int fieldType = rng.nextInt(4);
        int fieldScale = 2 + rng.nextInt(3);

        // 2. Choose 2..4 radial layers based on complexity
        int layerCount = Math.min(4, 1 + cfg.complexity());
        int[] layerKind = new int[layerCount];     // 0=petal, 1=star, 2=arch_band, 3=filigree, 4=spokes, 5=ring
        double[] rMin = new double[layerCount];
        double[] rMax = new double[layerCount];
        double[] pSharp = new double[layerCount];
        int[] pColor = new int[layerCount];
        int[] pAltColor = new int[layerCount];
        boolean[] pBevel = new boolean[layerCount];
        int[] pSym = new int[layerCount];

        double cursor = 0.88;
        for (int i = 0; i < layerCount; i++) {
            layerKind[i] = rng.nextInt(6);
            double band = 0.16 + rng.nextDouble() * 0.16;
            rMax[i] = cursor;
            rMin[i] = Math.max(0.16, cursor - band);
            cursor = rMin[i] - (0.02 + rng.nextDouble() * 0.05);
            pSharp[i] = 0.6 + rng.nextDouble() * 1.4;
            pColor[i] = (i & 1) == 0 ? PRIMARY : SECONDARY;
            pAltColor[i] = (i & 1) == 0 ? SECONDARY : PRIMARY;
            pBevel[i] = rng.nextBoolean();
            pSym[i] = rng.nextBoolean() ? sym : Math.max(4, sym / 2);
            if (cursor < 0.22) {
                layerCount = i + 1;
                break;
            }
        }

        // 3. Optional symmetric jewel accents
        boolean hasJewels = rng.nextBoolean();
        double jewelR = 0.55 + rng.nextDouble() * 0.22;
        boolean jewelOnDiag = rng.nextBoolean();

        // 4. Centerpiece style (0 = diamond jewel, 1 = mini star, 2 = ringed core)
        int centerStyle = rng.nextInt(3);
        boolean autoOutline = rng.nextInt(3) != 0; // 2/3 chance of crisp trim outlines between primary & base

        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                double dx = (x - half) / (half + 0.5);
                double dz = (z - half) / (half + 0.5);
                if (!inOutline(cfg.outline(), dx, dz, sym)) {
                    grid[z][x] = EMPTY;
                    continue;
                }
                double r = Math.hypot(dx, dz);
                double wa = foldAngle(dx, dz, sym);
                double normA = wa / (Math.PI / sym);

                // Background field
                int val = switch (fieldType) {
                    case 1 -> ((Math.floorDiv(x, fieldScale) + Math.floorDiv(z, fieldScale)) & 1) == 0 ? BASE : SECONDARY;
                    case 2 -> ((Math.floorDiv(x + z, fieldScale) + Math.floorDiv(x - z, fieldScale)) & 1) == 0 ? BASE : SECONDARY;
                    case 3 -> (normA < 0.25 && r > 0.35) ? SECONDARY : BASE;
                    default -> BASE;
                };

                // Apply layers from outer to inner
                for (int i = 0; i < layerCount; i++) {
                    double lwa = foldAngle(dx, dz, pSym[i]);
                    double lNorm = lwa / (Math.PI / pSym[i]);
                    double lo = rMin[i], hi = rMax[i], mid = (lo + hi) * 0.5;

                    switch (layerKind[i]) {
                        case 0 -> { // Petal / lotus
                            double edge = lo + (hi - lo) * Math.pow(Math.cos(lNorm * Math.PI * 0.5), pSharp[i]);
                            if (r <= edge && r >= lo * 0.75) {
                                if (Math.abs(r - edge) < 0.045) val = TRIM;
                                else val = pBevel[i] && lNorm < 0.4 ? pAltColor[i] : pColor[i];
                            }
                        }
                        case 1 -> { // Star points
                            double edge = lo + (hi - lo) * Math.pow(1.0 - lNorm, pSharp[i]);
                            if (r <= edge && r >= lo * 0.7) {
                                val = pBevel[i] && lNorm < 0.45 ? pColor[i] : pAltColor[i];
                            }
                        }
                        case 2 -> { // Arched / scalloped band
                            double arch = mid + (hi - lo) * 0.45 * Math.sin(lNorm * Math.PI);
                            if (r >= lo && r <= arch) {
                                if (Math.abs(r - arch) < 0.045 || Math.abs(r - lo) < 0.04) val = TRIM;
                                else val = pColor[i];
                            }
                        }
                        case 3 -> { // Filigree wave band
                            if (r >= lo && r <= hi) {
                                double wave = Math.cos((r - lo) / (hi - lo) * Math.PI * 2.0) * Math.cos(lNorm * Math.PI * 2.0);
                                if (wave > 0.25) val = pColor[i];
                                else if (wave < -0.45) val = TRIM;
                            }
                        }
                        case 4 -> { // Radial spokes + rim
                            if (r >= lo && r <= hi) {
                                if (Math.abs(r - hi) < 0.04 || Math.abs(r - lo) < 0.04) val = TRIM;
                                else if (lNorm < 0.14) val = TRIM;
                                else if (lNorm > 0.4 && lNorm < 0.6) val = pColor[i];
                            }
                        }
                        default -> { // Framed ring
                            if (r >= lo && r <= hi) {
                                if (Math.abs(r - hi) < 0.04 || Math.abs(r - lo) < 0.04) val = TRIM;
                                else val = (lNorm < 0.5) ? pColor[i] : pAltColor[i];
                            }
                        }
                    }
                }

                // Symmetric jewels
                if (hasJewels) {
                    double targetA = jewelOnDiag ? 1.0 : 0.0;
                    double dJewel = Math.hypot(r - jewelR, (normA - targetA) * jewelR * (Math.PI / sym));
                    if (dJewel < 0.055) val = ACCENT;
                    else if (dJewel < 0.095) val = TRIM;
                }

                // Centerpiece
                switch (centerStyle) {
                    case 0 -> {
                        if (Math.abs(dx) + Math.abs(dz) < 0.11) val = ACCENT;
                        else if (Math.abs(dx) + Math.abs(dz) < 0.17) val = TRIM;
                    }
                    case 1 -> {
                        double cStar = 0.18 * (0.55 + 0.45 * (1.0 - normA));
                        if (r < 0.07) val = ACCENT;
                        else if (r < cStar) val = PRIMARY;
                    }
                    default -> {
                        if (r < 0.08) val = ACCENT;
                        else if (r < 0.15) val = TRIM;
                    }
                }

                grid[z][x] = val;
            }
        }

        // Auto-outline pass: crisp 1-block trim where PRIMARY meets BASE
        if (autoOutline) {
            boolean[][] outlineCells = new boolean[n][n];
            for (int z = 1; z < n - 1; z++) {
                for (int x = 1; x < n - 1; x++) {
                    if (grid[z][x] != BASE) continue;
                    if (grid[z - 1][x] == PRIMARY || grid[z + 1][x] == PRIMARY
                            || grid[z][x - 1] == PRIMARY || grid[z][x + 1] == PRIMARY) {
                        outlineCells[z][x] = true;
                    }
                }
            }
            for (int z = 1; z < n - 1; z++)
                for (int x = 1; x < n - 1; x++)
                    if (outlineCells[z][x]) grid[z][x] = TRIM;
        }
    }

    /** Draws a crisp 1-block trim outline on any filled cell adjacent to an empty cell or grid edge. */
    private static void applyBorder(int[][] grid) {
        int n = grid.length;
        boolean[][] edge = new boolean[n][n];
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                if (grid[z][x] == EMPTY) continue;
                if (x == 0 || z == 0 || x == n - 1 || z == n - 1
                        || grid[z - 1][x] == EMPTY || grid[z + 1][x] == EMPTY
                        || grid[z][x - 1] == EMPTY || grid[z][x + 1] == EMPTY) {
                    edge[z][x] = true;
                }
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                if (edge[z][x]) grid[z][x] = TRIM;
            }
        }
    }
}

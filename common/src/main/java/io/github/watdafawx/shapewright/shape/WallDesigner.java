package io.github.watdafawx.shapewright.shape;

import java.util.Random;

/**
 * Procedural multi-block vertical wall/facade bay generator: produces an {@code int[height][width]}
 * grid (row 0 = top, row height-1 = bottom) of palette indices ({@code -1} = empty/window opening,
 * {@code 0} = base wall, {@code 1} = primary/pillars, {@code 2} = secondary/infill,
 * {@code 3} = trim/frame, {@code 4} = accent/glass/light). Pure math, shared and unit-testable.
 */
public final class WallDesigner {

    public static final int EMPTY = -1;
    public static final int BASE = 0;
    public static final int PRIMARY = 1;
    public static final int SECONDARY = 2;
    public static final int TRIM = 3;
    public static final int ACCENT = 4;

    public enum Style {
        RANDOM("random"),
        MEDIEVAL_BAY("medieval_bay"),
        GOTHIC_ARCH("gothic_arch"),
        CASTLE_WALL("castle_wall"),
        CLASSICAL("classical"),
        TIMBER_FRAME("timber_frame");

        public final String key;
        Style(String key) { this.key = key; }
        public String getNameKey() { return "shapewright.wall_designer.style." + key; }
    }

    public record Config(Style style, int width, int height, boolean openWindow, boolean battlements, int seed) {
        public Config {
            width = Math.max(7, Math.min(35, width | 1));
            height = Math.max(7, Math.min(35, height));
        }

        public static Config defaults() {
            return new Config(Style.MEDIEVAL_BAY, 13, 15, true, false, 1);
        }
    }

    private WallDesigner() {}

    /** Generates {@code int[height][width]} where {@code y = 0} is the top of the wall. */
    public static int[][] generate(Config cfg) {
        int w = cfg.width(), h = cfg.height();
        int[][] grid = new int[h][w];
        Random rng = new Random(cfg.seed() * 0x9E3779B97F4A7C15L + cfg.style().ordinal() * 31L);

        switch (cfg.style()) {
            case MEDIEVAL_BAY -> medievalBay(grid, cfg);
            case GOTHIC_ARCH -> gothicArch(grid, cfg);
            case CASTLE_WALL -> castleWall(grid, cfg);
            case CLASSICAL -> classical(grid, cfg);
            case TIMBER_FRAME -> timberFrame(grid, cfg);
            case RANDOM -> randomFacade(grid, cfg, rng);
        }

        if (cfg.battlements()) {
            applyBattlements(grid);
        }
        return grid;
    }

    private static void fill(int[][] g, int v) {
        for (int y = 0; y < g.length; y++)
            for (int x = 0; x < g[0].length; x++) g[y][x] = v;
    }

    /** 1. Medieval Bay: stone plinth, corner pilasters, arched window with sill/lintel, top cornice. */
    private static void medievalBay(int[][] g, Config cfg) {
        int h = g.length, w = g[0].length, cx = w / 2;
        fill(g, BASE);
        int plinthH = Math.max(2, h / 5);
        int corniceY = 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Foundation plinth
                if (y >= h - plinthH) {
                    g[y][x] = (y == h - plinthH) ? TRIM : SECONDARY;
                }
                // Corner pilasters
                if (x == 0 || x == w - 1) g[y][x] = PRIMARY;
                else if (x == 1 || x == w - 2) g[y][x] = (y >= h - plinthH) ? TRIM : TRIM;
                // Top cornice
                if (y == 0) g[y][x] = PRIMARY;
                else if (y == corniceY) g[y][x] = TRIM;
            }
        }
        // Center arched window
        int winW = Math.max(1, (w - 6) / 2);
        int winTop = corniceY + 2;
        int winBot = h - plinthH - 2;
        carveArchWindow(g, cx, winTop, winBot, winW, false, cfg.openWindow());
    }

    /** 2. Gothic Arch: tall pointed lancet window with stone tracery and buttress columns. */
    private static void gothicArch(int[][] g, Config cfg) {
        int h = g.length, w = g[0].length, cx = w / 2;
        fill(g, BASE);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x <= 1 || x >= w - 2) g[y][x] = (x == 0 || x == w - 1) ? PRIMARY : TRIM;
                if (y == h - 1 || y == h - 2) g[y][x] = PRIMARY;
                if (y == 0) g[y][x] = TRIM;
            }
        }
        int winW = Math.max(2, (w - 6) / 2);
        carveArchWindow(g, cx, 2, h - 4, winW, true, cfg.openWindow());
        // Vertical center mullion inside tall gothic window
        if (winW >= 2) {
            for (int y = 4; y <= h - 4; y++) g[y][cx] = TRIM;
        }
    }

    /** 3. Castle Wall: heavy coursed stone, corbelled top, arrow slit embrasure. */
    private static void castleWall(int[][] g, Config cfg) {
        int h = g.length, w = g[0].length, cx = w / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean course = (y % 3 == 0);
                g[y][x] = course ? SECONDARY : BASE;
                if (x == 0 || x == w - 1) g[y][x] = PRIMARY;
                if (y >= h - 2) g[y][x] = PRIMARY;
                if (y == 1) g[y][x] = (x % 2 == 0) ? TRIM : PRIMARY; // corbels
                if (y == 0) g[y][x] = PRIMARY;
            }
        }
        // Arrow slit in center
        int slitTop = Math.max(3, h / 3);
        int slitBot = Math.min(h - 4, (h * 2) / 3 + 1);
        int slitVal = cfg.openWindow() ? EMPTY : ACCENT;
        for (int y = slitTop; y <= slitBot; y++) {
            g[y][cx] = slitVal;
            if (cx - 1 >= 2) g[y][cx - 1] = TRIM;
            if (cx + 1 <= w - 3) g[y][cx + 1] = TRIM;
        }
        int mid = (slitTop + slitBot) / 2;
        if (cx - 1 >= 2) g[mid][cx - 1] = slitVal;
        if (cx + 1 <= w - 3) g[mid][cx + 1] = slitVal;
    }

    /** 4. Classical: plinth, fluted columns, entablature frieze with accent triglyphs. */
    private static void classical(int[][] g, Config cfg) {
        int h = g.length, w = g[0].length, cx = w / 2;
        fill(g, BASE);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Entablature top
                if (y == 0) g[y][x] = PRIMARY;
                else if (y == 1) g[y][x] = (x % 3 == 0) ? ACCENT : TRIM;
                else if (y == 2) g[y][x] = PRIMARY;
                // Base plinth
                else if (y >= h - 2) g[y][x] = (y == h - 2) ? TRIM : PRIMARY;
                // Side columns
                if (y >= 2 && y < h - 2 && (x == 1 || x == 2 || x == w - 2 || x == w - 3)) {
                    g[y][x] = (x == 1 || x == w - 2) ? PRIMARY : SECONDARY;
                }
            }
        }
        // Central recessed niche or window
        int nw = Math.max(1, (w - 8) / 2);
        if (nw >= 1) carveArchWindow(g, cx, 4, h - 4, nw, false, cfg.openWindow());
    }

    /** 5. Timber Frame: Tudor half-timber beams, diagonal braces, mullioned window. */
    private static void timberFrame(int[][] g, Config cfg) {
        int h = g.length, w = g[0].length, cx = w / 2;
        fill(g, BASE);
        int baseH = Math.max(2, h / 4);
        int midBeam = (h - baseH) / 2;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y >= h - baseH) {
                    g[y][x] = (y == h - baseH) ? TRIM : SECONDARY;
                    continue;
                }
                // Timber frame posts & horizontal beams
                boolean beam = (y == 0 || y == midBeam || x == 0 || x == w - 1);
                if (beam) {
                    g[y][x] = TRIM;
                } else if (y < midBeam) {
                    // Diagonal braces in upper bay
                    int dy = y;
                    if (x == dy || (w - 1 - x) == dy) g[y][x] = PRIMARY;
                }
            }
        }
        // Rectangular mullioned window in middle
        int winW = Math.max(1, (w - 6) / 2);
        int winTop = Math.max(2, midBeam - 2);
        int winBot = Math.min(h - baseH - 2, midBeam + 2);
        int glass = cfg.openWindow() ? EMPTY : ACCENT;
        for (int y = winTop - 1; y <= winBot + 1; y++) {
            for (int x = cx - winW - 1; x <= cx + winW + 1; x++) {
                if (y < 1 || y >= h - baseH || x < 1 || x >= w - 1) continue;
                boolean frame = (y == winTop - 1 || y == winBot + 1 || x == cx - winW - 1 || x == cx + winW + 1);
                if (frame) g[y][x] = TRIM;
                else if (x == cx || y == midBeam) g[y][x] = PRIMARY;
                else g[y][x] = glass;
            }
        }
    }

    /** 6. Procedural Random Facade: seed-driven architectural bay composer. */
    private static void randomFacade(int[][] g, Config cfg, Random rng) {
        int h = g.length, w = g[0].length, cx = w / 2;
        fill(g, BASE);
        int plinthH = 1 + rng.nextInt(Math.max(1, h / 4));
        int pilasterW = 1 + rng.nextInt(2);
        boolean hasBeltCourse = rng.nextBoolean();
        int beltY = plinthH + (h - plinthH) / 2;
        boolean pointed = rng.nextBoolean();
        boolean hasFrieze = rng.nextBoolean();
        int topBand = hasFrieze ? 2 : 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y >= h - plinthH) g[y][x] = (y == h - plinthH) ? TRIM : SECONDARY;
                if (hasBeltCourse && y == h - beltY) g[y][x] = TRIM;
                if (x < pilasterW || x >= w - pilasterW) g[y][x] = (x == 0 || x == w - 1) ? PRIMARY : TRIM;
                if (y == 0) g[y][x] = PRIMARY;
                else if (hasFrieze && y == 1) g[y][x] = ((x & 1) == 0) ? ACCENT : TRIM;
            }
        }
        int winW = Math.max(1, Math.min((w - pilasterW * 2 - 4) / 2, 1 + rng.nextInt(4)));
        int winTop = topBand + 1 + rng.nextInt(2);
        int winBot = Math.max(winTop + 2, h - plinthH - 2);
        carveArchWindow(g, cx, winTop, winBot, winW, pointed, cfg.openWindow());
    }

    private static void carveArchWindow(int[][] g, int cx, int top, int bot, int halfW, boolean pointed, boolean open) {
        int h = g.length, w = g[0].length;
        if (bot <= top + 1 || halfW < 1) return;
        int springY = Math.min(bot, top + halfW + (pointed ? 1 : 0));
        int fillVal = open ? EMPTY : ACCENT;

        for (int y = top - 1; y <= bot + 1; y++) {
            for (int x = cx - halfW - 1; x <= cx + halfW + 1; x++) {
                if (y < 0 || y >= h || x < 0 || x >= w) continue;
                int dx = Math.abs(x - cx);
                boolean inside;
                boolean border;
                if (y >= springY && y <= bot) {
                    inside = dx <= halfW;
                    border = dx == halfW + 1;
                } else if (y < springY && y >= top) {
                    double t = (double) (springY - y) / Math.max(1, springY - top);
                    double maxDx = pointed ? halfW * (1.0 - t) : halfW * Math.sqrt(Math.max(0, 1.0 - t * t));
                    inside = dx <= maxDx + 0.15;
                    border = !inside && dx <= maxDx + 1.25;
                } else {
                    inside = false;
                    border = (y == bot + 1 && dx <= halfW + 1) || (y == top - 1 && dx <= 1);
                }
                if (inside) g[y][x] = fillVal;
                else if (border) g[y][x] = TRIM;
            }
        }
    }

    /** Carves alternating crenellations into the top row. */
    private static void applyBattlements(int[][] g) {
        int w = g[0].length;
        for (int x = 0; x < w; x++) {
            if ((x % 3) == 1 && x > 0 && x < w - 1) g[0][x] = EMPTY;
            else g[0][x] = PRIMARY;
        }
    }
}

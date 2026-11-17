package io.github.watdafawx.shapewright;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One-time move of data saved under the old mod id ("effortlessbuilding", before this mod became Shapewright)
 * to the new names. Never overwrites: if the new file or folder already exists the old one is left alone.
 */
public final class LegacyMigration {

    public static final String OLD_ID = "effortlessbuilding";

    private static boolean clientDone;

    private LegacyMigration() {}

    /** Moves {@code config/effortlessbuilding-*} files and the {@code config/effortlessbuilding} folder. Safe to call often. */
    public static synchronized void migrateClient() {
        if (clientDone) return;
        clientDone = true;
        Path config = Path.of("config");
        if (!Files.isDirectory(config)) return;
        List<Path> old = new ArrayList<>();
        try (Stream<Path> s = Files.list(config)) {
            s.filter(p -> p.getFileName().toString().startsWith(OLD_ID)).forEach(old::add);
        } catch (IOException e) {
            Constants.LOG.warn("Could not scan config folder for old files", e);
            return;
        }
        for (Path p : old) {
            String name = p.getFileName().toString();
            move(p, config.resolve(Constants.MOD_ID + name.substring(OLD_ID.length())));
        }
    }

    /** Moves {@code <world>/effortlessbuilding} to {@code <world>/shapewright}. */
    public static void migrateWorld(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        move(root.resolve(OLD_ID), root.resolve(Constants.MOD_ID));
    }

    private static void move(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) return;
        try {
            Files.move(from, to);
            Constants.LOG.info("Moved {} to {}", from, to);
        } catch (IOException e) {
            Constants.LOG.warn("Could not move {} to {}", from, to, e);
        }
    }
}

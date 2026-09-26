package nl.requios.effortlessbuilding.shape;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads Sponge {@code .schem} files (WorldEdit's format, versions 2 and 3) as shapes.
 * Only the positions of non-air blocks are used; the player's held block is placed at each.
 * <p>
 * Client and server each read their own folders (relative to the game or server directory), so in
 * multiplayer a schematic must exist on both: the client for the preview, the server for placing.
 */
public final class SchematicLibrary {

    /** Searched in order; a name found in an earlier folder wins. */
    public static final List<Path> FOLDERS = List.of(
            Path.of("config", Constants.MOD_ID, "schematics"),
            Path.of("schematics"),
            Path.of("config", "worldedit", "schematics"));

    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    private record Loaded(long modified, List<Cell> cells) {}
    private static final Map<Path, Loaded> cache = new HashMap<>();

    private SchematicLibrary() {}

    /** Schematic names (file names without extension) available locally, sorted. */
    public static List<String> list() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path folder : FOLDERS) {
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> files = Files.list(folder)) {
                files.map(f -> f.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".schem"))
                        .forEach(n -> names.add(n.substring(0, n.length() - ".schem".length())));
            } catch (IOException e) {
                Constants.LOG.warn("[EffortlessBuilding] Cannot list schematics in {}", folder, e);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Block offsets of the named schematic: x/z centered on its footprint, y = 0 at its bottom,
     * sorted bottom layer first. Empty when the file is missing or unreadable.
     * The size limit is applied by {@link ShapeGenerator}.
     */
    public static List<Cell> cells(String name) {
        return cells(name, Integer.MAX_VALUE);
    }

    /** Like {@link #cells(String)}, dropping blocks further than {@code maxAxis} from the anchor along any axis. */
    public static synchronized List<Cell> cells(String name, int maxAxis) {
        Path file = find(name);
        if (file == null) return List.of();
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Loaded loaded = cache.get(file);
            if (loaded == null || loaded.modified() != modified) {
                loaded = new Loaded(modified, read(file));
                cache.put(file, loaded);
            }
            int half = (maxAxis - 1) / 2;
            return loaded.cells().stream()
                    .filter(c -> Math.abs(c.x()) <= half && c.y() < maxAxis && Math.abs(c.z()) <= half)
                    .toList();
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot read schematic {}", file, e);
            return List.of();
        }
    }

    private static Path find(String name) {
        // Names come from the network: accept plain file names only, never paths
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        for (Path folder : FOLDERS) {
            Path file = folder.resolve(name + ".schem");
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private static List<Cell> read(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
        // Version 3 nests everything under "Schematic" and moves blocks into "Blocks"
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) root = root.getCompound("Schematic");
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        CompoundTag blocks = root.contains("Blocks", Tag.TAG_COMPOUND) ? root.getCompound("Blocks") : root;
        CompoundTag palette = blocks.getCompound("Palette");
        byte[] data = blocks.contains("Data") ? blocks.getByteArray("Data") : blocks.getByteArray("BlockData");

        Set<Integer> air = new HashSet<>();
        for (String key : palette.getAllKeys()) {
            String id = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
            if (AIR.contains(id)) air.add(palette.getInt(key));
        }

        List<Cell> cells = new ArrayList<>();
        int index = 0, pos = 0, total = width * height * length;
        int offsetX = width / 2, offsetZ = length / 2;
        while (pos < data.length && index < total) {
            // Palette indices are varints
            int value = 0, shift = 0, b;
            do {
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);

            if (!air.contains(value)) {
                int y = index / (width * length);
                int rest = index % (width * length);
                cells.add(new Cell(rest % width - offsetX, y, rest / width - offsetZ));
            }
            index++;
        }
        return cells;
    }
}

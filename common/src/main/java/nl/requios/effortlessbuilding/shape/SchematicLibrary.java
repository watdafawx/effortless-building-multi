package nl.requios.effortlessbuilding.shape;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.ListTag;
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
 * Reads Sponge {@code .schem} files (WorldEdit's format, versions 2 and 3) and vanilla structure
 * {@code .nbt} files (saved by structure blocks) as shapes.
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
            Path.of("config", "worldedit", "schematics"),
            // Where structure blocks save on a server with the default world name
            Path.of("world", "generated", "minecraft", "structures"));

    private static final String SCHEM = ".schem", NBT = ".nbt";
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:structure_void");

    /** A read file: each non-air block's offset and its block state as written in the file. */
    private static final class Loaded {
        final long modified;
        final Map<Cell, String> blocks;
        final List<Cell> cells;
        Map<Cell, BlockState> states; // parsed on first use

        Loaded(long modified, Map<Cell, String> blocks) {
            this.modified = modified;
            this.blocks = blocks;
            this.cells = List.copyOf(blocks.keySet());
        }
    }

    private static final Map<Path, Loaded> cache = new HashMap<>();

    private SchematicLibrary() {}

    /**
     * Schematic names available locally, sorted: {@code .schem} files without their extension,
     * {@code .nbt} structure files with it (so both kinds can share a name).
     */
    public static List<String> list() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path folder : FOLDERS) {
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> files = Files.list(folder)) {
                files.map(f -> f.getFileName().toString()).forEach(n -> {
                    String lower = n.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(SCHEM)) names.add(n.substring(0, n.length() - SCHEM.length()));
                    else if (lower.endsWith(NBT)) names.add(n);
                });
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
        Loaded loaded = load(name);
        if (loaded == null) return List.of();
        int half = (maxAxis - 1) / 2;
        return loaded.cells.stream()
                .filter(c -> Math.abs(c.x()) <= half && c.y() < maxAxis && Math.abs(c.z()) <= half)
                .toList();
    }

    /**
     * The blocks the schematic was saved with, by offset (same offsets as {@link #cells(String)}).
     * Blocks from mods that are not installed are left out. Empty when the file is missing.
     */
    public static synchronized Map<Cell, BlockState> states(String name) {
        Loaded loaded = load(name);
        if (loaded == null) return Map.of();
        if (loaded.states == null) {
            Map<String, BlockState> parsed = new HashMap<>();
            Map<Cell, BlockState> states = new HashMap<>();
            for (var e : loaded.blocks.entrySet()) {
                BlockState state = parsed.computeIfAbsent(e.getValue(), SchematicLibrary::parse);
                if (state != null) states.put(e.getKey(), state);
            }
            loaded.states = Map.copyOf(states);
        }
        return loaded.states;
    }

    private static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (Exception e) {
            return null; // block from a mod that is not installed, or a typo in the file
        }
    }

    private static Loaded load(String name) {
        Path file = find(name);
        if (file == null) return null;
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Loaded loaded = cache.get(file);
            if (loaded == null || loaded.modified != modified) {
                loaded = new Loaded(modified, read(file));
                cache.put(file, loaded);
            }
            return loaded;
        } catch (IOException | RuntimeException e) {
            Constants.LOG.warn("[EffortlessBuilding] Cannot read schematic {}", file, e);
            return null;
        }
    }

    private static Path find(String name) {
        // Names come from the network: accept plain file names only, never paths
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        String fileName = name.toLowerCase(Locale.ROOT).endsWith(NBT) ? name : name + SCHEM;
        for (Path folder : FOLDERS) {
            Path file = folder.resolve(fileName);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private static Map<Cell, String> read(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64L * 1024 * 1024));
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(NBT)) return readStructure(root);
        // Version 3 nests everything under "Schematic" and moves blocks into "Blocks"
        if (root.contains("Schematic", Tag.TAG_COMPOUND)) root = root.getCompound("Schematic");
        int width = root.getShort("Width") & 0xFFFF;
        int height = root.getShort("Height") & 0xFFFF;
        int length = root.getShort("Length") & 0xFFFF;
        CompoundTag blocks = root.contains("Blocks", Tag.TAG_COMPOUND) ? root.getCompound("Blocks") : root;
        CompoundTag palette = blocks.getCompound("Palette");
        byte[] data = blocks.contains("Data") ? blocks.getByteArray("Data") : blocks.getByteArray("BlockData");

        // Palette: block state string -> index
        Map<Integer, String> states = new HashMap<>();
        for (String key : palette.getAllKeys()) {
            String id = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
            if (!AIR.contains(id)) states.put(palette.getInt(key), key);
        }

        Map<Cell, String> cells = new LinkedHashMap<>();
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

            String state = states.get(value);
            if (state != null) {
                int y = index / (width * length);
                int rest = index % (width * length);
                cells.put(new Cell(rest % width - offsetX, y, rest / width - offsetZ), state);
            }
            index++;
        }
        return cells;
    }

    /** Vanilla structure file: a size, a block-state palette and a list of {pos, state} blocks. */
    private static Map<Cell, String> readStructure(CompoundTag root) {
        ListTag size = root.getList("size", Tag.TAG_INT);
        int offsetX = size.getInt(0) / 2, offsetZ = size.getInt(2) / 2;
        // Structures with random variants (like shipwrecks) keep several palettes; use the first
        ListTag palette = root.contains("palette", Tag.TAG_LIST) ? root.getList("palette", Tag.TAG_COMPOUND)
                : root.getList("palettes", Tag.TAG_LIST).getList(0);
        // Palette entries are {Name, Properties{...}}; turn them into "name[key=value,...]" strings
        List<String> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            String name = entry.getString("Name");
            if (AIR.contains(name)) { states.add(null); continue; }
            CompoundTag props = entry.getCompound("Properties");
            if (props.isEmpty()) { states.add(name); continue; }
            StringJoiner joined = new StringJoiner(",", name + "[", "]");
            for (String key : props.getAllKeys()) joined.add(key + "=" + props.getString(key));
            states.add(joined.toString());
        }
        List<Map.Entry<Cell, String>> found = new ArrayList<>();
        ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            int index = block.getInt("state");
            String state = index >= 0 && index < states.size() ? states.get(index) : null;
            if (state == null) continue;
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            found.add(Map.entry(new Cell(pos.getInt(0) - offsetX, pos.getInt(1), pos.getInt(2) - offsetZ), state));
        }
        found.sort(Comparator.comparing((Map.Entry<Cell, String> e) -> e.getKey().y())
                .thenComparing(e -> e.getKey().z()).thenComparing(e -> e.getKey().x()));
        Map<Cell, String> cells = new LinkedHashMap<>();
        for (var e : found) cells.put(e.getKey(), e.getValue());
        return cells;
    }
}

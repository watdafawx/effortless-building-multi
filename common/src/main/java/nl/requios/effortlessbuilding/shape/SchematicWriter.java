package nl.requios.effortlessbuilding.shape;

import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Saves blocks as a schematic: Sponge {@code .schem} (version 2, read by WorldEdit, Litematica's
 * importer and this mod) or a vanilla structure {@code .nbt} (structure blocks, Create's schematicannon).
 */
public final class SchematicWriter {

    private SchematicWriter() {}

    /** Writes .nbt when the file name ends with it, .schem otherwise. */
    public static void write(Path file, Map<Cell, BlockState> blocks) throws IOException {
        if (blocks.isEmpty()) throw new IOException("Nothing to save");
        Files.createDirectories(file.getParent());
        CompoundTag root = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt")
                ? structure(blocks) : sponge(blocks);
        NbtIo.writeCompressed(root, file);
    }

    private static int dataVersion() {
        return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    private static int[] bounds(Map<Cell, BlockState> blocks) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell c : blocks.keySet()) {
            b[0] = Math.min(b[0], c.x()); b[1] = Math.min(b[1], c.y()); b[2] = Math.min(b[2], c.z());
            b[3] = Math.max(b[3], c.x()); b[4] = Math.max(b[4], c.y()); b[5] = Math.max(b[5], c.z());
        }
        return b;
    }

    private static CompoundTag sponge(Map<Cell, BlockState> blocks) {
        int[] b = bounds(blocks);
        int width = b[3] - b[0] + 1, height = b[4] - b[1] + 1, length = b[5] - b[2] + 1;
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++)
            for (int z = 0; z < length; z++)
                for (int x = 0; x < width; x++) {
                    BlockState state = blocks.get(new Cell(x + b[0], y + b[1], z + b[2]));
                    int index = state == null ? 0 : palette.computeIfAbsent(BlockStateParser.serialize(state), k -> palette.size());
                    // Palette indices are varints
                    while ((index & ~0x7F) != 0) { data.write((index & 0x7F) | 0x80); index >>>= 7; }
                    data.write(index);
                }
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach(paletteTag::putInt);

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", dataVersion());
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.putIntArray("Offset", new int[]{0, 0, 0});
        root.put("Palette", paletteTag);
        root.putInt("PaletteMax", palette.size());
        root.putByteArray("BlockData", data.toByteArray());
        root.put("BlockEntities", new ListTag());
        // Paste centered on the player, bottom at their feet (like this mod places it)
        CompoundTag meta = new CompoundTag();
        meta.putInt("WEOffsetX", b[0]);
        meta.putInt("WEOffsetY", 0);
        meta.putInt("WEOffsetZ", b[2]);
        root.put("Metadata", meta);
        return root;
    }

    private static CompoundTag structure(Map<Cell, BlockState> blocks) {
        int[] b = bounds(blocks);
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        ListTag blockList = new ListTag();
        List<Map.Entry<Cell, BlockState>> sorted = new ArrayList<>(blocks.entrySet());
        sorted.sort(Comparator.comparing((Map.Entry<Cell, BlockState> e) -> e.getKey().y())
                .thenComparing(e -> e.getKey().z()).thenComparing(e -> e.getKey().x()));
        for (var e : sorted) {
            int i = index.computeIfAbsent(e.getValue(), s -> { palette.add(s); return palette.size() - 1; });
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(e.getKey().x() - b[0], e.getKey().y() - b[1], e.getKey().z() - b[2]));
            block.putInt("state", i);
            blockList.add(block);
        }
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) paletteTag.add(stateTag(state));

        CompoundTag root = new CompoundTag();
        root.put("size", ints(b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1));
        root.put("palette", paletteTag);
        root.put("blocks", blockList);
        root.put("entities", new ListTag());
        root.putInt("DataVersion", dataVersion());
        return root;
    }

    private static CompoundTag stateTag(BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (!state.getValues().isEmpty()) {
            CompoundTag props = new CompoundTag();
            for (Property<?> p : state.getProperties()) props.putString(p.getName(), valueName(state, p));
            tag.put("Properties", props);
        }
        return tag;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int v : values) list.add(IntTag.valueOf(v));
        return list;
    }

    /** A file name made safe: letters, digits, dash, underscore and dot only. */
    public static String safeName(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return s.isEmpty() ? "shape" : s;
    }
}

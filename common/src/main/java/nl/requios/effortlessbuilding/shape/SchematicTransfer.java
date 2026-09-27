package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Packs a schematic's blocks (offset → block state string) into compact bytes for sending to the
 * server, and back. Pure Java, so it is unit tested. Decoding enforces limits: the bytes come from a client.
 */
public final class SchematicTransfer {

    /** Largest upload accepted, compressed. */
    public static final int MAX_BYTES = 4 << 20;
    /** Most blocks and distinct block states accepted in one schematic. */
    public static final int MAX_BLOCKS = 4_000_000, MAX_STATES = 16_384;
    /** Bytes per packet, below the 32 KiB client-to-server payload limit. */
    public static final int CHUNK = 30_000;

    private SchematicTransfer() {}

    public static byte[] encode(Map<Cell, String> blocks) {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (String s : blocks.values()) palette.putIfAbsent(s, palette.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(palette.size());
            for (String s : palette.keySet()) out.writeUTF(s);
            out.writeInt(blocks.size());
            for (var e : blocks.entrySet()) {
                out.writeShort(e.getKey().x());
                out.writeShort(e.getKey().y());
                out.writeShort(e.getKey().z());
                out.writeShort(palette.get(e.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The blocks, or null when the bytes are broken or over a limit. */
    public static Map<Cell, String> decode(byte[] data) {
        if (data.length > MAX_BYTES) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int states = in.readInt();
            if (states < 0 || states > MAX_STATES) return null;
            String[] palette = new String[states];
            for (int i = 0; i < states; i++) {
                palette[i] = in.readUTF();
                if (palette[i].length() > 1024) return null;
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_BLOCKS) return null;
            Map<Cell, String> blocks = new LinkedHashMap<>(Math.min(count, 1 << 16) * 2);
            for (int i = 0; i < count; i++) {
                Cell cell = new Cell(in.readShort(), in.readShort(), in.readShort());
                int index = in.readUnsignedShort();
                if (index >= states) return null;
                blocks.put(cell, palette[index]);
            }
            return blocks;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A fingerprint of the packed bytes, so an unchanged schematic is not sent twice. */
    public static long hash(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue() ^ ((long) data.length << 32);
    }
}

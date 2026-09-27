package nl.requios.effortlessbuilding.shape;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import nl.requios.effortlessbuilding.network.SchematicUploadC2SPacket;
import nl.requios.effortlessbuilding.platform.Services;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;

import java.util.*;

/**
 * Client side: before a build that uses schematics, sends the ones this server has not got yet
 * (or that changed since), so it can build from files that only exist on this computer.
 * Singleplayer reads the same folders, so nothing is sent there.
 */
public final class SchematicUploader {

    private static Object connection;
    /** Per schematic name: the local file version already sent on this connection. */
    private static final Map<String, Long> sent = new HashMap<>();

    private SchematicUploader() {}

    public static void ensure(ShapeParams params) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() || mc.getConnection() == null) return;
        if (mc.getConnection() != connection) {
            connection = mc.getConnection();
            sent.clear();
        }
        for (String name : SchematicLibrary.namesIn(params)) {
            long version = SchematicLibrary.version(name);
            if (version == -1 || Objects.equals(sent.get(name), version)) continue;
            Map<Cell, String> blocks = SchematicLibrary.blocks(name);
            if (blocks == null) continue;
            byte[] data = SchematicTransfer.encode(blocks);
            if (data.length > SchematicTransfer.MAX_BYTES || blocks.size() > SchematicTransfer.MAX_BLOCKS) {
                if (mc.player != null) mc.player.displayClientMessage(
                        Component.translatable("effortlessbuilding.message.schematic_too_big", name), false);
                sent.put(name, version); // don't retry every click
                continue;
            }
            long hash = SchematicTransfer.hash(data);
            int total = Math.max(1, (data.length + SchematicTransfer.CHUNK - 1) / SchematicTransfer.CHUNK);
            for (int i = 0; i < total; i++) {
                int from = i * SchematicTransfer.CHUNK;
                byte[] piece = Arrays.copyOfRange(data, from, Math.min(data.length, from + SchematicTransfer.CHUNK));
                Services.NETWORK.sendPayloadToServer(new SchematicUploadC2SPacket(name, hash, i, total, piece));
            }
            sent.put(name, version);
        }
    }
}

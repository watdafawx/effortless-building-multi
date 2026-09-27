package nl.requios.effortlessbuilding.shape;

import nl.requios.effortlessbuilding.Constants;
import nl.requios.effortlessbuilding.shape.ShapeGenerator.Cell;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * Server side: schematics players sent from their own computer, kept in memory per player until they
 * log out. Builds by that player use them in place of (or when there is no) file on the server.
 */
public final class SchematicUploads {

    /** Uploads kept per player; the least recently used one goes first. */
    private static final int PER_PLAYER = 8;

    /** A finished upload. */
    public record Upload(long hash, Map<Cell, String> blocks) {}

    private static final class Partial {
        final String name;
        final long hash;
        final int total;
        int next = 0;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Partial(String name, long hash, int total) {
            this.name = name;
            this.hash = hash;
            this.total = total;
        }
    }

    private static final Map<UUID, Map<String, Upload>> uploads = new HashMap<>();
    private static final Map<UUID, Partial> partials = new HashMap<>();

    private SchematicUploads() {}

    /** Adds one piece; the last piece checks and stores the whole schematic. Pieces arrive in order. */
    public static synchronized void receive(UUID player, String name, long hash, int index, int total, byte[] data) {
        int maxPieces = SchematicTransfer.MAX_BYTES / SchematicTransfer.CHUNK + 1;
        if (total < 1 || total > maxPieces || index < 0 || index >= total) return;
        Partial partial = partials.get(player);
        if (index == 0) {
            partial = new Partial(name, hash, total);
            partials.put(player, partial);
        }
        if (partial == null || !partial.name.equals(name) || partial.hash != hash || partial.total != total || partial.next != index) {
            partials.remove(player); // out of order or mixed up: drop it, the client sends again next build
            return;
        }
        partial.bytes.writeBytes(data);
        partial.next++;
        if (partial.bytes.size() > SchematicTransfer.MAX_BYTES) {
            partials.remove(player);
            return;
        }
        if (partial.next < total) return;

        partials.remove(player);
        byte[] all = partial.bytes.toByteArray();
        Map<Cell, String> blocks = SchematicTransfer.hash(all) == hash ? SchematicTransfer.decode(all) : null;
        if (blocks == null) {
            Constants.LOG.warn("[EffortlessBuilding] Rejected schematic upload '{}' from {}", name, player);
            return;
        }
        Map<String, Upload> mine = uploads.computeIfAbsent(player, k -> new LinkedHashMap<>(16, 0.75f, true));
        mine.put(name, new Upload(hash, blocks));
        while (mine.size() > PER_PLAYER) mine.remove(mine.keySet().iterator().next());
    }

    public static synchronized @Nullable Upload get(UUID player, String name) {
        Map<String, Upload> mine = uploads.get(player);
        return mine == null ? null : mine.get(name);
    }

    public static synchronized void clear(UUID player) {
        uploads.remove(player);
        partials.remove(player);
        SchematicLibrary.forget(player);
    }
}

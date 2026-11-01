package nl.requios.effortlessbuilding.utilities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Short text codes for sharing designs in chat: a prefix naming the kind ("EBS1:" shape, "EBP1:" palette)
 * followed by gzipped JSON in URL-safe Base64.
 */
public final class ShareCode {

    public static final String SHAPE = "EBS1:", PALETTE = "EBP1:";
    private static final Gson GSON = new Gson();

    private ShareCode() {}

    public static String encode(String prefix, JsonObject json) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            }
            return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory streams do not fail
        }
    }

    /** The JSON in a code of the given kind, or null if the text is not such a code. */
    public static @Nullable JsonObject decode(String prefix, String code) {
        String text = code.trim();
        if (!text.startsWith(prefix)) return null;
        try {
            byte[] data = Base64.getUrlDecoder().decode(text.substring(prefix.length()));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
                // Codes are small; refuse anything that unpacks to something huge
                byte[] json = gzip.readNBytes(1 << 20);
                return GSON.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}

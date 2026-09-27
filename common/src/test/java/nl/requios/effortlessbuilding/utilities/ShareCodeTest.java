package nl.requios.effortlessbuilding.utilities;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShareCodeTest {

    @Test
    void roundTripsAndChecksTheKind() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "GEAR");
        json.addProperty("size", 12);
        String code = ShareCode.encode(ShareCode.SHAPE, json);
        assertTrue(code.startsWith("EBS1:"));
        assertFalse(code.contains(" ") || code.contains("+") || code.contains("/"), "chat-safe characters only");
        assertEquals(json, ShareCode.decode(ShareCode.SHAPE, "  " + code + "\n"));
        assertNull(ShareCode.decode(ShareCode.PALETTE, code), "a shape code is not a palette code");
        assertNull(ShareCode.decode(ShareCode.SHAPE, "EBS1:not-a-code"));
    }
}

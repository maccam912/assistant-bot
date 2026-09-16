package com.assistantbot.think;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkMemoryConfigTest {
    @Test void historyIsBoundedExpiresAndSeparatesConsumedMessages() {
        var memory = new ThinkMemory();
        for (int i = 0; i < 20; i++) memory.add("x".repeat(600), i * 1000);
        var recent = memory.snapshot(20000, 0);
        assertEquals(12, recent.size());
        assertEquals(512, recent.get(0).getAsJsonObject().get("text").getAsString().length());
        assertEquals(2, memory.snapshot(20000, 18).size());
        assertEquals(0, memory.snapshot(150000, 0).size());
        assertEquals(20, memory.revision());
        memory.add(" ", 150000);
        assertEquals(20, memory.revision());
    }

    @Test void configHasDefaultsAndNeverPrintsKey() {
        var config = ThinkFixtures.config();
        assertEquals(1000, config.intervalMs());
        assertEquals("https://api.typesafe.ai/v1/systemone", config.endpoint().toString());
        assertFalse(config.toString().contains("test-key"));
        assertThrows(IllegalArgumentException.class, () -> TypesafeConfig.from(name -> null));
    }

    @Test void rejectsInvalidNumbersAndInsecureEndpoints() {
        for (var entry : Map.of("TYPESAFE_INTERVAL_MS", "0", "TYPESAFE_MIN_CONFIDENCE", "NaN",
                "TYPESAFE_TIMEOUT_MS", "oops", "TYPESAFE_SCAN_RADIUS", "10000",
                "TYPESAFE_URL", "http://example.com").entrySet()) {
            var values = Map.of("TYPESAFE_API_KEY", "test-key", entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> TypesafeConfig.from(values::get));
        }
    }
}

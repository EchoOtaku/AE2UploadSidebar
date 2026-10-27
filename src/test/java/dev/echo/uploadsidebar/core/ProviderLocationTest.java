package dev.echo.uploadsidebar.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderLocationTest {
    @Test void readsTheExisting04IdentityWithoutNeedingNameOrLabel() {
        var key = ProviderIdentity.block("minecraft:overworld", "ae2:pattern_provider", -21, 65, 34).key();
        assertEquals(new ProviderLocation("minecraft:overworld", "ae2:pattern_provider", -21, 65, 34, ""),
            ProviderLocation.parse(key).orElseThrow());
        for (String face : List.of("down", "up", "north", "south", "west", "east")) {
            var part = ProviderIdentity.part("example:orbit/moon", "ae2:cable_pattern_provider", 1, -60, 3, face);
            assertEquals(face, ProviderLocation.parse(part.key()).orElseThrow().side());
        }
    }

    @Test void refusesUnknownVersionsAmbiguousFieldsAndNonCanonicalCoordinates() {
        for (String key : List.of("", "minecraft:overworld (1, 2, 3)",
            "v2|block|minecraft:overworld|ae2:pattern_provider|1|2|3",
            "v1|block|minecraft:overworld|ae2:pattern_provider|1|2|3|north",
            "v1|part|minecraft:overworld|ae2:cable_pattern_provider|1|2|3",
            "v1|part|minecraft:overworld|ae2:cable_pattern_provider|1|2|3|diagonal",
            "v1|block|minecraft:overworld|ae2:pattern_provider|01|2|3",
            "v1|block|minecraft:overworld|ae2:pattern_provider|+1|2|3",
            "v1|block|minecraft:overworld|ae2:pattern_provider|1|2|3|",
            "v1|block|BAD:id|ae2:pattern_provider|1|2|3", "x".repeat(513))) {
            assertTrue(ProviderLocation.parse(key).isEmpty(), key);
        }
        assertTrue(ProviderLocation.parse(null).isEmpty());
    }

    @Test void refusesPositionsThatWouldAliasWhenPackedOrExceedMinecraftBounds() {
        for (int x : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE, -30_000_000, 30_000_000)) {
            assertTrue(ProviderLocation.parse(ProviderIdentity.block("minecraft:overworld", "ae2:pattern_provider", x, 70, 0).key()).isEmpty());
        }
        for (int y : List.of(-2049, 2048, Integer.MAX_VALUE)) {
            assertTrue(ProviderLocation.parse(ProviderIdentity.block("minecraft:overworld", "ae2:pattern_provider", 0, y, 0).key()).isEmpty());
        }
    }
}

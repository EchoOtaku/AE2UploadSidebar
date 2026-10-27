package dev.echo.uploadsidebar.core;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecipeMappingsTest {
    @TempDir Path directory;
    private RecipeMappings open() { return new RecipeMappings(directory.resolve("own.json"), directory.resolve("legacy.json")); }

    @Test void independentCreateEditDeleteAndReload() {
        var store = open();
        assertTrue(store.list().isEmpty());
        assertTrue(store.put("minecraft:crafting", "分子装配室"));
        assertEquals("分子装配室", open().resolveType("minecraft:crafting", "合成"));
        assertTrue(store.put("minecraft:crafting", "其他装配室"));
        assertEquals("其他装配室", open().resolveType("minecraft:crafting", "合成"));
        assertTrue(store.remove("minecraft:crafting"));
        assertTrue(open().list().isEmpty());
    }
    @Test void legacyImportWorksWithoutModAndNeverWritesLegacyOrReimportsDeletions() throws Exception {
        Path legacy = directory.resolve("legacy.json");
        String source = "{\"minecraft:crafting\":\"分子装配室\",\"Smelting\":\"熔炉\"}";
        Files.writeString(legacy, source);
        var store = open();
        assertEquals(2, store.list().size());
        assertEquals("熔炉", store.resolveProvider("SMELTING"));
        assertTrue(store.remove("smelting"));
        assertEquals(1, open().list().size());
        assertEquals(source, Files.readString(legacy));
    }
    @Test void ownFileTakesPriorityOverLegacy() throws Exception {
        assertTrue(open().put("crafting", "本地规则"));
        Files.writeString(directory.resolve("legacy.json"), "{\"crafting\":\"旧规则\"}");
        assertEquals("本地规则", open().resolveType("minecraft:crafting", "合成"));
    }
    @Test void corruptLegacyOrOwnFileFailsClosedWithoutOverwriting() throws Exception {
        for (String name : new String[] {"legacy.json", "own.json"}) {
            Files.writeString(directory.resolve(name), "{broken");
            var store = open();
            assertTrue(store.list().isEmpty());
            assertFalse(store.put("crafting", "机器"));
            assertEquals("{broken", Files.readString(directory.resolve(name)));
        }
    }
    @Test void externalEditsAndInvalidValuesArePreserved() throws Exception {
        var store = open();
        assertFalse(store.put("", "机器"));
        assertFalse(store.put("合成", "x\ny"));
        assertTrue(store.put("合成", "机器"));
        Files.writeString(directory.resolve("own.json"), "{}");
        assertFalse(store.put("合成", "覆盖"));
        assertEquals("{}", Files.readString(directory.resolve("own.json")));
    }
    @Test void explicitIdThenPathThenTitleAndSingleStepAliases() {
        var store = open();
        assertEquals("合成", store.resolveType("minecraft:crafting", "合成"));
        assertTrue(store.put("合成", "装配室"));
        assertEquals("装配室", store.resolveProvider(store.resolveType("minecraft:crafting", "合成")));
        assertTrue(store.put("crafting", "路径规则"));
        assertEquals("路径规则", store.resolveType("minecraft:crafting", "合成"));
        assertTrue(store.put("minecraft:crafting", "完整规则"));
        assertEquals("完整规则", store.resolveType("minecraft:crafting", "合成"));
        assertTrue(store.put("a", "b")); assertTrue(store.put("b", "a"));
        assertEquals("b", store.resolveProvider("a"));
    }
}

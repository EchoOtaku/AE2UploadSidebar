package dev.echo.uploadsidebar.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class MappingAutoUploadStoreTest {
    @TempDir Path directory;
    @Test void defaultsOffPersistsAndIsolatesWorldAndMappingContents() {
        Path file = directory.resolve("auto.properties");
        var store = new MappingAutoUploadStore(file);
        assertFalse(store.enabled("world", "crafting", "assembler"));
        assertTrue(store.set("world", "crafting", "assembler", true));
        store = new MappingAutoUploadStore(file);
        assertTrue(store.enabled("world", "crafting", "assembler"));
        assertFalse(store.enabled("server", "crafting", "assembler"));
        assertFalse(store.enabled("world", "smelting", "assembler"));
        assertFalse(store.enabled("world", "crafting", "different"));
        assertTrue(store.set("world", "crafting", "assembler", false));
        assertFalse(new MappingAutoUploadStore(file).enabled("world", "crafting", "assembler"));
    }
    @Test void corruptFileFailsClosedAndIsPreserved() throws Exception {
        Path file = directory.resolve("auto.properties");
        Files.writeString(file, "broken");
        var store = new MappingAutoUploadStore(file);
        assertFalse(store.enabled("world", "crafting", "assembler"));
        assertFalse(store.set("world", "crafting", "assembler", true));
        assertEquals("broken", Files.readString(file));
    }
}

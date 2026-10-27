package dev.echo.uploadsidebar.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderNotesStoreTest {
    @TempDir Path dir;

    @Test void notesAreIndependentByScopeAndPhysicalTargetAndSurviveReload() {
        Path file = dir.resolve("notes.properties");
        var store = new ProviderNotesStore(file);
        assertFalse(Files.exists(file));
        assertTrue(store.save("server-a", "location-1", "  终极粉碎工厂  "));
        assertTrue(store.save("server-a", "location-2", "洗矿机"));
        assertTrue(store.save("server-b", "location-1", "熔炉"));
        var reload = new ProviderNotesStore(file);
        assertEquals("终极粉碎工厂", reload.note("server-a", "location-1"));
        assertEquals("洗矿机", reload.note("server-a", "location-2"));
        assertEquals("熔炉", reload.note("server-b", "location-1"));
        assertTrue(reload.save("server-a", "location-1", ""));
        assertEquals("", new ProviderNotesStore(file).note("server-a", "location-1"));
        assertEquals("洗矿机", new ProviderNotesStore(file).note("server-a", "location-2"));
    }

    @Test void valuesAreBoundedByUnicodeCodePointsAndControlsCannotChangeUi() {
        var store = new ProviderNotesStore(dir.resolve("notes.properties"));
        assertTrue(store.save("world", "target", "🛠".repeat(64)));
        assertEquals(64, store.note("world", "target").codePointCount(0, 128));
        assertFalse(store.save("world", "target", "🛠".repeat(65)));
        assertFalse(store.save("world", "target", "abc\nother"));
        assertFalse(store.save("world", "target", "\u00a7khidden"));
        assertFalse(store.save("world", "target", "\ud800"));
        assertEquals("🛠".repeat(64), store.note("world", "target"));
    }

    @Test void corruptedFileIsNotOverwrittenAndNoPartialDataEscapes() throws Exception {
        Path file = dir.resolve("notes.properties");
        String broken = "format=1\nnote.bad=unsafe\n";
        Files.writeString(file, broken);
        var store = new ProviderNotesStore(file);
        assertFalse(store.available());
        assertFalse(store.save("server", "location", "note"));
        assertNotNull(store.error());
        assertEquals(broken, Files.readString(file));
    }

    @Test void writeFailureKeepsInMemoryValueAndOtherFiles() throws Exception {
        Path parentFile = dir.resolve("blocked");
        Files.writeString(parentFile, "keep");
        var store = new ProviderNotesStore(parentFile.resolve("notes.properties"));
        assertFalse(store.save("world", "target", "new"));
        assertEquals("", store.note("world", "target"));
        assertNotNull(store.error());
        assertEquals("keep", Files.readString(parentFile));
    }

    @Test void externalChangesAreNotSilentlyOverwritten() throws Exception {
        Path file = dir.resolve("notes.properties");
        var store = new ProviderNotesStore(file);
        assertTrue(store.save("world", "target", "first"));
        String external = Files.readString(file) + "\n# external edit\n";
        Files.writeString(file, external);
        assertFalse(store.save("world", "target", "second"));
        assertEquals("first", store.note("world", "target"));
        assertEquals(external, Files.readString(file));
    }

    @Test void malformedUtf8DoesNotGetSilentlyRewrittenAsReplacementCharacters() throws Exception {
        Path file = dir.resolve("notes.properties");
        byte[] prefix = ("format=1\nnote." + "a".repeat(64) + "=before").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] original = java.util.Arrays.copyOf(prefix, prefix.length + 1);
        original[original.length - 1] = (byte) 0xff;
        Files.write(file, original);
        var store = new ProviderNotesStore(file);
        assertFalse(store.available());
        assertFalse(store.save("world", "target", "new"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }
}

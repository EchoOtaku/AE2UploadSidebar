package dev.echo.uploadsidebar.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderIconsStoreTest {
    private static final String STONE = "{\"id\":\"minecraft:stone\",\"count\":1}";
    private static final String VARIANT = "{\"id\":\"minecraft:potion\",\"count\":1,\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:healing\"}}}";
    @TempDir Path dir;

    @Test void componentPayloadsSurviveReloadAndStayIndependentByScopeAndTarget() throws Exception {
        Path file = dir.resolve("icons.properties");
        Path notes = dir.resolve("ae2-upload-sidebar-notes.properties");
        Files.writeString(notes, "leave notes exactly unchanged");
        var store = new ProviderIconsStore(file);
        assertTrue(store.available());
        assertFalse(Files.exists(file));
        assertTrue(store.save("server-a", "location-1", VARIANT));
        assertTrue(store.save("server-a", "location-2", STONE));
        assertTrue(store.save("server-b", "location-1", STONE));
        var reload = new ProviderIconsStore(file);
        assertEquals(VARIANT, reload.icon("server-a", "location-1"));
        assertEquals(STONE, reload.icon("server-a", "location-2"));
        assertEquals(STONE, reload.icon("server-b", "location-1"));
        assertEquals("", reload.icon("server-c", "location-1"));
        assertEquals("leave notes exactly unchanged", Files.readString(notes));
        String content = Files.readString(file);
        assertFalse(content.contains("server-a") || content.contains("location-1"), "scope and identity are only hashed keys");
    }

    @Test void deletingOneIconRestoresDefaultWithoutAffectingOtherScopesOrRows() {
        Path file = dir.resolve("icons.properties");
        var store = new ProviderIconsStore(file);
        assertTrue(store.save("world", "first", VARIANT));
        assertTrue(store.save("world", "second", STONE));
        assertTrue(store.save("other-world", "first", STONE));
        assertTrue(store.save("world", "first", ""));
        var reload = new ProviderIconsStore(file);
        assertEquals("", reload.icon("world", "first"));
        assertEquals(STONE, reload.icon("world", "second"));
        assertEquals(STONE, reload.icon("other-world", "first"));
    }

    @Test void utf8ByteBudgetAndMalformedUtf16CannotSilentlyChangeSavedData() {
        var store = new ProviderIconsStore(dir.resolve("icons.properties"));
        String exactLimit = "x".repeat(ProviderIconsStore.MAX_ICON_BYTES);
        assertTrue(store.save("world", "target", exactLimit));
        assertFalse(store.save("world", "target", exactLimit + "x"));
        assertFalse(store.save("world", "target", "中".repeat(ProviderIconsStore.MAX_ICON_BYTES / 3 + 1)));
        assertFalse(store.save("world", "target", "\ud800"));
        assertEquals(exactLimit, store.icon("world", "target"));
        assertNotNull(store.error());
    }

    @Test void corruptPropertyStructureNeverOverwritesTheOriginalFile() throws Exception {
        Path file = dir.resolve("icons.properties");
        String original = "format=1\nicon.not-a-hash=" + STONE + "\n";
        Files.writeString(file, original);
        var store = new ProviderIconsStore(file);
        assertFalse(store.available());
        assertFalse(store.save("world", "target", VARIANT));
        assertEquals("", store.icon("world", "target"));
        assertNotNull(store.error());
        assertEquals(original, Files.readString(file));
    }

    @Test void malformedUtf8IsRejectedWithoutReplacementOrPartialReads() throws Exception {
        Path file = dir.resolve("icons.properties");
        byte[] prefix = ("format=1\nicon." + "a".repeat(64) + "=before").getBytes(StandardCharsets.UTF_8);
        byte[] original = Arrays.copyOf(prefix, prefix.length + 1);
        original[original.length - 1] = (byte) 0xff;
        Files.write(file, original);
        var store = new ProviderIconsStore(file);
        assertFalse(store.available());
        assertFalse(store.save("world", "target", STONE));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void failedWritesPreserveMemoryAndUnrelatedFiles() throws Exception {
        Path parentFile = dir.resolve("not-a-directory");
        Files.writeString(parentFile, "keep");
        var store = new ProviderIconsStore(parentFile.resolve("icons.properties"));
        assertFalse(store.save("world", "target", VARIANT));
        assertEquals("", store.icon("world", "target"));
        assertNotNull(store.error());
        assertEquals("keep", Files.readString(parentFile));
    }

    @Test void externalChangesAreNotOverwrittenAndMemoryOnlyCommitsAfterSuccess() throws Exception {
        Path file = dir.resolve("icons.properties");
        var store = new ProviderIconsStore(file);
        assertTrue(store.save("world", "target", VARIANT));
        String external = Files.readString(file) + "\n# external edit\n";
        Files.writeString(file, external);
        assertFalse(store.save("world", "target", STONE));
        assertEquals(VARIANT, store.icon("world", "target"));
        assertEquals(external, Files.readString(file));
    }

    @Test void collectionAndFileLimitsFailClosedBeforeAddingAnything() throws Exception {
        Path file = dir.resolve("icons.properties");
        Properties encoded = new Properties();
        encoded.setProperty("format", "1");
        for (int i = 0; i < ProviderIconsStore.MAX_ICONS; i++) encoded.setProperty("icon." + String.format("%064x", i), STONE);
        StringWriter writer = new StringWriter();
        encoded.store(writer, null);
        Files.writeString(file, writer.toString());
        var full = new ProviderIconsStore(file);
        assertTrue(full.available());
        assertFalse(full.save("world", "new-target", VARIANT));
        assertEquals(writer.toString(), Files.readString(file));
        encoded.setProperty("icon." + "f".repeat(64), STONE);
        writer = new StringWriter();
        encoded.store(writer, null);
        Files.writeString(file, writer.toString());
        assertFalse(new ProviderIconsStore(file).available());
        Files.write(file, new byte[ProviderIconsStore.MAX_FILE_BYTES + 1]);
        assertFalse(new ProviderIconsStore(file).available());
    }

    @Test void emptyOrAmbiguousScopeInputsCannotShareAKey() {
        var store = new ProviderIconsStore(dir.resolve("icons.properties"));
        assertFalse(store.save("", "location", STONE));
        assertFalse(store.save("world", "", STONE));
        assertTrue(store.save("a", "bc", VARIANT));
        assertTrue(store.save("ab", "c", STONE));
        assertEquals(VARIANT, store.icon("a", "bc"));
        assertEquals(STONE, store.icon("ab", "c"));
    }
}

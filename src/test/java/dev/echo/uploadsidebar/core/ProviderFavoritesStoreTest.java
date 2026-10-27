package dev.echo.uploadsidebar.core;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProviderFavoritesStoreTest {
    @TempDir Path directory;
    @Test void persistsAndIsolatesTargetsAndWorlds() {
        Path file = directory.resolve("favorites.properties");
        var store = new ProviderFavoritesStore(file);
        assertTrue(store.set("worldA", "provider1", true));
        var reloaded = new ProviderFavoritesStore(file);
        assertTrue(reloaded.favorite("worldA", "provider1"));
        assertFalse(reloaded.favorite("worldA", "provider2"));
        assertFalse(reloaded.favorite("worldB", "provider1"));
        assertTrue(reloaded.set("worldA", "provider1", false));
        assertFalse(new ProviderFavoritesStore(file).favorite("worldA", "provider1"));
        assertFalse(store.set("", "", true));
    }
    @Test void corruptOrExternallyEditedFileIsPreserved() throws Exception {
        Path file = directory.resolve("favorites.properties");
        Files.writeString(file, "broken");
        var store = new ProviderFavoritesStore(file);
        assertFalse(store.available());
        assertFalse(store.set("world", "provider", true));
        assertEquals("broken", Files.readString(file));
        Path other = directory.resolve("other.properties");
        var valid = new ProviderFavoritesStore(other);
        assertTrue(valid.set("world", "provider", true));
        Files.writeString(other, "external edit");
        assertFalse(valid.set("world", "provider", false));
        assertTrue(valid.favorite("world", "provider"));
        assertEquals("external edit", Files.readString(other));
    }
}

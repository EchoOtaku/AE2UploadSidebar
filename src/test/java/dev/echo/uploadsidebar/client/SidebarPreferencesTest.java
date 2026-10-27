package dev.echo.uploadsidebar.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.echo.uploadsidebar.core.PanelGeometry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidebarPreferencesTest {
    @TempDir Path directory;

    @Test void settingsRoundTripAndResetOnlyChangesPlacement() throws Exception {
        Path file = directory.resolve("client.properties");
        SidebarPreferences settings = SidebarPreferences.load(file);
        settings.moveTo(.75, .2);
        settings.setLocked(true);
        settings.setCollapsed(true);
        settings.setWidth(200);
        settings.setRows(7);
        settings.setTheme(SidebarPreferences.Theme.VANILLA);
        settings.save(file);
        var loaded = SidebarPreferences.load(file);
        assertEquals(.75, loaded.x());
        assertEquals(.2, loaded.y());
        assertTrue(loaded.locked());
        assertTrue(loaded.collapsed());
        assertEquals(200, loaded.width());
        assertEquals(7, loaded.rows());
        assertEquals(SidebarPreferences.Theme.VANILLA, loaded.theme());
        loaded.resetPosition();
        assertEquals(-1, loaded.x());
        assertEquals(-1, loaded.y());
        assertFalse(loaded.collapsed());
        assertFalse(loaded.locked());
        assertEquals(200, loaded.width());
        assertEquals(SidebarPreferences.Theme.VANILLA, loaded.theme());
    }

    @Test void invalidConfigFallsBackAndBoundsAreEnforced() throws Exception {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "width=9999\nrows=-2\nx=NaN\ny=Infinity\ntheme=unknown\nheightMode=unknown\n");
        var loaded = SidebarPreferences.load(file);
        assertEquals(PanelGeometry.MAX_WIDTH, loaded.width());
        assertEquals(1, loaded.rows());
        assertEquals(-1, loaded.x());
        assertEquals(-1, loaded.y());
        assertEquals(SidebarPreferences.Theme.AE2, loaded.theme());
        assertEquals(PanelGeometry.HeightMode.STANDARD, loaded.heightMode());
        loaded.setRows(12);
        loaded.setHeightMode(PanelGeometry.HeightMode.COMPACT);
        assertEquals(PanelGeometry.HeightMode.COMPACT, loaded.geometry().heightMode());
    }
}

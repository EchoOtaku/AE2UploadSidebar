package dev.echo.uploadsidebar.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SidebarSwitchTest {
    @TempDir Path directory;

    @Test void absentFileDefaultsOnWithoutCreatingConfiguration() {
        Path file = directory.resolve("switch.properties");
        var toggle = new SidebarSwitch(file);
        assertTrue(toggle.enabled());
        assertFalse(Files.exists(file));
    }

    @Test void offSurvivesRestartAndOnRestoresItWithoutTouchingUiPreferences() throws Exception {
        Path file = directory.resolve("switch.properties");
        Path ui = directory.resolve("ae2-upload-sidebar-client.properties");
        Files.writeString(ui, "width=200\nx=0.4\n");
        var toggle = new SidebarSwitch(file);
        assertTrue(toggle.setEnabled(false));
        assertFalse(toggle.enabled());
        var restarted = new SidebarSwitch(file);
        assertFalse(restarted.enabled());
        assertTrue(restarted.setEnabled(true));
        assertTrue(new SidebarSwitch(file).enabled());
        assertEquals("width=200\nx=0.4\n", Files.readString(ui));
    }

    @Test void brokenSavedSwitchFailsClosedAndCanBeRecoveredByCommand() throws Exception {
        Path file = directory.resolve("switch.properties");
        Files.writeString(file, "enabled=broken\n");
        var toggle = new SidebarSwitch(file);
        assertFalse(toggle.enabled());
        assertNotNull(toggle.persistenceError());
        assertTrue(toggle.setEnabled(true));
        assertNull(toggle.persistenceError());
        assertTrue(new SidebarSwitch(file).enabled());
    }

    @Test void unreadableConfigDoesNotPreventRuntimeDisableOrReportFalsePersistenceSuccess() throws Exception {
        Path blockedParent = directory.resolve("not-a-directory");
        Files.writeString(blockedParent, "keep");
        var toggle = new SidebarSwitch(blockedParent.resolve("switch.properties"));
        assertFalse(toggle.setEnabled(false));
        assertFalse(toggle.enabled());
        assertNotNull(toggle.persistenceError());
        assertEquals("keep", Files.readString(blockedParent));
    }

    @Test void malformedPropertiesAreContainedAndOffCanRepairThem() throws Exception {
        Path file = directory.resolve("switch.properties");
        Files.writeString(file, "enabled=\\uZZZZ\n");
        var toggle = new SidebarSwitch(file);
        assertFalse(toggle.enabled());
        assertNotNull(toggle.persistenceError());
        assertTrue(toggle.setEnabled(false));
        assertFalse(new SidebarSwitch(file).enabled());
    }
}

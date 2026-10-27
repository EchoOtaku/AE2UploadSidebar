package dev.echo.uploadsidebar.client;

import org.junit.jupiter.api.Test;

public final class SidebarKeyPolicyTest {
    @Test
    void hoveredPanelBlocksDropAndHotbarKeysWithoutSearchFocus() {
        // GLFW Q, then 1 through 9: these normally act on a container's hovered slot.
        for (int key : new int[] {81, 49, 50, 51, 52, 53, 54, 55, 56, 57}) {
            check(SidebarKeyPolicy.consumeOutsideSearch(true, key, false), "hovered panel must consume key " + key);
            check(!SidebarKeyPolicy.consumeOutsideSearch(false, key, false), "terminal keeps key " + key);
        }
        check(SidebarKeyPolicy.consumeOutsideSearch(true, 70, false), "offhand swap is also blocked");
    }

    @Test
    void escapeAndConfiguredInventoryKeyStillCloseTheTerminal() {
        check(!SidebarKeyPolicy.consumeOutsideSearch(true, 256, false), "Escape remains usable");
        check(!SidebarKeyPolicy.consumeOutsideSearch(true, 69, true), "default inventory close remains usable");
        check(!SidebarKeyPolicy.consumeOutsideSearch(true, 73, true), "remapped inventory close remains usable");
        check(SidebarKeyPolicy.consumeOutsideSearch(true, 69, false), "E has no exemption when remapped");
    }

    @Test
    void cursorCoordinatesRespectGuiScaleAndInvalidWindowSizes() {
        check(SidebarKeyPolicy.guiCoordinate(300, 720, 1440) == 150, "GUI scale 2");
        check(SidebarKeyPolicy.guiCoordinate(300, 480, 1440) == 100, "GUI scale 3");
        check(SidebarKeyPolicy.guiCoordinate(225, 450, 900) == 112.5, "vertical coordinate");
        check(SidebarKeyPolicy.guiCoordinate(-10, 720, 1440) < 0, "outside-window pointer is not clamped inside");
        check(SidebarKeyPolicy.guiCoordinate(100, 720, 0) == Double.NEGATIVE_INFINITY, "minimized window cannot hit panel");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        SidebarKeyPolicyTest tests = new SidebarKeyPolicyTest();
        tests.hoveredPanelBlocksDropAndHotbarKeysWithoutSearchFocus();
        tests.escapeAndConfiguredInventoryKeyStillCloseTheTerminal();
        tests.cursorCoordinatesRespectGuiScaleAndInvalidWindowSizes();
        System.out.println("SIDEBAR_KEY_POLICY_PASS");
    }
}

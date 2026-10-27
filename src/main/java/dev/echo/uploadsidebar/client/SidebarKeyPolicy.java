package dev.echo.uploadsidebar.client;

/** Pure input policy used before AE2 can act on a previously hovered inventory slot. */
final class SidebarKeyPolicy {
    private static final int ESCAPE = 256; // GLFW_KEY_ESCAPE, without a native dependency.

    private SidebarKeyPolicy() { }

    static boolean consumeOutsideSearch(boolean pointerInSidebar, int keyCode, boolean closesInventory) {
        return pointerInSidebar && keyCode != ESCAPE && !closesInventory;
    }

    static double guiCoordinate(double windowCoordinate, int guiSize, int windowSize) {
        return windowSize > 0 ? windowCoordinate * guiSize / windowSize : Double.NEGATIVE_INFINITY;
    }
}

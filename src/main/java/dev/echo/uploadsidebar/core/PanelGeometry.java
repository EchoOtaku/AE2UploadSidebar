package dev.echo.uploadsidebar.core;

/** GUI-coordinate layout; independent of Minecraft, configuration IO and rendering. */
public final class PanelGeometry {
    public static final int MIN_WIDTH = 136;
    public static final int MAX_WIDTH = 300;
    public static final int ROW_HEIGHT = 28;
    public static final int LIST_TOP = 73;
    public static final int FOOTER_HEIGHT = 36;
    public enum HeightMode { COMPACT, STANDARD, TERMINAL }

    public record Settings(double x, double y, int width, int rows, HeightMode heightMode,
            boolean collapsed, boolean forceExpanded) { }

    private PanelGeometry() { }

    public static SidebarSession.Layout layout(int screenWidth, int screenHeight, int guiLeft,
            int guiTop, int guiHeight, Settings settings) {
        int viewWidth = Math.max(1, screenWidth);
        int viewHeight = Math.max(1, screenHeight);
        boolean positioned = settings.x() >= 0 && settings.y() >= 0;
        int available = Math.min(viewWidth - 8, guiLeft - 30);
        boolean collapsed = settings.collapsed() || viewWidth < MIN_WIDTH
            || viewHeight < LIST_TOP + ROW_HEIGHT + FOOTER_HEIGHT
            || !positioned && !settings.forceExpanded() && available < MIN_WIDTH;
        int width = collapsed ? 84 : clamp(settings.width(), MIN_WIDTH, MAX_WIDTH);
        if (!collapsed && !positioned && !settings.forceExpanded()) width = Math.min(width, available);
        width = Math.min(width, viewWidth);
        int rows = settings.heightMode() == HeightMode.COMPACT ? 3 : clamp(settings.rows(), 1, 12);
        int height = collapsed ? 20 : settings.heightMode() == HeightMode.TERMINAL
            ? Math.max(LIST_TOP + ROW_HEIGHT + FOOTER_HEIGHT, guiHeight)
            : LIST_TOP + rows * ROW_HEIGHT + FOOTER_HEIGHT;
        height = Math.min(height, viewHeight);
        int x = positioned ? (int) Math.round(settings.x() * (viewWidth - width))
            : collapsed ? 4 : guiLeft - 26 - width;
        int y = positioned ? (int) Math.round(settings.y() * (viewHeight - height))
            : collapsed ? 4 : Math.max(4, guiTop);
        return new SidebarSession.Layout(clamp(x, 0, viewWidth - width),
            clamp(y, 0, viewHeight - height), width, height, collapsed);
    }

    public static int rowCount(SidebarSession.Layout box) {
        return box.collapsed() ? 0 : Math.max(1, (box.height() - LIST_TOP - FOOTER_HEIGHT) / ROW_HEIGHT);
    }

    public static double normalized(double coordinate, int viewport, int extent) {
        return viewport <= extent ? 0 : Math.max(0, Math.min(1, coordinate / (viewport - extent)));
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}

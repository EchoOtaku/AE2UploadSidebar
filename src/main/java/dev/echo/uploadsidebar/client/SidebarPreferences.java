package dev.echo.uploadsidebar.client;

import dev.echo.uploadsidebar.core.PanelGeometry;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Only our client preferences, resolved against the currently running game's config directory. */
public final class SidebarPreferences {
    public enum Theme { AE2, VANILLA }
    private double x = -1;
    private double y = -1;
    private int width = 152;
    private int rows = 5;
    private boolean collapsed;
    private boolean locked;
    private boolean forceExpanded;
    private PanelGeometry.HeightMode heightMode = PanelGeometry.HeightMode.STANDARD;
    private Theme theme = Theme.AE2;

    public static SidebarPreferences load(Path file) throws IOException {
        SidebarPreferences result = new SidebarPreferences();
        if (!Files.exists(file)) return result;
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        result.x = position(properties.getProperty("x"));
        result.y = position(properties.getProperty("y"));
        result.setWidth(integer(properties, "width", 152));
        result.rows = Math.max(1, Math.min(12, integer(properties, "rows", 5)));
        result.collapsed = Boolean.parseBoolean(properties.getProperty("collapsed", "false"));
        result.locked = Boolean.parseBoolean(properties.getProperty("locked", "false"));
        result.forceExpanded = Boolean.parseBoolean(properties.getProperty("forceExpanded", "false"));
        try { result.heightMode = PanelGeometry.HeightMode.valueOf(properties.getProperty("heightMode", "STANDARD")); }
        catch (IllegalArgumentException ignored) { }
        try { result.theme = Theme.valueOf(properties.getProperty("theme", "AE2")); }
        catch (IllegalArgumentException ignored) { }
        return result;
    }

    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("x", Double.toString(x));
        values.setProperty("y", Double.toString(y));
        values.setProperty("width", Integer.toString(width));
        values.setProperty("rows", Integer.toString(rows));
        values.setProperty("collapsed", Boolean.toString(collapsed));
        values.setProperty("locked", Boolean.toString(locked));
        values.setProperty("forceExpanded", Boolean.toString(forceExpanded));
        values.setProperty("heightMode", heightMode.name());
        values.setProperty("theme", theme.name());
        Path temporary = Files.createTempFile(target.getParent(), "ae2-sidebar-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "AE2 Upload Sidebar client preferences");
            }
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    public PanelGeometry.Settings geometry() {
        return new PanelGeometry.Settings(x, y, width, rows, heightMode, collapsed, forceExpanded);
    }
    public double x() { return x; }
    public double y() { return y; }
    public int width() { return width; }
    public int rows() { return rows; }
    public boolean collapsed() { return collapsed; }
    public boolean locked() { return locked; }
    public boolean forceExpanded() { return forceExpanded; }
    public PanelGeometry.HeightMode heightMode() { return heightMode; }
    public Theme theme() { return theme; }
    public void moveTo(double newX, double newY) {
        x = Double.isFinite(newX) ? Math.max(0, Math.min(1, newX)) : 0;
        y = Double.isFinite(newY) ? Math.max(0, Math.min(1, newY)) : 0;
    }
    public void setWidth(int value) { width = Math.max(PanelGeometry.MIN_WIDTH, Math.min(PanelGeometry.MAX_WIDTH, value)); }
    public void setRows(int value) { rows = Math.max(1, Math.min(12, value)); heightMode = PanelGeometry.HeightMode.STANDARD; }
    public void setHeightMode(PanelGeometry.HeightMode value) { heightMode = value; }
    public void setTheme(Theme value) { theme = value; }
    public void setLocked(boolean value) { locked = value; }
    public void setCollapsed(boolean value) { collapsed = value; if (!value) forceExpanded = true; }
    public void resetPosition() { x = -1; y = -1; collapsed = false; locked = false; forceExpanded = false; }

    private static int integer(Properties values, String key, int fallback) {
        try { return Integer.parseInt(values.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static double position(String value) {
        try { double parsed = Double.parseDouble(value); return Double.isFinite(parsed) ? Math.max(-1, Math.min(1, parsed)) : -1; }
        catch (NullPointerException | NumberFormatException ignored) { return -1; }
    }
}

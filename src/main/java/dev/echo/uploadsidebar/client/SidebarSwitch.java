package dev.echo.uploadsidebar.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Independent of panel construction and UI preferences so it remains usable during UI failures. */
public final class SidebarSwitch {
    private final Path file;
    private boolean enabled = true;
    private String persistenceError;

    public SidebarSwitch(Path file) {
        this.file = file.toAbsolutePath();
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            values.load(reader);
            String value = values.getProperty("enabled", "").strip();
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                throw new IOException("Invalid enabled value");
            enabled = Boolean.parseBoolean(value);
        } catch (NoSuchFileException missing) {
            // New installations are enabled, with no configuration write until a command is used.
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            enabled = false;
            persistenceError = error.toString();
        }
    }

    public boolean enabled() { return enabled; }
    public String persistenceError() { return persistenceError; }

    /** Applies in memory first. A disk error must never prevent the emergency stop. */
    public boolean setEnabled(boolean value) {
        enabled = value;
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "ae2-sidebar-switch-", ".tmp");
            Properties values = new Properties();
            values.setProperty("enabled", Boolean.toString(value));
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "AE2 Upload Sidebar runtime switch; use /ae2sidebar on or off");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            persistenceError = null;
            return true;
        } catch (IOException | SecurityException error) {
            persistenceError = error.toString();
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException ignored) { /* Do not undo a successful runtime stop. */ }
            }
        }
    }
}

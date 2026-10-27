package dev.echo.uploadsidebar.core;

import java.util.Optional;

/** One short-lived client waypoint. Menu closure/refresh does not erase it; world/connection changes do. */
public final class LocatorSession {
    public static final long DURATION_MILLIS = 60_000;
    public record Target(ProviderLocation location, String name, long expiresAt) { }
    private Target target;
    private Object connection;
    private Object level;

    public boolean toggle(ProviderLocation location, String name, Object connection, Object level, long now) {
        var current = current(connection, level, true, now);
        if (location == null || connection == null || level == null
                || current.map(value -> value.location().equals(location)).orElse(false)) {
            clear();
            return false;
        }
        this.connection = connection;
        this.level = level;
        target = new Target(location, name, now + DURATION_MILLIS);
        return true;
    }

    public Optional<Target> current(Object connection, Object level, boolean enabled, long now) {
        if (!enabled || connection == null || level == null || this.connection != connection || this.level != level
                || target != null && now >= target.expiresAt()) clear();
        return Optional.ofNullable(target);
    }

    public void clear() { target = null; connection = null; level = null; }
}
